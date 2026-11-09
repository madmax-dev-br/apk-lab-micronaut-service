package dev.apklab.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.apklab.runner.sidecar.SidecarException;
import dev.apklab.runner.sidecar.SidecarProcess;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The JSON-lines protocol client on its own, against the fake sidecar process. */
class SidecarProcessTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String NODE = System.getenv().getOrDefault("APK_LAB_NODE", "node");
    private static final String FAKE = Path.of("src/test/resources/fake-sidecar.mjs").toAbsolutePath().toString();

    @BeforeAll
    static void node() { assumeTrue(TestHttp.nodeAvailable(), "Node.js is needed to run the fake sidecar"); }

    private static SidecarProcess fake(Map<String, String> env) {
        Map<String, String> environment = new java.util.HashMap<>(env);
        environment.put("PATH", System.getenv("PATH"));
        return new SidecarProcess("test", List.of(NODE, FAKE), environment, JSON, 1024 * 1024);
    }

    @Test
    void readyHandshakeRequestsAndEvents() throws Exception {
        try (SidecarProcess sidecar = fake(Map.of())) {
            JsonNode hello = sidecar.start(Duration.ofSeconds(10));
            assertEquals(1, hello.path("protocol").asInt());
            assertEquals("fake-revision", hello.path("revision").asText());
            List<JsonNode> events = new CopyOnWriteArrayList<>();
            sidecar.onEvent(event -> events.add(event.message()));
            sidecar.call("init", JSON.createObjectNode().put("sessionId", "s1"), Duration.ofSeconds(5));
            sidecar.call("subscribe", JSON.createObjectNode().put("logcat", true), Duration.ofSeconds(5));
            for (int i = 0; i < 50 && events.size() < 2; i++) Thread.sleep(50);
            assertTrue(events.size() >= 2);
            assertEquals("logcat", events.get(0).path("event").asText());
            SidecarException error = assertThrows(SidecarException.class, () -> sidecar.call("nonsense", JSON.createObjectNode(), Duration.ofSeconds(5)));
            assertEquals(400, error.status());
            assertFalse(error.crashed(), "an error answer keeps the sidecar");
            assertTrue(sidecar.alive());
        }
    }

    @Test
    void concurrentCallsAreMatchedByIdEvenWhenOneHangs() throws Exception {
        try (SidecarProcess sidecar = fake(Map.of())) {
            sidecar.start(Duration.ofSeconds(10));
            Thread hanging = Thread.ofVirtual().start(() -> {
                try { sidecar.call("tap", JSON.readTree("{\"selector\":{\"text\":\"hang\"}}"), Duration.ofSeconds(30)); } catch (Exception ignored) { /* stopped */ }
            });
            JsonNode answer = sidecar.call("describe", JSON.createObjectNode(), Duration.ofSeconds(5));
            assertEquals("test.fake", answer.path("packageName").asText(), "a later request is answered while an earlier one is pending");
            sidecar.stop("test");
            hanging.join(Duration.ofSeconds(5));
            assertFalse(hanging.isAlive(), "stopping fails the pending call");
        }
    }

    @Test
    void aSidecarThatDiesBeforeReadyFailsTheStart() {
        SidecarProcess sidecar = fake(Map.of("FAKE_SIDECAR_FAIL_START", "1"));
        SidecarException error = assertThrows(SidecarException.class, () -> sidecar.start(Duration.ofSeconds(10)));
        assertEquals("sidecar-start-failed", error.code());
        assertTrue(error.getMessage().contains("refusing to start"), error.getMessage());
    }

    @Test
    void aMissingNodeExecutableIsAClearError() {
        SidecarProcess sidecar = new SidecarProcess("test", List.of("/nonexistent/node-binary", FAKE), Map.of(), JSON, 1024);
        SidecarException error = assertThrows(SidecarException.class, () -> sidecar.start(Duration.ofSeconds(2)));
        assertTrue(error.getMessage().contains("Node.js 22+"), error.getMessage());
    }

    @Test
    void anExitFailsPendingAndLaterCalls() throws Exception {
        SidecarProcess sidecar = fake(Map.of());
        sidecar.start(Duration.ofSeconds(10));
        SidecarException crash = assertThrows(SidecarException.class, () -> sidecar.call("tap", JSON.readTree("{\"selector\":{\"text\":\"crash\"}}"), Duration.ofSeconds(10)));
        assertTrue(crash.crashed());
        assertEquals(3, sidecar.exited().get(5, TimeUnit.SECONDS));
        SidecarException later = assertThrows(SidecarException.class, () -> sidecar.call("describe", JSON.createObjectNode(), Duration.ofSeconds(1)));
        assertTrue(later.crashed());
    }

    @Test
    void requestsAboveTheLineLimitAreRefusedWithoutStoppingTheSidecar() throws Exception {
        try (SidecarProcess sidecar = fake(Map.of())) {
            sidecar.start(Duration.ofSeconds(10));
            String big = "x".repeat(2 * 1024 * 1024);
            SidecarException error = assertThrows(SidecarException.class, () -> sidecar.call("describe", JSON.createObjectNode().put("pad", big), Duration.ofSeconds(5)));
            assertEquals(413, error.status());
            assertTrue(sidecar.alive());
        }
    }
}
