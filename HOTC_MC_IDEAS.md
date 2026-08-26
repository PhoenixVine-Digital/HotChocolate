# hotc-mc: a separated Minecraft/Forge library — ideas

`kubejs-aisle-tool`'s own `McBindings.hotc` is already, unintentionally,
step one of this: one file of shared `extern class` bindings + a couple
of small wrapper structs, consumed by every other file in that one
project. "hotc-mc" is what that becomes once it's factored out into its
own tree, shared across *multiple* mod projects instead of copy-pasted
into each one. Kept separate from `IDEAS.md` (language features) and
`DOC_TOOLING_IDEAS.md` (doc tooling) because this is a library/ecosystem
question, not a language-design one — most of these ideas need zero
compiler changes at all.

## The actual blocker, before any of these matter

HC has no notion of "depend on another already-compiled HC library"
today. A directory-mode compile (`hc build src/`) merges every `.hc`
file under one root into a single flat `Program` and compiles it as one
unit — that's *how* `McBindings.hotc` currently gets shared inside one
project (every file in `src/main/hotc/` sees its declarations, no
import needed, per the flat-global-namespace model). There's no
equivalent for pulling in a *second*, separately-versioned source tree
maintained elsewhere, the way a real dependency works.

Two honest paths, worth being explicit about instead of assuming the
nicer one:

- **Near-term, no compiler work**: `hotc-mc` is a git repo of `.hc`
  files a mod project vendors (submodule, or the Gradle plugin gets a
  `sourceDirs` (plural) option to merge a second tree into the same
  compile — a small, concrete Gradle-plugin change, not a compiler AST
  one). Every consuming project still compiles hotc-mc's source
  *together with* its own every time; there's no separately-published
  artifact, just a shared source tree with a version tag.
- **Real dependency, later**: HC would need something like "compile
  this tree once, emit both the `.class` files and a manifest of
  `extern class`-shaped declarations another compile can import" — a
  genuinely new compiler feature (closer to a package/module system
  than anything in `IDEAS.md` today). Worth wanting eventually; not
  worth blocking the library on.

Everything below assumes the near-term path.

## Ready to build today — zero new compiler features

These are all just more `extern class`/struct/fn declarations following
patterns this project's own port already proved out, factored into a
shared location instead of copy-pasted.

- **The single biggest lever: the *lazy* `extern class` form already
  solves the boilerplate problem.** `extern class PoseStack =
  "com.mojang.blaze3d.vertex.PoseStack";` — no body at all — already
  reflects each member's real signature off `--classpath` the first
  time something actually calls it (`ClasspathReflector`,
  `Checker.resolveExternMethods`), caching the result from then on.
  This isn't future compiler work; it shipped before this session and
  is just underused so far (every extern class this project's own port
  declared explicit method tables for, because the exact signature
  needed double-checking against real crash reports at the time). A
  hotc-mc v1 covering the common Forge/Minecraft surface can be
  hundreds of one-line lazy declarations — `PoseStack`, `RenderBuffers`,
  `VertexConsumer`, `RenderLevelStageEvent`, all of it — with zero
  hand-typed method signatures, not the "declare every method by hand"
  boilerplate the pain point actually describes. Reach for the
  explicit-signature form only where a member's erasure is genuinely
  ambiguous enough to be worth pinning down by hand (a generic method,
  an overload set `pickCandidate`'s arg-count-only resolution would
  otherwise guess wrong on) — not as the default.
- **Organized bindings, not `mc.core::X` imports.** HC has no import
  statement to organize around (flat global namespace, by design) —
  "organize by use, not by Mojang's package hierarchy" is still the
  right call, but it means splitting `hotc-mc` into multiple *files*
  (`entity.hc`, `item.hc`, `render.hc`, ...) for human navigation, not
  multiple importable namespaces. Same convention `McBindings.hotc`
  already loosely follows with its `--- section ---` comment dividers.
  `import mc.client::*;`-shaped syntax keeps showing up unprompted in
  brainstormed examples — worth treating as a real signal that a shared
  library changes the calculus here (see `IDEAS.md`'s new "A minimal
  import/visibility system" entry) rather than dismissing it outright,
  even though it's not how HC works today.
- **Client/server context types, mostly already the pattern.**
  `McBindings.hotc` already declares `Level`/`ClientLevel`/`LocalPlayer`
  as separate `extern class` aliases, not one generic `Level`. Extending
  that split to `ServerLevel`/`ServerPlayer` and writing APIs that take
  the specific one (`fn give_item(player: &ServerPlayer, ...)`) needs no
  new mechanism, just more declarations — genuinely cheap, genuinely
  useful, do this early.
- **Resource IDs as a real type.** `extern class ResourceLocation` +
  a `new(namespace, path) -> Self` constructor already exists
  (`McBindings.hotc`). No `id!("mod:name")` literal macro (HC has no
  macro system, see below) — but `ResourceLocation::new("my_mod",
  "my_machine")` at every call site is exactly as safe, just without
  the shorthand.
- **Text/Component builder.** A fluent wrapper (`text("Hello").
  color(...).bold()`) is the exact same pattern this session's own
  `CreativeModeTabBuilder` binding already used for a real Forge
  builder class — either wrap `Component`'s real builder machinery, or
  a plain HC struct accumulating state with chained methods. No new
  compiler feature.
- **Rendering utilities.** Not speculative — `RenderUtil.hc`/
  `CopyToolSelectionRender.hc` already exist in this exact project doing
  exactly this (wrapping `PoseStack`/`VertexConsumer` calls into plain
  HC fns). `hotc-mc` factoring these out generically (box/line/text
  helpers, a `SelectionRenderer` wrapping a `BlockRegion`) is following
  an already-proven pattern, not inventing one.
- **BlockState/Tags/capabilities helpers.** Plain extern-method wrapping
  the same way everything else in this project's bindings already
  works. "Safe typed access" for `BlockState.getValue(Property<T>)`
  will have the same "explicit `as` cast at the call site" ergonomics
  every other generic Java method in this project already has (erasure
  to `Object`, no way around it) — worth being honest that this won't
  feel magically nicer than the rest of HC's Java interop, just as
  consistent as it.
- **Debug helpers** (`debug.draw_box`, `debug.toast`) — pure library
  code on top of the rendering utilities above plus a scheduled-clear
  (see tick scheduling below). Nothing new.
- **Input handling** (key presses, text input). Not a language feature —
  `KeyMapping`/`InputConstants`/`Options` are ordinary extern bindings,
  same shape as everything else here, plus a thin ergonomic wrapper
  (`Input::is_key_down(Key::W)`). Belongs here, not in `IDEAS.md`.
- **Events, already fully working today** — not actually a gap. Real
  Java annotations (`@"net.minecraftforge.eventbus.api.SubscribeEvent"`
  on a plain top-level `fn`, `@Mod.EventBusSubscriber` on the class)
  already do the *entire* job, demonstrated live in this project's own
  `Structruretokubejsaisles.hc` (`on_common_setup`/`on_add_creative`).
  There's no Forge registration machinery left to hide — `hotc-mc`'s
  job here is just providing the Event `extern class` bindings
  themselves (`PlayerTickEvent`, etc.), not new sugar.
- **Networking, mostly already working too.** `NetworkRegistry`/
  `SimpleChannel.registerMessage` wrapped in lambda literals, with a
  plain struct + hand-written (or `@serializable`-generated, see below)
  `encode`/`decode`, is exactly this project's own `ClipboardPacket`/
  `NetworkHandler` port from this session, already proven working end
  to end (verified via `javap` against the real Forge descriptors).
  What's missing is purely the *ergonomic* wrapper hiding the channel
  setup — see `@packet` under "needs a new compiler feature."
- **BlockRegion, partially.** `BlockRegion::between/volume/contains/
  center` are ordinary struct + method code, buildable today. `for pos
  in region` is the one piece that ISN'T free: `Stmt.For`'s iterable is
  only ever an `Expr.Range` or a real array today — there's no custom
  iterator protocol, so a struct can't make itself loop-able. The
  honest v1: `region.blocks() -> [BlockPos]` (materializes a real array
  up front) and loop over *that* — correct today, just not memory-free
  for a huge region. A real iterator protocol is a language feature,
  not a library one; log it as its own `IDEAS.md` entry if it's worth
  wanting (`for x in custom_iterable { }`, needing a new `Iterable`-
  shaped interface contract the checker recognizes for `for`, not
  something `hotc-mc` can add by itself).

## Needs a specific new compiler feature first

These are genuinely good ideas the user's list correctly identified —
but each one is a compiler-feature request wearing a "library idea"
costume, the same way `@entry`/`@must_use`/`@serializable` each needed
real, individually-designed `Checker`/`CodeGen` support before they
existed. Worth logging honestly as compiler work in `IDEAS.md`, not
silently assumed as part of "just write the library."

- **`@item("copy_tool")`-style registration attributes.** HC has no
  general attribute-macro system, only a small set of individually
  hand-built directives (`@entry`, `@must_use`, `@serializable`, plus
  real `@"binary.Name"(...)` Java annotations). A new `@item(...)`
  directive needs the same bespoke treatment `@entry` got (a new AST
  node, `Checker` validation, `CodeGen` synthesis) — real, bounded work,
  but compiler work, not something `hotc-mc` alone can ship. The
  buildable-today equivalent: `items.register("copy_tool", ||
  Item::new(...) as JObject)` — a bare lambda argument, exactly the
  shape this project's own `Items.hc`/`CTab.hc` already use.
- **Trailing-block DSL syntax** (`stack.nbt { set("foo", 42); }`,
  `items.register("x") { Item::new(...) }`). HC's lambda syntax is
  `|params| expr` — a single expression, no statement-bodied trailing
  block, no implicit-receiver DSL-builder sugar the way Kotlin's
  trailing lambdas work. Any "DSL block" idea needs that language
  feature to exist first; until then, a builder-pattern struct with
  chained method calls (already proven via `CreativeModeTabBuilder`)
  is the real achievable shape for the same ergonomic goal.
- **`@packet("selection")` auto-registering networking.** The
  mechanism underneath (a real struct + `encode`/`decode` + a
  `registerMessage` call) already works end to end today — this is
  purely asking the compiler to synthesize the channel-registration
  boilerplate from a marker, the same shape as `@entry` synthesizing a
  `@Mod`-annotated constructor. Real, scoped, `@entry`-sized compiler
  feature; not a library concern.
- **Typed NBT serialization** (`stack.read_nbt<Selection>("selection")`).
  `@serializable` already generates exactly this shape of code — but the
  problem is worse than "scoped to one target": the string
  `"FriendlyByteBuf"` is a literal name check hardcoded inside the
  *general-purpose compiler's* `Checker.kt`, meaning Minecraft-specific
  knowledge is baked directly into HC itself, not into a library. Adding
  a *second* hardcoded name (`"CompoundTag"`) would double down on the
  same architectural smell instead of fixing it. The real fix:
  parameterize the target (`@serializable(target: "FriendlyByteBuf")` or
  similar — any extern class whose declared methods match the field-type
  -> `writeX`/`readX` shape the checker already looks for), so the
  compiler carries zero Minecraft-specific knowledge and `CompoundTag`
  support falls out for free as "just another target name," not a
  second special case. Real, scoped compiler work either way — the
  parameterized version is barely bigger than hardcoding a second name,
  and is the only version that actually belongs in a general-purpose
  compiler rather than `hotc-mc`.
- **`Ticks`/`Blocks`/`Degrees` as real unit types with literal syntax**
  (`20.ticks`). This is exactly `IDEAS.md`'s existing "Units-as-types /
  dimensional arithmetic" entry (deferred, real design cost) — the
  *value*-type distinction could be faked today with wrapper structs
  (`Ticks { value: Int }`), but the nice postfix-literal syntax needs
  either a new lexer suffix or real operator-overload-on-literal
  support. The tick-scheduling *helpers themselves* (`schedule.after`,
  `schedule.every`) don't need this at all — they work fine on plain
  `Int` tick counts today; the units are a purely cosmetic layer on
  top, buildable independently and later.

## Further out, and worth knowing the real constraints up front

- **Arena-backed render buffers are blocked on a JDK version conflict,
  not effort.** `arena struct` already exists in HC (`Ty.Arena`, real
  Project Panama-backed off-heap memory) — but running a program that
  actually uses one needs JDK 22+, and `hc run` shells out to a
  separate `java` for exactly that reason. Forge 1.20.1 mods run under
  JDK 17. That's a hard version floor, not a tuning problem: zero-GC
  render math via `arena` genuinely can't work inside a Forge-1.20.1
  mod's own render path today, no matter how good hotc-mc's wrapper
  code is. Revisit once/if a mod target moves to a JDK-21+-compatible
  Minecraft version.
- **Hot-reload is real but narrower than "change anything instantly."**
  Standard JVM class redefinition (`Instrumentation.redefineClasses`,
  the only hot-swap mechanism available without a patched JVM like
  DCEVM) can only swap *method bodies* — it can't add/remove fields or
  change a method's signature. "Tweak a render calculation and see it
  live" is genuinely achievable with `hc --watch` + a small in-game
  agent; "add a field to a struct while the game's running" isn't,
  without a fundamentally different (and much heavier) JVM setup. Scope
  the pitch to body-only changes rather than overselling it.
- **Native Mixin support belongs in `IDEAS.md`, and at a different
  scale than everything else here.** See `IDEAS.md`'s own "Mixins"
  entry (now updated with this exact distinction): the pragmatic
  version compiles HC syntax down to real, standard `@Mixin`-annotated
  output the existing SpongePowered Mixin framework already processes,
  not a from-scratch ASM bytecode-patching engine living inside HC's
  own compiler. The latter is roughly "reimplement Mixin," an
  undertaking on a completely different scale from anything else in
  either ideas file.

## Bottom line

The genuinely distinctive, ship-first items are the ones that are
*already basically proven* by this project's own port history rather
than speculative: client/server-typed APIs, the rendering-utility
pattern, events/networking via real Java annotations + lambdas, and —
the biggest single unlock — the *lazy* `extern class` form, which means
a broad hotc-mc v1 is mostly one-line declarations, not hand-typed
method tables. All zero-new-compiler-feature, all demonstrated working
end to end in this exact codebase already. The DSL-sugar and
attribute-macro ideas are real and worth wanting, but they're compiler
roadmap items (`IDEAS.md` material) riding along on a library-naming
conversation,
and conflating the two would make "just ship hotc-mc" quietly depend on
language features that don't exist yet.
