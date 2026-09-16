# List comprehensions — design doc

Spun out of a direct proposal (a real, concrete syntax sketch: `[expensive(x) for x in values if
x.isValid()]`) the same way `ECS_IDEAS.md`/`COLLECTIONS_IDEAS.md` were spun out once each became a
real, actively-worked feature — this doc is where the design gets worked through before any
`selfhost/` code changes. `COLLECTIONS_IDEAS.md`'s own real empty-`Vec<T>` prerequisite (a genuine
`[]` array literal, scoped to a concrete-type struct-literal-field context) already landed
specifically because THIS feature needed it: a comprehension's own result can legitimately be
empty (the filter rejects every element), and there was previously no way to build an empty
`Vec<T>` at all.

## Why "one specialized loop," not desugaring to `.filter()`/`.map()`

The proposal itself already leaned this way, and it's the right call for a concrete reason: `Vec
<T>` has no `filter`/`map` methods today (only `push`/`get`/`set`/`length`/`pop`/`clear` — checked
directly against `stdlib/vec.hotc`). Building those as real, closure-taking generic methods would
be substantial NEW surface on its own (closures-as-arguments flowing through `Vec<T>`'s existing
single-type-param generics, a second generic type parameter for the transformed element type on
`map` specifically, ...) — and even if built, chaining them would allocate an intermediate `Vec`
for every stage (`filter` builds one, `map` builds another), exactly the "iterate → allocate →
iterate → allocate" cost the original proposal called out. Compiling a comprehension straight to
ONE real loop — no intermediate collection, no closure indirection at the JVM level, everything
inlined the same way `gen_string_interp` already builds a `StringBuilder` chain via direct bytecode
within a single expression's own codegen — is both less NEW machinery to build and strictly
faster. This matches the project's own repeated preference this session: comprehensions become a
NEW dedicated AST node with its OWN codegen, not sugar that expands into other AST nodes.

## Language surface (starting point — see "Open questions" below)

```
let result = [expensive(x) for x in values if x.isValid()];
let squares = [x * x for x in numbers];
let evens = [x for x in numbers if x % 2 == 0];
```

**Deliberately NOT included in v1** (per explicit direction — "keep the grammar boring and
predictable," and this project's own established discipline of shipping the narrow, real case
first): multiple `for` clauses (`for x in xs for y in ys`, a real cartesian product), tuple
results, and nested comprehensions. A single `for` clause with an optional trailing `if` is the
entire v1 surface. Revisit only if a real caller genuinely needs more, matching how `HashMap2`'s
own `Eq` requirement, say, only got built once there was a real, stated need for it.

## Architecture

### AST — one new expression node, not a desugaring

```
Comprehension {
    result_expr: Expr,
    var_name: String,
    iter_expr: Expr,
    cond: Option<Expr>,
}
```

A genuinely new `Expr` variant (recursive `Expr`/`Option<Expr>` fields are already proven
throughout `Ast.hc` — `Binary`, `If.else_body`, etc. — no new machinery needed just to declare
this). Lives in EXPRESSION position (`let result = [ ... ];`, a call argument, a struct-literal
field value, anywhere an `Expr` is legal) — NOT lowered to a sequence of `Stmt`s at parse time,
since this language has no block-expression/IIFE mechanism to embed statements inside an arbitrary
expression position. This is exactly why it needs its own codegen (below) rather than a source-to-
source desugar into an ordinary `for` loop + `push` calls.

### Parser

`[` already dispatches to `array_lit`/`array_repeat` on seeing a value expression first (see
`Ast.hc`'s own `array_lit` header, just updated for the real empty-`[]` case). A comprehension
needs its own lookahead: after parsing the first expression (`result_expr`), if the next token is
`for` (not `,`/`;`/`]`), parse `for var_name in iter_expr`, then an optional `if cond`, then `]` —
mirroring the EXACT three-way branch `array_lit` already has for `[e1, e2, ...]` vs `[value;
count]`, just adding a fourth shape. `for`/`in`/`if` are all already reserved keywords (the
ordinary `for`/`while`-loop and `if`-statement grammar already uses them), so no new lexer tokens
are needed.

### Checker

`check_comprehension(result_expr, var_name, iter_expr, cond) -> Ty`, mirroring `check_for`'s own
shape closely (`selfhost/checker/Checker.hotc`):
- Push a real scope (matching `check_for`'s own `push_scope`/`pop_scope`), register `var_name`
  with the iterable's own element type, check `cond` (if present, must be `Bool`) and
  `result_expr` inside that scope, then pop it.
- The comprehension's own result type is `"[" + ty_name(result_expr's type) + "]"`'s WRAPPED
  form — concretely, `Vec$<ResultType>` (a real, concrete mono struct name, `ensure_instantiated`d
  immediately since the element type is always fully concrete by the time a comprehension is
  checked, never itself generic-and-unresolved).
- **Real open question, not yet answered**: `check_for`'s own existing `arr_expr` handling ONLY
  accepts a raw array type (`[T]`) as the iteration source today — `for x in someVec` doesn't
  compile at all currently, only `for x in someVec.data` (reaching into the raw backing array
  field directly) does. Does a comprehension's own `iter_expr` inherit that SAME narrow scope
  (simplest, reuses `check_for`'s own logic close to verbatim), or does it ALSO directly accept a
  `Vec<T>` (nicer ergonomics matching how `Vec<T>` is the primary collection type basically
  everywhere else in stdlib work this session, but real additional scope — see "Open questions").

### Codegen

`gen_comprehension` builds the result `Vec` INLINE, entirely within this one expression's own
codegen (no separate synthesized `FnDecl`/`Stmt` list the way ECS's `synthesize_*_ecs` functions
build real top-level driver code — this is closer to `gen_string_interp`'s own "emit a real
sequence of bytecode instructions that leaves one value on the stack" shape):
1. Emit the EMPTY result `Vec$<ResultType>` directly (`NEW`/`DUP`/`<init>` with `data` set via the
   real `SIPUSH 0` + `NEWARRAY`/`ANEWARRAY` sequence `Codegen.hotc`'s own struct-literal field
   codegen just gained for the empty-`[]` case, `len: 0`) — this is the concrete payoff of that
   prerequisite landing first: the comprehension's own result type is ALWAYS fully concrete by
   codegen time, so this is exactly the "known concrete array type" case that fix already handles,
   not the still-unsolved fully-generic one.
2. Store the fresh, empty result `Vec` into a real local slot (same "unregistered temp slot" trick
   `ArrayLit`'s own codegen already uses to sequence `NEW`/`DUP`/`<init>` against per-element
   stores).
3. Emit a REAL loop over `iter_expr` — reusing `gen_stmt`'s own existing `For` bytecode shape
   (index-based iteration + array-length bound check + element load) rather than inventing a
   second array-iteration bytecode pattern from scratch.
4. Inside the loop body: if `cond` is present, emit it and skip to the next iteration when false
   (a real conditional branch, same shape an ordinary `if` inside a `for` body already compiles
   to); otherwise, evaluate `result_expr` and call the result `Vec`'s own real `push` method
   (`INVOKEVIRTUAL`, `Vec$<ResultType>#push`, already a real, existing, monomorphized method once
   `ensure_instantiated` ran).
5. Load the result `Vec` back from its temp slot as the comprehension's own overall value.

No new low-level bytecode primitives needed anywhere in this sketch — every individual piece
(empty-array construction, indexed loop, conditional branch, `Vec::push` call) already has real,
proven codegen elsewhere in this file; the only genuinely new work is sequencing them together
under one new `Expr` variant's own `gen_expr` arm.

## Explicitly deferred (not silently dropped — tracked here)

- Multiple `for` clauses / cartesian products (`for x in xs for y in ys`).
- Tuple results (this language has no tuple type at all today — a separate, much bigger
  prerequisite than anything comprehensions themselves would need).
- Nested comprehensions (`[[y for y in row] for row in grid]`).
- The ECS-flavored extension floated in the original proposal (`[entity for entity in world if
  entity.has<Health>()]`) — genuinely interesting given this project's own ECS/codegen work, but a
  separate design question: `world` isn't a `Vec<T>`/array at all (it's the opaque, archetype-
  backed `World` struct `stdlib/ecs.hotc` defines), so iterating it would need an entirely
  different `iter_expr` shape than anything this doc's own architecture section covers. Worth its
  own follow-up once plain-collection comprehensions are proven, not attempted here.
- A `Set`/`Map`-shaped comprehension (`{x for x in xs}`, `{k: v for k, v in pairs}`) — this doc's
  whole architecture assumes a `Vec<T>` result; a hashed-collection-shaped comprehension would
  need its own separate design pass once `HashMap`/`HashMap2`/a future `HashSet` are the target,
  not attempted here.

## Open questions

1. **Comprehension source: raw array only, or also `Vec<T>` directly?** See the Checker section's
   own header for the real, existing asymmetry this raises (`for x in someVec` doesn't compile
   today at all). Reusing `check_for`'s exact existing scope (raw arrays only) is the lower-risk,
   smaller-diff option; accepting `Vec<T>` directly is the more ergonomic one (matches how almost
   every other real example in this codebase already reaches for `Vec<T>` over a raw array) but
   needs the comprehension's own codegen to ALSO support index-based `Vec::get`/`length` iteration
   as a genuinely separate code path from the array-iteration one, roughly doubling the codegen
   surface for comparatively small ergonomic gain. Leaning toward raw-array-only for v1, given the
   project's own repeated "ship the narrow case first" pattern — but a real pick, not resolved yet.
2. **Verb bikeshed**: is `result_expr for var_name in iter_expr if cond` the right clause order
   (Python's own), or would `for var_name in iter_expr` reading more like this language's own
   existing `for`-statement syntax (`for var_name in iter_expr { ... }`, just without the braces)
   matter enough to insist on it? The sketch above already matches the original proposal's own
   syntax exactly; no real objection raised yet, just flagging it as a real, if minor, choice.
3. **Error message shape when `iter_expr` isn't an array/`Vec`** — mirror `check_for`'s own
   existing wording ("'for x in ...' needs a range (a..b) or an array, got ...") closely, or write
   comprehension-specific phrasing? Small, but worth picking deliberately rather than by
   copy-paste accident.
4. **Does an empty-source comprehension need special-casing**, or does the general "loop zero
   times over an empty array, `Vec::push` never called" case already produce the correct empty
   result automatically (very likely yes, given step 1 of the codegen sketch always builds a real
   empty `Vec` up front regardless of how many iterations the loop body actually runs) — worth
   confirming with a real test once built, not just assumed.
