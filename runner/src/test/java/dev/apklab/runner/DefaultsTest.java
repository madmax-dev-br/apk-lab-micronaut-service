package dev.apklab.runner;

import dev.apklab.runner.session.MemoryBudget;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The defaults equal to the site's on the Java side: the memory budget rule for /health, the operator's network policy and the app data folder. */
class DefaultsTest {
    static final long GIB = 1024L * 1024 * 1024;

    @Test
    void deviceMemoryIsTheRamInGibRoundedDownToAPowerOfTwoClampedToAQuarterAndEight() {
        Object[][] cases = {{GIB / 10, 0.25}, {GIB * 3 / 10, 0.25}, {GIB * 6 / 10, 0.5}, {GIB, 1.0}, {GIB * 38 / 10, 2.0}, {4 * GIB, 4.0}, {GIB * 77 / 10, 4.0},
                {8 * GIB, 8.0}, {GIB * 155 / 10, 8.0}, {16 * GIB, 8.0}, {64 * GIB, 8.0}, {512 * GIB, 8.0}};
        for (Object[] c : cases) assertEquals((Double) c[1], MemoryBudget.deviceMemoryFor((Long) c[0]), "totalmem " + c[0]);
        assertNull(MemoryBudget.deviceMemoryFor(0));
        assertNull(MemoryBudget.deviceMemoryFor(-1));
    }

    @Test
    void eightGibGivesTheSitesBudgetAndAThreeGigabyteWorkerHeap() {
        MemoryBudget.Report eight = MemoryBudget.budget(0, 8.0, 2048, 6144);
        assertEquals(1024, eight.budgetMiB());
        assertEquals(671088, eight.maxHeapObjects());
        assertEquals("navigator.deviceMemory=8", eight.source());
        assertEquals(3072, eight.workerHeapMb());
        assertEquals(335544, MemoryBudget.budget(0, 4.0, 2048, 6144).maxHeapObjects());
        assertEquals(262144, MemoryBudget.budget(0, null, 2048, 6144).maxHeapObjects(), "no deviceMemory: the lab's Node default");
        MemoryBudget.Report configured = MemoryBudget.budget(2048, 8.0, 2048, 6144);
        assertEquals("configured", configured.source());
        assertNull(configured.deviceMemory());
        assertEquals(5120, configured.workerHeapMb());
        assertEquals(6144, MemoryBudget.workerHeapMb(4096, 2048, 6144), "bounded by RUNNER_VM_HEAP_MAX_MB");
        assertTrue(MemoryBudget.automatic(2048, 6144).maxHeapObjects() >= 65536);
    }

    @Test
    void theOperatorPolicyDefaultsToEveryPublicHostWithPrivateTargetsRefused() {
        RunnerSettings settings = new RunnerSettings();
        assertEquals(List.of("*"), settings.allowedHostList());
        assertFalse(settings.allowPrivateNetworkEnabled());
        settings.setAllowedHosts("none");
        assertEquals(List.of(), settings.allowedHostList());
        settings.setAllowedHosts("API.example.test, *.cdn.example.test");
        assertEquals(List.of("api.example.test", "*.cdn.example.test"), settings.allowedHostList());
        for (String yes : List.of("1", "true", "TRUE", "yes", "on")) { settings.setAllowPrivateNetwork(yes); assertTrue(settings.allowPrivateNetworkEnabled(), yes); }
        for (String no : List.of("0", "false", "", "nope")) { settings.setAllowPrivateNetwork(no); assertFalse(settings.allowPrivateNetworkEnabled(), no); }
    }

    @Test
    void appDataIsKeptInDataAppDataNextToTheServiceUnlessOff() {
        RunnerSettings settings = new RunnerSettings();
        Path root = Path.of(System.getProperty("apklab.repoRoot", ".")).toAbsolutePath();
        assertEquals(root.resolve("data/app-data").normalize(), settings.appDataPath().toAbsolutePath().normalize());
        settings.setAppDataDir("off");
        assertNull(settings.appDataPath());
        settings.setAppDataDir("/srv/apk-lab/app-data");
        assertEquals(Path.of("/srv/apk-lab/app-data"), settings.appDataPath());
        settings.setAppDataDir("");
        assertEquals(root.resolve("data/app-data").normalize(), settings.appDataPath().toAbsolutePath().normalize());
    }
}
