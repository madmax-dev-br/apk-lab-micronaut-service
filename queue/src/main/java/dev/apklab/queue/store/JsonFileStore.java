package dev.apklab.queue.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.apklab.queue.model.QueueItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** One JSON file ({ version, savedAt, items }), rewritten atomically (temp file + rename) at most every 200 ms after a change. */
public class JsonFileStore implements QueueStore {
    private static final Logger LOG = LoggerFactory.getLogger(JsonFileStore.class);
    private final Path file;
    private final ObjectMapper mapper;
    private final Map<String, JsonNode> items = new LinkedHashMap<>();
    private final ScheduledExecutorService writer = Executors.newSingleThreadScheduledExecutor(r -> Thread.ofPlatform().daemon().name("queue-store").unstarted(r));
    private ScheduledFuture<?> pending;

    public JsonFileStore(Path file, ObjectMapper mapper) {
        this.file = file.toAbsolutePath();
        this.mapper = mapper;
    }

    @Override
    public synchronized List<QueueItem> load() {
        List<QueueItem> out = new ArrayList<>();
        if (!Files.exists(file)) return out;
        try {
            JsonNode data = mapper.readTree(file.toFile());
            for (JsonNode node : data.path("items")) {
                items.put(node.path("id").asText(), node);
                out.add(mapper.treeToValue(node, QueueItem.class));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read the queue store " + file + ": " + e.getMessage(), e);
        }
        return out;
    }

    @Override
    public synchronized void save(QueueItem item) {
        items.put(item.id, mapper.valueToTree(item));
        schedule();
    }

    @Override
    public synchronized void remove(String id) {
        items.remove(id);
        schedule();
    }

    private void schedule() {
        if (pending != null && !pending.isDone()) return;
        pending = writer.schedule(this::writeQuietly, 200, TimeUnit.MILLISECONDS);
    }

    private void writeQuietly() {
        try { write(); } catch (IOException e) { LOG.warn("queue store {}: {}", file, e.getMessage()); }
    }

    private void write() throws IOException {
        ObjectNode root = mapper.createObjectNode();
        root.put("version", 1);
        root.put("savedAt", Instant.now().toString());
        ArrayNode list = root.putArray("items");
        synchronized (this) { items.values().forEach(list::add); }
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + "." + ProcessHandle.current().pid() + ".tmp");
        mapper.writeValue(temp.toFile(), root);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override
    public void flush() {
        synchronized (this) { if (pending != null) pending.cancel(false); }
        writeQuietly();
        writer.shutdown();
    }
}
