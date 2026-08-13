plugins {
    `java-gradle-plugin`
    // Explicit, not just `java-gradle-plugin`'s own transitive application -- JitPack probes
    // for a `publishToMavenLocal` task with a preliminary `./gradlew tasks --all` before
    // deciding whether to inject its own default publication for the "java" component. That
    // probe apparently runs before `java-gradle-plugin`'s own lazy `maven-publish` application
    // is visible to it, so it always concluded one was missing and injected a second, redundant
    // publication alongside the real "pluginMaven" one -- both targeting the identical
    // group:artifact:version, hence Gradle's own "will overwrite each other" warning. Applying
    // it explicitly up front is what should make the task visible to that early probe.
    `maven-publish`
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
