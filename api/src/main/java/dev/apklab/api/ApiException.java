package dev.apklab.api;

import io.micronaut.core.annotation.Nullable;

/** An error with an HTTP status and a stable code; both services turn it into an {@link ApiError} body. */
public class ApiException extends RuntimeException {
    private final int status;
    private final String code;
    private final transient Object details;
    private final Integer retryAfterSeconds;

    public ApiException(int status, String code, String message) { this(status, code, message, null, null); }

    public ApiException(int status, String code, String message, @Nullable Object details, @Nullable Integer retryAfterSeconds) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public static ApiException badRequest(String message) { return new ApiException(400, "bad-request", message); }
    public static ApiException notFound(String message) { return new ApiException(404, "not-found", message); }
    public static ApiException conflict(String code, String message) { return new ApiException(409, code, message); }
    public static ApiException forbidden(String code, String message) { return new ApiException(403, code, message); }

    public int status() { return status; }
    public String code() { return code; }
    public @Nullable Object details() { return details; }
    public @Nullable Integer retryAfterSeconds() { return retryAfterSeconds; }
}
