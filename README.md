# Hot Chocolate

## Here we only got one rule, never EVER let it cool.

A small JVM-targeted language for writing games and game mods (Minecraft/
Forge being the concrete, motivating target — see
[HOTC_MC_IDEAS.md](HOTC_MC_IDEAS.md)). The pitch: keep the parts of
Kotlin/Scala/Rust worth keeping for that job — concise syntax, real
generics that actually monomorphize instead of erasing/boxing, full
JVM/JDK interop so it drops into an existing engine/mod ecosystem for
free, static typing — and add just enough compiler discipline about
ownership to catch the mistakes that actually bite in game code:
accidentally aliasing/mutating shared state across systems, and proving
two background/parallel tasks (or two ECS systems) can't race on the
same data. Not an attempt at Rust's full memory-safety story — the JVM's
GC already owns that, so the scope here is deliberately narrower: move/
borrow checking for aliasing bugs, not lifetimes. This is a personal,
niche-scoped project, not a bid to replace anything mainstream — built
because it makes the author's own game/mod code better, not to chase
adoption. See [VALUE_PROPOSITION.md](VALUE_PROPOSITION.md) for the case
for why, laid out feature by feature against plain Java.

**License**: [Business Source License 1.1](LICENSE) — source-available now,
free for effectively all use (including compiling and distributing your own
programs written in it) except offering Hot Chocolate itself as a competing
commercial product or service; converts automatically to the GNU Affero
General Public License v3.0 on 2029-08-13, deliberately a strong-copyleft
license rather than a permissive one — chosen so that even after the BSL
window ends, nobody can take Hot Chocolate proprietary or rebrand it as
their own closed product, including as a hosted/network service (the "SaaS
loophole" plain GPL leaves open, which AGPL specifically closes).

## Where to start

- **Never programmed at all before?** [FIRST_LANGUAGE.md](FIRST_LANGUAGE.md).
- **Know how to program in something already?** [TUTORIAL.md](TUTORIAL.md) —
  a from-zero, example-driven walkthrough of the language.
- **Deciding whether this is worth using at all?** [VALUE_PROPOSITION.md](VALUE_PROPOSITION.md) —
  what it offers over plain Java, feature by feature.
- **Want the full design rationale, every feature's own disclosed scope
  cuts, and the compiler's implementation history?** [ARCHITECTURE.md](ARCHITECTURE.md) —
  the deep reference this README used to be, split out on its own.

## What's real right now

The compiler is **self-hosted** — written in Hot Chocolate itself
(`selfhost/{ast,lexer,parser,checker,codegen}/*.hotc` +
`selfhost/Driver.hotc`), built via a checked-in bootstrap and run as
`SelfhostCLI`. It's a scoped-down subset of an earlier, more complete
Kotlin implementation, but the core of the pitch above is real and
working today, not aspirational:

- **Move/borrow checking** — a struct passed by value and used again
  afterward is a real compile error; so is mutating through a plain `&`,
  or taking `&mut x` on something not declared `var`.
  `&`/`&mut` on a param is the whole annotation — nothing extra to write.
- **Real generics** — `Vec<Int>` genuinely monomorphizes (a real `Vec$Int`
  class holding raw `int`s), no Java-style erase-and-box.
- **`@sendable`** — compile-time proof that a lambda handed to a
  background task can't alias shared state it shouldn't touch.
- **Phoenix Flight** — a real background task pool (`use phoenix;`):
  spawn/join, real async chaining, virtual threads, real unboxed
  primitive task results — all with the `@sendable` safety guarantee
  built in.
- **ECS with ownership-derived scheduling** (`use ecs;`) — `component`/
  `system` declarations, real archetype storage, and a scheduler that
  derives which systems can run in parallel directly from their own
  `&`/`&mut` param annotations — the SAME signal the borrow checker
  already tracks, not a second declaration mechanism. Includes real
  parallel dispatch onto Phoenix Flight's own pool, query filters
  (`Without<T>`, `Changed<T>`), and a `--explain-schedule` flag to see
  the derived run order.
- **`arena struct`** — off-heap, contiguous game data (particles, ECS
  components) built on `java.lang.foreign`, for the hot-path allocations
  a GC-backed language usually can't avoid.
- **`--target 17|21|25`** — one compiler, three JDK targets, picked per
  invocation (e.g. an older Minecraft mod line pinned to 17 alongside a
  newer one on 21, from the same toolchain).
- **`math`** (`use math;`) — `Vec2`/`Vec3`/`Vec4`, `Mat4`, `Quaternion`,
  plus a seamless `extern class` wrapper over `java.lang.Math` itself,
  so the full JDK scalar-math API and real game/3D math both come from
  the same `use` line.
- **Full JDK/Java interop** (`extern class`/`extern interface`,
  compile-time `--classpath`-verified signatures — a wrong param/return
  type is a compile error, not a runtime `NoSuchMethodError`), sealed
  interfaces + exhaustive `match`, a real `Result<T, E>` with `?`,
  string interpolation, nullable types, and more.

See [ARCHITECTURE.md](ARCHITECTURE.md)'s own "Status" section for the
full, honest list of what's implemented vs. still a disclosed scope cut.

## Language sample

```
struct Player {
    name: String,
    hp: Int,
}

fn damage(p: &Player, amount: Int) -> Int {
    return p.hp - amount;
}

fn main() {
    let p = Player { name: "Rin", hp: 100 };
    let remaining = damage(&p, 30); // borrowed, p is still usable after
    print(remaining);
}
```

## Try it

```bash
./gradlew run --args="run examples/hello.hc"
```

or emit class files:

```bash
./gradlew run --args="build examples/hello.hc out"
```

For a program that uses `read_line()`, `./gradlew run` needs stdin
wired up (see `standardInput = System.\`in\`` on the `run` task in
`build.gradle.kts` — the Gradle Daemon doesn't forward stdin to a spawned
process otherwise). There's also a dedicated task for the interactive
[`examples/battle`](examples/battle/) dungeon-crawl demo — asks for your
name, then throws three enemies at you:

```bash
./gradlew playBattle
```

## Using it in a real project

A real, reusable Gradle plugin (`hc-gradle-plugin/`) — not a hand-copied
task block:

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        maven { url = uri("https://jitpack.io") }
        gradlePluginPortal()
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "hc") {
                useModule("com.github.P-H-O-E-N-I-X-PackForge.HotChocolate:hc-gradle-plugin:${requested.version}")
            }
        }
    }
}
```

```kotlin
// build.gradle.kts
plugins {
    id("hc") version "v0.1.6"
}

hotChocolate {
    version = "v0.1.6"   // resolves the compiler from JitPack
    sourceDir("src/main/hc")
}
```

No local HotChocolate checkout needed — both the plugin and the compiler
resolve from JitPack. See [ARCHITECTURE.md](ARCHITECTURE.md)'s own
"Gradle plugin" and "Published on JitPack" sections for the full setup
(including the local-checkout `compilerHome`/`includeBuild` path for
working on the compiler itself, and known JitPack quirks).

## Ideas backlog

Syntax sugar and features that came up, got weighed, and are worth
revisiting later but aren't built yet — see [IDEAS.md](IDEAS.md). Bigger,
actively-worked designs get their own doc once they're real:
[PHOENIX_FLIGHT_IDEAS.md](PHOENIX_FLIGHT_IDEAS.md),
[ECS_IDEAS.md](ECS_IDEAS.md).

## Layout

```
selfhost/
  ast/       Ast.hotc — AST node definitions
  lexer/     Lexer.hotc — tokenizer
  parser/    Parser.hotc — recursive-descent parser
  checker/   Checker.hotc — type checker + move/borrow checking
  codegen/   Codegen.hotc — ASM bytecode emitter
  Driver.hotc     — CLI entry point (built as SelfhostCLI)
  bootstrap/      — checked-in .class files used to compile selfhost/*.hotc itself
stdlib/            Option/Result/Vec/Registry/io/Phoenix Flight/ECS — opt-in via `use <topic>;`
src/main/java/hc/selfhost/codegen/CodegenShim.java  — small Java shim the self-hosted codegen calls into
examples/          sample .hc programs
hc-gradle-plugin/  the `hc` Gradle plugin
hc-intellij-plugin/  IntelliJ platform plugin for HC syntax/tooling support
```
