package dev.apklab.queue.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.apklab.api.ApiException;
import dev.apklab.api.QueueRequests;
import dev.apklab.queue.QueueService;
import dev.apklab.queue.QueueSettings;
import dev.apklab.queue.model.QueueItem;
import dev.apklab.queue.runners.RunnerGateway;
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
import io.micronaut.http.multipart.CompletedFileUpload;
import io.micronaut.http.sse.Event;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Interactive leases: the queue assigns a runner session to the holder and proxies its API at /leases/{id}/session/... (every runner session endpoint:
 * tap, type, screenshot.png, logcat/stream, ...) until the lease is deleted, expires (QUEUE_LEASE_MAX_S) or stays idle (QUEUE_LEASE_IDLE_S).
 */
@Controller("/leases")
@ExecuteOn(TaskExecutors.BLOCKING)
public class LeasesController {
    private static final Pattern BAD_PATH = Pattern.compile("(^|/)\\.\\.?(/|$)|%2e|%2f|%5c|\\\\", Pattern.CASE_INSENSITIVE);
    private static final List<String> FORWARDED_REQUEST_HEADERS = List.of("content-type", "accept", "last-event-id", "x-apk-config");
    private static final List<String> FORWARDED_RESPONSE_HEADERS = List.of("content-type", "x-image-size", "x-screenshot-notes", "retry-after", "content-disposition", "cache-control");

    private final QueueService queue;
    private final Submissions submissions;
    private final ObjectMapper mapper;

    public LeasesController(QueueService queue, Submissions submissions, ObjectMapper mapper) {
        this.queue = queue;
        this.submissions = submissions;
        this.mapper = mapper;
    }

    @Post(consumes = MediaType.MULTIPART_FORM_DATA)
    public HttpResponse<ObjectNode> submitMultipart(HttpRequest<?> request, @Part("apk") CompletedFileUpload apk, @Nullable @Part("lease") String lease) throws IOException {
        return submit(request, apk.getBytes(), submissions.parse(lease, QueueRequests.LeaseRequest.class, "lease"));
    }

    @Post(consumes = MediaType.APPLICATION_JSON)
    public HttpResponse<ObjectNode> submitJson(HttpRequest<?> request, @Body String body) {
        return submit(request, null, submissions.parse(body, QueueRequests.LeaseRequest.class, "lease"));
    }

    @Post(consumes = {"application/vnd.android.package-archive", MediaType.APPLICATION_OCTET_STREAM})
    public HttpResponse<ObjectNode> submitRaw(HttpRequest<?> request, @Body byte[] apk, @Nullable @Header("X-Job") String lease) {
        return submit(request, apk, submissions.parse(lease, QueueRequests.LeaseRequest.class, "lease"));
    }

    private HttpResponse<ObjectNode> submit(HttpRequest<?> request, @Nullable byte[] body, QueueRequests.LeaseRequest spec) {
        QueueSettings.Client client = QueueAuthFilter.client(request);
        byte[] apk = submissions.apk(body, spec.apkBase64(), spec.apkUrl());
        QueueItem item = queue.submitLease(client.id(), apk, spec, submissions.config(spec.config()));
        return HttpResponse.status(HttpStatus.ACCEPTED).body(queue.view(item));
    }

    @Get
    public List<ObjectNode> list(HttpRequest<?> request) {
        return queue.list(QueueAuthFilter.client(request).id(), "lease", null).stream().map(queue::view).toList();
    }

    @Get("/{id}")
    public ObjectNode get(HttpRequest<?> request, @PathVariable String id) { return queue.view(own(request, id)); }

    @Get(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM)
    public Flux<Event<JsonNode>> events(HttpRequest<?> request, @PathVariable String id) { return ItemEvents.stream(queue, own(request, id), mapper); }

    @Delete("/{id}")
    public ObjectNode delete(HttpRequest<?> request, @PathVariable String id) { return queue.cancel(own(request, id)); }

    @Post("/{id}/extend")
    public ObjectNode extend(HttpRequest<?> request, @PathVariable String id, @Nullable @Body QueueRequests.Extend body) {
        int seconds = body == null || body.seconds() == null ? 300 : body.seconds();
        return mapper.createObjectNode().put("expiresAt", queue.extend(own(request, id), seconds).toString());
    }

    // ---- the proxy ---------------------------------------------------------------------------------------------------------------------------------------------
    @Get(value = "/{id}/session{/path:.*}", produces = MediaType.ALL)
    public MutableHttpResponse<?> proxyGet(HttpRequest<?> request, @PathVariable String id, @Nullable @PathVariable String path) { return proxy(request, id, path, null); }

    @Post(value = "/{id}/session{/path:.*}", consumes = MediaType.ALL, produces = MediaType.ALL)
    public MutableHttpResponse<?> proxyPost(HttpRequest<?> request, @PathVariable String id, @Nullable @PathVariable String path, @Nullable @Body byte[] body) { return proxy(request, id, path, body); }

    @Put(value = "/{id}/session{/path:.*}", consumes = MediaType.ALL, produces = MediaType.ALL)
    public MutableHttpResponse<?> proxyPut(HttpRequest<?> request, @PathVariable String id, @Nullable @PathVariable String path, @Nullable @Body byte[] body) { return proxy(request, id, path, body); }

    @Patch(value = "/{id}/session{/path:.*}", consumes = MediaType.ALL, produces = MediaType.ALL)
    public MutableHttpResponse<?> proxyPatch(HttpRequest<?> request, @PathVariable String id, @Nullable @PathVariable String path, @Nullable @Body byte[] body) { return proxy(request, id, path, body); }

    @Delete(value = "/{id}/session{/path:.*}", produces = MediaType.ALL)
    public MutableHttpResponse<?> proxyDelete(HttpRequest<?> request, @PathVariable String id, @Nullable @PathVariable String path) {
        QueueItem lease = own(request, id);
        if (path == null || path.isBlank()) return HttpResponse.ok(queue.cancel(lease));   // DELETE of the session itself ends the lease
        return proxy(request, id, path, null);
    }

    private MutableHttpResponse<?> proxy(HttpRequest<?> request, String id, @Nullable String path, @Nullable byte[] body) {
        QueueItem lease = own(request, id);
        String rest = path == null || path.isBlank() ? "" : "/" + path.replaceFirst("^/+", "");
        if (BAD_PATH.matcher(rest).find()) throw ApiException.badRequest("Invalid session path");
        QueueService.RunnerSessionRef target = queue.leaseTarget(lease);
        StringBuilder query = new StringBuilder();
        request.getParameters().forEach((name, values) -> {
            if ("access_token".equals(name)) return;
            for (String value : values) query.append(query.length() == 0 ? "?" : "&").append(enc(name)).append('=').append(enc(value));
        });
        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : FORWARDED_REQUEST_HEADERS) request.getHeaders().findFirst(name).ifPresent(value -> headers.put(name, value));
        RunnerGateway.Raw raw = target.runner().forward(request.getMethod().name(), target.sessionId(), rest + query, headers, body);
        String type = first(raw.headers(), "content-type");
        MutableHttpResponse<Object> response = HttpResponse.status(HttpStatus.valueOf(raw.status()));
        for (String name : FORWARDED_RESPONSE_HEADERS) {
            String value = first(raw.headers(), name);
            if (value != null) response.header(name, value);
        }
        if (type != null && type.startsWith("text/event-stream")) {
            return response.body(stream(raw.body(), lease));
        }
        try (InputStream in = raw.body()) {
            return response.body(in.readAllBytes());
        } catch (IOException e) {
            throw new ApiException(502, "runner-unreachable", "Reading the runner's answer failed: " + e.getMessage());
        }
    }

    /** Copies an SSE body chunk by chunk; reading counts as lease use so an open stream keeps the lease from going idle. */
    private Flux<byte[]> stream(InputStream in, QueueItem lease) {
        return Flux.<byte[]>create(sink -> {
            Thread reader = Thread.ofVirtual().start(() -> {
                byte[] buffer = new byte[16 * 1024];
                try (in) {
                    int n;
                    while ((n = in.read(buffer)) >= 0 && !sink.isCancelled()) {
                        if (n > 0) sink.next(java.util.Arrays.copyOf(buffer, n));
                        lease.lastUsedAt = System.currentTimeMillis();
                        if (lease.isFinal()) break;
                    }
                    sink.complete();
                } catch (IOException e) {
                    sink.complete();
                }
            });
            sink.onDispose(() -> {
                reader.interrupt();
                try { in.close(); } catch (IOException ignored) { /* closing */ }
            });
        }, FluxSink.OverflowStrategy.BUFFER);
    }

    private static String first(Map<String, List<String>> headers, String name) {
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty()) return entry.getValue().get(0);
        }
        return null;
    }

    private static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    private QueueItem own(HttpRequest<?> request, String id) { return queue.own(id, QueueAuthFilter.client(request).id(), "lease"); }
}
