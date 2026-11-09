package dev.apklab.queue;

import dev.apklab.queue.model.QueueItem;
import dev.apklab.queue.runners.RunnerGateway;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The queue's scheduling rules, pure and synchronous (unit-tested without runners), the same rules as the Node service:
 * <ul>
 *   <li>an item is dispatchable while it is "queued", its notBefore passed and its client holds fewer runner sessions than its limit;</li>
 *   <li>among dispatchable items: higher priority first; within a priority, the client with fewer running items first (fairness), then the oldest (FIFO);</li>
 *   <li>an item goes to the healthy runner with the most free capacity (health minus what the queue assigned since; ties: least recently used).</li>
 * </ul>
 */
public final class Scheduler {
    public static final int PRIORITY_MIN = 0, PRIORITY_MAX = 9, DEFAULT_PRIORITY = 5;

    private Scheduler() {}

    public record Decision(boolean retry, boolean countAttempt, long delayMs) {
        public static final Decision NO = new Decision(false, false, 0);
    }

    /** Failure classification for retries. */
    public record Failure(boolean infrastructure, boolean capacity) {}

    public static Map<String, Integer> runningByClient(Collection<QueueItem> items) {
        Map<String, Integer> counts = new HashMap<>();
        for (QueueItem item : items) if (item.isActive()) counts.merge(item.clientId, 1, Integer::sum);
        return counts;
    }

    public static Optional<QueueItem> pickNext(Collection<QueueItem> items, int maxPerClient, Map<String, Integer> clientLimits, long now) {
        Map<String, Integer> running = runningByClient(items);
        QueueItem best = null;
        for (QueueItem item : items) {
            if (!"queued".equals(item.state)) continue;
            if (item.notBefore != null && item.notBefore > now) continue;
            int limit = clientLimits.getOrDefault(item.clientId, maxPerClient);
            if (running.getOrDefault(item.clientId, 0) >= limit) continue;
            if (best == null || compare(item, best, running) < 0) best = item;
        }
        return Optional.ofNullable(best);
    }

    static int compare(QueueItem a, QueueItem b, Map<String, Integer> running) {
        if (a.priority != b.priority) return Integer.compare(b.priority, a.priority);
        int ra = running.getOrDefault(a.clientId, 0), rb = running.getOrDefault(b.clientId, 0);
        if (ra != rb) return Integer.compare(ra, rb);
        if (a.enqueuedAt != b.enqueuedAt) return Long.compare(a.enqueuedAt, b.enqueuedAt);
        return Long.compare(a.seq, b.seq);
    }

    public static int freeSlots(RunnerGateway runner) { return Math.max(0, runner.available() - runner.assignedSinceHealth()); }

    public static Optional<RunnerGateway> pickRunner(Collection<? extends RunnerGateway> runners, boolean needsPlugins) {
        RunnerGateway best = null;
        for (RunnerGateway runner : runners) {
            if (!runner.healthy() || (needsPlugins && !runner.pluginsAllowed())) continue;
            int free = freeSlots(runner);
            if (free <= 0) continue;
            if (best == null || free > freeSlots(best) || (free == freeSlots(best) && runner.lastAssignedAt() < best.lastAssignedAt())) best = runner;
        }
        return Optional.ofNullable(best);
    }

    /** Position of each queued item in dispatch order (1-based), simulating the fairness rule as items are taken. */
    public static Map<String, Integer> queuePositions(Collection<QueueItem> items) {
        Map<String, Integer> simulated = new HashMap<>(runningByClient(items));
        Set<QueueItem> remaining = new LinkedHashSet<>();
        for (QueueItem item : items) if ("queued".equals(item.state)) remaining.add(item);
        Map<String, Integer> out = new LinkedHashMap<>();
        int position = 0;
        while (!remaining.isEmpty()) {
            QueueItem best = null;
            for (QueueItem item : remaining) if (best == null || compare(item, best, simulated) < 0) best = item;
            remaining.remove(best);
            out.put(best.id, ++position);
            simulated.merge(best.clientId, 1, Integer::sum);
        }
        return out;
    }

    /** Infrastructure failures (runner unreachable, runner 5xx, VM crash, capacity race) retry up to maxAttempts with backoff; app/step failures do not. */
    public static Decision retryDecision(QueueItem item, Failure failure, int maxAttempts, long baseDelayMs) {
        if ("cancelled".equals(item.state)) return Decision.NO;
        if (failure.capacity()) return new Decision(true, false, 2 * baseDelayMs);
        int limit = item.maxAttempts > 0 ? Math.min(item.maxAttempts, maxAttempts) : maxAttempts;
        if (failure.infrastructure() && item.attempts < limit) return new Decision(true, true, Math.min(30_000, baseDelayMs * (1L << Math.max(0, Math.min(10, item.attempts - 1)))));
        return Decision.NO;
    }

    /** Lease expiry: absolute (expiresAt) -> "max-duration", idle (no proxied call for idleMs) -> "idle", else null. */
    public static String leaseExpired(QueueItem lease, long now, long idleMs) {
        if (!"active".equals(lease.state)) return null;
        if (lease.expiresAt != null && now >= lease.expiresAt) return "max-duration";
        long last = lease.lastUsedAt != null ? lease.lastUsedAt : lease.startedAt != null ? lease.startedAt : now;
        if (idleMs > 0 && now - last >= idleMs) return "idle";
        return null;
    }

    public static List<QueueItem> sortedForListing(Collection<QueueItem> items) {
        List<QueueItem> list = new ArrayList<>(items);
        list.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
        return list;
    }
}
