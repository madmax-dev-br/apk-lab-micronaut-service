package dev.apklab.queue.runners;

import com.fasterxml.jackson.databind.JsonNode;
import io.micronaut.core.annotation.Nullable;

/**
 * A failed runner call. `infrastructure`: the runner or its VM failed (unreachable, 5xx, crashed sidecar, VM not running) and the job may be retried on
 * another runner; `capacity`: the runner was full (retried without counting an attempt). Anything else (4xx of a step) is the job's own failure.
 */
public class RunnerError extends RuntimeException {
    private final int status;
    private final String code;
    private final boolean infrastructure;
    private final boolean capacity;
    private final transient JsonNode details;

    public RunnerError(String message, int status, String code, boolean infrastructure, boolean capacity, @Nullable JsonNode details) {
        super(message);
        this.status = status;
        this.code = code;
        this.infrastructure = infrastructure;
        this.capacity = capacity;
        this.details = details;
    }

    public static RunnerError fromResponse(String runnerId, int status, @Nullable JsonNode body, String text) {
        String code = body != null && body.path("error").hasNonNull("code") ? body.path("error").path("code").asText() : "http-" + status;
        String message = body != null && body.path("error").hasNonNull("message") ? body.path("error").path("message").asText() : text;
        boolean capacity = status == 503 && "at-capacity".equals(code);
        boolean infrastructure = !capacity && (status >= 500 || "vm-not-running".equals(code));
        return new RunnerError("runner " + runnerId + ": " + status + " " + message, status, code, infrastructure, capacity, body == null ? null : body.path("error").get("details"));
    }

    public int status() { return status; }
    public String code() { return code; }
    public boolean infrastructure() { return infrastructure; }
    public boolean capacity() { return capacity; }
    public @Nullable JsonNode details() { return details; }
}
