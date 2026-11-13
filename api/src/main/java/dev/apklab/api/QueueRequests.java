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
    public static final List<String> STEP_TYPES = List.of("launch", "intent", "wait", "waitReady", "reload", "tap", "type", "scroll", "back", "sleep", "screenshot", "layoutBounds", "snapshot",
            "elements", "viewModels", "viewModelField", "inspect", "field", "composeTree", "classes", "statics", "staticField", "instances", "referrers", "config", "logcat", "network", "plugins", "diagnostic", "screenState", "screenshotPieces");

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
     * Common fields: name (unique, names its artifacts), continueOnError, timeoutMs. "Tela pronta": wait takes ready (alone or with a selector), waitReady waits for it,
     * screenshot takes waitReady, and rect (a box of the window in dp: "l,t,r,b", an array or an object) with padding (0-64 dp); screenshotPieces takes pieces
     * ([{ selector } | { key } | { rect }], up to 16, each with an optional own padding), padding, scale and waitReady (one artifact per piece); reload takes mode ("recreate", the default, or "reopen"). State outside the screen: classes (match, limit, initialized),
     * statics (className), staticField (className, path), instances (className, subclasses, limit, after, maxPages) and referrers (handle, limit, after, maxPages).
     * path is a dotted string or an array of parts (a part with dots, such as a map key).
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
            @Nullable Object path,
            @Nullable Map<String, Object> options,
            @Nullable @Size(max = 200) String handle,
            @Nullable @Min(1) @Max(4000) Integer maxNodes,
            @Nullable @Valid SessionConfig config,
            @Nullable @Pattern(regexp = "[VDIWEA]") String level,
            @Nullable @Size(max = 200) String tag,
            @Nullable @Size(max = 2000) String contains,
            @Nullable @Min(1) @Max(5000) Integer limit,
            @Nullable @Valid @Size(max = 32) List<@Valid PluginSpec> plugins,
            @Nullable @Min(0) @Max(300) Integer seconds,
            @Nullable @Pattern(regexp = "recreate|reopen", message = "mode must be \"recreate\" or \"reopen\"") String mode,
            @Nullable Boolean ready,
            @Nullable Boolean waitReady,
            @Nullable @Size(max = 200) String match,
            @Nullable Boolean initialized,
            @Nullable @Size(max = 500) String className,
            @Nullable Boolean subclasses,
            @Nullable @Min(0) Long after,
            @Nullable @Min(1) @Max(500) Integer maxPages,
            @Nullable Object rect,
            @Nullable @DecimalMin("0") @DecimalMax("64") Double padding,
            @Nullable @Size(max = 16) List<Map<String, Object>> pieces) {

        /** A step with only a type (and optionally a name), for code that builds jobs. */
        public static Step of(String type) { return new Builder(type).build(); }
        public static Builder builder(String type) { return new Builder(type); }

        /** Fluent construction of a step from Java (the record has many optional fields). */
        public static final class Builder {
            private final String type; private String name; private Boolean continueOnError; private Integer timeoutMs; private String activity; private Requests.Intent intent;
            private Selector selector; private Double x, y, dx, dy, scale; private Boolean longPress, append, gone, systemBars, names; private String text, key, viewModel, handle, level, tag, contains;
            private Integer ms, maxNodes, limit, seconds; private Map<String, Object> options; private SessionConfig config; private List<PluginSpec> plugins;
            private String mode; private Boolean ready, waitReady; private Object path;
            private String match, className; private Boolean initialized, subclasses; private Long after; private Integer maxPages;
            private Object rect; private Double padding; private List<Map<String, Object>> pieces;
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
            /** A path as parts, for a part with dots (a map key). */
            public Builder path(List<String> v) { path = List.copyOf(v); return this; }
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
            /** reload: "recreate" (Activity.recreate()) or "reopen" (a fresh instance with the same Intent). */
            public Builder mode(String v) { mode = v; return this; }
            /** wait: also (or only) wait for "Tela pronta". */
            public Builder ready(boolean v) { ready = v; return this; }
            /** screenshot: wait for "Tela pronta" before painting. */
            public Builder waitReady(boolean v) { waitReady = v; return this; }
            /** classes: part of the class name or a /regex/. */
            public Builder match(String v) { match = v; return this; }
            /** classes: only classes the app already initialized. */
            public Builder initialized(boolean v) { initialized = v; return this; }
            /** statics, staticField, instances: the class. */
            public Builder className(String v) { className = v; return this; }
            /** instances: also objects of subclasses (the default). */
            public Builder subclasses(boolean v) { subclasses = v; return this; }
            /** instances, referrers: continue a scan from its cursor (next). */
            public Builder after(long v) { after = v; return this; }
            /** instances, referrers: at most this many of the runtime's pages (100, max 500). */
            public Builder maxPages(int v) { maxPages = v; return this; }
            /** screenshot: only this box of the window, in dp ("left,top,right,bottom" or a List of four numbers). */
            public Builder rect(Object v) { rect = v; return this; }
            /** screenshot, screenshotPieces: dp around the piece (0-64). */
            public Builder padding(double v) { padding = v; return this; }
            /** screenshotPieces: [{ "selector": {...} } | { "key": "..." } | { "rect": ... }], each with an optional own "padding". */
            public Builder pieces(List<Map<String, Object>> v) { pieces = v; return this; }
            public Step build() {
                return new Step(type, name, continueOnError, timeoutMs, activity, intent, selector, x, y, longPress, text, append, dx, dy, gone, ms, scale, key, systemBars, names,
                        viewModel, path, options, handle, maxNodes, config, level, tag, contains, limit, plugins, seconds, mode, ready, waitReady,
                        match, initialized, className, subclasses, after, maxPages, rect, padding, pieces);
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
