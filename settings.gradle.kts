pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "apk-lab-micronaut-service"

include("api", "runner", "queue")
