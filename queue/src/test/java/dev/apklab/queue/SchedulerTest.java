package dev.apklab.queue;

import dev.apklab.queue.model.QueueItem;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure scheduling rules. */
class SchedulerTest {
    private static long seq;

    static QueueItem item(String id, String client, int priority, long enqueuedAt, String state) {
        QueueItem item = new QueueItem();
        item.id = id; item.clientId = client; item.priority = priority; item.enqueuedAt = enqueuedAt; item.createdAt = enqueuedAt; item.state = state; item.seq = ++seq; item.kind = "job"; item.maxAttempts = 3;
        return item;
    }

    @Test
    void higherPriorityFirstThenFifo() {
        List<QueueItem> items = List.of(item("a", "alice", 5, 1, "queued"), item("b", "alice", 7, 2, "queued"), item("c", "bob", 7, 3, "queued"));
        assertEquals("b", Scheduler.pickNext(items, 2, Map.of(), 10).orElseThrow().id);
    }

    @Test
    void withinAPriorityTheClientWithFewerRunningItemsGoesFirst() {
        List<QueueItem> items = List.of(item("a1", "alice", 5, 1, "running"), item("a2", "alice", 5, 2, "queued"), item("b1", "bob", 5, 3, "queued"));
        assertEquals("b1", Scheduler.pickNext(items, 5, Map.of(), 10).orElseThrow().id, "bob has nothing running: fairness beats FIFO");
    }

    @Test
    void perClientLimitsHoldItemsBack() {
        List<QueueItem> items = List.of(item("a1", "alice", 5, 1, "running"), item("a2", "alice", 5, 2, "running"), item("a3", "alice", 9, 3, "queued"), item("b1", "bob", 1, 4, "queued"));
        assertEquals("b1", Scheduler.pickNext(items, 2, Map.of(), 10).orElseThrow().id, "alice is at QUEUE_MAX_PER_CLIENT even with a higher priority");
        assertEquals("a3", Scheduler.pickNext(items, 2, Map.of("alice", 3), 10).orElseThrow().id, "a client's own limit (QUEUE_CLIENTS id:token:3) wins");
        assertTrue(Scheduler.pickNext(List.of(items.get(0), items.get(1), items.get(2)), 2, Map.of(), 10).isEmpty());
    }

    @Test
    void notBeforeDelaysARetry() {
        QueueItem retry = item("r", "alice", 9, 1, "queued");
        retry.notBefore = 100L;
        QueueItem other = item("o", "bob", 1, 2, "queued");
        assertEquals("o", Scheduler.pickNext(List.of(retry, other), 2, Map.of(), 50).orElseThrow().id);
        assertEquals("r", Scheduler.pickNext(List.of(retry, other), 2, Map.of(), 150).orElseThrow().id);
    }

    @Test
    void theRunnerWithMostFreeSlotsIsPicked() {
        FakeRunner a = new FakeRunner("a", 1), b = new FakeRunner("b", 3), sick = new FakeRunner("sick", 10);
        sick.healthy = false; sick.refreshHealth();
        assertEquals("b", Scheduler.pickRunner(List.of(a, b, sick), false).orElseThrow().id());
        b.noteAssigned(); b.noteAssigned();
        assertEquals(1, Scheduler.freeSlots(b), "assignments since the last health report count");
        assertEquals("a", Scheduler.pickRunner(List.of(a, b), false).orElseThrow().id(), "tie: the least recently used");
        b.noteAssigned(); a.noteAssigned();
        assertTrue(Scheduler.pickRunner(List.of(a, b, sick), false).isEmpty(), "no free slot anywhere");
        FakeRunner withPlugins = new FakeRunner("p", 1);
        withPlugins.plugins = true;
        assertEquals("p", Scheduler.pickRunner(List.of(new FakeRunner("x", 5), withPlugins), true).orElseThrow().id(), "plugin jobs need a runner with plugins enabled");
    }

    @Test
    void positionsFollowTheDispatchOrder() {
        List<QueueItem> items = List.of(item("a1", "alice", 5, 1, "queued"), item("a2", "alice", 5, 2, "queued"), item("b1", "bob", 5, 3, "queued"), item("x", "carol", 5, 0, "succeeded"));
        Map<String, Integer> positions = Scheduler.queuePositions(items);
        assertEquals(1, positions.get("a1"));
        assertEquals(2, positions.get("b1"), "after a1 is taken alice has one running: bob goes next");
        assertEquals(3, positions.get("a2"));
        assertNull(positions.get("x"));
    }

    @Test
    void retriesOnlyForInfrastructureFailures() {
        QueueItem job = item("j", "alice", 5, 1, "running");
        job.attempts = 1;
        Scheduler.Decision infra = Scheduler.retryDecision(job, new Scheduler.Failure(true, false), 3, 1000);
        assertTrue(infra.retry() && infra.countAttempt());
        assertEquals(1000, infra.delayMs());
        job.attempts = 2;
        assertEquals(2000, Scheduler.retryDecision(job, new Scheduler.Failure(true, false), 3, 1000).delayMs(), "exponential backoff");
        job.attempts = 3;
        assertFalse(Scheduler.retryDecision(job, new Scheduler.Failure(true, false), 3, 1000).retry(), "out of attempts");
        Scheduler.Decision capacity = Scheduler.retryDecision(job, new Scheduler.Failure(false, true), 3, 1000);
        assertTrue(capacity.retry() && !capacity.countAttempt(), "a capacity race retries without counting an attempt");
        job.attempts = 1;
        assertFalse(Scheduler.retryDecision(job, new Scheduler.Failure(false, false), 3, 1000).retry(), "a step failure of the app is final");
        job.state = "cancelled";
        assertFalse(Scheduler.retryDecision(job, new Scheduler.Failure(true, false), 3, 1000).retry());
        QueueItem single = item("s", "alice", 5, 1, "running");
        single.attempts = 1; single.maxAttempts = 1;
        assertFalse(Scheduler.retryDecision(single, new Scheduler.Failure(true, false), 3, 1000).retry(), "the job's own maxAttempts");
    }

    @Test
    void leasesExpireByDurationOrIdleness() {
        QueueItem lease = item("l", "alice", 5, 0, "active");
        lease.startedAt = 0L; lease.lastUsedAt = 0L; lease.expiresAt = 10_000L;
        assertNull(Scheduler.leaseExpired(lease, 1000, 5000));
        assertEquals("idle", Scheduler.leaseExpired(lease, 6000, 5000));
        lease.lastUsedAt = 9000L;
        assertEquals("max-duration", Scheduler.leaseExpired(lease, 10_000, 5000));
        lease.state = "queued";
        assertNull(Scheduler.leaseExpired(lease, 99_999, 5000));
    }
}
