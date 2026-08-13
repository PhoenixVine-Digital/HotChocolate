plugins {
    `java-gradle-plugin`
    kotlin("jvm") version "1.9.24"
}

// Same reasoning as the root build.gradle.kts -- without this, this subproject falls back to
// Gradle's own defaults (group = the root project's name, version = "unspecified") instead of
// whatever JitPack passed via `-Pgroup`/`-Pversion`.
group = (findProperty("group") as String?) ?: "hc"
version = (findProperty("version") as String?) ?: "0.1.0"

repositories {
    mavenCentral()
    gradlePluginPortal()
}

gradlePlugin {
    plugins {
        create("hotChocolate") {
            id = "hc"
            implementationClass = "hc.gradle.HotChocolatePlugin"
        }
    }
}

kotlin {
    jvmToolchain(17)
}
