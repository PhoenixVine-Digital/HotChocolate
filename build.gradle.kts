plugins {
    kotlin("jvm") version "1.9.24"
    application
}

group = "hc"
version = "0.1.0"

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

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}
