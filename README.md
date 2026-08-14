# Hot Chocolate

## Here we only got one rule, never EVER let it cool.

A small JVM-targeted language for games. The pitch: keep the parts of
Kotlin/Scala/Java worth keeping (concise syntax, real generics later, full
JVM/JDK interop, static typing) but give the compiler enough discipline about
ownership that memory bugs and accidental aliasing get caught before runtime,
the way Rust does.

**New to Hot Chocolate?** Start with [TUTORIAL.md](TUTORIAL.md) — a
from-zero, example-driven walkthrough of the language (assumes you already
know how to program in *something*). Never programmed at all before? Start
with [FIRST_LANGUAGE.md](FIRST_LANGUAGE.md) instead. This README is the
full reference and design rationale, not an introduction.

**License**: [Business Source License 1.1](LICENSE) — source-available now,
free for effectively all use (including compiling and distributing your own
programs written in it) except offering Hot Chocolate itself as a competing
commercial product or service; converts automatically to the GNU Affero
General Public License v3.0 on 2029-08-13, deliberately a strong-copyleft
license rather than a permissive one — chosen so that even after the BSL
window ends, nobody can take Hot Chocolate proprietary or rebrand it as
their own closed product, including as a hosted/network service (the "SaaS
loophole" plain GPL leaves open, which AGPL specifically closes).

## Status: phase 1 (this repo)

- Hand-written lexer/parser (`src/main/kotlin/hc/lexer`, `hc/parser`)
- Type checker + **compile-time move checker** (`hc/sema/Checker.kt`):
  structs are move-only (like Rust); `Int`/`Bool`/`String` are `Copy`.
  Passing a struct by value moves it; `&x` borrows it without moving.
  Using a moved-from variable is a compile error.
- Direct JVM bytecode codegen via ASM (`hc/codegen/CodeGen.kt`) — no
  intermediate `.java` step. Structs become plain JVM classes with public
  fields; functions become static methods.
- CLI (`hc/Main.kt`): `run` compiles + runs via a subprocess (see the
  toolchain note under "Off-heap data" below for why), `build` writes
  `.class` files to disk.
- Generics (monomorphized, zero-cost, with `T: Trait` bounds), `impl`
  blocks/methods (including `by field` delegation), interfaces with `&dyn`
  dynamic dispatch and `sealed` closed-set matching, `&mut` vs `&` with
  real exclusivity checking, arrays, generic enums + `match`, `Float`/
  `Double`, string interpolation, short-circuiting `&&`/`||`, error
  handling (`try`/`catch`/`throw` against real JVM exceptions, plus a
  `Result<T, E>`), nullable extern references (`Type?` with guard-clause
  narrowing), subclassing a real Java class (`extends`/`override`), a
  small self-hosted `Vec<T>`/`Registry<T>` prelude, off-heap `arena
  struct` buffers, real Java interop (`extern class`/`extern interface`,
  including a native `String` reaching real `java.lang.String` methods),
  a `module`/`pub`/`open`/`extend` system, and `static` global state —
  see the sections below.

### `Float` / `Double`

Two more `Copy` primitives alongside `Int`/`Bool`/`String`, added
specifically to unblock real Minecraft interop — `BlockPos` math is
`Int`, but rendering/camera/entity-position code is `double`-heavy, and
render-side APIs mix in plain `float` too.

```
extern class JMath = "java.lang.Math" {
    fn sqrt(x: Double) -> Double;
}

fn main() {
    let a: Float = 3.5f;   // 'f' suffix -> Float, matching Java's own literal convention
    let b = 3.5;           // unsuffixed -> Double, also matching Java's default
    print(JMath::sqrt(9.0));
}
```

- `1.5` is a `Double` literal (Java's own unsuffixed default); `1.5f`/
  `1.5F` is a `Float` literal — deliberately mirroring Java's suffix
  convention, since the whole point is recognizing these against real
  Java signatures.
- No implicit promotion between `Int`/`Float`/`Double` anywhere —
  arithmetic, comparison, and equality all require both operands to be
  the exact same type, same strictness as everywhere else in this
  language. `1 + 1.0` is a compile error, not a silent widen.
- `Double` is a genuinely *wide* JVM value (two local-variable-table/
  operand-stack slots, like `long`) — every slot-allocating or stack-
  duplicating codegen path (`declareLocal`/`declareParam`, `DUP` vs
  `DUP2`) accounts for this; `Float` is a normal one-slot value.
- `<`/`<=`/`>`/`>=` compile to the same NaN-safe `FCMPG`/`FCMPL`
  convention `javac` itself uses (`CMPG` for `<`/`<=`, `CMPL` for
  `>`/`>=`), so a comparison against `NaN` always comes out `false`,
  matching IEEE 754, rather than an arbitrary choice.
- Works everywhere `Int` does: struct fields, arrays, `print()`, and
  transparently through the generic `Vec<T>`/`Registry<T>` prelude
  (`Vec<Double>` needed no changes — the wide-slot handling above is
  what makes that "just work"). See `examples/floats.hc`.

**Known limitation**: `arena struct` fields are still `Int`/`Bool` only
— the off-heap layout math assumes a uniform 4-byte field size, and
`Double` (8 bytes) doesn't fit that without a real packing rework.

### `&mut` vs `&`

Borrows come in two kinds, and they're enforced, not just parsed:

- `&T` (shared, read-only): can read fields, cannot assign to them.
- `&mut T` (exclusive, read-write): can assign to fields (`p.hp = 5;`).
  Only a `var` local's fields, or a `&mut`-borrowed param/`&mut self`, can be
  assigned into — a `let` local's fields, or one reached through a plain
  `&`, are compile errors.
- Taking `&mut x` requires `x` itself to be declared `var` (not `let`).
- Within a single call's argument list (including the implicit receiver of
  a method call), the same variable can't be borrowed both `&mut` and
  anything else at once — `foo(&mut p, &p)` and `foo(&mut p, &mut p)` are
  both rejected, but `foo(&p, &p)` (multiple shared borrows) is fine.
- Struct fields can't themselves be reference types (`struct S { x: &Int }`
  is rejected) — there's no lifetime/region tracking, so a stored borrow
  could dangle; keeping `&`/`&mut` scoped to function parameters and call
  sites is what makes the above checks sound without one.

This is still a purely compile-time discipline, same as move-checking — the
JVM's GC keeps every object alive regardless, so nothing here prevents a
real memory-safety bug the way it would with manual memory management; it
catches aliasing/mutation bugs by construction instead, the way Rust's
borrow checker does for code that would otherwise compile fine and misbehave
at runtime.

### Generics (monomorphized — no boxing)

`struct`/`fn` can take type params (`struct Box<T> { value: T }`,
`fn identity<T>(x: T) -> T`). Type args are always inferred from
call/literal-site argument types — there's no explicit `foo<Int>(x)` call
syntax yet. Unlike Java/Kotlin/Scala generics, these are **never** erased to
`Object`: every distinct instantiation (`Box<Int>`, `Box<Player>`, ...) is
monomorphized into its own real class/method the first time it's used, with
real `int`/`boolean` fields where a primitive type arg is used — no boxing,
ever. Verified: `javap` on a compiled `Box<Int>` shows `public int value;`,
not `public Integer value;`. The tradeoff versus type erasure is class-file
duplication if the same generic struct gets instantiated with many different
reference types — that's a code-size cost, not a perf one, and an
opt-in "share one Object-backed impl across reference-type instantiations"
mode (like .NET does) is a reasonable phase-2 add if it matters in practice.
Bounds/constraints (`T: SomeTrait`) aren't supported yet — there's no
trait/interface system to bound against — so a generic body can only move a
`T` value around (store, pass, return); it can't call methods or do
arithmetic on it.

### Methods (`impl` blocks)

`impl StructName { fn method(&self, ...) -> T { ... } }` adds methods,
called as `value.method(...)`. `self` (owned, moves the receiver like any
other by-value struct param) and `&self` (borrowed) both work and are
move-checked the same as regular params — `p.describe()` where `describe`
takes owned `self` moves `p`, and a second `p.describe()` is a compile
error. Methods desugar to ordinary static functions named `Owner$method`
under the hood — there's no virtual dispatch/vtable, so no
inheritance/polymorphism yet, just plain structs with attached functions.
Generic impls work too: `impl<T> Box<T> { fn get(&self) -> T { ... } }`
monomorphizes per instantiation exactly like generic free functions.

### Arrays

`[Int]` is the type of an array of `Int` (`&[Int]` / `&mut [Int]` for
borrowed access, same rules as everything else). Backed directly by real
JVM arrays (`int[]`, `Player[]`, ...) — no wrapper struct, no boxing for
primitive element types. Two literal forms: `[1, 2, 3]` (elements, type
inferred from the first one) and `[value; count]` (a `count`-length array
filled with `value`, `count` can be a runtime expression; `value` must be
`Copy` — `[Player{...}; 5]` is rejected, since repeating a move-only value
would alias the same object into every slot). `arr[i]` reads, `arr[i] = v`
writes (same mutation rule as struct fields: needs `var` or `&mut`), and
`arr.length` reads the length. Arrays are move-only like structs; `[T]`
works as a generic parameter type and monomorphizes per element type
exactly like `Box<T>` does. Bounds checking is free — it's just the JVM's
own `*aload`/`*astore` array-bounds checks, no extra codegen needed.

### Loops: `for x in a..b` / `for x in arr`

One `for` syntax covers both counting and iterating a collection, Rust-style
(there's no separate "foreach" keyword — a range and an array are both just
things you can write `for x in ...` over):

- `for i in 0..5 { }` — `i` counts `0, 1, 2, 3, 4` (exclusive end); `0` and
  `5` can be arbitrary `Int` expressions, evaluated once each, not
  re-evaluated per iteration.
- `for x in arr { }` — `x` is bound to each element in turn; the array is
  only borrowed for the loop (never moved), and struct elements' fields are
  readable through `x` exactly like anywhere else.
- Both compile to a tight index-based bytecode loop (`IF_ICMPGE`/`IINC`/
  `GOTO`), no iterator-object allocation — arrays are indexed directly,
  ranges just count an int local.
- A bare `a..b` outside a `for` header is a compile error — ranges aren't a
  general-purpose value in this language, only a loop header shape.

### Destructors: `impl StructName { fn drop(&mut self) { } }`

Rust-style automatic cleanup: a `let`/`var` binding's `drop` method fires
when it goes out of scope still owned (unmoved) — at the end of its block,
or right before an early `return` that skips past it. This is what makes
`arena struct`-adjacent resource wrappers viable (e.g. a struct owning a
confined arena that closes it in `drop`), and is generally useful for
"make sure this got cleaned up" logic.

- Declared with the exact signature `fn drop(&mut self)` (no other params,
  no return value) — anything else is a compile error pointing at that
  requirement.
- Fires in reverse declaration order (last-declared, first-dropped), same
  as Rust — verified with three `let`s where the drop order comes out
  `c, b, a`.
- Fires once per loop iteration for a variable declared inside a loop body
  (it's a fresh binding each iteration, so cleaning it up each time is
  correct) — verified with a `while` loop.
- Fires correctly on every early-`return` path, not just falling off the
  end of a function — verified with a conditional early return that drops
  variables from multiple enclosing scopes in the right order before the
  return actually happens.
- A moved/returned value is correctly *not* dropped where it's moved from
  — only wherever it ends up still owned and unmoved gets the drop call.
  This falls directly out of reusing the existing move-checker's
  moved-state, rather than being a separate mechanism.
- Manual/early drop: the built-in `drop(x)` function (not a method —
  `x.drop()` is a compile error, specifically to prevent a double-drop
  footgun) consumes `x` immediately and calls its destructor right there;
  because it moves `x`, the normal end-of-scope drop correctly skips it
  afterward — verified there's no double `drop` call.

**Known limitation, stated plainly**: drop is *not* recursive/structural
like Rust's. Dropping a struct does not cascade into dropping its fields,
even if a field's own type has a `drop` impl — only the struct being
directly bound to a `let`/`var` (or manually `drop()`-ed) gets its own
`drop` called. A function parameter received by value (not `&`/`&mut`) is
also not currently auto-dropped when the function returns without doing
anything else with it. And generic structs can't have an *automatically*
firing `drop` yet (the built-in `drop(x)` function does still work for a
generic instance, since unlike the automatic scope-exit path it goes
through the same on-demand monomorphization as a normal method call).
These are real gaps versus Rust, not just caveats — worth knowing before
relying on this for anything safety-critical.

### Interfaces: `interface`, `impl X for Struct`, `&dyn X`

The first feature in this language that needs genuine dynamic dispatch —
every other method call compiles to a direct static call, resolved
entirely at compile time (that's what makes them zero-cost). An interface
reference doesn't know its concrete type until runtime, so calling through
one costs a real (small) `INVOKEINTERFACE`, layered on top, not
free — the tradeoff for actual polymorphism.

```
interface Describable {
    fn name(&self) -> String;                        // required
    fn tag(&self) -> String {                         // has a default
        return "[thing] " + self.name();
    }
}

struct Player { pname: String, hp: Int }
impl Describable for Player {
    fn name(&self) -> String { return self.pname; }   // tag() inherits the default
}

struct Item { iname: String }
impl Describable for Item {
    fn name(&self) -> String { return self.iname; }
    fn tag(&self) -> String { return "<item> " + self.iname; }  // overrides the default
}

fn announce(d: &dyn Describable) { print(d.tag()); }  // works for either concrete type
```

- **Required vs. default methods**: a signature with no body (`fn name(&self) -> String;`)
  must be provided by every `impl Interface for Struct` block — missing one is a
  **compile-time** error naming exactly which method(s) are missing. A signature
  *with* a body is a default: implementers may override it or just inherit it —
  verified both ways above (`Player` inherits `tag()`'s default, `Item` overrides it).
- **`&dyn Interface`**: a reference that can point to any struct implementing that
  interface, dispatched dynamically. There's no owned `dyn X` — always behind `&`/`&mut`.
  A concrete struct is compatible wherever a `&dyn Interface` it implements is expected
  (real subtyping — the only subtyping relationship in this language; everywhere else
  types must match exactly).
- **Two call forms, verified to produce identical results**: calling `.tag()` on a
  concretely-typed `Player`/`Item` still resolves at compile time (`INVOKEVIRTUAL`
  against the known class — a real instance method now, not this language's usual
  static-desugared one), while calling it through `&dyn Describable` is genuinely
  dynamic (`INVOKEINTERFACE`) and picks the right override at runtime.
- **Default method bodies can't touch struct internals.** Inside a default
  implementation, `self` is typed as `&dyn Interface`, not the concrete struct — so
  a default body can only call *other* interface methods through `self` (like
  `self.name()` above), never access a field directly. This exactly matches how
  Java/Rust default methods work, and falls out for free: `self` being `Ty.Dyn`
  rather than `Ty.Struct` means the existing field-access checker rejects it
  automatically, no special-case code needed for the restriction itself.
Finding and fixing this feature also forced a real, previously-latent bug fix:
call-site bytecode descriptors used to be built from the *caller's* argument
types rather than the *callee's* declared parameter types — harmless as long as
argument and parameter types were always identical, which was true everywhere
until interfaces introduced actual subtyping (passing a concrete `Player` where
`&dyn Describable` is expected). Now fixed to always build descriptors from the
callee's own signature ([CodeGen.kt](src/main/kotlin/hc/codegen/CodeGen.kt)),
which is what should have been happening all along.

#### Generic structs implementing interfaces

`impl<T> Labeled for Box<T> { fn label(&self) -> String { return self.name; } }`
works — a generic struct's interface impl is deferred until a concrete
instantiation exists (`Box<Int>`, `Box<Person>`, ...), then processed exactly
like a concrete one, so each instantiation gets its own correctly monomorphized
instance methods. Real limitation, not just a caveat: since interfaces
themselves don't take type parameters, this only works for methods whose
signature doesn't need to depend on `T` — `interface Gettable<T> { fn get(&self) -> T; }`
would need *generic interfaces*, which don't exist here. Finding this also
surfaced two real bugs, both fixed: the checker was looking up a generic
instantiation's interface membership under the wrong name (the shared
template's name instead of the specific instantiation's, e.g. `Box` instead
of `Box_Int`), and — the same leaf-node-sharing hazard fixed earlier for plain
generic methods — an interface method's body was being reused by reference
across different instantiations instead of deep-copied, so checking `Box_Person`
after `Box_Int` clobbered `Box_Int`'s type annotations and produced a
bytecode-verifier crash (`VerifyError: Box_Int not assignable to Box_Person`)
before the fix.

#### Interface inheritance (supertraits): `interface Sub: Super, Super2 { }`

A struct implementing `Sub` automatically satisfies `Super` too — verified
both ways: a struct that only writes `impl Sub for Struct` (no separate
`impl Super for Struct`) is still accepted wherever `&dyn Super` is expected,
and `Sub`'s default method bodies can call `Super`'s methods through `self`.
Under the hood this is real JVM interface inheritance (`Sub`'s class file
`extends`-equivalent-lists `Super` the same way Java interfaces do), so
`Super`'s default methods are inherited automatically by anything
implementing `Sub` with no extra codegen needed for that part.

#### Same-named methods from unrelated interfaces

Fixed properly rather than left unspecified. Two cases:

- **A struct provides one override that satisfies both.** `impl A for Widget { fn info(&self)... }`
  plus a separate, empty `impl B for Widget { }` where `B` also declares `info`
  — this now compiles, because on the JVM one instance method named `info`
  satisfies any number of interfaces' requirement for it simultaneously. This
  needed a real fix: completeness ("did every required method get provided")
  used to be checked per impl block in isolation, so it couldn't see that a
  requirement from a *different* interface's impl block was already satisfied.
  Now checked once, after all of a struct's impl blocks are processed,
  against everything it provides collectively.
- **Neither is overridden and it's genuinely ambiguous** (two unrelated
  interfaces each defaulting `info`, no override anywhere) — this is a
  **compile-time error** naming both interfaces, not a silent pick. This isn't
  just about type-checking convenience: even if the two defaults happened to
  have identical signatures, the struct's class has no single method to link
  an `INVOKEVIRTUAL` against, so silently picking one would risk a
  `IncompatibleClassChangeError` at runtime — the same "inherits unrelated
  defaults" situation real `javac` also refuses to compile.

### Bounded generics: `<T: Trait>`

```
fn apply_damage<T: Damageable>(x: &mut T, amount: Int) -> Int {
    x.damage(amount);
    return x.hp();
}
```

Worth understanding *why* this needs to exist at all, given how generics
work here: since a generic body is only ever checked once fully
monomorphized (never abstractly against a bound, unlike real Rust), an
**unbounded** `T` can already call any method that happens to exist on
whatever concrete type it's eventually instantiated with — genuinely
duck-typed, C++-template-style, errors surfacing per-instantiation rather
than at the generic declaration. A declared bound gives two guarantees on
top of that, not one:

- **Validation at every instantiation site**: `checkTypeParamBounds` runs
  when `T` gets substituted with a concrete type, confirming that type
  actually implements every bound trait, producing one clear error naming
  exactly what's missing — verified: instantiating `apply_damage` with a
  plain `Int` (which doesn't implement `Damageable`) is rejected with
  `'Int' for type param 'T' doesn't implement [Damageable]`, right at the
  call site, rather than failing confusingly deep inside the body.
- **The body itself is checked once, abstractly, against the bound** —
  every bounded type param is substituted with a real `&dyn` value (a
  synthetic interface merging every bound trait's methods, built
  specifically for this check) instead of any concrete type, so a method
  call the body makes on a `T`-typed value only resolves if the bound
  actually declares it. This is the guarantee that makes a bound worth
  writing at all: without it (this was a real gap in the original
  bounded-generics pass, fixed once it was flagged as a lynchpin blocking
  other, bigger features — see `IDEAS.md`), a bounded generic's body was
  checked exactly like an
  *unbounded* one, fully duck-typed against whichever concrete type
  happened to be substituted in wherever it was first called — so a body
  could compile fine against the first instantiation tried (accidentally
  relying on some method that merely happens to exist on that one
  concrete type, not on anything the bound actually promises) and only
  fail, confusingly, at a *different*, later call site instantiating the
  same generic with a type that satisfies the identical declared bound
  but lacks that extra method. Verified directly: a body calling a method
  outside the declared bound (`x.describe()` inside `<T: Damageable>`,
  where `describe` isn't part of `Damageable`) is now rejected with `'T:
  Damageable' has no method 'describe'` — even though the one concrete
  type actually used at the call site (`Player`) really does have a
  `describe()` method. The check runs exactly once per generic, cached,
  entirely independent of whether or how many times it's ever actually
  instantiated — matching how a real bound's correctness shouldn't depend
  on which instantiations someone happened to exercise.

Multiple bounds combine with `+`: `<T: A + B>` — checked against the
union of both traits' methods, and rejected up front if the two bounds
declare conflicting signatures for the same method name (no real type
could implement both compatibly anyway).

**Known limitation**: this second guarantee only covers top-level
generic fns where *every* type param has a declared bound. A mixed
`<T: Trait, U>` (some params bounded, some not) has no way to abstractly
type the unbounded `U` at all, so it falls back to the original
per-instantiation-only checking for such fns — not a regression, just
not yet improved by this fix. Generic struct/impl methods (where the
receiver itself would need abstracting, not just its params) aren't
covered yet either.

### Composition delegation sugar: `impl Interface for Struct by field`

```
impl Describable for Car by engine { }   // no method bodies needed
```

Any interface method not explicitly overridden in the block gets a
forwarding method synthesized automatically — `fn describe(&self) -> String
{ return self.engine.describe(); }` — rather than requiring hand-written
boilerplate to compose one struct's behavior out of another's. Verified
both dispatch paths still work correctly through the delegate: calling
`.describe()` on a concretely-typed `Car` and calling it through `&dyn
Describable` both correctly forward to `engine`. Whether the delegate
field's type actually satisfies the interface isn't specially validated —
the synthesized forwarding call just goes through the same checker pass as
any other method call and fails there if it doesn't, so the error (if any)
looks identical to a hand-written mistake, not a special delegation-only
error path.

### Enums and `match` — a flat tagged union, deliberately not a class hierarchy

```
enum Shape {
    Circle { radius: Int },
    Square { side: Int },
    Point,
}

fn area(s: Shape) -> Int {
    var result = 0;
    match s {
        Circle { radius } => { result = radius * radius * 3; }
        Square { side } => { result = side * side; }
        Point => { result = 0; }
    }
    return result;
}
```

This is the composition-over-inheritance answer to "how do you get a fixed,
closed set of variants without struct subtyping": every variant of an enum
compiles into **one single JVM class**, holding an `Int` `tag` field plus
*every* variant's fields side by side (namespaced `Variant$field`, so two
variants can reuse a field name without colliding). Constructing a variant
just sets the tag and that variant's own fields, leaving the others at
their default zero value — cheap and simple, at the cost of wasting some
memory per instance (every value carries every variant's fields, not just
its own). No sealed-class hierarchy, no per-variant subclass, no
inheritance anywhere in the representation — deliberate, given the
project's composition-first direction elsewhere.

- **Construction**: `Circle { radius: 2 }` reuses struct-literal syntax
  exactly; a unit variant (no fields) is constructed bare, e.g. `Point`
  (no braces). Variant names share a single global namespace with struct
  names (and each other) — two enums can't declare a variant with the same
  name, and a struct can't share a name with any variant. This is a real,
  deliberate constraint to keep bare-name resolution (`Point` without an
  `enum::` qualifier) unambiguous; documented as a limitation, not
  something to work around.
- **`match` works as a statement or a value** — see "`if`/`match` as
  expressions" below for the value form. Field patterns bind by position
  against the variant's declared field order (`Circle { radius }` binds a
  fresh local named `radius`) — no renaming, no nested patterns, no
  literal-value matching.
- **Exhaustiveness is enforced at compile time.** Every variant must be
  covered by name, or a `_` wildcard arm must be present (which must come
  last) — verified: a `match` missing two of three variants and no
  wildcard is rejected, naming exactly which variants are missing.
- The scrutinee is **consumed** (moved) by `match`, matching Rust's default
  `match x { }` semantics for a non-`Copy` `x` — fields get destructured by
  value into the arm's fresh bindings.
- **Known limitation**: no destructor support for enums (unlike structs'
  `drop`) — a real gap versus a "real" enum system, not just a caveat.

#### Generic enums: `enum Option<T> { Some { value: T }, None }`

Monomorphized exactly like a generic struct — the same on-demand
instantiation machinery, just applied to enum variants. The interesting
part was the *unit* variant case: `Some { value: 5 }` can infer `T` from
the field it's given, but `None` has no fields at all to infer anything
from. That only resolves in a position where the target type is already
known — `return None;` against a function declared to return `Option<T>`,
or `let x: Option<Int> = None;` — which needed threading an `expectedTy`
hint into the checker for exactly those two call sites (nowhere else).
Verified with a function returning `Option<Int>` from two different
call sites (found vs. not-found) plus a separate `Option<String>` in the
same program, both instantiations existing simultaneously and correctly.
A bare `None` anywhere the type *isn't* already known from context (no
type-directed inference beyond that) is a clear compile-time error, not a
silent wrong guess.

Building this also forced fixing a real, previously-invisible bug in
generic substitution: instantiating a generic with a `&dyn Trait` or
array type argument produced an unresolvable mangled type name (e.g.
`"Dyn_Block"`) instead of a real type reference — nobody had hit it
because nothing had substituted a generic parameter with those kinds of
types before. Fixed by making the substitution machinery convert a
resolved type back into a proper reference-shaped `TypeRef` (setting
`isRef`/`isDyn` correctly) rather than just using its mangled name.

### `if`/`match` as expressions

```
let min_x = if pos1.getX() < pos2.getX() { pos1.getX(); } else { pos2.getX(); };

let area = match shape {
    Circle { radius } => { radius * radius * 3; }
    Square { side } => { side * side; }
    Point => { 0; }
};
```

`if`/`match` still work as plain statements exactly as above; this is the
same syntax additionally usable anywhere an expression is expected (a `let`
RHS, a call argument, ...) — reachable because the parser now also accepts
a leading `if`/`match` as a primary expression, not just at statement
position, so this is purely additive (existing statement-form code is
completely unaffected).

- **Deliberately not full Rust-style block-tail-value semantics.** Each
  branch/arm is still an ordinary `{ ... }` block using the normal
  statement grammar (every statement needs its usual `;`) — but to be used
  as a value, that block must contain **exactly one statement**, itself a
  bare expression (`radius * radius * 3;`, not `let`/`if`/`return`/...).
  This is a ternary-shaped if/match-expression (ML/Kotlin's `if` model),
  not "the last statement in any block, sans semicolon, is the block's
  value" — that would need a real grammar change (distinguishing a
  semicolon-terminated statement from a value-producing tail expression);
  this needs none, since `{ expr; }` already parsed as an ordinary
  statement block before this feature existed, just always discarded.
  A branch with more than one statement, or whose one statement isn't a
  bare expression, is a clear compile-time error naming the branch.
- **`if`-expressions require `else`** (no value without one); chained
  `else if` works the same as the statement form.
- **Both branches/every arm must produce the same type** — a compile-time
  error otherwise, naming both/all the mismatched types.
- **`match`-expressions keep the statement form's exhaustiveness rules**
  (every variant covered by name, or a `_` wildcard) — same error, same
  wording. An exhaustive match with no explicit wildcard (every variant
  gets its own arm) compiles to a runtime trap on the "no arm matched"
  path instead of a fallthrough, purely to satisfy the JVM bytecode
  verifier's static stack-shape check (every other path already leaves a
  value on the stack) — provably unreachable given the checker's own
  exhaustiveness guarantee, same idiom already used for a function that
  falls off its end without a `return`.
- **No null-narrowing inside an if-expression's condition** — the
  `if x != null { ... }` narrowing the statement form supports (see
  "Nullable extern references") isn't applied here; out of scope for this
  first pass.

### Sealed interfaces and `match`-by-concrete-type

```
sealed interface Shape {
    fn area(&self) -> Int;
}
struct Circle { radius: Int }
impl Shape for Circle { fn area(&self) -> Int { return self.radius * self.radius * 3; } }
struct Square { side: Int }
impl Shape for Square { fn area(&self) -> Int { return self.side * self.side; } }

fn describe(s: &dyn Shape) -> Int {
    match s {
        Circle { radius } => { return radius * 100; }
        Square { side } => { return side * 1000; }
    }
}
```

`sealed` marks an interface's implementer set as closed and tracked by the
compiler (in declaration order) — today that mostly means "known", since
there's no multi-file module boundary yet to actually *enforce* closure
against, but what it unlocks is real: `match` over a `&dyn SealedInterface`
value, dispatching by **concrete struct type** rather than by enum
variant, with the same compile-time exhaustiveness checking as an enum
`match` (verified: a `match` over `&dyn Shape` missing `Square` and no
wildcard is rejected, naming it).

This is the actual answer to "cheaper dynamic dispatch," and it's honest
about what it does and doesn't buy you: it isn't a hand-rolled dispatch
table competing with `INVOKEINTERFACE` for raw speed (the JVM's JIT
already does inline caching there, which is typically about as fast for
the small implementer counts this is realistic for). What it *does* do is
let the matched case skip dynamic dispatch **entirely** — the pattern
match itself compiles to an `instanceof`/`checkcast` chain (there's no
shared tag field the way an enum has one, since implementers are
independent struct classes, not variants of one flat type), and once
matched, any subsequent method call inside that arm on the destructured
value is a completely ordinary, zero-cost static call, not a virtual one.
The saving isn't in how the match itself dispatches — it's that matching
converts "I don't know the concrete type" into "I do," once, up front,
rather than paying dynamic dispatch on every subsequent call.

### `Vec<T>` and `Registry<T>` — a small self-hosted prelude

Two things worth having, and neither needed new compiler machinery to
build: they're just ordinary Hot Chocolate source
([`Prelude.kt`](src/main/kotlin/hc/Prelude.kt)), parsed and merged into
every compiled program before checking — real "eating your own dog food,"
not special-cased in the compiler. Since generic declarations are only
ever checked once instantiated, including this unconditionally costs
nothing for a program that never references it.

- **`Vec<T>`** is a genuine growable array (`[T]` alone is fixed-size).
  Verified pushing 5 elements past an initial capacity of 4, correctly
  triggering the grow-and-copy path. Building it needed one real checker
  relaxation: `[value; count]` used to require a `Copy` value; it now
  allows any type, **aliasing** the same reference into every slot rather
  than moving it (memory-safe on the JVM regardless of element type —
  storing one reference into N array slots is exactly what any array-fill
  already does under the hood). That's what lets `push` fill a freshly
  grown backing array using the just-pushed value as a throwaway filler
  for the slots it's about to overwrite anyway, without needing a
  separate "default value for T" concept this language doesn't have.
- **`Registry<T>`** is the deliberate, extensible counterpart to `enum`:
  built on `Vec`, string-keyed, `.get` returns `Option<T>` so a missing
  key is a normal, matchable value rather than a crash. This is the
  intended tool for a Minecraft-style block/item registry — `enum` is for
  a fixed, known-at-compile-time set of cases; `Registry<dyn Trait>` is
  for a set that grows as more things get registered into it. Verified
  end-to-end with `Registry<dyn Block>` holding *two different* concrete
  struct types (`StoneBlock`, `DirtBlock`) registered under separate keys,
  `.get()` returning the right one dynamically dispatched through `Option`,
  and a missing key correctly producing `None`.

Getting the registry case working end-to-end surfaced one more real,
previously-hidden gap and fixed it: `let x: &dyn Trait = &someStruct;`
was only *validating* that the assignment was compatible, not actually
widening `x`'s own stored type to the trait — so `x` stayed typed as the
concrete struct internally, meaning passing `x` into anything doing type
*inference* (like a generic function call) would infer the concrete type,
not the trait, silently defeating the entire point of writing the
annotation. Fixed so a compatible declared type now genuinely widens the
variable's type from that point on, not just checks against it.

**Known limitation, stated plainly**: no true `Vec::new()`/empty
construction — both `Vec` and `Registry` need at least one seed value
(`vec_of`/`registry_new`) to build their initial backing array from, since
this language has no per-type default/zero value to fill an empty one
with. A real gap versus a "real" collections library, not just a caveat.

### Off-heap data (`arena struct`, `Arena<T>`)

This is the actual phase-2 payoff mentioned early on: hot-path game data
(particles, ECS components, grids) that lives in contiguous native memory
instead of as N separate heap objects, cutting GC pressure and improving
cache locality. Built on the JDK's Foreign Memory API (`java.lang.foreign`),
stable since JDK 22 — no preview flags needed.

- `arena struct Particle { x: Int, y: Int, alive: Bool }` declares an
  off-heap struct. Fields are restricted to `Int`/`Bool` for now (4 bytes
  each, naturally aligned, no packing yet — a phase-3 concern) — no
  `String`/nested-struct/array fields, since those are heap objects and
  can't live in raw memory without a lot more machinery.
- `arena Particle[count]` allocates a buffer of `count` elements as one
  contiguous `MemorySegment`, auto-reclaimed once unreachable
  (`Arena.ofAuto()` — see the honesty note below).
- The type of that buffer is `Arena<Particle>`.
- `buf[i].x` reads, `buf[i].x = v` writes — compiled directly to
  `MemorySegment.get`/`set` at `i * sizeof(Particle) + offsetof(x)`, no
  intermediate object ever materialized. A bare `buf[i]` (without a
  field) is a compile error — there's no way to hand back "the whole
  off-heap struct" as one JVM value, so only field-at-a-time access exists.
  Same `var`/`&mut` mutation gating as everything else.
- `Particle { x: 1, y: 2 }` (a struct literal) does **not** work for an
  arena struct — construct the buffer with `arena Particle[count]`
  instead; the checker gives a specific error pointing at that syntax.

**Honesty about what this is and isn't**: this is *contiguous off-heap
storage with automatic reclamation*, not a fully manual arena with
explicit `free`. `Arena.ofAuto()` still lets the JVM decide when memory
is reclaimed (once the `MemorySegment` is unreachable) — real manual
control (`Arena.ofConfined()` + explicit `close()`) would need
destructor/RAII-style "drop" semantics this language doesn't have yet.
What you get today is the actual perf-relevant part for a game engine —
one contiguous allocation instead of N GC-tracked objects, real
`int`/`boolean` storage, no per-element overhead — without yet promising
deterministic manual deallocation.

**Toolchain note**: the compiler itself only needs JDK 17 (it emits
`java.lang.foreign` calls as plain ASM bytecode, no compile-time
dependency on those classes). But *running* a program that uses
`arena struct` needs a JDK 22+ to actually execute — `hc run` shells out
to a real `java` process for this reason (rather than executing
in-process via reflection, like it used to). It picks the `java`
executable from `$HC_JAVA_HOME` or `$JAVA_HOME` if set, falling back to
whatever `java` is on `PATH`. If a program does use `arena struct` and
the resolved `java` isn't 22+, `hc run` refuses to launch it and prints a
clear message telling you to set `HC_JAVA_HOME`, rather than letting it
fail with a confusing `NoClassDefFoundError`.

### Java interop

Calling into existing, externally-compiled JVM classes (the JDK itself,
or eventually Minecraft/Forge/Fabric APIs) via a declared, trusted class
shape — no classfile introspection, no `.jar` parsing at compile time:

```
extern class Random = "java.util.Random" {
    fn new() -> Self;
    fn nextInt(self, bound: Int) -> Int;
}
extern class JMath = "java.lang.Math" {
    fn max(a: Int, b: Int) -> Int;
}

fn main() {
    let r = Random::new();
    print(r.nextInt(6));
    print(JMath::max(3, 9));
}
```

- `extern class Alias = "binary.class.Name" { ... }` declares an alias
  for an existing JVM class by its binary name (dots become slashes
  automatically). Every method's parameter/return types must be the
  *exact* JVM-erased signature.
- Inside the block, `fn new() -> Self;` (no `self`, name `new`) is a
  constructor — `Alias::new(args)` compiles to `NEW` + `DUP` + args +
  `INVOKESPECIAL <init>`. `fn name(self, ...) -> T;` is an instance
  method — `value.name(args)` compiles to `INVOKEVIRTUAL`. Anything else
  (no `self`, name isn't `new`) is a static method — `Alias::name(args)`
  compiles to `INVOKESTATIC`.
- An extern-typed value (`Random` above) is move-only, like a struct —
  same borrow/ownership rules apply.

**Honesty about what this is and isn't**: this is exactly analogous to a
hand-written FFI declaration file (a `.d.ts` for TypeScript, an `extern
"C"` block in Rust) — the compiler *trusts* the declaration completely,
there's no classfile parser cross-checking it against the real class. A
wrong signature (wrong param types, wrong binary name, a method that
doesn't actually exist) type-checks fine and fails at runtime with a
`NoSuchMethodError`/`NoClassDefFoundError`, not a compile error.

**Known limitation**: generic Java APIs erase to `Object` at the
bytecode level (type erasure) — wrapping something like
`List<String>.get(int)` needs the declaration to say the erased
signature (`Object`, not `String`), or the `INVOKEVIRTUAL`/
`INVOKEINTERFACE` link-fails at runtime even though the declaration
"looks" correct.

#### `extern class Alias = "..." interface { ... }`: when the real target is an interface, not a class

```
extern class JList = "java.util.List" interface {
    fn add(&mut self, item: JObject) -> Bool;
}
extern class Component = "net.minecraft.network.chat.Component" interface {
    static fn literal(text: String) -> Component;
}
```

Plenty of real JVM APIs you'd reach for with `extern class` are actually
backed by an `interface`, not a class — `java.util.List`/`Set`/
`Iterator`/`Map.Entry`, `java.lang.Iterable`, `java.util.function
.Supplier`, and plenty of framework types (Minecraft's own `Component`,
`Registry`, ...). The JVM encodes a call to an interface's method
differently at the constant-pool level than a call to a class's — an
*instance* method needs `INVOKEINTERFACE` (not `INVOKEVIRTUAL`), and even
a *static* one (legal since Java 8) needs an `InterfaceMethodref`
constant, not a plain `Methodref`, despite still using the `INVOKESTATIC`
opcode. Since there's no classfile introspection here (same "trust the
declaration" honesty as everything else under `extern`), the source has
to say which kind the real target is: append `interface` right after the
binary name string, on any of the three declaration forms (explicit,
`use { }`, or bare/lazy).

**Getting this wrong is a real, silent-until-link-time failure mode**,
not just a style nicety — a wrong invoke kind compiles clean and even
`javap`-verifies as a structurally valid classfile, then throws
`IncompatibleClassChangeError: ... must be InterfaceMethodref constant`
(static) or the `INVOKEVIRTUAL`-against-an-interface equivalent
(instance) the moment the JVM actually *links* the call — verified
against a real Forge mod calling `Component.literal(String)` and
`List.add(Object)`, both of which crashed exactly this way before this
existed. If you're declaring an `extern class` for something and aren't
sure whether it's really a class or an interface, check with `javap` —
guessing wrong is invisible until the call actually runs.

#### `extern interface`: implementing a Java interface

`extern class` lets Hot Chocolate code call *into* an existing JVM
class. `extern interface` is the other direction: it lets an HC struct
*implement* an existing JVM interface, so real Java code can call back
into it — exactly the shape a mod loader needs (`ModInitializer`, event
listeners, `Runnable`-style callbacks, ...):

```
extern interface Run = "java.lang.Runnable" {
    fn run(&self);
}
extern class JThread = "java.lang.Thread" {
    fn new(target: &dyn Run) -> Self;
    fn start(self);
    fn join(self);
}

struct Greeter { name: String }
impl Run for Greeter {
    fn run(&self) { print("hi from a real java.lang.Thread, " + self.name); }
}

fn main() {
    let t = JThread::new(&Greeter { name: "Rin" });
    t.start();
    t.join();
}
```

`impl Run for Greeter` goes through the exact same machinery as any
other `impl Interface for Struct` (a real JVM instance method on
`Greeter`'s own class, method-name-ambiguity/completeness checking, the
works) — the only difference is codegen has `Greeter`'s class
`implements` the *real* binary name (`java/lang/Runnable`) instead of a
class this compiler generated, so `INSTANCEOF`/`INVOKEINTERFACE` from
genuine Java code sees a real `Runnable`. See `examples/extern_interface.hc`.

**Known limitations**: an `extern interface` can never be `sealed` —
arbitrary external Java code could implement it too, which would make
`match`-by-concrete-type's closed-set assumption unsound, so the syntax
doesn't allow it. It also can't `extends` another interface, and its
methods can't have default bodies (there's no interface class here to
attach one to — every method must be overridden). Both are the same
"declare the trusted shape, nothing more" scope cut as `extern class`.

#### Reference-type casts (`as`, compiling to `CHECKCAST`)

Generic Java APIs erase their type parameters to `Object` at the bytecode
level — `List<Component>.add(Component)` really has the erased descriptor
`add(Ljava/lang/Object;)Z`, so an `extern class` declaration for it must say
`item: JObject`, not the concrete type. `as` bridges that gap in both
directions:

```
extern class JList = "java.util.List" {
    fn add(&mut self, item: JObject) -> Bool;
}
extern class JObject = "java.lang.Object" {}

tooltip.add(Component::literal("hi") as JObject);   // concrete -> erased
let s = (someObject as MyExternType);                // erased -> concrete
```

- `as` now accepts a reference-type cast (both sides `Ty.isObjectRef()` —
  `Str_`/`Struct`/`Enum`/`Dyn`/`JavaExtern`/`Array`) as an alternative to its
  existing numeric-conversion form, compiling to a plain `CHECKCAST` against
  the target's descriptor.
- Same honesty tradeoff as `extern class` itself: a wrong cast type-checks
  fine and throws `ClassCastException` at runtime, not a compile error — this
  is a trusted assertion, not a verified one.
- This is what actually makes generic JDK collections (`List`, `Map`,
  iterators, ...) usable from HC: declare the erased `Object` signature, then
  cast on the way in and out.

#### Runtime type checks: `expr is Type`

`as`'s boolean-returning counterpart — a real JVM `INSTANCEOF`, not a cast:

```
extern class JObject = "java.lang.Object" {}
extern class JList = "java.util.List" {}

fn describe(x: JObject) {
    if x is JList {
        print("it's a list");
    }
}
```

- Same scope as `as`'s reference-type form: both sides must be an object
  reference type (`Str_`/`Struct`/`Enum`/`Dyn`/`JavaExtern`/`Array`) — `is`
  doesn't apply to `Int`/`Long`/`Float`/`Double`/`Bool`, same as real Java.
- Deliberately non-consuming (unlike `as`, which moves its operand) — `x is
  Foo` is a peek at `x`, not a move, the same reasoning `x == null`'s own
  non-consuming check already uses. `x` stays fully usable right after,
  including inside the `if` body.
- Same precedence tier as `as` (binds tighter than arithmetic, looser than
  unary), and the two chain together freely in either order.

#### `extern class` fields — static (`Alias::FIELD`) and instance (`recv.FIELD`)

Some real JVM APIs expose state through a `public` field instead of a
getter method — `Integer.MAX_VALUE`/`Style.EMPTY`/`ForgeRegistries.ITEMS`
(static), or `Minecraft.player`/`Minecraft.font` (instance). `extern
class` bodies can declare either alongside their methods:

```
extern class JInteger = "java.lang.Integer" {
    static MAX_VALUE: Int;
}
extern class JPoint = "java.awt.Point" {
    fn new(x: Int, y: Int) -> Self;
    x: Int;
    y: Int;
}

fn main() {
    print(JInteger::MAX_VALUE);       // 2147483647 -- static, via ::
    let p = JPoint::new(3, 4);
    print(p.x);                       // 3 -- instance, via .
}
```

- `static NAME: Type;` declares a static field, read via `Alias::NAME`
  (no parens — that's what distinguishes it from `Alias::method()`),
  compiling to `GETSTATIC`. `NAME: Type;` (no `static`) declares an
  instance field, read via `recv.NAME` — the exact same syntax a struct
  field already uses — compiling to `GETFIELD` against the receiver.
  Either form is distinguished from a method purely by the absence of
  `fn` (no parens follow the name either way).
- Same trust model as everything else under `extern`: the declared type is
  never checked against the real field's actual type, so a wrong one
  type-checks fine and throws at runtime (`NoSuchFieldError` or a
  `ClassCastException`-shaped failure at the first real use), not a
  compile error. Reading the wrong access kind (`Alias::instanceField` or
  `value.staticField`) *is* a compile error, though — the checker tracks
  which kind each declared field is and points you at the right syntax.
- **Read-only, explicit-signature form only** for this first pass — no
  `= value` write for either kind, and not resolvable via the `use { }`/
  lazy reflected forms yet (a field has no "candidate list" the way an
  overloaded method name does, so reflecting it isn't the same shape of
  problem — just not wired up this pass).

#### Property-style access: `recv.field` reading as `recv.getField()`/`recv.isField()`

```
extern class JFile = "java.io.File" {
    fn new(path: String) -> Self;
    fn getName(self) -> String;
    fn isDirectory(self) -> Bool;
}

fn main() {
    let f = JFile::new("hello.txt");
    print(f.name);        // -- f.getName(), a Java-bean getter
    print(f.directory);   // -- f.isDirectory(), a Bool-returning "isX" getter
}
```

`recv.field` first tries to resolve as a real declared instance field (see
above); when there isn't one, it falls back to a zero-arg `getField`/
`isField` instance method (Java bean-getter naming — `field` capitalized
and prefixed) before giving up, cutting the getter-call ceremony that
dominates interop-heavy code (`stack.getTag()`, `pos1.getX()`,
`enemy.getHealth()`).

- **A real declared field always wins** — this is pure fallback sugar,
  never a silent choice between two things: if `Alias` has both a
  declared instance field named `foo` and a `getFoo`/`isFoo` method, `.foo`
  always reads the field.
- **Read-only for this first pass** — no `recv.field = value` sugar over a
  `setField` method yet (see the ideas backlog: unlike the getter case,
  there's currently no extern instance field *write* path at all to layer
  sugar over, so it's a bigger, separate addition).
- **Scoped to non-`lazy` `extern class` declarations** (explicit signature
  or `use { }` reflection, both fully resolved up front) — a bare/lazy
  declaration resolves members on demand via real classpath reflection,
  and probing it for two guessed candidate names (`getField` then
  `isField`) on every unmatched field access would mean firing (and
  potentially erroring on) speculative reflection lookups instead of one
  deliberate method call.
- Same "trust the declaration" honesty as everything else under `extern`:
  a `getField`/`isField` method that doesn't actually exist on the real
  class type-checks fine (the checker only ever sees whatever signature
  you declared) and throws at runtime.

#### Java annotations: `@"binary.Name"(arg: value, ...)`

Some Java frameworks find your code through annotation *reflection*
instead of a registration API you can call — Forge's event bus is the
motivating case: `@Mod.EventBusSubscriber(modid = MODID, value =
Dist.CLIENT)` on a class plus `@SubscribeEvent` on each handler method is
how `MinecraftForge.EVENT_BUS`/the mod-bus find your listeners at all.
`@"binary.Name"` immediately before a top-level `struct` or `fn` declares
a real Java annotation, emitted as a genuine classfile
`RuntimeVisibleAnnotations` attribute — not a compiler-internal marker,
something reflection actually sees:

```
@"net.minecraftforge.fml.common.Mod$EventBusSubscriber"(
    modid: "yourmodid",
    value: [enum("net.minecraftforge.api.distmarker.Dist", "CLIENT")],
)
pub struct MyClientEvents {}

@"net.minecraftforge.eventbus.api.SubscribeEvent"
pub fn onRenderOverlay(event: &RenderGuiOverlayEventPost) {
    // ...
}
```

- `@"binary.Name"` alone is a marker annotation (no args). `@"binary.Name"
  (argName: value, ...)` supplies named arguments.
- Three argument value shapes exist, deliberately — annotation arguments
  are compile-time constants baked directly into the classfile attribute,
  not executable code, so this is its own small grammar, not the general
  expression language: a string literal (`"..."`), a real Java `enum`
  constant via `enum("binary.Name", "CONST")` (compiles to
  `AnnotationVisitor.visitEnum`, exactly what an enum-typed annotation
  argument is stored as at the classfile level — there's no "reference" to
  an enum constant the way `GETSTATIC` reads one at runtime; annotation
  metadata is inert data read back by reflection, never executed), or an
  array `[value, value, ...]` of either (`AnnotationVisitor.visitArray`) —
  needed whenever the real attribute's declared type is itself an array
  (`Mod.EventBusSubscriber.value()` is `Dist[]`, not a single `Dist`,
  which is why the example above wraps it in `[...]` even for one
  element). **Getting this wrong is a real, silent-until-runtime failure
  mode**, not just a style choice: writing a bare `enum(...)` where the
  real attribute is array-typed compiles fine and produces a classfile
  whose array-typed attribute got a scalar value instead — verified to
  reach all the way through to a `ClassCastException` inside Forge's own
  `@Mod.EventBusSubscriber` scanner (`EnumHolder` cast to `List`) the
  moment that class actually loads. There's no reflection against a real
  `@interface` to catch this at compile time (see below) — the source has
  to say which shape it means.
- Scoped to top-level `struct`/`fn` only for this first pass (not `impl`
  methods, interfaces, or enums) — that's exactly the shape Forge's
  event-bus pattern needs, paired with "Named binary target for
  module-level `pub fn`/`pub static`" (above): a module's first struct
  gets the class-level annotation, its `pub fn`s (compiled as static
  methods on that same class) get the method-level ones.
- Always emitted as `RUNTIME` retention (`RuntimeVisibleAnnotations`, not
  `RuntimeInvisibleAnnotations`) — reflection-driven consumers need it at
  runtime, and there's no source-level knob to ask for less.
- Same trust model as `extern`: the annotation's binary name and argument
  shape are never checked against a real `@interface` declaration (there
  isn't one to check against — declaring actual Java annotation *types*
  from HC isn't supported, only *using* existing ones). A typo'd name or
  wrong argument type compiles fine and just silently doesn't match what
  the reflecting framework expects — no compile-time or load-time error,
  since the JVM doesn't validate annotation data against the annotation
  interface at class-load time either.

#### `@serializable`: compiler-generated `encode`/`decode` for a struct

A bare compiler *directive* (no quoted binary name — distinguished from
`@"binary.Name"(...)` above at parse time), immediately before a
top-level `struct`. Generates real `pub fn encode(packet: &S, buf: &mut
FriendlyByteBuf)` / `pub fn decode(buf: &mut FriendlyByteBuf) -> S`
top-level fns from the struct's own field list, in declaration order —
genuinely generated bytecode (`javap` shows real `encode`/`decode`
methods), not a runtime reflection scheme:

```
extern class FriendlyByteBuf = "net.minecraft.network.FriendlyByteBuf" {
    // Really returns `FriendlyByteBuf` itself (fluent chaining), not `void` -- get this wrong
    // and it compiles fine, then throws `NoSuchMethodError` the moment it's actually called (see
    // "extern class fields"/"Java interop" below for why: the JVM matches by exact descriptor,
    // return type included, and there's no reflection here to catch a wrong one at compile time).
    fn writeUtf(self, v: String) -> FriendlyByteBuf;
    fn readUtf(self) -> String;
    // ... one write/read pair per field type actually used below
}

@serializable
struct ClipboardPacket {
    data: String,
}

fn main() {
    let packet = ClipboardPacket { data: "hello" };
    var buf = FriendlyByteBuf::new();
    encode(&packet, &mut buf);
    let restored = decode(&mut buf);
}
```

- Scoped to exactly one target for this pass: an `extern class` literally
  named `FriendlyByteBuf` must be declared somewhere in the same compile
  (Forge's networking buffer type is the motivating case — see
  `kubejs-aisle-tool`'s `ClipboardPacket`, whose hand-written
  `encode`/`decode` this replaces). No pluggable backend (NBT/JSON/other
  wire formats) yet.
- Field types are limited to `Int`/`Long`/`Float`/`Double`/`Bool`/
  non-nullable `String`, each mapping to a real
  `writeInt`/`readInt`, `writeLong`/`readLong`, `writeFloat`/`readFloat`,
  `writeDouble`/`readDouble`, `writeBoolean`/`readBoolean`,
  `writeUtf`/`readUtf` call — a struct field of any other type (a nested
  struct, an enum, a nullable `String?`, another `extern class`) is a
  compile error naming the unsupported field.
- Whether the declared `FriendlyByteBuf` extern class actually has the
  needed `writeX`/`readX` methods is left to the normal checker pass over
  the synthesized bodies to catch — same "no special-cased validation"
  approach the `by field` interface-delegation synthesis already uses.
- `encode`/`decode` are ordinary top-level `pub fn`s (not name-mangled
  like `impl`-desugared methods), landing on the struct's own holder
  class via the "named binary target" mechanism above when the struct is
  its file's first declared one — so a compile with more than one
  `@serializable` struct needs each in its own file/module to avoid the
  same flat-global-namespace collision any other same-named top-level
  `pub fn` pair would hit.

### String interpolation: `"a {expr} b"`

```
struct Enemy { name: String, health: Int }

fn main() {
    let enemy = Enemy { name: "Goblin", health: 42 };
    print("Enemy {enemy.name} has {enemy.health} health");
}
```

- Sugar over the existing `String + String` concatenation, not a new value kind
  — `"a {x} b"` desugars (in the lexer) into literal segments and real embedded
  expressions, then compiles (in codegen) to the same `StringBuilder` chain
  `+` on strings already used, just generalized to N parts instead of 2. No
  new runtime mechanism.
- An embedded `{expr}` can be **any expression**, not just a bare variable —
  `"{1 + 2}"`, `"{enemy.get_name()}"`, `"{a} and {b}"` all work, because the
  lexer splices the expression's own real token stream into the surrounding
  string's tokens and the parser's ordinary `expression()` parses it — no
  separate mini-parser for interpolation.
- Only the existing `Copy` types can be interpolated: `Int`/`Long`/`Float`/
  `Double`/`Bool`/`String` — the same set `print()` already knows how to
  render. There's no user-defined `toString`/interpolation overload
  mechanism in this language, so interpolating a struct or `extern class`
  value is a **compile-time error** naming the offending type, not a silent
  JVM-default `toString()` or an unhelpful runtime crash.
- `\{`/`\}` escape to literal braces; `{}` (empty) is a legal, if useless,
  interpolation of nothing. Nested strings/interpolation inside a `{...}`
  work too (`"outer {"inner " + "concat"} done"`) — the lexer tracks brace
  depth and skips over nested string literals correctly rather than naively
  scanning for the next `"`. See `examples/interpolation.hc`.

### Nullable extern references: `Type?`

The other half of what makes real JVM interop honest: HC-native code still
has no concept of null at all, but a JVM method genuinely can hand one
back, and pretending otherwise (the old workaround: cast the result to
`Long`/`Int` and compare to zero) doesn't even compile — `isNumeric`
rejects it outright, so the only escape used to be a second declared
method (`has_pos`/`containsKey`-style) or pushing the check to a thin Java
shell. `Type?` closes the gap directly, scoped to exactly where null is a
real fact of life: the `extern class` boundary.

```
extern class SecurityManager = "java.lang.SecurityManager" {
    fn toString(&self) -> String;
}
extern class System = "java.lang.System" {
    static fn getSecurityManager() -> SecurityManager?;
}

fn describe_security_manager() -> String {
    let sm = System::getSecurityManager();
    if sm == null {
        return "no security manager installed";
    }
    // `sm` is narrowed to plain (non-nullable) SecurityManager here on --
    // calling a method on it needs no cast or extra check.
    return sm.toString();
}
```

- **`Type?` in an `extern class` declaration** marks that return (or
  param) as possibly-null — resolves to `Ty.JavaExtern(binaryName,
  nullable = true)`. Scoped specifically to extern types: writing `Int?`
  or `SomeStruct?` is a compile error ("nullable types are only supported
  for `extern class` types") — HC-native values still can't be null.
- **A nullable value can only be compared against the `null` literal**
  (`x == null` / `x != null`) until narrowed. Calling a method on one
  directly is a compile-time error naming exactly that: *"cannot call
  'toString' on a possibly-null value -- check `== null`/`!= null`
  first"* — verified directly, not just designed to say that.
- **Comparing against `null` doesn't consume the value** — unlike normal
  binary-op operands, `x == null` reads `x` without moving it (the same
  `consume = false` treatment a `&`-borrow gets), so the guard-clause
  idiom below can keep using `x` afterward.
- **Guard-clause narrowing**: `if x == null { <block that always returns
  or throws> }` with no `else` — the checker recognizes this exact shape
  (structurally, not full reachability analysis) and re-types `x` as
  non-nullable for every statement after the `if`, in the same scope.
  This is what makes `return sm.toString();` after the null check above
  type-check with zero cast or extra ceremony — verified both directions:
  the narrowed call compiles and runs correctly, and the *same* call
  written *without* the preceding null check is correctly rejected.
- **Branch-scoped narrowing**: `if x != null { ... }` narrows `x` inside
  that `then` branch's own scope (never leaking to an `else` or to code
  after the `if`); `if x == null { ... } else { ... }` narrows `x` inside
  the `else` branch instead. Each is scoped to exactly that block — declared
  into the same `Env` frame the block's own scope-exit already discards, so
  there's no risk of a narrowing from one branch bleeding into its sibling.
  ```
  if player != null {
      player.getMainHandItem();  // fine -- narrowed for this block only
  }
  // player is still Player? here, unnarrowed
  ```
- **Codegen** uses the JVM's own `IFNULL`/`IFNONNULL` branch instructions
  directly against the single reference on the stack — no boxing, no
  constructing an actual `null` constant to compare against.

**Known limitations, real scope cuts, not oversights**:
- **Only the exact `ident == null`/`ident != null` shape narrows** (either
  literal order) — no `if x == null || y == null { ... }` compound
  conditions, no narrowing inside a `while` condition, no narrowing of
  anything other than a bare identifier (a field access like `mc.player`
  needs binding to a local first: `let player = mc.player; if player !=
  null { ... }`). This is pattern-matched narrowly against the idioms real
  ported code actually needed, not a general flow-typing system.
  (Deliberately so, not just unimplemented: general flow-sensitive
  re-typing of an *existing* variable would be a much bigger mechanism in
  this checker — see `IDEAS.md`'s `if let` entry for the same reasoning
  applied to `Option`.)
- **No `?.`/`?:` (safe-call/Elvis) operators** — narrow via the guard
  clause, then use the value normally; there's no shorthand for "do
  something only if non-null" inline yet.

### `String?`: the same nullability, for the one built-in type that isn't a `JavaExtern`

```
extern class Properties = "java.util.Properties" {
    fn getProperty(&self, key: String) -> String?;
}

fn describe(p: &Properties, key: String) -> String {
    let v = p.getProperty(key);
    if v == null {
        return "missing";
    }
    return "found: " + v;   // v: String here, full String power (+, interpolation, ...)
}
```

`Ty.Str_` went from a singleton `object` to a `data class Str_(val nullable: Boolean = false)`
— same shape as `Ty.JavaExtern.nullable`, same narrowing mechanism
(`Checker.narrowNonNull`), same `== null`/`!= null`-only restriction
until narrowed. A real JDK method returning a possibly-null `String`
(`Map.get`, `System.getProperty`, `Properties.getProperty`, ...) can now
be declared honestly instead of needing a workaround.

- `+`, string interpolation, and the `.method()` bridge to `extern class
  ... = "java.lang.String"` declarations (see "Java interop" above) all
  correctly reject a not-yet-narrowed `String?`, same "must narrow first"
  discipline as everywhere else nullability applies — `print(s)` and
  string interpolation both name the specific problem ("possibly-null
  String") rather than a generic type-mismatch error.
- **`==`/`!=` between two `String` values is null-safe**, not just
  narrowing-gated: comparing two still-nullable strings directly (`a ==
  b`, neither narrowed, either or both potentially actually null at
  runtime) compiles to `java.util.Objects.equals(a, b)` rather than
  `a.equals(b)` — the naive translation would NPE the moment the *left*
  operand happened to genuinely be null. Two provably non-null strings
  still get the cheaper direct `a.equals(b)` (`INVOKEVIRTUAL`, no static
  helper call) — verified both codegen paths are actually taken via
  `javap`, not just that both compile.
- `read_line()` stays declared non-nullable for now, deliberately, even
  though the real `BufferedReader.readLine()` it wraps can genuinely
  return null at EOF (see `examples/input.hc`'s existing, honest-about-
  itself failure mode) — changing that would be a breaking change to
  every existing program calling `read_line()` without narrowing.
  Worth revisiting, just not silently as a side effect of this pass.

### `extends`: subclassing a real Java class

The actual last-mile feature for writing a whole Forge/Minecraft mod class in
Hot Chocolate, not just its logic. Everywhere else in this language, "IS-A"
is `impl Interface for Struct` (JVM `implements`) — composition over
inheritance, deliberately. But some Java frameworks (Forge chief among them)
require genuine *subclassing* to hook into: `Item`, `Block`, `BlockEntity`,
`Screen` all expect you to extend them, not just implement an interface,
because their default behavior lives in the superclass and gets reached via
`super.foo()`. `extends` is the escape hatch for exactly that case, and only
that case — it doesn't change the interface-based story for everything else.

```
extern class ArrayList = "java.util.ArrayList" {
    fn new() -> Self;
    fn size(&self) -> Int;
}

struct LoudList extends ArrayList {}

impl LoudList {
    override fn size(&self) -> Int {
        print("size() called!");
        return 42;
    }
}

fn main() {
    let list = LoudList::new();
    print(list.size());  // prints "size() called!" then 42
}
```

- **`struct S extends C { }`**: `C` must be a previously-declared `extern
  class`. `S`'s generated classfile has `C`'s real binary name as its actual
  JVM `superName` — verified with `javap`: `final class LoudList extends
  java.util.ArrayList`, not a comment claiming so.
- **Construction**: `S::new(args)` — same `::new` spelling as an extern
  class's own constructor call, deliberately, since from the caller's side
  it's the same shape. One generated `<init>` per constructor `C` itself
  declares, each just loading its args and forwarding straight to `C`'s real
  `<init>` via `INVOKESPECIAL` — verified in the compiled bytecode.
- **`impl S { override fn method(&self, ...) { ... } }`**: matched by name
  against `C`'s declared method table and validated against its *exact*
  signature (self + params + return), same rigor as an interface override.
  Compiles to a real instance method with `C`'s own method name (not this
  language's usual mangled/static-desugared scheme), so external Java code
  holding a plain `C` reference reaches it through ordinary JVM virtual
  dispatch — verified by actually calling `.size()` on a `LoudList` and
  confirming the override runs, not just that it compiles. `self.method(...)`
  from other HC code on the same struct also resolves to it, through the
  same real-instance-method/`INVOKEVIRTUAL` path an interface override
  already uses (`checkMethodCall`'s `Ty.Struct` branch treats a superclass
  override and an interface override as the same kind of explicit override).

**Known limitations, real scope cuts for this pass, not oversights**:
- **`S` must have zero fields of its own.** The generated constructor's only
  job is forwarding args to `C`'s constructor — there's nowhere for extra
  per-instance state to live in that scheme yet. A struct needing both
  inherited behavior *and* its own fields isn't supported.
- **No `super.method(...)` calls.** An override can't invoke the base
  class's own implementation of the method it's overriding — it's a full
  replacement, not an augmentation.
- **Single inheritance only, no further subclassing.** `S` itself is
  generated `final`; nothing can extend an `extends`-struct.
- **Resolved**: overriding a method whose body needs a null check (Forge's
  `Item.useOn(UseOnContext)` calling `context.getPlayer()`, which is
  legitimately null for a non-player use context like a dispenser) used to
  be blocked here, before "Nullable extern references: `Type?`" shipped.
  The real mod's `CopyToolItem` is now fully off its Java shell — see
  `kubejs-aisle-tool/hc/CopyToolItem.hc`, a real `extends Item` struct
  overriding `use`/`useOn`/`appendHoverText`, verified end-to-end against
  the real Forge classpath (`javap` confirms the generated class really
  `extends net.minecraft.world.item.Item`, and the full mod build links
  against it).

### Logical operators: `&&` / `||`

```
while i < arr.length && arr[i] < 10 {
    print(arr[i]);
    i = i + 1;
}
```

Real short-circuit evaluation (branch-based codegen, not eager-evaluate-
both-sides-then-combine) — `||` binds looser than `&&`, which binds
looser than `==`/comparisons, same relative precedence as every
C-descended language. The short-circuiting isn't just a performance
nicety: `arr[i]` above must never execute once `i < arr.length` is
false, or the loop would throw `ArrayIndexOutOfBoundsException` on its
last iteration. Verified both for short-circuit *behavior* (a right-hand
side with an observable side effect, like a `print`, genuinely never
runs once the left side alone decides the result) and for *correctness
under that reliance* (the bounds-check-then-index pattern above runs
clean with no exception). See `examples/logical_ops.hc`.

### `String` can call `extern class` methods declared against `"java.lang.String"`

```
extern class JString = "java.lang.String" {
    fn split(&self, regex: String) -> [String];
    fn trim(&self) -> String;
}

fn main() {
    let s = "  10 20 30  ";
    print(s.trim().split(" ")[0]);
}
```

A native HC `String` (`Ty.Str_`) and an `extern class Foo = "java.lang.String"
{ ... }` declaration are the exact same JVM type underneath — both
compile to descriptor `Ljava/lang/String;` — so the checker treats a
`Str_`-typed value as free to call any method some `extern class` in the
program happened to declare against that binary name, with **zero
codegen changes**: `genMethodCall`'s extern-call path never cared what
static HC type the checker called the receiver, only what real bytecode
value it is. This is the actual bridge from plain string literals/locals
to real `.split()`/`.trim()`/`.substring()`/etc. — before this, a native
`String` had no methods at all, and the only workaround (see
`hc/Copytool.hc`'s alphabet array) was avoiding string methods entirely.
See `examples/string_bridge.hc`.

**Known trap, found the hard way — avoid `String.charAt(int)`:** it
really returns Java `char` (JVM descriptor `C`), and HC has no `Char`
type to declare that return as. Declaring it `-> Int` (descriptor `I`)
type-checks fine and **crashes at runtime** with `NoSuchMethodError:
'int java.lang.String.charAt(int)'` the instant it's actually called —
verified directly. Use `.substring(i, i + 1)` for a single character
instead: real `String` in, real `String` out, no descriptor mismatch
possible. This is the same class of trap as the pre-existing "generic
Java APIs erase to `Object`" limitation below (`Map.put`/`.get`/
iterator methods all really return `Object`, not whatever type you
`extern`-declared) — a wrong-but-type-checking `extern` signature is
always a live risk exactly where the real JVM signature doesn't match
Java source-level intuition. When in doubt, verify by actually running
the call, not just compiling it — the checker trusts every `extern`
declaration completely and has no way to catch this itself.

### Error handling: `try`/`catch`/`throw`, and `Result<T, E>`

Two separate, deliberately non-overlapping mechanisms — real JVM exceptions
for the Java interop boundary, and an ordinary enum for HC-native
fallibility:

```
extern class Integer = "java.lang.Integer" {
    static fn parseInt(s: String) -> Int;
}
extern class NumberFormatException = "java.lang.NumberFormatException" {
    fn getMessage(&self) -> String;
}

fn parse_or_default(s: String, default: Int) -> Int {
    try {
        let n = Integer::parseInt(s);
        return n;
    } catch (e: NumberFormatException) {
        print("caught: " + e.getMessage());
        return default;
    }
}
```

- **`try { } catch (e: SomeExternType) { } catch (e2: OtherExternType) { }`**
  compiles to real JVM exception handling — ASM `visitTryCatchBlock`
  entries against the try body's start/end labels, one per `catch` clause,
  each with its own handler label and the caught type's real binary name.
  Not a language-invented mechanism: this is exactly what `javac` itself
  emits for a multi-catch `try`, just without checked-exception tracking.
- A `catch` type must resolve to an `extern class` (a real, trusted JVM
  type) — there's no HC-native exception type, and nothing here verifies
  the declared type is actually a `Throwable` subclass beyond the usual
  "trust the `extern` declaration" scope cut every other `extern`
  interaction already has. Multiple `catch` clauses are checked against
  the thrown value's runtime type in declaration order, same as Java.
- **`throw someExternValue;`** compiles directly to `ATHROW`. `someExternValue`
  must check to an extern class type (checker-enforced), same "trust it's
  really a `Throwable`" scope cut as `catch`.
- **Move-checking through `try`/`catch`**: a thrown exception can interrupt
  the try block at *any* point, so each `catch` body is checked from the
  moved-state *before* the try block ran — not after — since assuming the
  try block's own code fully executed before checking a handler for its
  own failure would be unsound. The statement's overall moved-state
  afterward conservatively merges the try block's outcome with every
  catch's, the same OR-merge `if`/`else` already uses for its two branches
  generalized to N branches.
- **Known limitation**: no `finally` yet — cleanup on the exceptional path
  needs a `catch` that re-`throw`s after doing the cleanup, for now.

```
pub enum Result<T, E> {
    Ok { value: T },
    Err { error: E },
}
```

- A small addition to the [self-hosted prelude](#vect-and-registryt----a-small-self-hosted-prelude),
  written in Hot Chocolate itself exactly like `Option<T>`/`Vec<T>` — for
  representing "this **HC-native** operation can fail" as an ordinary
  matchable value (no exception, no special control flow), the same way
  `Option<T>` already represents "this can be absent." `try`/`catch` and
  `Result` compose naturally: catch a real Java exception, then return an
  `Err` instead of propagating it further, turning "this specific JVM call
  can throw" into "this function's result might be absent," at the exact
  boundary where that translation makes sense — see `safe_parse` in
  `examples/error_handling.hc`.
- Building this surfaced and fixed a **real, previously-dormant checker
  bug**: the "infer a still-unsolved generic type param from the
  expected/declared type" fallback (originally built only for zero-field
  variant construction like bare `None`) was reading from the wrong
  internal map — `structInstanceArgs` (keyed by monomorphized *struct*
  names) instead of `enumInstanceArgs` (the actual *enum* one). This never
  mattered before because bare `None`/unit-variant construction goes
  through a completely different checker path (`Expr.Ident`) that never
  touches that fallback at all — it only surfaced once a *non-zero-field*
  variant needed a still-unsolved param filled from context, exactly
  `Result::Ok { value: n }`'s situation (`T` infers from `value`, but `E`
  never appears in an `Ok` at all, so it can only come from the function's
  declared return type). Fixed by pointing the fallback at the right map,
  and generalizing it from "all-or-nothing" (only fires when zero params
  are solvable from fields) to "fill whichever specific params are still
  missing after fields," so it now covers both cases with one code path.

### User input: `read_line()`

A built-in, special-cased in the checker/codegen exactly like `print` —
takes no arguments, returns one line read from stdin as a `String`:

```
fn main() {
    print("What's your name?");
    let name = read_line();
    print("Hello, " + name + "!");
}
```

Backed by a single `BufferedReader` over `System.in`, created once in
the compiled program's own `<clinit>` and kept for the program's whole
lifetime (a fresh reader per call would silently drop any input the
previous one had already buffered ahead past the current line). Parsing
a line into something other than a `String` — an `Int`, say — isn't a
separate builtin; it's just an ordinary [Java interop](#java-interop)
call, e.g. `extern class Integer = "java.lang.Integer" { fn parseInt(s:
String) -> Int; }` then `Integer::parseInt(read_line())`. See
`examples/input.hc`.

**Known limitation**: at end-of-input, `readLine()` returns Java's
`null` — a value this language has no concept of (there's no
`Option<String>` wrapping here, unlike `Registry.get`). Reading past
EOF today just crashes at runtime the moment that `null` reaches
something that dereferences it (e.g. `String` concatenation prints the
literal text `"null"`, but `Integer::parseInt` throws a
`NumberFormatException`) — there's no compile-time or clean runtime
signal for "no more input" yet.

### JDK 17 compatibility (and why it matters for Minecraft modding)

**A program that never uses `arena struct` compiles to plain JDK
17-bytecode with zero `java.lang.foreign` references anywhere** — that
codegen path only runs when the AST actually contains an `arena`
allocation or an off-heap field access, so if you don't write `arena`,
nothing about it exists in the output. Verified with `javap -verbose`:
class files emitted for `hello.hc`/`generics.hc`/`methods.hc`/`mut.hc`/
`arrays.hc` all report `major version: 61`, which *is* Java 17 — not
"17 or newer," the literal class file version Java 17 itself emits.

This matters concretely for the eventual goal of writing a Minecraft
1.20.1 mod in Hot Chocolate: Minecraft 1.20.1 (Forge/Fabric) runs on
Java 17 specifically, so mod code has to load in a Java 17 JVM. As long
as the mod-side code sticks to normal structs/generics/methods/arrays —
i.e., skips `arena struct` — the `.class` files `hc build` produces
should be loadable there with no toolchain mismatch. (Actually wiring up
a Forge/Fabric mod-dev pipeline — packaging, `mods.toml`, the mod loader
APIs, a Gradle mod-dev plugin — is a separate, substantial piece of work
this repo doesn't attempt yet; this is just the language/compiler half
of that being unblocked.)

### Multi-file projects

`hc run`/`hc build` accept a directory instead of a single `.hc` file:

```bash
./gradlew run --args="run examples/multifile"
```

Every `.hc` file *anywhere under* the directory — recursively, nested
subdirectories included — is parsed and merged into one flat program —
the exact same merge the prelude already goes through — before checking
and codegen. There's no `use`/`import` statement: every top-level name
(struct, fn, interface, enum, `extern class`) shares one global
namespace across every file in the tree, so a name can only be declared
once total, same as within a single file today. See `examples/
multifile/` (a `sealed interface` in one file, implemented by structs
in two others, matched over from a fourth) for a working example.

Nesting files under subdirectories mirroring their own `module` path
(`client/Foo.hc` declaring `module ...client;`, matching the Java/
Kotlin source-root convention) is a real, supported organizational
choice — a real Minecraft mod project's own `.hc` files are laid out
exactly this way — but it's purely a convenience for humans/tooling
browsing the source tree; the compiler itself never checks a file's
declared `module` against its actual directory path (see `IDEAS.md`'s
"Verify a declared module matches the file's directory path" for the
opt-in check that would add that, deliberately not built by default).
A perfectly flat directory with every file's module declared explicitly
works exactly as well — nesting changes nothing about how names resolve
or which class each file's own declarations land on.

This is also what makes `sealed interface`'s "every implementer is
declared in this compilation unit" guarantee actually mean something —
previously the entire program was always one file, so it was trivially
true; now it spans a real multi-file project directory (or tree).

**Known limitation**: no explicit dependency graph — every file under
the directory is compiled together regardless of whether anything in it
is actually used. Per-file namespacing, though, is exactly what `module`
(below) gives you.

### Modules: `module`, `pub`, `open`, `extend`

Not a copy of Java's `package` — a real ownership/visibility boundary,
and the vehicle for the language's actual ethos: composition over
inheritance, and an *ecosystem* other code can extend without touching
the original source (the concrete case this was built for: a Minecraft
mod adding new behavior to another mod's types).

```
module minecraft.machine;

pub open interface Machine {
    fn name(&self) -> String;
}

pub struct Furnace { }
impl Machine for Furnace {
    fn name(&self) -> String { return "furnace"; }
}

struct InternalConfig { }   // no `pub` -- invisible outside this module
```

```
module phoenix.machines;

// Adds a brand-new method to Machine from a *different* module than the
// one that declared it -- no edit to machines.hc needed, only possible
// because Machine was declared `open`.
extend Machine {
    fn phoenixTier(&self) -> Int { return 1; }
}

// Extensions work directly on concrete structs too, not just open traits.
extend Furnace {
    fn describe(&self) -> String { return self.name() + ", a phoenix machine"; }
}
```

```
fn main() {
    let f = Furnace { };
    print(f.describe());     // struct-direct extension
    print(f.phoenixTier());  // trait extension, reached through Furnace's own impl
}
```

- **`module a.b.c;`** — optional, first thing in a file. Unlike a bare
  namespace prefix, different files in the *same* compile can freely
  declare different modules; each top-level struct/interface/enum is
  qualified into its own declaring file's module, independently. A
  declaration with no `module` line lands in the default (unnamed)
  module, exactly like a program that never declares one always has.
- **`pub`** — Rust-style, not Java's four-tier system: private by
  default, `pub` to export. For struct/interface/enum, this is real JVM
  enforcement, not just a checker opinion — a non-`pub` declaration
  compiles to a package-private class, so cross-module code that
  shouldn't reach it can't even link against it, `IllegalAccessError` at
  load time if something tries. (See `examples/modules/`.)
- **`open interface X`** — marks a trait as allowed to receive new
  methods from `extend` blocks in other modules. Orthogonal to `sealed`:
  `sealed` is about the closed set of *implementers* (what `match`
  exhaustiveness needs); `open` is about the extensible set of
  *extension methods*, which never changes who implements the trait.
- **`extend Target { fn new(&self, ...) -> T { body } }`** — adds a
  callable method to an existing struct or `open interface`, from any
  module, without modifying `Target`'s own declaration. Every method
  needs a body (there's no implementer to ask for an override — their
  classes are already compiled); a real inherent/interface method always
  wins over a same-named extension, and two modules extending the same
  target with the same method name in scope together is a compile
  error. Under the hood this is genuinely simple: it desugars into an
  ordinary top-level function taking the receiver as an explicit first
  argument, resolved by the checker as a last-resort fallback after
  every normal method-lookup path has already failed — no new runtime
  mechanism, no vtable patching.

Top-level functions get real enforcement too, not just types: every fn
(including desugared impl/extend methods) lands on its own holder class
in its own package — a `pub fn` in one file is a genuinely separate,
independently-linkable JVM method from a same-named private one in
another, not two methods sharing a class. `fn main()`'s own file (or
the default/unnamed module, if it never declared one) keeps the
CLI-supplied class name and the real JVM entry point. Every other file
gets a **named binary target**: if it declares at least one `struct`,
its top-level `pub fn`/`pub static` land as ordinary static members
directly on *that file's own first* declared struct's class — a real
name Java code can reference by writing it, not a hidden one. Only a
file with no struct at all falls back to an internal `$Fns` holder.

```
module defs;

pub struct Items { }   // this file's first struct -- the "named holder"

pub static COUNT: Int = 7;
pub fn bump(x: Int) -> Int { return x + 1; }
```

```java
// From plain Java: Items.COUNT and Items.bump(x) are real static
// members on defs.Items, not an undiscoverable defs.$Fns.
int n = defs.Items.bump(defs.Items.COUNT);
```

This is exactly the shape a Forge-style "registry holder" class needs
(`DeferredRegister`/`RegistryObject` fields other files read by name) —
see `kubejs-aisle-tool/src/main/hc/Items.hc` for a real one, compiling to
`net.oktawia.structruretokubejsaisles.defs.Items` with a real
`Items.COPY_TOOL` field other Java files in that mod read directly.
Scoped to exactly "first struct wins" per file — a file mixing multiple
structs with top-level fns still only merges onto the first one
declared.

**Grouped by file, not just by module** — deliberately, and this
matters the moment `sourceDir`/directory-mode compilation is in the
picture: multiple files can (and in a real project do) legitimately
share one `module` line, each meaning its own struct to keep its own
separately-named class. `kubejs-aisle-tool`'s `client` module has three
files this way (`CopyToolHudOverlay.hc`, `CopyToolSelectionRender.hc`,
`RenderUtil.hc`), each with its own struct — grouping by module alone
would have silently merged all three files' top-level fns onto
whichever struct happened to be first, breaking the other two files'
`OtherStruct::its_fn(...)`-shaped call sites the moment directory mode
put them in one compile together. A file with no struct at all (a
fn-only file like `Aisletool.hc`/`Copytool.hc`, both sharing a module
too) still gets its own file-named holder in that case, not a shared
`$Fns` — matches the exact TitleCase-file-name-is-the-class-name
convention a single-file compile's own entry class already follows.
Verified directly: `Aisletool.hc` and `Copytool.hc` compiled together
(no `fn main()` anywhere in either) still produce two separately-named
`Aisletool`/`Copytool` classes, not one merged class under an arbitrary
name — real regression coverage exists for exactly this shape now
(`Foo.hc`/`Bar.hc` sharing a module, each keeping its own class; a
fn-only file with no struct at all, arbitrarily chosen as the
compile's fallback "entry," still keeping its own name instead of
being renamed to the directory's own basename).

See `examples/modules/` for a working two-module example (`extend`
reaching across module boundaries, a private struct genuinely
inaccessible cross-module — verified with `javap`, not just by reading
the source).

### Global state: `pub static NAME: Type = initExpr;`

The prerequisite `open registry`/`register` (mentioned above) actually
needed: every value in Hot Chocolate used to be either a local (owned by
some enclosing scope) or a struct field (owned by some instance) — there
was no way for two unrelated call sites, let alone two separate modules,
to share and mutate *the same* persistent object. `static` is that:

```
module minecraft.machine;

pub static machineTiers: Registry<String> = registry_new("ulv", "Ultra Low Voltage");

pub fn registerTier(key: String, label: String) {
    machineTiers.register(key, label);
}
```

```
module phoenix.machines;

// A different module, contributing a new registry entry into machines.hc's static --
// this is the actual mechanism behind `open registry`/`register`.
pub fn seedPhoenixTiers() {
    registerTier("hv", "High Voltage (Phoenix)");
}
```

- Compiles to a real static field on its declaring module's holder
  class, initialized once in that class's `<clinit>` — `pub`/private
  maps onto `ACC_PUBLIC`/package-private exactly like everything else
  here.
- The initializer is checked with no locals, but every *already-declared*
  static is in scope — so a later static can read an earlier one:
  `pub static ITEMS: DeferredRegister = DeferredRegister::create(...);`
  followed by `pub static COPY_TOOL: RegistryObject = ITEMS.register(...);`
  works (see `Items.hc` again). This is backward-reference-only by
  construction, not a general dependency solver — a static's init is
  checked before any *later* static is added to the table at all, so a
  forward reference is simply unresolvable (an "unknown variable" error),
  never a special circularity case to detect. Sound at codegen time too:
  same-module statics initialize in this same declared order within one
  shared `<clinit>`, and a read of a different module's static just
  triggers that module's own class-init first, ordinary JVM
  `<clinit>`-on-first-use semantics.
- Reading/writing works exactly like a `var` local everywhere in the
  checker (same Ident/Assign/method-call code paths, injected into
  every fn's scope) with one difference: a read is *never* treated as a
  move. There's no scope for a global to be "used up" by — the next
  reader anywhere else in the program still needs to see it.

See `examples/registries/` for the full loop: `minecraft.machine`
declares and seeds a registry with no idea `phoenix.machines` exists;
`phoenix.machines` pushes a new entry into it through an ordinary `pub
fn` call; a third file reads both entries back out through the same
shared object.

**Known limitation, an honest scope cut, not an oversight**: this is
still genuine sharing only *within one compile invocation* — `static`
gives two modules in the same compile a real shared, mutable object,
but doesn't yet let two independently-compiled `.hc` builds (separate
jars, discovered only at JVM runtime, the real "mod B extends mod A
without A and B ever being compiled together" ecosystem story) share
one. That needs a runtime-discovery mechanism on top of this (a
ServiceLoader-style registration, or an explicit host-called
registration entrypoint) — `static` is the foundation it would sit on,
not that mechanism itself.

## Gradle plugin (`hc-gradle-plugin/`)

A real, reusable Gradle plugin — not a hand-copied task block. Any
project (like the real Minecraft mod this compiler is being built for)
that used to hand-write a `tasks.register('hcCompile', JavaExec) { ... }`
block per `.hc` file it wanted compiled can instead do:

```groovy
// settings.gradle
pluginManagement {
    includeBuild '../../HotChocolate'   // wherever your HotChocolate checkout is
}
```

```groovy
// build.gradle
plugins {
    id 'hc'
}

hotChocolate {
    version = "v0.1.5"   // resolves the compiler from JitPack -- see "Published on JitPack" below
    source file('hc/foo.hc')
    source file('hc/bar.hc')   // compiled after foo.hc, so it can `extern class` reach foo's output
}
```

#### `version` vs `compilerHome`: where the compiler itself comes from

`hotChocolate { }` needs exactly one of two ways to find the actual
compiler:

- **`version = "v0.1.5"`** — resolves the compiler as a real, already-
  published JitPack dependency (`com.github.P-H-O-E-N-I-X-PackForge:
  HotChocolate:v0.1.5`, adding the `jitpack.io` Maven repository to the
  consuming project automatically) instead of requiring a local
  `./gradlew installDist` against a sibling checkout. **This is the
  right default for essentially every consuming project**: no local
  HotChocolate checkout, no manual build step, a pinned version like any
  other dependency — reproducible on a machine (or CI runner) that's
  never touched the HotChocolate repo at all, the way `compilerHome`
  (pointing at whatever happens to be installed on one developer's
  machine) never was. Verified end-to-end: pointed a real consuming
  project's `hotChocolate { }` block at `version = "v0.1.5"`, confirmed
  the compiler genuinely resolves and runs over the network (not a
  silently-stale local copy) by observing it reject syntax added to the
  language *after* that tag was cut, then reverted back to local
  development's own `compilerHome`.
- **`compilerHome = file(...)`** — the local-dev escape hatch, unchanged
  from before: point it at a local `./gradlew installDist` output (e.g.
  a sibling checkout via `includeBuild`, as in the example above). Use
  this when you're actually working on the compiler itself and need an
  uncommitted, unpublished change to show up immediately. **Wins over
  `version` when both are set** — an explicit local override always
  beats a resolved artifact.

#### Zero-clone setup: resolving the plugin itself from JitPack, no `includeBuild`

`includeBuild` (above) needs a literal local checkout to point at — fine
for working on HotChocolate itself, overkill for just *using* it. The
plugin's own jar is published on JitPack too (see "Published on
JitPack" below), so a consuming project can skip `includeBuild` and
local cloning entirely:

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
    version = "v0.1.6"
    sourceDir("src/main/hc")
}
```

The `resolutionStrategy.eachPlugin` block is required, not optional —
Gradle's normal `plugins { id(...) version ... }` resolution looks for a
*plugin marker* artifact at coordinates `hc:hc.gradle.plugin:vX.Y.Z`
(the plugin id used verbatim as the marker's groupId, a Gradle
convention independent of the project's own group), and JitPack has no
way to publish anything under that arbitrary namespace — it only ever
publishes under `com.github.<user>...`. `eachPlugin` sidesteps the
marker lookup entirely, mapping the plugin id directly to the real
module coordinate. That coordinate's own shape is also JitPack-specific
and easy to get wrong by guessing: a Gradle multi-module build's
submodule publishes as `com.github.<user>.<repo>:<module-name>:version`
(the repo name folded into the *group*, not a separate path segment) —
confirmed by fetching the real POM directly rather than assuming.

Verified end-to-end, from a project directory containing nothing but
the two files above (no HotChocolate checkout anywhere on disk, no
`includeBuild`): `id("hc") version "v0.1.6"` resolves the plugin,
`hotChocolate { version = "v0.1.6" }` resolves the compiler, and a real
`.hc` file compiles to a real `.class` file. The compiler's own
transitive dependencies (Kotlin stdlib, ASM) are ordinary Maven Central
artifacts JitPack doesn't mirror — the plugin adds `mavenCentral()`
itself when resolving via `version`, so this works even for a project
that hasn't already declared it (virtually every real project has, but
a genuinely bare one shouldn't need to know that).

Applying the plugin registers one `hcCompile<Name>` task per declared
source (`hcCompileFoo`, `hcCompileBar`, ...), each depending on the one
before it, and automatically wires the last one into `compileJava`'s
classpath and `processResources` — exactly the dependency chain that
used to be copy-pasted by hand into every consuming `build.gradle`,
now declared once and reused. Verified end-to-end against the real mod
project: `hcCompileAisletool`/`hcCompileCopytool` both run, their output
lands in the mod's jar (`javap`/`unzip -l` both confirm the real
`.class` files are present under the expected package), and
`./gradlew build` succeeds.

**`sourceDir`, the directory-mode counterpart** (alongside `source`, in
the same `hotChocolate { }` block):

```groovy
hotChocolate {
    compilerHome = file("$rootDir/../../HotChocolate/build/install/hotchocolate")
    sourceDir file('src/main/hc')
}
```

One `hcCompile<DirName>` task compiling every `.hc` file anywhere under
the directory (recursively — nested subdirectories, e.g. mirroring each
file's own `module` path, work exactly the same as a flat layout) as a
single shared, `extern`-free-between-them compile (the Gradle-plugin
side of the CLI's own `hc build <dir> <outDir>` — see "Multi-file
projects" above). Declaration order between files stops mattering
entirely once this is used — a real mod project's own `.hc` files can
reference each other directly, no `extern class Foo =
"already.compiled.Foo" { ... }` bridging needed for the project's *own*
compiled classes. Verified end-to-end against the real Minecraft mod:
switching its 9-file `hotChocolate { }` block (previously nine chained
`source(file(...))` entries, several needing hand-written `extern class`
bridges purely to reach each other's output) to one `sourceDir` removed
every one of those bridges, `javap` confirms the real mod's already-
public classes (`Aisletool`, `Copytool`, `Items`, ...) all kept their
exact same binary names and cross-package visibility, and `./gradlew
build` still succeeds.

**What this deliberately is not**: a replacement for Gradle, a
dependency resolver, or a Maven Central publisher — it's Gradle's own
composite-build (`includeBuild`) and plugin mechanisms, used the way
they're meant to be used. See `IDEAS.md` for why a from-scratch build
tool ("Marshmallow") was considered and declined; this plugin is the
actual right-sized version of that want.

**Resolved**: neither the compiler (`hotChocolate { version = "v0.1.6" }`)
nor the plugin itself (`plugins { id("hc") version "v0.1.6" }` + the
`eachPlugin` mapping) needs a local install or checkout anymore — see
"Zero-clone setup" above. `includeBuild` remains the right tool
specifically for working on HotChocolate's own compiler/plugin code,
not a requirement for using either one.

### Published on JitPack

The raw compiler jar (and the plugin's own jar, separately) *are* now
real, versioned, remotely-resolvable Maven artifacts — no local checkout
needed for these specifically:

```kotlin
dependencies {
    implementation("com.github.P-H-O-E-N-I-X-PackForge:HotChocolate:v0.1.5")
}
```

built on demand by JitPack from any pushed tag (`git tag vX.Y.Z && git
push origin vX.Y.Z`, then JitPack builds it the first time someone
requests it). Verified via a real `build.log` run, not just configured —
including catching and fixing a real bug before calling it done: the
root `build.gradle.kts` used to hardcode `group = "hc"` / `version =
"0.1.0"`, silently ignoring the `-Pgroup`/`-Pversion` JitPack passes in,
so the first tag actually published under the wrong coordinates despite
JitPack's own page advertising the right ones. Fixed by reading those
properties with local-dev fallbacks (`(findProperty("group") as
String?) ?: "hc"`), confirmed against a second real build.

**Known, accepted limitation**: Gradle's own "Multiple publications ...
will overwrite each other" warning shows up during the `hc-gradle-plugin`
module's publish step (`java-gradle-plugin`'s auto-created `pluginMaven`
publication collides in coordinates with one JitPack separately injects
for the same "java" component). It's real but non-fatal — the build
still succeeds and both modules still get correctly served under it,
confirmed via `build.log`. Two different fixes were tried and reverted
after real JitPack builds (not just local ones) showed each traded the
warning for something worse: applying `maven-publish` explicitly
silences it but silently drops this module from what gets served at all
(the early-detection "failure" that produces the warning turns out to
also be what triggers the JitPack codepath that packages every module
correctly); giving `pluginMaven` a distinct artifactId to dodge the
actual collision while keeping that codepath active fails outright
("Publication with name 'pluginMaven' not found") since whatever applies
`maven-publish` in that fallback isn't `java-gradle-plugin`'s own
internal application. Left alone, documented, not worth a third blind
attempt at outguessing JitPack's own undocumented internal packaging
heuristics for a cosmetic, non-fatal warning.

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

## Ideas backlog

Syntax sugar and features that came up, got weighed, and are worth
revisiting later but aren't built yet — see [IDEAS.md](IDEAS.md).

## Layout

```
src/main/kotlin/hc/
  lexer/    tokenizer
  ast/      AST node definitions
  parser/   recursive-descent parser
  sema/     types + move/borrow checker
  codegen/  ASM bytecode emitter
  Main.kt   CLI entry point
examples/          sample .hc programs
hc-gradle-plugin/  the `hc` Gradle plugin (see "Gradle plugin" above)
```
