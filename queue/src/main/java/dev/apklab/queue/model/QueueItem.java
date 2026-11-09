package dev.apklab.queue.model;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.apklab.api.QueueRequests;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A job or a lease of the queue. Mutable, guarded by the queue's lock; its persisted form is this object as JSON (runtime-only fields are @JsonIgnore).
 * States: queued -> assigned -> running (job) | active (lease) -> succeeded | failed | cancelled | expired.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY, getterVisibility = JsonAutoDetect.Visibility.NONE, isGetterVisibility = JsonAutoDetect.Visibility.NONE)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class QueueItem {
    public static final Set<String> ACTIVE_STATES = Set.of("assigned", "running", "active");
    public static final Set<String> FINAL_STATES = Set.of("succeeded", "failed", "cancelled", "expired");

    public record Apk(String sha256, long bytes) {}
    public record Collect(boolean logcat, boolean network) {}
    public record Artifact(String name, String type, long bytes, String url) {}
    public record ErrorInfo(String code, String message, Integer step) {}

    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class StepRecord {
        public int index; public String type; public String name; public boolean ok; public long ms; public JsonNode output; public ErrorInfo error;
    }

    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Result {
        public List<StepRecord> steps = new ArrayList<>();
        public List<Artifact> artifacts = new ArrayList<>();
        public ObjectNode session;
        public List<ObjectNode> collectErrors;
    }

    public String id;
    public String kind;            // "job" | "lease"
    public String clientId;
    public String state;
    public int priority;
    public long createdAt;
    public long enqueuedAt;
    public long updatedAt;
    public int attempts;
    public int maxAttempts;
    public Apk apk;
    public JsonNode config;
    public String label;
    public List<QueueRequests.Step> steps;
    public Long timeoutMs;
    public Collect collect;
    public Result result;
    public String runnerId;
    public String sessionId;
    public Long assignedAt;
    public Long startedAt;
    public Long finishedAt;
    public Long notBefore;
    public ErrorInfo error;
    public ErrorInfo lastError;
    // leases
    public Long durationMs;
    public Long waitUntil;
    public Long expiresAt;
    public Long lastUsedAt;
    public JsonNode session;

    @JsonIgnore public long seq;
    @JsonIgnore public final List<ObjectNode> events = new ArrayList<>();
    @JsonIgnore public volatile Thread worker;
    @JsonIgnore public volatile boolean cancelRequested;

    public boolean isFinal() { return FINAL_STATES.contains(state); }
    public boolean isActive() { return ACTIVE_STATES.contains(state); }
}
