package dev.apklab.runner.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.apklab.api.ApiException;
import dev.apklab.runner.sidecar.SidecarException;
import dev.apklab.runner.sidecar.SidecarProcess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * One runner session: an APK file, its normalized config and the sidecar process that runs it. Operations go to the sidecar; listeners of the event channels
 * (logcat, network, session events, plugins) are counted so the sidecar forwards (and the VM produces Logcat lines) only while someone listens.
 */
public final class RunnerSession {
    private static final Logger LOG = LoggerFactory.getLogger(RunnerSession.class);

    public enum Channel { LOGCAT("logcat", "logcat"), NETLOG("netlog", "netlog"), EVENTS("events", "session"), PLUGINS("plugins", "plugin");
        final String subscribeKey; final String eventType;
        Channel(String subscribeKey, String eventType) { this.subscribeKey = subscribeKey; this.eventType = eventType; }
    }

    private final String id;
    private final String owner;
    private final Path dir;
    private final Path apkFile;
    private final long apkBytes;
    private final Instant createdAt = Instant.now();
    private final ObjectMapper mapper;
    private final Supplier<SidecarProcess> sidecarFactory;
    private final JsonNode initArgs;
    private final Duration operationTimeout;
    private final Duration loadTimeout;
    private final Map<Channel, Set<Consumer<JsonNode>>> listeners = new EnumMap<>(Channel.class);
    private final Map<Channel, Boolean> subscribed = new ConcurrentHashMap<>();
    private final Set<Runnable> closeHooks = new CopyOnWriteArraySet<>();
    private volatile SidecarProcess sidecar;
    private volatile Instant lastUsedAt = Instant.now();
    private volatile String state = "created";
    private volatile JsonNode config;
    private volatile String closeReason;
    private volatile String lastCrash;
    private volatile int restarts;

    public RunnerSession(String id, String owner, Path dir, Path apkFile, long apkBytes, ObjectMapper mapper, Supplier<SidecarProcess> sidecarFactory, JsonNode initArgs,
                         Duration operationTimeout, Duration loadTimeout) {
        this.id = id;
        this.owner = owner;
        this.dir = dir;
        this.apkFile = apkFile;
        this.apkBytes = apkBytes;
        this.mapper = mapper;
        this.sidecarFactory = sidecarFactory;
        this.initArgs = initArgs;
        this.operationTimeout = operationTimeout;
        this.loadTimeout = loadTimeout;
        for (Channel channel : Channel.values()) listeners.put(channel, new CopyOnWriteArraySet<>());
    }

    public String id() { return id; }
    public String owner() { return owner; }
    public Instant createdAt() { return createdAt; }
    public Instant lastUsedAt() { return lastUsedAt; }
    public String state() { return state; }
    public JsonNode config() { return config; }
    public long apkBytes() { return apkBytes; }
    public boolean closed() { return "closed".equals(state); }
    public void touch() { lastUsedAt = Instant.now(); }
    public int listenerCount() { return listeners.values().stream().mapToInt(Set::size).sum(); }
    public long sidecarPid() { SidecarProcess s = sidecar; return s == null ? -1 : s.pid(); }

    /** Starts the sidecar, creates the session in it and loads the APK with `config` (validated again by the lab's sanitizers inside the sidecar). */
    public JsonNode start(JsonNode config) {
        this.config = config;
        state = "loading";
        JsonNode result = spawnAndLoad(config);
        state = "ready";
        return result;
    }

    private JsonNode spawnAndLoad(JsonNode config) {
        SidecarProcess process = sidecarFactory.get();
        process.onEvent(this::dispatch);
        process.exited().thenAccept(code -> onSidecarExit(process, code));
        this.sidecar = process;
        process.call("init", initArgs, operationTimeout);
        ObjectNode load = mapper.createObjectNode();
        load.put("apkPath", apkFile.toString());
        load.set("config", config == null ? mapper.createObjectNode() : config);
        JsonNode result = process.call("load", load, loadTimeout.plus(operationTimeout));
        if (result != null && result.hasNonNull("config")) this.config = result.get("config");
        resubscribe();
        return result;
    }

    /** One protocol call; sidecar errors become API errors (status, code, message, details of the lab). */
    public JsonNode call(String op, JsonNode args) { return call(op, args, operationTimeout); }
    /** How long one sidecar operation may take (runner.operation-timeout-seconds). */
    public Duration operationTimeout() { return operationTimeout; }

    public JsonNode call(String op, JsonNode args, Duration timeout) {
        if (closed()) throw ApiException.conflict("session-closed", "The session is closed" + (closeReason != null ? " (" + closeReason + ")" : ""));
        SidecarProcess process = sidecar;
        if (process == null || !process.alive()) {
            throw new ApiException(409, "vm-not-running", "The session's sidecar is not running" + (lastCrash != null ? " (" + lastCrash + ")" : "")
                    + "; POST /sessions/" + id + "/restart loads the APK again", null, null);
        }
        touch();
        try {
            JsonNode result = process.call(op, args == null ? mapper.createObjectNode() : args, timeout);
            touch();
            return result;
        } catch (SidecarException e) {
            if (e.crashed()) {
                state = "crashed";
                lastCrash = e.getMessage();
            }
            Integer retryAfter = e.details() != null && e.details().has("retryAfter") ? e.details().get("retryAfter").asInt() : null;
            throw new ApiException(e.status(), e.code(), e.getMessage(), e.details(), retryAfter);
        }
    }

    public ObjectNode args() { return mapper.createObjectNode(); }

    /** POST /sessions/{id}/restart: in the running sidecar, or in a new one when the sidecar crashed (the APK file and the config are kept by the runner). */
    public synchronized JsonNode restart(JsonNode partialConfig) {
        if (closed()) throw ApiException.conflict("session-closed", "The session is closed");
        SidecarProcess process = sidecar;
        if (process != null && process.alive()) {
            ObjectNode args = args();
            if (partialConfig != null) args.set("config", partialConfig);
            JsonNode result = call("restart", args, loadTimeout.plus(operationTimeout));
            refreshConfig();
            state = "ready";
            return result;
        }
        if (process != null) process.stop("restart");
        JsonNode next = config == null ? mapper.createObjectNode() : config.deepCopy();
        if (partialConfig != null && partialConfig.isObject() && next.isObject()) partialConfig.fields().forEachRemaining(e -> ((ObjectNode) next).set(e.getKey(), e.getValue()));
        restarts++;
        state = "loading";
        try {
            JsonNode result = spawnAndLoad(next);
            state = "ready";
            lastCrash = null;
            return mapper.createObjectNode().set("session", result.path("session"));
        } catch (SidecarException e) {
            state = "crashed";
            lastCrash = e.getMessage();
            throw new ApiException(e.status() == 500 ? 503 : e.status(), e.code(), e.getMessage(), e.details(), null);
        }
    }

    public void refreshConfig() {
        try { config = call("getConfig", args()); } catch (ApiException ignored) { /* keep the previous one */ }
    }
    public void setConfig(JsonNode config) { if (config != null && config.isObject()) this.config = config; }

    // ---- event channels ----------------------------------------------------------------------------------------------------------------------------------------
    public Runnable listen(Channel channel, Consumer<JsonNode> listener) {
        listeners.get(channel).add(listener);
        updateSubscription(channel);
        return () -> {
            listeners.get(channel).remove(listener);
            updateSubscription(channel);
        };
    }

    private void updateSubscription(Channel channel) {
        boolean wanted = !listeners.get(channel).isEmpty();
        if (Boolean.valueOf(wanted).equals(subscribed.get(channel))) return;
        subscribed.put(channel, wanted);
        SidecarProcess process = sidecar;
        if (process == null || !process.alive()) return;
        ObjectNode args = args();
        args.put(channel.subscribeKey, wanted);
        Thread.ofVirtual().start(() -> {
            try { process.call("subscribe", args, operationTimeout); } catch (RuntimeException e) { LOG.debug("session {}: subscribe failed: {}", id, e.getMessage()); }
        });
    }

    private void resubscribe() {
        ObjectNode args = args();
        boolean any = false;
        for (Channel channel : Channel.values()) {
            boolean wanted = !listeners.get(channel).isEmpty();
            subscribed.put(channel, wanted);
            args.put(channel.subscribeKey, wanted);
            any |= wanted;
        }
        if (any) {
            try { sidecar.call("subscribe", args, operationTimeout); } catch (RuntimeException e) { LOG.debug("session {}: subscribe failed: {}", id, e.getMessage()); }
        }
    }

    private void dispatch(SidecarProcess.Event event) {
        for (Channel channel : Channel.values()) {
            if (!channel.eventType.equals(event.type())) continue;
            for (Consumer<JsonNode> listener : listeners.get(channel)) {
                try { listener.accept(event.message()); } catch (RuntimeException e) { LOG.debug("session {}: listener failed: {}", id, e.toString()); }
            }
        }
    }

    private void onSidecarExit(SidecarProcess process, int code) {
        if (process != sidecar || closed()) return;
        state = "crashed";
        String fatal = process.fatalMessage();
        lastCrash = "sidecar exited with code " + code + (fatal != null ? ": " + fatal : "");
        ObjectNode data = mapper.createObjectNode();
        data.put("code", code);
        data.put("message", lastCrash);
        ObjectNode message = mapper.createObjectNode();
        message.put("event", "session");
        message.put("name", "crashed");
        message.set("data", data);
        for (Consumer<JsonNode> listener : listeners.get(Channel.EVENTS)) {
            try { listener.accept(message); } catch (RuntimeException ignored) { /* closed stream */ }
        }
    }

    public ObjectNode describeLocal() {
        ObjectNode out = mapper.createObjectNode();
        out.put("id", id);
        out.put("state", state);
        out.put("createdAt", createdAt.toString());
        out.put("lastUsedAt", lastUsedAt.toString());
        out.put("apkBytes", apkBytes);
        out.put("restarts", restarts);
        out.put("listeners", listenerCount());
        if (lastCrash != null) out.put("lastCrash", lastCrash);
        if (closeReason != null) out.put("closeReason", closeReason);
        return out;
    }

    /** Session description: the sidecar's (VM state, package, activity, metrics) plus the runner's fields. */
    public JsonNode describe() {
        ObjectNode local = describeLocal();
        SidecarProcess process = sidecar;
        if (process == null || !process.alive() || closed()) return local;
        try {
            JsonNode remote = process.call("describe", args(), Duration.ofSeconds(10));
            if (remote instanceof ObjectNode node) {
                ObjectNode merged = node.deepCopy();
                merged.put("state", "crashed".equals(state) ? state : remote.path("state").asText(state));
                merged.put("apkBytes", apkBytes);
                merged.put("restarts", restarts);
                merged.put("listeners", listenerCount());
                merged.put("createdAt", createdAt.toString());
                merged.put("lastUsedAt", lastUsedAt.toString());
                return merged;
            }
        } catch (RuntimeException e) {
            LOG.debug("session {}: describe failed: {}", id, e.getMessage());
        }
        return local;
    }

    public synchronized void close(String reason) {
        if (closed()) return;
        closeReason = reason;
        state = "closed";
        ObjectNode message = mapper.createObjectNode();
        message.put("event", "session");
        message.put("name", "closed");
        message.set("data", mapper.createObjectNode().put("reason", reason));
        for (Consumer<JsonNode> listener : listeners.get(Channel.EVENTS)) {
            try { listener.accept(message); } catch (RuntimeException ignored) { /* closed stream */ }
        }
        for (Runnable hook : closeHooks) {
            try { hook.run(); } catch (RuntimeException ignored) { /* closed stream */ }
        }
        SidecarProcess process = sidecar;
        if (process != null) process.close();
        deleteDir();
    }

    private void deleteDir() {
        if (dir == null || !Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { /* best effort */ }
            });
        } catch (IOException e) {
            LOG.debug("session {}: could not delete {}: {}", id, dir, e.getMessage());
        }
    }

    /** Runs `hook` when the session closes (streams end with it); returns the deregistration. */
    public Runnable onClose(Runnable hook) {
        closeHooks.add(hook);
        return () -> closeHooks.remove(hook);
    }
}
