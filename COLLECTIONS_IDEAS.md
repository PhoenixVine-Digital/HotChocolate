# Real hashed collections — design doc

**Status, 2026-09-16 (Marshmallow integration)**: `HashMap<V>` got its first real consumer outside
this repo's own `examples/` -- Marshmallow's `TextureCache` (`stdlib/graphics.hotc`) switched from
its original two-parallel-`Vec`-with-linear-scan design to a real `Option<HashMap<Texture>>`,
lazily built on the first `get_or_load` call (since `HashMap<V>`'s own constructor needs a real
first key/value up front, and the cache has neither at construction time). This surfaced a genuine,
previously-latent gap, found the hard way, not by inspection: a bare fieldless-generic-variant VALUE
(`cache: None`, or `c.cache = Some { value: ... };`) nested inside ANOTHER struct's own field
position had no way to resolve its own concrete type. `check_expr`'s `StructLit`/`Ident` dispatch
only ever threads `self.cur_ret_ty` as the inference hint (correct for a `return None;`, meaningless
for a field nested inside a DIFFERENT struct literal or a `recv.field = ...` assignment) -- so
`TextureCache { cache: None }` and `self.cache = Some { value: hash_map_new(...) };` both failed
with "unknown struct or enum variant" even though the field's own DECLARED type
(`Option$HashMap$Texture`) said exactly what was needed. Fixed in three places, each threading the
real declared field type as the hint instead: `Checker.hotc`'s `check_struct_lit` (per-field loop)
and `check_field_assign_on_type`, and `Codegen.hotc`'s own struct-construction field loop and
`finish_field_assign` (via a temporary `self.ret_ty` override, saved/restored around just that one
field's codegen, mirroring how `Codegen.hotc` already leans on `self.ret_ty` as its own equivalent
hint elsewhere). Verified against a minimal, isolated reproduction of the exact shape (a
`ResourceCache`-shaped struct wrapping `Option<HashMap<Resource>>`, covering literal construction,
field re-assignment, and a `match` read) before trusting it in the real GL-backed `TextureCache`.
Full existing example regression suite: zero new failures, same 8 known pre-existing ones (plus 4
grep false-positives from this sweep's own stdin-dependent/intentionally-caught-exception examples,
confirmed not real regressions). Self-hosting verified to a true fixed point.

**Status, 2026-09-16 (later still)**: a real `[]` empty array literal landed -- narrow, but a real
prerequisite found discussing list comprehensions (`[f(x) for x in xs if pred(x)]`, a natural
extension of this whole "real collections" push): a comprehension's own result can legitimately be
EMPTY (the filter rejects everything), and this language had NO way to construct a genuinely empty
`Vec<T>` at all before this -- every array literal/repeat needed a real element to seed the backing
array/infer its type. Scoped narrowly on purpose: `[]` only resolves when the surrounding context
already names a CONCRETE array type directly (currently: a struct literal field whose declared type
is an array, e.g. `Vec<Int> { data: [], len: 0 }`) -- both `Checker.hotc`'s own `check_struct_lit`
and `Codegen.hotc`'s own struct-construction field loop special-case it there, reading the element
type from the field's own already-known declared type instead of (impossible, for zero elements)
inferring it from the literal itself. A fully GENERIC `fn f<T>() -> Vec<T>` still can't call some
`vec_empty<T>()` and get a real empty one -- that's the SAME "no return-type-only inference for a
zero-argument generic call" wall this doc's own earlier note already hit building `HashMap`/
`HashMap2` (`static fn new()` inside `impl Struct<V>` silently failing to infer `V`) -- unresolved,
and a separate, bigger ask than this pass attempted. Verified with a real program (`Vec<Int>`/
`Vec<String>`, each starting length 0, then pushed into normally -- `Vec<T>::push`'s own pre-
existing "grow from a zero-length backing array" handling, unrelated to this fix, already worked)
-- see `examples/empty_vec_literal.hotc`.

**Status, 2026-09-16 (even later)**: the reference-identity limitation the entry right below
disclosed is CLOSED. A real `Eq` interface (`fn equals(&self, other: &Self) -> Bool;`) landed
alongside `Hashable`, and `Self` in an interface's own declaration needed **zero compiler
changes** -- tried directly (a standalone `interface Eq { fn equals(&self, other: &Self) -> Bool;
}` + `impl Eq for ItemStack { fn equals(&self, other: &ItemStack) -> Bool { ... } }`, both as a
param type and, separately, as a return type) and it just worked, first try. The reason: `Self` in
an interface DECLARATION is never independently resolved to anything at all -- this language's
interface/impl checking is loose/structural (an impl's own method is checked as an ordinary struct
method against its OWN literally-spelled concrete signature, never cross-checked against the
interface's own declared param types), so `Self` only ever needs to PARSE as a type name (it
already does -- `Self` is just an ordinary `IDENT`, no reserved-keyword collision) and never needs
runtime resolution, because every real IMPL spells out its own concrete type directly and never
writes `Self` itself. `HashMap2<K: Hashable + Eq, V>`'s own `register`/`get`/`remove` now call
`stored_key.equals(&key)` instead of `==` -- verified with the SAME `ItemStack` example extended
with a `sword_lookalike` (a different instance, same fields): it now correctly `FOUND`s the
existing entry instead of missing, and re-registering through the lookalike correctly overwrites
the same logical entry (`length()` staying 2, not growing to 3) rather than creating a duplicate.
One real bug caught and fixed along the way: the FIRST pass only updated `register`/`remove`'s own
key-comparison sites, missing `get`'s own (a real, easy-to-miss third near-identical call site) --
the test's own `sword_lookalike` lookup still MISSed until that one was found and fixed too, a good
reminder that near-duplicated code (three copies of the same bucket-scan shape) needs the SAME
audit at EVERY copy, not just the ones that happen to get exercised first.

**Status, 2026-09-16 (later)**: `HashMap2<K: Hashable, V>` landed -- real, arbitrary-STRUCT-keyed
hashing (`HashMap2<ItemStack, Int>`, say), closing the gap this doc's own header originally
scoped OUT ("blocked on primitives being unable to implement interfaces... not needed by any
concrete use case this project actually has yet" -- it became a real, stated need: "I need to be
able to hashmap say a random minecraft item stack"). Didn't need the deferred `Hashable`-bound
route this doc originally imagined (primitives implementing interfaces) at all -- the real blocker
turned out to be that THIS COMPILER'S STRUCTS AND FUNCTIONS ONLY EVER SUPPORTED ONE GENERIC TYPE
PARAMETER, full stop (`Result<T, E>` was the only TWO-param generic anywhere, and only as an
`enum`). Landed by porting that exact, already-proven enum mechanism to `struct`/`fn` (`Ast.hc`'s
own `StructDecl.type_param2`/`FnDecl.type_param2`, mirrored end-to-end through
`Parser.hotc`/`Checker.hotc`/`Codegen.hotc`) -- real, substantial compiler work, verified with a
hand-computed program (`Pair<K: Hashable, V>`, a bounded two-param struct+fn, calling an interface
method on the bounded `K`) BEFORE touching `collections.hotc` at all, then again with a real
`HashMap2<ItemStack, Int>` end to end (insert/overwrite/miss/remove/resize all matching hand-
computed expected output exactly).

**Two real bugs found along the way, both worth keeping as patterns** (same "these were invisible
until something actually exercised them" lesson `ECS_IDEAS.md`'s own catalogue already has several
of):
1. **`substitute_field_type`/`substitute_field_type_cg` only ever substituted a mono name's LAST
   `"$"`-delimited segment**, not each one independently -- invisible for every single-param
   generic this compiler had ever had (`"Vec$T"` has exactly one segment, so "last" and "only"
   were the same thing), but real the moment a flat two-param mono name existed at all
   (`"Pair$K$V"`): substituting `K` first, the old recursive shape treated the REMAINING `"K$V"`
   as itself a nested mono argument ("K applied to V"), so `K` was silently never substituted, and
   a second sequential pass substituting `V` produced `"Pair$K$String"` -- `K` never replaced.
   Fixed by splitting the whole mono name flat on every `"$"` and checking each segment
   independently, rather than recursing into "the rest" as if it were always exactly one more
   nested type application.
2. **Codegen's own generic-CALL-SITE inference (`gen_expr`'s `Call` arm) only ever built a
   single-segment mono target name** (`callee + "$" + inferred_type`), completely independent of
   (and un-synced with) the checker's own now-dual-aware `check_generic_call` -- a real, separate
   reimplementation this file already carries for every other piece of generic-call metadata
   (`generic_fn_infer_idx`/`_unwrap`, "redone independently here since this file doesn't share the
   checker's own registries"). Missing its own `_idx2`/`_unwrap2` pair, a two-param call built the
   WRONG target name (`"pair_new$ItemStack"`, missing `$String`), found `fn_descs` empty for it,
   and fed ASM an empty method descriptor -- a real `StringIndexOutOfBoundsException` deep inside
   `MethodWriter.visitMethodInsn`, not a clean compiler error. Fixed by porting the exact same
   `_idx2`/`_unwrap2` metadata (computed once in `build_program_info`, mirroring `generic_fn_infer_
   idx`/`_unwrap`'s own population loop) through to the call site, appending a second `"$..."`
   segment when present.

**Status, 2026-09-16**: profiled, with real numbers. `examples/collections_stress_test.hotc` runs
a real head-to-head, timed with `System.nanoTime()` (same profiling discipline `ECS_IDEAS.md`'s own
archetype-storage stress tests already used -- real measurement over assumption): at N=2000
String-keyed entries, `Registry<Int>`'s O(n) linear scan took 47ms to insert / 36ms for 2000
lookups (each one an O(n) scan), while `HashMap<Int>` took 8ms to insert / 1ms for the same 2000
lookups -- a real 36x speedup on lookups at a size plenty of real caches (an asset list, tag
lookups) would actually hit, with both structures' own summed-value correctness check agreeing
exactly (1999000, hand-computable as `sum(0..1999)`). A second, larger run (`HashMap<Int>` only --
`Registry<Int>` at this size would be an O(n^2) sweep, minutes not seconds, which is the whole
point being demonstrated, not worth actually running) confirmed real scaling: 100,000 inserts in
118ms, 100,000 lookups in 65ms, `bucket_count` correctly growing via real resizes from 16 all the
way to 262,144 (`2^18`, seven real doublings), `length()` correctly reporting 100,000 throughout.
**Verdict: the real hashing/bucketing/resize machinery works correctly at scale and delivers the
real, intended performance win over `Registry<T>`'s linear scan** -- not just "compiles and returns
the right answer for a handful of entries," an actual measured advantage at the sizes this was
built for.

**Status, 2026-09-15**: landed. `stdlib/collections.hotc` ships `HashMap<V>`/`IntHashMap<V>`,
built exactly as this doc's own "Open questions" answers below settled (two-variant naming,
`register` as the verb, bitmask bucket indexing confirmed working, `IntHashMap<V>` as a direct-
indexed `Vec<Option<V>>` rather than a hash table, one big `use collections;` module). Verified
with a real, hand-computed-expected-output program (inserts/overwrites/removes/a forced 16→32
bucket resize with every value re-read afterward, on both types) and the full existing example
regression suite (zero new failures).

**A real, previously-undocumented generics constraint found building this**, worth keeping as a
pattern for any future generic stdlib type: this compiler's generic-instantiation machinery only
ever resolves a type parameter by unifying it against a REAL ARGUMENT VALUE somewhere in the call
chain — there is no "infer from the assignment's declared LHS type" path for a call with nothing
to unify against. Confirmed two ways: a `static fn new() -> Box<V>` inside `impl Box<V>` silently
monomorphized to the literal placeholder name `V` instead of a real type when called as `Box::new
()` with no arguments; the same static method given a real `V`-typed argument STILL failed
("expects V, got Int") — generic inference through a `static fn` declared inside `impl Struct<V> {
... }` doesn't work reliably at all, only a plain top-level `fn f<V>(...)` does (the exact
mechanism `vec_of`/`registry_new` already use, and exactly why they're written that way rather
than as `Vec::new()`/`Registry::new()`). Ordinary `&self`/`&mut self` INSTANCE methods inside
`impl Struct<V> { ... }` work fine, since `V` there comes from the receiver's own already-resolved
concrete type, not fresh inference — this is why every internal `None`/`Some { ... }` construction
in `collections.hotc` is routed through a small `&self` helper method returning it in `return`
position (confirmed to also correctly resolve bare `None`/`Some` sugar, alongside a `match` arm
and a function's own `return`) rather than written inline as an explicitly-qualified
`Option<...>::None {}` expression — that explicit form only accepts a SINGLE bare identifier as
its type argument (`Parser.hotc`'s own expression-level generic-enum-variant parsing, narrower
than `type_name_ref`'s fuller recursive generic-type support used for declarations), so it can
never spell a compound type like `Vec<Entry<V>>` directly. A SECOND, unrelated parser scope cut
found the same day: array-type syntax (`[T]`) only accepts a bare type name too, not a full
generic type expression — `[Option<Vec<Entry<V>>>]` doesn't parse at all — fixed by routing bucket/
slot storage through `Vec<Option<...>>` instead of a raw array, which reuses `Vec<T>`'s own fully
recursive generic-argument parsing for free. Neither of these is fixed at the compiler level here
— both are real, disclosed, narrower-than-ideal parser scope cuts, worked around rather than
patched, since patching either is compiler surgery outside this pass's own scope.

Spun out of `IDEAS.md`'s own "Collection literal sugar beyond arrays" entry
(still there, now pointing here) the same way `ECS_IDEAS.md` was spun out
once ECS became a real, actively-worked feature — this doc is where the
design gets worked through before any `selfhost/` code changes.

## Why this needs its own design pass, not just "port `java.util.HashMap`"

Every generic type this compiler has today — `Vec<T>`, `Registry<T>`,
`Option<T>`, `Result<T, E>` — is a **real, hand-rolled HC struct**, never an
`extern class` wrapper over a JDK collection. That's not stylistic
preference; it's forced by how HC's own generics actually compile. Confirmed
directly in the published jar (`hotchocolate-v0.1.32.jar`): `Vec<Int>` and
`Vec<Bool>` are TWO SEPARATE class files (`Vec$Int.class`, `Vec$Bool.class`),
each with its own real, monomorphized bytecode — not one erased `Vec` class
casting through `Object` the way `javac` compiles `java.util.HashMap<K, V>`
into a single `HashMap.class` regardless of `K`/`V`.

This means an `extern class JHashMap<K, V> = "java.util.HashMap" { ... }`
binding genuinely doesn't fit the rest of this language: every other generic
type gets a real, specific `get`/`put` for its own concrete type arguments,
while a JDK `HashMap` binding would need every call boxed through `Object`
with an inserted, unchecked cast back out — throwing away the exact
reification guarantee `Vec<T>`/`Registry<T>` already give for free. A real
`HashMap` has to be built the same way they were: a genuine HC struct, using
HC's own monomorphized generics, with **its own real hash function** — the
one genuinely new piece of machinery `Registry<T>`'s linear scan (see
`stdlib/registry.hotc`) never needed at all.

## The real key-type problem, and why this doc exists before any code

A fully generic `HashMap<K, V>` needs a way to hash an arbitrary `K`. HC does
have real bounded generics already — `fn f<T: A + B>(...)` (`type_bounds` on
a generic param, checked by `Checker.hotc`'s own `ensure_fn_instantiated`
against interfaces the concrete type actually implements) — so `HashMap<K:
Hashable, V>` looks like the obvious shape at first glance. **It doesn't
work today, though**: `impl InterfaceName for StructName { ... }` (see
`selfhost/parser/Parser.hotc`'s own `impl_decl`) only ever targets a
user-defined `struct` — there is no way for a PRIMITIVE type (`Int`,
`String`) to implement an interface at all. Since `Int` and `String` are
exactly the two key types every real, concrete use case actually needs
(entity ids, asset paths, tag names — nothing in this project's own examples
needs an arbitrary STRUCT as a map key), a `Hashable`-bounded generic
`HashMap<K, V>` would be real, substantial new checker work (teaching
primitives to satisfy interfaces) to solve a problem v1 doesn't actually
have.

**The pragmatic path, and the one this doc recommends**: don't genericize
over the key type at all. Ship two separate, single-type-parameter generic
structs — `HashMap<V>` (String-keyed) and `IntHashMap<V>` (Int-keyed) — using
the EXACT SAME single-`T`-parameter generics `Vec<T>`/`Registry<T>` already
prove work correctly. This is a strictly smaller ask than it sounds: `HashMap
<V>` is really "`Registry<V>`, but with real O(1)-average buckets instead of
an O(n) linear scan" — same String-key/generic-value shape, not a new concept.
A fully generic, arbitrary-`K` map (needing the `Hashable`-bound work above)
stays a real, explicitly-deferred follow-up, not blocking this.

## Language surface (starting point — see "Open questions" below)

```
use collections;

var cache: HashMap<Texture> = HashMap::new();
cache.put("assets/checker.png", tex);
let found = cache.get("assets/checker.png"); // Option<Texture>, mirrors Registry<T>::get
print(cache.contains_key("assets/checker.png"));
cache.remove("assets/checker.png");

var by_id: IntHashMap<PlayerPos> = IntHashMap::new();
by_id.put(entity_id, PlayerPos { x: 4.0f, y: 0.0f, z: 0.0f });
```

`get`/`contains_key`/`remove`/`length` mirror `Registry<T>`'s own existing
API shape (see `stdlib/registry.hotc`) as closely as possible — `put` is the
one new name (`Registry::register` is "last write wins register," `put` is
the more familiar map verb; open question below on whether to just call it
`register` instead for consistency).

## Architecture

### Storage: bucket array over `Vec<T>`, not a new low-level structure

`buckets: Vec<Vec<Entry<V>>>` (`Entry<V> { key: String, value: V }`) — reuses
`Vec<T>`'s own resizing array entirely, so no new raw-array/growth logic is
needed anywhere; `Registry<T>`'s own `entries: Vec<RegistryEntry<T>>` is
exactly this shape already, just with ONE bucket instead of many. A fixed
initial bucket count (16, matching `java.util.HashMap`'s own default — a
reasonable, uncontroversial starting point, not a JDK dependency) with real
rehashing (walk every existing entry, redistribute into a doubled bucket
array) once load factor crosses a threshold (0.75, same reasoning). Real,
disclosed complexity, but bounded and well-understood — same "correctness
first, fast later if it's ever proven to matter" discipline `ECS_IDEAS.md`'s
own archetype-storage section already established (and which its own
`@profile`-driven stress tests later confirmed was the right call to make).

### Hashing: hand-rolled, not `java.lang.String.hashCode()`

A real `hash_string(s: String) -> Int` written in plain HC (a polynomial
rolling hash over `codePointAt`, the same per-codepoint access
`Lexer.hotc`'s own `codepoint_to_string` already uses) — consistent with
this project's own established preference for building real functionality
in HC itself rather than reaching for a JDK equivalent by default (`Vec<T>`/
`Registry<T>` never called into `java.util.ArrayList`/`HashMap` either).
`IntHashMap<V>`'s own hash is the identity function (an `Int` key IS its own
hash) — no function needed at all, just the bucket-index computation.
Negative hash values (a real possibility — HC `Int` is a signed 32-bit value
that can overflow, same as Java's `int`) need an explicit fix-up (`abs(hash)
% buckets.length()`, or a power-of-two bucket count with a bitmask, TBD —
see "Open questions") before indexing into `buckets`.

### What does NOT need to change: `Registry<T>` stays exactly as-is

`Registry<T>` is used internally by the self-hosted compiler's OWN symbol
tables (`Checker.hotc`'s `vars`/`fn_sigs`/etc.) — real, bootstrap-sensitive
code, per `ECS_IDEAS.md`'s own catalogue of self-hosting fragility bugs
found the hard way this project. `HashMap<V>` ships as a wholly separate,
new, purely-additive stdlib type; nothing about `Registry<T>`'s own
implementation gets touched, and nothing already relying on its documented
O(n) linear-scan/"last write wins" semantics changes underneath it.

## Explicitly deferred (not silently dropped — tracked here)

- A fully generic `HashMap<K: Hashable, V>` for arbitrary struct keys —
  blocked on primitives being unable to implement interfaces today (a real
  checker limitation, not fundamental), and not needed by any concrete use
  case this project actually has yet.
- `HashSet<T>` — likely a thin wrapper reusing the exact same bucket
  machinery with no stored value (`HashSet<String>`/`IntHashSet`, mirroring
  the two-variant split above), but not core to the actual near-term need
  (an asset cache, entity-id lookups) that motivated this doc.
- Iteration helpers (`keys()`/`values()`/`entries()` returning a `Vec<T>` of
  each) — real, likely wanted eventually, but `get`/`put`/`remove`/
  `contains_key` cover every concrete use case in mind today.
- Bucket-resizing performance tuning beyond the straightforward "double at
  0.75 load factor" default — revisit only if a real profiled workload
  (same `@profile`/`--explain-schedule` discipline `ECS_IDEAS.md` already
  used to settle its own "is this actually slow" question) shows it matters.

## Open questions

1. **Two-variant naming** — `HashMap<V>` (implicit String key) + `IntHashMap
   <V>`, as sketched above, or something more explicit like `StringMap<V>`/
   `IntMap<V>` (arguably clearer that neither is a "real" generic-key map
   yet, at the cost of not reading as the familiar "HashMap" name)?
2. **`put` vs `register`** — new verb, or reuse `Registry<T>::register`'s own
   name for the closest-possible API continuity (a caller migrating FROM
   `Registry<T>` TO `HashMap<V>` for the performance win changes nothing but
   the type name)?
3. **Negative-hash fix-up mechanics** — plain `abs(hash) % buckets.length()`
   (simplest, correctness-first), or a power-of-two bucket count with a
   `hash & (n - 1)` bitmask (the more standard real-HashMap approach, needs
   confirming HC's bitwise-AND operator works the same way on `Int` as Java's
   does)?
4. **Does `IntHashMap<V>` even need real buckets**, or would a much simpler
   direct-indexed backing (`Vec<Option<V>>`, growing to cover the largest key
   seen) beat a hash table outright for the actual expected use case (dense,
   small, sequentially-assigned entity ids — see `World#spawn`'s own
   `__next_entity_id` counter in `ECS_IDEAS.md`) — genuinely worth measuring
   rather than assuming a generic hash table is the right structure for
   THIS specific key distribution?
5. **Module name** — `use collections;` (bundling both variants, and any
   later `HashSet`), or split into `use hashmap;`/`use hashset;` the way
   `phoenix`/`phoenix_virtual` are two separate topics today? `use
   collections;` reads better for a caller who wants "the map/set family,"
   but a split lets a caller pull in only what they need (relevant to `use`'s
   own "opt-in, only what's requested" design — see `Driver.hotc`'s own
   `resolve_stdlib_modules` header).
