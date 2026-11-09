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
    public record Field(@NotBlank @Size(max = 200) String handle, @Nullable Object path, @Nullable Map<String, Object> options) {}

    /** Static fields of a class the app already initialized (a Kotlin object, a companion, a singleton); a query never runs its static initializer. */
    @Introspected
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Statics(@NotBlank @Size(max = 500) String className, @Nullable Map<String, Object> options) {}

    /** One value by path from a static field: path is a dotted string or an array of parts (a part with dots, such as a map key). */
    @Introspected
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StaticField(@NotBlank @Size(max = 500) String className, @Nullable Object path, @Nullable Map<String, Object> options) {}

    /** Live objects of a type (subclasses too unless subclasses is false), the heap scanned in the runtime's short pages. limit (50, max 500), maxPages (100, max 500) and budgetMs are clamped. */
    @Introspected
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Instances(@NotBlank @Size(max = 500) String className, @Nullable Boolean subclasses, @Nullable Integer limit, @Nullable Long after,
                            @Nullable Integer maxPages, @Nullable Integer budgetMs) {}

    /** Static fields and objects that hold an object (handle), with the field they hold it by (via). */
    @Introspected
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Referrers(@NotBlank @Size(max = 200) String handle, @Nullable Integer limit, @Nullable Long after, @Nullable Integer maxPages, @Nullable Integer budgetMs) {}

    /** A path of /field and /static-field: absent, a dotted string, or an array of at most 32 parts (strings or numbers). */
    public static boolean isPath(@Nullable Object path) {
        if (path == null) return true;
        if (path instanceof String text) return text.length() <= 2000;
        if (path instanceof List<?> parts) return parts.size() <= 32 && parts.stream().allMatch(part -> (part instanceof String text && text.length() <= 2000) || part instanceof Number);
        return false;
    }

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
