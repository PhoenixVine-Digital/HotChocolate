# Phoenix Flight: a concurrency library — ideas

**Status, 2026-09-08: the "buildable today" half of this doc has
shipped** — a real `PhoenixPool`/`spawn`/`join`/`rebirth`/`shutdown`
wrapping `java.util.concurrent`, at `stdlib/phoenix.hotc`, opt-in via
`use phoenix;`. See ARCHITECTURE.md's own "Phoenix Flight" section for the full
design and `examples/phoenix_flight.hc` for a worked example. Also
shipped, and NOT originally scoped in this doc: a real compile-time
capture-safety check (`@sendable`) on `pool.spawn(...)`'s own lambda —
see ARCHITECTURE.md's own "Compile-time concurrency safety" section.

**Status, 2026-09-17 (correction)**: this note previously said `spawn_after`/
dependency-graph chaining was "not yet shipped" — stale. It's real, shipped,
and already tested: `PhoenixPool::spawn_after`/`::spawn_after_all` (block the
CALLING thread on the dependency/dependencies, then submit — a deliberate
trade-off against pool-worker starvation, see that fn's own header in
`stdlib/phoenix.hotc`) and `PhoenixPool::spawn_chain`/`PhoenixChain::then`
(a real, non-blocking `CompletableFuture`-backed continuation chain instead)
cover both real shapes this doc's own sketch wanted. See `examples/
phoenix_flight_chaining.hc`/`phoenix_flight_chain_async.hc`. The ONE thing
still genuinely not shipped, unchanged: the full ownership-derived parallel
scheduler (needs a real move/borrow checker first) — see `IDEAS.md`'s own
ECS entry.

**Note**: this file cites `ClipboardPacketHandler.hc` and similar files
from `kubejs-aisle-tool`, a separate consumer project not present in
this repo. Treat those references as unverifiable from this repo alone.

A `hotc-mc`-shaped sibling, not a language feature: `IDEAS.md`'s own
"Native concurrency primitives" entry already concluded a *new*
compiler-level concurrency primitive isn't needed, because `extern
class`/`extern interface` already reach `java.util.concurrent`
(`ExecutorService`, `CompletableFuture`, JDK 21+ `Thread.ofVirtual()`)
with zero new compiler work — the same FFI mechanism every other
binding in this project already uses. Phoenix Flight is what that
becomes as an actual library instead of an unrealized conclusion: task
spawning, pools, dependency graphs, all real HC structs/fns wrapping
real JVM concurrency primitives.

Kept separate from `HOTC_MC_IDEAS.md` because it isn't Minecraft-
specific — the task-graph layer is generally useful to any HC program —
but the two are meant to compose: Phoenix Flight provides the safe
primitives, `hotc-mc` (or a mod using both) provides the
Minecraft-specific patterns built on top of them (see "The Minecraft-
specific constraint" below).

## Buildable today — zero new compiler features

The whole task-spawning/pool/dependency-graph layer is ordinary
extern-class wrapping, no different in kind from any other binding this
project has already written and verified:

```
let pool = PhoenixPool::new(8);

let task = pool.spawn(|| generate_terrain());
let result = task.join();
```

- `PhoenixPool` wraps a real `ExecutorService` (or `Thread.ofVirtual()`
  per-task, worth benchmarking both — Minecraft's own asset/resource
  loading already runs on a real virtual-thread-flavored worker pool,
  visible as `Worker-Main-N` threads in any Forge log, so lightweight
  per-task virtual threads may be the more idiomatic fit here than a
  fixed thread pool).
- `spawn { }`/`.join()` map directly onto `ExecutorService.submit(...)`
  / `Future.get()` — a lambda literal (already shipped) as the task
  body, `.join()` blocking or (better) returning a real `Result<T,
  JException>` once `Result` itself exists (see `IDEAS.md`) instead of
  letting a task's exception surface as an unchecked JVM one.
- `spawn_after(dep) { }` (a task graph, not just a flat pool) wraps
  `CompletableFuture.thenApplyAsync`/`thenComposeAsync` — real,
  existing JDK machinery for exactly this, not something to hand-roll.
- The "rebirth" branding is free and worth keeping: `phoenix.rebirth()`
  resetting a pool after a task threw is just
  `ExecutorService.shutdownNow()` + constructing a fresh one under the
  hood, and it's a genuinely good name for what it does, not just a
  pun. "Phoenix Flight" itself is a strong name for the library too —
  keep it.

None of this needs `Result<T, E>` or `?` to exist first (both still
useful once they land, per `IDEAS.md`), and none of it needs the
ownership-derived-scheduling half below — a plain task pool with join
handles is useful entirely on its own, and is the part worth shipping
first.

## The Minecraft-specific constraint — this is the part that actually matters

Most `Level`/`Entity`/world-mutation state in Forge cannot safely be
touched off the main thread — that's not an HC or Phoenix Flight
limitation, it's how Minecraft itself is built, and a library that
made `parallel for entity in entities { entity.update() }` *feel* safe
for arbitrary Minecraft state would be actively worse than no library
at all — it'd be an invitation to a class of crash/corruption bug that
Forge's own single-threaded design already mostly prevents by making
the wrong thing awkward to write.

The actual safe pattern — background *compute*, then marshal the
result back onto the main thread — already has a real, proven mechanism
in this exact codebase: `ClipboardPacketHandler.hc`'s `NetworkEvent.
Context.enqueueWork(...)` (this session, verified working end to end).
Phoenix Flight's real Minecraft-facing value is making *that* pattern
the path of least resistance instead of a general-purpose `parallel
for`:

```
spawn_background { compute_expensive_thing() }
    .then_on_main_thread(ctx) { result -> apply_to_world(result) };
```

where `then_on_main_thread` is the library wrapping whichever real
main-thread-reentry mechanism is available in context (`NetworkEvent.
Context.enqueueWork` inside a packet handler, `Minecraft.
getInstance().execute(...)` on the client, a server's own tick-queue
equivalent) — the point being the API shape itself makes "results only
ever cross back onto the main thread through one narrow, checked door"
the default, not something the caller has to remember every time.

## ~~Belongs to `IDEAS.md`'s ECS entry, not duplicated here~~ — the general-purpose half shipped, 2026-09-17

`parallel for entity in entities { }` — compiler-verified "this can't
alias shared state it shouldn't touch" — landed as a REAL, general-
purpose language construct, separate from (and dispatched onto the SAME
runtime as) ECS's own `&`/`&mut`-driven system scheduler, which already
had its own real parallel dispatch. The move/borrow checker itself
needed ZERO new code: `parallel for x in arr { expr; }` desugars
ENTIRELY, at PARSE TIME, into ordinary already-proven pieces -- a fresh
`PhoenixPool`, one `pool.spawn(|| expr as PhoenixObject)` per element
(each capturing the CURRENT element via an ordinary zero-param lambda
literal), collected into a `Vec<PhoenixTask>`, then `join_all_and_
shutdown` before the statement completes -- so `@sendable`'s own
EXISTING lambda-capture-safety check (already wired to `PhoenixPool::
spawn`'s lambda argument) automatically verifies the element's own type
(and anything else `expr` captures) is safe to hand to a background
task, with no new safety-checking code at all. `Parser.hotc`'s own
`block()` splices the desugared statement sequence directly in, and
`"phoenix"` (plus its own transitive `"vec"` dependency) auto-injects
into `Program.uses` the same way ECS's own `phoenix` dependency already
does, so a program using `parallel for` doesn't need to also write `use
phoenix;` itself.

Real, disclosed v1 scope cuts:
- The loop body must be exactly ONE expression statement (a single
  `x.field = ...;`/`x.method(...);`-shaped line) -- lambda literals in
  this language only ever support a single-expression body (the same
  real, pre-existing limit `HOTC_MC_IDEAS.md`'s own "trailing-block DSL
  syntax" entry already flags). A real, previously-hit bug this surfaced
  and fixed: an EARLIER version bound the source array via an ordinary
  `let __pf_arr = arr_expr;`, which genuinely MOVED `arr_expr` when it
  was a bare identifier (the ordinary, non-`parallel` `for` never moves
  its own source) -- making the caller's own array unusable after the
  loop. Fixed by embedding `arr_expr` directly into the desugared `.
  length()`/`.get(idx)` calls instead (safe: struct-literal field values
  are never move-checked, and `Vec<T>::length`/`::get` both take `&self`
  regardless).
- Only the `Vec<T>`-iteration shape, not the range shape
  (`parallel for i in a..b { }`).
- A fixed worker count (4), not sized to the real element count --
  "correctness first," the same real, disclosed cost `ECS_IDEAS.md`'s
  own per-`run_all()` pool already accepts.
- The body's own expression must actually produce a real value (boxed
  via the automatic `as PhoenixObject` wrap) -- a bare call to a
  `Unit`-returning method as the WHOLE body has nothing real to box;
  not attempted here.

Verified against `examples/parallel_for.hotc` (a real `@sendable`
struct, mutated correctly, values proven correct after the loop) and a
deliberate negative test (capturing a non-`@sendable` struct correctly
rejected with the exact same error `PhoenixPool::spawn`'s own lambda
argument already produces). Full example regression suite: zero new
failures. Self-hosting verified to a true fixed point.

ECS's own `@parallel fn`/systems-level scheduling (a DIFFERENT
mechanism -- comparing DECLARED systems pairwise by their own `&`/
`&mut` component params, not a single loop's element-independence) is
unaffected and unchanged; see `IDEAS.md`'s "ECS with ownership-derived
system scheduling" entry for that one.
