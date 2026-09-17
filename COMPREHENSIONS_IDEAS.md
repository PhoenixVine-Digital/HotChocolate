# List comprehensions — design doc

**Status, 2026-09-16**: landed, exactly as this doc's own architecture section sketched, and it
compiled cleanly + ran correctly on the FIRST real attempt (a genuine rarity for a feature this
size this session) -- the design-doc-first discipline paid for itself directly here. A real,
previously-unnoticed architectural wall surfaced mid-implementation and got resolved before any
code was written, not discovered the hard way after: this compiler has no side-effect-free way to
learn an arbitrary expression's type (`gen_expr` always emits bytecode AND returns the type
together, no "just tell me" twin exists for a general expression), so the doc's own original plan
("build the empty result `Vec` before the loop starts") had nothing to infer `result_expr`'s type
FROM until a real element actually matched -- fatal for the zero-match case specifically, which is
exactly the case this whole feature needed to get right. Resolved by requiring an EXPLICIT declared
result type (`let squares: Vec<Int> = [x * x for x in numbers];` — the annotation is REQUIRED, not
optional, a real, disclosed v1 scope narrowing from "anywhere an `Expr` is legal" to "only as a
`let`/`var` initializer with a declared array type"), trusted directly the same way `Stmt::Let.
declared_type` already is for a `dyn` annotation elsewhere. Verified end to end with a real program
covering every real case at once: no filter, with filter, a function-call result expression AND a
function-call filter (the proposal's own two motivating examples), a genuine ZERO-MATCH result
(the case that needed the whole redesign, now a correct empty `Vec<Int>`, not a crash), and a
`Vec<T>` (not just a raw array) as the comprehension source -- every value matched hand-computed
expectations exactly. See `examples/comprehensions.hotc`. Full existing example regression suite:
zero new failures, same 8 known pre-existing ones. Self-hosting verified to a true fixed point.

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
- `iter_expr`'s own checked type is either a raw array (`[T]`, `is_array_type_name`) or a `Vec<T>`
  mono struct (a name like `"Vec$Int"` — recognized via `mono_base(name) == "Vec"`, mirroring how
  other checker code already recognizes a specific mono base). Either shape resolves the element
  type `var_name` gets registered with; anything else is the new, comprehension-specific error
  (see "Resolved decisions" #3) — no other collection shape is accepted this pass.

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
3. Emit a REAL loop over `iter_expr`, one of two shapes depending on the checked source type
   (see "Resolved decisions" #1): for a raw array, reuse `gen_stmt`'s own existing `For` bytecode
   shape directly (index-based iteration + `arraylength` bound check + `xALOAD` element load); for
   a `Vec<T>`, the SAME index-based loop skeleton but bounded by a real `Vec::length()` call and
   loading each element via a real `Vec::get(i)` call instead of an array-load opcode. Both share
   everything except that one innermost "get the current element" step -- not two independent
   loop implementations, one shared skeleton with a swapped-out element-access call.
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
- ~~Tuple results~~ — **unblocked, 2026-09-17**: `stdlib/tuple.hotc`'s own `Tuple2<A, B>` (an
  ordinary two-type-param generic struct, needing zero new compiler machinery) means `[tuple2(x, x
  * x) for x in nums]` -- a real tuple-shaped comprehension result -- already works TODAY, no
  special comprehension-side syntax needed (`result_expr` was always allowed to be any expression,
  including a call). No `(a, b)` LITERAL syntax landed alongside it (a real, disclosed narrower
  scope -- `tuple2(a, b)` is the available constructor). Landing this surfaced a real, genuine gap
  in comprehensions themselves, now fixed: `check_comprehension` registered the result struct type
  but never explicitly registered its own `.push()` method -- `gen_comprehension`'s synthesized
  bytecode calls `.push()` directly, bypassing the ordinary `check_method_call` path that would
  normally trigger `ensure_method_instantiated`, so a result element type that had never ALSO been
  `.push()`-ed anywhere else via ordinary checked code (every earlier example happened to reuse
  `Vec<Int>`/`Vec<String>`, already registered elsewhere) got a real `NoSuchMethodError` at RUNTIME
  even though checking/codegen both reported success. Verified against `examples/
  tuple_and_comprehension.hotc`.
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

## Resolved decisions

Answered directly (2026-09-16) — kept as the record of what was decided and why, not just a bare
answer key.

1. **Comprehension source: BOTH raw array and `Vec<T>` directly.** The real ergonomic case wins
   over the smaller diff — `for x in someVec` not compiling today (only `for x in someVec.data`
   does) was flagged as a real, existing asymmetry, not a reason to propagate it into a NEW
   feature. Codegen needs two real iteration shapes (index-into-array vs. `Vec::get`/`length`),
   not one — see "Architecture" below for how they share everything except the innermost
   load-current-element step.
2. **Clause order: `result_expr for var_name in iter_expr if cond`, Python-style, is the one real
   grammar** — not a second parseable syntax alongside a `for`-statement-flavored alternative.
   "Suggested but not enforced" describes a STYLE preference (how the docs/examples present it),
   not a compiler-level choice between two accepted orderings.
3. **Error message: comprehension-specific phrasing**, not `check_for`'s own wording reused
   verbatim — e.g. `"comprehension source 'for x in ...' needs an array or Vec<T>, got " + name`,
   distinct enough from `check_for`'s own "'for x in ...' needs a range (a..b) or an array, got
   ..." that a caller reading either error immediately knows which construct is complaining.
4. **Empty-source handling: turned out to need a real design change, not just verification** — see
   this doc's own top "Status" note for the full story. Building the empty `Vec` per the original
   sketch's own step 1 ran into a genuine wall (no way to know `result_expr`'s type before ANY
   element has been evaluated), resolved by requiring an explicit declared result type rather than
   inferring one. Once that landed, the zero-match case verified correctly on the first real test
   run — exactly the confirmation this item originally asked for, just via a different path than
   assumed when it was written.
