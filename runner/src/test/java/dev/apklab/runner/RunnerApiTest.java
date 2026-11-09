package dev.apklab.runner;

import com.fasterxml.jackson.databind.JsonNode;
import dev.apklab.runner.session.SessionManager;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The runner API against a fake sidecar: auth, validation, plugins disabled by default, capacity, the protocol's crash / timeout / size handling, SSE. */
@MicronautTest(environments = "test")
class RunnerApiTest {
    @Inject EmbeddedServer server;
    @Inject SessionManager sessions;
    TestHttp http;

    @BeforeAll
    static void node() { assumeTrue(TestHttp.nodeAvailable(), "Node.js is needed to run the fake sidecar"); }

    @BeforeEach
    void client() { http = new TestHttp(server.getURL().toString(), "unit-test-token"); }

    @AfterEach
    void closeAll() { sessions.list().forEach(s -> sessions.close(s, "test")); }

    private String create(String config) {
        TestHttp.Response response = http.postBytes("/sessions", TestHttp.fakeApk(), "application/vnd.android.package-archive", config == null ? Map.of() : Map.of("X-APK-Config", config));
        assertEquals(201, response.status(), () -> String.valueOf(response.json()));
        return response.json().path("session").path("id").asText();
    }

    // ---- auth --------------------------------------------------------------------------------------------------------------------------------------------------
    @Test
    void healthIsPublicAndReportsCapacity() {
        TestHttp.Response health = new TestHttp(server.getURL().toString(), null).get("/health");
        assertEquals(200, health.status());
        assertTrue(health.json().path("ok").asBoolean());
        assertEquals("fake-revision", health.json().path("runtimeRevision").asText());
        assertEquals(2, health.json().path("available").asInt());
        assertFalse(health.json().path("pluginsAllowed").asBoolean());
    }

    @Test
    void everythingElseNeedsTheToken() {
        TestHttp anonymous = new TestHttp(server.getURL().toString(), null);
        assertEquals(401, anonymous.get("/sessions").status());
        assertEquals("unauthorized", anonymous.get("/sessions").code());
        assertEquals(401, new TestHttp(server.getURL().toString(), "wrong-token").get("/sessions").status());
        assertEquals(401, anonymous.postBytes("/sessions", TestHttp.fakeApk(), "application/octet-stream", Map.of()).status());
        assertEquals(200, anonymous.get("/sessions?access_token=unit-test-token").status(), "EventSource clients pass the token as access_token on GET");
        assertEquals(401, anonymous.postBytes("/sessions?access_token=unit-test-token", TestHttp.fakeApk(), "application/octet-stream", Map.of()).status(), "only on GET");
        assertEquals(200, http.get("/sessions").status());
    }

    // ---- sessions over the protocol ----------------------------------------------------------------------------------------------------------------------------
    @Test
    void sessionLifecycleThroughTheSidecar() {
        TestHttp.Response created = http.postBytes("/sessions", TestHttp.fakeApk(), "application/vnd.android.package-archive",
                Map.of("X-APK-Config", "{\"endpointMocks\":[{\"method\":\"GET\",\"url\":\"https://api.exemplo.test/v1/perfil\",\"status\":200,\"body\":\"{}\"}]}"));
        assertEquals(201, created.status(), () -> String.valueOf(created.json()));
        String id = created.json().path("session").path("id").asText();
        assertEquals("test.fake", created.json().path("inventory").path("packageName").asText());
        assertEquals("https://api.exemplo.test/v1/perfil", created.json().path("config").path("endpointMocks").path(0).path("url").asText(), "the validated config reaches the sidecar");
        assertTrue(created.json().path("apkPath").asText().endsWith("app.apk"), "the APK goes to the sidecar as a file of the session directory");

        TestHttp.Response tap = http.post("/sessions/" + id + "/tap", Map.of("selector", Map.of("testTag", "save")));
        assertEquals(200, tap.status());
        assertEquals("save", tap.json().path("args").path("selector").path("testTag").asText());
        assertFalse(tap.json().path("args").path("selector").has("empty"), "selectors are sent with only their set fields");

        TestHttp.Response missing = http.post("/sessions/" + id + "/tap", Map.of("selector", Map.of("text", "missing")));
        assertEquals(404, missing.status());
        assertEquals("element-not-found", missing.code());
        assertEquals("test.fake.MainActivity", missing.json().path("error").path("details").path("activity").asText(), "the sidecar's error details are kept");

        assertEquals("c:1:2", http.get("/sessions/" + id + "/layout-bounds").json().path("elements").path(0).path("key").asText());
        TestHttp.Response shot = http.get("/sessions/" + id + "/screenshot.png");
        assertEquals(200, shot.status());
        assertEquals("image/png", shot.header("content-type"));
        assertArrayEquals(new byte[] {(byte) 0x89, 'P', 'N', 'G'}, java.util.Arrays.copyOf(shot.bytes(), 4));
        assertEquals("1x1", shot.header("x-image-size"));

        TestHttp.Response patched = http.patch("/sessions/" + id + "/config", Map.of("device", Map.of("darkTheme", true)));
        assertEquals(200, patched.status(), () -> String.valueOf(patched.json()));
        assertTrue(patched.json().path("config").path("device").path("darkTheme").asBoolean());

        assertEquals(200, http.get("/sessions/" + id).status());
        assertTrue(http.delete("/sessions/" + id).json().path("closed").asBoolean());
        assertEquals(404, http.get("/sessions/" + id).status());
    }

    @Test
    void multipartAndJsonCreationsAreAccepted() {
        TestHttp.Response multipart = http.createSessionMultipart(TestHttp.fakeApk(), "{\"device\":{\"darkTheme\":true}}");
        assertEquals(201, multipart.status(), () -> String.valueOf(multipart.json()));
        assertTrue(multipart.json().path("config").path("device").path("darkTheme").asBoolean());
        TestHttp.Response json = http.post("/sessions", Map.of("apkBase64", java.util.Base64.getEncoder().encodeToString(TestHttp.fakeApk()), "config", Map.of("eventSeconds", 60)));
        assertEquals(201, json.status(), () -> String.valueOf(json.json()));
        assertEquals(60, json.json().path("config").path("eventSeconds").asInt());
    }

    @Test
    void theSidecarDoesNotInheritTheRunnersEnvironment() {
        String id = create(null);
        JsonNode env = sessions.get(id).call("env", null);
        List<String> names = new java.util.ArrayList<>();
        env.forEach(n -> names.add(n.asText()));
        assertTrue(names.contains("APK_LAB_RUNTIME_DIR"));
        assertFalse(names.contains("RUNNER_TOKEN"));
        assertTrue(names.stream().noneMatch(n -> n.startsWith("RUNNER_") || n.startsWith("QUEUE_") || n.startsWith("JAVA")), names::toString);
    }

    // ---- validation --------------------------------------------------------------------------------------------------------------------------------------------
    @Test
    void configIsValidatedBeforeAnySidecarStarts() {
        TestHttp.Response unknown = http.postBytes("/sessions", TestHttp.fakeApk(), "application/octet-stream", Map.of("X-APK-Config", "{\"devise\":{}}"));
        assertEquals(400, unknown.status());
        assertTrue(unknown.json().path("error").path("message").asText().contains("devise"), unknown.json()::toString);
        TestHttp.Response nested = http.postBytes("/sessions", TestHttp.fakeApk(), "application/octet-stream", Map.of("X-APK-Config", "{\"endpointMocks\":[{\"url\":\"/x\",\"stauts\":200}]}"));
        assertEquals(400, nested.status());
        assertTrue(nested.json().path("error").path("message").asText().contains("endpointMocks[0].stauts"), nested.json()::toString);
        TestHttp.Response pattern = http.postBytes("/sessions", TestHttp.fakeApk(), "application/octet-stream", Map.of("X-APK-Config", "{\"raster\":\"sometimes\"}"));
        assertEquals(400, pattern.status());
        assertTrue(pattern.json().path("error").path("message").asText().contains("config.raster"), pattern.json()::toString);
        TestHttp.Response status = http.postBytes("/sessions", TestHttp.fakeApk(), "application/octet-stream", Map.of("X-APK-Config", "{\"endpointMocks\":[{\"url\":\"/x\",\"status\":99}]}"));
        assertEquals(400, status.status());
        TestHttp.Response memory = http.postBytes("/sessions", TestHttp.fakeApk(), "application/octet-stream", Map.of("X-APK-Config", "{\"memoryMiB\":4096}"));
        assertEquals(403, memory.status());
        assertEquals("memory-policy", memory.code());
        TestHttp.Response notZip = http.postBytes("/sessions", new byte[] {1, 2, 3}, "application/octet-stream", Map.of());
        assertEquals(400, notZip.status());
        assertEquals(0, sessions.list().size(), "no session was created");
        TestHttp.Response base64Header = http.postBytes("/sessions", TestHttp.fakeApk(), "application/octet-stream",
                Map.of("X-APK-Config", java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("{\"logcat\":\"always\"}".getBytes())));
        assertEquals(201, base64Header.status(), () -> String.valueOf(base64Header.json()));
    }

    @Test
    void defaultsMatchTheSiteAndTheRasterSettingsAreValidated() {
        JsonNode health = new TestHttp(server.getURL().toString(), null).get("/health").json();
        assertTrue(health.path("memory").path("maxHeapObjects").asInt() >= 65536, health::toString);
        assertTrue(health.path("memory").path("budgetMiB").asInt() > 0, health::toString);
        assertEquals("[\"*\"]", health.path("network").path("allowedHosts").toString());
        assertFalse(health.path("network").path("allowPrivateNetwork").asBoolean());
        assertTrue(health.path("appData").asBoolean(), "app data is kept by default");

        String id = create("{\"raster\":\"progressive\",\"rasterScale\":0.25,\"rasterNavigation\":false}");
        JsonNode init = http.get("/sessions/" + id).json();
        assertNotNull(init);
        JsonNode policy = sessions.get(id).call("initArgs", TestHttp.JSON.createObjectNode()).path("policy");
        assertEquals("[\"*\"]", policy.path("allowedHosts").toString(), policy::toString);
        assertFalse(policy.path("allowPrivateNetwork").asBoolean(), policy::toString);
        assertTrue(sessions.get(id).call("initArgs", TestHttp.JSON.createObjectNode()).path("appDataDir").asText().contains("app-data"), "the sidecar gets the app data folder");
        JsonNode config = http.get("/sessions/" + id + "/config").json();
        assertEquals(0.25, config.path("rasterScale").asDouble());
        assertFalse(config.path("rasterNavigation").asBoolean());
        TestHttp.Response live = http.patch("/sessions/" + id + "/config", Map.of("rasterScale", 0.75));
        assertEquals(200, live.status(), () -> String.valueOf(live.json()));
        for (String bad : List.of("{\"rasterScale\":0.3}", "{\"rasterScale\":0}", "{\"rasterNavigation\":\"yes\"}")) {
            TestHttp.Response response = http.postBytes("/sessions", TestHttp.fakeApk(), "application/octet-stream", Map.of("X-APK-Config", bad));
            assertEquals(400, response.status(), bad);
        }
        assertEquals(400, http.patch("/sessions/" + id + "/config", Map.of("rasterScale", 0.6)).status());
        JsonNode defaults = http.get("/defaults").json();
        assertEquals("primitives", defaults.path("config").path("missingApis").asText(), defaults::toString);
        assertFalse(defaults.path("policy").path("allowPrivateNetwork").asBoolean());
    }

    @Test
    void theSidecarsOwnValidationFailsTheCreation() {
        TestHttp.Response response = http.postBytes("/sessions", TestHttp.fakeApk(), "application/octet-stream", Map.of("X-APK-Config", "{\"device\":{\"screen\":\"invalid-preset\"}}"));
        assertEquals(400, response.status());
        assertTrue(response.json().path("error").path("message").asText().contains("unsupported value"));
        assertEquals(0, sessions.list().size(), "the failed session was closed");
    }

    @Test
    void requestBodiesAreValidated() {
        String id = create(null);
        assertEquals(400, http.post("/sessions/" + id + "/tap", Map.of()).status(), "tap needs a target");
        TestHttp.Response unknownSelector = http.post("/sessions/" + id + "/tap", Map.of("selector", Map.of("textt", "x")));
        assertEquals(400, unknownSelector.status());
        assertEquals("bad-request", unknownSelector.code());
        TestHttp.Response badKind = http.post("/sessions/" + id + "/tap", Map.of("selector", Map.of("kind", "widget")));
        assertEquals(400, badKind.status());
        assertEquals(400, http.post("/sessions/" + id + "/type", Map.of("selector", Map.of("testTag", "x"))).status(), "type needs text");
        assertEquals(400, http.post("/sessions/" + id + "/wait", Map.of("selector", Map.of("testTag", "x"), "timeoutMs", 999999)).status());
        assertEquals(400, http.post("/sessions/" + id + "/input", Map.of("type", "explode")).status());
        assertEquals(400, http.post("/sessions/" + id + "/permission-requests/1", Map.of("answer", "maybe")).status());
        assertEquals(400, http.send("POST", "/sessions/" + id + "/tap", "{not json", "application/json", Map.of()).status());
        assertEquals(404, http.get("/sessions/nope").status());
    }

    @Test
    void reloadAndReadinessReachTheSidecar() throws Exception {
        String id = create(null);
        TestHttp.Response recreate = http.post("/sessions/" + id + "/reload", Map.of());
        assertEquals(200, recreate.status(), () -> String.valueOf(recreate.json()));
        assertEquals("recreate", recreate.json().path("mode").asText(), "recreate is the default mode");
        assertEquals("reopen", http.post("/sessions/" + id + "/reload", Map.of("mode", "reopen")).json().path("mode").asText());
        TestHttp.Response badMode = http.post("/sessions/" + id + "/reload", Map.of("mode", "refresh"));
        assertEquals(400, badMode.status());
        assertTrue(badMode.json().path("error").path("message").asText().contains("recreate"), badMode.json()::toString);

        TestHttp.Response ready = http.get("/sessions/" + id + "/ready");
        assertEquals(200, ready.status());
        assertTrue(ready.json().path("ready").asBoolean());
        TestHttp.Response waitReady = http.post("/sessions/" + id + "/wait", Map.of("ready", true));
        assertEquals(200, waitReady.status(), () -> String.valueOf(waitReady.json()));
        assertTrue(waitReady.json().path("args").path("ready").asBoolean());
        assertFalse(waitReady.json().path("args").has("selector"), "ready alone needs no selector");
        assertTrue(waitReady.json().path("element").isNull());
        TestHttp.Response both = http.post("/sessions/" + id + "/wait", Map.of("ready", true, "selector", Map.of("text", "Olá")));
        assertEquals("Olá", both.json().path("args").path("selector").path("text").asText());
        assertEquals(400, http.post("/sessions/" + id + "/wait", Map.of("timeoutMs", 100)).status(), "a wait needs a selector or ready");
        assertEquals(400, http.post("/sessions/" + id + "/wait", Map.of("ready", "yes")).status(), "ready is a boolean");
        TestHttp.Response timeout = http.post("/sessions/" + id + "/wait", Map.of("ready", true, "timeoutMs", 1));
        assertEquals(408, timeout.status());
        assertEquals("ready-timeout", timeout.code());
        assertEquals("quadro pendente", timeout.json().path("error").path("details").path("waitingFor").path(0).asText());

        TestHttp.Response shot = http.get("/sessions/" + id + "/screenshot.png?waitReady=1&readyTimeoutMs=2500");
        assertEquals(200, shot.status());
        assertTrue(shot.header("x-screen-ready").contains("\"waitedMs\":2500"), shot.header("x-screen-ready"));
        assertNull(http.get("/sessions/" + id + "/screenshot.png").header("x-screen-ready"), "only with waitReady");

        Thread trigger = new Thread(() -> { try { Thread.sleep(700); http.post("/sessions/" + id + "/reload", Map.of()); } catch (InterruptedException ignored) { } });
        trigger.start();
        List<TestHttp.Sse> events = http.readEvents("/sessions/" + id + "/events", "ready", 1, Duration.ofSeconds(10));
        trigger.join();
        TestHttp.Sse flip = events.stream().filter(e -> "ready".equals(e.event())).findFirst().orElseThrow(() -> new AssertionError(events.toString()));
        assertTrue(flip.data().path("ready").asBoolean());
    }

    @Test
    void pluginsAreDisabledByDefault() {
        TestHttp.Response withPlugins = http.postBytes("/sessions", TestHttp.fakeApk(), "application/octet-stream",
                Map.of("X-APK-Config", "{\"plugins\":[{\"id\":\"p\",\"code\":\"lab.output.text('x')\"}]}"));
        assertEquals(403, withPlugins.status());
        assertEquals("plugins-disabled", withPlugins.code());
        String id = create(null);
        TestHttp.Response put = http.put("/sessions/" + id + "/plugins", Map.of("plugins", List.of(Map.of("id", "p", "code", "return 1"))));
        assertEquals(403, put.status());
        assertEquals("plugins-disabled", put.code());
        assertEquals(200, http.put("/sessions/" + id + "/plugins", Map.of("plugins", List.of())).status(), "clearing plugins is always allowed");
    }

    @Test
    void capacityIsEnforced() {
        create(null);
        create(null);
        TestHttp.Response third = http.postBytes("/sessions", TestHttp.fakeApk(), "application/octet-stream", Map.of());
        assertEquals(503, third.status());
        assertEquals("at-capacity", third.code());
        assertEquals("5", third.header("retry-after"));
        assertEquals(0, http.get("/health").json().path("available").asInt());
    }

    // ---- protocol failures -------------------------------------------------------------------------------------------------------------------------------------
    @Test
    void aCrashedSidecarFailsTheSessionClearlyAndRestartBringsItBack() {
        String id = create("{\"device\":{\"darkTheme\":true}}");
        long firstPid = sessions.get(id).sidecarPid();
        TestHttp.Response crash = http.post("/sessions/" + id + "/tap", Map.of("selector", Map.of("text", "crash")));
        assertEquals(503, crash.status(), () -> String.valueOf(crash.json()));
        assertEquals("sidecar-crashed", crash.code());
        assertTrue(crash.json().path("error").path("message").asText().contains("crashing on request"), "the sidecar's last stderr lines are in the message: " + crash.json());
        TestHttp.Response after = http.post("/sessions/" + id + "/tap", Map.of("selector", Map.of("testTag", "x")));
        assertEquals(409, after.status());
        assertEquals("vm-not-running", after.code());
        assertEquals("crashed", http.get("/sessions/" + id).json().path("state").asText());
        TestHttp.Response restart = http.post("/sessions/" + id + "/restart", Map.of());
        assertEquals(200, restart.status(), () -> String.valueOf(restart.json()));
        assertTrue(sessions.get(id).sidecarPid() != firstPid, "a new sidecar process");
        assertTrue(http.get("/sessions/" + id + "/config").json().path("device").path("darkTheme").asBoolean(), "the config survives the crash");
        assertEquals(200, http.post("/sessions/" + id + "/tap", Map.of("selector", Map.of("testTag", "x"))).status());
    }

    @Test
    void anUnresponsiveSidecarTimesOutAndIsStopped() {
        String id = create(null);
        long started = System.nanoTime();
        TestHttp.Response hang = http.post("/sessions/" + id + "/tap", Map.of("selector", Map.of("text", "hang")));
        assertEquals(504, hang.status());
        assertEquals("sidecar-timeout", hang.code());
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toSeconds() < 10);
        assertEquals("vm-not-running", http.get("/sessions/" + id + "/layout-bounds").code());
    }

    @Test
    void oversizedAndMalformedLinesStopTheSidecar() {
        String id = create(null);
        TestHttp.Response huge = http.post("/sessions/" + id + "/tap", Map.of("selector", Map.of("text", "huge")));
        assertEquals(503, huge.status());
        assertEquals("sidecar-crashed", huge.code());
        String second = create(null);
        TestHttp.Response garbage = http.post("/sessions/" + second + "/tap", Map.of("selector", Map.of("text", "garbage")));
        assertEquals(503, garbage.status());
        assertTrue(garbage.json().path("error").path("message").asText().contains("protocol error"), garbage.json()::toString);
    }

    // ---- SSE ---------------------------------------------------------------------------------------------------------------------------------------------------
    @Test
    void logcatStreamsOnlyWhileSomeoneListens() throws InterruptedException {
        String id = create(null);
        assertFalse(sessions.get(id).call("subscriptions", null).path("logcat").asBoolean());
        List<TestHttp.Sse> events = http.readEvents("/sessions/" + id + "/logcat/stream", "log", 3, Duration.ofSeconds(10));
        List<TestHttp.Sse> logs = events.stream().filter(e -> "log".equals(e.event())).toList();
        assertTrue(logs.size() >= 3, events::toString);
        assertNotNull(logs.get(0).id(), "events carry the line's seq as id");
        assertEquals("Fake", logs.get(0).data().path("tag").asText());
        boolean off = false;
        for (int i = 0; i < 50 && !off; i++) {
            off = !sessions.get(id).call("subscriptions", null).path("logcat").asBoolean();
            if (!off) Thread.sleep(100);
        }
        assertTrue(off, "the sidecar stops forwarding Logcat once the stream closed");
    }

    @Test
    void unknownRoutesUseTheErrorShape() {
        TestHttp.Response response = http.get("/nothing-here");
        assertEquals(404, response.status());
        assertEquals("not-found", response.code());
        assertNull(response.header("access-control-allow-origin"), "no CORS unless configured");
    }
}
