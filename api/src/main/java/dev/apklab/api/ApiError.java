package dev.apklab.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Nullable;

/** The error body of both services: { "error": { "code", "message", "details"? }, "requestId"? } (the same shape as the Node service). */
@Introspected
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(Body error, @Nullable String requestId) {

    @Introspected
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Body(String code, String message, @Nullable Object details) {}

    public static ApiError of(String code, String message, @Nullable Object details) { return new ApiError(new Body(code, message, details), null); }
}
