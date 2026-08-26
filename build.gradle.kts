plugins {
    kotlin("jvm") version "1.9.24"
    application
}

// JitPack builds with `-Pgroup=com.github.<org> -Pversion=<tag>` so consumers can depend on
// `com.github.<org>:HotChocolate:<tag>` -- reading those properties (falling back to plain
// local-dev defaults when they're absent) is what makes the coordinates JitPack's own page
// advertises actually match what gets published, instead of silently publishing under the
// hardcoded local group/version and leaving every copy-pasted dependency line broken.
group = (findProperty("group") as String?) ?: "hc"
version = (findProperty("version") as String?) ?: "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.ow2.asm:asm:9.7")
    implementation("org.ow2.asm:asm-util:9.7")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("hc.MainKt")
}

// The application plugin's `run` task doesn't forward stdin to the Gradle Daemon-spawned
// process by default -- `read_line()`-using programs would see EOF immediately even when run
// interactively. Wiring `standardInput` explicitly makes Gradle actually pipe the terminal's
// stdin through.
tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

// `./gradlew playBattle` -- runs the interactive dungeon-crawl example (examples/battle/,
// prompts for the player's name via `read_line()`) with stdin connected, same reasoning as
// the `run` task above. A thin, purpose-specific alias over `run --args="run examples/battle"`
// so there's a one-word command for it.
tasks.register<JavaExec>("playBattle") {
    group = "application"
    description = "Runs the interactive examples/battle dungeon-crawl demo (hc program)."
    mainClass.set("hc.MainKt")
    classpath = sourceSets.main.get().runtimeClasspath
    args = listOf("run", "examples/battle")
    standardInput = System.`in`
}

tasks.register<JavaExec>("calc") {
    group = "application"
    description = "Runs the hotc calculator."
    mainClass.set("hc.MainKt")
    classpath = sourceSets.main.get().runtimeClasspath
    args = listOf("run", "examples/calc")
    standardInput = System.`in`
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}
