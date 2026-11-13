package dev.apklab.runner;

import com.fasterxml.jackson.databind.JsonNode;
import dev.apklab.runner.session.SessionManager;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pieces of the screen on the synthetic Compose example: a plugin sent through the API that returns one component (its data, a PNG of just it with padding,
 * and a box of the window), GET screenshot.png?rect=, padding on an element, and POST /screenshot-pieces. Runs with APK_LAB_RUNTIME_DIR and APK_LAB_TEST_APK_DIR.
 */
@MicronautTest(environments = "e2e", startApplication = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "APK_LAB_RUNTIME_DIR", matches = ".+")
@EnabledIfEnvironmentVariable(named = "APK_LAB_TEST_APK_DIR", matches = ".+")
class ScreenPiecesEndToEndTest implements TestPropertyProvider {
    static final String TOKEN = "e2e-token";
    static final String PLUGIN = """
            const [busca] = await lab.find({ testTag: 'search' });
            lab.ui.kv({ key: busca.key, largura: Math.round(busca.size.width), altura: Math.round(busca.size.height) }, { id: 'dados' });
            lab.ui.image(await lab.capture({ testTag: 'search' }, { padding: 8 }), { id: 'componente', caption: 'Campo de busca' });
            lab.ui.image(await lab.screenshot({ bounds: { left: 0, top: 0, right: 200, bottom: 100 } }), { id: 'retangulo', caption: 'Retângulo' });
            """;
    @Inject EmbeddedServer server;
    @Inject SessionManager sessions;
    TestHttp http;

    @Override
    public Map<String, String> getProperties() {
        return Map.ofEntries(Map.entry("runner.runtime-dir", System.getenv("APK_LAB_RUNTIME_DIR")), Map.entry("runner.sidecar-script", "../sidecar/vm-host.mjs"), Map.entry("runner.token", TOKEN),
                Map.entry("runner.max-line-mb", "64"), Map.entry("runner.operation-timeout-seconds", "45"), Map.entry("runner.call-margin-seconds", "15"), Map.entry("runner.max-sessions", "2"),
                Map.entry("runner.allow-plugins", "true"), Map.entry("runner.app-data-dir", "off"));
    }

    @BeforeEach
    void client() { http = new TestHttp(server.getURL().toString(), TOKEN); }

    @AfterEach
    void closeAll() { sessions.list().forEach(s -> sessions.close(s, "test")); }

    private TestHttp.Response ok(TestHttp.Response response) {
        assertTrue(response.status() < 300, () -> response.status() + " " + response.json());
        return response;
    }

    private String launchedCompose() throws IOException {
        TestHttp.Response created = http.createSessionMultipart(Files.readAllBytes(Path.of(System.getenv("APK_LAB_TEST_APK_DIR")).resolve("compose-approvals-debug.apk")), "{\"launch\":true}");
        assertEquals(201, created.status(), () -> String.valueOf(created.json()));
        String id = created.json().path("session").path("id").asText();
        assertTrue(ok(http.post("/sessions/" + id + "/wait", Map.of("ready", true, "timeoutMs", 30000))).json().path("ready").asBoolean());
        return id;
    }

    private JsonNode search(String id) { return ok(http.get("/sessions/" + id + "/elements?testTag=search")).json().path("elements").get(0); }
    /** "WxH" from a PNG's IHDR chunk. */
    static String pngSize(byte[] png) {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(png);
        return b.getInt(16) + "x" + b.getInt(20);
    }
    private static int width(JsonNode element) { return (int) Math.round(element.path("bounds").path("right").asDouble() - element.path("bounds").path("left").asDouble()); }
    private static int height(JsonNode element) { return (int) Math.round(element.path("bounds").path("bottom").asDouble() - element.path("bounds").path("top").asDouble()); }

    @Test
    void aPluginSentThroughTheApiReturnsOneComponentAndABoxOfTheWindow() throws Exception {
        String id = launchedCompose();
        ok(http.put("/sessions/" + id + "/plugins", Map.of("plugins", List.of(Map.of("id", "componente", "name", "Um componente", "code", PLUGIN)))));
        JsonNode plugin = null;
        for (int i = 0; i < 120; i++) {
            plugin = ok(http.get("/sessions/" + id + "/plugins/componente")).json();
            int images = 0; for (JsonNode block : plugin.path("blocks")) if ("image".equals(block.path("kind").asText())) images++;
            if (images >= 2 || plugin.path("errorCount").asInt() > 0) break;
            Thread.sleep(100);
        }
        assertEquals(0, plugin.path("errorCount").asInt(), plugin::toString);
        JsonNode element = search(id);
        String componente = null, retangulo = null, key = null;
        for (int index = 0; index < plugin.path("blocks").size(); index++) {
            JsonNode block = plugin.path("blocks").get(index);
            if ("dados".equals(block.path("id").asText())) for (JsonNode entry : block.path("entries")) if ("key".equals(entry.get(0).asText())) key = entry.get(1).asText();
            if (!"image".equals(block.path("kind").asText())) continue;
            String size = pngSize(ok(http.get("/sessions/" + id + "/plugins/componente/blocks/" + index + "/image")).bytes());
            if ("componente".equals(block.path("id").asText())) componente = size; else retangulo = size;
        }
        assertEquals(element.path("key").asText(), key, "the plugin found the component REST finds");
        assertEquals((width(element) + 16) + "x" + (height(element) + 16), componente, "the component with 8 dp of padding on each side");
        assertEquals("200x100", retangulo, "lab.screenshot({ bounds }) cuts that box of the window, not the whole screen");
    }

    @Test
    void restPiecesRectanglePaddingAndSeveralPiecesFromOnePainting() throws Exception {
        String id = launchedCompose();
        TestHttp.Response rect = ok(http.get("/sessions/" + id + "/screenshot.png?rect=10,10,210,110"));
        assertEquals("200x100", rect.header("x-image-size"));
        assertTrue(rect.header("x-screen-bounds") != null, "where the box is on the painted screen");
        assertEquals(400, http.get("/sessions/" + id + "/screenshot.png?rect=10,10,5,5").status());
        JsonNode element = search(id);
        TestHttp.Response padded = ok(http.get("/sessions/" + id + "/elements/" + URLEncoder.encode(element.path("key").asText(), StandardCharsets.UTF_8) + "/screenshot.png?padding=4"));
        assertEquals((width(element) + 8) + "x" + (height(element) + 8), padded.header("x-image-size"));
        JsonNode pieces = ok(http.post("/sessions/" + id + "/screenshot-pieces", Map.of("padding", 2, "pieces", List.of(
                Map.of("selector", Map.of("testTag", "search")), Map.of("key", element.path("key").asText(), "padding", 0), Map.of("rect", List.of(10, 10, 110, 60)),
                Map.of("selector", Map.of("testTag", "not-on-screen")))))).json().path("pieces");
        assertEquals(element.path("key").asText(), pieces.get(0).path("element").path("key").asText());
        assertEquals(width(element) + 4, pieces.get(0).path("width").asInt());
        assertTrue(Base64.getDecoder().decode(pieces.get(0).path("pngBase64").asText()).length > 100);
        assertEquals(width(element), pieces.get(1).path("width").asInt(), "a piece's own padding wins");
        assertEquals(104, pieces.get(2).path("width").asInt()); assertEquals(54, pieces.get(2).path("height").asInt());
        assertEquals("element-not-found", pieces.get(3).path("error").path("code").asText());
        assertFalse(pieces.get(3).has("pngBase64"));
        assertEquals(400, http.post("/sessions/" + id + "/screenshot-pieces", Map.of("pieces", List.of())).status());
    }
}
