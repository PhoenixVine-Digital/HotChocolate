import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

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

// `java-gradle-plugin` auto-creates a "pluginMaven" publication for the real implementation
// jar, defaulting its artifactId to this project's own name ("hc-gradle-plugin"). JitPack
// separately auto-injects its own default publication for this project's "java" component --
// verified two ways it actually behaves, not just documented behavior: (1) when JitPack's own
// early `./gradlew tasks --all` probe fails to see `publishToMavenLocal` already available
// (which happens because `java-gradle-plugin`'s `maven-publish` application isn't visible to
// that probe), it falls back to a path that both injects that extra publication AND correctly
// packages every module for serving; (2) making the task visible earlier (by applying
// `maven-publish` explicitly) skips that fallback entirely -- no more duplicate-publication
// warning, but JitPack's *other* code path then only serves the root project's own artifacts,
// silently dropping this module. So the fallback path is the one that actually works end-to-end
// and is worth keeping; what's fixed here instead is the actual coordinate collision it causes:
// giving "pluginMaven" a distinct artifactId means it and JitPack's same-named injected
// publication no longer share a GAV, so there's nothing left to "overwrite each other" even
// though both still get created. The plugin marker artifact (what `id("hc")` resolves through)
// is wired to follow "pluginMaven" automatically, so this doesn't change anything for consumers.
// `configure<PublishingExtension>`, not the sugared `publishing { }` block: `maven-publish`
// isn't declared in this file's own `plugins { }` block (only applied transitively by
// `java-gradle-plugin`), so the Kotlin DSL has no typed accessor generated for it at script
// compile time -- this is the same configuration, just reached through the untyped extension API.
// `pluginManager.withPlugin("maven-publish")`, not `afterEvaluate`: `java-gradle-plugin` applies
// `maven-publish` conditionally, only once something actually needs it (a real `publish*` task
// in the requested task graph) -- confirmed directly, since even `afterEvaluate` here still
// fails with "Extension of type 'PublishingExtension' does not exist" on a plain `./gradlew
// build`. `withPlugin` is the idiomatic way to react to a plugin regardless of exactly when
// (or whether) it ends up applied: it fires immediately if already present, or later at the
// moment it actually is, instead of guessing at a specific lifecycle phase.
pluginManager.withPlugin("maven-publish") {
    configure<PublishingExtension> {
        publications.named<MavenPublication>("pluginMaven") {
            artifactId = "hc-gradle-plugin-impl"
        }
    }
}

kotlin {
    jvmToolchain(17)
}
