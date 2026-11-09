package dev.apklab.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Nullable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A server-side plugin: the body of an async JavaScript function with the lab's read-only `lab` API in scope (the same API as the site's plugins).
 * Runs in the session's sidecar, in its own worker_threads Worker with a memory limit and a CPU watchdog. Disabled unless the runner has RUNNER_ALLOW_PLUGINS=true.
 */
@Introspected
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PluginSpec(
        @Nullable @Pattern(regexp = "[\\w.-]{1,64}") String id,
        @Nullable @Size(max = 120) String name,
        @NotBlank String code,
        @Nullable Boolean enabled) {}
