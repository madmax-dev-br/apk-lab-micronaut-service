package dev.apklab.queue.runners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.apklab.queue.QueueSettings;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** A runner reached over HTTP with its bearer token (the runner API of this repository, or of the Node service: both have the same paths). */
public class HttpRunnerGateway implements RunnerGateway {
    private final QueueSettings.RunnerRef ref;
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    private volatile boolean healthy;
    private volatile int available;
    private volatile int assignedSinceHealth;
    private volatile long lastAssignedAt;
    private volatile boolean pluginsAllowed;
    private volatile Integer maxSessions;
    private volatile String runtimeRevision;
    private volatile String lastError;
    private volatile Instant lastHealth;

    public HttpRunnerGateway(QueueSettings.RunnerRef ref, ObjectMapper mapper) {
        this.ref = ref;
        this.mapper = mapper;
    }

    @Override public String id() { return ref.id(); }
    @Override public String url() { return ref.url(); }
    @Override public boolean healthy() { return healthy; }
    @Override public int available() { return available; }
    @Override public int assignedSinceHealth() { return assignedSinceHealth; }
    @Override public long lastAssignedAt() { return lastAssignedAt; }
    @Override public boolean pluginsAllowed() { return pluginsAllowed; }
    @Override public synchronized void noteAssigned() { assignedSinceHealth++; lastAssignedAt = System.currentTimeMillis(); }
    @Override public void markFull() { available = 0; }

    private HttpRequest.Builder request(String path, Duration timeout) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(ref.url() + path)).timeout(timeout);
        if (ref.token() != null && !ref.token().isBlank()) builder.header("Authorization", "Bearer " + ref.token());
        return builder;
    }

    @Override
    public void refreshHealth() {
        try {
            HttpResponse<String> response = client.send(request("/health", Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            JsonNode health = mapper.readTree(response.body());
            synchronized (this) {
                healthy = response.statusCode() == 200 && health.path("ok").asBoolean(false);
                available = health.path("available").asInt(0);
                maxSessions = health.hasNonNull("maxSessions") ? health.get("maxSessions").asInt() : null;
                pluginsAllowed = health.path("pluginsAllowed").asBoolean(false);
                runtimeRevision = health.path("runtimeRevision").asText(null);
                assignedSinceHealth = 0;
                lastError = healthy ? null : health.path("problem").asText("runner reported not ok");
            }
        } catch (IOException | RuntimeException e) {
            healthy = false;
            available = 0;
            lastError = "unreachable: " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        lastHealth = Instant.now();
    }

    @Override
    public Map<String, Object> describe() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", ref.id());
        out.put("url", ref.url());
        out.put("healthy", healthy);
        out.put("available", available);
        out.put("maxSessions", maxSessions);
        out.put("pluginsAllowed", pluginsAllowed);
        out.put("runtimeRevision", runtimeRevision);
        out.put("lastHealth", lastHealth == null ? null : lastHealth.toString());
        out.put("lastError", lastError);
        return out;
    }

    private JsonNode send(HttpRequest request) throws RunnerError {
        HttpResponse<byte[]> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new RunnerError("runner " + ref.id() + " unreachable: " + e.getMessage(), 0, "runner-unreachable", true, false, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RunnerError("cancelled", 0, "cancelled", false, false, null);
        }
        JsonNode json = null;
        try { if (response.body().length > 0) json = mapper.readTree(response.body()); } catch (IOException ignored) { /* not JSON */ }
        if (response.statusCode() >= 300) throw RunnerError.fromResponse(ref.id(), response.statusCode(), json, new String(response.body(), 0, Math.min(500, response.body().length), StandardCharsets.UTF_8));
        return json;
    }

    @Override
    public JsonNode createSession(byte[] apk, JsonNode config, Duration timeout) throws RunnerError {
        String header;
        try { header = Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(config == null ? mapper.createObjectNode() : config)); }
        catch (IOException e) { throw new RunnerError("config not encodable", 0, "bad-config", false, false, null); }
        return send(request("/sessions", timeout).header("Content-Type", "application/vnd.android.package-archive").header("X-APK-Config", header)
                .POST(HttpRequest.BodyPublishers.ofByteArray(apk)).build());
    }

    @Override
    public JsonNode call(String sessionId, String method, String path, JsonNode body, Duration timeout) throws RunnerError {
        HttpRequest.Builder builder = request("/sessions/" + enc(sessionId) + path, timeout);
        if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
        else {
            try { builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body))); }
            catch (IOException e) { throw new RunnerError("body not encodable", 0, "bad-body", false, false, null); }
        }
        return send(builder.build());
    }

    @Override
    public Bytes bytes(String sessionId, String path, Duration timeout) throws RunnerError {
        HttpResponse<byte[]> response;
        try {
            response = client.send(request("/sessions/" + enc(sessionId) + path, timeout).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new RunnerError("runner " + ref.id() + " unreachable: " + e.getMessage(), 0, "runner-unreachable", true, false, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RunnerError("cancelled", 0, "cancelled", false, false, null);
        }
        if (response.statusCode() >= 300) {
            JsonNode json = null;
            try { json = mapper.readTree(response.body()); } catch (IOException ignored) { /* not JSON */ }
            throw RunnerError.fromResponse(ref.id(), response.statusCode(), json, "");
        }
        return new Bytes(response.body(), response.headers().firstValue("content-type").orElse("application/octet-stream"), response.headers().firstValue("x-screenshot-notes").orElse(null));
    }

    @Override
    public void deleteSession(String sessionId) {
        if (sessionId == null) return;
        try {
            client.send(request("/sessions/" + enc(sessionId), Duration.ofSeconds(10)).DELETE().build(), HttpResponse.BodyHandlers.discarding());
        } catch (IOException ignored) {
            // already gone
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public Raw forward(String method, String sessionId, String pathAndQuery, Map<String, String> headers, byte[] body) throws RunnerError {
        HttpRequest.Builder builder = request("/sessions/" + enc(sessionId) + pathAndQuery, Duration.ofHours(1));
        headers.forEach(builder::header);
        builder.method(method, body == null || body.length == 0 ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        try {
            HttpResponse<InputStream> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            return new Raw(response.statusCode(), response.headers().map(), response.body());
        } catch (IOException e) {
            throw new RunnerError("runner " + ref.id() + " unreachable: " + e.getMessage(), 502, "runner-unreachable", true, false, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RunnerError("cancelled", 0, "cancelled", false, false, null);
        }
    }

    private static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
