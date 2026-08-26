# Documentation tooling ideas

Split out of `IDEAS.md` — these all build directly on the structured
`///` doc-comment feature that already shipped (see `IDEAS.md`'s own
"Structured `///` doc comments" entry, and the README) rather than
being a separate, unrelated wishlist. Kept here on its own because it's
a coherent theme worth reading as a group: what `hc doc` could become
next, roughly in the order it's worth building.

Same rules as `IDEAS.md`: enough context that picking one up later
doesn't need re-deriving the reasoning from scratch. Move an entry into
the README when it actually gets built; delete it if it turns out to be
a bad idea on reflection.

## Cheap, build these first — direct payoff from what's already shipped

### Doc coverage report (`hc doc --check`)

```
$ hc doc --check src/

Documentation coverage

Functions     91%  (41/45)
Structs      100%  (12/12)

Missing documentation:
  src/render/Renderer.hc:42  fn draw_mesh(...)
  src/world/World.hc:91      struct ChunkManager
```

Nearly free on top of what already shipped: every top-level `fn`/
`struct` the parser sees either got a `DocComment` attached or didn't
(`Parser.docComment()`), so "coverage" is just `documented / total`
over the same declaration list `hc doc` already walks to render
`api.md` -- no new AST, no new checker pass, just a different report
over data that's already there. `--minimum N` (exit nonzero below a
threshold) makes this usable as a CI gate the same way a coverage
threshold works for tests. Same **top-level `fn`/`struct` only** scope
the doc-comment feature itself shipped with -- once `impl` methods
carry doc comments (see below), coverage should count those too.

### `impl` method doc comments + interface doc inheritance

```
interface Renderable {
    /// Draws this object to the screen.
    fn render(&self);
}

impl Renderable for Player {
    /// Renders the player and their equipped armor.
    fn render(&self) { ... }
}
```

The doc-comment feature's own shipped-scope note already flags this
gap: "an `impl` block's own methods don't carry doc comments yet...
no natural extension point to wire up without more design than the
feature needed for v1." The actual design isn't big: `ImplBlock.methods`
and `InterfaceMethodDecl` both need a `docComment: DocComment?` field
(mirroring `FnDecl`'s), `Parser.docComment()` needs calling at both
sites, and `hc doc`'s renderer needs one merge rule for the interesting
case -- a struct's `impl` method with no doc comment of its own inherits
the interface method's, one *with* its own doc comment keeps it (shown
alongside "implements `Renderable`" as an inline note, not silently
replacing the interface's description). No new resolution machinery:
`@see` targets inside an inherited doc comment still resolve exactly
like `Checker.checkDocSees` already does for top-level ones.

### Ownership shown for free in generated docs

```
fn attack(attacker: &Player, target: &mut Enemy, weapon: Weapon) -> Int
```

renders as something like:

```
attack(attacker, target, weapon) -> Int
  attacker: &Player   (borrowed)
  target:   &mut Enemy (borrowed, mutably)
  weapon:   Weapon     (consumed)
```

Every other doc-focused idea here needs a human to write a `///` line
before it says anything. This one doesn't: `Param.type.isRef`/`isMut`
already fully describe whether a param is borrowed, mutably borrowed,
or consumes its argument -- `hc doc`'s renderer just needs to print
that alongside the type instead of only the bare `TypeRef`. This is
also the one differentiator a generated HC doc page has that a
generated *Java* doc page structurally can't: ownership is real,
checked information here, not a convention or a comment. Cheap (a
rendering-only change, no new AST) and it's the one thing worth
shipping even if nothing else on this list ever gets built.

### Monomorphization visibility in generated docs

```
fn identity<T>(x: T) -> T { return x; }
```

renders with a footer noting which concrete instantiations actually
exist in the compiled program:

```
identity<T>(x: T) -> T
  instantiated as: identity_Int, identity_String
```

`Checker.fnInstances`/`structInstances` already cache exactly this
(mangled name -> concrete type args, built during monomorphization) --
`hc doc` just needs to read them back per generic template instead of
only rendering the unresolved `<T>` signature. Useful for exactly the
question a systems-flavored generic raises that a Java generic doesn't:
"is this actually being instantiated anywhere, and with what?" A
generic template with zero instantiations (dead code, or a library
entry point nothing in this compile happens to call yet) is visibly
distinguishable from one used everywhere, for free.

## Moderate effort, real payoff

### Verified-vs-trusted extern docs (`hc doc --verify-extern`)

An `extern class` declaration is trusted, not checked, against the
real JVM class it names -- a wrong signature compiles clean and only
fails at runtime (`NoSuchMethodError`/`IncompatibleClassChangeError`),
the single most common real bug category any Forge/Minecraft-interop
`.hc` project hits (this project's own port history has a long list of
exactly this). `ClasspathReflector` already exists and already does
real signature verification for the `use { }`/lazy-resolution forms
(`Checker.resolveExternMethods`) -- this is the same machinery, just
run in a mode that additionally checks *explicit*-signature `extern
class` blocks (the form that's never verified today) against
`--classpath`, and has `hc doc` annotate each declaration in the
rendered output: a verified method gets a quiet checkmark, a mismatch
gets a loud warning showing the real signature next to the declared
one. Doesn't change what compiles -- `extern class` staying
trust-by-default is deliberate (no classpath needed for a program that
doesn't ask for one) -- this is strictly an opt-in, additional
confidence pass surfaced through documentation rather than a compile
error.

### Compiled/verified `@example` doctests, revisited

The doc-comment feature's shipped-scope note lists this as real,
deliberate phase-2 material ("no compiled/verified `@example` blocks"),
and that's still the right call for v1 -- but it's worth re-scoping
because it's cheaper than "build a test runner" sounds: `hc run` already
compiles a program to a real classfile and executes it in a subprocess
(`Main.kt`'s existing pipeline). A doctest is just that same pipeline
pointed at synthetic input -- take a fenced `` ```hc ``` `` block out of
an `@example` tag, wrap it in a throwaway `fn main() { ... }`, run it
through the exact same `compile`+launch path `hc run` already has, and
report the fenced block's own source line if it fails. `hc test-docs`
wouldn't need a new compiler pass, a new execution model, or new
AST -- just a new CLI entry point that re-slices existing `DocComment`
data into a synthetic `Program` and calls the same `compileProgram`/
launch code `run` already calls. The real design question worth
settling before picking this up: what an `@example` needs to assert
against (a bare `assert(...)` call needing a real `assert` builtin
that doesn't exist yet, vs. just "exits zero / doesn't throw").

## Blocked on a bigger feature landing first

### Data-flow ("reads/writes/requires") documentation for `system`s

```
MovementSystem

Reads:  Velocity
Writes: Transform
```

The documentation-side payoff of `IDEAS.md`'s "ECS with
ownership-derived system scheduling" entry, not a separate feature to
design — genuinely blocked on it, not just easier after it. The only
reason this could ever be more than a manually-written convention (a
doc comment claiming "reads Velocity" with nothing checking it's
actually true, which is worse than not documenting it at all) is that
a `system`'s `&`/`&mut` component params are real, checked information
the *scheduler* already has to derive for its own purposes. Once that
exists, `hc doc` rendering it back out is nearly free — the same
"ownership shown for free" reasoning as the plain-function idea above,
just one level up at the system-vs-component granularity instead of
fn-vs-param. Tried scoping this down to work *without* a real ECS (e.g.
some annotation convention layered onto plain structs/fns) and it
doesn't hold up: without the scheduler's own read/write conflict
analysis backing it, "documenting data flow" degenerates into exactly
the unchecked, driftable claim this whole doc sits down to avoid.
Revisit alongside `IDEAS.md`'s ECS entry, not before it.

## Convention, not a feature (no compiler change needed)

### `@why` / design-rationale sections

```
/// Calculates machine power consumption.
///
/// @why Kept here instead of in the machine implementation because
/// multiple machine types use the same calculation.
```

Doesn't need a new tag type, a new checker pass, or anything else --
`@why`/`@note`-style headings are just prose under an existing `///`
block, and `hc doc`'s renderer already passes untagged/custom-tagged
text through. Worth writing down as a project style-guide convention
("document *why*, not just *what*, for anything non-obvious") rather
than proposing it as a backlog item with implementation cost, since it
has none.
