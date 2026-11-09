package dev.apklab.queue.store;

import dev.apklab.queue.model.QueueItem;

import java.util.List;

/**
 * Persistence of jobs and leases. The queue keeps its working set in memory and calls the store on every change. A database store (PostgreSQL, Redis, ...)
 * implements the same four methods; {@link MemoryStore} keeps nothing across restarts, {@link JsonFileStore} writes one JSON file atomically.
 */
public interface QueueStore {
    /** Items saved before the last shutdown (called once at start). */
    List<QueueItem> load();
    /** The item changed (implementations may batch writes). */
    void save(QueueItem item);
    /** The item was removed by retention. */
    void remove(String id);
    /** Writes everything pending (shutdown). */
    void flush();
}
