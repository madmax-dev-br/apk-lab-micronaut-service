package dev.apklab.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Nullable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/** Request bodies of the queue API (the same JSON as the Node service's queue). */
public final class QueueRequests {
    private QueueRequests() {}

    /** Step types of a job, in the order the queue runs them. */
    public static final List<String> STEP_TYPES = List.of("launch", "intent", "wait", "tap", "type", "scroll", "back", "sleep", "screenshot", "layoutBounds", "snapshot",
            "elements", "viewModels", "viewModelField", "inspect", "field", "composeTree", "config", "logcat", "network", "plugins", "diagnostic", "screenState");

    /**
     * A job: an APK (multipart part "apk", apkBase64 or apkUrl), the session config and declarative steps. The queue runs it on a runner session and keeps the
     * step outputs and artifacts (screenshots, JSON dumps) for the retention period.
     */
    @Introspected
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record JobRequest(
            @Nullable @Valid SessionConfig config,
            @Nullable @Valid @Size(max = 200) List<@Valid Step> steps,
            @Nullable @Min(0) @Max(9) Integer priority,
            @Nullable @Min(1000) Long timeoutMs,
            @Nullable @Valid Collect collect,
            @Nullable @Size(max = 200) String label,
            @Nullable @Min(1) @Max(10) Integer maxAttempts,
            @Nullable @Size(max = 4000) String apkUrl,
            @Nullable String apkBase64) {}

    /** What the job keeps at the end besides its steps: the full Logcat and network log as artifacts. */
    @Introspected
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Collect(@Nullable Boolean logcat, @Nullable Boolean network) {}

    /**
     * One declarative step. `type` picks the step; only the fields of that type may be set (others are rejected by the queue's validation).
     * Common fields: name (unique, names its artifacts), continueOnError, timeoutMs.
     */
    @Introspected
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Step(
            @NotBlank String type,
            @Nullable @Pattern(regexp = "[\\w.-]{1,64}") String name,
            @Nullable Boolean continueOnError,
            @Nullable @Min(1) @Max(120000) Integer timeoutMs,
            @Nullable @Size(max = 500) String activity,
            @Nullable @Valid Requests.Intent intent,
            @Nullable @Valid Selector selector,
            @Nullable Double x,
            @Nullable Double y,
            @Nullable Boolean longPress,
            @Nullable @Size(max = 100000) String text,
            @Nullable Boolean append,
            @Nullable Double dx,
            @Nullable Double dy,
            @Nullable Boolean gone,
            @Nullable @Min(0) @Max(60000) Integer ms,
            @Nullable @DecimalMin("0.1") @DecimalMax("4") Double scale,
            @Nullable @Size(max = 200) String key,
            @Nullable Boolean systemBars,
            @Nullable Boolean names,
            @Nullable @Size(max = 500) String viewModel,
            @Nullable @Size(max = 2000) String path,
            @Nullable Map<String, Object> options,
            @Nullable @Size(max = 200) String handle,
            @Nullable @Min(1) @Max(4000) Integer maxNodes,
            @Nullable @Valid SessionConfig config,
            @Nullable @Pattern(regexp = "[VDIWEA]") String level,
            @Nullable @Size(max = 200) String tag,
            @Nullable @Size(max = 2000) String contains,
            @Nullable @Min(1) @Max(5000) Integer limit,
            @Nullable @Valid @Size(max = 32) List<@Valid PluginSpec> plugins,
            @Nullable @Min(0) @Max(300) Integer seconds) {

        /** A step with only a type (and optionally a name), for code that builds jobs. */
        public static Step of(String type) { return new Builder(type).build(); }
        public static Builder builder(String type) { return new Builder(type); }

        /** Fluent construction of a step from Java (the record has many optional fields). */
        public static final class Builder {
            private final String type; private String name; private Boolean continueOnError; private Integer timeoutMs; private String activity; private Requests.Intent intent;
            private Selector selector; private Double x, y, dx, dy, scale; private Boolean longPress, append, gone, systemBars, names; private String text, key, viewModel, path, handle, level, tag, contains;
            private Integer ms, maxNodes, limit, seconds; private Map<String, Object> options; private SessionConfig config; private List<PluginSpec> plugins;
            Builder(String type) { this.type = type; }
            public Builder name(String v) { name = v; return this; }
            public Builder continueOnError(boolean v) { continueOnError = v; return this; }
            public Builder timeoutMs(int v) { timeoutMs = v; return this; }
            public Builder activity(String v) { activity = v; return this; }
            public Builder intent(Requests.Intent v) { intent = v; return this; }
            public Builder selector(Selector v) { selector = v; return this; }
            public Builder at(double px, double py) { x = px; y = py; return this; }
            public Builder text(String v) { text = v; return this; }
            public Builder append(boolean v) { append = v; return this; }
            public Builder scrollBy(double ddx, double ddy) { dx = ddx; dy = ddy; return this; }
            public Builder gone(boolean v) { gone = v; return this; }
            public Builder ms(int v) { ms = v; return this; }
            public Builder scale(double v) { scale = v; return this; }
            public Builder key(String v) { key = v; return this; }
            public Builder systemBars(boolean v) { systemBars = v; return this; }
            public Builder names(boolean v) { names = v; return this; }
            public Builder viewModel(String v) { viewModel = v; return this; }
            public Builder path(String v) { path = v; return this; }
            public Builder options(Map<String, Object> v) { options = v; return this; }
            public Builder handle(String v) { handle = v; return this; }
            public Builder maxNodes(int v) { maxNodes = v; return this; }
            public Builder config(SessionConfig v) { config = v; return this; }
            public Builder level(String v) { level = v; return this; }
            public Builder tag(String v) { tag = v; return this; }
            public Builder contains(String v) { contains = v; return this; }
            public Builder limit(int v) { limit = v; return this; }
            public Builder plugins(List<PluginSpec> v) { plugins = v; return this; }
            public Builder seconds(int v) { seconds = v; return this; }
            public Builder longPress(boolean v) { longPress = v; return this; }
            public Step build() {
                return new Step(type, name, continueOnError, timeoutMs, activity, intent, selector, x, y, longPress, text, append, dx, dy, gone, ms, scale, key, systemBars, names,
                        viewModel, path, options, handle, maxNodes, config, level, tag, contains, limit, plugins, seconds);
            }
        }
    }

    /** An interactive lease: the queue assigns a runner session and proxies its API (/leases/{id}/session/...) to the holder until it ends or expires. */
    @Introspected
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LeaseRequest(
            @Nullable @Valid SessionConfig config,
            @Nullable @Min(0) @Max(9) Integer priority,
            @Nullable @Min(30) Integer durationSeconds,
            @Nullable @Min(1) @Max(3600) Integer waitSeconds,
            @Nullable @Size(max = 200) String label,
            @Nullable @Size(max = 4000) String apkUrl,
            @Nullable String apkBase64) {}

    @Introspected
    public record Extend(@Nullable @Min(30) Integer seconds) {}
}
