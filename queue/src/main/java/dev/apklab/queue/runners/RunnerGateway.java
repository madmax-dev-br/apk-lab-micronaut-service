package dev.apklab.queue.runners;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** One runner as the queue sees it: its last health report and its session API. The HTTP implementation is {@link HttpRunnerGateway}; tests use fakes. */
public interface RunnerGateway {
    String id();
    String url();

    /** Asks the runner's /health and updates healthy / available / pluginsAllowed; never throws. */
    void refreshHealth();
    boolean healthy();
    int available();
    int assignedSinceHealth();
    long lastAssignedAt();
    boolean pluginsAllowed();
    /** Counts one session the queue started on this runner since its last health report. */
    void noteAssigned();
    /** Marks the runner as full until its next health report (it answered at-capacity). */
    void markFull();
    Map<String, Object> describe();

    JsonNode createSession(byte[] apk, JsonNode config, Duration timeout) throws RunnerError;
    JsonNode call(String sessionId, String method, String path, JsonNode body, Duration timeout) throws RunnerError;
    Bytes bytes(String sessionId, String path, Duration timeout) throws RunnerError;
    void deleteSession(String sessionId);

    /** A raw request to the session API for the lease proxy: the body is streamed back (SSE included). */
    Raw forward(String method, String sessionId, String pathAndQuery, Map<String, String> headers, byte[] body) throws RunnerError;

    /** A screenshot: its bytes, content type, X-Screenshot-Notes and (after ?waitReady=1) X-Screen-Ready. */
    record Bytes(byte[] bytes, String type, String notes, String ready) {
        public Bytes(byte[] bytes, String type, String notes) { this(bytes, type, notes, null); }
    }
    record Raw(int status, Map<String, List<String>> headers, InputStream body) {}
}
