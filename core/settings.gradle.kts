// Platform-neutral logic shared by the Android shell (and a future iOS shell
// via Kotlin Multiplatform). Builds on its own with plain Gradle, no Android SDK.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositories { mavenCentral() }
}
rootProject.name = "core"
