# Ideas backlog

Syntax sugar and features that came up, got weighed, and are worth doing —
just not right now. Not a roadmap with dates, a parking lot: each entry
should have enough context that picking it up later doesn't need
re-deriving the reasoning from scratch. Move an entry into the README
(with a real implementation, not just a description) when it actually
gets built; delete an entry if it turns out to be a bad idea on reflection
rather than leaving it here stale.

## High leverage, do these next

### `if let Some(x) = opt { }`

```
if let Some(pos1) = pos1_opt {
    // pos1: BlockPos, bound fresh, exactly like a match arm
}
```

Sugar over `match`, not a new mechanism: desugars to `match pos1_opt {
Some { value: pos1 } => { <body> } _ => {} }`, reusing pattern-binding and
scrutinee-move semantics `match` already has, just without match's
exhaustiveness requirement (that's the actual point — `if let` is allowed
to ignore variants it doesn't care about). Cuts real nesting in ported
code — `CopyToolItem::use_item`'s two-level `match pos1_opt { Some { ... }
=> { match pos2_opt { ... } } }` is exactly the shape this flattens.

**Scope v1 to a single pattern per `if let`** — no tuple/`and`-chaining
(`if let Some(a) = x, Some(b) = y { }`, sometimes called "let chains,"
isn't even stable in Rust yet). Nested `if let`s already solve the
two-`Option` case reasonably. **Deliberately not doing**: implicit
flow-typing where an existing `Option<T>` variable gets silently
re-typed to `T` inside an `if` based on control flow (e.g. `if pos1 and
pos2 { }` re-typing both in place) — nothing else in this checker
does flow-sensitive narrowing of an *existing* variable; every other
narrowing mechanism (`match`) binds into a **fresh** name instead of
reinterpreting the original. Keeping that invariant (a variable's type
never silently changes mid-function) is worth more than the extra
brevity here.

### Inclusive ranges: `a..=b`

```
for z in min_z..=max_z {
    for x in 0..<size_x {
        ...
    }
}
```

Add `..=` (inclusive) as a **new** option alongside the existing `..`
(exclusive) — don't rename `..` to `..<` for symmetry, since that would
be a breaking migration across every `.hc` file that exists today
(including the whole real mod port) for a purely cosmetic gain. Low risk:
one new lexer token, parser accepts it in the same range-in-`for`-header
spot `..` already occupies, and codegen either desugars `a..=b` to the
existing `a..(b+1)` or just flips the loop-exit comparison
(`IF_ICMPGE`→`IF_ICMPGT`). Directly fixes the actual misreadable spot in
real code: `for z in min_z..max_z + 1` reads worse than `..=max_z` would.

### Structured `///` doc comments (compiler-understood, not Javadoc-style text blobs)

```
/// Attacks an enemy and returns the resulting damage.
/// @param target The enemy being attacked.
/// @returns The damage dealt.
/// @see Enemy.health
fn attack(target: &Enemy) -> Int { ... }
```

The actual differentiator isn't prettier HTML output, it's that a doc
comment becomes a real `DocComment` node the parser attaches to the
`FnDecl`/`StructDecl`/etc. it precedes — `@param`/`@returns`/`@example`/
`@warning`/`@see`/`@deprecated` structured as tagged fields, not opaque
text a separate tool re-parses later the way Javadoc does. Two things
fall out of that almost for free once the model exists:

- **`@see` targets get checked against the real symbol table.** The
  checker already resolves every name in the program — cross-referencing
  a `@see SomeStruct.method` against that same resolution is a small
  addition on existing infrastructure, and "no dead doc links, ever" is
  an immediate, concrete improvement over every other language's docs.
- **One clean renderer** (an `hc doc <dir>` CLI command emitting
  Markdown/HTML in the README's own prose style, not a Javadoc-style API
  dump) already beats Javadoc aesthetically, and every other output
  format (IDE hover, a Javadoc-compat export for Java-side consumers,
  an LLM-readable reference dump) is just another renderer over the same
  structured model later — don't build more than one up front.

**Deliberately NOT doing**: a parallel `doc fn { summary ... }` block
declaration syntax — it duplicates the signature you already wrote once
and pushes the real `fn` declaration further down the file for no
capability gain over structured `///` tags. Also not doing (yet):
compiled/verified `@example` blocks (extract each example, compile+run it
as its own program, fail the doc build on mismatch) — genuinely valuable,
"documentation that can't go stale because it's tested," but it's a
second compiler entry point (a doc-test mode), not a parser addition —
real phase-2 material once the structured model itself exists and has a
consumer.

### Property-style *write* access for `extern class` setters (the read half already shipped)

```
enemy.health = 50      // instead of enemy.setHealth(50)
```

The read half of this (`enemy.health` reading as `enemy.getHealth()`/
`enemy.isHealthy()`, see README) already shipped, scoped to non-`lazy`
`extern class` declarations (explicit signature or `use { }` reflection,
both fully resolved up front -- probing a *lazy* class for both `getX`/
`isX` candidate names would fire real, potentially-noisy reflection
lookups for guesses, not a single deliberate call). The write half is a
genuinely separate, bigger addition, not just "the same trick in
reverse": there's currently **no extern instance field *write* path at
all** in the checker (`checkFieldAssign` only ever handles `Ty.Struct`
receivers today) -- adding setter sugar means building that whole write
pathway from scratch, not layering sugar over an existing mechanism the
way the getter case did. Same resolution rule the getter case already
established: sugar only applies when there's no real declared field of
that name (a real field always wins, never a silent choice between the
two), and needs a matching single-arg `setX` method whose param type
matches the assigned value's type.

### `@must_use` — compiler-enforced "don't silently drop this return value"

```
@must_use
fn try_spawn(pos: BlockPos) -> SpawnResult { ... }

fn main() {
    try_spawn(pos);  // compile error: return value of 'try_spawn' must be used
    let result = try_spawn(pos);  // fine
}
```

Proven pattern (Rust's `#[must_use]`, C++'s `[[nodiscard]]`) that pairs
naturally with `Result<T, E>` (already shipped, see "Error handling" in
the README): a fallible operation whose failure case is silently ignored
is one of the most common real bug classes in exactly the kind of code
this language targets (game/engine APIs where "did the spawn actually
happen" matters). Cheap to build: a checker-level annotation (reusing the
`@"binary.Name"` machinery, or a lighter HC-only marker that never needs
to reach bytecode at all — this one's purely a compile-time discipline,
not something Java reflection needs to see) recorded per `fn`, checked
at every `Stmt.ExprStmt` whose expression is a `Call`/`MethodCall`/
`StaticCall` targeting a `@must_use` fn. General-purpose, not
game-specific — applies equally to any fallible HC-native fn, not just
engine APIs.

### Explicit, seeded RNG as a real type

```
let rng = Random(seed);
let pick = rng.choose(enemies);
let dmg = rng.int(5..=10);
```

Cheap, no engine/runtime needed, and not really a standalone idea so
much as the *prerequisite* for `@deterministic` (above) to mean anything
at all: a global mutable RNG (`Random.nextFloat()`-style, reached
straight through `java.util.Random`/`Math.random()` today) is itself a
hidden source of nondeterminism — two runs calling it in a different
order get different results, silently, with no way for the checker to
see it coming. An `rng: Random` value threaded explicitly through
`@deterministic` code (same shape as `&mut` state already threaded
through fn params everywhere else in this language) makes "this
function's randomness is part of its declared inputs" a checkable fact
instead of an assumption. Doesn't need new compiler machinery — this is
almost entirely `extern class Random = "java.util.Random" { ... }` plus
a handful of convenience methods (`choose`/`int(range)`/`float()`)
already expressible with what's shipped (ranges, generics for `choose`
over an array). Mostly a prelude/stdlib addition, not a language
feature — worth doing whenever the `@deterministic` design work above
actually happens, as its concrete foundation.

### Compile-time asset existence checks

```
let texture = asset("textures/player.png");
```

```
let texture = asset("textures/plauer.png");
// compile error: asset 'textures/plauer.png' not found in <configured assets dir>
```

A misspelled resource path is a real, common bug class in game/engine
code (and doubly so in Minecraft modding specifically, where texture/
sound/model paths are just string literals with zero compiler help
today) — and unlike most of this backlog, it's genuinely simple: a
`asset("...")` builtin (or `extern`-adjacent syntax) that resolves
against a compiler-configured assets directory (a CLI flag or a
`hotChocolate { }` Gradle DSL entry, same shape as `compilerHome`) and
fails to compile if the file isn't there. **Scope this narrowly**: just
existence-checking a path string, returning `String` (the resolved/
validated path) — no `Asset<Texture>` typed-resource machinery, no
asset-type inference, no build-time asset transformation. Those are
real, bigger ideas (see the `@gpu`/typestate entries below for the
general shape of "types that track resource state") but conflating them
with this one keeps a genuinely cheap, high-value check from shipping.

### `@dev` — conditionally-compiled debug-only code

```
@dev
fn debug_draw_hitboxes() { ... }

fn main() {
    if dev {
        debug.draw_text(...);  // compiled out entirely in a release build
    }
}
```

Small, proven (Rust's `#[cfg(debug_assertions)]`), and genuinely useful:
debug-only rendering/logging/assertions that should have zero presence
(not just "disabled at runtime" — actually absent from the classfile) in
a shipped build. Parser + checker + codegen all need to know about a
build-mode flag (a new `hc build --release`-style CLI switch, defaulting
to "dev" the way `hc run` already implicitly is), and codegen simply
skips emitting `@dev`-marked fns/`if dev { }` blocks entirely when that
flag is off. General-purpose, not game-specific.

### Typestate types: resource lifecycle tracked in the type system

```
struct Texture<State> { ... }

impl Texture<Loading> {
    fn poll(&self) -> TextureLoadResult { ... }
}
impl Texture<Ready> {
    fn draw(&self, x: Int, y: Int) { ... }
}

fn main() {
    let t: Texture<Loading> = Texture::load("player.png");
    t.draw(0, 0);  // compile error: no method 'draw' on Texture<Loading>
}
```

Real, implementable using infrastructure that already exists —
monomorphized generics (`Ty` already tracks concrete type args per
instantiation) — no engine runtime required, just a phantom type
parameter that never actually holds a value, purely there to gate which
`impl<State>` block's methods are callable. The genuine payoff: a whole
class of "used a resource before it was ready" bugs (drawing an
unloaded texture, rendering an unuploaded mesh) becomes a compile error
instead of a runtime crash or silent no-op. Real design work: how a
value's tracked state *transitions* (does `Texture::finish_loading(t:
Texture<Loading>) -> Texture<Ready>` consume the old one and hand back a
differently-typed new one — which fits this checker's existing move
semantics naturally, since the old binding really is "used up" — or is
there a mutation-in-place story, which is a much harder fit given `Ty`
doesn't change type across a mutation today).

## Good ideas, real design cost — defer

### Verify a declared `module` matches the file's directory path (opt-in, not a hard requirement)

Real gap surfaced by this session's own directory-mode work: `module
a.b.c;` is purely a declared string, completely disconnected from the
file's actual location on disk — nothing stops (or even warns about) a
copy-pasted file keeping its old module line, two files in the same
directory declaring wildly different unrelated modules, or a typo in a
dotted module path silently landing a struct in the wrong package.
Java/Kotlin both enforce (or strongly convention) that a source file's
`package` line matches its directory path relative to the source root,
catching exactly this class of mistake at the point the file is
written, not later as a confusing cross-reference/import error.

**Deliberately not proposing this as a hard requirement or default-on
check**, though. Directory-mode compilation is recursive now (see the
README's "Multi-file projects"), and `kubejs-aisle-tool` itself has
since moved to exactly the nested-directory-mirrors-module layout this
entry describes (`client/CopyToolHudOverlay.hc` declaring `module
...client;`, etc.) — so the flat-layout argument that originally
motivated keeping this opt-in no longer applies to that project
specifically. Still worth keeping opt-in rather than default-on,
though: a smaller project, or a "just a couple example scripts, no real
module structure" directory, shouldn't be forced into Java-style
directory discipline it has no use for. The real motivating case for
*building* this now is sharper than it was, though — the exact silent
failure mode this entry describes already happened for real once
(files moved into `client/`/`defs/`/`items/` subdirectories while
directory-mode compilation was still non-recursive, so 5 of 9 files
were silently excluded from the compile with zero error or warning,
caught only because the build broke downstream). Recursive scanning
(now shipped) fixes the *files-go-missing* half of that; this entry is
the other half — catching a *mismatched* module/directory before it
causes a confusing error, not just after.

Real middle ground: an opt-in check (`hc build --check-module-dirs`, or
a `hotChocolate { }` Gradle DSL flag), off by default — surfaces the
mistake for a project that *wants* Java-style directory discipline (a
larger, multi-team project where the convention actually earns its
keep, or exactly the shape `kubejs-aisle-tool` has now adopted) without
forcing it on a smaller project with no real module structure to
verify. Scoped to directory-mode compiles specifically — a single
arbitrary file (`hc build foo.hc`) has no meaningful "source root" to
compute a relative path against at all, so there's nothing to check
there regardless of the flag.

### ECS with ownership-derived system scheduling

```
component Transform { x: Float, y: Float }
component Velocity { dx: Float, dy: Float }

system Movement {
    fn run(&mut transforms: Transform, &velocities: Velocity) {
        transforms.x += velocities.dx;
        transforms.y += velocities.dy;
    }
}
```

The single most *HC-specific* idea in this whole backlog, and worth
calling out as the flagship entry here rather than burying it among the
others: this is the actual reason Rust game engines built around an ECS
(Bevy chief among them) lean on the borrow checker at all — a system
declaring `&mut Velocity` vs `&Velocity` isn't just safety, it's the
*scheduling signal*. Two systems that only ever read the same component
can run in parallel; a system that writes a component can't run
alongside anything else touching it. The scheduler doesn't need a
separate dependency-declaration mechanism (`reads X, writes Y` written
out by hand) — it can derive the whole thing from the exact `&`/`&mut`
annotations this checker already parses, tracks, and enforces for
completely different reasons (move/alias safety) today. No other JVM
language could do this the same way, because no other JVM language
tracks `&` vs `&mut` on component access to begin with — this is a
legitimate "why HC, not Kotlin" pitch, not a stretch feature bolted on
for novelty.

**Real design work, and it's substantial**: this isn't "add a `system`
keyword," it's a whole runtime (component storage — likely archetype-
or sparse-set-based given the perf expectations of anything calling
itself an ECS; a scheduler that topologically sorts systems by derived
read/write conflicts and actually parallelizes non-conflicting ones,
meaning real multi-threaded codegen, an area this compiler has never
touched; query iteration codegen that's actually competitive with a
hand-written loop, not just "correct"). Precedent worth studying before
designing this: Bevy's own scheduler and its `SystemParam`/
`WorldQuery` trait machinery, and where its ergonomics do vs don't
translate to a language with real static generics instead of Rust's
trait-solver-driven query DSL. Revisit once the language has a real
concurrency story at all (see "Native concurrency primitives" below) —
scheduling *parallel* systems safely presupposes a real threading model
to schedule them onto.

**Bonus, cheap on top of this if it ships**: a small `@profile` marker
(same shape as `@must_use`/`@dev` above, no new mechanism) recorded per
`fn` — but on a `system`, it's more useful than on an arbitrary
function, since the scheduler already knows *which components a system
touches and whether it ran in parallel*. A profiler event built from
that is structured data ("Movement system, wrote Transform, ran
alongside Audio"), not just a raw JVM stack trace. Not worth its own
entry — genuinely small once systems/scheduling exist, not worth
building standalone before they do.

### Units-as-types / dimensional arithmetic

```
let speed: MetersPerSecond = 5.0;
let time: Seconds = 2.0;
let distance: Meters = speed * time;   // Meters, inferred from the multiplication
position += speed + time;              // compile error: MetersPerSecond + Seconds
```

Established PL feature (F#'s units of measure, Rust's `uom`/`dimensioned`
crates) with a real, legitimate payoff for general game/physics code —
catching "added a velocity to a duration" bugs at compile time instead
of at runtime (or never, if the numbers just happen to look plausible).
Genuinely heavier than most of this backlog: needs a real system of
unit-tagged numeric types, arithmetic operators that compute the
*resulting* unit from the operands' units (multiplication/division
combine units, addition/subtraction require matching units), and a
conversion story between compatible units (`Meters` ↔ `Feet`) that
doesn't silently lose precision or get inserted somewhere wrong. Worth
noting honestly: the current dogfood project (Minecraft modding via
Forge) barely needs this — vanilla/Forge APIs are plain `int`/`double`
with no unit discipline of their own to interoperate with — so this is
a bet on HC's broader general-purpose-game-language ambition paying off
in a different, non-Minecraft project before it's worth the design cost.

**The single most concrete motivating case, worth building first if this
ever gets picked up**: time specifically. Every engine API that takes a
bare `Float`/`Long` for "seconds" or "milliseconds" invites exactly the
mistake this feature exists to catch (`velocity * deltaTime` where
`deltaTime` is secretly still in milliseconds is a real, common,
silent-until-it-isn't bug). A `Duration`/`Instant` pair with real
arithmetic (`now - start` yields a `Duration`; `if elapsed > 500ms`
compares two `Duration`s; `deltaTime.seconds()`/`.milliseconds()` for
the rare case something external needs a bare number) is a strict
subset of the general unit system above — worth shipping as its own
first cut even before generalizing to arbitrary units, since it doesn't
need the full "any unit times any unit" combination-inference machinery,
just a handful of fixed, known operations on one specific unit family.

### Numeric range bounds (`Int<0..100>`-style refinement types)

A different, unrelated meaning of "bounds" from "Bounded generics:
`<T: Trait>`" (see the README) — worth stating explicitly since the two
are easy to conflate: that feature bounds a *type parameter* to
implementing a trait; this one would bound a *value* to a numeric range,
checked at compile time where provable and at runtime (a real range
check, not just documentation) where it isn't. `health: Int<0..=100>`
as a field/param type, rejecting an assignment the compiler can prove
falls outside the range at compile time (`let x: Int<0..=100> = 150;`)
and inserting a real bounds check for anything it can't prove (a value
computed from user input, a JVM interop return value). Real payoff for
exactly the kind of game-value bugs `@must_use`/typestate types (both
shipped/backlogged already) are also aimed at — health going negative,
an array index computed from a percentage overflowing, damage rolls
outside their intended range — all currently silent until they cause a
visible bug somewhere downstream of where the actual mistake happened.

Real design work: this is a refinement-type system (Ada's range types,
Rust's `NonZeroU32`-style newtypes taken further), genuinely new
machinery this checker doesn't have any version of — every arithmetic
operation on a ranged value needs to compute the *resulting* range
(`Int<0..=100> + Int<0..=100>` produces `Int<0..=200>`, not `Int<0..=100>`,
and range-narrowing back down needs an explicit clamp/cast), and the
compile-time-provable-vs-needs-a-runtime-check distinction needs a real
decision procedure (even a conservative one — "only literal-vs-literal
arithmetic gets proven statically, everything else gets a runtime
check" is a defensible, much simpler first cut than full range-interval
analysis). Worth revisiting once there's a concrete real program hitting
enough of these bugs to justify it — not a first-pick item.

### `@deterministic` + built-in state replay/rewind

Grouping these as one design problem, not two — replay/rewind (record
every state change, then play/pause/rewind/scrub through them, the kind
of tool that turns "why did this enemy die" into an actual answerable
question instead of a guess) is only trustworthy if the code producing
those state changes is provably deterministic given the same inputs;
without that guarantee, a "replay" is just a suggestion. Real payoff for
serious game/engine work: rollback netcode, deterministic lockstep
multiplayer, and reproducible bug reports all depend on exactly this.

**Why this is hard, honestly**: `@deterministic fn generate_terrain(seed:
Seed) -> Terrain { }` sounds like a simple annotation, but *enforcing*
it means the checker needs real effect-tracking — knowing that
`System::currentTimeMillis()`, unordered `HashMap` iteration, wall-clock
reads, or a call into ANY `extern class` method the compiler can't see
the implementation of (which is most of them, by design — see `extern`'s
whole "trust the declaration" model) might be non-deterministic, and
rejecting or requiring an explicit `@non_deterministic` opt-out for
every path that could reach one. That's a whole-program purity/effect
type system, a materially bigger feature than anything shipped so far.
The replay half is its own large surface on top: recording every
mutation to `@recordable`-marked state efficiently (a naive "diff
everything every tick" approach won't scale to a real game's world
state) is closer to an event-sourcing system than a language feature.
Revisit only once there's a real HC game with an actual multiplayer/
replay need driving the design, not speculatively.

### Hot reload with live struct field migration

```
@hot_reload
struct Enemy { speed: Float, health: Int = 100 }
```

Change `health: Int` to `health: Int = 100` while the game is running,
and already-alive `Enemy` instances get the new field with its default —
no restart. Genuinely valuable iteration-speed feature for game dev
specifically (the edit-compile-run loop is real developer time lost),
and more plausible for HC's own runtime than it would be bolted onto
Forge specifically (Forge's classloading has no reload hook to build on
at all, which is part of why this only makes sense once "general JVM
game language" is the actual target, not Minecraft modding). Still a
real JVM hot-swap/live-migration project underneath: JVM's own
`Instrumentation.redefineClasses` can swap method *bodies* but not add/
remove/retype fields on an already-instantiated object, so this needs
either a generated migration function per changed shape (compare old vs
new `StructInfo.fields`, synthesize a copy-with-defaults step) run
against every live instance, or a level of indirection this language's
current "real JVM fields, zero overhead" struct representation doesn't
have today. Worth designing once there's a real HC-native runtime loop
to hook into (see "hot reload" needing something to reload *into*) —
not a Forge-specific feature.

### Bounded compile-time evaluation (`const fn`)

```
const LEVEL_COUNT: Int = compute_level_count(RAW_LEVEL_DATA);
```

Real, established feature (Zig's `comptime`, Rust's `const fn`) — a
function the compiler can prove is pure (no `extern` calls, no mutation
of anything outside its own locals, no I/O) gets *executed by the
compiler itself* when called in a `const` context, folding the result
into a compile-time constant. Legitimate, bounded version of "run code
at compile time," **not** the "bake a whole navmesh into the build" idea
that inspired it — that's asset-pipeline territory (its own large
project, arguably closer to the asset-existence-check entry above
scaled up to actual content generation, not a language feature) and
stays out of scope here. Real design work: exactly what subset of the
language is legal inside a `const fn` (loops? `struct` construction?
calls to other `const fn`s only, or any sufficiently-pure fn?), and
where the interpreter/evaluator lives — a second execution mode inside
the compiler itself, separate from real codegen, is genuinely new
infrastructure this compiler doesn't have any version of today.

### `@gpu` — GPU compute functions

```
@gpu
fn update_particles(particles: GPU<Particle>, dt: Float) {
    particles.position += particles.velocity * dt;
}
```

Flagging this honestly as the single largest lift of anything in this
entire backlog, by a wide margin — larger than the ECS scheduler, larger
than macros, larger than everything else combined. The JVM has no
native GPU compute pathway at all; this would mean writing an actual
shader/kernel compiler backend from scratch (translating a subset of HC
straight to SPIR-V or GLSL/HLSL, not just calling out to an existing one
the way `extern class` calls out to existing JVM classes), plus a real
host-device memory transfer story for the `GPU<T>` wrapper type, plus
whatever validation catches "this HC code uses a GPU-incompatible
operation" before it ever reaches the backend. This is worth recording
as a real ambition for "general JVM language for games," not worth
touching until there's an established base of real HC games and a
concrete, specific performance need driving it — not speculatively, the
way most of this backlog can reasonably be picked up "whenever."

Real repetition already visible in `kubejs-aisle-tool`'s own `.hc` files —
every `extern class` bridging one of HC's *own* already-compiled files
(`extern class Copytool = "net.oktawia.structruretokubejsaisles.hc.Copytool"
{ static fn use_item(...) -> ...; ... }`) is hand-copied boilerplate
mirroring a signature that already exists elsewhere, and the min/max
`BlockPos` construction pattern (`BlockPos::new(JMath::min(p1.getX(),
p2.getX()), ...)` repeated for X/Y/Z, twice, in both `CopyToolHudOverlay`
and `CopyToolSelectionRender`) is the same handful of lines duplicated
almost verbatim. Both are exactly what a macro system earns its keep on.

Real design work needed before picking this up, not a small addition:
- **What kind of macro.** A textual/token-substitution macro (C-preprocessor-
  style) is the wrong model for a language this deliberate about types and
  ownership — it'd bypass the checker entirely. The right shape is closer
  to a hygienic, AST-level macro (Rust's `macro_rules!`, or a `derive`-style
  annotation that generates real `FnDecl`/`StructDecl` nodes the checker
  still runs over normally) — a much bigger design surface than anything
  built so far, since it means exposing *part* of the compiler's own AST
  as a thing user code can construct.
- **Hygiene.** A macro-introduced identifier must never accidentally
  capture or collide with a call-site name — the classic macro-system
  correctness problem, orthogonal to (and probably harder than) anything
  the move/borrow checker already does.
- **Where it plugs into the pipeline.** Expanded before or interleaved
  with the checker? A macro that generates an `extern class` block still
  needs every name it introduces to go through the same registration/
  resolution passes real hand-written declarations do (see the ordering
  fix that shipped for `extern interface` referencing `extern class`
  types) — a macro system that expands *after* those passes would need
  its own duplicate registration logic, a real correctness risk.

Directory-mode multi-file compilation (above) removes one whole category
of the motivating repetition (the `extern class Copytool`-bridging-HC's-
own-files case) for free, without needing macros at all — worth landing
that first and re-assessing how much duplication is actually left before
sinking design time into this.

### Lifecycle annotations (`@update`/`@fixed_update`/`@render`/`@startup`)

```
@fixed_update
fn physics(delta: Duration) { ... }

@render
fn draw(ctx: RenderContext) { ... }
```

Doesn't need the full ECS/systems entry above to be worth doing — any
HC program with a game loop at all (not just an ECS-shaped one) has this
exact "which of these functions runs on which schedule" problem, and
today that's entirely convention (a raw `if` in a loop calling things by
hand). A marker per fn (same lightweight mechanism as `@must_use`/`@dev`
— checker-tracked, no bytecode annotation needed since nothing outside
this compiler needs to see it) that a runtime loop can discover and call
in the right order/cadence. Real design question: what actually
*provides* the loop calling these — a runtime this language would need
to own (see "hot reload" above needing something similar), or is this
purely descriptive metadata a *host* application (an existing game loop
written by hand, Java or HC) queries and calls into itself? The latter
is far cheaper and doesn't presuppose HC owns the loop — probably the
right first cut.

### Events/signals as a language construct

```
event PlayerDied(player: Entity);
event DamageTaken(target: Entity, amount: Int);

on PlayerDied(player) {
    spawn_death_effect(player);
}
```

Real payoff: avoids the observer/listener boilerplate every engine
reinvents (a registration call, a handle to unregister, a manually
maintained list of subscribers) for what's conceptually just "run this
code when that thing happens." `event` declares a typed payload shape
(essentially a lightweight unit-variant-with-fields, close to an enum
variant already); `on EventName(pattern) { }` is sugar for subscribing a
generated listener. Real design work: dispatch order when multiple `on`
blocks handle the same event (declaration order? explicit priority?),
whether an event can be filtered/cancelled (`event.cancel()`
mid-handler, common in real engines for "block this damage"), and
whether this is a compile-time-wired, zero-runtime-cost dispatch (every
`on` block for a given event type known statically, compiled to a
direct sequential call chain) or a genuinely dynamic subscription
system (handlers registered/unregistered at runtime). The former is
much cheaper and fits this language's existing "resolve everything
statically" bias; only worth the dynamic version if a real use case
needs runtime-variable subscription.

### Coroutines for game-logic sequencing

```
coroutine enemy_attack(enemy: &mut Enemy) {
    await 500ms;
    enemy.attack();
    await animation("attack").complete;
    enemy.can_attack = true;
}
```

Real, common game-code shape ("wait, then act, then wait for something
else, then act again") that's miserable to write as explicit callbacks
or a hand-rolled state machine every time. The important design
constraint, stated up front: `await` here must mean "suspend this
logical task until its condition holds," compiled to a resumable state
machine (a real coroutine transform — the fn's local state becomes
persisted fields on a generated class, each `await` point becomes a
resume label), **not** "block a thread" — spinning up an actual
`java.lang.Thread` per in-flight coroutine would be a real resource cost
completely at odds with "hundreds of enemies each running their own
attack sequence." **Blocked on the same thing `defer`/pipeline syntax
already are** (see both entries below): this language has no
closures/first-class functions yet, and a coroutine body is exactly a
"resumable closure" in disguise — the state-machine transform needs
somewhere to actually put captured locals, which is the same missing
piece those other entries are waiting on. Revisit together with
closures, not before.

### State machine syntax

```
state PlayerState {
    Idle {
        on Move -> Walking;
    }
    Walking {
        on Stop -> Idle;
        on Attack -> Attacking;
    }
    Attacking {
        on Complete -> Idle;
    }
}
```

Game state (menu flow, character states, AI behavior) is close enough
to already-shipped `enum`/`match` that this is more sugar than new
mechanism — a `state` block could plausibly desugar to an enum (one
variant per named state) plus a generated `transition(event)` method
built from the `on X -> Y` table, reusing `match`'s existing
exhaustiveness checking to guarantee every declared event is handled
somewhere. Real design work: whether a state can carry its own data
(`Attacking { target: Entity }`, matching how enum variants already
carry fields) and how that data survives or resets across a transition,
and whether an *invalid* transition (an event with no `on` arm in the
current state) is a compile error, a silent no-op, or a runtime error —
the honest answer probably depends on whether the full event set is
known statically per state, which needs real thought before committing
to a runtime behavior.

### Networking / execution-domain annotations

```
@replicated
struct PlayerState { position: Vec3, health: Int }

@server
fn damage_player(player: Entity, amount: Int) { ... }

@rpc
fn shoot(target: Vec3) { ... }
```

Extends the already-shipped `@serializable` (see README) into genuinely
new territory: not just "generate a codec for this struct's shape" but
"generate the network
call plumbing itself" — `@rpc` on a fn means calling it from a client
generates the send-and-forget packet, while the *real* body only ever
executes server-side (closely related to `ClipboardPacket`'s existing
hand-written encode/decode/handle split in `kubejs-aisle-tool`, just
automated). `@server`/`@client` as execution-domain markers is also the
general form of the already-shipped `@dev` conditional-compilation idea
— the same "compile this out entirely for the wrong target" mechanism,
just with more possible targets than dev/release (worth designing them
as one unified target-selection axis, not two separate ad-hoc systems,
if both get built). Real design work, and it's substantial: `@replicated`
needs an actual sync strategy (full-state broadcast every tick? dirty-
field diffing? who's authoritative on conflict?) — that's a real
networking-architecture decision this backlog entry can describe the
annotation surface for, but shouldn't pretend to have already
answered. `@serializable` (see README) is already the prerequisite
codec-generation infrastructure this needs; the concrete next step is
extending it toward `@rpc` once there's a real multiplayer HC program
motivating the sync-strategy decisions.

### Macros

Real repetition visible in `kubejs-aisle-tool`'s own `.hc` files, even
after directory-mode compilation (see the README's "Multi-file
projects"/Gradle plugin sections) eliminated the biggest category of it
(hand-redeclaring an `extern class` bridging one of HC's *own*
already-compiled files no longer needed at all, once every file in a
project compiles together): the min/max `BlockPos` construction pattern
(`BlockPos::new(JMath::min(p1.getX(), p2.getX()), ...)` repeated for
X/Y/Z, twice, in both `CopyToolHudOverlay.hc` and
`CopyToolSelectionRender.hc`) is still the same handful of lines
duplicated almost verbatim — directory mode fixes cross-*file*
repetition, not repetition *within* a program's own logic. That's
exactly what a macro system earns its keep on.

Real design work needed before picking this up, not a small addition:
- **What kind of macro.** A textual/token-substitution macro (C-preprocessor-
  style) is the wrong model for a language this deliberate about types and
  ownership — it'd bypass the checker entirely. The right shape is closer
  to a hygienic, AST-level macro (Rust's `macro_rules!`, or a `derive`-style
  annotation that generates real `FnDecl`/`StructDecl` nodes the checker
  still runs over normally) — a much bigger design surface than anything
  built so far, since it means exposing *part* of the compiler's own AST
  as a thing user code can construct.
- **Hygiene.** A macro-introduced identifier must never accidentally
  capture or collide with a call-site name — the classic macro-system
  correctness problem, orthogonal to (and probably harder than) anything
  the move/borrow checker already does.
- **Where it plugs into the pipeline.** Expanded before or interleaved
  with the checker? A macro that generates an `extern class` block still
  needs every name it introduces to go through the same registration/
  resolution passes real hand-written declarations do (see the ordering
  fix that shipped for `extern interface` referencing `extern class`
  types) — a macro system that expands *after* those passes would need
  its own duplicate registration logic, a real correctness risk.

Worth reassessing how much duplication is *actually* left once
property-style-access/other backlog entries above ship (`@serializable`
already has) — several of them independently shrink the motivating cases
here (e.g. a
real `min`/`max`-over-a-struct helper only needs bounded generics, which
already work correctly, not a macro) — before sinking design time into
this specifically.

### Mixins (patching an existing compiled class's bytecode)

Bytecode-level mixin support (in the SpongePowered/Mixin sense — injecting
code into an *already-compiled* target class, the actual mechanism most
real Forge/Fabric mods lean on beyond straightforward interop) is a
natural next step, and closer than it used to look: its real
prerequisite is just being able to reference and reason about an existing
compiled class at all, which `extern class`/`extern interface` already
do, plus multi-file compilation for organizing a real mixin-heavy
project, which also already exists. Patching a target class's bytecode
only makes sense once both of those are solid — they are — so this is
genuinely a "when we want it" feature now, not blocked on anything
structural. Real design work still needed: injection point syntax
(`@Inject`-equivalent — at method head/tail/specific instruction?),
how a mixin's `pub`/module visibility interacts with a target class it
doesn't own, and whether this targets HC-compiled classes, real
externally-compiled ones, or both.

### Pipeline syntax: `xs |> filter(f) |> map(g)`

Reads nicely, but this language doesn't have first-class functions/
closures yet (every `impl`/interface method call is resolved to a static
target at compile time, and `Vec<T>`'s `push`/`get`/`set` are the only
"generic over an operation" thing that exists). `filter`/`map` as generic
higher-order functions need that first — `|>` itself is trivial sugar
(`a |> f(b)` desugars to `f(a, b)`) once there's something worth piping
into.

### `defer` / `using` for resource cleanup

```
let file = open("save.dat");
defer file.close();
```

Tempting, but this is a GC'd JVM target — there's no real "this value's
lifetime just ended" moment to hook `defer` to beyond what the existing
`drop`/move-checker machinery (see the README's Destructors section)
already tracks. `defer` would either (a) just be sugar for "declare a
`drop` impl and let scope-exit call it," in which case it's not adding a
capability, only a spelling, or (b) need real closure capture to defer an
arbitrary block rather than a single method call, which circles back to
the "no closures yet" blocker above. Worth revisiting once closures exist;
until then `drop` already covers the actual use case.

### Named arguments

```
createEnemy(health: 100, damage: 20, hostile: true, name: "Goblin");
```

Real readability win for interop signatures with several same-typed
params (exactly the kind Minecraft/Forge APIs have a lot of). Pure parser
+ checker sugar — resolve named args to positional slots by looking up the
callee's declared param names, no codegen change. Not urgent because nothing
currently blocks writing the positional form; low risk, low urgency, good
"slow week" pickup.

### Pattern matching sugar: positional enum patterns

```
match enemy {
    Goblin(hp) -> ...
}
```

`match` already destructures by field name (`Circle { radius }`) — this
is purely a shorter spelling for single/few-field variants, binding by
declared position instead of requiring the `{ field: name }` form. Small,
uncontroversial, not worth doing until it's actually annoying someone —
the existing form isn't broken, just more verbose for the common
one-or-two-field-variant case.

## Considered and declined (kept for the reasoning, not as a TODO)

### `:=` for `let`

Already covered — `let x = ...` already infers the type today, so `:=`
would be a second spelling for something that already exists, not a new
capability. Not worth the syntax surface area/ambiguity (would need to
decide how it interacts with `var`, whether it implies mutability, etc.)
for zero functional gain.

### Collection literal sugar beyond arrays (`{ "key": value }` maps)

No native map type exists — `LinkedHashMap` etc. are reached today purely
through `extern class` (see `hc/Copytool.hc`'s block-ID mapping for a real
example). A literal syntax needs a real target type to construct into
first; revisit once/if a native `Map<K, V>` (like `Vec<T>`/`Registry<T>`)
gets built, not before.

### A hand-rolled full borrow checker (lifetimes/regions, not just moves)

Explicitly discussed and declined early on, worth keeping the reasoning
on record rather than re-litigating it: everything in Rust's actual
borrow checker is built around validating memory *lifetimes* — that a
reference never outlives the data it points to. That's a genuinely
multi-year undertaking on its own. What HC has instead (move-checking +
`&`/`&mut` exclusivity, no lifetime/region tracking at all) is a
deliberately smaller, achievable subset: compile-time discipline about
*aliasing and mutation*, sitting on top of the JVM's GC doing the actual
memory-safety work. "Make types work first, memory management is way
easier to just do GC" — the JVM already solves memory safety; adding a
real borrow checker on top wouldn't fix a bug that exists today, since
nothing GC-backed can actually dangle. Not revisiting this.

### Valhalla-esque JVM value types

HC already captures a good chunk of what Valhalla is chasing, just via a
different mechanism: monomorphized generics with real unboxed primitive
fields (verified with `javap` — a `Box<Int>` has a real `public int
value;`, no boxing), and `arena struct` for genuine off-heap value
semantics. Actually targeting JVM-level value types would mean depending
on preview/unstable classfile features from a JDK feature that isn't
fully shipped in mainline OpenJDK yet — directly against the "plain JDK
17 bytecode, nothing exotic" promise the whole Minecraft-modding use case
depends on. Revisit only if/when Valhalla itself ships stably.

### Native concurrency primitives (threads/async as language features)

Mostly unnecessary — `extern class`/`extern interface` already reach
`java.lang.Thread`, `Runnable`, and (JDK 21+) `Thread.ofVirtual()` with
zero new compiler work; `examples/extern_interface.hc`'s `Runnable`
example basically *is* the threads story already. A native structured-
concurrency/async primitive is its own large design space and nothing
concrete is currently blocked on not having one. Revisit only if a real
program hits something interop genuinely can't reach.

### A custom build tool ("Marshmallow") replacing Gradle, with Maven Central support

Declined, not deferred. A dependency resolver plus a Maven repository
client plus a build task DAG is its own multi-year project — larger in
scope than the compiler itself, and exactly the class of undertaking the
"don't hand-roll a borrow checker" reasoning above applies to just as
much. Gradle already does this job today, including for the real
end-to-end mod build — there's no concrete pain point replacing Gradle
itself would solve. **What the actual underlying want turned out to be**
(a real, reusable `hc` Gradle plugin instead of hand-copying a
`tasks.register('hcCompile', JavaExec) { ... }` block into every
consuming project's `build.gradle`) is built — see `hc-gradle-plugin/`
and the README's "Gradle plugin" section. That's the right-sized version
of this idea: extending Gradle properly, not replacing it.
