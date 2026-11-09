package dev.apklab.queue.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.apklab.queue.QueueService;
import dev.apklab.queue.model.QueueItem;
import io.micronaut.http.sse.Event;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;

import java.util.Set;
import java.util.concurrent.TimeUnit;

/** SSE progress of a job or lease: every past event, then live ones; the stream ends after the final state (succeeded, failed, cancelled, expired). */
final class ItemEvents {
    private static final Set<String> FINAL = QueueItem.FINAL_STATES;
    private ItemEvents() {}

    static Flux<Event<JsonNode>> stream(QueueService queue, QueueItem item, ObjectMapper mapper) {
        return Flux.<Event<JsonNode>>create(sink -> {
            Runnable unlisten = queue.listen(item, event -> {
                sink.next(Event.<JsonNode>of(event).name(event.path("type").asText("event")));
                if (FINAL.contains(event.path("type").asText())) sink.complete();
            });
            for (ObjectNode event : queue.pastEvents(item)) sink.next(Event.<JsonNode>of(event).name(event.path("type").asText("event")));
            if (item.isFinal()) sink.complete();
            Disposable heartbeat = Schedulers.parallel().schedulePeriodically(() -> sink.next(Event.<JsonNode>of(mapper.createObjectNode()).name("ping")), 15, 15, TimeUnit.SECONDS);
            sink.onDispose(() -> { heartbeat.dispose(); unlisten.run(); });
        }, FluxSink.OverflowStrategy.BUFFER);
    }
}
