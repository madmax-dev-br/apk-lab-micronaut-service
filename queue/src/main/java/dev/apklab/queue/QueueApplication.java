package dev.apklab.queue;

import io.micronaut.runtime.Micronaut;

/** The queue: jobs and interactive leases of APK sessions, scheduled on the registered runners. */
public final class QueueApplication {
    public static final String VERSION = "0.1.0";

    private QueueApplication() {}

    public static void main(String[] args) {
        Micronaut.build(args).mainClass(QueueApplication.class).defaultEnvironments("queue").start();
    }
}
