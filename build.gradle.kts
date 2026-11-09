plugins {
    id("io.micronaut.application") version "4.6.2" apply false
    id("io.micronaut.library") version "4.6.2" apply false
}

subprojects {
    group = "dev.apklab"
    version = "0.1.0"
    repositories { mavenCentral() }
    tasks.withType<JavaCompile>().configureEach {
        options.release.set(21)
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-parameters", "-Xlint:unchecked"))
    }
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        // End-to-end tests run only when both are set (see docs, "Testes"); CI leaves them unset.
        listOf("APK_LAB_RUNTIME_DIR", "APK_LAB_TEST_APK_DIR", "APK_LAB_NODE").forEach { name ->
            System.getenv(name)?.let { environment(name, it) }
        }
        systemProperty("apklab.repoRoot", rootDir.absolutePath)
        testLogging { events("passed", "skipped", "failed"); showStandardStreams = false; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    }
}

// Downloads the pinned APK Web Lab runtime (sidecar/runtime.lock.json) into vendor/apk-lab with SHA-256 verification.
tasks.register<Exec>("fetchRuntime") {
    group = "apk lab"
    description = "Downloads the pinned APK Web Lab runtime into vendor/apk-lab (SHA-256 verified)."
    workingDir = rootDir
    commandLine(System.getenv("APK_LAB_NODE") ?: "node", "sidecar/fetch-runtime.mjs")
}
