# Ideas backlog

Syntax sugar and features that came up, got weighed, and are worth doing —
just not right now. Not a roadmap with dates, a parking lot: each entry
should have enough context that picking it up later doesn't need
re-deriving the reasoning from scratch. Move an entry into the README
(with a real implementation, not just a description) when it actually
gets built; delete an entry if it turns out to be a bad idea on reflection
rather than leaving it here stale.

## High leverage, do these next

### Reference-type casts (`as` for objects, compiling to `CHECKCAST`)

Real, concrete gap found while porting `ClipboardPacket`'s clipboard-
reconstruction logic to `hc/Clipboard.hc`: `java.util.Map`/`List`/
iterator methods all erase their type params to `Object` at the JVM
level (`V put(K, V)` really has erased descriptor
`(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;`, not
`(String,String)void` — the README's Java interop section already
documented this as a known limitation). Declaring the erased signature
correctly (`Object` params/return) type-checks and links fine, but then
there's no way to get a usable `String` back out of the `Object` a
`.get()` hands back — `as` only supports numeric conversions today
(`Int`/`Long`/`Float`/`Double`), there's no reference-type cast at all.
Worked around this time by avoiding `java.util.LinkedHashMap` entirely
in favor of a fixed-capacity-array-plus-count structure (mirroring
`Copytool.hc`'s alphabet table) — fine for a small, bounded mapping, but
not a real substitute for reaching arbitrary JDK collection APIs, which
matters a lot for a language whose whole pitch is deep JVM interop.

Scope: extend `as` to also accept `(objectExpr as SomeType)` where both
sides are reference types (`Ty.Str_`/`Ty.Struct`/`Ty.Enum`/`Ty.Dyn`/
`Ty.JavaExtern`/`Ty.Array`, per `Ty.isObjectRef()` — already exists),
compiling to a plain `CHECKCAST` against the target's descriptor —
exactly as "trust it" as `extern class` already is (a wrong cast
type-checks fine and throws `ClassCastException` at runtime, same
honesty tradeoff already documented for `extern`). This is what would
actually unblock generic JDK collections: `extern class JMap = "java.util.Map"
{ fn get(&self, key: Object) -> Object; }` declared with real `Object`
erasure, then `(result as String)` to get a usable value back out.

### `String?` (nullable native strings)

Real follow-up to the now-shipped "Nullable extern references: `Type?`"
(see the README) — deliberately left out of that first pass. Nullability
there is a flag on `Ty.JavaExtern`; `Ty.Str_` is a separate built-in type
(a singleton `object`, not a data class), so it can't carry the same flag
without either introducing a wrapper (`Ty.Nullable(inner: Ty)`, more
general but touches every site that pattern-matches on `Ty` directly) or
converting `Str_` to a data class (touches every `== Ty.Str_` comparison
across the checker/codegen — high blast radius for a first cut). A real
JDK method returning a possibly-null `String` (`Map.get`, `System
.getProperty`, ...) is common enough that this is worth doing, just not
by expanding the first pass's risk after the fact.

### `CopyToolItem` fully off its Java shell

Now actually unblocked: `extends`/`override` (for the class shape) and
`Type?` (for `Item.useOn(UseOnContext)`'s `context.getPlayer()` null
check, the exact case that motivated nullable references) have both
shipped. Not done automatically in the same pass that built the nullable
feature, on purpose — this would delete the current, working, verified
Java shell (`items/CopyToolItem.java`) in favor of a change that can't be
runtime-verified against real Minecraft/Forge classes in this dev
environment (no game classpath available, only ever compiled against
`extern` declarations). Real plan when picked up: a new `hc/CopyToolItem.hc`
(its own `module net.oktawia.structruretokubejsaisles.items;`, separately
compiled — each `.hc` source is its own independent `hcCompile<Name>`
task, not a merged multi-file program) declaring `struct CopyToolItem
extends Item { }` with `override fn use`/`override fn useOn`, each
extern-referencing the *already-compiled* `Copytool` class's `use_item`/
`use_on` functions (exactly how `Copytool.hc` already extern-references
`Aisletool` from a separate compile) rather than duplicating their
bodies. Should genuinely delete `items/CopyToolItem.java` once done, not
keep both — there'd be nothing left for it to do.

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

### `if`/`match` as expressions

```
let damage = if critical { base * 2 } else { base };
```

Right now `if`/`match` are statements only — every arm is a `{ block }`
executing side effects, and getting a value out means assigning into a
`var` declared before the `if` (see `Enums and match` in the README).
Making them usable in expression position (at least `if`, `match` is a
bigger lift given its exhaustiveness machinery) is a checker change more
than a codegen one — the value just needs to flow out of whichever branch
actually ran instead of being written to a control-flow-shared local by
hand. High value for exactly the kind of small, data-construction-heavy
code this language is aimed at (`let min_x = if pos1.getX() < pos2.getX()
{ pos1.getX() } else { pos2.getX() };` reads a lot better than the current
statement-form workaround, and shows up constantly in real Minecraft-port
code — see `CopyToolItem`'s `min_x`/`min_y`/`min_z`/... block).

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

### Property-style access for `extern class` getters/setters

```
enemy.health          // instead of enemy.getHealth()
enemy.health = 50      // instead of enemy.setHealth(50)
```

Scope this narrowly: only for `extern class` members, only exact
`getX`/`setX` pairs reflected/declared with matching types, not a general
field-access-desugars-to-any-method-call mechanism. The risk is ambiguity
between "this is a real field" and "this is a sugared getter call" — needs
a clear, checker-enforced resolution rule (probably: sugar only applies
when there's no real field of that name, so a real field always wins and
there's never a silent choice between the two). High value for interop-
heavy code — most of `CopyToolItem.hc`'s ceremony is exactly this pattern
(`stack.getTag()`, `pos1.getX()`, `tag.getLong(key)`).

## Good ideas, real design cost — defer

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
