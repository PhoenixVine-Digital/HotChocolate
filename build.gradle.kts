plugins {
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
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

// `selfhost/bootstrap` -- the checked-in, pre-compiled bootstrap seed of the self-hosted
// HotChocolate compiler (see selfhost/bootstrap/README.md). These `.class` files have no
// Gradle-compilable source at all (they're built FROM `selfhost/*.hotc` by the self-hosted
// compiler itself, not by `javac`) -- unioning the directory straight onto the runtime classpath
// is how `mainClass.set("SelfhostCLI")` below finds it.
val selfhostBootstrap = files("selfhost/bootstrap")
sourceSets.main.get().runtimeClasspath += selfhostBootstrap

// **Fixed 2026-09-11 -- a real, previously-undiscovered break in "Published on JitPack" itself.**
// Adding `selfhostBootstrap` to `runtimeClasspath` (above) is what makes `./gradlew run` find
// `SelfhostCLI` locally, but it does NOT put those classes INTO the plain `jar` task's own
// output -- `jar` only ever packages `sourceSets.main.output` (the real, `javac`/`kotlinc`-
// compiled classes, which since the self-hosted migration is just `CodegenShim.class` -- every
// OTHER class, including `SelfhostCLI` itself and all of `hc/selfhost/**`, comes from the
// checked-in bootstrap seed, not from this module's own compiled sources). JitPack builds and
// publishes exactly this `jar` task's output as the Maven artifact -- so every tag published
// since the migration produced a real, resolvable, but COMPILER-LESS jar (confirmed: `jar tf
// build/libs/*.jar` before this fix listed only `CodegenShim.class`), silently broken the whole
// time `version = "..."` has been documented as the normal way to consume this compiler.
// Confirmed this was the actual root cause of `ClassNotFoundException: SelfhostCLI` scaffolding
// Marshmallow (the first real end-to-end exercise of the JitPack path since the migration) --
// fixed by explicitly folding the bootstrap seed into the `jar` task's own contents too.
//
// **Fixed 2026-09-11 -- a fourth, still deeper break in the very same "nothing has ever actually
// consumed this artifact" family.** Even with `SelfhostCLI` genuinely present, EVERY compile
// still failed with `NoSuchFileException: stdlib/option.hotc` -- `Driver.hotc`'s own
// `stdlib_topic_path` (`return "stdlib/" + topic + ".hotc";`) resolves every stdlib topic
// (`option`/`vec`/`phoenix`/`ecs`/... -- and note this fires even with ZERO `use` lines in a
// program, since the "no `use` at all" default injects the original five unconditionally) via a
// path relative to the PROCESS'S OWN WORKING DIRECTORY, with no other fallback. That only ever
// worked because every real invocation of this compiler, ever, ran from inside a HotChocolate
// checkout itself (where `stdlib/` genuinely sits next to the process's cwd) -- the published
// jar never bundled the `stdlib/*.hotc` SOURCE files at all, so no external consuming project
// could have compiled anything through this artifact, not even a program with no stdlib usage
// of its own. Fixed here by bundling `stdlib/` into the jar too (as plain classpath-adjacent
// resource files, not `.class` files -- nothing compiles them, `SelfhostCLI` parses them fresh
// like any other `.hc`/`.hotc` source at COMPILE TIME); `hc-gradle-plugin`'s own matching fix
// (see its `HotChocolatePlugin.kt`) extracts them next to each real compile's own working
// directory before invoking the compiler, since `stdlib_topic_path`'s own filesystem-relative
// lookup isn't something a consuming project's `hcCompile*` task can just leave to chance.
tasks.named<Jar>("jar") {
    from(selfhostBootstrap)
    from("stdlib") { into("stdlib") }
}

application {
    mainClass.set("SelfhostCLI")
}

// The application plugin's `run` task doesn't forward stdin to the Gradle Daemon-spawned
// process by default -- `read_line()`-using programs would see EOF immediately even when run
// interactively. Wiring `standardInput` explicitly makes Gradle actually pipe the terminal's
// stdin through -- `SelfhostCLI`'s own "run" mode (`Driver.hotc`'s own `run_cli`) further
// inherits it into the real child `java` process it spawns for the compiled target, so this one
// override covers both hops.
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
    mainClass.set("SelfhostCLI")
    classpath = sourceSets.main.get().runtimeClasspath
    args = listOf("run", "examples/battle")
    standardInput = System.`in`
}

tasks.register<JavaExec>("calc") {
    group = "application"
    description = "Runs the hotc calculator."
    mainClass.set("SelfhostCLI")
    classpath = sourceSets.main.get().runtimeClasspath
    args = listOf("run", "examples/calc")
    standardInput = System.`in`
}
