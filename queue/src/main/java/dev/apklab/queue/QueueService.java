package dev.apklab.queue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.apklab.api.ApiException;
import dev.apklab.api.QueueRequests;
import dev.apklab.queue.model.QueueItem;
import dev.apklab.queue.runners.RunnerError;
import dev.apklab.queue.runners.RunnerGateway;
import dev.apklab.queue.store.QueueStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * The queue: accepts jobs (an APK, a session config and declarative steps) and interactive leases from authenticated clients, schedules them on the
 * registered runners ({@link Scheduler}: priorities, per-client fairness, runner capacity, retries) and keeps results for the retention period.
 * Plain Java (no Micronaut types) so the scheduling can be tested with fake runners; {@link QueueBeans} builds the application's instance.
 */
public class QueueService implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(QueueService.class);
    private static final int MAX_EVENTS = 500;

    private final QueueSettings settings;
    private final ObjectMapper mapper;
    private final QueueStore store;
    private final List<RunnerGateway> runners;
    private final Map<String, QueueItem> items = new ConcurrentHashMap<>();
    private final Map<String, Set<Consumer<ObjectNode>>> listeners = new ConcurrentHashMap<>();
    private final Map<String, Integer> clientLimits = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timers = Executors.newScheduledThreadPool(2, r -> Thread.ofPlatform().daemon().name("queue-timer").unstarted(r));
    private final Path apkDir;
    private final Path artifactDir;
    private long seq;
    private volatile boolean closed;

    public QueueService(QueueSettings settings, ObjectMapper mapper, QueueStore store, List<RunnerGateway> runners) {
        this.settings = settings;
        this.mapper = mapper;
        this.store = store;
        this.runners = List.copyOf(runners);
        this.apkDir = settings.dataPath().resolve("apks");
        this.artifactDir = settings.dataPath().resolve("artifacts");
        try {
            Files.createDirectories(apkDir);
            Files.createDirectories(artifactDir);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create the queue data directory " + settings.dataPath() + ": " + e.getMessage(), e);
        }
        settings.clientMap().values().forEach(client -> { if (client.maxConcurrent() != null) clientLimits.put(client.id(), client.maxConcurrent()); });
        restore();
    }

    /** Starts the health loop and the 1 s dispatch / sweep tick. */
    public QueueService start() {
        refreshHealth();
        long health = Math.max(100, settings.getHealthInterval().toMillis());
        timers.scheduleWithFixedDelay(this::refreshHealth, health, health, TimeUnit.MILLISECONDS);
        timers.scheduleWithFixedDelay(() -> { try { dispatch(); sweep(); } catch (RuntimeException e) { LOG.warn("tick: {}", e.toString()); } }, 250, 250, TimeUnit.MILLISECONDS);
        return this;
    }

    public void refreshHealth() {
        try (var scope = Executors.newVirtualThreadPerTaskExecutor()) {
            for (RunnerGateway runner : runners) scope.submit(runner::refreshHealth);
        }
        dispatch();
    }

    private void restore() {
        for (QueueItem item : store.load()) {
            item.seq = ++seq;
            if (item.isActive()) {
                if (item.runnerId != null && item.sessionId != null) runner(item.runnerId).ifPresent(r -> r.deleteSession(item.sessionId));
                if ("lease".equals(item.kind)) {
                    item.state = "expired"; item.finishedAt = System.currentTimeMillis();
                    item.error = new QueueItem.ErrorInfo("queue-restarted", "The queue restarted while the lease was active", null);
                } else if (item.attempts < item.maxAttempts) {
                    item.state = "queued"; item.runnerId = null; item.sessionId = null;
                } else {
                    item.state = "failed"; item.finishedAt = System.currentTimeMillis();
                    item.error = new QueueItem.ErrorInfo("queue-restarted", "The queue restarted while the job ran (no attempts left)", null);
                }
            }
            items.put(item.id, item);
        }
    }

    public List<RunnerGateway> runners() { return runners; }
    public Optional<RunnerGateway> runner(String id) { return runners.stream().filter(r -> r.id().equals(id)).findFirst(); }
    public int maxConcurrentOf(String clientId) { return clientLimits.getOrDefault(clientId, settings.getMaxPerClient()); }
    public void setClientLimit(String clientId, int limit) { clientLimits.put(clientId, limit); }

    // ---- submissions ---------------------------------------------------------------------------------------------------------------------------------------------
    public QueueItem submitJob(String clientId, byte[] apk, QueueRequests.JobRequest spec, JsonNode config) {
        List<QueueRequests.Step> steps = spec.steps() == null ? List.of(QueueRequests.Step.of("launch")) : spec.steps();
        Steps.validate(steps, settings.isAllowPlugins(), mapper);
        if (config.has("plugins") && config.get("plugins").size() > 0 && !settings.isAllowPlugins()) throw ApiException.forbidden("plugins-disabled", "Plugins are disabled on this queue (QUEUE_ALLOW_PLUGINS)");
        long jobTimeout = settings.getJobTimeout().toMillis();
        QueueItem item = newItem("job", clientId, apk, spec.priority(), spec.label(), config, spec.maxAttempts());
        item.steps = steps;
        item.timeoutMs = spec.timeoutMs() == null ? jobTimeout : Math.min(jobTimeout, Math.max(1000, spec.timeoutMs()));
        item.collect = new QueueItem.Collect(spec.collect() != null && Boolean.TRUE.equals(spec.collect().logcat()), spec.collect() != null && Boolean.TRUE.equals(spec.collect().network()));
        return enqueue(item);
    }

    public QueueItem submitLease(String clientId, byte[] apk, QueueRequests.LeaseRequest spec, JsonNode config) {
        if (config.has("plugins") && config.get("plugins").size() > 0 && !settings.isAllowPlugins()) throw ApiException.forbidden("plugins-disabled", "Plugins are disabled on this queue (QUEUE_ALLOW_PLUGINS)");
        QueueItem item = newItem("lease", clientId, apk, spec.priority(), spec.label(), config, 1);
        long max = settings.getLeaseMax().toMillis();
        item.durationMs = spec.durationSeconds() == null ? max : Math.min(max, spec.durationSeconds() * 1000L);
        item.waitUntil = spec.waitSeconds() == null ? null : System.currentTimeMillis() + spec.waitSeconds() * 1000L;
        return enqueue(item);
    }

    private QueueItem newItem(String kind, String clientId, byte[] apk, Integer priority, String label, JsonNode config, Integer maxAttempts) {
        synchronized (this) {
            long queued = items.values().stream().filter(i -> i.clientId.equals(clientId) && "queued".equals(i.state)).count();
            if (queued >= settings.getMaxQueuedPerClient()) throw new ApiException(429, "queue-full", "At most " + settings.getMaxQueuedPerClient() + " queued items per client", null, 10);
        }
        if (apk == null || apk.length == 0) throw ApiException.badRequest("No APK: send it as a file, apkBase64 or apkUrl");
        if (apk.length > settings.maxApkBytes()) throw new ApiException(413, "too-large", "APK larger than " + settings.getMaxApkMb() + " MiB (QUEUE_MAX_APK_MB)");
        if (!(apk[0] == 0x50 && apk[1] == 0x4b)) throw ApiException.badRequest("The APK is not a ZIP file");
        String sha256 = sha256(apk);
        try {
            Path file = apkDir.resolve(sha256 + ".apk");
            if (!Files.exists(file)) Files.write(file, apk);
        } catch (IOException e) {
            throw new ApiException(500, "io-error", "Could not store the APK: " + e.getMessage());
        }
        QueueItem item = new QueueItem();
        long now = System.currentTimeMillis();
        item.id = UUID.randomUUID().toString();
        item.kind = kind;
        item.clientId = clientId;
        item.state = "queued";
        item.priority = priority == null ? Scheduler.DEFAULT_PRIORITY : priority;
        item.createdAt = now;
        item.enqueuedAt = now;
        item.maxAttempts = Math.min(settings.getMaxAttempts(), Math.max(1, maxAttempts == null ? settings.getMaxAttempts() : maxAttempts));
        item.apk = new QueueItem.Apk(sha256, apk.length);
        item.config = config;
        item.label = label;
        return item;
    }

    private QueueItem enqueue(QueueItem item) {
        synchronized (this) {
            item.seq = ++seq;
            items.put(item.id, item);
            emit(item, "queued", null);
            save(item);
        }
        timers.execute(this::dispatch);
        return item;
    }

    // ---- dispatch ------------------------------------------------------------------------------------------------------------------------------------------------
    /** Assigns queued items to runners while both exist (see {@link Scheduler}). */
    public synchronized void dispatch() {
        if (closed) return;
        while (true) {
            Optional<QueueItem> next = Scheduler.pickNext(items.values(), settings.getMaxPerClient(), clientLimits, System.currentTimeMillis());
            if (next.isEmpty()) return;
            QueueItem item = next.get();
            boolean plugins = needsPlugins(item);
            Optional<RunnerGateway> picked = Scheduler.pickRunner(runners, plugins);
            if (picked.isEmpty()) return;
            RunnerGateway runner = picked.get();
            runner.noteAssigned();
            item.state = "assigned";
            item.runnerId = runner.id();
            item.assignedAt = System.currentTimeMillis();
            if ("job".equals(item.kind)) item.attempts++;
            ObjectNode data = mapper.createObjectNode().put("runner", runner.id()).put("attempt", item.attempts);
            emit(item, "assigned", data);
            save(item);
            Thread worker = Thread.ofVirtual().name(item.kind + "-" + item.id.substring(0, 8)).unstarted(() -> {
                if ("job".equals(item.kind)) runJob(item, runner); else startLease(item, runner);
            });
            item.worker = worker;
            worker.start();
        }
    }

    private boolean needsPlugins(QueueItem item) {
        if (item.config != null && item.config.path("plugins").size() > 0) return true;
        return item.steps != null && item.steps.stream().anyMatch(s -> "plugins".equals(s.type()));
    }

    private void runJob(QueueItem item, RunnerGateway runner) {
        Duration callTimeout = Duration.ofMinutes(3);
        var timeout = timers.schedule(() -> { synchronized (this) { if ("running".equals(item.state) || "assigned".equals(item.state)) { item.lastError = new QueueItem.ErrorInfo("job-timeout", "timeout", null); Thread w = item.worker; if (w != null) w.interrupt(); } } },
                item.timeoutMs == null ? settings.getJobTimeout().toMillis() : item.timeoutMs, TimeUnit.MILLISECONDS);
        try {
            byte[] apk = Files.readAllBytes(apkDir.resolve(item.apk.sha256() + ".apk"));
            JsonNode created = runner.createSession(apk, item.config, callTimeout);
            JsonNode session = created.path("session");
            synchronized (this) {
                item.sessionId = session.path("id").asText();
                if (item.cancelRequested || "cancelled".equals(item.state)) { runner.deleteSession(item.sessionId); return; }
                item.state = "running";
                if (item.startedAt == null) item.startedAt = System.currentTimeMillis();
                item.result = new QueueItem.Result();
                ObjectNode summary = mapper.createObjectNode().put("packageName", session.path("packageName").asText(null)).put("runner", runner.id())
                        .put("runtimeRevision", session.path("runtimeRevision").asText(null));
                summary.set("loadMs", session.path("metrics").path("loadMs"));
                summary.set("launchMs", session.path("metrics").path("launchMs"));
                item.result.session = summary;
                emit(item, "running", mapper.createObjectNode().set("session", summary));
                save(item);
            }
            QueueItem.StepRecord failed = null;
            for (int index = 0; index < item.steps.size(); index++) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                QueueRequests.Step step = item.steps.get(index);
                long started = System.currentTimeMillis();
                QueueItem.StepRecord record = new QueueItem.StepRecord();
                record.index = index; record.type = step.type(); record.name = step.name();
                emit(item, "step-started", mapper.createObjectNode().put("index", index).put("step", step.type()));
                try {
                    Steps.Outcome outcome = Steps.run(step, index, runner, item.sessionId, mapper);
                    for (Steps.Artifact artifact : outcome.artifacts()) item.result.artifacts.add(storeArtifact(item, artifact));
                    record.ok = true; record.ms = System.currentTimeMillis() - started; record.output = outcome.output();
                } catch (RunnerError e) {
                    if (e.infrastructure() || e.capacity() || "cancelled".equals(e.code())) throw e;
                    record.ok = false; record.ms = System.currentTimeMillis() - started; record.error = new QueueItem.ErrorInfo(e.code(), e.getMessage(), index);
                    if (!Boolean.TRUE.equals(step.continueOnError())) failed = record;
                } catch (ApiException e) {
                    record.ok = false; record.ms = System.currentTimeMillis() - started; record.error = new QueueItem.ErrorInfo(e.code(), e.getMessage(), index);
                    if (!Boolean.TRUE.equals(step.continueOnError())) failed = record;
                }
                synchronized (this) {
                    item.result.steps.add(record);
                    ObjectNode data = mapper.createObjectNode().put("index", index).put("ok", record.ok).put("ms", record.ms);
                    if (record.error != null) data.set("error", mapper.valueToTree(record.error));
                    emit(item, "step-finished", data);
                    save(item);
                }
                if (failed != null) break;
            }
            collect(item, runner, "logcat", "/logcat?limit=5000", item.collect != null && item.collect.logcat());
            collect(item, runner, "network", "/network?limit=2000", item.collect != null && item.collect.network());
            if (failed != null) {
                finish(item, "failed", new QueueItem.ErrorInfo(failed.error.code(), "step " + failed.index + " (" + failed.type + ") failed: " + failed.error.message(), failed.index));
            } else {
                finish(item, "succeeded", null);
            }
        } catch (InterruptedException e) {
            interrupted(item);
        } catch (RunnerError e) {
            if (Thread.interrupted() || "cancelled".equals(e.code())) { interrupted(item); return; }
            failOrRetry(item, e);
        } catch (IOException e) {
            finish(item, "failed", new QueueItem.ErrorInfo("io-error", e.getMessage(), null));
        } catch (RuntimeException e) {
            LOG.error("job {} failed", item.id, e);
            finish(item, "failed", new QueueItem.ErrorInfo("internal", String.valueOf(e.getMessage()), null));
        } finally {
            timeout.cancel(false);
        }
    }

    private void interrupted(QueueItem item) {
        synchronized (this) {
            if ("cancelled".equals(item.state)) return;
            if (item.lastError != null && "job-timeout".equals(item.lastError.code())) {
                long seconds = Math.round((item.timeoutMs == null ? settings.getJobTimeout().toMillis() : item.timeoutMs) / 1000.0);
                finish(item, "failed", new QueueItem.ErrorInfo("job-timeout", "The job exceeded " + seconds + " s", null));
            } else {
                finish(item, "cancelled", null);
            }
        }
    }

    private void collect(QueueItem item, RunnerGateway runner, String what, String path, boolean wanted) {
        if (!wanted || item.sessionId == null) return;
        try {
            JsonNode data = runner.call(item.sessionId, "GET", path, null, Duration.ofMinutes(1));
            item.result.artifacts.add(storeArtifact(item, new Steps.Artifact(what + ".json", "application/json", mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(data))));
        } catch (RuntimeException | IOException e) {
            if (item.result.collectErrors == null) item.result.collectErrors = new ArrayList<>();
            item.result.collectErrors.add(mapper.createObjectNode().put("what", what).put("message", e.getMessage()));
        }
    }

    private QueueItem.Artifact storeArtifact(QueueItem item, Steps.Artifact artifact) throws IOException {
        String safe = artifact.name().replaceAll("[^\\w.-]", "_");
        if (safe.length() > 120) safe = safe.substring(0, 120);
        Path dir = artifactDir.resolve(item.id);
        Files.createDirectories(dir);
        Files.write(dir.resolve(safe), artifact.bytes());
        return new QueueItem.Artifact(safe, artifact.type(), artifact.bytes().length, "/jobs/" + item.id + "/artifacts/" + safe);
    }

    public Path artifactFile(QueueItem item, String name) {
        Path file = artifactDir.resolve(item.id).resolve(name).normalize();
        if (!file.startsWith(artifactDir.resolve(item.id))) throw ApiException.badRequest("Invalid artifact name");
        return file;
    }

    private void startLease(QueueItem item, RunnerGateway runner) {
        try {
            byte[] apk = Files.readAllBytes(apkDir.resolve(item.apk.sha256() + ".apk"));
            JsonNode created = runner.createSession(apk, item.config, Duration.ofMinutes(3));
            synchronized (this) {
                String sessionId = created.path("session").path("id").asText();
                if ("cancelled".equals(item.state) || item.cancelRequested) { runner.deleteSession(sessionId); return; }
                long now = System.currentTimeMillis();
                item.sessionId = sessionId;
                item.state = "active";
                item.startedAt = now;
                item.lastUsedAt = now;
                item.expiresAt = now + Math.min(item.durationMs == null ? settings.getLeaseMax().toMillis() : item.durationMs, settings.getLeaseMax().toMillis());
                item.session = created.path("session");
                ObjectNode data = mapper.createObjectNode().put("expiresAt", Instant.ofEpochMilli(item.expiresAt).toString());
                data.set("session", created.path("session"));
                emit(item, "active", data);
                save(item);
            }
        } catch (RunnerError e) {
            failOrRetry(item, e);
        } catch (IOException e) {
            finish(item, "failed", new QueueItem.ErrorInfo("io-error", e.getMessage(), null));
        }
    }

    private synchronized void failOrRetry(QueueItem item, RunnerError error) {
        if ("cancelled".equals(item.state)) return;
        Scheduler.Decision decision = Scheduler.retryDecision(item, new Scheduler.Failure(error.infrastructure(), error.capacity()), settings.getMaxAttempts(), settings.getRetryBase().toMillis());
        String runnerId = item.runnerId, sessionId = item.sessionId;
        if (runnerId != null && sessionId != null) runner(runnerId).ifPresent(r -> r.deleteSession(sessionId));
        QueueItem.ErrorInfo info = new QueueItem.ErrorInfo(error.code(), error.getMessage(), null);
        if (decision.retry()) {
            if (!decision.countAttempt() && "job".equals(item.kind)) item.attempts = Math.max(0, item.attempts - 1);
            if (error.capacity()) runner(runnerId).ifPresent(RunnerGateway::markFull);
            item.state = "queued"; item.runnerId = null; item.sessionId = null; item.worker = null;
            item.notBefore = System.currentTimeMillis() + decision.delayMs();
            item.lastError = info;
            ObjectNode data = mapper.createObjectNode().put("attempt", item.attempts).put("inMs", decision.delayMs());
            data.set("error", mapper.valueToTree(info));
            emit(item, "retry", data);
            save(item);
            return;
        }
        finish(item, "failed", info);
    }

    private synchronized void finish(QueueItem item, String state, QueueItem.ErrorInfo error) {
        if (item.isFinal()) return;
        item.state = state;
        item.finishedAt = System.currentTimeMillis();
        if (error != null) item.error = error;
        String runnerId = item.runnerId, sessionId = item.sessionId;
        // the slot is free once the runner closed the session: ask that runner for its capacity right away and dispatch, instead of waiting for the periodic health check
        if (runnerId != null && sessionId != null) runner(runnerId).ifPresent(r -> Thread.ofVirtual().start(() -> {
            try { r.deleteSession(sessionId); } finally { r.refreshHealth(); timers.execute(this::dispatch); }
        }));
        item.sessionId = null;
        item.worker = null;
        emit(item, state, error == null ? null : mapper.createObjectNode().set("error", mapper.valueToTree(error)));
        save(item);
        timers.execute(this::dispatch);
    }

    // ---- client operations ---------------------------------------------------------------------------------------------------------------------------------------
    public QueueItem own(String id, String clientId, String kind) {
        QueueItem item = items.get(id);
        if (item == null || !item.clientId.equals(clientId) || (kind != null && !kind.equals(item.kind))) throw ApiException.notFound("No " + (kind == null ? "item" : kind) + " " + id);
        return item;
    }

    public List<QueueItem> list(String clientId, String kind, String state) {
        return Scheduler.sortedForListing(items.values().stream().filter(i -> i.clientId.equals(clientId) && kind.equals(i.kind) && (state == null || state.equals(i.state))).toList())
                .stream().limit(500).toList();
    }

    public synchronized ObjectNode cancel(QueueItem item) {
        if (item.isFinal()) return view(item);
        item.cancelRequested = true;
        Thread worker = item.worker;
        finish(item, "cancelled", null);
        if (worker != null) worker.interrupt();
        return view(item);
    }

    public synchronized Instant extend(QueueItem lease, int seconds) {
        if (!"active".equals(lease.state)) throw ApiException.conflict("not-active", "Lease is " + lease.state);
        long max = lease.startedAt + settings.getLeaseMax().toMillis();
        lease.expiresAt = Math.min(max, System.currentTimeMillis() + Math.max(30, seconds) * 1000L);
        save(lease);
        return Instant.ofEpochMilli(lease.expiresAt);
    }

    /** The runner and session of an active lease, for the proxy; counts as use (idle expiry). */
    public synchronized RunnerSessionRef leaseTarget(QueueItem lease) {
        if (!"active".equals(lease.state)) {
            Integer position = "queued".equals(lease.state) ? Scheduler.queuePositions(items.values()).get(lease.id) : null;
            throw ApiException.conflict("not-active", "Lease is " + lease.state + (position != null ? " (position " + position + ")" : ""));
        }
        lease.lastUsedAt = System.currentTimeMillis();
        RunnerGateway runner = runner(lease.runnerId).orElseThrow(() -> new ApiException(502, "runner-gone", "The lease's runner is no longer registered"));
        return new RunnerSessionRef(runner, lease.sessionId);
    }

    public record RunnerSessionRef(RunnerGateway runner, String sessionId) {}

    /** Ends expired leases, leases that waited too long, and removes finished items after the retention period. */
    public void sweep() {
        long now = System.currentTimeMillis();
        List<QueueItem> removed = new ArrayList<>();
        synchronized (this) {
            for (QueueItem item : items.values()) {
                if ("lease".equals(item.kind)) {
                    String reason = Scheduler.leaseExpired(item, now, settings.getLeaseIdle().toMillis());
                    if (reason != null) finish(item, "expired", new QueueItem.ErrorInfo("lease-" + reason, "Lease ended (" + reason + ")", null));
                    if ("queued".equals(item.state) && item.waitUntil != null && now > item.waitUntil) finish(item, "expired", new QueueItem.ErrorInfo("lease-wait-timeout", "No runner became free in time", null));
                }
                if (item.isFinal() && item.finishedAt != null && now - item.finishedAt > settings.getResultTtl().toMillis()) removed.add(item);
            }
            for (QueueItem item : removed) {
                items.remove(item.id);
                store.remove(item.id);
                listeners.remove(item.id);
            }
        }
        for (QueueItem item : removed) {
            deleteTree(artifactDir.resolve(item.id));
            if (items.values().stream().noneMatch(other -> other.apk != null && other.apk.sha256().equals(item.apk.sha256()))) {
                try { Files.deleteIfExists(apkDir.resolve(item.apk.sha256() + ".apk")); } catch (IOException ignored) { /* best effort */ }
            }
        }
    }

    // ---- events --------------------------------------------------------------------------------------------------------------------------------------------------
    private void emit(QueueItem item, String type, ObjectNode data) {
        ObjectNode event = mapper.createObjectNode().put("type", type).put("at", Instant.now().toString()).put("state", item.state);
        if (data != null) event.setAll(data);
        synchronized (item.events) {
            item.events.add(event);
            if (item.events.size() > MAX_EVENTS) item.events.subList(0, item.events.size() - MAX_EVENTS).clear();
        }
        for (Consumer<ObjectNode> listener : listeners.getOrDefault(item.id, Set.of())) {
            try { listener.accept(event); } catch (RuntimeException ignored) { /* closed stream */ }
        }
    }

    /** Past events of the item, then live ones until it reaches a final state. Returns the deregistration. */
    public Runnable listen(QueueItem item, Consumer<ObjectNode> listener) {
        listeners.computeIfAbsent(item.id, k -> new CopyOnWriteArraySet<>()).add(listener);
        return () -> { Set<Consumer<ObjectNode>> set = listeners.get(item.id); if (set != null) set.remove(listener); };
    }

    public List<ObjectNode> pastEvents(QueueItem item) {
        synchronized (item.events) { return List.copyOf(item.events); }
    }

    // ---- views ---------------------------------------------------------------------------------------------------------------------------------------------------
    public ObjectNode view(QueueItem item) {
        ObjectNode out = mapper.valueToTree(item);
        out.remove("config");
        if (item.config != null && !item.config.isEmpty()) out.set("config", item.config);
        Integer position = "queued".equals(item.state) ? Scheduler.queuePositions(items.values()).get(item.id) : null;
        if (position != null) out.put("position", position); else out.putNull("position");
        out.put("eventsCount", item.events.size());
        for (String key : List.of("createdAt", "enqueuedAt", "updatedAt", "assignedAt", "startedAt", "finishedAt", "notBefore", "waitUntil", "expiresAt", "lastUsedAt")) {
            JsonNode value = out.get(key);
            if (value != null && value.canConvertToLong() && value.asLong() > 0) out.put(key, Instant.ofEpochMilli(value.asLong()).toString());
        }
        return out;
    }

    public ObjectNode stats(String clientId) {
        List<QueueItem> mine = items.values().stream().filter(i -> i.clientId.equals(clientId)).toList();
        ObjectNode out = mapper.createObjectNode().put("client", clientId).put("maxConcurrent", maxConcurrentOf(clientId))
                .put("queued", mine.stream().filter(i -> "queued".equals(i.state)).count())
                .put("running", mine.stream().filter(QueueItem::isActive).count())
                .put("succeeded", mine.stream().filter(i -> "succeeded".equals(i.state)).count())
                .put("failed", mine.stream().filter(i -> "failed".equals(i.state)).count());
        ArrayNode list = out.putArray("runners");
        runners.forEach(r -> list.add(mapper.valueToTree(r.describe())));
        return out;
    }

    public long queuedCount() { return items.values().stream().filter(i -> "queued".equals(i.state)).count(); }
    public QueueItem item(String id) { return items.get(id); }
    public List<QueueItem> all() { return List.copyOf(items.values()); }

    private void save(QueueItem item) {
        item.updatedAt = System.currentTimeMillis();
        store.save(item);
    }

    @Override
    public void close() {
        closed = true;
        timers.shutdownNow();
        for (QueueItem item : items.values()) {
            if (item.isActive() && item.runnerId != null && item.sessionId != null) runner(item.runnerId).ifPresent(r -> r.deleteSession(item.sessionId));
        }
        store.flush();
    }

    static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static void deleteTree(Path dir) {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) { /* best effort */ } });
        } catch (IOException ignored) {
            // best effort
        }
    }
}
