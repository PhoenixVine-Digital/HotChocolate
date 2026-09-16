# Real hashed collections — design doc

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
