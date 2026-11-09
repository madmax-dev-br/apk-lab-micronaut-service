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
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/** Request bodies of the runner API (the same JSON as the Node service's runner). Coordinates are screen dp. */
public final class Requests {
    private Requests() {}

    @Introspected
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Launch(@Nullable @Size(max = 500) String activity) {}

    /** { selector } or { key } taps an element (Compose semantics OnClick, View click); { x, y } taps a point. longPress holds for 700 ms. */
    @Introspected
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Tap(@Nullable @Valid Selector selector, @Nullable @Size(max = 200) String key,
                      @Nullable @DecimalMin("-10000") @DecimalMax("10000") Double x, @Nullable @DecimalMin("-10000") @DecimalMax("10000") Double y,
                      @Nullable Boolean longPress) {}

    /** Types into an element: Compose SetText (InsertTextAtCursor with append) or an EditText. */
    @Introspected
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Type(@Nullable @Valid Selector selector, @Nullable @Size(max = 200) String key, @NotNull @Size(max = 100000) String text, @Nullable Boolean append) {}

    @Introspected
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Scroll(@Nullable @Valid Selector selector, @Nullable @Size(max = 200) String key, @Nullable Double x, @Nullable Double y,
                         @Nullable @DecimalMin("-100000") @DecimalMax("100000") Double dx, @Nullable @DecimalMin("-100000") @DecimalMax("100000") Double dy) {}

    /**
     * Waits until an element matching the selector is on the screen (or gone) and, with ready = true, the screen is "Tela pronta" (snapshot.screenReady.ready);
     * either condition alone works. Without a selector the default timeout is 15 s.
     */
    @Introspected
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Wait(@Nullable @Valid Selector selector, @Nullable @Size(max = 200) String key, @Nullable @Min(0) @Max(120000) Integer timeoutMs, @Nullable Boolean gone,
                       @Nullable Boolean ready) {}

    /** "Recriar" (recreate, the default: Activity.recreate(), saved state and retained ViewModels kept) or "Reabrir" (reopen: a fresh instance with the same Intent). */
    @Introspected
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Reload(@Nullable @Pattern(regexp = "recreate|reopen", message = "mode must be \"recreate\" or \"reopen\"") String mode) {}

    /** An Intent for startActivity: a deep link is { action: "android.intent.action.VIEW", data: "https://..." }. */
    @Introspected
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Intent(@Nullable @Size(max = 500) String action, @Nullable @Size(max = 8000) String data, @Nullable @Size(max = 500) String category,
                         @Nullable @Size(max = 32) List<@Size(max = 500) String> categories, @Nullable @Size(max = 200) Map<String, Object> extras,
                         @Nullable @Size(max = 500) String type, @Nullable @Size(max = 500) String component, @Nullable Integer flags) {}

    @Introspected
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Inspect(@NotBlank @Size(max = 200) String handle, @Nullable Map<String, Object> options) {}

    @Introspected
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Field(@NotBlank @Size(max = 200) String handle, @Nullable @Size(max = 2000) String path, @Nullable Map<String, Object> options) {}

    @Introspected
    public record PermissionAnswer(@NotNull @Pattern(regexp = "allow|once|deny|dismiss") String answer) {}

    @Introspected
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Plugins(@NotNull @Valid @Size(max = 32) List<@Valid PluginSpec> plugins) {}

    @Introspected
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Restart(@Nullable @Valid SessionConfig config) {}

    /** JSON form of session creation (the APK as base64); multipart and raw APK bodies are also accepted. */
    @Introspected
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CreateSessionJson(@NotBlank String apkBase64, @Nullable @Valid SessionConfig config) {}
}
