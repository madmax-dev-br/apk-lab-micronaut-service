package dev.apklab.runner;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The sidecar's own unit tests (sidecar/test/*.test.mjs: deviceMemory and the memory budget, the default config, the private-network guard, raster settings),
 * run with node --test so ./gradlew test covers them. No VM and no network; with APK_LAB_RUNTIME_DIR they also compare with the runtime's modules.
 */
class SidecarScriptsTest {
    @Test
    void sidecarUnitTestsPass() throws IOException, InterruptedException {
        assumeTrue(TestHttp.nodeAvailable(), "Node.js is needed for the sidecar's tests");
        Path root = Path.of(System.getProperty("apklab.repoRoot", "..")).toAbsolutePath();
        ProcessBuilder builder = new ProcessBuilder(System.getenv().getOrDefault("APK_LAB_NODE", "node"), "--test", "sidecar/test/").directory(root.toFile()).redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(true, process.waitFor(120, TimeUnit.SECONDS), "node --test finished");
        String summary = output.lines().filter(line -> line.startsWith("ℹ tests") || line.startsWith("ℹ pass") || line.startsWith("ℹ fail") || line.startsWith("ℹ skipped")).reduce("", (a, b) -> a + " " + b);
        System.out.println("[sidecar tests]" + summary);
        assertEquals(0, process.exitValue(), output);
    }
}
