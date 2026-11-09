package dev.apklab.queue.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.apklab.api.ApiException;
import dev.apklab.api.QueueRequests;
import dev.apklab.queue.QueueService;
import dev.apklab.queue.QueueSettings;
import dev.apklab.queue.model.QueueItem;
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
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.http.multipart.CompletedFileUpload;
import io.micronaut.http.sse.Event;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Jobs: submit (multipart "apk" + "job", JSON with apkBase64 / apkUrl, or the raw APK with an X-Job header), status, SSE progress, cancel, artifacts. */
@Controller("/jobs")
@ExecuteOn(TaskExecutors.BLOCKING)
public class JobsController {
    private final QueueService queue;
    private final Submissions submissions;
    private final ObjectMapper mapper;

    public JobsController(QueueService queue, Submissions submissions, ObjectMapper mapper) {
        this.queue = queue;
        this.submissions = submissions;
        this.mapper = mapper;
    }

    @Post(consumes = MediaType.MULTIPART_FORM_DATA)
    public HttpResponse<ObjectNode> submitMultipart(HttpRequest<?> request, @Part("apk") CompletedFileUpload apk, @Nullable @Part("job") String job) throws IOException {
        return submit(request, apk.getBytes(), submissions.parse(job, QueueRequests.JobRequest.class, "job"));
    }

    @Post(consumes = MediaType.APPLICATION_JSON)
    public HttpResponse<ObjectNode> submitJson(HttpRequest<?> request, @Body String body) {
        return submit(request, null, submissions.parse(body, QueueRequests.JobRequest.class, "job"));
    }

    @Post(consumes = {"application/vnd.android.package-archive", MediaType.APPLICATION_OCTET_STREAM})
    public HttpResponse<ObjectNode> submitRaw(HttpRequest<?> request, @Body byte[] apk, @Nullable @Header("X-Job") String job) {
        return submit(request, apk, submissions.parse(job, QueueRequests.JobRequest.class, "job"));
    }

    private HttpResponse<ObjectNode> submit(HttpRequest<?> request, @Nullable byte[] body, QueueRequests.JobRequest spec) {
        QueueSettings.Client client = QueueAuthFilter.client(request);
        byte[] apk = submissions.apk(body, spec.apkBase64(), spec.apkUrl());
        QueueItem item = queue.submitJob(client.id(), apk, spec, submissions.config(spec.config()));
        return HttpResponse.status(HttpStatus.ACCEPTED).body(queue.view(item));
    }

    @Get
    public List<ObjectNode> list(HttpRequest<?> request, @Nullable @QueryValue String state) {
        return queue.list(QueueAuthFilter.client(request).id(), "job", state).stream().map(queue::view).toList();
    }

    @Get("/{id}")
    public ObjectNode get(HttpRequest<?> request, @PathVariable String id) { return queue.view(own(request, id)); }

    @Get(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM)
    public Flux<Event<JsonNode>> events(HttpRequest<?> request, @PathVariable String id) { return ItemEvents.stream(queue, own(request, id), mapper); }

    @Delete("/{id}")
    public ObjectNode delete(HttpRequest<?> request, @PathVariable String id) { return queue.cancel(own(request, id)); }

    @Post("/{id}/cancel")
    public ObjectNode cancel(HttpRequest<?> request, @PathVariable String id) { return queue.cancel(own(request, id)); }

    @Get("/{id}/artifacts/{name}")
    public MutableHttpResponse<byte[]> artifact(HttpRequest<?> request, @PathVariable String id, @PathVariable String name) throws IOException {
        QueueItem item = own(request, id);
        QueueItem.Artifact artifact = item.result == null ? null : item.result.artifacts.stream().filter(a -> a.name().equals(name)).findFirst().orElse(null);
        if (artifact == null) throw ApiException.notFound("No artifact " + name);
        Path file = queue.artifactFile(item, artifact.name());
        return HttpResponse.ok(Files.readAllBytes(file)).contentType(artifact.type()).header("Content-Disposition", "attachment; filename=\"" + artifact.name() + "\"");
    }

    private QueueItem own(HttpRequest<?> request, String id) { return queue.own(id, QueueAuthFilter.client(request).id(), "job"); }
}
