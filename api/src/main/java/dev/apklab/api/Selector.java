package dev.apklab.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Nullable;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Finds an element on the current screen (the boxes of "Mostrar limites de layout"): by layout-bounds key ("c:7544:12", "v:6163"), Compose testTag, exact text,
 * text fragment, content description, Android id ("save" or "pkg:id/save"), View id, kind ("compose" | "view"), clickability; index picks among several matches.
 */
@Introspected
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Selector(
        @Nullable @Size(max = 200) String key,
        @Nullable @Size(max = 2000) String testTag,
        @Nullable @Size(max = 2000) String text,
        @Nullable @Size(max = 2000) String textContains,
        @Nullable @Size(max = 2000) String contentDescription,
        @Nullable @Size(max = 2000) String androidId,
        @Nullable Integer viewId,
        @Nullable @Pattern(regexp = "compose|view") String kind,
        @Nullable Boolean clickable,
        @Nullable @Min(0) Integer index) {

    public static Selector byTestTag(String tag) { return new Selector(null, tag, null, null, null, null, null, null, null, null); }
    public static Selector byText(String text) { return new Selector(null, null, text, null, null, null, null, null, null, null); }
    public static Selector byKey(String key) { return new Selector(key, null, null, null, null, null, null, null, null, null); }
    public static Selector byTextContains(String text) { return new Selector(null, null, null, text, null, null, null, null, null, null); }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isEmpty() {
        return key == null && testTag == null && text == null && textContains == null && contentDescription == null && androidId == null && viewId == null && kind == null;
    }
}
