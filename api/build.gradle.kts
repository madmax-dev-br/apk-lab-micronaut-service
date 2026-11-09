plugins {
    id("io.micronaut.library")
}

micronaut {
    version(providers.gradleProperty("micronautVersion").get())
    testRuntime("junit5")
    processing {
        incremental(true)
        annotations("dev.apklab.api.*")
    }
}

dependencies {
    annotationProcessor("io.micronaut.validation:micronaut-validation-processor")
    api("io.micronaut.validation:micronaut-validation")
    api("jakarta.validation:jakarta.validation-api")
    api("io.micronaut:micronaut-jackson-databind")
    testImplementation("org.junit.jupiter:junit-jupiter-api")
}
