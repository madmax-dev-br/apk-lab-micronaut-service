package dev.apklab.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import dev.apklab.runner.session.MemoryBudget;
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
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The session defaults equal to the site's, end to end on the real runtime with the runner's default network policy (RUNNER_ALLOWED_HOSTS "*", private network
 * refused): the memory budget from navigator.deviceMemory, Tela pronta, a reload, progressive raster settings, an endpoint mock with no network config, and a host
 * redirect to a local server refused without RUNNER_ALLOW_PRIVATE_NETWORK (RunnerEndToEndTest runs the same redirect with it). Local servers only.
 */
@MicronautTest(environments = "e2e", startApplication = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "APK_LAB_RUNTIME_DIR", matches = ".+")
@EnabledIfEnvironmentVariable(named = "APK_LAB_TEST_APK_DIR", matches = ".+")
class RunnerDefaultsEndToEndTest implements TestPropertyProvider {
    static final String TOKEN = "e2e-defaults-token";
    @Inject EmbeddedServer server;
    @Inject SessionManager sessions;
    TestHttp http;

    @Override
    public Map<String, String> getProperties() {
        try {
            return Map.of("runner.runtime-dir", System.getenv("APK_LAB_RUNTIME_DIR"), "runner.sidecar-script", "../sidecar/vm-host.mjs", "runner.token", TOKEN,
                    "runner.max-line-mb", "64", "runner.operation-timeout-seconds", "45", "runner.call-margin-seconds", "15", "runner.max-sessions", "3",
                    "runner.app-data-dir", Files.createTempDirectory("apk-lab-app-data").toString());
        } catch (IOException e) { throw new IllegalStateException(e); }
    }

    @BeforeEach
    void client() { http = new TestHttp(server.getURL().toString(), TOKEN); }

    @AfterEach
    void closeAll() { sessions.list().forEach(s -> sessions.close(s, "test")); }

    static byte[] apk(String name) throws IOException { return Files.readAllBytes(Path.of(System.getenv("APK_LAB_TEST_APK_DIR")).resolve(name)); }

    private TestHttp.Response ok(TestHttp.Response response) {
        assertTrue(response.status() < 300, () -> response.status() + " " + response.json());
        return response;
    }

    private JsonNode create(String apk, String config) throws IOException {
        TestHttp.Response response = http.createSessionMultipart(apk(apk), config);
        assertEquals(201, response.status(), () -> String.valueOf(response.json()));
        return response.json();
    }

    @Test
    void aDefaultSessionHasTheSitesMemoryBudgetAndDefaultsReachesTelaProntaAndReloads() throws Exception {
        int expected = MemoryBudget.automatic(2048, 6144).maxHeapObjects();
        if (MemoryBudget.machineMemoryBytes() >= 8L * 1024 * 1024 * 1024) assertEquals(671088, expected, "8 GiB or more: the site's 1024 MiB budget");
        JsonNode health = new TestHttp(server.getURL().toString(), null).get("/health").json();
        assertEquals(expected, health.path("memory").path("maxHeapObjects").asInt(), health::toString);
        assertEquals("[\"*\"]", health.path("network").path("allowedHosts").toString());

        JsonNode created = create("compose-approvals-debug.apk", "{\"launch\":true}");
        String id = created.path("session").path("id").asText();
        JsonNode memory = created.path("session").path("memory");
        assertEquals(expected, memory.path("maxHeapObjects").asInt(), memory::toString);
        if (expected == 671088) { assertEquals(1024, memory.path("budgetMiB").asInt()); assertEquals(3072, memory.path("workerHeapMb").asInt()); }
        JsonNode config = created.path("config");
        assertEquals("primitives", config.path("missingApis").asText(), config::toString);
        assertEquals("[\"*\"]", config.path("network").path("allowedHosts").toString());
        assertEquals("full", config.path("raster").asText());
        assertTrue(config.path("appData").path("keep").asBoolean());
        assertTrue(ok(http.post("/sessions/" + id + "/wait", Map.of("ready", true, "timeoutMs", 30000))).json().path("ready").asBoolean());
        // The default engine is the site's: the WebAssembly core, with its default memory (512 MiB heap, 512 MiB metaspace, 1000 threads).
        assertEquals("wasm", config.path("engine").asText(), config::toString);
        assertEquals("{\"heapMiB\":512,\"metaMiB\":512,\"threads\":1000}", config.path("coreMemory").toString());
        assertEquals("wasm", memory.path("engine").asText(), memory::toString);
        JsonNode health0 = ok(http.get("/sessions/" + id + "/snapshot")).json().path("vmHealth");
        assertEquals("wasm", health0.path("engine").asText(), health0::toString);
        assertEquals(1000, health0.path("core").path("threadCapacity").asInt(), "the core got the thread capacity the session reports");
        assertTrue(health0.path("core").path("metaBytes").asLong() > 0);
        // engine "js": the JavaScript VM, whose object limit is the budget the session reports.
        JsonNode onJs = create("compose-approvals-debug.apk", "{\"launch\":true,\"engine\":\"js\"}");
        String jsId = onJs.path("session").path("id").asText();
        assertEquals("js", onJs.path("session").path("memory").path("engine").asText());
        assertTrue(ok(http.post("/sessions/" + jsId + "/wait", Map.of("ready", true, "timeoutMs", 30000))).json().path("ready").asBoolean());
        JsonNode jsHealth = ok(http.get("/sessions/" + jsId + "/snapshot")).json().path("vmHealth");
        assertEquals("js", jsHealth.path("engine").asText());
        assertEquals(expected, jsHealth.path("limit").asInt(), "the VM got the limit the session reports");
        ok(http.delete("/sessions/" + jsId));
        JsonNode reloaded = ok(http.post("/sessions/" + id + "/reload", Map.of("mode", "recreate"))).json();
        assertTrue(reloaded.path("snapshot").path("activity").asText().endsWith("MainActivity"));
        assertTrue(ok(http.post("/sessions/" + id + "/wait", Map.of("ready", true, "timeoutMs", 30000))).json().path("ready").asBoolean());
        JsonNode defaults = ok(http.get("/defaults")).json();
        assertEquals("primitives", defaults.path("config").path("missingApis").asText(), defaults::toString);
        assertEquals("phone", defaults.path("device").path("screen").asText());
    }

    @Test
    void progressiveRasterAtAQuarterScaleStillGivesAFullSizeScreenshotAndAppliesLive() throws Exception {
        String id = create("compose-approvals-debug.apk", "{\"launch\":true,\"raster\":\"progressive\",\"rasterScale\":0.25,\"rasterNavigation\":true}").path("session").path("id").asText();
        ok(http.post("/sessions/" + id + "/tap", Map.of("selector", Map.of("testTag", "filter-Pendentes"))));
        JsonNode motion = ok(http.get("/sessions/" + id + "/snapshot?full=1")).json().path("vmHealth").path("motionRaster");
        assertEquals(0.25, motion.path("scale").asDouble(), motion::toString);
        assertTrue(motion.path("enabled").asBoolean());
        TestHttp.Response shot = ok(http.get("/sessions/" + id + "/screenshot.png?waitReady=1"));
        assertEquals("411x914", shot.header("x-image-size"));
        assertTrue(TestHttp.JSON.readTree(shot.header("x-screen-ready")).path("ready").asBoolean());
        JsonNode live = ok(http.patch("/sessions/" + id + "/config", Map.of("rasterScale", 0.75, "rasterNavigation", false))).json();
        JsonNode raster = null;
        for (JsonNode item : live.path("applied")) if ("raster".equals(item.path("key").asText())) raster = item.path("raster");
        assertTrue(raster != null, live::toString);
        assertEquals(0.75, raster.path("scale").asDouble());
        assertFalse(raster.path("navigation").asBoolean());
        ok(http.post("/sessions/" + id + "/tap", Map.of("selector", Map.of("testTag", "filter-Todos"))));
        assertEquals(0.75, ok(http.get("/sessions/" + id + "/snapshot?full=1")).json().path("vmHealth").path("motionRaster").path("scale").asDouble());
    }

    @Test
    void aMockAnswersWithNoNetworkConfigAndAPrivateTargetIsRefusedByDefault() throws Exception {
        String mocked = create("net-probe.apk", "{\"launch\":true,\"endpointMocks\":[{\"method\":\"GET\",\"url\":\"/net-probe/hello.json\",\"status\":200,\"body\":\"{\\\"message\\\":\\\"resposta do mock\\\"}\"}]}").path("session").path("id").asText();
        ok(http.post("/sessions/" + mocked + "/tap", Map.of("selector", Map.of("text", "Load"))));
        ok(http.post("/sessions/" + mocked + "/wait", Map.of("selector", Map.of("textContains", "resposta do mock"), "timeoutMs", 15000)));

        AtomicInteger hits = new AtomicInteger();
        HttpServer local = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        local.createContext("/", exchange -> { hits.incrementAndGet(); byte[] body = "{}".getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close(); });
        local.start();
        try {
            int port = local.getAddress().getPort();
            for (String config : List.of("{\"launch\":true,\"network\":{\"hostMap\":[{\"from\":\"http://localhost:8000\",\"to\":\"127.0.0.1:" + port + "\"}]}}", "{\"launch\":true}")) {
                String id = create("net-probe.apk", config).path("session").path("id").asText();
                ok(http.post("/sessions/" + id + "/tap", Map.of("selector", Map.of("text", "Load"))));
                JsonNode row = null;
                for (int i = 0; i < 100 && row == null; i++) {
                    for (JsonNode entry : ok(http.get("/sessions/" + id + "/network")).json().path("requests")) if (entry.path("complete").asBoolean()) row = entry;
                    if (row == null) Thread.sleep(100);
                }
                assertTrue(row != null, "the request reached the network log");
                assertTrue(row.path("failure").path("message").asText().contains("RUNNER_ALLOW_PRIVATE_NETWORK"), row::toString);
                System.out.printf("[e2e] refused: %s %s%n", row.path("failure").path("type").asText(), row.path("failure").path("message").asText());
            }
            assertEquals(0, hits.get(), "nothing reached the local server");
        } finally {
            local.stop(0);
        }
    }
}
