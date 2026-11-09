package dev.apklab.queue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.apklab.queue.runners.RunnerError;
import dev.apklab.queue.runners.RunnerGateway;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** An in-memory runner for the queue's scheduling tests: capacity, scripted failures, slow steps, and a record of every call. */
public class FakeRunner implements RunnerGateway {
    static final ObjectMapper JSON = new ObjectMapper();
    final String id;
    volatile int capacity;
    volatile boolean healthy = true;
    volatile boolean plugins;
    volatile long stepDelayMs;
    final Set<String> open = ConcurrentHashMap.newKeySet();
    final List<String> calls = new CopyOnWriteArrayList<>();
    final Queue<RunnerError> createFailures = new ConcurrentLinkedQueue<>();
    final Map<String, RunnerError> pathFailures = new ConcurrentHashMap<>();
    final AtomicInteger created = new AtomicInteger();
    final AtomicInteger maxOpen = new AtomicInteger();
    private volatile int available;
    private volatile int assignedSinceHealth;
    private volatile long lastAssignedAt;

    FakeRunner(String id, int capacity) {
        this.id = id;
        this.capacity = capacity;
        refreshHealth();
    }

    static RunnerError infrastructure(String message) { return new RunnerError(message, 503, "sidecar-crashed", true, false, null); }
    static RunnerError atCapacity() { return new RunnerError("full", 503, "at-capacity", false, true, null); }
    static RunnerError appError() { return new RunnerError("No element on the screen matches", 404, "element-not-found", false, false, null); }

    @Override public String id() { return id; }
    @Override public String url() { return "fake://" + id; }
    @Override public synchronized void refreshHealth() { available = healthy ? Math.max(0, capacity - open.size()) : 0; assignedSinceHealth = 0; }
    @Override public boolean healthy() { return healthy; }
    @Override public int available() { return available; }
    @Override public int assignedSinceHealth() { return assignedSinceHealth; }
    @Override public long lastAssignedAt() { return lastAssignedAt; }
    @Override public boolean pluginsAllowed() { return plugins; }
    @Override public synchronized void noteAssigned() { assignedSinceHealth++; lastAssignedAt = System.nanoTime(); }
    @Override public void markFull() { available = 0; }
    @Override public Map<String, Object> describe() { return Map.of("id", id, "healthy", healthy, "available", available); }

    @Override
    public JsonNode createSession(byte[] apk, JsonNode config, Duration timeout) {
        calls.add("create");
        RunnerError failure = createFailures.poll();
        if (failure != null) throw failure;
        String session = id + "-s" + created.incrementAndGet();
        open.add(session);
        maxOpen.accumulateAndGet(open.size(), Math::max);
        ObjectNode out = JSON.createObjectNode();
        out.putObject("session").put("id", session).put("packageName", "test.fake").put("runtimeRevision", "fake").putObject("metrics").put("loadMs", 1);
        out.set("config", config);
        return out;
    }

    @Override
    public JsonNode call(String sessionId, String method, String path, JsonNode body, Duration timeout) {
        calls.add(method + " " + path);
        if (!open.contains(sessionId)) throw new RunnerError("no session", 404, "not-found", false, false, null);
        RunnerError failure = pathFailures.get(path);
        if (failure != null) throw failure;
        if (stepDelayMs > 0) {
            try { Thread.sleep(stepDelayMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RunnerError("cancelled", 0, "cancelled", false, false, null); }
        }
        ObjectNode out = JSON.createObjectNode();
        switch (path) {
            case "/launch" -> out.put("launchMs", 5).putObject("snapshot").put("activity", "test.fake.MainActivity");
            case "/tap" -> out.put("via", "semantics").putObject("element").put("key", "c:1:2").put("name", "Button");
            case "/view-models" -> { return JSON.createArrayNode().add(JSON.createObjectNode().put("className", "test.fake.CounterModel").put("handle", "h1")); }
            case "/field" -> out.put("$kind", "MutableState").put("value", 3);
            default -> out.put("ok", true).put("path", path);
        }
        return out;
    }

    @Override
    public Bytes bytes(String sessionId, String path, Duration timeout) {
        calls.add("GET " + path);
        return new Bytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'}, "image/png", "{\"approximatedText\":0}");
    }

    @Override
    public void deleteSession(String sessionId) {
        calls.add("delete");
        open.remove(sessionId);
    }

    @Override
    public Raw forward(String method, String sessionId, String pathAndQuery, Map<String, String> headers, byte[] body) {
        calls.add("forward " + method + " " + pathAndQuery);
        return new Raw(200, Map.of("content-type", List.of("application/json")), new ByteArrayInputStream(("{\"forwarded\":\"" + pathAndQuery + "\"}").getBytes()));
    }
}
