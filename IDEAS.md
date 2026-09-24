# Ideas backlog

Syntax sugar and features that came up, got weighed, and are worth doing —
just not right now. Not a roadmap with dates, a parking lot: each entry
should have enough context that picking it up later doesn't need
re-deriving the reasoning from scratch. Move an entry into ARCHITECTURE.md
(with a real implementation, not just a description) when it actually
gets built; delete an entry if it turns out to be a bad idea on reflection
rather than leaving it here stale.

Documentation-tooling ideas (everything building on top of the `hc doc`
Markdown renderer — both the structured `///` doc-comment feature
itself and the renderer shipped in `selfhost/` 2026-09-03, see ARCHITECTURE.md's
own "Structured `///` doc comments" section) live in their own file,
`DOC_TOOLING_IDEAS.md`, rather than mixed in here — it's a coherent
enough theme to read as a group. Minecraft/Forge-library ideas (a
separated `hotc-mc` binding
library) live in `HOTC_MC_IDEAS.md` — that file also flags a couple of
compiler-feature requests (generalizing `@serializable` to a second
target, an `@packet` registration directive) that surfaced while
sorting library ideas from language ones; worth folding proper entries
for those into this file if they get picked up. A general-purpose
concurrency library (`PhoenixPool`/task graphs, built entirely on the
existing `extern class` FFI, no new compiler feature) lives in
`PHOENIX_FLIGHT_IDEAS.md`, cross-referenced from this file's own "ECS
with ownership-derived system scheduling" entry — that's the one place
a real language feature (compiler-verified parallel-safety) is still
genuinely needed. Windowing/rendering (`stdlib/window.hotc`, the
eventual `graphics.hotc`, and the explicitly-decided OpenGL-now/
Vulkan-before-ship backend plan) lives in `GRAPHICS_IDEAS.md`, same
spun-out-once-it-became-real pattern as the other two.

## High leverage, do these next

### ~~Enum `==`/`!=` compares reference identity, not the variant~~ -- tag comparison, then full structural equality, both now shipped

Confirmed and fixed while self-hosting the parser (`selfhost/`): `make_foo() == FOO` (where `make_foo()` genuinely returns the `FOO` variant) evaluated to `false`, because `==`/`!=` on two `Ty.Enum` operands fell through to the same `IF_ACMPEQ`/`IF_ACMPNE` (reference identity) path every other reference type gets -- correct for `extern class`/`Struct`/`Dyn` values (there's no sensible structural notion of equality to fall back to for those), actively wrong for `enum`, where comparing "which variant is this" is close to the ONLY thing anyone ever means by `==` on one. This isn't a rare case: any parser/interpreter-shaped code constantly compares a just-read value's kind against an expected one (`token.kind == TokType.FN`) -- exactly what surfaced this, immediately, the moment real self-hosted code tried to write it.

**Shipped (tag-only)**: `CodeGen.genBinary` special-cased `Ty.Enum` operands, comparing the real `tag: Int` field each variant's generated class already carries (`GETFIELD ... tag` on both sides, then `IF_ICMPEQ`/`IF_ICMPNE`) instead of the raw object reference. Correct and complete for any all-unit-variant enum (`TokType`, and plenty of real enums like it) -- there's no field data to differ on, so tag equality *is* full equality for these.

**Shipped (full structural equality), 2026-09-16**: `Some { value: 5 } == Some { value: 5 }` used to return `true` regardless of whether `value` actually matched (only the shared tag was ever compared) -- a real, live correctness bug, not just a missing feature, flagged as the single highest-priority item across every `_IDEAS.md` doc's own "deferred" list. `gen_binary`'s enum branch now, once tags match, dispatches on the RUNTIME tag value to find out which variant it actually is, then compares THAT variant's own fields one at a time -- reusing `gen_binary`'s own "==" codegen recursively per field (a synthetic `$enumeq_l`/`$enumeq_r` local pair, the same "wrap an already-computed value in a synthetic `Ident` so existing codegen can be reused" trick `Match`'s own literal-arm dispatch already uses for `$match_subject`) rather than hand-writing a comparison opcode per field type -- gets every type's own real equality rule for free (`Int`/`Bool` compare directly, `String` via `.equals()`, a NESTED enum recurses right back into this same fix). A struct- or array-typed field still falls back to reference identity (a separate, still-open, disclosed gap -- see README's own "Compile-time safety roadmap" section) -- not a regression, just consistent with that gap's own existing scope. Needed one new piece of bookkeeping (`group_variant_names_by_enum_cg`, re-grouping the existing flat `variant_tag_keys` list by enum name so the runtime dispatch chain knows every variant a concrete enum type could be) and surfaced two more real, adjacent gaps along the way, both now fixed too: `let e: Option<Int> = None;` and `let s: Option<String> = Some { value: expensive() };` (a bare variant or a struct-literal variant as a PLAIN `let` initializer, not nested inside another struct's own field) both failed with "unknown struct or enum variant" even with a perfectly good declared type sitting right there, since `check_stmt`'s own `Let` handling never threaded `declared_type` through for either shape -- same real gap family `check_struct_lit`'s per-field loop and `check_field_assign_on_type` were already fixed for, just at a third and fourth call site. Verified with a dedicated test covering the exact regression (`Some{5} != Some{9}`), cross-variant comparison, a multi-field variant, a zero-field variant, nested-enum recursion, and real `String` equality through a non-literal (`"hel" + "lo"`) field value -- see `examples/enum_structural_eq.hotc`. Full example regression suite: zero new failures, same pre-existing set. Self-hosting verified to a true fixed point.

### ~~Bitwise XOR (`^`) and shift (`<<`/`>>`/`>>>`)~~ -- shipped 2026-09-16

Existing `&`/`|` covered AND/OR; XOR and both shift directions were the real, disclosed gap left
after that (flagged when asked directly: "nothing in stdlib needs it today, but any future
bit-packing -- color channels, a spatial hash, a PRNG -- would hit a wall immediately"). Landed
cheaply, same reasoning `&`/`|` used: `^` reuses `IXOR`/`LXOR` (130/131), `<<`/`>>`/`>>>` reuse
`ISHL`/`ISHR`/`IUSHR`/`LSHL`/`LSHR`/`LUSHR` (120/122/124/121/123/125) directly, slotted into real
C/Java precedence (`| < ^ < & < == < relational < shift < + -`).

The one real design wrinkle: `>>`/`>>>` do NOT get their own merged lexer token, unlike `<<`
(`LTLT`, unambiguous) -- a lone `>` still has to close ONE level of a nested generic type
annotation (`Vec<Vec<Int>>`), and merging two/three adjacent `GT`s into a single token at the
LEXER level would make that permanently ambiguous with no way to un-merge it later. Fixed instead
by keeping `GT` a single-char token always, and having `Parser.hotc`'s own new `shift()` (slotted
between `comparison` and `term`) assemble `>>`/`>>>` from two/three CONSECUTIVE `GT` tokens by
bounded lookahead, only from real expression-operand position -- `type_name_ref`'s own separate
grammar path (used for every type annotation) never touches `shift()` at all, so the two can never
collide. Verified directly: `Vec<Vec<Int>>`-shaped nested generic annotations still parse
correctly, `1 << 2 == 4` respects real precedence, and `-1 >> 28` (arithmetic, sign-extending,
`-1`) vs `-1 >>> 28` (logical, zero-filling, `15`) both produce the real, distinct JVM semantics.
See `examples/bitwise_shift_xor.hotc`. Full example regression suite: zero new failures, same 8
known pre-existing ones.

### List comprehensions: `[expensive(x) for x in values if x.isValid()]`

Real design doc: `COMPREHENSIONS_IDEAS.md` (spun out 2026-09-16, same pattern `ECS_IDEAS.md`/
`COLLECTIONS_IDEAS.md` already established). A genuine `Comprehension` expression node, NOT sugar
desugaring to `.filter()`/`.map()` (`Vec<T>` doesn't have those methods, and building them would
allocate a real intermediate `Vec` per stage anyway) -- compiles to one specialized loop, no
intermediate collection, everything inlined the same way `gen_string_interp` already builds a
`StringBuilder` chain via direct bytecode within one expression's own codegen. v1 scope
deliberately narrow (single `for` clause, optional `if`, no multiple clauses/tuples/nesting) per
explicit direction to keep the grammar boring and predictable. Needed (and got) a real prerequisite
first: a genuinely empty `Vec<T>` literal (`COLLECTIONS_IDEAS.md`'s own note, `[]` now works in a
concrete-type struct-literal-field context) -- a comprehension's own result can legitimately be
empty when the filter rejects everything, and there was previously no way to build one at all. See
that doc's own "Open questions" for what's still unresolved (chiefly: does the comprehension source
need to be a raw array, matching `for`'s own existing narrower scope, or does it also accept `Vec
<T>` directly).

### ~~A `Byte` primitive type (or at minimum, a real `byte[]` bridge)~~ -- `Char`/`Byte` shipped, 2026-09-16

Landed as the full, first option below -- real `Byte`/`Char` types (`Ty.TyChar`/`Ty.TyByte`), real
descriptors (`C`/`B`), real array element handling (`CALOAD`/`CASTORE`/`BALOAD`/`BASTORE`,
`T_CHAR`/`T_BYTE` for `NEWARRAY` -- a genuine `byte[]`/`char[]`, not the generic reference-array
`ANEWARRAY` path), and real arithmetic/comparison/cast support (`i2c`/`i2b` narrowing on `as`,
same-type-only arithmetic reusing `Int`'s own opcodes directly since both are JVM int-category
primitives). `Short` deliberately NOT included -- a real, disclosed scope cut (no concrete blocking
API needed it the way `Byte`'s own `ClassWriter.toByteArray()`/raw-I/O motivation did), revisit if
one shows up. See `examples/char_byte_primitives.hotc`; two real, previously-latent bugs found and
fixed along the way (documented there and in the commit that landed this).

**Original gap, kept for the record**: confirmed as a genuine, currently-unworkaroundable gap during a real
ASM-interop spike (see `PHOENIX_FLIGHT_IDEAS.md`-adjacent self-hosting
work): `Ty` has `Int_`/`Long_`/`Float_`/`Double_`/`Bool_` and nothing
else numeric — no `Byte`, no `Short`, no `Char` — so there is currently
no way to declare an `extern class` member whose real signature
involves `byte[]`/`short`/`char` anywhere, param or return. This isn't
hypothetical or rare: `ClassWriter.toByteArray()` (the *only* way to
get finished bytecode out of ASM), `Files.readAllBytes`/most raw-I/O
APIs, and any crypto/hashing/network-buffer API all return or take
`byte[]`. `ClasspathReflector`'s own `javaClassToTyOrNull` already
documents this exact gap in its own comment ("same reasoning ... uses
for byte/short/char") — it's a known, named limitation, not an
oversight nobody noticed.

Two possible scopes, worth deciding between explicitly rather than
defaulting to the bigger one:

- **Full primitive type**: a real `Byte`/`Ty.Byte_` alongside the
  existing five, with its own descriptor (`B`), its own array element
  handling (`BALOAD`/`BASTORE`/`T_BYTE` for `NEWARRAY`, not the generic
  reference-array `ANEWARRAY` path arrays of `JavaExtern`/`Struct` use),
  arithmetic/cast rules matching `Int`'s (`I2B` narrowing on `as`).
  `Short`/`Char` would be the same shape again, each its own descriptor
  and array-instruction pair — worth asking whether all three are
  actually needed or just `Byte` (the one every real blocking API
  above actually needs) for a first pass.
- **Narrower: just enough to bridge `byte[]` opaquely**, without a
  general `Byte` scalar type at all — e.g. a special-cased `[Byte]`
  array type usable only as an extern method param/return (GETSTATIC/
  PUTSTATIC-adjacent bridging, never indexed/constructed from HC source
  directly). Smaller compiler surface, but a real value can't be built
  or inspected from HC code, only passed through — fine for "write
  these bytes to a file" but not for anything that needs to actually
  read/process byte content in HC itself.

**The pragmatic workaround already verified working, if this stays
unbuilt**: a tiny same-project Java/Kotlin shim class exposing a
`byte[]`-free method (`static void writeClass(ClassWriter cw, String
path)`, internally calling `cw.toByteArray()` and `Files.write` on the
Java side, never surfacing a `byte[]` in its own signature) — `extern
class`-bridged from HC like anything else, real ASM `ClassWriter`
passed straight through as an ordinary extern value. Verified end to
end: built a real class file via HC-driven ASM calls (`ClassWriter`/
`MethodVisitor`, real opcodes, no compiler features needed for that
half at all), wrote it via the shim, and *ran* the resulting class with
a real `java` invocation — it printed the expected output. This is a
completely real, available-today path around the gap for exactly the
self-hosted-codegen use case that motivated finding this in the first
place; a real `Byte` type would just make it native instead of
shim-mediated.

### ~~`if let Some(x) = opt { }`~~ -- now shipped

```
if let Some(pos1) = pos1_opt {
    // pos1: BlockPos, bound fresh, exactly like a match arm
} else if let Some(other) = fallback_opt {
    // else if let chains too, same as plain 'else if'
} else {
    // ...
}
```

Shipped exactly as scoped below: sugar over `match`, not a new AST
node -- `Parser.ifLetStmt()` desugars straight to `Stmt.Match` with the
pattern as one arm and an always-synthesized `_` wildcard arm (the
`else` block, or an empty block if omitted). Single pattern per `if
let`, statement position only, no implicit flow-typing -- all exactly
as scoped. One thing that came for free: `else if`/`else if let`
chaining, via a shared `ifOrIfLetStmt()` dispatcher both `ifStmt()`'s
own `else if` and `ifLetStmt()`'s use, so a chain can freely mix plain
`else if cond` and `else if let pat = expr`. The variant-pattern
grammar itself (`Variant`, `Variant::Nested`, `Variant { a, b }`,
`Variant { field: binding }`, `_`) was factored out of `matchStmt`/
`matchExpr` (which had it duplicated) into a shared `variantPattern()`
that `if let` reuses directly, rather than adding a third copy.
Cut real nesting in ported code as intended --
`CopyToolItem::use_item`'s two-level `pos1_opt`/`pos2_opt` match is
now a flat `if let ... else`.

### ~~Compile-time verification of hand-written `extern class`/`extern interface` declarations~~ -- now shipped

```
$ hc build --classpath forge.jar src/
error: extern class 'SimpleChannel': declared 'registerMessage(...)' doesn't match any real
       overload of 'net.minecraftforge.network.simple.SimpleChannel.registerMessage' on the
       configured classpath -- found: (Int, Class, BiConsumer, Function, BiConsumer) -> MessageHandler
```

Shipped as `Checker.hotc`'s `verify_extern_signature` — unconditional
whenever `--classpath` is provided (not a separate `--verify-extern`
flag, and not opt-out-able once a classpath is given), running every
explicit-signature `extern class`'s declared members through the same
classpath-reflection machinery the lazy form already used, comparing
declared vs. real param/return types and erroring on any mismatch. One
documented, deliberate gap: any declaration involving a type the
reflector can't represent at all (byte/short/char, an HC-native
struct/enum param, a bound-generic `Dyn`) is skipped rather than
flagged — silence there means "unable to check," not "verified clean."

The single highest-value item across every idea session this project's
own port history has generated: something like ten real bugs this
session alone were exactly this error class — a hand-written `extern
class`/`extern interface` signature that compiles clean and only fails
at runtime (`NoSuchMethodError`/`IncompatibleClassChangeError`/wrong
return type silently accepted). `ClasspathReflector` already exists and
already does real signature verification, but only for the *lazy*
(`extern class X = "binary.Name";`, no body) and `use { }` forms —
never for the explicit-signature form, which is exactly the one every
wrong-signature bug this session actually hit came from (a hand-typed
method table is where a typo/wrong-return-type/wrong-erasure mistake
actually lives; the lazy/reflected forms can't be wrong the same way,
since nothing was hand-typed to get wrong).

A weaker, docs-only version of this idea (annotating `hc doc`'s
rendered output instead of erroring at build time) is in
`DOC_TOOLING_IDEAS.md`.

### ~~Lambdas / closures~~ — now shipped in `selfhost/` too (2026-09-02)

```
// || body  or  |x, y| body -- targets a single-method 'extern interface',
// inferred from the call argument's own declared type. Captures enclosing
// locals by move (a real field on a synthesized implementer class, set
// from a real constructor call at the use site -- no invokedynamic, this
// compiler generates direct bytecode the way javac itself used to for
// pre-Java-8 anonymous inner classes).
PacketDistributor::PLAYER.with(|| p as JObject);
```

Shipped as `|params| expr` lambda literals in the now-retired Kotlin
compiler (`Expr.Lambda` in the AST, `Checker.checkLambda`,
`CodeGen.genLambdaClass`) -- scoped exactly as suggested below: target
must be a single-abstract-method `extern interface`, inferred only from
a call argument's declared param type, body is one expression (no
statements of its own, same "ternary-shaped" cut as `Expr.If`/
`Expr.Match`'s value forms already use). Bare method-reference syntax
(`Type::method` used directly as a value) did NOT ship alongside the
Kotlin compiler's own original version, but has since been ported into
`selfhost/` too (2026-09-03, see ARCHITECTURE.md's own "Lambda literals" and
"Status" sections) -- desugars entirely to the same lambda machinery,
no second emission path.
**Ported into `selfhost/` on 2026-09-02**, redone independently in
`Checker.hotc`/`Codegen.hotc` (this file's own registries aren't shared
with the checker's, same split every other duplicated feature in this
codebase already has) — see ARCHITECTURE.md's "Lambda literals" section for
the current, verified state. One difference from the Kotlin design as
written above: the target doesn't have to be specifically an `extern
interface`, any single-method interface (local or extern) works, since
codegen already needed to handle both cases for `impl X for Struct`
anyway.

**`Foo.class` literal**: also shipped only in the retired Kotlin
compiler (`Expr.ClassLit` in the AST, `Checker.checkClassLit`,
`CodeGen`'s `LDC <Type>.class` via a real ASM `Type` operand) -- needed
by `SimpleChannel.registerMessage(int, Class, BiConsumer, Function,
BiConsumer)`'s second arg. Same status as lambdas: not present in
`selfhost/`, needs porting.

### ~~~Inclusive ranges: `a..=b`~~~ shipped

```
for z in min_z..=max_z {
    for x in 0..<size_x {
        ...
    }
}
```

Add `..=` (inclusive) as a **new** option alongside the existing `..`
(exclusive) — don't rename `..` to `..<` for symmetry, since that would
be a breaking migration across every `.hotc` file that exists today
(including the whole real mod port) for a purely cosmetic gain. Low risk:
one new lexer token, parser accepts it in the same range-in-`for`-header
spot `..` already occupies, and codegen either desugars `a..=b` to the
existing `a..(b+1)` or just flips the loop-exit comparison
(`IF_ICMPGE`→`IF_ICMPGT`). Directly fixes the actual misreadable spot in
real code: `for z in min_z..max_z + 1` reads worse than `..=max_z` would.


### ~~Explicit, seeded RNG as a real type~~ — shipped 2026-09-17

```
use random;
var rng = random_new(42 as Long);
let dmg = rng.int(5, 10);   // inclusive both ends
let crit = rng.boolean();
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
instead of an assumption. Landed as `stdlib/random.hotc` -- almost
entirely `extern class JRandomHc = "java.util.Random" { ... }`, wrapped
in a plain `Random` struct plus `int`/`float`/`double`/`boolean`
convenience methods, no new compiler machinery at all. One real, disclosed
scope cut confirmed: `choose(items)` over an arbitrary `Vec<T>` isn't
included this pass -- it would need a real generic METHOD on a
non-generic struct (a fresh `T` introduced by the method itself, not the
receiver's own already-resolved type param), a shape this compiler's
generic-instantiation machinery has never been proven to support (every
existing generic method gets its type param from the receiver, never
fresh per call) -- worth its own real try once an actual caller needs it,
not attempted speculatively here. Verified against `examples/
random_seeded.hotc`: two `Random`s seeded identically produce IDENTICAL
sequences (proven by comparing draws directly, not just eyeballing
output), `int(lo, hi)` never leaves its declared inclusive range across
200 draws, two different seeds produce different sequences, and `float()`
stays in `[0, 1)`. Full example regression suite: zero new failures.
Self-hosting verified to a true fixed point (no compiler changes at all
beyond registering `"random"` as a known stdlib topic in `Driver.hotc`).

### ~~Compile-time asset existence checks~~ — shipped 2026-09-17

```
let texture = asset("textures/player.png");
```

```
let texture = asset("textures/plauer.png");
// compile error: asset 'textures/plauer.png' not found (looked in '<assets-dir>/textures/plauer.png')
```

A misspelled resource path is a real, common bug class in game/engine
code (and doubly so in Minecraft modding specifically, where texture/
sound/model paths are just string literals with zero compiler help
today). Scoped exactly as planned: just existence-checking a path string
-- no `Asset<Texture>` typed-resource machinery, no asset-type inference,
no build-time transformation. `asset(...)` needed ZERO checker changes at
all: `Checker.hotc`'s own `check_call` already trusts any unknown callee
(the same fallback `print`/`read_line` already ride), so the whole
feature landed as `Driver.hotc`-only: a `--assets-dir <path>` CLI flag
(off/`""` by default -- no flag means no check, `asset(...)` still just
compiles as a plain string passthrough) and `check_assets`, which walks
every fn/method/system body looking for the call and verifies the path
against a real `java.io.File.exists()`. Real, disclosed narrower scope:
only recognizes the call directly in a `let`/`var` init, a `return`
value, a bare statement, or one level inside an `if`/`while`/`for`/
`try`/`match` body (recursed into) -- not arbitrarily deep inside a
larger expression (`asset("x") + "y"`) -- covers the doc's own real
motivating shape without needing a second, general expression-tree
walker just for this one diagnostic. Runs AFTER `strip_dev_code` (an
`asset(...)` call a release build's `if dev {}` already dropped is
never checked at all, correctly). `Codegen.hotc`'s own intrinsic case
(alongside `print`/`read_line`) is a one-line passthrough: by the time
codegen runs, the path was already verified (or the compile already
failed), so `asset(...)` just codegens its own string-literal argument
directly. Verified against `examples/asset_existence_check.hotc` (a real
existing file, found correctly) and a deliberately misspelled path
(confirmed: a real, clear compile-time `RuntimeException` naming the
missing file and the exact path it looked for). Full example regression
suite: zero new failures. Self-hosting verified to a true fixed point.

### ~~`@dev` — conditionally-compiled debug-only code~~ — shipped 2026-09-17

```
@dev
fn debug_draw_hitboxes() { ... }

fn main() {
    if dev {
        debug_draw_hitboxes();  // compiled out entirely in a release build
    }
}
```

Small, proven (Rust's `#[cfg(debug_assertions)]`), and genuinely useful:
debug-only rendering/logging/assertions that have zero presence (not just
"disabled at runtime" — actually absent from the classfile) in a release
build. Turned out bigger than it first looked: making a stripped `@dev` fn's
call site safe to leave in source needed real PARSER-level conditional
compilation, not just a checker/codegen filter -- `if false { debug_only(
); }` would still need `debug_only` to exist for ordinary dead-code-free
checking/codegen to succeed, defeating the whole point. Landed as a `Driver.
hotc`-only preprocessing pass (`strip_dev_code`/`strip_dev_blocks_list`) that
rewrites the fully-merged `Program` BEFORE `compile_program` ever sees it --
neither the checker nor codegen needed to learn this feature exists at all.
`dev` itself is a real, pre-existing reserved keyword (`Lexer.hotc`'s own
`DEV` token, previously unused anywhere) that `Parser.hotc`'s `primary()`
now resolves to a plain `Ident { name: "dev" }`; the rewrite pass recognizes
the EXACT shape `If { cond: Ident { name: "dev" }, ... }` and either splices
`then_body` in unconditionally (dev build) or drops the whole block (release
build, `--release` CLI flag) -- recursing into every `If`/`While`/`For`/
`Try`/`Match` body so a nested `if dev { ... }` inside a loop still works.
`@dev fn`s are filtered out of `Program.fns` the same pass, before anything
downstream ever registers or emits them -- an ungated call to one in a
release build is a real "unknown function" compile error, exactly the
signal a forgotten guard should produce. Scope cut, disclosed: `@dev` on an
`impl` METHOD isn't covered this pass, only top-level `fn`s. Verified against
`examples/dev_conditional_compilation.hotc` both ways (`run`/`--release run`)
and a direct check that an ungated call genuinely fails to compile in
release mode. Full example regression suite: zero new failures. Self-hosting
verified to a true fixed point.

### Typestate types: resource lifecycle tracked in the type system

**Status, 2026-09-22 (real, disclosed SUBSET shipped -- via ordinary distinct structs, NOT
phantom generics)**: this entry's own original claim ("real, implementable using infrastructure
that already exists -- monomorphized generics... just a phantom type parameter... to gate which
`impl<State>` block's methods are callable") turned out NOT to hold up as written: checked
`Parser.hotc`'s own `impl_decl` directly, and `impl Texture<Loading> { ... }`/`impl Texture<T> {
... }` parse IDENTICALLY today -- the trailing `<...>` after an impl's struct name is parsed and
UNCONDITIONALLY DISCARDED (`skip_optional_type_params`), never distinguished per concrete
instantiation. Building the ORIGINAL phantom-generic design for real would need genuine per-
instantiation impl-block dispatch in `Checker.hotc`'s own method-registration/monomorphization
machinery -- a real, deep change, not a parser-level trick.
`typestate Texture { state Loading { ... } state Ready { ... } impl Loading { fn poll(&self) ->
Ready { ... } } impl Ready { fn draw(&self, ...) { ... } } }` (`Parser.hotc`'s own
`typestate_decl`) ships the SAME real payoff a much simpler way: each `state S { fields }`/`impl
S { ... }` hoists to an ORDINARY, INDEPENDENT top-level struct/impl (no shared `Texture<State>`
family, no phantom parameter at all) -- `Loading` genuinely has no `.draw()` method, so using it
before it's `Ready` is a real "no such method" error, confirmed live (`examples/
typestate_texture.hotc`'s own negative-path check: calling `.width()` on a `TextureLoading`
value fails at codegen with "no such method... on it", not silently). Zero `Checker.hotc`/
`Codegen.hotc` changes for THAT part. `typestate_decl` adds exactly one new real check beyond
what plain top-level structs already give: every `impl S { ... }` inside the block must name an
`S` actually declared as a `state` in the SAME block (confirmed catching a real typo -- `impl
TextureRedy` when only `state TextureReady` was declared -- as a named parse-time error).
Disclosed, real gaps versus the original vision: no move-checking that actually CONSUMES the old
state on a transition call (this compiler's move-checking tracks bare locals, not method
RECEIVERS -- calling `.poll()` twice on the same `Loading` value isn't rejected), and the user
picks each state's own struct name directly rather than writing `Texture<Loading>` (a real
naming-collision responsibility across the whole file, same as any other top-level struct). Full
example regression sweep clean, self-hosting verified to a true fixed point.

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

### Extensible/open registries as a first-class alternative to `enum`

**Update: the library-level version of this already shipped.**
`Registry<T>` (`prelude_source()` in `selfhost/Driver.hotc`, alongside
`Vec`/`Option`/`Result`) is a
real, string-keyed, open, growable registry — `.register(key, value)`,
`.get(key) -> Option<T>`, built on `Vec` — verified end to end holding
two different concrete struct types under `Registry<dyn Trait>` with
dynamic dispatch through the returned `Option`. That's the actual
Minecraft-`DeferredRegister`-shaped need this entry was chasing, and it
needed zero new compiler machinery — same "eat your own dog food, no
special-casing" story `Vec` itself shipped with. What's below is now
narrower than originally scoped: not "does an extensible-registry
concept exist" (it does), but specifically whether `match` deserves
first-class, checker-understood support for an open `Registry<T>`'s
values, given `match`'s whole value proposition (exhaustiveness) can't
apply to an open set — genuinely worth questioning whether that's worth
building at all versus "use `if let` chains for registry values,
that's what they're for."

```
registry BlockKind {
    Stone,
    Dirt,
    // ... this compile's own entries ...
}

// a different module, a different mod, a different compile even --
// adds a new "variant" without ever touching BlockKind's own declaration:
extend registry BlockKind {
    MyModOre,
}
```

Doesn't cleanly fit either existing interface flavor, which is exactly
why it's worth its own entry rather than folding into `enum`: a `sealed
interface` is a *closed* set, every implementer known at compile time,
which is what makes exhaustive `match` sound — the opposite of what's
wanted here. A plain (non-sealed) `interface` is open-implementer, but
has no notion of a fixed, enumerable *set of known values* the way an
`enum`'s variants are a closed, iterable, matchable set. What's being
asked for is a third shape: an open set of named values, extensible
*across separately-compiled mods*, that still wants enum-like ergonomics
(iterate all registered values, match/branch on one, no boxing) without
enum's closed-set compile-time guarantee. This is, not coincidentally,
exactly what Minecraft's own `DeferredRegister`/registry pattern already
is at the Java level — `hotc-mc`'s job is wrapping that real mechanism
(buildable today, see `HOTC_MC_IDEAS.md`), not inventing a language
feature to replace it. The case for a *language* feature specifically:
`match` on an open registry value can never be exhaustive (a `match`
that handles every value known at compile time still needs a `_` catch-
all for anything a mod registers later, at class-load time, that this
compile never saw) — worth deciding explicitly whether that's just "use
`if`/`if let` chains instead of `match` for registry values" (no new
mechanism, a real, available answer today) before designing anything
bigger.

**Decided, 2026-09-17**: no new `match` mechanism for registry values.
`if`/`if let` chains are the real, sufficient answer — this was never
blocked on anything, just left open pending a concrete reason to prefer
a `_`-catch-all `match` over an ordinary chain, and no such reason
materialized (this project has yet to write a real registry-heavy mod
where the chain was reported as painful). Revisit only if that changes
in a real caller's own code, not preemptively — the broader "open
registries as a first-class `enum` alternative" idea this sub-question
lives inside stays open on its own merits, unrelated to this one.

### A minimal import/visibility system (motivated by a real shared library, not by taste)

Every top-level name (struct/fn/interface/enum/`extern class`) sharing
one flat global namespace across an entire directory-mode compile is a
deliberate, working design *within one project* — no import statements
needed, `McBindings.hotc`'s declarations are just visible everywhere
else in the same compile already. It stops being obviously fine once a
real *shared* library (`hotc-mc`, see `HOTC_MC_IDEAS.md`) enters the
picture: a consumer compiling its own mod alongside a large shared
binding library now has every one of that library's names — used or
not — sharing its one global namespace, with real collision risk that
didn't exist when it was just one project's own files (`hotc-mc`
declaring a `Level` extern alias colliding with a mod's own unrelated
`Level` struct, say). This came up unprompted, multiple separate times,
across a batch of hotc-mc brainstorming (`import mc.client::*;`-shaped
syntax showing up in examples nobody was asked to write that way) —
worth treating as a real signal instead of dismissing it as people
reflexively reaching for Rust/Kotlin muscle memory.

**Not proposing full Rust-style module paths or wildcard imports** —
that's a much bigger redesign (nested module trees, path resolution,
re-exports) than the actual problem needs. The narrower version worth
scoping first: keep the flat namespace as the *default* (small/single
projects keep working exactly as today, zero migration cost), but let
a name be declared non-default-visible (something like today's
`pub`/private split, one level up) so a library can mark internal
helper types as not polluting a consumer's namespace at all, without
requiring every consumer to explicitly import every symbol it uses.
Real design questions before this is buildable: does "library" need to
become a real compile-time concept distinct from "just another
directory in the same compile" (ties directly into `HOTC_MC_IDEAS.md`'s
own "no cross-compile dependency mechanism exists yet" blocker — this
and that are likely one design effort, not two), and whether collision
detection alone (a clear compile error naming both declaration sites)
gets 80% of the value for a fraction of the design cost of real
scoping.

**Shipped, 2026-09-23 (the narrower "collision detection" version, not real scoping)**: `priv
struct Foo { ... }` / `priv enum` / `priv interface` marks a top-level TYPE as not visible outside
the file that declares it, in a directory-mode compile -- referencing it from any OTHER file in
the same compile is a real, named compile error (`'Foo' is declared 'priv' in lib.hotc and can't
be referenced from main.hotc`), run BEFORE the checker ever sees a merged program. Deliberately the
narrower of the two options this entry's own "real design questions" paragraph above floats: NOT
real per-scope resolution (no "library" concept, no cross-compile dependency mechanism -- that
whole design question is untouched, still open, still tied to `HOTC_MC_IDEAS.md`'s own blocker),
just name-based reference detection across files, matching the "collision detection alone" framing
almost exactly. Also scoped to TYPES only, not `fn`/`static` -- a real, disclosed narrowing beyond
even that: the actual motivating case is a shared library's internal helper TYPES leaking into a
consumer's namespace, and restricting to type-NAME-shaped positions (struct-literal/static-call/
cast/`is`/arena-constructor targets, every field/param/return type string, `extends`/`impl ... for`
/`extend` targets) keeps the walker's false-positive surface low -- a bare value/call reference
would also need tracking LOCAL scope, since an unrelated local variable/parameter sharing a name
with another file's private TYPE (`let vec = ...;` vs. a `priv struct Vec` a world away) would
otherwise be a real false positive. `Driver.hotc`'s own `check_private_visibility` is the real
enforcement (`Parser.hotc`'s own `PRIV` token/gate is parse-only); it runs on the raw file-path
list, re-parsing every file itself rather than the natural-looking "collect each file's own
`Program` into a `Vec<Program>` first" approach -- found a real, previously-latent `Vec<T>`
monomorphization gap doing exactly that (`Vec<Program>` compiles fine as source, then
`NoClassDefFoundError: Vec$Program` the moment the compiled class actually loads -- `Program` is
by far this AST's largest struct, and this is the same broader class of gap already found this
session for `Vec<Vec<T>>` struct fields, just one level shallower and on a much bigger struct);
re-parsing sidesteps it entirely, at the cost of parsing each file in a directory twice instead of
once (not perf-critical, same tolerance `check_module_dirs` already established). Verified against
`examples/priv_visibility_ok/` (a library file's `priv struct Cache` + public `Point`/
`describe_point`/`make_cache`, a consumer file using the public surface freely without ever naming
`Cache`, including receiving one back from `make_cache()` through type inference alone) and
`examples/priv_visibility_violation/` (the same library, but the consumer directly declares `let
c: Cache = ...;` -- correctly rejected with the exact error above). Full example regression suite:
zero new failures. Self-hosting verified to a true fixed point.

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
ARCHITECTURE.md's "Multi-file projects"), and `kubejs-aisle-tool` itself has
since moved to exactly the nested-directory-mirrors-module layout this
entry describes (`client/CopyToolHudOverlay.hotc` declaring `module
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

**Shipped, 2026-09-17**: `--check-module-dirs`, an opt-in leading CLI flag (off
by default, same "surface it, don't force it" reasoning this entry's own
middle-ground sketch already called for). `Driver.hotc`'s own `check_module_
dirs` parses every real `.hotc` file under the target directory a SECOND
time (redundant with the real merge, but this is a compile-time diagnostic,
not perf-critical) purely to read back its own declared `module a.b.c;`
string, computes the module a DIRECTORY-derived name would suggest
(`expected_module_for_path`, strip the source root, strip the filename,
replace remaining `/` with `.`), and prints a warning — never a hard error
— on any mismatch. Cross-platform by construction: `java.io.File.getPath()`
returns the PLATFORM-NATIVE separator, so `normalize_path_seps_hc` maps
backslash to forward slash before any string comparison, rather than
assuming either convention. Scoped to directory-mode compiles only, exactly
as planned — a single-file compile has no source root to check against.
Verified against `examples/module_dir_check/` (a `client/Right.hotc`
correctly matching, a `client/Wrong.hotc` declaring `module wrong.name;`
correctly flagged, and a root-level `main.hotc` with no module declaration
correctly silent) both with and without the flag. Full example regression
suite: zero new failures. Self-hosting verified to a true fixed point.

### ECS with ownership-derived system scheduling

**Status, 2026-09-09**: spun out into its own file, `ECS_IDEAS.md`, the same
way Phoenix Flight got `PHOENIX_FLIGHT_IDEAS.md` once it became a real,
actively-worked feature — that's now where the live design, phase plan
(verification-only first, real parallel dispatch later), and open questions
live. This entry stays as the original motivating sketch/pitch; don't track
the design twice once `ECS_IDEAS.md` exists.

```
component Transform { x: Float, y: Float }
component Velocity { dx: Float, dy: Float }

system Movement {
    fn run(transforms: &mut Transform, velocities: &Velocity) {
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
concurrency story at all (see "Native concurrency primitives" below,
and `PHOENIX_FLIGHT_IDEAS.md` for the library layer that story would
actually run on) — scheduling *parallel* systems safely presupposes a
real threading model to schedule them onto. `PHOENIX_FLIGHT_IDEAS.md`'s
task-pool/dependency-graph half is buildable independently and first
(plain library code, no compiler changes); the compiler-verified
"these systems can't alias the same `&mut` component" half described
here is the one genuinely new piece of design — don't track it twice
under two different names once both files exist.

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

**Status, 2026-09-22 (real, disclosed SUBSET shipped)**: `unit Name(Base);` (`Base` one of `Int`/
`Long`/`Float`/`Double`) now generates a distinct newtype struct (`struct Name { value: Base }`)
plus real `+`/`-`/`*`/`/`/`==` operator overloads, reusing `Checker.hotc`'s own EXISTING operator-
overload dispatch (a plain struct method named `add`/`sub`/`mul`/`div`/`eq` -- see that file's own
`check_binary` header) -- zero `Checker.hotc`/`Codegen.hotc` changes needed, pure `Parser.hotc`
AST synthesis (`unit_decl`). `Ticks + Degrees` is a genuine, checker-caught type error (confirmed:
`'+' on 'Ticks' expects Ticks, got Degrees`), not just a naming convention. **What this is NOT**:
the full dimensional-analysis vision sketched below (`Meters * Seconds` inferring a NEW derived
unit type on the fly) -- `mul`/`div` here are unit-times-bare-SCALAR only (`ticks(20) * 3`), never
unit-times-unit; there's no conversion story between compatible units (`Meters` <-> `Feet`) at all.
A real, much smaller, real subset: same-family arithmetic (`Ticks + Ticks`, `Ticks * 3`) and
cross-family rejection, not cross-family COMBINATION. The `Duration`-first motivating case below
is still open -- this ships the general MECHANISM (any `unit` declaration gets the same treatment)
rather than that one specific `Duration`/`Instant` pair. Verified with `examples/
units_as_types.hotc`. Full example regression sweep clean, self-hosting verified to a true fixed
point.

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

Real, decades-proven prior art worth designing against directly: Ada's
`subtype Natural is Integer range 0 .. Integer'Last;` is exactly this
feature, shipped since the 1980s — a named, range-constrained integer
subtype, checked at compile time where provable and at runtime
otherwise. Worth reading Ada's own subtype/range-constraint rules
before inventing HC's syntax from scratch here, rather than rediscovering
the same design space.

A different, unrelated meaning of "bounds" from "Bounded generics:
`<T: Trait>`" (see ARCHITECTURE.md) — worth stating explicitly since the two
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

**Status, 2026-09-23 (real, disclosed SUBSET shipped -- a name blocklist, not the whole-program
effect system this entry's own header calls for)**: this entry's own honest framing still holds --
a REAL "provably deterministic" guarantee needs whole-program effect-tracking, a materially bigger
feature than anything else shipped this whole backlog sweep, and that full version is NOT what
landed. What shipped instead, narrower but real: `@deterministic fn simulate_tick(...) { ... }`
(`Driver.hotc`'s own `check_deterministic_fns`, a standalone diagnostic pass mirroring `check_
assets`'s exact shape -- no `Checker.hotc`/`TyChecker` change at all, so none of that struct's own
50-plus-argument constructor needed touching) walks the marked fn's own body, recursively through
every nested block AND every sub-expression (stronger than `check_assets`'s own shallower top-
level-only scan), rejecting a DIRECT call to a small, explicit, named blocklist of known non-
deterministic operations: `random_new` (`stdlib/random.hotc`'s own RNG constructor -- a
`@deterministic` fn must receive its `Random` as a parameter from a caller who controls the seed,
confirmed live: `rng.int(...)` inside the marked fn is fine, `random_new(...)` inside it is a real,
named compile error) and `nanoTime`/`currentTimeMillis` (wall-clock reads). Real, disclosed gap
this entry's own header already anticipated: this is a NAME BLOCKLIST, not effect-tracking --
calling an ordinary, un-annotated helper fn that itself calls `random_new` internally is NOT
caught (that needs tracking which PLAIN fns are transitively non-deterministic too, the real
"whole-program purity/effect type system" this entry calls a materially bigger feature, still not
attempted). `HashMap` iteration order, thread-scheduling-dependent behavior, and arbitrary
`extern class` calls are ALSO not analyzed at all (same "trust the declaration" gap this entry's
own header names).
The replay/rewind half shipped as `@derive(Snapshot)` (extending the ALREADY-shipped `@derive`
mechanism -- see the "Units-as-types"/"`@derive(Eq, Hash)`" entries -- with a third derivable
kind, zero new parser/checker/codegen machinery beyond what `derive_impls` already had):
`snapshot(&self) -> Self` copies every field into a fresh value; `restore(&mut self, snap: &Self)`
writes every field of a previously-taken snapshot back onto `self` in place -- `let saved = sim.
snapshot(); ...simulate...; sim.restore(&saved);` genuinely rewinds. Real, disclosed gap: this is
per-struct, MANUAL snapshotting (the caller decides when to snapshot/restore one specific value),
NOT the "record every mutation to `@recordable`-marked state efficiently, scrub through a whole
recorded timeline" event-sourcing system this entry's own header describes -- that remains the
real, larger follow-up, unattempted. Also a real, SHALLOW copy (a reference-typed field copies the
reference, not a deep clone of whatever it points to), same disclosed shallowness `@derive(Eq,
Hash)`'s own field-by-field dispatch already has for nested struct fields. Verified with `examples/
deterministic_replay.hotc`: a real `@deterministic` violation throws the expected named error; a
full snapshot/simulate/restore round-trip returns exactly the pre-simulation values. Full example
regression sweep clean, self-hosting verified to a true fixed point.

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

**Status, 2026-09-24 (real, disclosed SUBSET shipped)**: an EXPLICIT `const fn` qualifier, matching
Rust's own actual design, not inferred purity — the "any sufficiently-pure fn" question above is
deliberately NOT attempted; only a fn literally written `const fn` is ever evaluated at compile
time, and only from inside another `const fn` or a `const NAME: Type = expr;` initializer.
`Driver.hotc`'s own `eval_const_expr`/`eval_const_stmts` are the interpreter (a genuinely new,
separate execution mode, exactly as this entry's own header anticipated — no `Checker.hotc`/
`Codegen.hotc` change needed at all, since a `const fn` never reaches either: it's parsed into its
own `Program.const_fns` list, never merged into the ordinary `fns`). Real, disclosed LEGAL SUBSET,
answering this entry's own open question directly: literals, arithmetic/comparison/logical
operators (operand types must match exactly — no implicit numeric promotion, same restriction this
language's own real operators already have), `let`/`if`/`return`, and a call to ANOTHER `const fn`
(recursively, depth-guarded). **No loops, no `struct`/array construction, no `match`, no calls to
an ordinary fn, no `extern` reach at all** — each a real, named compile error, not silently
accepted or ignored. A `const`'s own value is substituted as a literal everywhere it's referenced
in ordinary fn/method/`extend` bodies (`Driver.hotc`'s own `fold_consts`, mirroring `Checker.hotc`'s
own `subst_expr`/`subst_stmt` generic-substitution walkers almost exactly, just swapping the
substitution rule) — genuinely zero runtime cost: no `static` field, no `<clinit>` entry, nothing;
a `const` is indistinguishable from having hand-written the literal at every use site by the time
`Checker.hotc`/`Codegen.hotc` ever see the program. A later `const` can reference an earlier one in
its own initializer (folded in declaration order before evaluation). Real, disclosed scope cut
beyond the legal-subset restriction itself: a `const fn` body can only reference its OWN params and
call other `const fn`s — it can NOT reference a top-level `const`'s own value (that would need
threading the const-substitution table into the interpreter's own env, not attempted this pass).
Two real, previously-latent `Vec<T>`/`Option<T>` monomorphization gaps found building this (the
same broader class of gap this whole backlog has hit repeatedly): a `ConstValue` enum (this
feature's own internal value representation) crashes `NoClassDefFoundError`/`ClassNotFoundException`
the moment it's wrapped in EITHER `Vec<ConstValue>` (the evaluation environment) or
`Option<ConstValue>` (a `const fn` body's own return value) and that class is actually loaded, even
though it compiles fine as source either way — fixed by storing env values and return values as
`Vec<Expr>`/`Option<Expr>` instead (already-proven-safe generic arguments elsewhere in this AST,
e.g. `Return.value` is a real `Option<Expr>` already), converting to/from `ConstValue` only at the
point of use, never inside a `Vec`/`Option` wrapper. Verified against `examples/const_fn.hotc`: a
`const fn` calling another `const fn`, a later `const` referencing an earlier one, `Int`
arithmetic, and `String` concatenation, all producing the exact expected values; a negative test
(a `while` loop inside a `const fn`) correctly rejected with the named error instead of silently
accepted or crashing. Full example regression suite: zero new failures. Self-hosting verified to a
true fixed point.

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

**Scoping pass, 2026-09-23 (still NOT started -- this is the concrete plan for when it is, not an
implementation)**: the paragraph above is still the right honest headline (the largest lift in
the backlog, correctly not attempted speculatively), but "translate a subset of HC straight to
SPIR-V or GLSL/HLSL" turns out to have a real, dramatically cheaper on-ramp than a from-scratch
backend implies, once `stdlib/graphics.hotc`'s own EXISTING machinery is accounted for:

- **Target GLSL compute shaders specifically, not SPIR-V, and not a new runtime.**
  `stdlib/graphics.hotc`'s own `Shader` struct ALREADY compiles GLSL source text at RUNTIME
  (`GL20::glCreateShader`/`glShaderSource`/`glCompileShader`/`glLinkProgram`) -- the exact
  mechanism a compute kernel needs, just for a vertex/fragment pair today. `@gpu fn` doesn't need
  a new BACKEND EXECUTABLE FORMAT at all, only a new `Codegen.hotc`-SIBLING pass that walks a
  `@gpu` fn's own `Vec<Stmt>` body and emits a GLSL STRING instead of ASM bytecode for that one
  function -- structurally the SAME "compile this AST to a different target text" shape
  `Codegen.hotc` already is, aimed at a text emitter instead of `ClassWriter2`. The runtime half
  (compile the emitted GLSL, `glDispatchCompute`, read results back) is ORDINARY new stdlib code
  (`stdlib/gpu.hotc`, a sibling to `graphics.hotc`), not a new execution model.
- **Real, concrete new requirement found checking this**: `stdlib/window.hotc`'s own
  `Window::new` currently requests OpenGL 3.3 core (`GLFW_CONTEXT_VERSION_MAJOR/MINOR` hints,
  hardcoded `3`/`3`) -- compute shaders need GL 4.3+ (`GL_ARB_compute_shader`, standardized in
  4.3). A real open question this raises, not yet answered: bump the ONE existing context-version
  hint globally (simpler, but a real compatibility risk for whatever hardware/driver combination
  the 3.3 floor was originally chosen for), or add a SEPARATE opt-in higher-version context path
  used only by programs that actually `use gpu;`? Needs a real answer before any code, not
  guessed at here.
- **A real, narrow v1 language subset, matching this whole backlog's own "ship an honest subset"
  discipline**: only `Int`/`Float`/`Bool` scalars and flat `Vec<Int>`/`Vec<Float>` PARAMETERS
  (each becomes one `layout(std430, binding=N) buffer` SSBO declaration) -- no structs, no
  `String`, no `Vec<T>` METHOD calls (`.push()`/`.get()` don't exist as GLSL concepts; a buffer
  parameter maps to GLSL's own `[]` indexing directly, `buf[gl_GlobalInvocationID.x]`, not
  `buf.get(i)`), no recursion (GLSL forbids it), no calling any OTHER HC fn unless it's ALSO
  `@gpu` (mirrors `@deterministic`'s own already-shipped "must only call other same-attribute
  fns" discipline, reusable almost verbatim as a checker/diagnostic pass), no `String`/exception/
  struct-literal expressions at all. This is dramatically narrower than the brainstormed `GPU
  <Particle>`/struct-of-arrays example at the top of this entry -- that needs a real host-device
  struct-layout marshaling story (SoA transform, alignment/padding rules matching GLSL's own
  `std430` layout) that's its own, separate, later phase, not v1.
- **Dispatch surface**: a plain top-level `fn` call to a `@gpu`-marked fn (`update_particles(pos,
  vel, dt)`) desugars, same parser-level-AST-synthesis strategy this whole backlog already uses
  everywhere else, into: compile-and-cache the emitted GLSL program (once, not per-call), upload
  each `Vec` parameter into an SSBO, `glDispatchCompute(ceil(n / workgroup_size), 1, 1)`, a
  `glMemoryBarrier`, then read the (possibly-mutated) buffers back into ordinary `Vec<T>` values.
  Workgroup size: a fixed, hardcoded constant (`local_size_x = 256`) for v1 -- real tuning is a
  later, hardware-specific concern, not needed to prove the mechanism works at all.
- **Honest relative sizing against everything else THIS backlog sweep actually shipped**: every
  other item (`@derive`, `unit`, `sequence`, `@tunable`, `@requires`/`@ensures`, real `Long`/
  `Char` literals, `typestate`, `@deterministic`/`@derive(Snapshot)`) reused EXISTING Checker.hotc/
  Codegen.hotc machinery, mostly through pure parser-level AST desugaring, and each was verified
  end-to-end within one sitting using nothing but `java -cp ... SelfhostCLI` on this same
  development machine. `@gpu` needs a genuinely NEW code-emission target (GLSL text, not JVM
  bytecode) AND a real windowed OpenGL 4.3+ context to test end-to-end at all (not just compile --
  actually RUNNING a compute dispatch needs a live GPU context, which this remote/headless
  development environment may not even have access to) -- a fundamentally different, much larger
  category of work than anything else in this sweep, confirming the very first paragraph's own
  "single largest lift" framing was correct, not just cautious.

Real repetition already visible in `kubejs-aisle-tool`'s own `.hotc` files —
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

**Status, 2026-09-23 (real, disclosed SUBSET shipped -- the cheap first cut this entry's own
header recommends)**: `@startup fn init_world() { ... }` / `@update fn move_player(dt: Float) {
... }` (also `@fixed_update`/`@render`) mark a plain top-level fn as belonging to a lifecycle
category; `Parser.hotc`'s own tail synthesizes ONE dispatcher per category actually used
(`run_startup()`/`run_update(dt)`/`run_fixed_update(dt)`/`run_render(dt)`), each calling every fn
in that category in DECLARATION order. Pure parser-level AST synthesis (`build_lifecycle_
dispatcher_fn`), same "collect names, generate one dispatcher" strategy `@tunable` already
established -- zero `Checker.hotc`/`Codegen.hotc` changes. Answers this entry's own real design
question ("what actually PROVIDES the loop") with the cheap option it names as the right first
cut: this is purely descriptive metadata plus a callable dispatcher, NOT a runtime HC would need
to own -- a host application (a hand-written loop, today; a future HC-owned game-loop runtime,
later) calls `run_update(dt)`/etc. itself, on whatever cadence it already has. Real, disclosed
scope cut: a uniform signature per category is ENFORCED (`@startup` must take zero params,
`@update`/`@fixed_update`/`@render` exactly one `Float` -- checked once, right when each marked
fn is parsed, with a real, named error otherwise) so the dispatcher never needs per-fn signature
introspection at call-site-generation time. Verified with `examples/lifecycle_annotations.hotc`:
two `@startup` fns run in declaration order, then `run_update`/`run_fixed_update`/`run_render`
each call their own marked fn with `dt` threaded through correctly; a wrong-signature `@update` fn
hits the real, disclosed compile error. Full example regression sweep clean, self-hosting
verified to a true fixed point.

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

**Status, 2026-09-23 (real, disclosed SUBSET shipped -- the compile-time-wired version this
entry's own header recommends)**: `event PlayerDied(name: String, cause: String);` declares an
event's own payload shape (a real param list, parsed exactly like a `fn`'s own); `handle
PlayerDied(name, cause) { ... }` registers a handler (MULTIPLE `handle` blocks for the same event
are real and expected, called in declaration order). Firing is just calling the auto-generated
`emit_PlayerDied(name, cause)` dispatcher directly -- no separate `emit` keyword/statement at
all, since the dispatcher is an ordinary, callable top-level `fn` once synthesized, and ordinary
call-site type-checking already validates its arguments for free. Pure parser-level AST
synthesis (`event_decl`/`on_decl`/`build_event_emit_fn` in `Parser.hotc`) -- zero `Checker.hotc`/
`Codegen.hotc` changes, and genuinely zero runtime subscription machinery, exactly the cheap
option this entry's own header names as the right call over a dynamic system. Real, disclosed
naming deviation from the brainstormed syntax: the handler keyword is `handle`, not the more
obvious `on` -- found the hard way that `on` collides HARD with this compiler's OWN source
(`Checker.hotc`'s own `check_field_access_on_type`/`check_field_assign_on_type` use bare `on`/
`on0` pervasively as a real local/param name; reserving it broke this compiler's own self-
compile, caught immediately by the self-hosting fixed-point check this whole backlog sweep relies
on). A second real, previously-latent gap hit building this: a doubly-nested generic `Vec<Vec
<Param>>` used AS A STRUCT FIELD TYPE (on `Parser` itself) crashes with `NoClassDefFoundError:
Vec$Vec$Param` -- a broader version of the already-known `Vec<Vec<String>>.set()` gap (see
`COLLECTIONS_IDEAS.md`), this time triggered by the FIELD DECLARATION itself, not a method call.
Worked around the same way that earlier gap was: flattened `event`'s own per-event field lists
into three parallel flat Vecs (`event_field_counts`/`event_field_names`/`event_field_types`)
instead of storing a real `Vec<Vec<Param>>`, reconstructing a plain (single-level, unaffected)
`Vec<Param>` on demand via `event_params_for`'s own linear scan. Verified with `examples/
events_signals.hotc`: two handlers for the same event both fire, in order, with the right
payload; a `handle` naming a never-declared event, and one with the wrong binding count, both
hit their own real, disclosed error paths. Full example regression sweep clean, self-hosting
verified to a true fixed point (recovered mid-pass from a bad bootstrap sync caused by the first
of the two bugs above -- restored from the last good git-committed bootstrap rather than
debugging forward against a broken compiler).

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

**Status, 2026-09-22 (real, disclosed SUBSET shipped -- via virtual threads, not a CPS
transform)**: this entry's own original blocker ("no closures yet, and spinning up a REAL
`java.lang.Thread` per in-flight coroutine would be a real resource cost") is resolved
differently than originally envisioned: closures/lambdas exist now (used throughout this
backlog's own later entries), AND JDK 21's virtual threads (`stdlib/phoenix_virtual.hotc`,
already shipped) make "one thread per coroutine" a NON-issue -- a virtual thread genuinely
unmounts from its carrier thread while blocked on `Thread.sleep`, so thousands of them blocked
concurrently cost nothing like a real platform thread would. `sequence { npc.say("hi"); wait
(2.0f); npc.say("bye"); wait_until(|| player_near(npc) as PhoenixObject); }` (`Parser.hotc`'s own
`sequence_stmts`) desugars into spawning the block's own body (as a synthesized top-level helper
fn) onto a fresh `PhoenixVirtualPool`; `wait`/`wait_until` (`stdlib/sequence.hotc`, a new topic,
transitively requiring `phoenix_virtual` and therefore `--target 21`+) do the real suspending via
`Thread.sleep`-based blocking/polling. Genuinely non-blocking of the caller and of every OTHER
sequence -- confirmed with `examples/sequence_coroutines.hotc`, where the caller's own very next
line prints BEFORE the sequence's own first line, and the sequence's three steps still land in
their own correct relative order. Zero `Checker.hotc`/`Codegen.hotc` changes -- pure `Parser.hotc`
AST synthesis, same strategy `parallel for`/`@dev` already established. **What this is NOT**:
the CPS/resumable-state-machine transform originally sketched below (no per-`await`-point resume
labels, no captured-locals-as-persisted-fields) -- this is real, correct suspension, just
implemented with a cheap real thread instead of a compiled state machine. Real, disclosed scope
cuts: `wait_until` POLLS every 50ms (not a real condition-variable wakeup -- fine for game-logic
timing, not for sub-frame precision); the spawned task's own handle is discarded, so a running
`sequence` can't currently be `.join()`ed/cancelled from the statement that started it; `--target
17` callers get a real, clear compile error naming `phoenix_virtual`, not a silent fallback. Full
example regression sweep clean (same canonical failure set, `phoenix_virtual_threads.hc` itself
now ALSO passes when the sweep is run at `--target 21`, confirming that failure was always just a
target-flag artifact, not a real defect). Self-hosting verified to a true fixed point.

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

### Array slicing (`arr[start..end]` / `arr[start..=end]`)

**Status, 2026-09-23 (shipped)**: `arr[1..4]` (exclusive) / `arr[1..=3]` (inclusive) produces a
NEW array, a real copy -- this compiler's arrays are real JVM arrays, so there's no sub-range
"view" concept to give instead. Unlike almost everything else shipped this session, this one is
NOT pure Parser-level desugaring: `a..b` was deliberately kept OUT of the general `Expr` grammar
(see `Ast.hc`'s own `Stmt::For` header -- giving range a general `Expr` variant would cost a match
arm in every other `Expr` site across all four files, for a shape that's legal almost nowhere).
Slicing is the one other place a range-shaped thing is legal, so it got the same treatment as
`IndexFieldGet` before it: its own dedicated, narrow `Expr::Slice { arr, start, end, inclusive }`
node instead of promoting `Range` to something general. Parsed in `Parser.hotc`'s own
`postfix_loop`, peeking for `DOTDOT`/`DOTDOTEQ` right after the first bracketed expression -- no
lexer change, both tokens already existed for `for`-loop ranges. `Checker.hotc` gained a real
`check_slice` (mirrors `check_index`, but returns the array's own type rather than the element
type). `Codegen.hotc` gained a genuinely new codegen path: `java.util.Arrays.copyOfRange`,
dispatched to the correct typed overload per real primitive array element this compiler ever
emits (`int[]`/`long[]`/`float[]`/`double[]`/`byte[]`/`char[]`; `Bool` shares `int[]`'s the same as
everywhere else in this file) or the generic reference-type overload for a struct/String element
array (erased to `Object[]` at the bytecode level, needing a `CHECKCAST` back to the real array
descriptor for the verifier -- same mechanism `gen_cast`'s own struct/enum branch already uses).
Verified in `examples/array_slicing.hotc` against an `Int` array, a `Char` array, and a struct
(`Point`) array -- the struct case specifically exercises the `CHECKCAST` path, and all three
hand-verified correct. `arr[a..b] = x` is not a valid assignment target -- `Parser.hotc`'s own
`assignment` already falls through to "Invalid assignment target" for any `Expr` shape it doesn't
explicitly list, so this needed no new code to reject, only NOT adding a `Slice` arm there. Full
example regression sweep: zero new failures (the flagged cases were all pre-existing/unrelated --
two interactive stdin-reading examples that only fail non-interactively, a scratch file with no
`main`, the two known JDK-21-target virtual-thread examples that need a direct JDK 21 `java`
invocation rather than `./gradlew run`'s own default toolchain, and one slow-but-passing example
that only tripped a conservative sweep timeout). Self-hosting verified to a true fixed point.

### `@tunable` -- live-editable debug constants

**Status, 2026-09-22 (real, disclosed SUBSET shipped)**: `@tunable static SPEED_MULT: Float =
1.5f;` marks a top-level `static` as reachable by NAME STRING from a debug console/UI, without
recompiling. The real insight this rode on: a plain HC `static` is ALREADY a genuinely mutable
JVM static field (`NAME = value;` falls back to a real `PUTSTATIC` when `NAME` isn't a local --
`Checker.hotc`'s own `check_assign` header), so there was no new storage mechanism to build at
all -- just a way for code that only has the tunable's name AS A STRING to reach it.
`Parser.hotc`'s own `parse_program` tail synthesizes exactly three functions, ONCE per file, from
every `@tunable` collected across the whole file (`build_tunable_get_fn`/`build_tunable_set_fn`/
`build_tunable_names_fn`): `tunable_get(name: String) -> Float`/`tunable_set(name: String, value:
Float)` (each a flat if-chain comparing `name` against every tunable's own name, dispatching to
an ordinary `Ident`/`Assign` on the real static) and `tunable_names() -> Vec<String>` (so a debug
console can enumerate what's valid without hardcoding). Zero `Checker.hotc`/`Codegen.hotc`
changes -- pure AST synthesis reusing already-proven constructs, same strategy `@derive`/`unit`/
`sequence` all already established. Verified with `examples/tunable_constants.hotc`: writing
through `tunable_set` genuinely changes the real static (confirmed by reading it back BOTH
through `tunable_get` and by naming it directly), and an unknown name/wrong-typed static both hit
their own disclosed, real error paths. Full example regression sweep clean, self-hosting verified
to a true fixed point.

Real, disclosed scope cut: `Float`-only (the overwhelmingly common "balance tuning" case --
multipliers, speeds, cooldowns), not a general any-type registry, which would need real type-
erasure/boxing machinery this compiler doesn't have a clean story for yet (see `Codegen.hotc`'s
own `PhoenixTask::join()` header on that exact `Object`-erasure cost elsewhere). An unknown name
passed to `tunable_get` returns `0.0f` silently rather than throwing -- `tunable_names()` is the
intended way to know what's actually valid. No actual in-game debug-console UI is part of this
pass either -- that's a real, separate follow-up (a window/graphics-topic feature) that would
consume `tunable_get`/`tunable_set`/`tunable_names` as its own backend.

### `@requires`/`@ensures` -- design-by-contract, dev-only

**Status, 2026-09-22 (real, disclosed SUBSET shipped)**: `@requires(hp >= 0) @ensures(result >=
0) fn damage(hp: Int, amount: Int) -> Int { ... }` compiles to real assertions in DEV builds,
gone entirely in `--release`. The real trick this piggybacks on, exactly as originally
brainstormed: every generated check is wrapped in `if dev { ... }`, the EXACT shape `Driver.hotc`'s
own, already-shipped `strip_dev_code`/`strip_dev_blocks_list` pass already recognizes and strips
-- `--release` drops the whole block (condition expression included) before the checker ever
sees it, the default keeps it as ordinary always-executed code. Zero NEW strip machinery, zero
`Checker.hotc`/`Codegen.hotc` changes -- pure reuse of `@dev`'s own existing infrastructure via
`Parser.hotc`'s own `apply_contract`. `@requires(cond)` prepends one check at the top of the fn
body; `@ensures(cond)` rewrites EVERY explicit `return expr;` throughout the whole body (recursing
through `if`/`while`/`for`/`try`/`match`, mirroring `strip_dev_blocks_list`'s own recursion shape)
into `let result = expr; if dev { if !(cond) { throw ContractError::new(...); } } return
result;` -- binding the return value to a real local literally named `result` is what lets `cond`
reference it (`@ensures(result >= 0)`) via ordinary variable lookup, no AST substitution needed.
`ContractError` (declared in no stdlib topic) is synthesized directly into `Program.externs` only
when actually used. Verified BOTH directions live, not just the happy path: a real `@requires`
violation throws `"requires failed in 'damage'"` and a real `@ensures` violation throws
`"ensures failed in 'broken_clamp'"` in a dev build; the SAME two violating programs compiled
with `--release` silently return the raw, un-checked, wrong value instead (`-8`, `-90`) --
confirming the strip is real, not just present-but-inert. Full example regression sweep clean,
self-hosting verified to a true fixed point.

Real, disclosed scope cuts: only one `@requires` and one `@ensures` per `fn` (multiple conditions
already expressible as `cond1 && cond2`); a bare `return;` (no value) is completely unchecked by
`@ensures` (nothing to bind `result` to); a `Unit`-returning fn that falls off the end with no
explicit `return` is likewise unchecked (no `Return` node to intercept). No `@invariant` (a
struct-level, checked-on-every-public-method-boundary condition) -- a real, larger follow-up if a
concrete need for it ever comes up.

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

**Note: this entire entry assumes `@serializable` and `@dev` as a
foundation, and neither exists in `selfhost/` today** — `@` itself
parses now (both real `@"binary.Name"(...)` annotations and the
`@must_use` bare directive shipped 2026-09-03, see ARCHITECTURE.md), but
`selfhost/`'s own directive dispatch only recognizes `must_use` --
`@serializable`/`@dev` themselves still aren't real directives there,
still shipped only in the now-retired Kotlin compiler. This entry's
design reasoning is preserved below in case it gets revisited once
`@serializable`/`@dev` land in `selfhost/`, but treat it as blocked on
that first.

Extends `@serializable` (shipped in the retired Kotlin compiler, see
above) into genuinely new territory: not just "generate a codec for
this struct's shape" but "generate the network
call plumbing itself" — `@rpc` on a fn means calling it from a client
generates the send-and-forget packet, while the *real* body only ever
executes server-side (closely related to `ClipboardPacket`'s existing
hand-written encode/decode/handle split in `kubejs-aisle-tool`, just
automated). `@server`/`@client` as execution-domain markers is also the
general form of the `@dev` conditional-compilation idea (also
Kotlin-only) — the same "compile this out entirely for the wrong
target" mechanism, just with more possible targets than dev/release
(worth designing them as one unified target-selection axis, not two
separate ad-hoc systems, if both get built). Real design work, and it's
substantial: `@replicated` needs an actual sync strategy (full-state
broadcast every tick? dirty-field diffing? who's authoritative on
conflict?) — that's a real networking-architecture decision this
backlog entry can describe the annotation surface for, but shouldn't
pretend to have already answered.

### Macros

Real repetition visible in `kubejs-aisle-tool`'s own `.hotc` files, even
after directory-mode compilation (see the ARCHITECTURE.md's "Multi-file
projects"/Gradle plugin sections) eliminated the biggest category of it
(hand-redeclaring an `extern class` bridging one of HC's *own*
already-compiled files no longer needed at all, once every file in a
project compiles together): the min/max `BlockPos` construction pattern
(`BlockPos::new(JMath::min(p1.getX(), p2.getX()), ...)` repeated for
X/Y/Z, twice, in both `CopyToolHudOverlay.hotc` and
`CopyToolSelectionRender.hotc`) is still the same handful of lines
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
property-style-access/other backlog entries above ship — several of
them independently shrink the motivating cases
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

**The one decision worth making explicitly before starting, though**:
this means "HC's compiler becomes its own ASM-based bytecode patcher"
(target resolution, injection-point/shift semantics, `@Shadow`-style
field aliasing against a class HC never compiled) — which is, honestly,
reimplementing SpongePowered Mixin's own runtime from scratch, a much
larger and more novel undertaking than anything else in this file. The
pragmatic version is much smaller: have HC syntax compile down to real,
standard `@Mixin`/`@Inject`/`@Shadow`-annotated *Java-shaped* class
output that the existing, already-battle-tested Mixin framework
processes at Forge's own launch time — same ergonomic win (write the
injection in HC, get real borrow-checked params), zero new bytecode-
patching engine, reusing decade-old, widely-deployed machinery instead
of re-deriving it. Only worth building the from-scratch ASM-patcher
version if there's a concrete reason the real Mixin framework's own
output can't be targeted — "we want it to feel native" isn't reason
enough on its own, given the difference in effort.

Also worth being honest about the safety story if `@shadow` fields ever
get wired into the borrow checker: HC can enforce move/borrow
discipline on *its own* code's access to a shadowed field, but has zero
visibility into the real target class's own internal aliasing (it's
opaque, already-compiled bytecode) — so "the borrow checker protects
this shadowed field" is a real but *narrower* guarantee than it sounds,
not full safety against the vanilla class's own concurrent/aliased
access to the same field.

### Pipeline syntax: `xs |> filter(f) |> map(g)`

Written when this language had no closures yet — that part's since shipped (2026-09-02, see this
doc's own entry above), so the ORIGINAL blocker no longer applies; `filter`/`map` as real generic
higher-order `Vec<T>` methods are buildable now, just not built (`Vec<T>` still only has `push`/
`get`/`set`/`length`/`pop`/`clear`). Still not pursuing `|>` itself, though, for a different real
reason found designing list comprehensions (`COMPREHENSIONS_IDEAS.md`): chained `filter`/`map`
calls allocate a real intermediate `Vec` per stage, where a comprehension compiles to ONE
specialized loop with no intermediate allocation at all — see that doc's own header for the full
reasoning. If `filter`/`map` ever get built anyway (a real caller wanting the point-free style),
`|>` stays trivial sugar on top (`a |> f(b)` desugars to `f(a, b)`), unchanged from before.

### `defer` / `using` for resource cleanup

```
let file = open("save.dat");
defer file.close();
```

Tempting, but this is a GC'd JVM target — there's no real "this value's
lifetime just ended" moment to hook `defer` to beyond what the existing
`drop`/move-checker machinery (see ARCHITECTURE.md's Destructors section)
already tracks. `defer` would either (a) just be sugar for "declare a
`drop` impl and let scope-exit call it," in which case it's not adding a
capability, only a spelling, or (b) need real closure capture to defer an
arbitrary block rather than a single method call, which circles back to
the "no closures yet" blocker above. Worth revisiting once closures exist;
until then `drop` already covers the actual use case.

### ~~Pattern matching sugar: positional enum patterns~~ — shipped 2026-09-17

```
match enemy {
    Goblin(hp) => { ... }
    Dragon(hp, fire) => { ... }
}
```

`match` already destructures by field name (`Circle { radius }`) — this
is purely a shorter spelling for single/few-field variants, binding by
declared position instead of requiring the `{ field: name }` form.
`Ast.hc`'s own `MatchArm` gained one new `is_positional: Bool` field
(threaded through all 13 construction sites across the compiler);
`field_names` stays EMPTY for a positional arm (the parser has no way to
know the variant's own real field names, only the checker/codegen do) --
`Checker.hotc`'s own `check_match` and `Codegen.hotc`'s own match
codegen both resolve each binding's real field name from the variant's
own already-known, declaration-ordered field table (`variant_field_info`
/`variant_field_names`) at the bind's own POSITION instead of reading
`field_names` directly, with a real, clear error if the pattern binds
more values than the variant actually has. Verified against `examples/
positional_match.hotc`: a one-field variant, a two-field variant
(binding both `hp` and `fire` correctly), and a bare zero-field variant
matched alongside the positional ones in the SAME `match`. Full example
regression suite: zero new failures. Self-hosting verified to a true
fixed point.

## Considered and declined (kept for the reasoning, not as a TODO)

### `:=` for `let`

Already covered — `let x = ...` already infers the type today, so `:=`
would be a second spelling for something that already exists, not a new
capability. Not worth the syntax surface area/ambiguity (would need to
decide how it interacts with `var`, whether it implies mutability, etc.)
for zero functional gain.

### Collection literal sugar beyond arrays (`{ "key": value }` maps)

No native map type exists — `LinkedHashMap` etc. are reached today purely
through `extern class` (see `hc/Copytool.hotc`'s block-ID mapping for a real
example). A literal syntax needs a real target type to construct into
first; revisit once/if a native `Map<K, V>` (like `Vec<T>`/`Registry<T>`)
gets built, not before. **A real, hand-rolled `HashMap`/`IntHashMap` is now
being designed** — see `COLLECTIONS_IDEAS.md` (spun out 2026-09-15, same
pattern `ECS_IDEAS.md` and `PHOENIX_FLIGHT_IDEAS.md` already established):
`java.util.HashMap` can't just be `extern class`-bound the way `Registry<T>`
avoided doing already, since HC's own generics are monomorphized per
instantiation (real, separate class files, confirmed in the published
jar), not type-erased like `javac` compiles `HashMap<K, V>` — see that doc's
own header for the full reasoning.

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
zero new compiler work; `examples/extern_interface.hotc`'s `Runnable`
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
and the ARCHITECTURE.md's "Gradle plugin" section. That's the right-sized version
of this idea: extending Gradle properly, not replacing it.