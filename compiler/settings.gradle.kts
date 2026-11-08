pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
plugins {
    // Provisions the pinned JVM (Temurin 21, see build.gradle.kts) where it isn't installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement { repositories { mavenCentral() } }
rootProject.name = "drivine-cypher-compiler"
