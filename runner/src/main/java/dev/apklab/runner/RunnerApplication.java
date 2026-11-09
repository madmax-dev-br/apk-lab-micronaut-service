package dev.apklab.runner;

import io.micronaut.runtime.Micronaut;

/** The runner: an HTTP API that loads APKs into APK Web Lab sessions (one Node sidecar per session) and exposes every lab feature. */
public final class RunnerApplication {
    public static final String VERSION = "0.1.0";

    private RunnerApplication() {}

    public static void main(String[] args) {
        Micronaut.build(args).mainClass(RunnerApplication.class).defaultEnvironments("runner").start();
    }
}
