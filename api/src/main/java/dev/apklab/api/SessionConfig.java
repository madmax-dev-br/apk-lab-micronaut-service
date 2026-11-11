package dev.apklab.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
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

/**
 * The configuration of a runner session: every "Controles" feature of the lab as one JSON object. Unknown keys are rejected (a typo never silently does nothing).
 * The structure is checked here (Bean Validation); the values are then checked again by the lab's own sanitizers in the sidecar (device presets, host map rules,
 * endpoint mocks, method specs, permissions), so the rules stay the lab's.
 * Every field is optional; in a PATCH only the fields present change.
 */
@Introspected
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionConfig(
        @Nullable @Valid Device device,
        @Nullable @Valid Network network,
        @Nullable @Valid Permissions permissions,
        @Nullable @Valid @Size(max = 100) List<@Valid MethodOverride> methodOverrides,
        @Nullable @Valid @Size(max = 100) List<@Valid IgnoredActivity> ignoredActivities,
        @Nullable @Valid @Size(max = 50) List<@Valid EndpointMock> endpointMocks,
        /** 0 (defaults) or one of 60, 120, 300: how long one input event may run in the VM. */
        @Nullable @Min(0) @Max(300) Integer eventSeconds,
        /** "primitives" (default, like the site), "strict" (an unimplemented API fails with an error naming it), "void" or "all" (tolerant: stubs, always reported). */
        @Nullable @Pattern(regexp = "strict|off|void|primitives|all") String missingApis,
        @Nullable Boolean strictCrash,
        /** Java heap budget of the VM in MiB: 0 (automatic) or 512, 1024, 2048, 3072, 4096 (bounded by the runner's RUNNER_MAX_MEMORY_MIB). */
        @Nullable @Min(0) @Max(4096) Integer memoryMiB,
        /** The VM engine: "wasm" (default, like the site: the Java heap, frames and interpreter in the runtime's WebAssembly core) or "js" (the JavaScript VM); next load. */
        @Nullable @Pattern(regexp = "wasm|js") String engine,
        /** With engine "wasm": the sizes of the core's memory (defaults 512, 512, 1000; at most 2048 MiB in all; heapMiB bounded by RUNNER_MAX_MEMORY_MIB); next load. */
        @Nullable @Valid CoreMemory coreMemory,
        @Nullable @Valid AppData appData,
        /** "full" (default on the server: a screenshot never captures a reduced frame) or "progressive" (reduced while the screen moves). */
        @Nullable @Pattern(regexp = "progressive|full") String raster,
        /** "Resolução no movimento" with raster "progressive": 1, 0.75, 0.5 (default), 0.35 or 0.25; applied live. */
        @Nullable @DecimalMin("0.25") @DecimalMax("1") Double rasterScale,
        /** "Tela nova em baixa resolução" with raster "progressive": a new screen starts reduced (default true); applied live. */
        @Nullable Boolean rasterNavigation,
        /** "on-demand" (default: Logcat lines leave the VM only while someone reads them) or "always". */
        @Nullable @Pattern(regexp = "on-demand|always") String logcat,
        @Nullable @Valid @Size(max = 32) List<@Valid PluginSpec> plugins,
        /** true (launcher Activity), false, or an Activity class name: launch right after loading. */
        @Nullable JsonNode launch
) {
    public static SessionConfig empty() { return new SessionConfig(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null); }

    /** The "Resolução no movimento" steps of the lab (runtime/motion-raster.js MOTION_SCALE_STEPS). */
    public static final List<Double> RASTER_SCALE_STEPS = List.of(1.0, 0.75, 0.5, 0.35, 0.25);

    @Introspected
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Device(
            @Nullable @Size(max = 64) String screen,
            @Nullable @Pattern(regexp = "portrait|landscape") String orientation,
            @Nullable @Pattern(regexp = "online|offline|metered") String connectivity,
            @Nullable @Pattern(regexp = "gesture|buttons") String navigation,
            @Nullable @DecimalMin("0.85") @DecimalMax("2") Double fontScale,
            @Nullable @DecimalMin("0") @DecimalMax("10") Double animatorScale,
            @Nullable @Min(16) @Max(64) Integer statusBarHeightDp,
            @Nullable Boolean darkTheme,
            @Nullable Boolean hour24,
            @Nullable @Pattern(regexp = "[a-z]{2}-[A-Z]{2}") String locale,
            @Nullable @Size(max = 63) String timeZone,
            @Nullable @Valid Battery battery) {}

    @Introspected
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Battery(@Nullable @Min(0) @Max(100) Integer level, @Nullable Boolean charging) {}

    /** allowedHosts: "host", "*.suffix", "host:port", an IP literal or "*" (default ["*"], like the site; each must also be allowed by the runner's RUNNER_ALLOWED_HOSTS,
     *  and loopback / private / link-local targets need RUNNER_ALLOW_PRIVATE_NETWORK). */
    @Introspected
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Network(
            @Nullable @Size(max = 64) List<@NotBlank @Size(max = 253) String> allowedHosts,
            @Nullable @Valid @Size(max = 50) List<@Valid HostRule> hostMap,
            @Nullable Boolean offline,
            @Nullable @Size(max = 2000) String proxyUrl) {}

    /** A host redirect: requests to `from` ("https://api.example.test", "api.example.test", "*.example.test:8443") go to `to` ("127.0.0.1:9000", "http://staging.test"). */
    @Introspected
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record HostRule(@NotBlank @Size(max = 260) String from, @NotBlank @Size(max = 300) String to, @Nullable Boolean enabled) {}

    @Introspected
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Permissions(
            /** { "android.permission.CAMERA": "allow" | "deny" | "ask" } */
            @Nullable @Size(max = 200) Map<@Size(max = 200) String, @Pattern(regexp = "allow|deny|ask") String> states,
            @Nullable @Valid Location location,
            /** How runtime permission dialogs are answered: "dismiss" (default), "manual" (answer through the API), "allow", "once", "deny". */
            @Nullable @Pattern(regexp = "dismiss|manual|allow|once|deny") String dialogs) {}

    @Introspected
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Location(
            @Nullable Boolean enabled,
            @Nullable @DecimalMin("-90") @DecimalMax("90") Double latitude,
            @Nullable @DecimalMin("-180") @DecimalMax("180") Double longitude,
            @Nullable @DecimalMin("0") @DecimalMax("100000") Double accuracy) {}

    /** A method whose return value the VM replaces: "Lpkg/Class;->name(args)ret" or "pkg.Class.name(...)", and the value ("true", "42", "\"text\"", "null"). */
    @Introspected
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MethodOverride(@NotBlank @Size(max = 1000) String method, @Nullable @Size(max = 10000) String value, @Nullable Boolean enabled) {}

    /** An Activity the VM does not start (its start request is dropped and reported): "pkg.Activity" or { "name": ..., "enabled": ... }. */
    @Introspected
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record IgnoredActivity(@NotBlank @Size(max = 500) String name, @Nullable Boolean enabled) {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static IgnoredActivity of(String name) { return new IgnoredActivity(name, true); }

        @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
        public static IgnoredActivity create(@JsonProperty("name") String name, @JsonProperty("enabled") Boolean enabled) { return new IgnoredActivity(name, enabled); }
    }

    /**
     * The lab's endpoint mock: the first enabled mock whose method, URL pattern ("https://host/path", "/path" on any host, "*" wildcards), query mode and
     * bodyContains match answers the request with this status, headers and body (text or base64) after delayMs; the request never reaches the network.
     */
    @Introspected
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EndpointMock(
            @Nullable @Pattern(regexp = "[\\w-]{1,40}") String id,
            @Nullable Boolean enabled,
            @Nullable @Size(max = 120) String name,
            @Nullable @Pattern(regexp = "(?i)ANY|GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS") String method,
            @NotBlank @Size(max = 4000) String url,
            @Nullable @Pattern(regexp = "ignore|exact") String query,
            @Nullable @Size(max = 4000) String bodyContains,
            @Nullable @Min(200) @Max(599) Integer status,
            @Nullable @Size(max = 200) String reason,
            @Nullable @Valid @Size(max = 64) List<@Valid Header> headers,
            @Nullable String body,
            @Nullable String bodyBase64,
            @Nullable @Min(0) @Max(600000) Long delayMs) {}

    @Introspected
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Header(@NotBlank @Size(max = 200) String name, @Nullable @Size(max = 8000) String value) {}

    /** Java heap and metaspace of the WebAssembly core in MiB and how many Java threads may be alive (1 MiB each); the three share one memory of at most 2048 MiB. */
    @Introspected
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CoreMemory(@Nullable @Min(64) @Max(1536) Integer heapMiB, @Nullable @Min(64) @Max(1024) Integer metaMiB, @Nullable @Min(16) @Max(1000) Integer threads) {}

    @Introspected
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AppData(@Nullable Boolean keep, @Nullable Boolean clear) {}
}
