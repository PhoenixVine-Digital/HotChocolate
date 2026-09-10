# Learning Hot Chocolate

This is a from-scratch tutorial for someone who has never written a line of
Hot Chocolate (HC) before. It assumes you already know how to program (in
anything — Java, Kotlin, Python, whatever) but explains every piece of HC's
own syntax and semantics from zero. If you already know Rust or Kotlin,
several sections will feel very familiar — HC borrows its ownership model
from Rust and its concise syntax from Kotlin, deliberately.

**Never programmed in anything before, at all?** See
[FIRST_LANGUAGE.md](FIRST_LANGUAGE.md) instead — it covers less ground but
explains programming concepts themselves (variables, functions, types) as
it goes, rather than assuming you already know them from another language.

For the *design rationale* behind these features (why they're scoped the way
they are, what's still missing, known limitations), see [ARCHITECTURE.md](ARCHITECTURE.md)
— this document teaches you to write HC; ARCHITECTURE.md explains why HC is
built the way it is. When in doubt about an edge case, ARCHITECTURE.md is
the more exhaustive reference. (For the short "what is this and why" pitch,
see [README.md](README.md) instead.)

**Status note**: Hot Chocolate's compiler recently moved from a hand-written
Kotlin implementation to a self-hosted one (written in HC itself, under
`selfhost/`). The self-hosted compiler is an explicitly scoped-down subset —
see the "Status" section near the top of [ARCHITECTURE.md](ARCHITECTURE.md)
for the current list of what works and what doesn't. Several sections below
(ownership enforcement, generics, `@serializable`) currently describe
intended behavior that isn't fully working yet; each says so where
that's the case.

## Table of contents

1. [Setup: building and running the compiler](#1-setup-building-and-running-the-compiler)
2. [Hello, world](#2-hello-world)
3. [Variables and basic types](#3-variables-and-basic-types)
4. [Functions](#4-functions)
5. [Control flow](#5-control-flow)
6. [Structs](#6-structs)
7. [Ownership: moves and borrows](#7-ownership-moves-and-borrows)
8. [Methods (`impl` blocks)](#8-methods-impl-blocks)
9. [Arrays](#9-arrays)
10. [Enums and `match`](#10-enums-and-match)
11. [Generics](#11-generics)
12. [Interfaces](#12-interfaces)
13. [Error handling](#13-error-handling)
14. [Nullability](#14-nullability)
15. [Talking to real Java code](#15-talking-to-real-java-code)
16. [`@serializable`](#16-serializable)
17. [Modules and multi-file projects](#17-modules-and-multi-file-projects)
18. [Where to go next](#18-where-to-go-next)

---

## 1. Setup: building and running the compiler

Clone the HotChocolate repo and build it once:

```bash
./gradlew installDist
```

This produces `build/install/hotchocolate/bin/hotchocolate(.bat)` — the real
CLI you'll use everywhere below. Two commands matter:

```bash
hotchocolate run my_program.hc              # compile + run immediately
hotchocolate build my_program.hc out_dir     # compile to .class files in out_dir
```

`run` also accepts a *directory* instead of a single file — every `.hc` file
found anywhere under it (recursively) gets compiled together as one program,
which is how a real multi-file project works (more on this in section 17).

If you're working inside this repo itself without having run `installDist`
yet, you can also do:

```bash
./gradlew run --args="run examples/hello.hc"
```

Everything below assumes you have a way to run a `.hc` file; pick whichever
of the two invocations above is convenient.

## 2. Hello, world

Create a file `hello.hc`:

```
fn main() {
    print("Hello, Hot Chocolate!");
}
```

Run it:

```bash
hotchocolate run hello.hc
```

`main()` is your entry point, exactly like Java/Kotlin/Rust. `print(...)`
is a built-in — it prints its argument followed by a newline, and accepts
`Int`, `Long`, `Float`, `Double`, `Bool`, or `String`.

## 3. Variables and basic types

```
fn main() {
    let name = "Rin";       // immutable — can't be reassigned
    var hp = 100;            // mutable — can be reassigned

    hp = hp - 30;
    print(name);
    print(hp);
}
```

- `let` declares an immutable binding. `var` declares a mutable one. There's
  no third option — pick whichever matches whether you actually reassign it.
- HC infers the type from the initializer in both cases above (`name` is
  `String`, `hp` is `Int`), but you can also write it explicitly:
  `let hp: Int = 100;`.

The built-in scalar types are:

| Type     | JVM equivalent | Notes |
|----------|-----------------|-------|
| `Int`    | `int`           | 32-bit |
| `Long`   | `long`          | 64-bit — literal needs an `L` suffix: `123L` |
| `Float`  | `float`         | 32-bit |
| `Double` | `double`        | 64-bit |
| `Bool`   | `boolean`       | `true`/`false` |
| `String` | `java.lang.String` | HC's one built-in reference type — see section 15 for how it reaches real `String` methods |

There's no `Char` type (a real, documented gap — see ARCHITECTURE.md's Java
interop section for the workaround: `.substring(i, i+1)` instead of
`.charAt(i)`).

**One important difference from Java/Kotlin**: `Int op Long`, `Int op
Float`, etc. never implicitly convert. `1 + 1.0` is a compile error — cast
explicitly with `as`:

```
let x = 5;
let y = 2.5;
let z = (x as Double) + y;
```

## 4. Functions

```
fn add(a: Int, b: Int) -> Int {
    return a + b;
}

fn greet(name: String) {
    print("Hello, " + name + "!");
}

fn main() {
    print(add(2, 3));
    greet("world");
}
```

- Every parameter needs an explicit type — no inference on parameters.
- A function with no `-> Type` returns nothing (like Kotlin's `Unit`/Java's
  `void`) — just omit the arrow.
- `return` is required (no implicit "last expression is the return value"
  the way Rust has for whole function bodies — see section 5 for the
  smaller-scoped `if`/`match`-as-expression feature that *does* work that
  way for single branches).

## 5. Control flow

`if`/`while`/`for` all look like C-family languages, with no parentheses
around the condition and mandatory braces:

```
fn classify(hp: Int) -> String {
    if hp <= 0 {
        return "dead";
    } else if hp < 30 {
        return "critical";
    } else {
        return "healthy";
    }
}

fn main() {
    var i = 0;
    while i < 3 {
        print(i);
        i = i + 1;
    }

    // Range loop: 0, 1, 2, 3, 4 (end is exclusive)
    for n in 0..5 {
        print(n);
    }

    // Array loop
    let names = ["Rin", "Kai"];
    for name in names {
        print(name);
    }
}
```

### `if`/`match` as values

`if` and `match` (see section 10) can *also* be used as expressions — not
just statements — as long as each branch is exactly one expression:

```
fn main() {
    let a = 3;
    let b = 7;
    let smaller = if a < b { a; } else { b; };
    print(smaller);
}
```

Note the semicolons *inside* the braces — each branch is still an ordinary
`{ ... }` statement block under the hood, just restricted to holding exactly
one bare expression statement. This isn't full "last expression in any
block is its value" the way Rust does it (that would need a bigger grammar
change) — it's closer to Kotlin's `if` expression. A branch with more than
one statement, or a missing `else`, is a compile error.

## 6. Structs

A `struct` is a plain data type — think a Kotlin `data class` without the
generated methods, or a Rust `struct`:

```
struct Player {
    name: String,
    hp: Int,
}

fn main() {
    let p = Player { name: "Rin", hp: 100 };
    print(p.name);
    print(p.hp);
}
```

- Construct with `TypeName { field: value, ... }` — every field required,
  no default values, no positional construction.
- Fields are read with plain `.field` access, same as any C-family language.
- A struct compiles to a real, plain JVM class with public fields — nothing
  magic happens at the bytecode level.

## 7. Ownership: moves and borrows

**Not enforced by the compiler currently in this repo.** The
self-hosted compiler's checker (`selfhost/checker/Checker.hotc`)
explicitly does not implement move/borrow checking yet — verified
directly: the "use after move" example below currently compiles and
runs without the error it describes. `&`/`&mut` borrow syntax does
parse and work mechanically (a `&mut` parameter really can mutate the
caller's data), but none of the compile-time safety guarantees below
are actually checked today. This is the single biggest gap between
this document and the current compiler — see ARCHITECTURE.md's "Status"
section.

This is the one part of HC that's genuinely different from Java/Kotlin, so
it's worth slowing down for. **Every non-`struct` value here is `Copy`**
(`Int`, `Long`, `Float`, `Double`, `Bool`, `String`) — passing them around
is always a plain copy, nothing to think about. **Every `struct` value is
move-only**, exactly like Rust:

```
struct Player { name: String, hp: Int }

fn take(p: Player) {
    print(p.hp);
}

fn main() {
    let p = Player { name: "Rin", hp: 100 };
    take(p);      // p is MOVED into take() here
    print(p.hp);  // compile error: use of moved value 'p'
}
```

Passing a struct *by value* transfers ownership — the caller can't use it
afterward. This is caught at compile time, not runtime. If you just want to
let a function look at a struct without taking it, pass a **borrow**
instead, with `&`:

```
fn take(p: &Player) {
    print(p.hp);
}

fn main() {
    let p = Player { name: "Rin", hp: 100 };
    take(&p);      // borrowed, not moved
    print(p.hp);   // fine -- p is still usable
}
```

- `&x` borrows `x` without moving it — the callee can read it, but the
  caller keeps ownership.
- `&mut x` borrows it *mutably* — the callee can modify it through the
  reference. The variable being borrowed must have been declared `var`
  (mutable), and only one `&mut` borrow (or any number of plain `&`
  borrows, but not both at once) can be alive at a time — this is checked
  at compile time, the same "no aliased mutation" rule Rust enforces.

```
fn heal(p: &mut Player, amount: Int) {
    p.hp = p.hp + amount;
}

fn main() {
    var p = Player { name: "Rin", hp: 50 };
    heal(&mut p, 20);
    print(p.hp); // 70
}
```

**Why this exists**: it catches a real class of bugs — accidentally
mutating something two different pieces of code both thought they owned —
at compile time instead of at a mystery runtime moment. If you're used to
Java/Kotlin, the adjustment is: decide up front whether a function needs to
*own*, *read*, or *mutate* a struct argument, and spell that out with
plain/`&`/`&mut`.

One more piece: a struct can declare a destructor with `impl StructName {
fn drop(&mut self) { ... } }`, which the compiler calls automatically when
an owned value falls out of scope (in reverse declaration order, same as
Rust). See `examples/drop.hc` if you need this — most day-to-day HC code
doesn't.

## 8. Methods (`impl` blocks)

```
struct Player {
    name: String,
    hp: Int,
}

impl Player {
    fn damage(&self, amount: Int) -> Int {
        return self.hp - amount;
    }

    fn describe(self) -> String {
        return self.name;
    }
}

fn main() {
    let p = Player { name: "Rin", hp: 100 };
    print(p.damage(30));   // 70
    print(p.describe());   // "Rin" -- this MOVES p (self, not &self)
}
```

- `&self` borrows the receiver (like most everyday methods) — `&mut self`
  borrows it mutably (needed to modify `self.field`) — bare `self` (no `&`)
  *moves* the receiver, consuming it (rare, but sometimes exactly what you
  want, e.g. a "convert this into that" method).
- Call methods with ordinary `.` syntax; under the hood every method
  desugars to a static function taking `self` as its first argument — pure
  syntax sugar, not a different mechanism from a top-level function.

## 9. Arrays

```
fn sum(arr: &[Int]) -> Int {
    var total = 0;
    var i = 0;
    while i < arr.length {
        total = total + arr[i];
        i = i + 1;
    }
    return total;
}

fn main() {
    let nums = [1, 2, 3, 4, 5];
    print(nums.length);
    print(sum(&nums));

    var board = [0; 5];   // [value; count] -- 5 zeros
    board[2] = 99;
    print(board[2]);
}
```

- `[T]` is the array type; `&[T]` borrows it (the usual way to pass an
  array into a function without transferring ownership).
- `arr.length` is a real field read (not a method call) — the one field
  every array exposes.
- `[value; count]` builds a fixed-size array of `count` copies of `value`.

## 10. Enums and `match`

An `enum` is a closed set of variants, each optionally carrying its own
named fields — a tagged union, not a class hierarchy:

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

fn main() {
    print(area(Circle { radius: 2 }));
    print(area(Square { side: 3 }));
    print(area(Point));
}
```

- Construct a variant with the same `Variant { field: value }` syntax a
  struct uses; a variant with no fields is constructed bare (`Point`, no
  braces).
- `match` destructures by variant name and field position — `Circle {
  radius }` binds a fresh local `radius`. No nested patterns, no
  literal-value matching (`Circle { radius: 5 } => ...` doesn't work).
- **Exhaustiveness is enforced**: every variant needs its own arm, or a `_`
  wildcard arm (which must come last) — a `match` missing a variant with no
  wildcard is a compile-time error naming exactly what's missing.
- Like `if`, `match` also works as a value, following the same "each arm is
  exactly one expression" rule from section 5:

```
fn area(s: Shape) -> Int {
    return match s {
        Circle { radius } => { radius * radius * 3; }
        Square { side } => { side * side; }
        Point => { 0; }
    };
}
```

(No commas between match arms, in either form — that's not a typo above.)

## 11. Generics

**One remaining gap against the compiler currently in this repo**:
`impl<T> Box<T> { ... }` (generic `impl` blocks, used just below) fails
to parse (`unexpected token '<'`) — a plain top-level
`fn identity<T>(x: T) -> T` parses fine on its own. (A separate runtime
bug — bare generic struct/enum literals like `Box { value: 42 }`
failing with `unknown struct or enum variant` — was fixed 2026-09-02;
see ARCHITECTURE.md's "Status" section.)

Generics are monomorphized (like Rust, unlike Java) — every concrete
instantiation gets its own specialized, zero-cost copy generated at compile
time, not a single type-erased implementation:

```
struct Box<T> {
    value: T,
}

impl<T> Box<T> {
    fn get(&self) -> T {
        return self.value;
    }
}

fn identity<T>(x: T) -> T {
    return x;
}

fn main() {
    let a = identity(5);          // instantiates identity_Int
    let s = identity("hi");       // instantiates identity_String

    let boxInt = Box { value: 42 };
    print(boxInt.get());

    let boxPlayer = Box { value: Player { name: "Rin", hp: 100 } };
    print(boxPlayer.get().hp);
}

struct Player { name: String, hp: Int }
```

Type arguments are inferred from how you call/construct — you never write
`identity<Int>(5)` yourself. You can also bound a type parameter to require
an interface (`fn heal<T: Damageable>(x: &mut T)`) — see ARCHITECTURE.md's
"Bounded generics" section once you need it.

## 12. Interfaces

```
interface Describable {
    fn name(&self) -> String;
    fn tag(&self) -> String {
        // default method -- implementers can override or inherit this
        return "[thing] " + self.name();
    }
}

struct Player { pname: String, hp: Int }

impl Describable for Player {
    fn name(&self) -> String {
        return self.pname;
    }
}

fn announce(d: &dyn Describable) {
    print(d.tag());
}

fn main() {
    let p = Player { pname: "Rin", hp: 100 };
    print(p.tag());        // static dispatch -- the concrete type is known
    announce(&p);           // dynamic dispatch through &dyn
}
```

- `&dyn InterfaceName` is how you accept "any type implementing this
  interface" without knowing which one at compile time — real JVM
  `invokeinterface` dispatch under the hood.
- An interface method with a body is a *default* method — implementers
  don't have to override it.
- A `sealed interface` additionally requires every implementer to be known
  at compile time (declared in the same compile), which unlocks matching
  `&dyn` values by concrete type with `match` the same way an enum's
  variants are matched — see the ARCHITECTURE.md's "Sealed interfaces" section.

## 13. Error handling

Real JVM exceptions, via `try`/`catch`/`throw` — no HC-native exception
type, always a real `extern class` (see section 15):

```
extern class NumberFormatException = "java.lang.NumberFormatException" {
    fn getMessage(&self) -> String;
}
extern class Integer = "java.lang.Integer" {
    static fn parseInt(s: String) -> Int;
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

For your own fallible functions (not wrapping a throwing Java call), prefer
the self-hosted `Result<T, E>` over throwing:

```
fn safe_parse(s: String) -> Result<Int, String> {
    try {
        return Result::Ok { value: Integer::parseInt(s) };
    } catch (e: NumberFormatException) {
        return Result::Err { error: "bad number: " + s };
    }
}

fn main() {
    match safe_parse("7") {
        Ok { value } => { print("parsed: {value}"); }
        Err { error } => { print("error: {error}"); }
    }
}
```

`Result<T, E>` is just a regular generic enum from HC's small self-hosted
prelude (alongside `Option<T>`) — nothing special about it beyond what
section 10/11 already covered.

(You've now seen `"text {expr} more text"` a few times — that's **string
interpolation**: any `{...}` inside a string literal embeds an expression's
value, stringified inline. It's sugar over plain `String + String`
concatenation, nothing more.)

## 14. Nullability

**Not implemented in `selfhost/` today**: `Type?` fails to parse
(`unexpected token '?'`) — verified via `examples/nullable.hc`. This
section describes the design as built for the retired Kotlin compiler.

HC-native values are never null — `Int`, `String`, `Player`, etc. can't be
null, period. The one place null is a real fact of life is talking to
existing JVM APIs, so nullability is scoped to exactly that boundary:

```
extern class SecurityManager = "java.lang.SecurityManager" {
    fn toString(&self) -> String;
}
extern class System = "java.lang.System" {
    static fn getSecurityManager() -> SecurityManager?;
}

fn describe() -> String {
    let sm = System::getSecurityManager();
    if sm == null {
        return "none installed";
    }
    // sm is narrowed to non-nullable SecurityManager here on --
    // no cast needed to call a method on it.
    return sm.toString();
}
```

- `Type?` (only on an `extern class` type, or the native `String?`) marks a
  value as possibly null.
- Comparing against `== null`/`!= null` in a guard clause narrows the
  variable to non-nullable for the rest of that branch — calling a method
  on a still-possibly-null value without checking first is a compile
  error.

## 15. Talking to real Java code

`extern class` declares the trusted shape of an already-compiled JVM class
— no reflection, no bytecode introspection, you just tell the compiler
what's there:

```
extern class Random = "java.util.Random" {
    fn new() -> Self;
    fn nextInt(self, bound: Int) -> Int;
}

extern class JMath = "java.lang.Math" {
    static fn max(a: Int, b: Int) -> Int;
}

fn main() {
    print(JMath::max(3, 9));       // static call: Alias::method(...)

    let r = Random::new();          // constructor
    print(r.nextInt(6));            // instance call: value.method(...)
}
```

- `fn new(...) -> Self;` declares a constructor, called as `Alias::new(...)`.
- A `static fn` is called `Alias::method(...)`; an instance method takes a
  bare `self` param in the declaration and is called `value.method(...)`.
- If a wrong signature is declared, it compiles fine and fails at *runtime*
  (`NoSuchMethodError`/similar) — same trust model as any FFI declaration
  file in any language. There's no checking against the real class.
- **Property-style sugar** (unverified against the compiler currently in
  this repo — no getter-fallback handling was found in
  `selfhost/checker/Checker.hotc` during an audit of this doc; treat as
  likely not ported): if `recv.field` doesn't match a declared field,
  the compiler falls back to trying `recv.getField()`/`recv.isField()` (a
  Java bean getter) before giving up — `enemy.health` instead of
  `enemy.getHealth()`. Read-only; a real declared field always wins if both
  exist.
- HC's native `String` can call real `java.lang.String` methods directly
  once declared via `extern class JString = "java.lang.String" { ... }` —
  see `examples/string_bridge.hc`.

## 16. `@serializable`

**Not implemented in `selfhost/` today**: `@` doesn't parse as an
annotation token at all yet — verified via `examples/test_serializable.hotc`,
which fails with `unexpected token '@'`. This section describes the
design as built for the retired Kotlin compiler.

For a struct that just needs to read/write itself to a Forge-style
`FriendlyByteBuf` (or anything else with matching `writeX`/`readX` method
names), `@serializable` generates real `encode`/`decode` functions from the
field list instead of you hand-writing them:

```
// Real Minecraft/Netty return types matter here, not just parameter types -- the JVM matches a
// method by its *whole* descriptor, return type included, so a wrong one compiles fine and only
// fails with NoSuchMethodError once actually called. `writeInt` really returns the underlying
// `io.netty.buffer.ByteBuf` (fluent chaining, inherited from a different type than
// `FriendlyByteBuf` itself); `writeUtf` returns `FriendlyByteBuf`. Both discarded at every call
// site below either way -- this is purely about matching the real signature, not about using the
// return value.
extern class NettyByteBuf = "io.netty.buffer.ByteBuf" {}

extern class FriendlyByteBuf = "net.minecraft.network.FriendlyByteBuf" {
    fn writeInt(self, v: Int) -> NettyByteBuf;
    fn readInt(self) -> Int;
    fn writeUtf(self, v: String) -> FriendlyByteBuf;
    fn readUtf(self) -> String;
}

@serializable
pub struct ClipboardPacket {
    id: Int,
    data: String,
}

fn main() {
    let packet = ClipboardPacket { id: 7, data: "hello" };
    var buf = FriendlyByteBuf::new();
    encode(&packet, &mut buf);
    let restored = decode(&mut buf);
    print(restored.id);
    print(restored.data);
}
```

Field types are limited to `Int`/`Long`/`Float`/`Double`/`Bool`/non-nullable
`String` for now, each mapped to a real `writeX`/`readX` call in
declaration order. An `extern class` literally named `FriendlyByteBuf` must
be declared somewhere in the same compile.

## 17. Modules and multi-file projects

A file can open with a `module a.b.c;` line (dotted, JVM-package-style),
governing that file's `pub`/visibility boundary. Multiple files can declare
different modules in the same compile — there's no whole-program single
package the way `package` normally implies.

```
module com.example.game;

pub struct Player { name: String, hp: Int }

pub fn make_player(name: String) -> Player {
    return Player { name: name, hp: 100 };
}
```

**Directory-mode compilation** is how a real multi-file project works: pass
a *directory* to `hotchocolate run`/`build` instead of a single file, and
every `.hc` file anywhere under it (recursively — nested subdirectories are
fine) compiles together as one flat program. There are no `import`/`use`
statements — every top-level `struct`/`fn`/`interface`/`enum`/`extern
class` in the whole directory shares one global namespace, visible to every
other file in the same compile automatically. `pub` on a `fn`/`static`
still matters *across modules* (a non-`pub` fn in module `a` can't be
called from module `b`), but no file needs to "import" another file to see
its declarations.

If you're building a real project (e.g. a Minecraft mod) rather than a
one-off script, see ARCHITECTURE.md's "Gradle plugin" section for wiring a
`sourceDir` into your build so `.hc` sources compile automatically
alongside your Java.

## 18. Where to go next

- **[ARCHITECTURE.md](ARCHITECTURE.md)** — the full reference. Every feature has a
  dedicated section going deeper than this tutorial does, plus the design
  rationale behind each scope cut.
- **[examples/](examples/)** — `.hc` programs, one concept per file,
  organized by feature (`generics.hc`, `interfaces.hc`, `enums.hc`,
  `arrays.hc`, `error_handling.hc`, `nullable.hc`, ...). When in doubt
  about exact syntax, find the matching example file and read it. **Not
  all of them currently compile and run** against the self-hosted
  compiler in this repo — several exercise features called out
  throughout this tutorial and in ARCHITECTURE.md's "Status" section; the
  basics (`hello.hc`, `mut.hc`, `loops.hc`, control flow, plain
  structs, `enums.hc`, `interfaces.hc`, `sealed.hc`, `generics.hc`,
  `floats.hc`, multi-catch `try`/`catch`, directory/multi-file
  compilation) do work.
- **[examples/battle/](examples/battle/)** — a small interactive
  dungeon-crawl demo (`./gradlew playBattle`) that ties together structs,
  interfaces, `&dyn`, generics, and enums in one real program instead of
  isolated snippets.
- **[IDEAS.md](IDEAS.md)** — features that don't exist yet, with the
  reasoning behind why they're scoped the way they'd be if built. Worth a
  skim if you hit something HC can't do yet — there's a good chance it's
  already been thought through.

Not covered in this tutorial (deliberately, to keep it beginner-focused —
see ARCHITECTURE.md when you need them): `arena struct`/off-heap buffers,
subclassing a real Java class with `extends`, composition delegation
(`by field`), bounded generics (`T: Trait`), `extern interface`, and
global `static` state.
