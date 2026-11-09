plugins {
    id("io.micronaut.application")
}

micronaut {
    version(providers.gradleProperty("micronautVersion").get())
    runtime("netty")
    testRuntime("junit5")
    processing {
        incremental(true)
        annotations("dev.apklab.queue.*")
    }
}

application {
    mainClass.set("dev.apklab.queue.QueueApplication")
}

dependencies {
    implementation(project(":api"))
    annotationProcessor("io.micronaut.validation:micronaut-validation-processor")
    implementation("io.micronaut.validation:micronaut-validation")
    implementation("io.micronaut:micronaut-jackson-databind")
    implementation("io.micronaut:micronaut-http-server-netty")
    implementation("io.micronaut:micronaut-http-client")
    implementation("io.micronaut.reactor:micronaut-reactor")
    runtimeOnly("ch.qos.logback:logback-classic")
    runtimeOnly("org.yaml:snakeyaml")
    testImplementation("io.micronaut.test:micronaut-test-junit5")
    testImplementation("org.junit.jupiter:junit-jupiter-api")
}

tasks.named<JavaExec>("run") {
    workingDir = rootDir
}

// The end-to-end test of a queued job starts a real runner in its own JVM (separate classpath and configuration).
val runnerProject = project(":runner")
tasks.named<Test>("test") {
    dependsOn(":runner:classes", ":runner:processResources")
    doFirst {
        val runnerMain = runnerProject.extensions.getByType<SourceSetContainer>()["main"]
        systemProperty("apklab.runnerClasspath", runnerMain.runtimeClasspath.asPath)
    }
}
