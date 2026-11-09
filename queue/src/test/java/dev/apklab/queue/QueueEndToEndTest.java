package dev.apklab.queue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A queued job with steps and an interactive lease against a real runner (started in its own JVM from the runner module's classpath) with the real APK Web Lab
 * runtime. Runs only when APK_LAB_RUNTIME_DIR and APK_LAB_TEST_APK_DIR are set.
 */
@MicronautTest(environments = "test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "APK_LAB_RUNTIME_DIR", matches = ".+")
@EnabledIfEnvironmentVariable(named = "APK_LAB_TEST_APK_DIR", matches = ".+")
class QueueEndToEndTest implements TestPropertyProvider {
    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient CLIENT = HttpClient.newHttpClient();
    static final String RUNNER_TOKEN = "e2e-runner-token";
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
    Process runner;
    @Inject EmbeddedServer server;

    @Override
    public Map<String, String> getProperties() {
        try {
            int port;
            try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
            Path repo = Path.of(System.getProperty("apklab.repoRoot"));
            ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", System.getProperty("apklab.runnerClasspath"),
                    "dev.apklab.runner.RunnerApplication").redirectErrorStream(true).redirectOutput(Files.createTempFile("runner-e2e", ".log").toFile());
            builder.environment().put("RUNNER_PORT", String.valueOf(port));
            builder.environment().put("RUNNER_TOKEN", RUNNER_TOKEN);
            builder.environment().put("RUNNER_SIDECAR_SCRIPT", repo.resolve("sidecar/vm-host.mjs").toString());
            builder.environment().put("RUNNER_MAX_SESSIONS", "2");
            builder.environment().put("RUNNER_WORK_DIR", Files.createTempDirectory("runner-e2e-work").toString());
            runner = builder.start();
            String base = "http://127.0.0.1:" + port;
            boolean up = false;
            for (int i = 0; i < 120 && !up; i++) {
                try { up = CLIENT.send(HttpRequest.newBuilder(URI.create(base + "/health")).build(), HttpResponse.BodyHandlers.ofString()).statusCode() == 200; }
                catch (IOException e) { Thread.sleep(500); }
            }
            if (!up) throw new IllegalStateException("the runner did not start");
            return Map.of("queue.clients", "e2e:e2e-client-token", "queue.runners", base + "|" + RUNNER_TOKEN, "queue.data-dir", Files.createTempDirectory("queue-e2e").toString(),
                    "queue.health-interval", "1s");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    void stop() { if (runner != null) runner.destroy(); }

    HttpResponse<byte[]> send(String method, String path, String type, byte[] body, Map<String, String> headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(server.getURL() + path)).timeout(Duration.ofMinutes(3)).header("Authorization", "Bearer e2e-client-token");
        if (type != null) builder.header("Content-Type", type);
        headers.forEach(builder::header);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    JsonNode json(HttpResponse<byte[]> response) throws IOException { return JSON.readTree(response.body()); }

    static byte[] apk(String name) throws IOException { return Files.readAllBytes(Path.of(System.getenv("APK_LAB_TEST_APK_DIR")).resolve(name)); }

    @Test
    void aQueuedJobWithStepsAndItsResults() throws Exception {
        String job = """
                {"label": "aprovações", "collect": {"network": true},
                 "steps": [
                   {"type": "launch"},
                   {"type": "wait", "selector": {"text": "Central de aprovações"}, "timeoutMs": 20000},
                   {"type": "type", "selector": {"testTag": "search"}, "text": "Monitor"},
                   {"type": "wait", "selector": {"text": "Monitor para QA"}},
                   {"type": "tap", "selector": {"testTag": "order-2"}},
                   {"type": "layoutBounds", "name": "caixas"},
                   {"type": "screenshot", "name": "tela"},
                   {"type": "screenshot", "name": "busca", "selector": {"testTag": "search"}, "scale": 2},
                   {"type": "viewModels", "name": "vms"},
                   {"type": "diagnostic"}
                 ]}""";
        long started = System.nanoTime();
        HttpResponse<byte[]> submitted = send("POST", "/jobs", "application/vnd.android.package-archive", apk("compose-approvals-debug.apk"), Map.of("X-Job", java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.readTree(job).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))));   // headers are Latin-1: base64url for non-ASCII
        assertEquals(202, submitted.statusCode(), new String(submitted.body()));
        String id = json(submitted).path("id").asText();
        HttpResponse<byte[]> events = send("GET", "/jobs/" + id + "/events", null, null, Map.of());
        String stream = new String(events.body());
        JsonNode result = json(send("GET", "/jobs/" + id, null, null, Map.of()));
        long ms = Duration.ofNanos(System.nanoTime() - started).toMillis();
        assertEquals("succeeded", result.path("state").asText(), result.toPrettyString());
        assertTrue(stream.contains("event: succeeded"));
        JsonNode steps = result.path("result").path("steps");
        assertEquals(10, steps.size());
        List<String> stepMs = new ArrayList<>();
        steps.forEach(s -> stepMs.add(s.path("type").asText() + "=" + s.path("ms").asLong()));
        assertTrue(steps.get(5).path("output").path("count").asInt() > 5);
        assertTrue(steps.get(8).toString().contains("ExpenseModel"), steps.get(8).toString());
        byte[] png = send("GET", "/jobs/" + id + "/artifacts/tela.png", null, null, Map.of()).body();
        assertArrayEquals(PNG, java.util.Arrays.copyOf(png, 8));
        byte[] element = send("GET", "/jobs/" + id + "/artifacts/busca.png", null, null, Map.of()).body();
        assertArrayEquals(PNG, java.util.Arrays.copyOf(element, 8));
        assertTrue(result.path("result").path("artifacts").toString().contains("network.json"));
        System.out.printf("[e2e] queued job: %d ms total, session load %s ms, steps %s, screenshot %d bytes, element %d bytes%n", ms,
                result.path("result").path("session").path("loadMs"), stepMs, png.length, element.length);
    }

    @Test
    void aQueuedJobWithReloadWaitReadyAndAReadyScreenshot() throws Exception {
        String job = """
                {"steps": [
                   {"type": "launch"},
                   {"type": "waitReady"},
                   {"type": "reload", "mode": "recreate"},
                   {"type": "waitReady", "timeoutMs": 20000},
                   {"type": "reload", "mode": "reopen"},
                   {"type": "wait", "ready": true, "selector": {"text": "Pedidos da equipe"}},
                   {"type": "screenshot", "name": "pronta", "waitReady": true}
                 ]}""";
        HttpResponse<byte[]> submitted = send("POST", "/jobs", "application/vnd.android.package-archive", apk("compose-approvals-debug.apk"), Map.of("X-Job", JSON.readTree(job).toString()));
        assertEquals(202, submitted.statusCode(), new String(submitted.body()));
        String id = json(submitted).path("id").asText();
        send("GET", "/jobs/" + id + "/events", null, null, Map.of());   // the SSE stream ends with the job
        JsonNode result = json(send("GET", "/jobs/" + id, null, null, Map.of()));
        assertEquals("succeeded", result.path("state").asText(), result.toPrettyString());
        JsonNode steps = result.path("result").path("steps");
        assertTrue(steps.get(1).path("output").path("ready").asBoolean(), steps.get(1).toString());
        assertEquals("recreate", steps.get(2).path("output").path("mode").asText());
        assertEquals("reopen", steps.get(4).path("output").path("mode").asText());
        assertTrue(steps.get(5).path("output").path("ready").asBoolean(), steps.get(5).toString());
        assertTrue(steps.get(6).path("output").path("ready").path("ready").asBoolean(), steps.get(6).toString());
        assertArrayEquals(PNG, java.util.Arrays.copyOf(send("GET", "/jobs/" + id + "/artifacts/pronta.png", null, null, Map.of()).body(), 8));
    }

    @Test
    void anInteractiveLeaseProxiesTheSession() throws Exception {
        HttpResponse<byte[]> created = send("POST", "/leases", "application/vnd.android.package-archive", apk("net-probe.apk"),
                Map.of("X-Job", "{\"durationSeconds\":120,\"config\":{\"launch\":true,\"endpointMocks\":[{\"url\":\"/net-probe/hello.json\",\"body\":\"via lease\"}]}}"));
        assertEquals(202, created.statusCode(), new String(created.body()));
        String id = json(created).path("id").asText();
        JsonNode lease = null;
        for (int i = 0; i < 120; i++) {
            lease = json(send("GET", "/leases/" + id, null, null, Map.of()));
            if (!"queued".equals(lease.path("state").asText()) && !"assigned".equals(lease.path("state").asText())) break;
            Thread.sleep(250);
        }
        assertEquals("active", lease.path("state").asText(), lease.toString());
        HttpResponse<byte[]> tap = send("POST", "/leases/" + id + "/session/tap", "application/json", "{\"selector\":{\"text\":\"Load\"}}".getBytes(), Map.of());
        assertEquals(200, tap.statusCode(), new String(tap.body()));
        HttpResponse<byte[]> waited = send("POST", "/leases/" + id + "/session/wait", "application/json", "{\"selector\":{\"text\":\"via lease\"},\"timeoutMs\":15000}".getBytes(), Map.of());
        assertEquals(200, waited.statusCode(), new String(waited.body()));
        HttpResponse<byte[]> shot = send("GET", "/leases/" + id + "/session/screenshot.png", null, null, Map.of());
        assertArrayEquals(PNG, java.util.Arrays.copyOf(shot.body(), 8));
        assertEquals("cancelled", json(send("DELETE", "/leases/" + id, null, null, Map.of())).path("state").asText());
        System.out.printf("[e2e] lease: active, proxied tap/wait/screenshot (%d bytes)%n", shot.body().length);
    }
}
