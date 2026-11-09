package dev.apklab.queue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.apklab.api.ApiException;
import dev.apklab.api.QueueRequests.Step;
import dev.apklab.api.Selector;
import dev.apklab.queue.runners.RunnerError;
import dev.apklab.queue.runners.RunnerGateway;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/** Declarative job steps: validation (before a job is accepted) and execution against a runner session (the same steps as the Node service). */
public final class Steps {
    public static final int MAX_STEPS = 200;
    private static final List<String> COMMON = List.of("type", "name", "continueOnError", "timeoutMs");
    static final Map<String, List<String>> FIELDS = new LinkedHashMap<>();
    static {
        FIELDS.put("launch", List.of("activity"));
        FIELDS.put("intent", List.of("intent"));
        FIELDS.put("wait", List.of("selector", "gone"));
        FIELDS.put("tap", List.of("selector", "x", "y", "longPress"));
        FIELDS.put("type", List.of("selector", "text", "append"));
        FIELDS.put("scroll", List.of("selector", "x", "y", "dx", "dy"));
        FIELDS.put("back", List.of());
        FIELDS.put("sleep", List.of("ms"));
        FIELDS.put("screenshot", List.of("scale", "selector", "key", "systemBars"));
        FIELDS.put("layoutBounds", List.of("names"));
        FIELDS.put("snapshot", List.of());
        FIELDS.put("elements", List.of("selector"));
        FIELDS.put("viewModels", List.of());
        FIELDS.put("viewModelField", List.of("viewModel", "path", "options"));
        FIELDS.put("inspect", List.of("handle", "options"));
        FIELDS.put("field", List.of("handle", "path", "options"));
        FIELDS.put("composeTree", List.of("maxNodes"));
        FIELDS.put("config", List.of("config"));
        FIELDS.put("logcat", List.of("level", "tag", "contains", "limit"));
        FIELDS.put("network", List.of("limit"));
        FIELDS.put("plugins", List.of("plugins", "seconds"));
        FIELDS.put("diagnostic", List.of());
        FIELDS.put("screenState", List.of());
    }

    private Steps() {}

    /** One artifact a step produced (a screenshot, a JSON dump). */
    public record Artifact(String name, String type, byte[] bytes) {}
    public record Outcome(JsonNode output, List<Artifact> artifacts) {
        static Outcome of(JsonNode output) { return new Outcome(output, List.of()); }
    }

    /** Checks the steps of a submission; fails with a 400 naming the step (or 403 for plugins when they are disabled). */
    public static void validate(List<Step> steps, boolean pluginsAllowed, ObjectMapper mapper) {
        if (steps == null) return;
        if (steps.size() > MAX_STEPS) throw ApiException.badRequest("at most " + MAX_STEPS + " steps");
        Set<String> names = new HashSet<>();
        for (int index = 0; index < steps.size(); index++) {
            Step step = steps.get(index);
            String where = "steps[" + index + "]";
            if (step == null || !FIELDS.containsKey(step.type())) throw ApiException.badRequest(where + ".type must be one of " + String.join(", ", FIELDS.keySet()));
            List<String> allowed = FIELDS.get(step.type());
            JsonNode json = mapper.valueToTree(step);
            List<String> unknown = new ArrayList<>();
            for (Iterator<String> it = json.fieldNames(); it.hasNext(); ) {
                String key = it.next();
                if (!COMMON.contains(key) && !allowed.contains(key)) unknown.add(key);
            }
            if (!unknown.isEmpty()) throw ApiException.badRequest(where + " (" + step.type() + "): keys not used by this step: " + String.join(", ", unknown));
            if (step.name() != null && !names.add(step.name())) throw ApiException.badRequest(where + ".name must be unique");
            boolean hasSelector = step.selector() != null && !step.selector().isEmpty();
            switch (step.type()) {
                case "wait" -> { if (!hasSelector) throw ApiException.badRequest(where + " (wait) needs a selector"); }
                case "type" -> {
                    if (!hasSelector) throw ApiException.badRequest(where + " (type) needs a selector");
                    if (step.text() == null) throw ApiException.badRequest(where + " (type) needs text");
                }
                case "tap" -> { if (!hasSelector && (step.x() == null || step.y() == null)) throw ApiException.badRequest(where + " (tap) needs a selector or x/y"); }
                case "sleep" -> { if (step.ms() == null) throw ApiException.badRequest(where + " (sleep) needs ms between 0 and 60000"); }
                case "viewModelField" -> { if (step.viewModel() == null || step.path() == null) throw ApiException.badRequest(where + " (viewModelField) needs viewModel (class name or suffix) and path"); }
                case "inspect", "field" -> { if (step.handle() == null) throw ApiException.badRequest(where + " (" + step.type() + ") needs a handle"); }
                case "plugins" -> {
                    if (!pluginsAllowed) throw ApiException.forbidden("plugins-disabled", where + ": plugins are disabled on this queue (QUEUE_ALLOW_PLUGINS)");
                    if (step.plugins() == null || step.plugins().isEmpty()) throw ApiException.badRequest(where + " (plugins) needs a plugins list");
                    if (step.seconds() == null) throw ApiException.badRequest(where + " (plugins) needs seconds between 0 and 300");
                }
                case "config" -> { if (step.config() == null) throw ApiException.badRequest(where + " (config) needs a config object"); }
                case "intent" -> { if (step.intent() == null) throw ApiException.badRequest(where + " (intent) needs an intent object"); }
                default -> { }
            }
        }
    }

    /** Runs one step on a runner session. Runner failures propagate as {@link RunnerError} (their infrastructure flag decides a retry). */
    public static Outcome run(Step step, int index, RunnerGateway runner, String sessionId, ObjectMapper mapper) throws InterruptedException {
        Duration timeout = Duration.ofMillis(Math.max(60_000, (step.timeoutMs() == null ? 0 : step.timeoutMs()) + 15_000L));
        String label = step.name() != null ? step.name() : "step-" + (index + 1);
        JsonNode stepJson = mapper.valueToTree(step);
        switch (step.type()) {
            case "launch": {
                JsonNode r = runner.call(sessionId, "POST", "/launch", pick(mapper, stepJson, "activity"), timeout);
                return Outcome.of(mapper.createObjectNode().put("activity", r.path("snapshot").path("activity").asText(null)).set("launchMs", r.get("launchMs")));
            }
            case "intent": {
                JsonNode r = runner.call(sessionId, "POST", "/intent", stepJson.get("intent"), timeout);
                ObjectNode out = mapper.createObjectNode();
                out.set("deepLink", r.get("deepLink"));
                out.put("activity", r.path("snapshot").path("activity").asText(null));
                return Outcome.of(out);
            }
            case "wait": {
                ObjectNode body = mapper.createObjectNode();
                body.set("selector", stepJson.get("selector"));
                body.put("timeoutMs", step.timeoutMs() == null ? 10_000 : step.timeoutMs());
                body.put("gone", Boolean.TRUE.equals(step.gone()));
                return Outcome.of(runner.call(sessionId, "POST", "/wait", body, timeout));
            }
            case "tap": {
                JsonNode r = runner.call(sessionId, "POST", "/tap", pick(mapper, stepJson, "selector", "x", "y", "longPress"), timeout);
                return Outcome.of(elementSummary(mapper, r));
            }
            case "type": {
                JsonNode r = runner.call(sessionId, "POST", "/type", pick(mapper, stepJson, "selector", "text", "append"), timeout);
                return Outcome.of(elementSummary(mapper, r));
            }
            case "scroll": {
                JsonNode r = runner.call(sessionId, "POST", "/scroll", pick(mapper, stepJson, "selector", "x", "y", "dx", "dy"), timeout);
                return Outcome.of(mapper.createObjectNode().put("via", r.path("via").asText(null)));
            }
            case "back": {
                JsonNode r = runner.call(sessionId, "POST", "/back", null, timeout);
                return Outcome.of(mapper.createObjectNode().put("activity", r.path("snapshot").path("activity").asText(null)).put("closed", r.path("snapshot").path("closed").asBoolean(false)));
            }
            case "sleep": {
                Thread.sleep(step.ms());
                return Outcome.of(mapper.createObjectNode().put("sleptMs", step.ms()));
            }
            case "screenshot": {
                double scale = step.scale() == null ? 1 : step.scale();
                String path = "/screenshot.png?scale=" + scale + "&systemBars=" + (Boolean.FALSE.equals(step.systemBars()) ? 0 : 1);
                String key = step.key();
                if (key == null && step.selector() != null) {
                    JsonNode found = runner.call(sessionId, "GET", "/elements?" + query(step.selector()), null, timeout).path("elements");
                    int at = step.selector().index() == null ? 0 : step.selector().index();
                    if (found.size() <= at) throw new ApiException(404, "element-not-found", "screenshot: no element matches " + stepJson.get("selector"));
                    key = found.get(at).path("key").asText();
                }
                if (key != null) path = "/elements/" + URLEncoder.encode(key, StandardCharsets.UTF_8) + "/screenshot.png?scale=" + scale;
                RunnerGateway.Bytes shot = runner.bytes(sessionId, path, timeout);
                String name = label + ".png";
                ObjectNode out = mapper.createObjectNode().put("artifact", name).put("bytes", shot.bytes().length);
                if (key != null) out.put("key", key);
                if (shot.notes() != null) { try { out.set("notes", mapper.readTree(shot.notes())); } catch (java.io.IOException ignored) { /* not JSON */ } }
                return new Outcome(out, List.of(new Artifact(name, "image/png", shot.bytes())));
            }
            case "layoutBounds": {
                JsonNode r = runner.call(sessionId, "GET", "/layout-bounds?names=" + (Boolean.FALSE.equals(step.names()) ? 0 : 1), null, timeout);
                return new Outcome(mapper.createObjectNode().put("activity", r.path("activity").asText(null)).put("count", r.path("elements").size()),
                        List.of(json(mapper, label + ".layout-bounds.json", r)));
            }
            case "snapshot": {
                JsonNode r = runner.call(sessionId, "GET", "/snapshot", null, timeout);
                return new Outcome(mapper.createObjectNode().put("activity", r.path("activity").asText(null)), List.of(json(mapper, label + ".snapshot.json", r)));
            }
            case "elements":
                return Outcome.of(runner.call(sessionId, "GET", "/elements" + (step.selector() == null ? "" : "?" + query(step.selector())), null, timeout));
            case "viewModels":
                return Outcome.of(runner.call(sessionId, "GET", "/view-models", null, timeout));
            case "viewModelField": {
                JsonNode models = runner.call(sessionId, "GET", "/view-models", null, timeout);
                String wanted = step.viewModel();
                JsonNode model = null;
                for (JsonNode m : models) if (wanted.equals(m.path("className").asText())) { model = m; break; }
                if (model == null) for (JsonNode m : models) { String c = m.path("className").asText(); if (c.endsWith("." + wanted) || c.endsWith("$" + wanted)) { model = m; break; } }
                if (model == null) for (JsonNode m : models) if (m.path("className").asText().contains(wanted)) { model = m; break; }
                if (model == null) {
                    StringJoiner found = new StringJoiner(", ");
                    models.forEach(m -> found.add(m.path("className").asText()));
                    throw new ApiException(404, "view-model-not-found", "No ViewModel matching " + wanted + " on the screen (found: " + (found.length() == 0 ? "none" : found) + ")");
                }
                ObjectNode body = mapper.createObjectNode().put("handle", model.path("handle").asText()).put("path", step.path());
                body.set("options", step.options() == null ? mapper.createObjectNode() : mapper.valueToTree(step.options()));
                ObjectNode out = mapper.createObjectNode().put("viewModel", model.path("className").asText()).put("handle", model.path("handle").asText()).put("path", step.path());
                out.set("value", runner.call(sessionId, "POST", "/field", body, timeout));
                return Outcome.of(out);
            }
            case "inspect": {
                ObjectNode body = mapper.createObjectNode().put("handle", step.handle());
                body.set("options", step.options() == null ? mapper.createObjectNode() : mapper.valueToTree(step.options()));
                return Outcome.of(runner.call(sessionId, "POST", "/inspect", body, timeout));
            }
            case "field": {
                ObjectNode body = mapper.createObjectNode().put("handle", step.handle()).put("path", step.path() == null ? "" : step.path());
                body.set("options", step.options() == null ? mapper.createObjectNode() : mapper.valueToTree(step.options()));
                return Outcome.of(runner.call(sessionId, "POST", "/field", body, timeout));
            }
            case "composeTree": {
                JsonNode r = runner.call(sessionId, "GET", "/compose-tree?maxNodes=" + (step.maxNodes() == null ? 2000 : step.maxNodes()), null, timeout);
                return new Outcome(mapper.createObjectNode().put("totalNodes", r.path("totalNodes").asInt(0)), List.of(json(mapper, label + ".compose-tree.json", r)));
            }
            case "config":
                return Outcome.of(runner.call(sessionId, "PATCH", "/config", stepJson.get("config"), timeout));
            case "logcat": {
                StringJoiner q = new StringJoiner("&");
                if (step.level() != null) q.add("level=" + step.level());
                if (step.tag() != null) q.add("tag=" + enc(step.tag()));
                if (step.contains() != null) q.add("contains=" + enc(step.contains()));
                q.add("limit=" + (step.limit() == null ? 5000 : step.limit()));
                JsonNode r = runner.call(sessionId, "GET", "/logcat?" + q, null, timeout);
                return new Outcome(mapper.createObjectNode().put("lines", r.path("lines").size()).set("total", r.get("total")), List.of(json(mapper, label + ".logcat.json", r)));
            }
            case "network": {
                JsonNode r = runner.call(sessionId, "GET", "/network?limit=" + (step.limit() == null ? 2000 : step.limit()), null, timeout);
                return new Outcome(mapper.createObjectNode().put("requests", r.path("requests").size()).set("total", r.get("total")), List.of(json(mapper, label + ".network.json", r)));
            }
            case "plugins": {
                ObjectNode body = mapper.createObjectNode();
                body.set("plugins", mapper.valueToTree(step.plugins()));
                runner.call(sessionId, "PUT", "/plugins", body, timeout);
                Thread.sleep(step.seconds() * 1000L);
                JsonNode out = runner.call(sessionId, "GET", "/plugins?images=inline", null, timeout);
                List<Artifact> artifacts = new ArrayList<>();
                for (JsonNode plugin : out) {
                    int blockIndex = 0;
                    for (JsonNode block : plugin.path("blocks")) {
                        if ("image".equals(block.path("kind").asText()) && block.hasNonNull("dataBase64") && block instanceof ObjectNode b) {
                            String name = label + "." + plugin.path("id").asText() + "." + blockIndex + ".png";
                            artifacts.add(new Artifact(name, block.path("type").asText("image/png"), java.util.Base64.getDecoder().decode(block.get("dataBase64").asText())));
                            b.put("artifact", name);
                            b.remove("dataBase64");
                        }
                        blockIndex++;
                    }
                }
                return new Outcome(out, artifacts);
            }
            case "diagnostic": {
                JsonNode r = runner.call(sessionId, "GET", "/diagnostic", null, timeout);
                ObjectNode out = mapper.createObjectNode();
                out.set("lastError", r.get("lastError"));
                out.put("runtimeErrors", r.path("runtimeErrors").size());
                return new Outcome(out, List.of(json(mapper, label + ".diagnostic.json", r)));
            }
            case "screenState":
                return Outcome.of(runner.call(sessionId, "GET", "/screen-state", null, timeout));
            default:
                throw ApiException.badRequest("unknown step " + step.type());
        }
    }

    private static ObjectNode elementSummary(ObjectMapper mapper, JsonNode r) {
        ObjectNode out = mapper.createObjectNode().put("via", r.path("via").asText(null));
        if (r.hasNonNull("element")) out.set("element", mapper.createObjectNode().put("key", r.path("element").path("key").asText()).put("name", r.path("element").path("name").asText()));
        if (r.has("snapshot")) out.put("activity", r.path("snapshot").path("activity").asText(null));
        return out;
    }

    private static ObjectNode pick(ObjectMapper mapper, JsonNode source, String... keys) {
        ObjectNode out = mapper.createObjectNode();
        for (String key : keys) if (source.has(key)) out.set(key, source.get(key));
        return out;
    }

    private static Artifact json(ObjectMapper mapper, String name, JsonNode value) {
        try { return new Artifact(name, "application/json", mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value)); }
        catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }

    static String query(Selector selector) {
        StringJoiner q = new StringJoiner("&");
        if (selector.key() != null) q.add("key=" + enc(selector.key()));
        if (selector.testTag() != null) q.add("testTag=" + enc(selector.testTag()));
        if (selector.text() != null) q.add("text=" + enc(selector.text()));
        if (selector.textContains() != null) q.add("textContains=" + enc(selector.textContains()));
        if (selector.contentDescription() != null) q.add("contentDescription=" + enc(selector.contentDescription()));
        if (selector.androidId() != null) q.add("androidId=" + enc(selector.androidId()));
        if (selector.viewId() != null) q.add("viewId=" + selector.viewId());
        if (selector.kind() != null) q.add("kind=" + selector.kind());
        return q.toString();
    }

    private static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    /** For listings: the step types and the fields each one takes. */
    public static ArrayNode describe(ObjectMapper mapper) {
        ArrayNode out = mapper.createArrayNode();
        FIELDS.forEach((type, fields) -> {
            ObjectNode item = out.addObject().put("type", type);
            ArrayNode list = item.putArray("fields");
            fields.forEach(list::add);
        });
        return out;
    }
}
