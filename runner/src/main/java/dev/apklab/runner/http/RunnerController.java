package dev.apklab.runner.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.apklab.runner.RunnerApplication;
import dev.apklab.runner.RunnerSettings;
import dev.apklab.runner.session.RunnerSession;
import dev.apklab.runner.session.SessionManager;
import dev.apklab.runner.sidecar.SidecarLauncher;
import dev.apklab.runner.sidecar.SidecarProcess;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/** Service endpoints of the runner: health with capacity (used by the queue), stats, plugin templates. */
@Controller
@ExecuteOn(TaskExecutors.BLOCKING)
public class RunnerController {
    private final SessionManager sessions;
    private final RunnerSettings settings;
    private final SidecarLauncher launcher;
    private final ObjectMapper mapper;
    private volatile JsonNode templates;

    public RunnerController(SessionManager sessions, RunnerSettings settings, SidecarLauncher launcher, ObjectMapper mapper) {
        this.sessions = sessions;
        this.settings = settings;
        this.launcher = launcher;
        this.mapper = mapper;
    }

    @Get("/")
    public JsonNode root() {
        return mapper.createObjectNode().put("service", "apk-lab-runner").put("implementation", "micronaut").put("version", RunnerApplication.VERSION)
                .put("docs", "https://madmax-dev-br.github.io/apk-lab-micronaut-service/");
    }

    /** Public: { ok, service, version, runtimeRevision, pluginsAllowed, maxSessions, activeSessions, reserved, available, freeMemoryMb }. */
    @Get("/health")
    public JsonNode health() {
        SessionManager.Capacity capacity = sessions.capacity();
        ObjectNode out = mapper.createObjectNode();
        String revision = runtimeRevision();
        out.put("ok", revision != null);
        out.put("service", "apk-lab-runner");
        out.put("implementation", "micronaut");
        out.put("version", RunnerApplication.VERSION);
        out.put("runtimeRevision", revision);
        if (revision == null) out.put("problem", "APK Web Lab runtime not found: run ./gradlew fetchRuntime or set APK_LAB_RUNTIME_DIR");
        out.put("pluginsAllowed", settings.isAllowPlugins());
        out.put("maxSessions", capacity.maxSessions());
        out.put("activeSessions", capacity.activeSessions());
        out.put("reserved", capacity.reserved());
        out.put("available", revision == null ? 0 : capacity.available());
        out.put("freeMemoryMb", capacity.freeMemoryMb());
        return out;
    }

    @Get("/stats")
    public JsonNode stats() {
        SessionManager.Capacity capacity = sessions.capacity();
        ObjectNode out = mapper.valueToTree(capacity);
        ArrayNode list = out.putArray("sessions");
        for (RunnerSession session : sessions.list()) {
            ObjectNode item = session.describeLocal();
            item.put("sidecarPid", session.sidecarPid());
            try { item.set("sidecar", session.call("stats", mapper.createObjectNode())); } catch (RuntimeException ignored) { /* crashed or closed */ }
            list.add(item);
        }
        Runtime runtime = Runtime.getRuntime();
        out.putObject("jvm").put("heapUsedMb", (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)).put("heapMaxMb", runtime.maxMemory() / (1024 * 1024));
        return out;
    }

    /** The plugin templates of the bundle (asked once to a short-lived sidecar, then cached). */
    @Get("/plugin-templates")
    public JsonNode pluginTemplates() {
        JsonNode cached = templates;
        if (cached != null) return cached;
        try (SidecarProcess sidecar = launcher.start("templates")) {
            cached = sidecar.call("templates", mapper.createObjectNode(), Duration.ofSeconds(20));
        }
        templates = cached;
        return cached;
    }

    private String runtimeRevision() {
        Path dir = settings.getRuntimeDir().isBlank() ? launcher.script().getParent().getParent().resolve("vendor/apk-lab") : Path.of(settings.getRuntimeDir());
        try {
            return mapper.readTree(Files.readString(dir.resolve("build-info.json"))).path("revision").asText(null);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
