package dev.apklab.queue.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.apklab.queue.QueueApplication;
import dev.apklab.queue.QueueService;
import dev.apklab.queue.Steps;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;

/** Service endpoints of the queue: health (public), the runner registry, the client's stats, the step catalog. */
@Controller
public class QueueController {
    private final QueueService queue;
    private final ObjectMapper mapper;

    public QueueController(QueueService queue, ObjectMapper mapper) {
        this.queue = queue;
        this.mapper = mapper;
    }

    @Get("/")
    public JsonNode root() {
        return mapper.createObjectNode().put("service", "apk-lab-queue").put("implementation", "micronaut").put("version", QueueApplication.VERSION)
                .put("docs", "https://madmax-dev-br.github.io/apk-lab-micronaut-service/");
    }

    @Get("/health")
    public JsonNode health() {
        ObjectNode out = mapper.createObjectNode().put("ok", true).put("service", "apk-lab-queue").put("implementation", "micronaut").put("version", QueueApplication.VERSION);
        ArrayNode runners = out.putArray("runners");
        queue.runners().forEach(r -> runners.addObject().put("id", r.id()).put("healthy", r.healthy()).put("available", r.available()));
        out.put("queued", queue.queuedCount());
        return out;
    }

    @Get("/runners")
    public JsonNode runners() {
        ArrayNode out = mapper.createArrayNode();
        queue.runners().forEach(r -> out.add(mapper.valueToTree(r.describe())));
        return out;
    }

    @Get("/stats")
    public JsonNode stats(HttpRequest<?> request) { return queue.stats(QueueAuthFilter.client(request).id()); }

    @Get("/steps")
    public JsonNode steps() { return Steps.describe(mapper); }
}
