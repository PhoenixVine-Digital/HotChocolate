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

// Deliberately NOT trying to silence Gradle's "Multiple publications ... will overwrite each
// other" warning here (java-gradle-plugin's own "pluginMaven" publication vs. one JitPack
// injects for the same coordinates) -- two different fix attempts were tried and reverted after
// real JitPack builds (not just local ones) showed each one traded the cosmetic warning for an
// actual regression:
//   1. Applying `maven-publish` explicitly (so JitPack's own early probe sees
//      `publishToMavenLocal` already exists) does silence the warning, but that probe result is
//      also what triggers the *fallback* codepath in JitPack's own build script that correctly
//      packages every module for serving -- skip the fallback and it silently serves only the
//      root project's artifacts, dropping this module's jar/pom entirely from what's published.
//   2. Keeping the fallback active and instead giving "pluginMaven" a distinct artifactId (via
//      `pluginManager.withPlugin("maven-publish") { ... }`, to dodge the actual coordinate
//      collision) fails outright on JitPack specifically: whatever applies `maven-publish` in
//      that fallback path isn't `java-gradle-plugin`'s own internal application, so
//      "pluginMaven" doesn't exist yet when the hook fires -- "Publication with name
//      'pluginMaven' not found", a hard build failure, strictly worse than the warning.
// The warning is real but non-fatal (the build still succeeds, both modules still get
// correctly served under it -- verified via an actual JitPack build.log). Leaving it alone is
// the working state; both silencing attempts are documented here so they aren't retried blind.

kotlin {
    jvmToolchain(17)
}
