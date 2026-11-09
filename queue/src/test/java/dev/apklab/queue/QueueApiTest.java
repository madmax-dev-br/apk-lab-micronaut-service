package dev.apklab.queue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The queue's HTTP API with a fake runner (a JDK HttpServer speaking the runner API): auth and isolation, validation, jobs, SSE progress, artifacts, the lease proxy. */
@MicronautTest(environments = "test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QueueApiTest implements TestPropertyProvider {
    static final ObjectMapper JSON = new ObjectMapper();
    static final byte[] APK = {0x50, 0x4b, 3, 4, 9, 9};
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 0};
    static final List<String> seen = new CopyOnWriteArrayList<>();
    static HttpServer runner;
    static Path data;
    static final HttpClient CLIENT = HttpClient.newHttpClient();

    @Inject EmbeddedServer server;

    @Override
    public Map<String, String> getProperties() {
        try {
            data = Files.createTempDirectory("queue-api-test");
            runner = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            runner.createContext("/", QueueApiTest::fakeRunner);
            runner.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return Map.of("queue.clients", "alice:alice-token-1:2,bob:bob-token-22", "queue.runners", "http://127.0.0.1:" + runner.getAddress().getPort() + "|runner-secret",
                "queue.data-dir", data.toString(), "queue.health-interval", "200ms", "queue.cors-origins", "");
    }

    @AfterAll
    void stop() { runner.stop(0); }

    static void fakeRunner(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath(), method = exchange.getRequestMethod();
        seen.add(method + " " + exchange.getRequestURI() + " auth=" + exchange.getRequestHeaders().getFirst("Authorization"));
        exchange.getRequestBody().readAllBytes();
        if (path.equals("/health")) { json(exchange, 200, "{\"ok\":true,\"available\":2,\"maxSessions\":2,\"pluginsAllowed\":false,\"runtimeRevision\":\"fake\"}"); return; }
        if (path.equals("/sessions") && method.equals("POST")) { json(exchange, 201, "{\"session\":{\"id\":\"s-" + System.nanoTime() + "\",\"packageName\":\"test.fake\",\"metrics\":{\"loadMs\":3}}}"); return; }
        if (path.endsWith("/launch")) { json(exchange, 200, "{\"launchMs\":4,\"snapshot\":{\"activity\":\"test.fake.Main\"}}"); return; }
        if (path.endsWith("/screenshot.png")) {
            exchange.getResponseHeaders().add("Content-Type", "image/png");
            exchange.getResponseHeaders().add("X-Image-Size", "411x914");
            exchange.sendResponseHeaders(200, PNG.length);
            exchange.getResponseBody().write(PNG);
            exchange.close();
            return;
        }
        if (path.endsWith("/logcat/stream")) {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            for (int i = 0; i < 3; i++) {
                out.write(("id: " + i + "\nevent: log\ndata: {\"seq\":" + i + ",\"message\":\"linha " + i + "\"}\n\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            exchange.close();
            return;
        }
        if (path.endsWith("/tap") && exchange.getRequestURI().getQuery() == null && seen.stream().anyMatch(s -> s.contains("missing"))) { json(exchange, 404, "{\"error\":{\"code\":\"element-not-found\",\"message\":\"nope\"}}"); return; }
        if (method.equals("DELETE")) { json(exchange, 200, "{\"closed\":true}"); return; }
        json(exchange, 200, "{\"ok\":true,\"path\":\"" + path + "\",\"query\":\"" + exchange.getRequestURI().getQuery() + "\"}");
    }

    static void json(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    record Response(int status, JsonNode json, byte[] bytes, java.net.http.HttpHeaders headers) {
        String code() { return json == null ? null : json.path("error").path("code").asText(null); }
    }

    Response send(String method, String path, String token, String type, byte[] body, Map<String, String> headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(server.getURL() + path)).timeout(Duration.ofSeconds(30));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (type != null) builder.header("Content-Type", type);
        headers.forEach(builder::header);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        HttpResponse<byte[]> response = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        JsonNode json = response.headers().firstValue("content-type").orElse("").contains("json") && response.body().length > 0 ? JSON.readTree(response.body()) : null;
        return new Response(response.statusCode(), json, response.body(), response.headers());
    }

    Response submitJob(String token, String jobJson) throws Exception {
        return send("POST", "/jobs", token, "application/vnd.android.package-archive", APK, jobJson == null ? Map.of() : Map.of("X-Job", jobJson));
    }

    JsonNode waitFinal(String kind, String id, String token) throws Exception {
        for (int i = 0; i < 200; i++) {
            JsonNode item = send("GET", "/" + kind + "/" + id, token, null, null, Map.of()).json();
            if (List.of("succeeded", "failed", "cancelled", "expired").contains(item.path("state").asText())) return item;
            if ("leases".equals(kind) && "active".equals(item.path("state").asText())) return item;
            Thread.sleep(50);
        }
        throw new AssertionError(kind + " " + id + " did not finish");
    }

    @Test
    void authAndClientIsolation() throws Exception {
        assertEquals(200, send("GET", "/health", null, null, null, Map.of()).status());
        assertEquals(401, send("GET", "/jobs", null, null, null, Map.of()).status());
        assertEquals(401, send("GET", "/jobs", "wrong-token-x", null, null, Map.of()).status());
        Response job = submitJob("alice-token-1", "{\"steps\":[{\"type\":\"launch\"}]}");
        assertEquals(202, job.status(), () -> String.valueOf(job.json()));
        String id = job.json().path("id").asText();
        assertEquals(404, send("GET", "/jobs/" + id, "bob-token-22", null, null, Map.of()).status(), "bob does not see alice's job");
        assertEquals(200, send("GET", "/jobs/" + id + "?access_token=alice-token-1", null, null, null, Map.of()).status());
        assertEquals(2, send("GET", "/stats", "alice-token-1", null, null, Map.of()).json().path("maxConcurrent").asInt(), "alice's own limit from QUEUE_CLIENTS");
        assertTrue(seen.stream().anyMatch(s -> s.startsWith("GET /health") && s.endsWith("auth=Bearer runner-secret")), "the queue calls runners with their token");
    }

    @Test
    void submissionsAreValidated() throws Exception {
        Response unknownStep = submitJob("alice-token-1", "{\"steps\":[{\"type\":\"teleport\"}]}");
        assertEquals(400, unknownStep.status());
        assertTrue(unknownStep.json().path("error").path("message").asText().contains("type must be one of"), unknownStep.json()::toString);
        Response wrongField = submitJob("alice-token-1", "{\"steps\":[{\"type\":\"back\",\"text\":\"x\"}]}");
        assertEquals(400, wrongField.status());
        assertTrue(wrongField.json().path("error").path("message").asText().contains("keys not used by this step: text"), wrongField.json()::toString);
        assertEquals(400, submitJob("alice-token-1", "{\"steps\":[{\"type\":\"wait\"}]}").status(), "wait needs a selector");
        Response waitNothing = submitJob("alice-token-1", "{\"steps\":[{\"type\":\"wait\",\"gone\":true}]}");
        assertTrue(waitNothing.json().path("error").path("message").asText().contains("needs a selector, ready: true, or both"), waitNothing.json()::toString);
        Response badMode = submitJob("alice-token-1", "{\"steps\":[{\"type\":\"reload\",\"mode\":\"refresh\"}]}");
        assertEquals(400, badMode.status());
        assertTrue(badMode.json().path("error").path("message").asText().contains("recreate"), badMode.json()::toString);
        Response readyWithSelector = submitJob("alice-token-1", "{\"steps\":[{\"type\":\"waitReady\",\"selector\":{\"text\":\"x\"}}]}");
        assertTrue(readyWithSelector.json().path("error").path("message").asText().contains("keys not used by this step: selector"), readyWithSelector.json()::toString);
        assertEquals(400, submitJob("alice-token-1", "{\"steps\":[{\"type\":\"back\",\"waitReady\":true}]}").status(), "waitReady is a screenshot field");
        assertEquals(400, submitJob("alice-token-1", "{\"priority\":12}").status());
        Response badConfig = submitJob("alice-token-1", "{\"config\":{\"endpointMocks\":[{\"url\":\"/x\",\"stauts\":200}]}}");
        assertEquals(400, badConfig.status());
        assertTrue(badConfig.json().path("error").path("message").asText().contains("stauts"));
        Response plugins = submitJob("alice-token-1", "{\"steps\":[{\"type\":\"plugins\",\"plugins\":[{\"code\":\"1\"}],\"seconds\":1}]}");
        assertEquals(403, plugins.status());
        assertEquals("plugins-disabled", plugins.code());
        assertEquals(403, submitJob("alice-token-1", "{\"config\":{\"plugins\":[{\"code\":\"1\"}]}}").status());
        Response notApk = send("POST", "/jobs", "alice-token-1", "application/octet-stream", new byte[] {1, 2, 3}, Map.of());
        assertEquals(400, notApk.status());
        Response url = send("POST", "/jobs", "alice-token-1", "application/json", "{\"apkUrl\":\"https://downloads.exemplo.test/app.apk\"}".getBytes(), Map.of());
        assertEquals(403, url.status(), "apkUrl hosts must be in QUEUE_APK_URL_HOSTS");
        assertEquals("apk-url-not-allowed", url.code());
        assertNull(send("GET", "/health", null, null, null, Map.of("Origin", "https://painel.exemplo.test")).headers().firstValue("access-control-allow-origin").orElse(null), "no CORS by default");
    }

    @Test
    void aJobRunsOnTheRunnerWithArtifactsAndSseProgress() throws Exception {
        String spec = "{\"label\":\"smoke\",\"steps\":[{\"type\":\"launch\"},{\"type\":\"screenshot\",\"name\":\"inicio\"},{\"type\":\"layoutBounds\",\"name\":\"caixas\"}],\"collect\":{\"logcat\":true}}";
        String boundary = "----q" + System.nanoTime();
        byte[] body = (("--" + boundary + "\r\nContent-Disposition: form-data; name=\"apk\"; filename=\"app.apk\"\r\nContent-Type: application/octet-stream\r\n\r\n")).getBytes();
        var out = new java.io.ByteArrayOutputStream();
        out.write(body); out.write(APK);
        out.write(("\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"job\"\r\n\r\n" + spec + "\r\n--" + boundary + "--\r\n").getBytes());
        Response submitted = send("POST", "/jobs", "alice-token-1", "multipart/form-data; boundary=" + boundary, out.toByteArray(), Map.of());
        assertEquals(202, submitted.status(), () -> String.valueOf(submitted.json()));
        String id = submitted.json().path("id").asText();
        HttpResponse<InputStream> events = CLIENT.send(HttpRequest.newBuilder(URI.create(server.getURL() + "/jobs/" + id + "/events")).header("Authorization", "Bearer alice-token-1").build(), HttpResponse.BodyHandlers.ofInputStream());
        String stream = new String(events.body().readAllBytes(), StandardCharsets.UTF_8);   // ends at the final state
        assertTrue(stream.contains("event: step-finished") && stream.contains("event: succeeded"), stream);
        JsonNode job = waitFinal("jobs", id, "alice-token-1");
        assertEquals("succeeded", job.path("state").asText(), job.toString());
        assertEquals("smoke", job.path("label").asText());
        assertEquals("test.fake.Main", job.path("result").path("steps").path(0).path("output").path("activity").asText());
        List<String> names = new java.util.ArrayList<>();
        job.path("result").path("artifacts").forEach(a -> names.add(a.path("name").asText()));
        assertEquals(List.of("inicio.png", "caixas.layout-bounds.json", "logcat.json"), names);
        Response png = send("GET", "/jobs/" + id + "/artifacts/inicio.png", "alice-token-1", null, null, Map.of());
        assertEquals("image/png", png.headers().firstValue("content-type").orElse(null));
        assertArrayEquals(PNG, png.bytes());
        assertEquals(404, send("GET", "/jobs/" + id + "/artifacts/..%2Fqueue.json", "alice-token-1", null, null, Map.of()).status());
    }

    @Test
    void aLeaseProxiesTheRunnerSessionApi() throws Exception {
        Response created = send("POST", "/leases", "bob-token-22", "application/json",
                ("{\"apkBase64\":\"" + java.util.Base64.getEncoder().encodeToString(APK) + "\",\"durationSeconds\":120,\"config\":{\"device\":{\"darkTheme\":true}}}").getBytes(), Map.of());
        assertEquals(202, created.status(), () -> String.valueOf(created.json()));
        String id = created.json().path("id").asText();
        JsonNode lease = waitFinal("leases", id, "bob-token-22");
        assertEquals("active", lease.path("state").asText(), lease.toString());
        assertTrue(seen.stream().anyMatch(s -> s.startsWith("POST /sessions ")), "the session was created on the runner");

        Response tap = send("POST", "/leases/" + id + "/session/tap", "bob-token-22", "application/json", "{\"selector\":{\"testTag\":\"save\"}}".getBytes(), Map.of());
        assertEquals(200, tap.status(), () -> String.valueOf(tap.json()));
        assertTrue(tap.json().path("path").asText().endsWith("/tap"));
        Response bounds = send("GET", "/leases/" + id + "/session/layout-bounds?names=0&access_token=bob-token-22", null, null, null, Map.of());
        assertEquals("names=0", bounds.json().path("query").asText(), "query strings pass through, the access token does not");
        Response shot = send("GET", "/leases/" + id + "/session/screenshot.png", "bob-token-22", null, null, Map.of());
        assertArrayEquals(PNG, shot.bytes());
        assertEquals("411x914", shot.headers().firstValue("x-image-size").orElse(null));
        HttpResponse<InputStream> sse = CLIENT.send(HttpRequest.newBuilder(URI.create(server.getURL() + "/leases/" + id + "/session/logcat/stream")).header("Authorization", "Bearer bob-token-22").build(), HttpResponse.BodyHandlers.ofInputStream());
        assertTrue(sse.headers().firstValue("content-type").orElse("").startsWith("text/event-stream"));
        String text = new String(sse.body().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(text.contains("event: log") && text.contains("linha 2"), text);
        assertEquals(400, send("GET", "/leases/" + id + "/session/..%2F..%2Fhealth", "bob-token-22", null, null, Map.of()).status());
        assertEquals(404, send("GET", "/leases/" + id + "/session/layout-bounds", "alice-token-1", null, null, Map.of()).status(), "only the holder");

        Response ended = send("DELETE", "/leases/" + id, "bob-token-22", null, null, Map.of());
        assertEquals("cancelled", ended.json().path("state").asText());
        assertEquals(409, send("GET", "/leases/" + id + "/session/layout-bounds", "bob-token-22", null, null, Map.of()).status());
    }
}
