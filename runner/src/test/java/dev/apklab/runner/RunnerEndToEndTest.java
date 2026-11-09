package dev.apklab.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
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
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end against the real APK Web Lab runtime and synthetic probe APKs. Runs only when APK_LAB_RUNTIME_DIR (a built runtime) and APK_LAB_TEST_APK_DIR
 * (a folder with net-probe.apk and compose-approvals-debug.apk) are set; CI leaves them unset. No external network: mocks answer, redirects go to a local server.
 */
@MicronautTest(environments = "e2e", startApplication = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "APK_LAB_RUNTIME_DIR", matches = ".+")
@EnabledIfEnvironmentVariable(named = "APK_LAB_TEST_APK_DIR", matches = ".+")
class RunnerEndToEndTest implements TestPropertyProvider {
    static final String TOKEN = "e2e-token";
    @Inject EmbeddedServer server;
    @Inject SessionManager sessions;
    TestHttp http;

    @Override
    public Map<String, String> getProperties() {
        return Map.of("runner.runtime-dir", System.getenv("APK_LAB_RUNTIME_DIR"), "runner.sidecar-script", "../sidecar/vm-host.mjs", "runner.token", TOKEN,
                "runner.max-line-mb", "64", "runner.operation-timeout-seconds", "45", "runner.call-margin-seconds", "15", "runner.max-sessions", "3",
                "runner.allowed-hosts", "127.0.0.1", "runner.allow-plugins", "true");
    }

    @BeforeEach
    void client() { http = new TestHttp(server.getURL().toString(), TOKEN); }

    @AfterEach
    void closeAll() { sessions.list().forEach(s -> sessions.close(s, "test")); }

    static byte[] apk(String name) throws IOException { return Files.readAllBytes(Path.of(System.getenv("APK_LAB_TEST_APK_DIR")).resolve(name)); }

    static long timed(Runnable work) { long t = System.nanoTime(); work.run(); return Duration.ofNanos(System.nanoTime() - t).toMillis(); }

    private JsonNode create(String apk, String config) throws IOException {
        long started = System.nanoTime();
        TestHttp.Response response = http.createSessionMultipart(apk(apk), config);
        assertEquals(201, response.status(), () -> String.valueOf(response.json()));
        System.out.printf("[e2e] create %s: %d ms (load %s ms)%n", apk, Duration.ofNanos(System.nanoTime() - started).toMillis(), response.json().path("session").path("metrics").path("loadMs"));
        return response.json();
    }

    private TestHttp.Response ok(TestHttp.Response response) {
        assertTrue(response.status() < 300, () -> response.status() + " " + response.json());
        return response;
    }

    @Test
    void aSessionCreatedWithAnEndpointMockShowsTheMockedAnswerInTheNetworkLog() throws IOException {
        String config = """
                {"endpointMocks": [{"name": "perfil", "method": "GET", "url": "/net-probe/hello.json", "status": 200,
                  "headers": [{"name": "Content-Type", "value": "application/json"}], "body": "{\\"nome\\":\\"Ana\\",\\"plano\\":\\"mock\\"}"}],
                 "launch": true}""";
        String id = create("net-probe.apk", config).path("session").path("id").asText();
        ok(http.post("/sessions/" + id + "/tap", Map.of("selector", Map.of("text", "Load"))));
        JsonNode waited = ok(http.post("/sessions/" + id + "/wait", Map.of("selector", Map.of("textContains", "HTTP 200"), "timeoutMs", 15000))).json();
        assertEquals("TextView \"HTTP 200 OK\"", waited.path("element").path("name").asText());
        assertEquals(1, ok(http.get("/sessions/" + id + "/elements?textContains=plano")).json().path("elements").size(), "the mocked body is on the screen");
        JsonNode rows = ok(http.get("/sessions/" + id + "/network")).json().path("requests");
        assertEquals(1, rows.size());
        assertEquals("GET", rows.get(0).path("method").asText());
        assertEquals(200, rows.get(0).path("status").asInt());
        assertFalse(rows.get(0).path("mockId").isNull(), "the row is marked as answered by a mock: " + rows.get(0));
        assertEquals("perfil", rows.get(0).path("mockLabel").asText());

        // live update: the same endpoint now fails with 500 after 2 s
        JsonNode patched = ok(http.patch("/sessions/" + id + "/config", Map.of("endpointMocks", List.of(Map.of("name", "falha", "method", "GET", "url", "/net-probe/hello.json",
                "status", 500, "body", "{\"erro\":\"interno\"}", "delayMs", 2000))))).json();
        assertEquals("endpointMocks", patched.path("applied").path(0).path("key").asText());
        long started = System.nanoTime();
        ok(http.post("/sessions/" + id + "/tap", Map.of("selector", Map.of("text", "Load"))));
        ok(http.post("/sessions/" + id + "/wait", Map.of("selector", Map.of("textContains", "HTTP 500"), "timeoutMs", 20000)));
        long elapsed = Duration.ofNanos(System.nanoTime() - started).toMillis();
        System.out.printf("[e2e] mocked 500 with delayMs 2000 answered after %d ms%n", elapsed);
        assertTrue(elapsed >= 1900, "the delay is applied: " + elapsed);
        JsonNode second = ok(http.get("/sessions/" + id + "/network?since=0")).json().path("requests");
        assertEquals(500, second.get(0).path("status").asInt());
        assertEquals("falha", second.get(0).path("mockLabel").asText());
    }

    @Test
    void aHostRedirectSendsTheAppToALocalServer() throws Exception {
        List<String> seen = new CopyOnWriteArrayList<>();
        HttpServer local = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        local.createContext("/", exchange -> {
            seen.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            byte[] body = "{\"origem\":\"servidor local\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        local.start();
        try {
            int port = local.getAddress().getPort();
            String config = "{\"network\":{\"hostMap\":[{\"from\":\"http://localhost:8000\",\"to\":\"127.0.0.1:" + port + "\"}],\"allowedHosts\":[\"127.0.0.1\"]},\"launch\":true}";
            String id = create("net-probe.apk", config).path("session").path("id").asText();
            ok(http.post("/sessions/" + id + "/tap", Map.of("selector", Map.of("text", "Load"))));
            ok(http.post("/sessions/" + id + "/wait", Map.of("selector", Map.of("textContains", "servidor local"), "timeoutMs", 15000)));
            assertEquals(List.of("GET /net-probe/hello.json"), seen);
            JsonNode row = ok(http.get("/sessions/" + id + "/network")).json().path("requests").get(0);
            assertEquals("http://localhost:8000/net-probe/hello.json", row.path("url").asText());
            assertEquals("http://127.0.0.1:" + port + "/net-probe/hello.json", row.path("mappedUrl").asText(), row.toString());

            TestHttp.Response refused = http.createSessionMultipart(apk("net-probe.apk"), "{\"network\":{\"hostMap\":[{\"from\":\"http://localhost:8000\",\"to\":\"elsewhere.test\"}]}}");
            assertEquals(403, refused.status(), "a redirect target outside RUNNER_ALLOWED_HOSTS is refused by the sidecar's policy check");
            assertEquals("network-policy", refused.code());
        } finally {
            local.stop(0);
        }
    }

    @Test
    void composeScreenWaitTapTypeLayoutBoundsAndScreenshots() throws IOException {
        JsonNode created = create("compose-approvals-debug.apk", null);
        String id = created.path("session").path("id").asText();
        long launch = timed(() -> ok(http.post("/sessions/" + id + "/launch", Map.of())));
        ok(http.post("/sessions/" + id + "/wait", Map.of("selector", Map.of("text", "Central de aprovações"), "timeoutMs", 20000)));
        long tap = timed(() -> assertEquals("semantics", ok(http.post("/sessions/" + id + "/tap", Map.of("selector", Map.of("testTag", "filter-Aprovados")))).json().path("via").asText()));
        ok(http.post("/sessions/" + id + "/wait", Map.of("selector", Map.of("textContains", "1 resultado"), "timeoutMs", 10000)));
        ok(http.post("/sessions/" + id + "/tap", Map.of("selector", Map.of("testTag", "filter-Todos"))));
        long type = timed(() -> ok(http.post("/sessions/" + id + "/type", Map.of("selector", Map.of("testTag", "search"), "text", "Monitor"))));
        ok(http.post("/sessions/" + id + "/wait", Map.of("selector", Map.of("text", "Monitor para QA"), "timeoutMs", 10000)));
        ok(http.post("/sessions/" + id + "/wait", Map.of("selector", Map.of("text", "Licença de design"), "gone", true, "timeoutMs", 10000)));

        long[] boundsMs = new long[1];
        JsonNode[] boundsBox = new JsonNode[1];
        boundsMs[0] = timed(() -> boundsBox[0] = ok(http.get("/sessions/" + id + "/layout-bounds")).json());
        JsonNode bounds = boundsBox[0];
        JsonNode order = null;
        for (JsonNode element : bounds.path("elements")) if ("order-2".equals(element.path("testTag").asText())) order = element;
        assertTrue(order != null, "order-2 is a named box of layout bounds");
        assertTrue(order.path("size").path("width").asDouble() > 100);

        byte[] png = new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
        long[] shotMs = new long[1];
        TestHttp.Response[] shotBox = new TestHttp.Response[1];
        shotMs[0] = timed(() -> shotBox[0] = ok(http.get("/sessions/" + id + "/screenshot.png")));
        TestHttp.Response shot = shotBox[0];
        assertArrayEquals(png, java.util.Arrays.copyOf(shot.bytes(), 8), "a valid PNG signature");
        assertEquals("411x914", shot.header("x-image-size"));
        assertTrue(shot.bytes().length > 5000);
        TestHttp.Response element = ok(http.get("/sessions/" + id + "/elements/" + order.path("key").asText() + "/screenshot.png?scale=2"));
        assertArrayEquals(png, java.util.Arrays.copyOf(element.bytes(), 8));
        int width = (int) Math.round(order.path("size").path("width").asDouble() * 2);
        assertTrue(element.header("x-image-size").startsWith(String.valueOf(width).substring(0, 2)), element.header("x-image-size") + " for width " + width);
        assertEquals(404, http.get("/sessions/" + id + "/elements/c:0:0/screenshot.png").status());

        JsonNode tree = ok(http.get("/sessions/" + id + "/compose-tree?maxNodes=500")).json();
        assertTrue(tree.path("owners").size() >= 1, tree.toString().substring(0, Math.min(300, tree.toString().length())));
        assertEquals(200, http.get("/sessions/" + id + "/diagnostic").status());
        System.out.printf("[e2e] compose: launch %d ms, tap %d ms, type %d ms, layout-bounds %d ms (%d boxes), screenshot %d ms (%d bytes)%n",
                launch, tap, type, boundsMs[0], bounds.path("elements").size(), shotMs[0], shot.bytes().length);
    }

    @Test
    void logcatStreamDeliversTheLinesOfTheSession() throws Exception {
        String config = "{\"endpointMocks\":[{\"method\":\"GET\",\"url\":\"/net-probe/hello.json\",\"status\":200,\"body\":\"ok\"}],\"launch\":true}";
        String id = create("net-probe.apk", config).path("session").path("id").asText();
        Thread tapper = Thread.ofVirtual().start(() -> {
            try { Thread.sleep(800); } catch (InterruptedException e) { return; }
            http.post("/sessions/" + id + "/tap", Map.of("selector", Map.of("text", "Load")));
        });
        List<TestHttp.Sse> events = http.readUntil("/sessions/" + id + "/logcat/stream",
                received -> received.stream().anyMatch(e -> "log".equals(e.event()) && e.data().path("message").asText().contains("mock")), Duration.ofSeconds(20));
        tapper.join();
        TestHttp.Sse line = events.stream().filter(e -> "log".equals(e.event()) && e.data().path("message").asText().contains("mock")).findFirst().orElse(null);
        assertTrue(line != null, "the lab's warning about the mocked endpoint arrives over SSE: " + events);
        assertEquals("W", line.data().path("level").asText());
        assertTrue(line.id() != null && !line.id().isBlank(), "each line carries its seq as the SSE id");
        JsonNode paged = ok(http.get("/sessions/" + id + "/logcat?contains=mock")).json();
        assertTrue(paged.path("lines").size() >= 1);
        System.out.printf("[e2e] logcat stream: %d events, line: %s %s: %s%n", events.size(), line.data().path("level").asText(), line.data().path("tag").asText(), line.data().path("message").asText());
    }

    @Test
    void aServerSidePluginReportsWhatItSees() throws Exception {
        String code = "const vms = await lab.viewModels();\nlab.ui.badge(String(vms.length));\nlab.ui.text('ViewModels: ' + vms.map(vm => vm.className).join(', '), { id: 'vms' });";
        String config = TestHttp.JSON.writeValueAsString(Map.of("launch", true, "plugins", List.of(Map.of("id", "vm-watch", "name", "ViewModels", "code", code))));
        String id = create("compose-approvals-debug.apk", config).path("session").path("id").asText();
        JsonNode plugin = null;
        for (int i = 0; i < 50; i++) {
            plugin = ok(http.get("/sessions/" + id + "/plugins/vm-watch")).json();
            if (plugin.path("blocks").size() > 0) break;
            Thread.sleep(200);
        }
        assertTrue(plugin.path("blocks").size() > 0, String.valueOf(plugin));
        assertTrue(plugin.path("blocks").get(0).toString().contains("ViewModels:"), plugin.toString());
        System.out.printf("[e2e] plugin: status %s, badge %s, %s%n", plugin.path("status").asText(), plugin.path("badge").asText(), plugin.path("blocks").get(0));
    }

    // ---- "Tela pronta" and "Recriar / Reabrir" -----------------------------------------------------------------------------------------------------------------
    private JsonNode appViewModel(String id) {
        for (JsonNode model : ok(http.get("/sessions/" + id + "/view-models")).json()) if (!model.path("library").asBoolean()) return model;
        throw new AssertionError("the app has no ViewModel on the screen");
    }

    @Test
    void theScreenTurnsReadyAfterLaunchAndTheReadyEventFires() throws Exception {
        String id = create("compose-approvals-debug.apk", "{}").path("session").path("id").asText();
        JsonNode before = ok(http.get("/sessions/" + id + "/ready")).json();
        assertFalse(before.path("ready").asBoolean(), before.toString());
        Thread launcher = Thread.ofVirtual().start(() -> {
            try { Thread.sleep(500); } catch (InterruptedException e) { return; }
            http.post("/sessions/" + id + "/launch", Map.of());
        });
        List<TestHttp.Sse> events = http.readUntil("/sessions/" + id + "/events",
                received -> received.stream().anyMatch(e -> "ready".equals(e.event()) && e.data().path("ready").asBoolean()), Duration.ofSeconds(30));
        launcher.join();
        TestHttp.Sse flip = events.stream().filter(e -> "ready".equals(e.event()) && e.data().path("ready").asBoolean()).findFirst().orElse(null);
        assertTrue(flip != null, "a ready event reaches the events stream: " + events);
        assertTrue(flip.data().path("activity").asText().endsWith("MainActivity"), flip.data().toString());

        JsonNode ready = null;
        for (int i = 0; i < 100; i++) { ready = ok(http.get("/sessions/" + id + "/ready")).json(); if (ready.path("ready").asBoolean()) break; Thread.sleep(100); }
        assertTrue(ready.path("ready").asBoolean() && ready.path("current").asBoolean(), String.valueOf(ready));
        assertEquals(0, ready.path("screenReady").path("waitingFor").size());

        JsonNode waited = ok(http.post("/sessions/" + id + "/wait", Map.of("ready", true, "timeoutMs", 15000))).json();
        assertTrue(waited.path("ready").asBoolean());
        assertTrue(waited.path("element").isNull());
        JsonNode both = ok(http.post("/sessions/" + id + "/wait", Map.of("ready", true, "selector", Map.of("text", "Pedidos da equipe"), "timeoutMs", 15000))).json();
        assertEquals("compose", both.path("element").path("kind").asText());

        TestHttp.Response shot = ok(http.get("/sessions/" + id + "/screenshot.png?waitReady=1&readyTimeoutMs=15000"));
        assertEquals("image/png", shot.header("content-type"));
        assertArrayEquals(new byte[] {(byte) 0x89, 'P', 'N', 'G'}, java.util.Arrays.copyOf(shot.bytes(), 4));
        assertEquals("411x914", shot.header("x-image-size"));
        assertTrue(TestHttp.JSON.readTree(shot.header("x-screen-ready")).path("ready").asBoolean(), shot.header("x-screen-ready"));
        String key = ok(http.get("/sessions/" + id + "/elements?testTag=new-order")).json().path("elements").path(0).path("key").asText();
        TestHttp.Response crop = ok(http.get("/sessions/" + id + "/elements/" + java.net.URLEncoder.encode(key, StandardCharsets.UTF_8) + "/screenshot.png?waitReady=1"));
        assertArrayEquals(new byte[] {(byte) 0x89, 'P', 'N', 'G'}, java.util.Arrays.copyOf(crop.bytes(), 4));
    }

    @Test
    void recreateKeepsTheViewModelAndReopenCreatesANewOne() throws Exception {
        String id = create("compose-approvals-debug.apk", "{\"launch\":true}").path("session").path("id").asText();
        ok(http.post("/sessions/" + id + "/wait", Map.of("ready", true, "timeoutMs", 15000)));
        JsonNode first = appViewModel(id);
        JsonNode recreated = ok(http.post("/sessions/" + id + "/reload", Map.of("mode", "recreate"))).json();
        assertEquals("recreate", recreated.path("mode").asText());
        assertTrue(recreated.path("snapshot").path("activity").asText().endsWith("MainActivity"));
        JsonNode kept = appViewModel(id);
        assertEquals(first.path("handle").asText(), kept.path("handle").asText(), "recreate keeps the retained ViewModel");
        assertFalse(first.path("owner").path("handle").asText().equals(kept.path("owner").path("handle").asText()), "with a new Activity instance");
        ok(http.post("/sessions/" + id + "/wait", Map.of("ready", true, "selector", Map.of("text", "Pedidos da equipe"), "timeoutMs", 15000)));
        JsonNode reopened = ok(http.post("/sessions/" + id + "/reload", Map.of("mode", "reopen"))).json();
        assertEquals("reopen", reopened.path("mode").asText());
        JsonNode fresh = appViewModel(id);
        assertEquals(first.path("className").asText(), fresh.path("className").asText());
        assertFalse(first.path("handle").asText().equals(fresh.path("handle").asText()), "reopen clears the ViewModelStore");
        ok(http.post("/sessions/" + id + "/wait", Map.of("ready", true, "timeoutMs", 15000)));
        assertEquals("recreate", ok(http.post("/sessions/" + id + "/reload", Map.of())).json().path("mode").asText(), "recreate is the default");
        assertEquals(400, http.post("/sessions/" + id + "/reload", Map.of("mode", "refresh")).status());
    }

    @Test
    void aPluginThatAwaitsWaitReadyProducesAnImage() throws Exception {
        String id = create("compose-approvals-debug.apk", "{\"launch\":true}").path("session").path("id").asText();
        JsonNode template = null;
        for (JsonNode t : ok(http.get("/plugin-templates")).json()) if ("Print na tela pronta".equals(t.path("name").asText())) template = t;
        assertTrue(template != null, "the service lists its template");
        String code = "const tela = await lab.waitReady({ timeoutMs: 15000 });\nlab.ui.text(tela.ready.ready ? 'pronta' : 'não', { id: 'estado' });\nlab.ui.image(await lab.screenshot({ maxWidth: 200 }), { id: 'print', caption: tela.activity });";
        ok(http.put("/sessions/" + id + "/plugins", Map.of("plugins", List.of(Map.of("id", "espera", "name", "Espera", "code", code), Map.of("id", "modelo", "name", template.path("name").asText(), "code", template.path("code").asText())))));
        for (String pid : List.of("espera", "modelo")) {
            JsonNode plugin = null, image = null;
            for (int i = 0; i < 150 && image == null; i++) {
                plugin = ok(http.get("/sessions/" + id + "/plugins/" + pid)).json();
                for (JsonNode block : plugin.path("blocks")) if ("image".equals(block.path("kind").asText())) image = block;
                if ("erro".equals(plugin.path("status").asText())) break;
                if (image == null) Thread.sleep(100);
            }
            assertTrue(image != null, pid + ": " + plugin);
            TestHttp.Response png = ok(http.get("/sessions/" + id + "/" + image.path("path").asText()));
            assertArrayEquals(new byte[] {(byte) 0x89, 'P', 'N', 'G'}, java.util.Arrays.copyOf(png.bytes(), 4));
            if (pid.equals("espera")) {
                assertTrue(image.path("caption").asText().endsWith("MainActivity"), image.toString());
                boolean pronta = false;
                for (JsonNode block : plugin.path("blocks")) if ("estado".equals(block.path("id").asText())) pronta = "pronta".equals(block.path("text").asText());
                assertTrue(pronta, plugin.toString());
            } else assertTrue(image.path("caption").asText().contains("pronta #1"), image.toString());
        }
    }
}
