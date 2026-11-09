package dev.apklab.runner.sidecar;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.apklab.runner.RunnerSettings;
import jakarta.inject.Singleton;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds sidecar processes: {@code node --max-old-space-size=<RUNNER_SIDECAR_HEAP_MB> sidecar/vm-host.mjs} with a minimal environment
 * (PATH, HOME, the runtime directory). The VM Worker's own heap is bounded inside the sidecar (worker_threads resourceLimits, RUNNER_VM_HEAP_MB).
 */
@Singleton
public class SidecarLauncher {
    private final RunnerSettings settings;
    private final ObjectMapper mapper;

    public SidecarLauncher(RunnerSettings settings, ObjectMapper mapper) {
        this.settings = settings;
        this.mapper = mapper;
    }

    public Path script() {
        Path script = Path.of(settings.getSidecarScript());
        if (!script.isAbsolute()) {
            Path fromCwd = script.toAbsolutePath();
            if (Files.exists(fromCwd)) return fromCwd;
            String root = System.getProperty("apklab.repoRoot");
            if (root != null && Files.exists(Path.of(root).resolve(script))) return Path.of(root).resolve(script);
            return fromCwd;
        }
        return script;
    }

    public List<String> command() {
        List<String> command = new ArrayList<>();
        command.add(settings.getNode());
        command.add("--max-old-space-size=" + Math.max(128, settings.getSidecarHeapMb()));
        command.add(script().toString());
        return command;
    }

    public Map<String, String> environment() {
        Map<String, String> env = new HashMap<>();
        for (String name : List.of("PATH", "HOME", "TMPDIR", "TZ", "LANG", "SYSTEMROOT")) {
            String value = System.getenv(name);
            if (value != null) env.put(name, value);
        }
        if (!settings.getRuntimeDir().isBlank()) env.put("APK_LAB_RUNTIME_DIR", Path.of(settings.getRuntimeDir()).toAbsolutePath().toString());
        env.put("APK_LAB_SIDECAR_MAX_LINE_BYTES", String.valueOf(settings.maxLineBytes()));
        return env;
    }

    /** Starts a sidecar and waits until its runtime is loaded. */
    public SidecarProcess start(String label) {
        SidecarProcess process = new SidecarProcess(label, command(), environment(), mapper, settings.maxLineBytes());
        process.start(Duration.ofSeconds(settings.getStartTimeoutSeconds()));
        return process;
    }
}
