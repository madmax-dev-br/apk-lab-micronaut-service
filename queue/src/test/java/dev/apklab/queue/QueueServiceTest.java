package dev.apklab.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.apklab.api.ApiException;
import dev.apklab.api.QueueRequests;
import dev.apklab.api.QueueRequests.Step;
import dev.apklab.api.Selector;
import dev.apklab.queue.model.QueueItem;
import dev.apklab.queue.runners.RunnerGateway;
import dev.apklab.queue.store.JsonFileStore;
import dev.apklab.queue.store.MemoryStore;
import dev.apklab.queue.store.QueueStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The queue service with in-memory runners: steps, capacity, fairness, retries, cancel, leases, retention, persistence. */
class QueueServiceTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final byte[] APK = {0x50, 0x4b, 3, 4, 1, 2, 3};
    @TempDir Path dir;
    final List<QueueService> services = new ArrayList<>();

    @AfterEach
    void close() { services.forEach(QueueService::close); }

    QueueSettings settings() {
        QueueSettings s = new QueueSettings();
        s.setDataDir(dir.toString());
        s.setRetryBase(Duration.ofMillis(20));
        s.setHealthInterval(Duration.ofMillis(100));
        s.setClients("alice:alice-token-1,bob:bob-token-22");
        return s;
    }

    QueueService service(QueueSettings settings, QueueStore store, RunnerGateway... runners) {
        QueueService service = new QueueService(settings, JSON, store, List.of(runners)).start();
        services.add(service);
        return service;
    }

    static QueueRequests.JobRequest job(List<Step> steps, Integer priority) {
        return new QueueRequests.JobRequest(null, steps, priority, null, null, null, null, null, null);
    }

    static void await(BooleanSupplier condition, String what) {
        long end = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > end) throw new AssertionError("timed out waiting for " + what);
            try { Thread.sleep(20); } catch (InterruptedException e) { throw new AssertionError(e); }
        }
    }

    @Test
    void aJobRunsItsStepsAndKeepsOutputsAndArtifacts() throws Exception {
        FakeRunner runner = new FakeRunner("r1", 2);
        QueueService queue = service(settings(), new MemoryStore(), runner);
        List<Step> steps = List.of(Step.of("launch"), Step.builder("tap").selector(Selector.byTestTag("save")).build(),
                Step.builder("screenshot").name("tela").build(), Step.builder("viewModelField").viewModel("CounterModel").path("count").build());
        QueueItem item = queue.submitJob("alice", APK, job(steps, null), JSON.createObjectNode());
        await(() -> "succeeded".equals(item.state), "the job");
        assertEquals(4, item.result.steps.size());
        assertTrue(item.result.steps.stream().allMatch(s -> s.ok));
        assertEquals("test.fake.MainActivity", item.result.steps.get(0).output.path("activity").asText());
        assertEquals("tela.png", item.result.artifacts.get(0).name());
        assertTrue(Files.size(queue.artifactFile(item, "tela.png")) > 0);
        assertEquals(3, item.result.steps.get(3).output.path("value").path("value").asInt());
        assertTrue(runner.open.isEmpty(), "the runner session is deleted at the end");
        List<String> types = queue.pastEvents(item).stream().map(e -> e.path("type").asText()).toList();
        assertEquals(List.of("queued", "assigned", "running", "step-started", "step-finished"), types.subList(0, 5));
        assertEquals("succeeded", types.get(types.size() - 1));
    }

    @Test
    void reloadWaitReadyAndScreenshotWaitReadyReachTheRunner() {
        FakeRunner runner = new FakeRunner("r1", 1);
        QueueService queue = service(settings(), new MemoryStore(), runner);
        List<Step> steps = List.of(Step.of("launch"), Step.builder("reload").mode("reopen").build(), Step.builder("waitReady").timeoutMs(20000).build(),
                Step.builder("wait").ready(true).selector(Selector.byText("Olá")).build(), Step.builder("screenshot").name("pronta").waitReady(true).build(), Step.of("reload"));
        QueueItem item = queue.submitJob("alice", APK, job(steps, null), JSON.createObjectNode());
        await(() -> "succeeded".equals(item.state) || "failed".equals(item.state), "the job");
        assertEquals("succeeded", item.state, () -> String.valueOf(item.error));
        assertEquals("reopen", item.result.steps.get(1).output.path("mode").asText());
        assertFalse(item.result.steps.get(1).output.path("ready").asBoolean(true));
        assertTrue(item.result.steps.get(2).output.path("ready").asBoolean());
        assertEquals("c:1:2", item.result.steps.get(3).output.path("element").path("key").asText());
        assertTrue(item.result.steps.get(4).output.path("ready").path("ready").asBoolean(), item.result.steps.get(4).output::toString);
        assertEquals("recreate", item.result.steps.get(5).output.path("mode").asText());
        assertTrue(runner.calls.contains("GET /screenshot.png?scale=1.0&systemBars=1&waitReady=1&readyTimeoutMs=15000"), runner.calls::toString);
        assertEquals(List.of("POST /reload {\"mode\":\"reopen\"}", "POST /wait {\"ready\":true,\"timeoutMs\":20000}",
                "POST /wait {\"selector\":{\"text\":\"Olá\"},\"timeoutMs\":10000,\"gone\":false,\"ready\":true}", "POST /reload {\"mode\":\"recreate\"}"),
                runner.bodies.stream().filter(b -> b.startsWith("POST /reload") || b.startsWith("POST /wait")).toList());
    }

    @Test
    void runnerCapacityIsRespected() {
        FakeRunner runner = new FakeRunner("r1", 1);
        runner.stepDelayMs = 150;
        QueueSettings settings = settings();
        settings.setMaxPerClient(5);
        QueueService queue = service(settings, new MemoryStore(), runner);
        List<QueueItem> jobs = new ArrayList<>();
        for (int i = 0; i < 3; i++) jobs.add(queue.submitJob("alice", APK, job(List.of(Step.of("launch")), null), JSON.createObjectNode()));
        await(() -> jobs.stream().allMatch(j -> "succeeded".equals(j.state)), "three jobs");
        assertEquals(1, runner.maxOpen.get(), "never more than the runner's one free slot");
    }

    @Test
    void priorityAndFairnessDecideTheOrder() {
        FakeRunner runner = new FakeRunner("r1", 2);
        runner.stepDelayMs = 300;
        QueueSettings settings = settings();
        settings.setMaxPerClient(5);
        QueueService queue = service(settings, new MemoryStore(), runner);
        runner.healthy = false; runner.refreshHealth();   // hold dispatch while submitting
        QueueItem a1 = queue.submitJob("alice", APK, job(List.of(Step.of("launch")), null), JSON.createObjectNode());
        QueueItem a2 = queue.submitJob("alice", APK, job(List.of(Step.of("launch")), null), JSON.createObjectNode());
        QueueItem a3 = queue.submitJob("alice", APK, job(List.of(Step.of("launch")), null), JSON.createObjectNode());
        QueueItem b1 = queue.submitJob("bob", APK, job(List.of(Step.of("launch")), null), JSON.createObjectNode());
        assertEquals(1, queue.view(a1).path("position").asInt());
        assertEquals(2, queue.view(b1).path("position").asInt(), "bob's first job comes before alice's second");
        assertEquals(3, queue.view(a2).path("position").asInt());
        QueueItem urgent = queue.submitJob("bob", APK, job(List.of(Step.of("launch")), 9), JSON.createObjectNode());
        assertEquals(1, queue.view(urgent).path("position").asInt(), "priority 9 first");
        queue.cancel(urgent);
        runner.healthy = true;
        await(() -> List.of(a1, a2, a3, b1).stream().allMatch(QueueItem::isFinal), "all jobs");
        assertTrue(a1.startedAt < a2.startedAt && b1.startedAt < a2.startedAt, "with alice running, bob's job takes the second slot before alice's next");
        assertTrue(a2.startedAt <= a3.startedAt);
    }

    @Test
    void infrastructureFailuresAreRetriedElsewhereAndAppFailuresAreNot() {
        FakeRunner flaky = new FakeRunner("flaky", 1), good = new FakeRunner("good", 1);
        flaky.createFailures.add(FakeRunner.infrastructure("sidecar exited"));
        good.healthy = false; good.refreshHealth();
        QueueService queue = service(settings(), new MemoryStore(), flaky, good);
        QueueItem job = queue.submitJob("alice", APK, job(List.of(Step.of("launch")), null), JSON.createObjectNode());
        await(() -> job.lastError != null, "the first failure");
        good.healthy = true;
        await(() -> "succeeded".equals(job.state), "the retry");
        assertEquals(2, job.attempts);
        assertTrue(queue.pastEvents(job).stream().anyMatch(e -> "retry".equals(e.path("type").asText())));

        FakeRunner strict = new FakeRunner("strict", 1);
        strict.pathFailures.put("/tap", FakeRunner.appError());
        QueueService second = service(settings(), new MemoryStore(), strict);
        QueueItem failing = second.submitJob("alice", APK, job(List.of(Step.of("launch"), Step.builder("tap").selector(Selector.byText("Salvar")).build(), Step.of("back")), null), JSON.createObjectNode());
        await(() -> "failed".equals(failing.state), "the failing job");
        assertEquals(1, failing.attempts, "a step failure is not retried");
        assertEquals("element-not-found", failing.error.code());
        assertEquals(2, failing.result.steps.size(), "the steps after the failure did not run");

        QueueItem tolerant = second.submitJob("alice", APK, job(List.of(Step.builder("tap").selector(Selector.byText("Salvar")).continueOnError(true).build(), Step.of("back")), null), JSON.createObjectNode());
        await(() -> tolerant.isFinal(), "the tolerant job");
        assertEquals("succeeded", tolerant.state, "continueOnError");
        assertFalse(tolerant.result.steps.get(0).ok);
    }

    @Test
    void retriesStopAtMaxAttempts() {
        FakeRunner broken = new FakeRunner("broken", 1);
        for (int i = 0; i < 5; i++) broken.createFailures.add(FakeRunner.infrastructure("crash " + i));
        QueueSettings settings = settings();
        settings.setMaxAttempts(3);
        QueueService queue = service(settings, new MemoryStore(), broken);
        QueueItem job = queue.submitJob("alice", APK, job(List.of(Step.of("launch")), null), JSON.createObjectNode());
        await(() -> "failed".equals(job.state), "the job to fail");
        assertEquals(3, job.attempts);
        assertEquals("sidecar-crashed", job.error.code());
    }

    @Test
    void cancelWorksQueuedAndRunning() {
        FakeRunner runner = new FakeRunner("r1", 1);
        runner.stepDelayMs = 5_000;
        QueueService queue = service(settings(), new MemoryStore(), runner);
        QueueItem running = queue.submitJob("alice", APK, job(List.of(Step.of("launch")), null), JSON.createObjectNode());
        QueueItem waiting = queue.submitJob("bob", APK, job(List.of(Step.of("launch")), null), JSON.createObjectNode());
        await(() -> "running".equals(running.state), "the first job to run");
        queue.cancel(waiting);
        assertEquals("cancelled", waiting.state);
        long started = System.currentTimeMillis();
        queue.cancel(running);
        await(() -> runner.open.isEmpty(), "the session to be deleted");
        assertEquals("cancelled", running.state);
        assertTrue(System.currentTimeMillis() - started < 3000, "the running step was interrupted");
    }

    @Test
    void jobTimeoutFailsTheJob() {
        FakeRunner runner = new FakeRunner("r1", 1);
        runner.stepDelayMs = 3_000;
        QueueService queue = service(settings(), new MemoryStore(), runner);
        QueueItem job = queue.submitJob("alice", APK, new QueueRequests.JobRequest(null, List.of(Step.of("launch")), null, 1000L, null, null, null, null, null), JSON.createObjectNode());
        await(() -> job.isFinal(), "the timeout");
        assertEquals("failed", job.state);
        assertEquals("job-timeout", job.error.code());
    }

    @Test
    void leasesBecomeActiveAndExpire() {
        FakeRunner runner = new FakeRunner("r1", 1);
        QueueSettings settings = settings();
        settings.setLeaseMax(Duration.ofMillis(600));
        settings.setLeaseIdle(Duration.ofSeconds(30));
        QueueService queue = service(settings, new MemoryStore(), runner);
        QueueItem lease = queue.submitLease("alice", APK, new QueueRequests.LeaseRequest(null, null, null, null, null, null, null), JSON.createObjectNode());
        await(() -> "active".equals(lease.state), "the lease");
        assertNotNull(queue.leaseTarget(lease).sessionId());
        QueueItem waiting = queue.submitLease("bob", APK, new QueueRequests.LeaseRequest(null, null, null, null, null, null, null), JSON.createObjectNode());
        ApiException notYet = assertThrows(ApiException.class, () -> queue.leaseTarget(waiting));
        assertTrue(notYet.getMessage().contains("position 1"), notYet.getMessage());
        await(() -> "expired".equals(lease.state), "max duration");
        assertEquals("lease-max-duration", lease.error.code());
        await(() -> "active".equals(waiting.state), "the second lease after the first freed the runner");
    }

    @Test
    void idleLeasesAndLeasesThatWaitTooLongExpire() {
        FakeRunner runner = new FakeRunner("r1", 1);
        QueueSettings settings = settings();
        settings.setLeaseIdle(Duration.ofMillis(1800));
        QueueService queue = service(settings, new MemoryStore(), runner);
        QueueItem lease = queue.submitLease("alice", APK, new QueueRequests.LeaseRequest(null, null, null, null, null, null, null), JSON.createObjectNode());
        QueueItem impatient = queue.submitLease("bob", APK, new QueueRequests.LeaseRequest(null, null, null, 1, null, null, null), JSON.createObjectNode());
        await(() -> "active".equals(lease.state), "the lease");
        await(() -> "expired".equals(impatient.state), "the impatient lease");
        assertEquals("lease-wait-timeout", impatient.error.code());
        await(() -> "expired".equals(lease.state), "idle expiry");
        assertEquals("lease-idle", lease.error.code());
    }

    @Test
    void perClientQueueLimitAndOwnership() {
        FakeRunner runner = new FakeRunner("r1", 0);
        QueueSettings settings = settings();
        settings.setMaxQueuedPerClient(2);
        QueueService queue = service(settings, new MemoryStore(), runner);
        QueueItem first = queue.submitJob("alice", APK, job(null, null), JSON.createObjectNode());
        queue.submitJob("alice", APK, job(null, null), JSON.createObjectNode());
        ApiException full = assertThrows(ApiException.class, () -> queue.submitJob("alice", APK, job(null, null), JSON.createObjectNode()));
        assertEquals(429, full.status());
        assertEquals(404, assertThrows(ApiException.class, () -> queue.own(first.id, "bob", "job")).status(), "bob cannot see alice's job");
        assertEquals(400, assertThrows(ApiException.class, () -> queue.submitJob("bob", new byte[] {1, 2}, job(null, null), JSON.createObjectNode())).status());
    }

    @Test
    void retentionRemovesFinishedItemsAndTheirFiles() {
        FakeRunner runner = new FakeRunner("r1", 1);
        QueueSettings settings = settings();
        settings.setResultTtl(Duration.ofMillis(300));
        QueueService queue = service(settings, new MemoryStore(), runner);
        QueueItem job = queue.submitJob("alice", APK, job(List.of(Step.builder("screenshot").name("shot").build()), null), JSON.createObjectNode());
        await(() -> "succeeded".equals(job.state), "the job");
        Path artifact = queue.artifactFile(job, "shot.png");
        assertTrue(Files.exists(artifact));
        await(() -> queue.item(job.id) == null, "retention");
        assertFalse(Files.exists(artifact));
        assertFalse(Files.exists(dir.resolve("apks").resolve(QueueService.sha256(APK) + ".apk")), "the APK of no remaining item is deleted");
    }

    @Test
    void theFileStoreRestoresItemsAfterARestart() throws Exception {
        FakeRunner runner = new FakeRunner("r1", 0);
        Path file = dir.resolve("queue.json");
        QueueService first = new QueueService(settings(), JSON, new JsonFileStore(file, JSON), List.of(runner)).start();
        QueueItem job = first.submitJob("alice", APK, job(List.of(Step.of("launch")), 3), JSON.createObjectNode());
        first.close();
        assertTrue(Files.readString(file).contains(job.id));
        runner.capacity = 1;
        QueueService second = service(settings(), new JsonFileStore(file, JSON), runner);
        QueueItem restored = second.item(job.id);
        assertNotNull(restored);
        assertEquals(3, restored.priority);
        await(() -> "succeeded".equals(restored.state), "the restored job to run");
    }
}
