package dev.apklab.runner.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.apklab.api.ApiException;
import dev.apklab.api.Requests;
import dev.apklab.api.SessionConfig;
import dev.apklab.runner.session.ConfigValidator;
import dev.apklab.runner.session.RunnerSession;
import dev.apklab.runner.session.SessionManager;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.Part;
import io.micronaut.http.annotation.Patch;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.http.multipart.CompletedFileUpload;
import io.micronaut.http.sse.Event;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.validation.Validated;
import jakarta.validation.Valid;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * The session API of the runner. Paths and JSON bodies are the Node service's (apk-lab-node-service): /sessions, /sessions/{id}/launch, /tap, /type, /wait,
 * /layout-bounds, /screenshot.png, /logcat/stream, ... Every operation is forwarded to the session's sidecar, which runs the lab's own code.
 */
@Controller("/sessions")
@Validated
@ExecuteOn(TaskExecutors.BLOCKING)
public class SessionsController {
    private static final List<String> INPUT_TYPES = List.of("click", "input", "check", "focus", "press", "scroll", "scrollby", "compose-action", "pointer");

    private final SessionManager sessions;
    private final ConfigValidator configs;
    private final ObjectMapper mapper;

    public SessionsController(SessionManager sessions, ConfigValidator configs, ObjectMapper mapper) {
        this.sessions = sessions;
        this.configs = configs;
        this.mapper = mapper;
    }

    // ---- creation ----------------------------------------------------------------------------------------------------------------------------------------------
    /** multipart/form-data: part "apk" (the file) and optional part "config" (JSON). */
    @Post(consumes = MediaType.MULTIPART_FORM_DATA)
    public HttpResponse<JsonNode> createMultipart(HttpRequest<?> request, @Part("apk") CompletedFileUpload apk, @Nullable @Part("config") String config) throws IOException {
        return created(request, apk.getBytes(), configs.parse(config));
    }

    /** application/json: { "apkBase64": "...", "config": {...} }. */
    @Post(consumes = MediaType.APPLICATION_JSON)
    public HttpResponse<JsonNode> createJson(HttpRequest<?> request, @Valid @Body Requests.CreateSessionJson body) {
        byte[] apk;
        try { apk = Base64.getMimeDecoder().decode(body.apkBase64()); } catch (IllegalArgumentException e) { throw ApiException.badRequest("apkBase64 is not base64"); }
        return created(request, apk, configs.check(body.config(), "config"));
    }

    /** The APK as the raw body (application/vnd.android.package-archive or application/octet-stream); config in the X-APK-Config header (JSON or base64url JSON). */
    @Post(consumes = {"application/vnd.android.package-archive", MediaType.APPLICATION_OCTET_STREAM})
    public HttpResponse<JsonNode> createRaw(HttpRequest<?> request, @Body byte[] apk, @Nullable @Header("X-APK-Config") String config, @Nullable @QueryValue("config") String configQuery) {
        return created(request, apk, configs.parse(config != null ? config : configQuery));
    }

    private HttpResponse<JsonNode> created(HttpRequest<?> request, byte[] apk, JsonNode config) {
        AuthFilter.Principal principal = AuthFilter.principal(request);
        return HttpResponse.status(HttpStatus.CREATED).body(sessions.create(apk, config, principal.id(), principal.namespace()));
    }

    // ---- lifecycle ---------------------------------------------------------------------------------------------------------------------------------------------
    @Get
    public List<JsonNode> list() {
        List<JsonNode> out = new ArrayList<>();
        for (RunnerSession session : sessions.list()) out.add(session.describe());
        return out;
    }

    @Get("/{id}")
    public JsonNode get(@PathVariable String id) { return sessions.get(id).describe(); }

    @Delete("/{id}")
    public JsonNode delete(@PathVariable String id) {
        sessions.close(sessions.get(id), "deleted");
        return mapper.createObjectNode().put("closed", true);
    }

    @Get("/{id}/inventory")
    public JsonNode inventory(@PathVariable String id) { return call(id, "inventory", null); }

    @Get("/{id}/config")
    public JsonNode config(@PathVariable String id) { return call(id, "getConfig", null); }

    /** Live config update through the same paths the site uses; keys that need a reload (missingApis, strictCrash, memoryMiB, appData, raster) are listed in restartRequired. */
    @Patch("/{id}/config")
    public JsonNode patchConfig(@PathVariable String id, @Valid @Body SessionConfig body) {
        RunnerSession session = sessions.get(id);
        ObjectNode args = mapper.createObjectNode();
        args.set("config", configs.check(body, "config"));
        JsonNode result = session.call("updateConfig", args);
        if (result.has("config")) session.setConfig(result.get("config"));
        return result;
    }

    @Post("/{id}/restart")
    public JsonNode restart(@PathVariable String id, @Nullable @Valid @Body Requests.Restart body) {
        JsonNode partial = body == null || body.config() == null ? null : configs.check(body.config(), "config");
        return sessions.get(id).restart(partial);
    }

    // ---- navigation and input ----------------------------------------------------------------------------------------------------------------------------------
    @Post("/{id}/launch")
    public JsonNode launch(@PathVariable String id, @Nullable @Valid @Body Requests.Launch body) { return call(id, "launch", body); }

    /** startActivity with an Intent; { action: "android.intent.action.VIEW", data: "<url>" } is a deep link. */
    @Post("/{id}/intent")
    public JsonNode intent(@PathVariable String id, @Valid @Body Requests.Intent body) { return call(id, "intent", body); }

    @Post("/{id}/back")
    public JsonNode back(@PathVariable String id) { return call(id, "back", null); }

    @Post("/{id}/tap")
    public JsonNode tap(@PathVariable String id, @Valid @Body Requests.Tap body) {
        if (body.selector() == null && body.key() == null && (body.x() == null || body.y() == null)) throw ApiException.badRequest("tap needs { x, y } (screen dp) or { selector } / { key }");
        return call(id, "tap", body);
    }

    @Post("/{id}/type")
    public JsonNode type(@PathVariable String id, @Valid @Body Requests.Type body) {
        if (body.selector() == null && body.key() == null) throw ApiException.badRequest("type needs a selector");
        return call(id, "type", body);
    }

    @Post("/{id}/scroll")
    public JsonNode scroll(@PathVariable String id, @Valid @Body Requests.Scroll body) { return call(id, "scroll", body); }

    /** A raw Worker input on ids taken from a snapshot: { type: click | input | check | focus | press | scroll | scrollby | compose-action | pointer, ... }. */
    @Post("/{id}/input")
    public JsonNode input(@PathVariable String id, @Body JsonNode body) {
        if (body == null || !body.isObject() || !INPUT_TYPES.contains(body.path("type").asText())) throw ApiException.badRequest("type: one of " + String.join(", ", INPUT_TYPES));
        return sessions.get(id).call("input", body);
    }

    @Post("/{id}/wait")
    public JsonNode waitFor(@PathVariable String id, @Valid @Body Requests.Wait body) {
        if (body.selector() == null && body.key() == null) throw ApiException.badRequest("wait needs a selector");
        int timeoutMs = body.timeoutMs() == null ? 10000 : body.timeoutMs();
        return sessions.get(id).call("wait", mapper.valueToTree(body), java.time.Duration.ofMillis(timeoutMs + 30_000L));
    }

    // ---- reading the screen ------------------------------------------------------------------------------------------------------------------------------------
    @Get("/{id}/snapshot")
    public JsonNode snapshot(@PathVariable String id, @Nullable @QueryValue String full, @Nullable @QueryValue Integer trace) {
        ObjectNode args = mapper.createObjectNode().put("full", truthy(full, false));
        if (trace != null) args.put("trace", trace);
        return sessions.get(id).call("snapshot", args);
    }

    /** The boxes of "Mostrar limites de layout": key, name, bounds (window dp), screenBounds, texts, testTag, actions, ... */
    @Get("/{id}/layout-bounds")
    public JsonNode layoutBounds(@PathVariable String id, @Nullable @QueryValue String names) {
        return sessions.get(id).call("layoutBounds", mapper.createObjectNode().put("names", truthy(names, true)));
    }

    @Get("/{id}/elements")
    public JsonNode elements(@PathVariable String id, @Nullable @QueryValue String key, @Nullable @QueryValue String testTag, @Nullable @QueryValue String text,
                             @Nullable @QueryValue String textContains, @Nullable @QueryValue String contentDescription, @Nullable @QueryValue String androidId,
                             @Nullable @QueryValue Integer viewId, @Nullable @QueryValue String kind) {
        ObjectNode selector = mapper.createObjectNode();
        if (key != null) selector.put("key", key);
        if (testTag != null) selector.put("testTag", testTag);
        if (text != null) selector.put("text", text);
        if (textContains != null) selector.put("textContains", textContains);
        if (contentDescription != null) selector.put("contentDescription", contentDescription);
        if (androidId != null) selector.put("androidId", androidId);
        if (viewId != null) selector.put("viewId", viewId);
        if (kind != null) selector.put("kind", kind);
        ObjectNode args = mapper.createObjectNode();
        if (!selector.isEmpty()) args.set("selector", selector);
        return sessions.get(id).call("elements", args);
    }

    @Get("/{id}/compose-tree")
    public JsonNode composeTree(@PathVariable String id, @Nullable @QueryValue Integer maxNodes) {
        return sessions.get(id).call("composeTree", mapper.createObjectNode().put("maxNodes", maxNodes == null ? 2000 : maxNodes));
    }

    @Get("/{id}/screen-state")
    public JsonNode screenState(@PathVariable String id) { return call(id, "screenState", null); }

    @Get("/{id}/diagnostic")
    public JsonNode diagnostic(@PathVariable String id) { return call(id, "diagnostic", null); }

    /** PNG of the current screen, composed with the bundle's Skia (CanvasKit): VM rasters as the page draws them, plain widget text approximated (x-screenshot-notes). */
    @Get(value = "/{id}/screenshot.png", produces = "image/png")
    public MutableHttpResponse<byte[]> screenshot(@PathVariable String id, @Nullable @QueryValue Double scale, @Nullable @QueryValue String systemBars) {
        ObjectNode args = mapper.createObjectNode().put("scale", scale == null ? 1 : scale).put("systemBars", truthy(systemBars, true));
        return png(sessions.get(id).call("screenshot", args));
    }

    /** PNG of one element, by its layout-bounds key (GET /sessions/{id}/layout-bounds lists the keys). */
    @Get(value = "/{id}/elements/{key}/screenshot.png", produces = "image/png")
    public MutableHttpResponse<byte[]> elementScreenshot(@PathVariable String id, @PathVariable String key, @Nullable @QueryValue Double scale) {
        ObjectNode args = mapper.createObjectNode().put("key", key).put("scale", scale == null ? 1 : scale);
        return png(sessions.get(id).call("elementScreenshot", args));
    }

    private MutableHttpResponse<byte[]> png(JsonNode shot) {
        byte[] bytes = Base64.getDecoder().decode(shot.path("pngBase64").asText());
        MutableHttpResponse<byte[]> response = HttpResponse.ok(bytes).contentType("image/png")
                .header("X-Image-Size", shot.path("width").asInt() + "x" + shot.path("height").asInt())
                .header("Cache-Control", "no-store");
        if (shot.has("notes")) response.header("X-Screenshot-Notes", shot.get("notes").toString());
        return response;
    }

    // ---- VM inspection -----------------------------------------------------------------------------------------------------------------------------------------
    @Get("/{id}/activity")
    public JsonNode activity(@PathVariable String id) { return call(id, "activity", null); }

    @Get("/{id}/view-models")
    public JsonNode viewModels(@PathVariable String id) { return call(id, "viewModels", null); }

    @Get("/{id}/fragments")
    public JsonNode fragments(@PathVariable String id) { return call(id, "fragments", null); }

    @Post("/{id}/inspect")
    public JsonNode inspect(@PathVariable String id, @Valid @Body Requests.Inspect body) { return call(id, "inspect", body); }

    @Post("/{id}/field")
    public JsonNode field(@PathVariable String id, @Valid @Body Requests.Field body) { return call(id, "field", body); }

    @Get("/{id}/methods")
    public JsonNode methods(@PathVariable String id, @Nullable @QueryValue String query) {
        return sessions.get(id).call("methods", mapper.createObjectNode().put("query", query == null ? "" : query));
    }

    // ---- logs --------------------------------------------------------------------------------------------------------------------------------------------------
    @Get("/{id}/logcat")
    public JsonNode logcat(@PathVariable String id, @Nullable @QueryValue Long since, @Nullable @QueryValue Integer limit, @Nullable @QueryValue String level,
                           @Nullable @QueryValue String tag, @Nullable @QueryValue String contains) {
        ObjectNode args = mapper.createObjectNode();
        if (since != null) args.put("since", since);
        if (limit != null) args.put("limit", limit);
        if (level != null) args.put("level", level);
        if (tag != null) args.put("tag", tag);
        if (contains != null) args.put("contains", contains);
        return sessions.get(id).call("logcat", args);
    }

    @Get(value = "/{id}/logcat/stream", produces = MediaType.TEXT_EVENT_STREAM)
    public Flux<Event<JsonNode>> logcatStream(@PathVariable String id, @Nullable @QueryValue Long since, @Nullable @Header("Last-Event-ID") String lastEventId) {
        RunnerSession session = sessions.get(id);
        List<Event<JsonNode>> initial = new ArrayList<>();
        Long from = since != null ? since : parseLong(lastEventId);
        if (from != null) initial.addAll(Streams.logLines(mapper.createObjectNode().set("data", session.call("logcat", mapper.createObjectNode().put("since", from).put("limit", 5000)).path("lines"))));
        return Streams.of(session, RunnerSession.Channel.LOGCAT, mapper, initial, Streams::logLines);
    }

    @Get("/{id}/network")
    public JsonNode network(@PathVariable String id, @Nullable @QueryValue Long since, @Nullable @QueryValue Integer limit) {
        ObjectNode args = mapper.createObjectNode();
        if (since != null) args.put("since", since);
        if (limit != null) args.put("limit", limit);
        return sessions.get(id).call("network", args);
    }

    @Get(value = "/{id}/network/stream", produces = MediaType.TEXT_EVENT_STREAM)
    public Flux<Event<JsonNode>> networkStream(@PathVariable String id, @Nullable @QueryValue Long since, @Nullable @Header("Last-Event-ID") String lastEventId) {
        RunnerSession session = sessions.get(id);
        List<Event<JsonNode>> initial = new ArrayList<>();
        Long from = since != null ? since : parseLong(lastEventId);
        if (from != null) initial.addAll(Streams.netRows(mapper.createObjectNode().set("data", session.call("network", mapper.createObjectNode().put("since", from).put("limit", 2000)).path("requests"))));
        return Streams.of(session, RunnerSession.Channel.NETLOG, mapper, initial, Streams::netRows);
    }

    /** Session events: snapshot, loaded, runtime-error, permission-request, permission-config, app-data, plugin, crashed, closed. The first event describes the session. */
    @Get(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM)
    public Flux<Event<JsonNode>> events(@PathVariable String id) {
        RunnerSession session = sessions.get(id);
        return Streams.of(session, RunnerSession.Channel.EVENTS, mapper, List.of(Event.of(session.describe()).name("session")), Streams::sessionEvents);
    }

    // ---- permissions, app data ---------------------------------------------------------------------------------------------------------------------------------
    @Get("/{id}/permission-requests")
    public JsonNode permissionRequests(@PathVariable String id) { return call(id, "permissionRequests", null); }

    @Post("/{id}/permission-requests/{requestId}")
    public JsonNode answerPermission(@PathVariable String id, @PathVariable String requestId, @Valid @Body Requests.PermissionAnswer body) {
        return sessions.get(id).call("answerPermission", mapper.createObjectNode().put("requestId", requestId).put("answer", body.answer()));
    }

    @Post("/{id}/app-data/clear")
    public JsonNode clearAppData(@PathVariable String id) { return call(id, "clearAppData", null); }

    // ---- plugins -----------------------------------------------------------------------------------------------------------------------------------------------
    @Get("/{id}/plugins")
    public JsonNode plugins(@PathVariable String id, @Nullable @QueryValue String images) {
        return sessions.get(id).call("listPlugins", mapper.createObjectNode().put("inlineImages", "inline".equals(images)));
    }

    @Put("/{id}/plugins")
    public JsonNode setPlugins(@PathVariable String id, @Valid @Body Requests.Plugins body) {
        SessionConfig asConfig = new SessionConfig(null, null, null, null, null, null, null, null, null, null, null, null, null, body.plugins(), null);
        JsonNode checked = configs.check(asConfig, "config");
        RunnerSession session = sessions.get(id);
        ObjectNode args = mapper.createObjectNode();
        args.set("plugins", checked.path("plugins").isMissingNode() ? mapper.createArrayNode() : checked.get("plugins"));
        return session.call("setPlugins", args);
    }

    @Get(value = "/{id}/plugins/stream", produces = MediaType.TEXT_EVENT_STREAM)
    public Flux<Event<JsonNode>> pluginStream(@PathVariable String id) {
        return Streams.of(sessions.get(id), RunnerSession.Channel.PLUGINS, mapper, List.of(), Streams::pluginEvents);
    }

    @Get("/{id}/plugins/{pluginId}")
    public JsonNode plugin(@PathVariable String id, @PathVariable String pluginId, @Nullable @QueryValue String images) {
        return sessions.get(id).call("getPlugin", mapper.createObjectNode().put("pluginId", pluginId).put("inlineImages", "inline".equals(images)));
    }

    @Post("/{id}/plugins/{pluginId}/stop")
    public JsonNode stopPlugin(@PathVariable String id, @PathVariable String pluginId) {
        return sessions.get(id).call("stopPlugin", mapper.createObjectNode().put("pluginId", pluginId));
    }

    @Post("/{id}/plugins/{pluginId}/restart")
    public JsonNode restartPlugin(@PathVariable String id, @PathVariable String pluginId) {
        return sessions.get(id).call("restartPlugin", mapper.createObjectNode().put("pluginId", pluginId));
    }

    @Get("/{id}/plugins/{pluginId}/blocks/{index}/image")
    public MutableHttpResponse<byte[]> pluginImage(@PathVariable String id, @PathVariable String pluginId, @PathVariable int index) {
        JsonNode image = sessions.get(id).call("pluginImage", mapper.createObjectNode().put("pluginId", pluginId).put("index", index));
        return HttpResponse.ok(Base64.getDecoder().decode(image.path("dataBase64").asText())).contentType(image.path("type").asText("image/png"));
    }

    // ---- helpers -----------------------------------------------------------------------------------------------------------------------------------------------
    private JsonNode call(String id, String op, @Nullable Object body) {
        JsonNode args = body == null ? mapper.createObjectNode() : mapper.valueToTree(body);
        return sessions.get(id).call(op, args);
    }

    static boolean truthy(@Nullable String value, boolean fallback) {
        if (value == null || value.isBlank()) return fallback;
        return value.matches("(?i)1|true|yes|on");
    }

    private static Long parseLong(@Nullable String text) {
        if (text == null || text.isBlank()) return null;
        try { return Long.parseLong(text.trim()); } catch (NumberFormatException e) { return null; }
    }
}
