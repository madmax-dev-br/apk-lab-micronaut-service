package dev.apklab.runner.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.apklab.runner.session.RunnerSession;
import io.micronaut.http.sse.Event;
import reactor.core.Disposable;
import reactor.core.publisher.BufferOverflowStrategy;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Server-sent event streams of a session. The session's channel is subscribed in the sidecar only while at least one stream is open (for Logcat this also
 * means the VM sends its lines only then); a "ping" event every 15 s keeps proxies from closing idle streams; the stream ends when the session closes.
 */
public final class Streams {
    private Streams() {}

    public static Flux<Event<JsonNode>> of(RunnerSession session, RunnerSession.Channel channel, ObjectMapper mapper,
                                           List<Event<JsonNode>> initial, Function<JsonNode, List<Event<JsonNode>>> toEvents) {
        return Flux.<Event<JsonNode>>create(sink -> {
            for (Event<JsonNode> event : initial) sink.next(event);
            Runnable unlisten = session.listen(channel, message -> {
                for (Event<JsonNode> event : toEvents.apply(message)) sink.next(event);
            });
            Runnable unclose = session.onClose(sink::complete);
            Disposable heartbeat = Schedulers.parallel().schedulePeriodically(() -> sink.next(Event.<JsonNode>of(mapper.createObjectNode()).name("ping")), 15, 15, TimeUnit.SECONDS);
            sink.onDispose(() -> {
                heartbeat.dispose();
                unclose.run();
                unlisten.run();
            });
        }, FluxSink.OverflowStrategy.BUFFER).onBackpressureBuffer(10_000, dropped -> {}, BufferOverflowStrategy.DROP_OLDEST);
    }

    /** Logcat lines: one "log" event per line, id = the line's sequence number (usable as ?since= / Last-Event-ID to resume). */
    public static List<Event<JsonNode>> logLines(JsonNode message) {
        return eachRow(message.path("data"), "log");
    }

    /** Network log rows: one "request" event per row (a row is sent again when it completes), id = its sequence number. */
    public static List<Event<JsonNode>> netRows(JsonNode message) {
        return eachRow(message.path("data"), "request");
    }

    private static List<Event<JsonNode>> eachRow(JsonNode rows, String name) {
        if (!rows.isArray()) return List.of();
        java.util.ArrayList<Event<JsonNode>> out = new java.util.ArrayList<>(rows.size());
        for (JsonNode row : rows) {
            Event<JsonNode> event = Event.of(row).name(name);
            if (row.has("seq")) event = event.id(row.get("seq").asText());
            out.add(event);
        }
        return out;
    }

    /** Session events (snapshot, loaded, runtime-error, permission-request, crashed, closed, ...): the event name is the session event's name. */
    public static List<Event<JsonNode>> sessionEvents(JsonNode message) {
        return List.of(Event.of(message.path("data")).name(message.path("name").asText("session")));
    }

    public static List<Event<JsonNode>> pluginEvents(JsonNode message) {
        return List.of(Event.of(message.path("data")).name("plugin"));
    }
}
