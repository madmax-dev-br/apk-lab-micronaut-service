package dev.apklab.queue.store;

import dev.apklab.queue.model.QueueItem;

import java.util.List;

/** The default store: nothing survives a restart of the queue. */
public class MemoryStore implements QueueStore {
    @Override public List<QueueItem> load() { return List.of(); }
    @Override public void save(QueueItem item) { }
    @Override public void remove(String id) { }
    @Override public void flush() { }
}
