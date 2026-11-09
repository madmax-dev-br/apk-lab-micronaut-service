plugins {
    id("io.micronaut.application")
}

micronaut {
    version(providers.gradleProperty("micronautVersion").get())
    runtime("netty")
    testRuntime("junit5")
    processing {
        incremental(true)
        annotations("dev.apklab.runner.*")
    }
}

application {
    mainClass.set("dev.apklab.runner.RunnerApplication")
}

dependencies {
    implementation(project(":api"))
    annotationProcessor("io.micronaut.validation:micronaut-validation-processor")
    implementation("io.micronaut.validation:micronaut-validation")
    implementation("io.micronaut:micronaut-jackson-databind")
    implementation("io.micronaut:micronaut-http-server-netty")
    implementation("io.micronaut.reactor:micronaut-reactor")
    runtimeOnly("ch.qos.logback:logback-classic")
    runtimeOnly("org.yaml:snakeyaml")
    testImplementation("io.micronaut:micronaut-http-client")
    testImplementation("io.micronaut.test:micronaut-test-junit5")
    testImplementation("org.junit.jupiter:junit-jupiter-api")
}

// `./gradlew :runner:run` from the repository root: the sidecar is found relative to the repository.
tasks.named<JavaExec>("run") {
    workingDir = rootDir
}
