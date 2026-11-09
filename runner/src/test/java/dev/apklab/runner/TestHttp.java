package dev.apklab.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** A small JDK HTTP client for the tests (status codes as values, SSE reading), so the tests read like the curl recipes of the documentation. */
public final class TestHttp {
    public static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public record Response(int status, JsonNode json, byte[] bytes, java.net.http.HttpHeaders headers) {
        public String code() { return json == null ? null : json.path("error").path("code").asText(null); }
        public String header(String name) { return headers.firstValue(name).orElse(null); }
    }

    private final String base;
    private final String token;

    public TestHttp(String base, String token) {
        this.base = base.replaceAll("/$", "");
        this.token = token;
    }

    public Response get(String path) { return send("GET", path, null, null, Map.of()); }
    public Response delete(String path) { return send("DELETE", path, null, null, Map.of()); }
    public Response post(String path, Object body) { return send("POST", path, body, "application/json", Map.of()); }
    public Response patch(String path, Object body) { return send("PATCH", path, body, "application/json", Map.of()); }
    public Response put(String path, Object body) { return send("PUT", path, body, "application/json", Map.of()); }
    public Response postBytes(String path, byte[] body, String type, Map<String, String> headers) { return send("POST", path, body, type, headers); }

    public Response send(String method, String path, Object body, String type, Map<String, String> headers) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(180));
            if (token != null) builder.header("Authorization", "Bearer " + token);
            headers.forEach(builder::header);
            HttpRequest.BodyPublisher publisher;
            if (body == null) publisher = HttpRequest.BodyPublishers.noBody();
            else if (body instanceof byte[] bytes) publisher = HttpRequest.BodyPublishers.ofByteArray(bytes);
            else if (body instanceof String text) publisher = HttpRequest.BodyPublishers.ofString(text);
            else publisher = HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body));
            if (type != null) builder.header("Content-Type", type);
            builder.method(method, publisher);
            HttpResponse<byte[]> response = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            JsonNode json = null;
            String contentType = response.headers().firstValue("content-type").orElse("");
            if (contentType.contains("json") && response.body().length > 0) json = JSON.readTree(response.body());
            return new Response(response.statusCode(), json, response.body(), response.headers());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** A multipart/form-data body with an "apk" file part and an optional "config" JSON part. */
    public Response createSessionMultipart(byte[] apk, String configJson) {
        String boundary = "----apklab" + System.nanoTime();
        var out = new java.io.ByteArrayOutputStream();
        try {
            out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"apk\"; filename=\"app.apk\"\r\nContent-Type: application/vnd.android.package-archive\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(apk);
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            if (configJson != null) {
                out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"config\"\r\nContent-Type: application/json\r\n\r\n" + configJson + "\r\n").getBytes(StandardCharsets.UTF_8));
            }
            out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return send("POST", "/sessions", out.toByteArray(), "multipart/form-data; boundary=" + boundary, Map.of());
    }

    /** One server-sent event. */
    public record Sse(String event, String id, JsonNode data) {}

    /** Reads SSE events from `path` until `count` events named `name` arrived or the timeout passed; then closes the stream. */
    public List<Sse> readEvents(String path, String name, int count, Duration timeout) {
        return readUntil(path, events -> events.stream().filter(e -> name.equals(e.event())).count() >= count, timeout);
    }

    /** Reads SSE events from `path` until `done` accepts the events received so far (or the timeout passed); then closes the stream. */
    public List<Sse> readUntil(String path, java.util.function.Predicate<List<Sse>> done, Duration timeout) {
        List<Sse> events = new ArrayList<>();
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path)).header("Accept", "text/event-stream");
            if (token != null) builder.header("Authorization", "Bearer " + token);
            HttpResponse<java.io.InputStream> response = CLIENT.send(builder.GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            Thread reader = Thread.ofVirtual().start(() -> {
                try (BufferedReader lines = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                    String event = null, id = null; StringBuilder data = new StringBuilder();
                    String line;
                    while ((line = lines.readLine()) != null) {
                        if (line.isEmpty()) {
                            if (data.length() > 0) {
                                synchronized (events) { events.add(new Sse(event, id, JSON.readTree(data.toString()))); }
                                synchronized (events) { if (done.test(events)) break; }
                            }
                            event = null; id = null; data.setLength(0);
                        } else if (line.startsWith("event:")) event = line.substring(6).trim();
                        else if (line.startsWith("id:")) id = line.substring(3).trim();
                        else if (line.startsWith("data:")) data.append(line.substring(5).trim());
                    }
                } catch (IOException ignored) { /* stream closed */ }
            });
            reader.join(timeout);
            response.body().close();
            reader.interrupt();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        synchronized (events) { return List.copyOf(events); }
    }

    public static boolean nodeAvailable() {
        try {
            Process process = new ProcessBuilder(System.getenv().getOrDefault("APK_LAB_NODE", "node"), "--version").redirectErrorStream(true).start();
            return process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** The smallest body the runner accepts as an APK (a ZIP signature); the fake sidecar never opens it. */
    public static byte[] fakeApk() { return new byte[] {0x50, 0x4b, 3, 4, 0, 0, 0, 0}; }
}
