package dev.apklab.runner.sidecar;

import com.fasterxml.jackson.databind.JsonNode;
import io.micronaut.core.annotation.Nullable;

/** A failure reported by the sidecar for one request ({"ok": false, "error": {...}}), or a protocol / process failure (status 5xx, `crashed` true). */
public class SidecarException extends RuntimeException {
    private final int status;
    private final String code;
    private final transient JsonNode details;
    private final boolean crashed;

    public SidecarException(int status, String code, String message, @Nullable JsonNode details, boolean crashed) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
        this.crashed = crashed;
    }

    public static SidecarException crashed(String message) { return new SidecarException(503, "sidecar-crashed", message, null, true); }
    public static SidecarException timeout(String op, long millis) {
        return new SidecarException(504, "sidecar-timeout", "The sidecar did not answer " + op + " within " + millis + " ms; the session's VM was stopped", null, true);
    }

    public int status() { return status; }
    public String code() { return code; }
    public @Nullable JsonNode details() { return details; }
    /** true when the sidecar process is gone (or was stopped because it stopped answering): the session needs a restart. */
    public boolean crashed() { return crashed; }
}
