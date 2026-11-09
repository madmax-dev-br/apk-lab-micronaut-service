package dev.apklab.runner.sidecar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * One Node sidecar process and its JSON-lines protocol (see sidecar/vm-host.mjs):
 * requests {"id", "op", "args"} on stdin; responses {"id", "ok", "result" | "error"} and events {"event", ...} on stdout; logs on stderr.
 * Limits: one line at most {@code maxLineBytes} in each direction; every call has a timeout. A protocol violation, an oversized line, a timeout or the end
 * of the process fails every pending call with a "crashed" {@link SidecarException} and stops the process.
 */
public final class SidecarProcess implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(SidecarProcess.class);
    private static final int STDERR_TAIL = 30;

    public enum State { STARTING, READY, STOPPED }

    /** An event line of the sidecar: logcat, netlog, session, plugin. */
    public record Event(String type, JsonNode message) {}

    private final String label;
    private final List<String> command;
    private final Map<String, String> environment;
    private final ObjectMapper mapper;
    private final long maxLineBytes;
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();
    private final CompletableFuture<JsonNode> ready = new CompletableFuture<>();
    private final CompletableFuture<Integer> exited = new CompletableFuture<>();
    private final Deque<String> stderrTail = new ArrayDeque<>();
    private volatile Consumer<Event> eventListener = event -> {};
    private volatile State state = State.STARTING;
    private volatile String fatalMessage;
    private volatile String stopReason;
    private Process process;
    private OutputStream stdin;

    public SidecarProcess(String label, List<String> command, Map<String, String> environment, ObjectMapper mapper, long maxLineBytes) {
        this.label = label;
        this.command = List.copyOf(command);
        this.environment = Map.copyOf(environment);
        this.mapper = mapper;
        this.maxLineBytes = maxLineBytes;
    }

    public void onEvent(Consumer<Event> listener) { this.eventListener = listener == null ? event -> {} : listener; }

    /** Starts the process and waits for its "ready" line (the runtime bundle loaded). */
    public JsonNode start(Duration timeout) {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().clear();   // the sidecar never sees the runner's secrets (RUNNER_TOKEN, ...): only what it needs
        builder.environment().putAll(environment);
        try {
            process = builder.start();
        } catch (IOException e) {
            state = State.STOPPED;
            throw new SidecarException(503, "sidecar-start-failed", "Could not start the sidecar (" + command.get(0) + "): " + e.getMessage()
                    + ". Node.js 22+ must be installed (RUNNER_NODE names the executable).", null, true);
        }
        stdin = process.getOutputStream();
        Thread out = Thread.ofVirtual().name("sidecar-out-" + label).start(this::readStdout);
        Thread err = Thread.ofVirtual().name("sidecar-err-" + label).start(this::readStderr);
        process.onExit().thenAccept(p -> onExit(p.exitValue()));
        try {
            JsonNode hello = ready.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            state = State.READY;
            return hello;
        } catch (TimeoutException e) {
            stop("start timeout");
            throw new SidecarException(503, "sidecar-start-failed", "The sidecar did not become ready within " + timeout.toSeconds() + " s" + tailSuffix(), null, true);
        } catch (Exception e) {
            stop("start failed");
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof SidecarException se) throw new SidecarException(503, "sidecar-start-failed", se.getMessage(), null, true);
            throw new SidecarException(503, "sidecar-start-failed", "The sidecar failed to start: " + cause.getMessage() + tailSuffix(), null, true);
        } finally {
            if (state != State.READY && out.isAlive()) out.interrupt();
            if (state != State.READY && err.isAlive()) err.interrupt();
        }
    }

    public State state() { return state; }
    public boolean alive() { return state == State.READY && process != null && process.isAlive(); }
    public long pid() { return process == null ? -1 : process.pid(); }
    public CompletableFuture<Integer> exited() { return exited; }

    /** Sends one request and waits for its response. Timeouts stop the process (an unresponsive sidecar cannot be trusted with the next request). */
    public JsonNode call(String op, JsonNode args, Duration timeout) {
        if (!alive()) throw SidecarException.crashed("The sidecar is not running" + (stopReason != null ? " (" + stopReason + ")" : "") + tailSuffix());
        long id = ids.incrementAndGet();
        ObjectNode request = mapper.createObjectNode();
        request.put("id", id);
        request.put("op", op);
        request.set("args", args == null ? mapper.createObjectNode() : args);
        byte[] line;
        try {
            line = (mapper.writeValueAsString(request) + "\n").getBytes(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SidecarException(400, "bad-request", "Request could not be encoded: " + e.getMessage(), null, false);
        }
        if (line.length > maxLineBytes) throw new SidecarException(413, "too-large", "The request is larger than the sidecar line limit (" + maxLineBytes + " bytes)", null, false);
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        try {
            synchronized (this) {
                stdin.write(line);
                stdin.flush();
            }
        } catch (IOException e) {
            pending.remove(id);
            stop("stdin closed");
            throw SidecarException.crashed("The sidecar stopped reading requests: " + e.getMessage() + tailSuffix());
        }
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.remove(id);
            stop("timeout of " + op);
            throw SidecarException.timeout(op, timeout.toMillis());
        } catch (InterruptedException e) {
            pending.remove(id);
            Thread.currentThread().interrupt();
            throw new SidecarException(503, "interrupted", "Interrupted while waiting for the sidecar", null, false);
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof SidecarException se) throw se;
            throw SidecarException.crashed(String.valueOf(e.getCause()));
        }
    }

    /** Asks the sidecar to close its session and exit; kills it after the grace period. */
    @Override
    public void close() {
        if (process == null) return;
        if (alive()) {
            try { call("shutdown", mapper.createObjectNode(), Duration.ofSeconds(3)); } catch (RuntimeException ignored) { /* stopping anyway */ }
        }
        stop("closed");
    }

    /** Stops the process now (closing stdin first, then SIGTERM, then SIGKILL). */
    public void stop(String reason) {
        if (stopReason == null) stopReason = reason;
        state = State.STOPPED;
        if (process == null) return;
        try { stdin.close(); } catch (IOException ignored) { /* already closed */ }
        if (process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
        failPending("The sidecar was stopped (" + reason + ")");
    }

    public String lastStderr() {
        synchronized (stderrTail) { return String.join("\n", stderrTail); }
    }
    public String fatalMessage() { return fatalMessage; }

    // ---- reading -------------------------------------------------------------------------------------------------------------------------------------------------
    private void readStdout() {
        try (InputStream in = process.getInputStream()) {
            ByteArrayOutputStream line = new ByteArrayOutputStream(8192);
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) >= 0) {
                int start = 0;
                for (int i = 0; i < n; i++) {
                    if (buffer[i] != '\n') continue;
                    line.write(buffer, start, i - start);
                    handleLine(line.toByteArray());
                    line.reset();
                    start = i + 1;
                }
                line.write(buffer, start, n - start);
                if (line.size() > maxLineBytes) {
                    protocolError("a line of the sidecar is larger than " + maxLineBytes + " bytes");
                    return;
                }
            }
        } catch (IOException e) {
            LOG.debug("sidecar {} stdout closed: {}", label, e.getMessage());
        }
    }

    private void handleLine(byte[] bytes) {
        if (bytes.length == 0) return;
        JsonNode message;
        try {
            message = mapper.readTree(bytes);
        } catch (IOException e) {
            protocolError("the sidecar wrote a line that is not JSON: " + new String(bytes, 0, Math.min(bytes.length, 200), StandardCharsets.UTF_8));
            return;
        }
        if (message.hasNonNull("event")) {
            String type = message.get("event").asText();
            switch (type) {
                case "ready" -> ready.complete(message);
                case "fatal" -> {
                    fatalMessage = message.path("message").asText();
                    LOG.warn("sidecar {} fatal: {}", label, fatalMessage);
                    ready.completeExceptionally(new SidecarException(503, "sidecar-fatal", fatalMessage, null, true));
                }
                default -> {
                    try { eventListener.accept(new Event(type, message)); } catch (RuntimeException e) { LOG.warn("sidecar {} event listener failed: {}", label, e.toString()); }
                }
            }
            return;
        }
        JsonNode idNode = message.get("id");
        if (idNode == null || !idNode.canConvertToLong()) {
            JsonNode error = message.path("error");
            LOG.warn("sidecar {} answered without an id: {}", label, error.isMissingNode() ? message : error);
            return;
        }
        CompletableFuture<JsonNode> future = pending.remove(idNode.asLong());
        if (future == null) return;   // timed out already
        if (message.path("ok").asBoolean(false)) {
            future.complete(message.get("result"));
        } else {
            JsonNode error = message.path("error");
            JsonNode details = error.get("details");
            future.completeExceptionally(new SidecarException(error.path("status").asInt(500), error.path("code").asText("internal"),
                    error.path("message").asText("sidecar error"), details == null || details.isNull() ? null : details, false));
        }
    }

    private void readStderr() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.length() > 2000 ? line.substring(0, 2000) + "…" : line;
                synchronized (stderrTail) {
                    stderrTail.addLast(trimmed);
                    while (stderrTail.size() > STDERR_TAIL) stderrTail.removeFirst();
                }
                LOG.debug("[{}] {}", label, trimmed);
            }
        } catch (IOException e) {
            LOG.debug("sidecar {} stderr closed", label);
        }
    }

    private void protocolError(String message) {
        LOG.warn("sidecar {}: protocol error: {}", label, message);
        stop("protocol error: " + message);
    }

    private void onExit(int code) {
        state = State.STOPPED;
        exited.complete(code);
        String reason = fatalMessage != null ? fatalMessage : stopReason != null ? stopReason : "exit code " + code;
        ready.completeExceptionally(SidecarException.crashed("The sidecar exited (" + reason + ")" + tailSuffix()));
        failPending("The sidecar exited (" + reason + ")");
        if (stopReason == null) LOG.warn("sidecar {} exited unexpectedly (code {}){}", label, code, tailSuffix());
    }

    private void failPending(String message) {
        for (Long id : List.copyOf(pending.keySet())) {
            CompletableFuture<JsonNode> future = pending.remove(id);
            if (future != null) future.completeExceptionally(SidecarException.crashed(message + tailSuffix()));
        }
    }

    private String tailSuffix() {
        String tail = lastStderr();
        if (tail.isBlank()) return "";
        String[] lines = tail.split("\n");
        int from = Math.max(0, lines.length - 5);
        return "; last sidecar output: " + String.join(" | ", java.util.Arrays.copyOfRange(lines, from, lines.length));
    }
}
