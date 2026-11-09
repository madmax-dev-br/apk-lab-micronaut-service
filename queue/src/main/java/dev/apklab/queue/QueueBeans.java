package dev.apklab.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.apklab.queue.runners.HttpRunnerGateway;
import dev.apklab.queue.runners.RunnerGateway;
import dev.apklab.queue.store.JsonFileStore;
import dev.apklab.queue.store.MemoryStore;
import dev.apklab.queue.store.QueueStore;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;

import java.nio.file.Path;
import java.util.List;

/** Wires the queue from its settings: the store (QUEUE_STORE=memory | file), the runner registry (QUEUE_RUNNERS) and the service. */
@Factory
public class QueueBeans {

    @Singleton
    QueueStore store(QueueSettings settings, ObjectMapper mapper) {
        if ("file".equalsIgnoreCase(settings.getStore())) {
            Path file = settings.getStoreFile().isBlank() ? settings.dataPath().resolve("queue.json") : Path.of(settings.getStoreFile());
            return new JsonFileStore(file, mapper);
        }
        if (!"memory".equalsIgnoreCase(settings.getStore())) throw new IllegalStateException("QUEUE_STORE must be memory or file");
        return new MemoryStore();
    }

    /** The runners of QUEUE_RUNNERS (a holder: a List bean would collide with Micronaut's injection of all beans of a type). */
    public record RunnerRegistry(List<RunnerGateway> runners) {}

    @Singleton
    RunnerRegistry runners(QueueSettings settings, ObjectMapper mapper) {
        return new RunnerRegistry(settings.runnerList().stream().<RunnerGateway>map(ref -> new HttpRunnerGateway(ref, mapper)).toList());
    }

    @Context
    @Bean(preDestroy = "close")
    QueueService service(QueueSettings settings, ObjectMapper mapper, QueueStore store, RunnerRegistry registry) {
        if (!settings.authDisabled() && settings.clientMap().isEmpty()) throw new IllegalStateException("QUEUE_CLIENTS is required (or QUEUE_AUTH=off for local, single-user use)");
        return new QueueService(settings, mapper, store, registry.runners()).start();
    }
}
