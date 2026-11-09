package dev.apklab.runner.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.apklab.api.ApiException;
import dev.apklab.runner.RunnerSettings;
import dev.apklab.runner.sidecar.SidecarLauncher;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** The runner's sessions: capacity, creation (APK file + sidecar + load), lookup, idle / max-age sweeping and shutdown. */
@Singleton
public class SessionManager {
    private static final Logger LOG = LoggerFactory.getLogger(SessionManager.class);

    private final RunnerSettings settings;
    private final SidecarLauncher launcher;
    private final ObjectMapper mapper;
    private final Map<String, RunnerSession> sessions = new ConcurrentHashMap<>();
    private final AtomicInteger reserved = new AtomicInteger();

    public SessionManager(RunnerSettings settings, SidecarLauncher launcher, ObjectMapper mapper) {
        this.settings = settings;
        this.launcher = launcher;
        this.mapper = mapper;
    }

    public record Capacity(int maxSessions, int activeSessions, int reserved, int available, long freeMemoryMb) {}

    public Capacity capacity() {
        int active = sessions.size(), held = reserved.get();
        long free = freeMemoryMb();
        return new Capacity(settings.getMaxSessions(), active, held, Math.max(0, settings.getMaxSessions() - active - held), free);
    }

    private static long freeMemoryMb() {
        try {
            if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) return os.getFreeMemorySize() / (1024 * 1024);
        } catch (RuntimeException | LinkageError ignored) { /* not available */ }
        return -1;
    }

    /** Creates a session: reserves a slot, writes the APK, starts the sidecar and loads the APK. Fails with 503 at capacity, 4xx for a bad APK or config. */
    public ObjectNode create(byte[] apk, JsonNode config, String owner, String namespace) {
        if (apk == null || apk.length == 0) throw ApiException.badRequest("No APK in the request body");
        if (apk.length > settings.maxApkBytes()) throw new ApiException(413, "too-large", "APK larger than " + settings.getMaxApkMb() + " MiB (RUNNER_MAX_APK_MB)");
        if (!(apk[0] == 0x50 && apk[1] == 0x4b)) throw ApiException.badRequest("The body is not an APK (ZIP) file");
        int slot = reserved.incrementAndGet();
        if (sessions.size() + slot > settings.getMaxSessions()) {
            reserved.decrementAndGet();
            throw new ApiException(503, "at-capacity", "The runner is at capacity (" + sessions.size() + "/" + settings.getMaxSessions() + " sessions)", null, 5);
        }
        String id = UUID.randomUUID().toString();
        Path dir = settings.workPath().resolve("sessions").resolve(id);
        RunnerSession session = null;
        try {
            Files.createDirectories(dir);
            Path apkFile = dir.resolve("app.apk");
            Files.write(apkFile, apk);
            ObjectNode init = mapper.createObjectNode();
            init.put("sessionId", id);
            if (owner != null) init.put("owner", owner);
            init.set("policy", policy());
            init.set("limits", limits());
            String appDataDir = appDataDirFor(namespace);
            if (appDataDir != null) init.put("appDataDir", appDataDir);
            session = new RunnerSession(id, owner, dir, apkFile, apk.length, mapper, () -> launcher.start(id.substring(0, 8)), init,
                    Duration.ofSeconds(settings.getOperationTimeoutSeconds() + (long) settings.getCallMarginSeconds()), Duration.ofSeconds(settings.getLoadTimeoutSeconds()));
            sessions.put(id, session);
            reserved.decrementAndGet();
            slot = 0;
            LOG.info("session {} created ({} bytes)", id, apk.length);
            JsonNode result;
            try {
                result = session.start(config);
            } catch (RuntimeException e) {
                close(session, "load-failed");
                throw toApi(e);
            }
            return result instanceof ObjectNode node ? node : mapper.createObjectNode().set("result", result);
        } catch (IOException e) {
            if (session != null) close(session, "load-failed");
            throw new ApiException(500, "io-error", "Could not store the APK: " + e.getMessage());
        } finally {
            if (slot > 0) reserved.decrementAndGet();
        }
    }

    private static ApiException toApi(RuntimeException e) {
        if (e instanceof ApiException api) return api;
        if (e instanceof dev.apklab.runner.sidecar.SidecarException se) return new ApiException(se.status(), se.code(), se.getMessage(), se.details(), null);
        return new ApiException(422, "load-failed", String.valueOf(e.getMessage()));
    }

    public ObjectNode policy() {
        ObjectNode policy = mapper.createObjectNode();
        ArrayNode hosts = policy.putArray("allowedHosts");
        settings.allowedHostList().forEach(hosts::add);
        policy.put("pluginsAllowed", settings.isAllowPlugins());
        policy.put("maxPlugins", settings.getPluginMax());
        policy.put("maxPluginCodeBytes", settings.getPluginMaxCodeKb() * 1024);
        policy.put("maxMemoryMiB", settings.getMaxMemoryMib());
        return policy;
    }

    private ObjectNode limits() {
        ObjectNode limits = mapper.createObjectNode();
        limits.put("vmHeapMb", settings.getVmHeapMb());
        limits.put("vmHeapMaxMb", settings.getVmHeapMaxMb());
        limits.put("loadTimeoutMs", settings.getLoadTimeoutSeconds() * 1000);
        limits.put("operationTimeoutMs", settings.getOperationTimeoutSeconds() * 1000);
        ObjectNode plugins = limits.putObject("plugins");
        plugins.put("heapMb", settings.getPluginHeapMb());
        plugins.put("busySeconds", settings.getPluginBusySeconds());
        plugins.put("maxRunSeconds", settings.getPluginMaxRunSeconds());
        return limits;
    }

    private String appDataDirFor(String namespace) {
        if (settings.getAppDataDir().isBlank()) return null;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest((namespace == null ? "default" : namespace).getBytes(StandardCharsets.UTF_8));
            return Path.of(settings.getAppDataDir()).toAbsolutePath().resolve(HexFormat.of().formatHex(digest).substring(0, 16)).toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public RunnerSession get(String id) {
        RunnerSession session = sessions.get(id);
        if (session == null) throw ApiException.notFound("No session " + id);
        session.touch();
        return session;
    }

    public List<RunnerSession> list() { return List.copyOf(sessions.values()); }

    public void close(RunnerSession session, String reason) {
        sessions.remove(session.id());
        session.close(reason);
        LOG.info("session {} closed ({})", session.id(), reason);
    }

    /** Closes sessions idle for longer than RUNNER_IDLE_TIMEOUT_S (open streams count as use) and sessions older than RUNNER_SESSION_MAX_S. */
    @Scheduled(fixedDelay = "5s", initialDelay = "5s")
    void sweep() {
        Instant now = Instant.now();
        for (RunnerSession session : list()) {
            if (session.listenerCount() > 0) session.touch();
            if (Duration.between(session.lastUsedAt(), now).toSeconds() > settings.getIdleTimeoutSeconds()) close(session, "idle-timeout");
            else if (Duration.between(session.createdAt(), now).toSeconds() > settings.getSessionMaxSeconds()) close(session, "max-age");
        }
    }

    @PreDestroy
    void shutdown() {
        for (RunnerSession session : list()) close(session, "shutdown");
    }
}
