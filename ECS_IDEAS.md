# ECS with ownership-derived scheduling — design doc

**Status, 2026-09-15 (later still)**: the GL-thread-safety hazard right below is **fixed**, not
just worked around. `@main_thread` -- a bare directive, same shape `@profile` already has --
marks a system as touching a thread-affine external resource; `check_systems` now forces any TWO
`@main_thread` systems to conflict with EACH OTHER unconditionally, regardless of what their own
`&`/`&mut` component access would otherwise permit (a new `systems_conflict_or_main_thread_ecs`,
OR'd with the existing `systems_conflict_ecs`, feeds both the warning loop and the real Phase B
grouping logic). Marshmallow's own `Render`/`PlayerRender` are now marked `@main_thread` directly
-- replacing the fully sequential `@after` chain across all four systems that the note below
originally used as a stopgap -- and the compiler correctly reports "systems 'X' and 'Y' are both
'@main_thread' -- forced to run sequentially" for them. Verified with a synthetic two-system test
(disjoint components, would otherwise parallelize): 2 sequential groups WITH `@main_thread` on
both, 1 parallel group WITHOUT it (`--explain-schedule` confirms both directions), plus the full
existing example/ECS regression suite with zero output changes (including a real stress test's
own genuine parallel dispatch, unaffected). This only solves the "author remembers to mark it"
half -- whether the checker could ever INFER this automatically (by seeing a system's own body
call into `window`/`graphics`) is still open, and not attempted here.

**Status, 2026-09-15 (later)**: a real, previously-latent hazard found adding a SECOND rendering
system to Marshmallow's ECS use (a `PlayerMove`/`PlayerRender` pair, driven by a new `Input`
resource's own arrow-key fields, alongside the existing `Rotate`/`Render` ring). `Render` and
`PlayerRender` share only READ-only access to `Renderable`/`Camera` (`&Renderable`/`&Camera`, no
`&mut` on either side anywhere) -- a real, correct "no conflict" call by `systems_conflict_ecs`,
which is exactly why the scheduler was happy to run one of them on a real background thread via
Phoenix Flight's own parallel dispatch (Phase B) while the other ran on the main thread. Real
OpenGL calls made from inside a system's own body (`Shader::use_program`, `Mesh::draw`, ...) are
NOT thread-safe at all -- a GL context only ever works on the thread that created it -- so this
produced a real LWJGL fatal abort ("No context is current") the instant both rendering systems'
own draw calls landed in the same parallel wave. **This is a real, general hazard for ANY future
system that makes OpenGL calls, not specific to this one pair**: the ownership-conflict analysis
this whole scheduler is built on has NO CONCEPT of "also touches a thread-affine external
resource" -- it only ever reasons about HC-visible `&`/`&mut` component access, which is
genuinely silent about a system's own body calling into `window`/`graphics`. Worked around in
Marshmallow with a deliberate, fully sequential `@after` chain across all four systems involved
(a real, disclosed trade of potential parallelism this scene doesn't need for correctness) --
NOT fixed at the compiler level. A real fix would need some way to mark a system (or a whole
scheduling GROUP) as "never runs on any thread but the main one," independent of whether its own
component access would otherwise permit parallelism -- a genuinely new kind of scheduling
constraint this doc doesn't have an answer for yet. Worth a new "Still open" entry once this
becomes a real blocker rather than a one-off worked around by hand.

**Status, 2026-09-15**: the resource-injection gap this doc's own "Still open" #6 raised is
landed. `resource Name { fields }` declares a single, world-wide value (own registry,
`Checker.hotc`'s `resources`, never archetype-matched); `world.set_resource(Name { ... })` writes
it (a real `PUTSTATIC` to `__resource_<Name>`, a field left at the JVM's own `null` default until
first set -- a real, disclosed runtime precondition, not enforced at compile time, matching every
other "trust the caller" stance this file's own runtime state already takes). A system's `run`
param naming a resource is borrowed by `&`/`&mut` exactly like a component (folded into the SAME
ownership-conflict analysis `check_systems` already does), but fetched from that static directly
rather than a per-row archetype field -- `required_components_of_system_ecs` excludes it from
archetype matching entirely, so a resource never has to exist on every entity to be usable.
Verified with a real, hand-computed-expected-output program (a `DeltaTime` resource read across
two `world.run_all()` calls with different values set in between), against the full existing ECS
example suite (zero output changes), AND in Marshmallow itself: the ECS ring's `Render` system now
reads a real `Camera` resource (`eye`/`target`/`up`) that `main()` sets from its OWN live,
mouse-look free-fly camera every frame -- confirmed live, the ring cubes now correctly track
camera movement instead of staying fixed on screen. The IntelliJ plugin's own parser was taught
`resource`/`RESOURCE_DECL` too (mirroring `component`), so this doesn't regress IDE support the
way the missing `static`-in-`impl` handling did earlier.

**Status, 2026-09-11**: first real use in a real project, and a real gap found by it. Marshmallow
(the game engine this compiler exists to serve) now spawns 5 real entities (`Transform` +
`Renderable`, the latter holding owned `Mesh`/`Shader`/`Texture` struct values -- since those are
just thin wrappers around GL integer handles, every entity sharing the same mesh/shader/texture
shares the same underlying VAO/program/texture id, no duplicate GL resources per entity), animated
by a `Rotate` system and drawn by an `@after(Rotate)` `Render` system. Confirmed working end to
end on real hardware: 5 lit, textured cubes orbiting in a ring.

**The real gap this surfaced**: `World` only ever exposes `spawn`/`run_all`/`add`/`remove`/
`shutdown` -- there is no way for ordinary caller code to read a component's CURRENT value back
out after a `run_all()` call, or to push fresh external state (mouse/keyboard input, a moving
camera, anything computed outside the ECS world) INTO an already-spawned entity before the next
`run_all()`. Marshmallow's own `Render` system works around this by hardcoding a fixed camera
directly in the system body -- real, but not general: any system that needs a live value from
outside the ECS world (a camera that moves, a delta-time value computed by the caller's own
`FrameTimer`, anything) currently can't get one. This is a real, unsolved design question, not
just a missing convenience method -- see a new "Still open" entry below for what a fix would
actually need to answer (a resource/singleton mechanism, most likely, but exactly what shape one
takes is genuinely open).

**Status, 2026-09-10**: query filters landed -- `Without<Component>` on a `system`'s own `run`
param excludes any archetype that HAS that component from that system's own matching set
entirely (a pure, compile-time-only exclusion -- no new runtime state, no `&`/`&mut` needed on
the param, since it borrows nothing). Rides the EXISTING generic type-name syntax `Parser.hotc`'s
own `type_name_ref` already parses for free (`Without<Frozen>` -> the mono-name string
`"Without$Frozen"`, same convention `Vec<Int>` uses) -- zero grammar changes. `Checker.hotc`'s
own `check_systems` validates the named component is real and skips the ordinary `&`/`&mut`-
required check for it entirely; `Codegen.hotc`'s own `real_run_params_ecs`/`required_components_
of_system_ecs`/`excluded_components_of_system_ecs` strip the filter from the REAL synthesized
`run` fn's signature/call-site args (there's no archetype field backing a filter) and feed the
exclusion into `build_system_dispatch_body_ecs`'s own archetype-matching loop (`sig_has_any_ecs`,
`sig_has_all_ecs`'s negated sibling). Verified end to end with a real program (two entities, one
plain, one with an extra `Frozen` marker component; a `Render` system using `Without<Frozen>`)
-- output confirmed the frozen entity's archetype was skipped entirely, never even reaching
`Render`'s own per-row loop. See `examples/ecs_query_filters.hc`.

**`Changed<Component>` landed too, same day** -- real per-archetype-row change-tracking state: a
parallel `Vec<Bool>` per component per archetype (`Codegen.hotc`'s own `archetype_field_changed_
name_ecs`), reset to `false` for every row at the START of each `world.run_all()` call (right
after that call's own queued add/remove flush -- `build_reset_changed_stmts_ecs`) and set `true`
for any row a `&mut`-taking system's own dispatch touches during THAT SAME call (`synthesize_
system_call_stmts_ecs`'s own mark-changed statements, one per REAL `&mut` param), plus on every
freshly spawned row (`World#spawn`'s own hand-written bytecode) and carried along (not reset)
across an archetype MOVE (`build_move_stmts_ecs`'s own "Changed<T>" carry-over, swap-with-last +
pop mirrored onto the flag Vec so it stays row-aligned with its own component Vec). `Changed<T>`
reads the flag per row in `run_all`'s own generated loop, skipping the whole `run` call for a row
where it's `false` -- never even reaching the per-row body.

**Real, disclosed tick-boundary consequence** (the concrete open question from this doc's own
earlier "Still open" note, now resolved by actually building it and seeing what the simplest
correct implementation gives): within one `world.run_all()` call, a `Changed<T>` reader only ever
sees writes from `&mut`-taking systems SCHEDULED BEFORE it that SAME call -- never a `world.add`/
`world.remove` flushed that same call (the flush's own "just added" `true` is wiped by the reset
that immediately follows it, by design -- see `build_reset_changed_stmts_ecs`'s own header), and
never a system scheduled AFTER it (that read simply sees "no changes yet this tick," not last
tick's staleness). `check_systems` already forces a `Changed<T>` reader to serialize against a
concurrent `&mut` writer of the same component (folded into the ordinary read set), so this is an
ordering nuance, not a thread-safety hazard -- and it's the reason `@after(Move)` matters in
`examples/ecs_changed_filter.hc` (a `Changed<Transform>` reader with no explicit ordering relative
to its own writer would non-deterministically see either "changed" or "not yet," depending on
which side of the writer the scheduler's own declaration-order tie-break happened to place it).
Verified end to end with a real program (a `Move` system writing `&mut Transform` on all but a
`Frozen`-marked entity via `Without<Frozen>`, an `@after(Move)` `Watcher` system reading `Changed
<Transform>`) across TWO `world.run_all()` calls -- output confirmed `Watcher` only ever printed
the entity `Move` actually touched, both ticks. See `examples/ecs_changed_filter.hc`.

**Status, 2026-09-10 (earlier this day)**: seven slices landed, including Phase B (real parallel dispatch) and
queued archetype transitions -- `world.spawn(...)`/`world.run_all()`/`world.add(...)`/
`world.remove(...)`/`world.shutdown()` all run correctly end-to-end (verified with real programs;
component field mutations and print output matched hand-computed expected values exactly,
including a real archetype move with a swapped-entity row fixup, and a real
two-systems-on-two-threads run through Phoenix Flight's own, now-REUSED pool). `world.shutdown()`
is new: Phase B's dispatch pool is a program-wide static reused across every `world.run_all()`
call rather than recreated per call (a real performance fix, see "Explicitly deferred" below) --
any program that ever needs real parallelism now has to call it once, near the end, same
contract `PhoenixPool::shutdown()` already puts on ordinary Phoenix Flight callers. `world.add`/
`world.remove` now QUEUE their request instead of applying it immediately -- the real move
happens at the start of the NEXT `world.run_all()` call, closing a mid-iteration mutation hazard
before it's ever reachable (see "Still open" #3, below, for the honest nuance: no program can
actually trigger that hazard TODAY, since a system's own `run` params never carry its entity id).

1. The `Lexer`/`Parser`/`Ast.hc` layer — `component Name { fields }`, `system
   Name { fn run(...) { ... } }`, and the `@after(Other)`/`@before(Other)`/
   `@profile` directive syntax (the directive-parsing loop also now supports
   STACKING multiple directives on one declaration, not just one, needed for
   `system`) all parse into real `ComponentDecl`/`SystemDecl` AST nodes. One
   correction made along the way: this doc's own original sketch put `&`/
   `&mut` before the param NAME (`fn run(&mut transforms: Transform, ...)`,
   mirroring `&mut self`) — that's not how this language's own param syntax
   works anywhere else (`&`/`&mut` goes after the colon: `transforms: &mut
   Transform`), so the sketch below is corrected to match rather than
   inventing a second, system-only syntax.
2. `Checker.hotc` registration and the real scheduling analysis —
   `register_component` (mirrors `register_struct`, including the "no
   reference-typed fields" rule); `check_systems`, which validates every
   `run` param names a known `component` BY REFERENCE (never by value),
   derives read/write sets straight from `is_ref`/`is_mut_ref`, validates
   `@after`/`@before` targets are real system names, emits the ALWAYS-ON
   conflict warning (see "Resolved decisions" #6 — the CLI opt-out for
   iteration isn't wired yet, still a following-slice gap), and topologically
   sorts the real execution order (`self.system_order`) with a real cycle
   check. See `examples/ecs_scheduling.hc` for a worked example (the
   conflict warning fires exactly as designed). Nothing runs any of this
   yet — no `World`, no archetype storage, no codegen at all for `component`/
   `system` — that's next.
3. Archetype storage + `world.spawn(...)` codegen — `stdlib/ecs.hotc`'s
   deliberately-opaque `World` struct; `TyChecker.archetype_signatures`
   (populated by a `World#spawn` special-case gate in
   `check_method_call_by_type`, which validates every arg is a known
   component struct literal and dedups the resulting component-set
   signature); `Codegen.hotc` synthesizes one real `StructDecl` (parallel
   `Vec<Component>` fields, one per component in the signature — see
   `synthesize_archetype_struct_ecs`) and one real top-level `pub static`
   instance (`synthesize_archetype_static_ecs`) per distinct signature
   actually spawned anywhere in the program, fed through the ORDINARY
   struct/static registration and codegen passes (the "materialize real AST,
   reuse existing codegen" pattern this compiler already used for
   `extend`/default-methods) — so no new low-level bytecode machinery was
   needed for storage itself, only for the genuinely special `world.spawn`
   call site (`finish_method_call`'s own `World#spawn` branch: push a fresh
   entity id from a shared `__next_entity_id` counter static, push each
   component value into its matching archetype field's `Vec`, increment the
   counter). `world.run_all()` / query iteration over spawned archetypes is
   NOT built yet — that's next.
4. `world.run_all()` codegen — one real, plain top-level fn per `system`
   (`synthesize_system_fn_ecs`, literally its own `run` block given a real
   callable name) plus one `__world_run_all` fn that calls each of those,
   once per matching archetype row, in `checker.system_order` — built
   entirely as ordinary source-level `Ast.hc` (`For`/`Call`/`MethodCall`/
   `FieldAccess`), so the ORDINARY fn-check/codegen pipeline compiles it with
   zero hand-written bytecode at all (see `build_run_all_body_ecs`). `world.
   run_all()` itself is a thin forward to that one fn. Verified with a real
   program (two `world.spawn(...)` calls, two `world.run_all()` calls, a
   `Movement`/`Render` system pair with `@after`) — output matched
   hand-computed expected values exactly across both runs.
5. Archetype-transition mechanics — `world.add(entity, Component { ... })`/
   `world.remove(entity, Component)` move an already-spawned entity's row
   between archetypes for real. Two API decisions made getting here (see
   `ECS_IDEAS.md`'s own commit history for the exchange): `world.spawn(...)`
   now returns the entity id it created (`Int`, was `Unit`) so `add`/`remove`
   have something to operate on; `world.remove`'s own second argument is a
   BARE component type name (`world.remove(e, Transform)`, not a struct
   literal — there's no value to construct for a removal), parsed as an
   ordinary `Ident` with zero grammar changes (`Checker.hotc`'s own
   `check_method_call` intercepts `World#add`/`World#remove` BEFORE the
   generic arg-checking pass that would otherwise reject a bare component
   name as an unknown identifier).

   The real engineering chunk this doc's own "Runtime — archetype storage"
   section flagged as the biggest: the FULL set of archetypes a program
   needs isn't just what `world.spawn(...)` builds directly, since `add`/
   `remove` can move an entity into a signature nothing ever spawned into
   -- `archetype_closure_ecs` computes the real closure (every signature
   reachable from the spawned set by repeatedly adding/removing any type
   ever named in an `add`/`remove` call anywhere in the program, to a fixed
   point). Each archetype gets a real, compile-time-assigned integer `__id`
   field on its own struct (read back via a plain `GETFIELD`, no second
   lookup table needed anywhere); two new entity-id-indexed statics,
   `__entity_archetype_id`/`__entity_row`, track which archetype and row
   each entity currently lives in (`World#spawn`'s own codegen updates both
   when it creates a row). `world.add`/`world.remove` are thin forwards
   (same "hand-written glue, real logic as synthesized AST" split
   `world.run_all()` established) to one real `__world_add_<Component>`/
   `__world_remove_<Component>` fn per component type ever added/removed —
   each dispatches on the entity's own CURRENT archetype (a runtime value,
   unknowable at compile time in general) via one `If` per compatible
   source archetype, and the move itself (evacuate the old row via
   swap-with-last + pop, populate a new row in the target, fix up whichever
   entity got swapped into the vacated slot) is entirely synthesized
   `Ast.hc` (`Let`/`If`/`MethodCall`/`FieldAccess`) — no hand-written
   bytecode for the move logic itself. Verified with a real program (two
   entities, one spawned with a component the other lacks; that component
   added to one and removed from the other mid-program; `world.run_all()`
   run before and after) — output matched a full hand-derived trace exactly,
   including the swapped-entity row fixup.
6. Phase B — real parallel dispatch. `check_systems`'s own topo-sort became a GROUPED topo-sort
   (`checker.system_groups: Vec<Vec<String>>`, alongside the flattened `system_order` kept for
   anything that still wants a flat view): same Kahn's-algorithm shape, but each round now picks
   EVERY ready, pairwise-non-conflicting system in declaration order, not just the first one, so
   unrelated conflict-free systems land in the same real parallel batch. Each `system` gets its
   own query-iteration wrapped in a real, zero-param, separately-callable
   `__system_<Name>_dispatch() -> String` fn (needed because Phase A had it inlined straight into
   `__world_run_all`, and `pool.spawn(...)` needs something with NO free variables to call) --
   `build_run_all_body_ecs` now reads `system_groups` and, for a size-1 group, calls that one
   dispatch fn directly (byte-identical to Phase A's own behavior); for a size>1 group, it spawns
   every member's dispatch fn onto a real `PhoenixPool` and `.join()`s all of them before the
   next group runs (that `.join()` doubles as the real memory-visibility barrier the JVM memory
   model needs between groups). The pool itself is a per-`world.run_all()`-call LOCAL, not a
   program-wide static -- see the real bug below on why. `use ecs;` now also transitively pulls
   in `phoenix` (same "codegen needs it regardless of what the user's own source writes"
   reasoning already established for `vec`). Verified with a real program (two systems touching
   disjoint components, genuinely scheduled onto the SAME group with zero `@after` hints needed
   -- the checker's own conflict analysis alone deferred the third, conflicting system to the
   next round) -- output matched hand-computed values exactly across three `world.run_all()`
   calls, confirming the actual background-thread dispatch produced the right results, not just
   that it compiled.

   **A real bug from this slice, worth keeping as a pattern**: the FIRST version made `
   __world_pool` a program-wide `pub static`, created once in `<clinit>` via `PhoenixPool::new(
   ...)` -- reasonable-looking (why recreate a thread pool every call?), but
   `Executors.newFixedThreadPool`'s own worker threads are NOT daemon threads, so a pool that's
   never explicitly `.shutdown()`'d keeps the entire JVM process alive forever after `main()`
   returns, even though every actual line of program logic already finished. A real, previously-
   hit hang (a program that computed and printed the exactly right output, then never exited) --
   fixed by making the pool a LOCAL inside `__world_run_all` itself instead, created fresh and
   `.shutdown()` at the end of the SAME call. The real, disclosed cost: a fresh thread pool now
   gets created and torn down on every `world.run_all()` call rather than once -- a genuine
   performance concern, deliberately deferred (joins "archetype-transition performance work" in
   this doc's own "Explicitly deferred" list) rather than solved here, since correctness (the
   process actually exiting) matters more than this pass's own speed.
   **Also a real, disclosed gap this slice does NOT close**: `check_systems`'s own conflict
   analysis already proves two CONCURRENTLY-scheduled systems never touch the same written
   component, and no component data is ever captured across the `pool.spawn(...)` closure
   boundary (each dispatch fn reaches its own component data through top-level statics, not a
   captured variable) -- so this pass never needed the `@sendable`-on-every-component enforcement
   "Resolved decisions" #5 anticipated requiring. Worth a second look if a future slice's own
   design changes how a dispatched system reaches its data.

**Five real bugs from an earlier slice, all worth keeping as patterns** (this was
the first time `world.spawn(...)`/`world.run_all()` were ever actually RUN,
not just compiled — every one of these was invisible until then):

1. **Bootstrap sync must cover the WHOLE package tree, not just the flat
   entry-point files.** `SelfhostCLI.class`/`SelfhostStage2Compiler.class` at
   `selfhost/bootstrap`'s top level are thin drivers -- the REAL compiler
   logic (`Checker.hotc`, `Codegen.hotc`, ...) compiles down to its OWN
   per-struct classes under `selfhost/bootstrap/hc/selfhost/...` (e.g. `hc/
   selfhost/codegen/CodeGen.class`). Syncing only the top-level files after a
   `run selfhost` build silently leaves those inner classes stale -- the
   compiler keeps running OLD checker/codegen logic while every top-level
   symptom (build succeeds, `SELF-HOSTING MILESTONE` prints) looks fine.
   Always diff and sync the FULL `hc/` subtree alongside the top-level files.
2. **A checker field populated as a side effect of checking a CALL isn't
   ready before that call has actually been checked.** `checker.
   archetype_signatures` only gets entries once `world.spawn(...)` is
   type-checked (inside `check_fn`) -- synthesizing archetype structs/statics
   BEFORE the bulk `check_fn` loop runs (where the original code put it,
   right after `component`/`system` registration) silently produced ZERO
   archetypes for a program that spawns entities perfectly validly. Fixed by
   moving that whole synthesis section to run AFTER checking finishes (right
   after `has_real_errors`), with a fresh `n_final`/`sn_final` count so the
   later structs/fns codegen loops (captured `n`/`sn` earlier) actually see
   what got added.
3. **A synthesized struct field of a generic type still needs the mono
   struct's own VALUE expression type-checked somewhere, or it never gets a
   real `.class` file.** `Vec$Int`'s own real monomorphization
   (`ensure_instantiated`, separate from and a precondition for the
   method-level `ensure_method_instantiated`) is only ever triggered by
   type-checking a `StructLit` shaped like it -- the ordinary "check every
   static's own initializer" loop already existed for exactly this reason,
   but ran too early to see the archetype static (bug #2's ordering issue
   again). Fixed with an explicit `checker.check_expr(&astatic.value)` right
   after building it.
4. **`StructLit.generic_base` is EXCLUSIVELY for generic-enum-variant
   disambiguation (`Option<Int>::Some { ... }`), never for a plain generic
   STRUCT.** The correct way to construct a concrete generic struct literal
   programmatically is `name: "Vec$Int"` (the full mono name) with
   `generic_base: None` -- setting `generic_base: Some("Vec$Int")` with
   `name: "Vec"` instead (which reads like the "obviously correct" pairing)
   made `gen_expr` treat it as ENUM-VARIANT construction, emitting a no-arg
   constructor + field-by-field `PUTFIELD`s instead of a real `Vec$Int(data,
   len)` constructor call -- `NoSuchMethodError: Vec$Int: method 'void
   <init>()' not found` at runtime, compiling cleanly the whole time.
5. **A stdlib topic that transitively NEEDS another one (via synthesized
   codegen, not literal source text) has to declare that dependency in
   `add_stdlib_topic_and_deps`.** `use ecs;` alone never pulled in `vec`,
   even though the archetype-synthesis pass builds real `Vec$<Component>`
   fields and emits real `Vec$<T>.push()`/`.get()` calls regardless of
   whether the user's OWN source ever writes the word `Vec` -- without `vec`
   merged in, `Vec`'s own generic template was never registered at all,
   so `ensure_method_instantiated` found no template for `Vec#push` and
   handed ASM an empty method descriptor. Fixed by adding `ecs -> vec` as a
   real transitive dependency, same as `registry -> vec, option`.

**A real bug from an earlier slice, worth keeping as a pattern**: passing the SAME
struct value to two calls in a row (`checker.register_static_decl(astatic);
statics.push(astatic);`) tripped the move checker, because
`register_static_decl`'s parameter was declared bare (`s: StaticDecl`)
instead of by reference — the fix, as with every other false positive this
project has hit, was adding `&` to the parameter type (`s: &StaticDecl`),
which has zero effect on runtime behavior. The one wrinkle: the CALL SITE
also needs its own `&` (`register_static_decl(&astatic)`), and this
language allows `&` directly on a non-variable expression like
`&statics.get(i)` (see `Checker.hotc`'s own `str_vec_eq_ecs(&list.get(i),
...)` for prior precedent) — so no temporary local binding was needed there
either.

**A real methodological lesson from an earlier slice, worth keeping**: bootstrap-
swapping a wrapper class (`Vec$Bool`, `Vec$String`, ...) by just checking "did
this file's bytes change" isn't enough — this compiler emits a monomorphized
type's methods LAZILY, driven by what's actually CALLED somewhere in the
program being compiled that run. A narrow single-file test can produce a
"fresher" `Vec$String.class` that's actually MISSING a method (`.set()`) a
broader build (like the real `selfhost` directory-mode self-compile, which
exercises `sort_str_vec`) needs — copying that narrower build's output into
bootstrap silently REGRESSES it. Real incident this slice: exactly that
happened to `Vec$String`, breaking `parse_directory` until caught and fixed
by re-deriving bootstrap from a full `run selfhost` build instead. The fix
going forward: only ever sync bootstrap wrapper classes from a build that ran
the full `selfhost` self-hosting pipeline to a real `BUILD SUCCESSFUL`, never
from a narrower single-file test run, and verify a TRUE fixed point (rebuild
again from the just-updated bootstrap, confirm zero further byte differences)
before trusting it.

Spun out of `IDEAS.md`'s own "ECS with ownership-derived system scheduling"
entry (still there, now pointing here) the same way `PHOENIX_FLIGHT_IDEAS.md`
was spun out once Phoenix Flight became a real, actively-worked feature —
this doc is where that happens for the ECS entry.

Two decisions are already made, both from a real conversation, not guesses:

- **Phase A of this feature is verification-only.** Systems run
  SEQUENTIALLY at runtime. The compiler proves which systems COULD run in
  parallel — derived from the `&`/`&mut` component-access annotations on a
  system's own params, the exact same `Param.is_ref`/`is_mut_ref` machinery
  `selfhost/ast/Ast.hotc` already carries for move/borrow checking (see
  ARCHITECTURE.md's own "Compile-time safety roadmap" section, Phases 2-3) — but
  doesn't yet dispatch anything onto real threads. Real parallel execution
  (almost certainly by handing non-conflicting systems to Phoenix Flight's
  own pool, see `PHOENIX_FLIGHT_IDEAS.md`) is an explicit, deliberate
  follow-up once this analysis is proven correct on real programs, not
  attempted here.
- **Component storage is archetype-based** (Bevy-style): entities sharing
  the same exact component SET are stored together, one set of parallel
  arrays per archetype, for cache-friendly iteration. Adding or removing a
  component from an entity moves its whole row to a different archetype.
  Real engineering, not a simplification — see "Runtime" below.

## Why this is HC's own flagship differentiator, not a stretch feature

Most ECS designs need a second, hand-written declaration mechanism —
`reads(Transform), writes(Velocity)` spelled out separately from the actual
code — because the HOST language has no way to know from a function's own
signature what it touches. HC already does, for a completely different
reason: Phases 2-3 of the move/borrow-checking roadmap made `&` (shared,
read-only) and `&mut` (exclusive, read-write) real, enforced param
annotations, checked on every call already. A system's own `run` params
using those SAME annotations against component types isn't a new mechanism
at all — it's reading a signal the checker already produces for other
reasons. No other JVM language tracks `&` vs `&mut` on parameters this way,
so no other JVM language could derive a real scheduling graph from ordinary
function signatures the way this one can.

## Language surface (starting point — see "Open questions" below)

```
component Transform { x: Float, y: Float }
component Velocity { dx: Float, dy: Float }

system Movement {
    fn run(transforms: &mut Transform, velocities: &Velocity) {
        transforms.x = transforms.x + velocities.dx;
        transforms.y = transforms.y + velocities.dy;
    }
}
```

`component` is close to an ordinary `struct` — a plain data record, no
methods. `system` declares exactly one `run` method whose params name the
component types it touches, `&`/`&mut` deciding read vs write access — this
IS the query. `run` is called once per matching entity (one that has EVERY
component named in its param list), with each param bound to that entity's
own row.

## Architecture, piece by piece

### Parser
New `ComponentDecl`/`SystemDecl` AST nodes, closely mirroring `StructDecl`/
`ImplDecl`'s own existing shape (see `selfhost/ast/Ast.hotc`). A system's
`run` params are ordinary `Param`s — `is_ref`/`is_mut_ref` already exist on
that struct from Phase 3, so no new parsing work is needed there at all,
just a new top-level declaration shape wrapping them.

### Checker
- Register every `component` (mirrors `register_struct` in
  `selfhost/checker/Checker.hotc`).
- For each `system`, derive its READ set (params with `is_ref &&
  !is_mut_ref`) and WRITE set (`is_mut_ref`) straight from its own,
  already-checked `run` params — zero new annotation syntax, this is the
  "no separate declaration mechanism" payoff described above.
- Build a conflict graph across every system: two systems conflict if they
  share a component where AT LEAST ONE of them writes it (two readers of
  the same component never conflict — same shared-vs-exclusive rule Phase 3
  already enforces for ordinary `&`/`&mut` params).
- This conflict graph IS the real, new, HC-specific verification — a
  compile-time-provable "these two systems can never alias the same
  component's mutable state" fact. How it gets SURFACED is an open
  question below (a diagnostic report? a derived execution-group ordering
  visible to the programmer? both?).

### Runtime — archetype storage
A `World` holds a set of archetypes. Each archetype is exactly one
component-set's worth of parallel arrays (structure-of-arrays), entity-id
indexed, plus a mapping from entity id to (archetype, row). Spawning an
entity with components {A, B} finds-or-creates the {A, B} archetype and
appends a row; adding/removing a component MOVES that entity's whole row
into a different archetype's arrays (real data movement, not a flag flip).
This is the single biggest engineering chunk of the whole feature — flagged
here explicitly so it isn't underestimated. Correctness first, matching
this project's own established discipline elsewhere — "fast" is not a goal
for the first cut; a query that's merely correct against every matching
archetype ships before a query that's fast against one.

### Codegen
Per-system query-iteration codegen: for each system, walk every archetype
that contains its full component set, calling `run` once per row with the
right component arrays sliced in. The driver executes systems in
DEPENDENCY order — topologically sorted from the checker's own conflict
graph — but sequentially, on one thread, for this phase. That ordering
isn't wasted work: it's exactly the grouping real parallel dispatch would
need later (each group = systems provably safe to run concurrently), so
Phase B (real parallelism) becomes "dispatch each group onto Phoenix
Flight's pool instead of running it inline" rather than a redesign.

## Explicitly deferred (not silently dropped — tracked here)

- Archetype-transition performance work (batching moves, avoiding
  reallocation churn).
- ~~Real multi-threaded dispatch of non-conflicting systems onto Phoenix
  Flight's own pool~~ — landed (Phase B, see "Status" at the top).
  ~~Its own follow-up performance gap~~ (the dispatch pool being recreated
  and torn down on every single `world.run_all()` call) — also landed,
  2026-09-10: `__world_pool` is now a real, program-wide static, created
  once and REUSED across every call, at the cost of putting a real,
  disclosed new obligation on the program: nothing inside `run_all()`
  itself can shut the pool down anymore (it might be called again), so a
  program that ever created one (`pool_size > 1` -- i.e., at least one real
  parallel group) now has to call the new `world.shutdown()` once, near
  the end, or the process hangs forever after `main()` returns exactly
  the same way a forgotten `PhoenixPool::shutdown()` already does. A
  program where every group tops out at 1 system never creates a pool at
  all, and `world.shutdown()` is then a real, harmless no-op — see
  `Codegen.hotc`'s own `build_world_shutdown_body_ecs` header. Verified by
  re-running `examples/ecs_parallel_test.hc` (now calling `world.shutdown
  ()`) and confirming it still produces the right output AND exits fast
  (~3.5s, vs. hanging before this fix existed without the call).
- ~~Query filters beyond a flat "has these components"~~ — landed, 2026-09-10: `Without<T>`
  (compile-time archetype exclusion) and `Changed<T>` (real per-row runtime change-tracking, a
  parallel `Vec<Bool>` per component per archetype) both work. See `examples/ecs_query_filters.hc`/
  `examples/ecs_changed_filter.hc`.
- ~~The `@profile` marker~~ — **landed, 2026-09-10**: a profiled system's own dispatch fn times
  itself with a synthesized `__ProfileClock::nanoTime()` extern (`java.lang.System.nanoTime()`
  under a private HC-level name, only added to a program that actually has one `@profile`'d
  system) and prints "which system, whether it ran in a real parallel group (and how big), how
  long" -- exactly the structured event this entry originally asked for. Lives INSIDE the
  dispatch fn body (`build_system_dispatch_body_ecs`), not wrapped around the call site, since a
  size>1 group's own call site is a `pool.spawn(|| ... as PhoenixObject)` lambda whose body is a
  single `Expr` with nowhere to fit a timing sequence.

  **Used to answer the real question this whole list existed to eventually ask**: is archetype-
  transition/iteration performance an actual bottleneck, or premature optimization? Ran two real
  stress tests (`examples/ecs_stress_test.hc`, `examples/ecs_stress_test_transitions.hc`) via
  `SelfhostCLI --explain-schedule run <path>`, timed with real `nanoTime()` per-system readings,
  not just wall-clock:
  - **100,000 entities, 200 ticks, 3 systems (one real parallel group of 2, one serialized after
    it)**: every single profiled dispatch call rounded to `0ms`/`1ms` (sub-millisecond) at
    STEADY STATE (last 50 of 200 ticks) -- `Movement` averaged `0.08ms`, `Damage` `0.06ms`,
    `Regen` (the one with a `Changed<T>` filter) rounded to `0ms`. Total process wall time
    (JVM start + compile + all 200 ticks): under 1 second.
  - **20,000 entities, 50 add-then-remove cycles (2,000,000 total archetype moves, the
    swap-with-last+pop machinery's own worst case)**: completed in ~0.6-0.7s of actual compute
    (total wall time ~1.07s, JVM/compile startup ~0.4s of that) -- no quadratic blowup, no
    visible slowdown across cycles.

  **Verdict: archetype-transition/iteration performance is NOT a real bottleneck at any scale
  this project's actual target use case (game/mod entity counts, realistically low thousands, not
  hundreds of thousands) would ever hit.** The "Archetype-transition performance work" item right
  above this one stays deferred, now on STRONGER evidence than just "correctness first, don't
  optimize yet" -- there's nothing here worth optimizing at the scale that matters, and premature
  batching/reallocation-avoidance work would be solving a problem that doesn't exist.

## Resolved decisions

Answered in this file directly (2026-09-09) — kept as the record of what was
decided and why, not just a bare answer key. A smaller round of follow-up
questions the answers themselves raise is below, in "Still open."

1. **Syntax/attributes**: confirmed — `system` needs its own attributes, not
   just a bare declaration. Proposed concrete mechanism (see "Still open" #1
   for the one piece still needing a pick): reuse the EXISTING bare-directive
   convention this language already has (`@must_use`, `@sendable`, `@dev`),
   e.g. `@after(Movement)` / `@before(Render)` for ordering hints, `@profile`
   (already named in `IDEAS.md`'s own entry), `@run_if(condition)` for run
   conditions — no new directive MECHANISM, just new directive names valid on
   a `system` block specifically, the same way `@sendable` is only valid on
   `struct`.
2. **Invocation model**: confirmed — explicit and transparent, but still easy
   to use. Resolution: `world.run_all();` is the ergonomic single call (no
   hand-written per-system boilerplate, no hidden magic tick loop the
   language imposes), but its BEHAVIOR is fully transparent because it's
   built entirely from inspectable pieces — the exact derived order it runs
   in is the same thing "Still open" #2 (schedule surfacing) makes visible,
   so "explicit" is satisfied by the schedule being inspectable/loggable,
   not by forcing the programmer to write `world.run(Movement); world.run(
   Physics);` by hand. `world.run(SomeSystem)` (single-system, fully manual)
   stays available too, for anyone who wants to bypass the derived order
   entirely.
3. **Entity spawn/despawn**: confirmed — "the first one," meaning components
   CAN be added to/removed from an entity after it's spawned, triggering a
   real archetype move. Archetype transitions are core v1 scope, not
   deferred — consistent with committing to archetype storage at all.
4. **Ordering fallback**: "a good mix of bug-repro safety and transparency" —
   resolved as: deterministic BY declaration order when the conflict graph
   doesn't decide it (the repro-safety half — reruns behave identically), and
   that fallback ordering is always shown by the same schedule-reporting
   mechanism from #6/"Still open" #2 (the transparency half), so it's a
   documented, inspectable default rather than a silent trap.
5. **Shared primitive with `@sendable`**: confirmed — yes. Concrete shape:
   factor the underlying "is this type safe to touch across a thread
   boundary" predicate (today: `Codegen.hotc`'s own inline Copy-primitive-or-
   `@sendable`-struct check inside `finish_method_call`'s `PhoenixPool::spawn`
   gate) into one reusable primitive, then require every `component` type to
   ALSO satisfy it before Phase B (real parallel dispatch) is allowed to run
   that component's systems concurrently. Not enforced in Phase A at all
   (nothing runs concurrently yet, so there's nothing to check) — the
   plumbing is shared now so Phase B doesn't need a second implementation of
   the same rule later.
6. **Surfacing the schedule**: confirmed — both. A compiler flag (name TBD —
   `--explain-schedule` was the placeholder) that prints the full derived
   order/parallel-groups for every `system` in a program, AND a build-time
   warning specifically when two systems share a component and conflict on
   it (at least one writes) — telling the author BY NAME which two systems
   and which component forced them to serialize, so losing parallelism is
   never silent.

## Still open

Narrower follow-ups the answers above raise — small enough to resolve here
without a big back-and-forth, but real picks nonetheless:

1. ~~**Attribute syntax specifics**~~ — **Resolved and shipped, 2026-09-17**: yes to both. `@after`/
   `@before` now take a comma-separated list (`@after(Movement, Input)`, `Ast.hc`'s own `SystemDecl.
   after`/`.before` changed from `Option<String>` to `Vec<String>`), and `@run_if(condition_fn)`
   shipped too -- `condition_fn` is any plain, real top-level `fn condition_fn() -> Bool`;
   `Codegen.hotc`'s own `build_system_dispatch_body_ecs` wraps the WHOLE per-tick dispatch (every
   matching archetype row) in `if (condition_fn()) { ... }`, skipping the system entirely for the
   tick when it returns `false`. `Checker.hotc`'s own `check_systems` validates every name in
   both lists still resolves to a real system (an unknown name is dropped from that ONE entry,
   same "downgrade to no constraint" reasoning the old single-target version already had, just
   per-entry now instead of once) and that `@run_if`'s target is a real, zero-arg, `Bool`-returning
   fn -- found the hard way that `check_systems` runs BEFORE ordinary top-level fns get their
   signatures registered into the checker's own `fn_sigs`, so validating against `self.fn_sigs`
   always failed even for a correctly-typed target; fixed by scanning the real `fns: &Vec<FnDecl>`
   list directly instead. `system_ready_ecs`'s own topo-sort readiness check generalized the same
   way (ALL of a system's `@after` targets must be placed, not just one). Verified against
   `examples/ecs_multi_after_run_if.hotc`: `Render` correctly waits on BOTH `Movement` and `Regen`,
   and a `@run_if`-gated system whose condition is always `false` (`Poison`) never runs at all
   across two full `world.run_all()` calls, while a sibling gated `true` (`Regen`) runs every
   time -- exact hand-computed values both ticks. Full example regression suite: zero new
   failures. Self-hosting verified to a true fixed point.
2. run is in v2, and no after cannot name multiple
2. ~~**`--explain-schedule`'s actual output shape**~~ — **Resolved and implemented, 2026-09-10**:
   one parallel-group per line, in RUN order, each listing its own member system names and
   `(parallel)` for any group with more than one member (`Codegen.hotc`'s own `print_schedule_
   report_ecs`) -- the simplest shape that answers "which systems run together, and in what
   order," deliberately not a full dependency-graph dump (the ALREADY-existing per-conflict
   warning, unconditional, is what explains WHY two systems didn't land together, so this report
   doesn't repeat that reasoning). `SelfhostCLI [--target N] --explain-schedule run <path>` -- a
   second, independently optional leading flag, stacking with `--target` the same "shift `off`
   further" way (`run_cli` in `Driver.hotc`). The per-conflict warning itself still fires on EVERY
   build, unconditionally -- the opt-out for rapid iteration (#3, right below) is a real, separate,
   still-unresolved question, not answered by this flag. Verified against `examples/power_demo.hc`
   (which itself doubles as this doc's own "full power" demo -- see ARCHITECTURE.md's own "Phoenix Flight"/
   ECS sections): `--explain-schedule` correctly printed `group 0: Movement, Damage  (parallel)`
   then `group 1: LowHealthAlert`, matching the conflict warning (Damage/LowHealthAlert only) and
   the hand-computed expected trace exactly, with or without `--target` also present.
3. ~~every build, we want safety but also opt out for iteration~~ — **Resolved and shipped,
   2026-09-17**: `--quiet-schedule`, a fourth independently-optional leading CLI flag (stacks with
   `--target`/`--explain-schedule`/`--classpath` the same way, shifting `off` further in
   `Driver.hotc`'s own `run_cli`). Suppresses ONLY the always-on per-conflict warning loop in
   `Codegen.hotc`'s own `compile_program` -- `--explain-schedule`'s own full-schedule report is
   completely unaffected either way, confirming "print more" and "print less" really were
   independent asks. Threaded as a new `quiet_schedule: Bool` param on `compile_program` itself
   (and `run_doc_cli`), all 5 internal self-hosting-bootstrap probe call sites passing `false`
   (never suppress there).
3. **Archetype-move mechanics** — when a component is added/removed post-
   spawn (decision #3), does that take effect immediately (mid-tick), or
   queue until the next `world.run_all()` (avoiding an archetype changing
   out from under a system that's mid-iteration over it, the same "don't
   mutate a collection you're iterating" hazard most ECS designs explicitly
   buffer against)?

   **Superseded, 2026-09-10** — the note directly below (from the SAME day)
   originally shipped `world.add`/`world.remove` as immediate, then queuing
   landed later that same day once the actual design surface turned out to
   be small: `world.add(entity, Value { ... })`/`world.remove(entity, Value)`
   now QUEUE the request (pushed onto a real, per-component-type pending
   `Vec` pair -- `__pending_add_<T>_entities`/`_values`, or `__pending_
   remove_<T>_entities`) instead of applying it immediately. The real move
   (the renamed `__world_apply_add_<T>`/`__world_apply_remove_<T>`, formerly
   just `__world_add_<T>`/`__world_remove_<T>`) only runs at the START of
   the NEXT `world.run_all()` call, before any system runs that tick
   (`build_flush_transitions_stmts_ecs`, prepended to `__world_run_all`'s
   own body) -- draining every pending entry into a real apply call, then
   resetting each pending `Vec` back to empty via a new `Vec::clear()`
   method (`stdlib/vec.hotc`).
   Resolves the "same entity twice before the next flush" question the
   ORIGINAL note raised as a blocker: nothing special is needed, since each
   queued (entity, value) pair is applied independently and in order --
   two queued transitions on the same entity just apply as two sequential
   moves when the flush runs, exactly as if they'd been called immediately
   one after the other.
   Doesn't change observable behavior for the case every existing test
   exercises (`add`/`remove` called from ordinary code BETWEEN `run_all()`
   calls, never from inside a system) — the transition is still fully
   visible by the time the very next `run_all()`'s own systems run, so
   `examples/ecs_archetype_transition_test.hc`'s own output is byte-for-byte
   unchanged. A real, disclosed limit worth naming precisely: the mid-
   iteration hazard this fixes ("calling `world.add`/`world.remove` from
   INSIDE a system's own `run` body would mutate an archetype's arrays
   while `__world_run_all` is iterating them") isn't actually REACHABLE by
   any program today, since a system's own `run(...)` params are ONLY ever
   component references (see the language surface section, above) — there
   is no way for a system to learn its own current entity's id at all, so
   it has nothing to pass to `world.add`/`world.remove` even if it wanted
   to. This fix is real, forward-looking infrastructure for the day a
   system DOES gain access to its own entity id (or a query/callback API
   is added) rather than a fix for an exploit anyone can write today — and
   a real, disclosed edge case of queuing itself: a transition queued and
   never followed by another `world.run_all()` call simply never takes
   effect, same as any ECS with a deferred-transition sync point.
   ~~Implemented, 2026-09-10, as IMMEDIATE, not queued~~ — `world.add(...)`/
   `world.remove(...)` perform the real move synchronously, the instant
   they're called. This was a real, disclosed DIVERGENCE from #4's own
   answer below ("we need to have safety," read as leaning toward queued)
   made for a concrete reason: queuing needs a real buffer + flush point
   (the start of `world.run_all()`, per the question above) and a decision
   about what happens to a buffered transition if the SAME entity gets a
   second one before the next flush — genuine additional design surface,
   not a small addition, and nothing in the codebase depends on it yet
   (Phase A has no concurrent access at all — see decision #5 — so there's
   no actual THREAD safety hazard, only the "mutating a collection you're
   mid-iteration over" hazard the question itself named). Concretely, that
   hazard was real: calling `world.add`/`world.remove` from INSIDE a
   system's own `run` body, mid-`world.run_all()`, was unguarded — it would
   have mutated an archetype's arrays while `__world_run_all`'s own
   generated `For` loop was iterating them. Not exercised by anything at
   the time (both test programs called `add`/`remove` from `main`, between
   `run_all()` calls, never from inside a system) — queuing (above) is the
   fix, now landed.
4. We need to have safety
5. ~~**`Changed<Component>`'s own tick-boundary semantic**~~ — **Resolved and implemented,
   2026-09-10** (see the "Status" note above for the full design and disclosed consequence): reset
   every archetype's every component's own change-flags to `false` at the START of `__world_run_
   all`, right after the queued add/remove flush (which means a flush-time "just added" flag does
   NOT survive to be seen this tick -- the simpler of the two options this question raised, and
   the one that shipped), then any `&mut`-taking system's dispatch sets `true` for every row it
   touches as it runs.
6. ~~**A resource/singleton-injection mechanism**~~ — **Resolved and implemented, 2026-09-15**:
   surfaced (see the "Status" note at the top). Concretely needed: a way for `main()`'s own
   per-frame external state (a live camera position, keyboard/mouse input, a `FrameTimer`'s own
   delta time -- `stdlib/graphics.hotc`, added alongside this same session's work) to reach a
   system's own `run` body, and/or a way for ordinary caller code to read a component's value
   back out after `world.run_all()` returns (right now NEITHER direction exists -- `World` only
   ever exposes `spawn`/`run_all`/`add`/`remove`/`shutdown`). Real prior art from other ECS
   designs: a "resource" is a single, world-wide value (not per-entity) a system can request
   alongside its ordinary `&`/`&mut` component params -- e.g. `fn run(t: &mut Transform, dt:
   &DeltaTime)` where `DeltaTime` is set once per tick via something like `world.set_resource
   (DeltaTime { seconds: timer.delta_seconds })` right before `run_all()`, and read (never
   archetype-matched, since there's exactly one of it) by any system that names it. Real open
   questions this raises, not yet answered:
   - Is a resource declared with the SAME `component` keyword (relying on there being no
     `Entity` row for it to distinguish it from an ordinary per-entity component), or a new,
     separate keyword (`resource Name { fields }`)? The former reuses existing grammar/checker
     machinery; the latter is more honest about "this is a different kind of thing, matched
     differently."
   - Read-only resources (`&DeltaTime`) are the easy, obviously-safe case -- ordinary borrow
     rules apply, no new conflict analysis needed. A MUTABLE resource a system can read AND
     write (as opposed to one only `main()` ever sets before `run_all()`) reopens the same
     ownership-conflict analysis `check_systems` already does for components -- does a resource
     participate in that same conflict graph, or get its own, simpler rule ("at most one system
     may take it by `&mut` at all, full stop, since there's only ever one of it")?
   - Does reading a component's value back out after `run_all()` need its own real query API on
     `World` (`world.get::<Transform>(entity_id) -> Transform`, using the entity id `world.spawn`
     already returns), independent of the resource question above -- or does solving resources
     make that need go away in practice, since a caller who needs a value back could route it
     through a mutable resource a LATER system writes into instead?
   Real, disclosed scope note: nothing above is small — this is genuinely open design work, not a
   "just add a method" gap, closer in size to the original archetype-storage decision than to any
   single "Still open" item resolved above.

   **Answers, now that it's built**: a NEW, separate `resource` keyword (not `component`) --
   the "more honest about being a different kind of thing" option won, and it cost nothing extra
   (`Checker.hotc`'s own `resources` registry, `Codegen.hotc`'s own `resource_field_name_ecs`
   naming, both small, parallel copies of the `component` machinery). Mutable resources DO
   participate in the SAME conflict graph ordinary components use (folded into `check_systems`'s
   own `sys_reads`/`sys_writes`, unchanged) -- no separate, simpler rule needed; two systems
   fighting over the same resource by `&mut` (or one `&mut` while another reads) genuinely can't
   run in parallel, exactly like a shared component, and the existing conflict-warning machinery
   catches it for free. The "read a component back out after `run_all()`" question is NOT solved
   by this -- that's still a real, separate gap (no query API on `World` exists) -- but in
   practice it hasn't needed solving: every real use so far (Marshmallow's `Camera`) is `main()`
   pushing state IN, never reading component state back OUT, so resources answered the actual
   need without requiring the harder half.

   **Built, 2026-09-24**: `world.get(id)`, the real query API this "Still open" item's own answer
   above explicitly left unsolved. No real generic-method syntax (`world.get::<Transform>(id)`) --
   the queried component type is read back from the caller's own declared `Option<T>` result type
   (`let t: Option<Transform> = world.get(id);`), the same "hint via declared type" convention
   comprehensions already established. One `__world_get_<T>` helper fn synthesized per distinct
   queried type (`Codegen.hotc`'s own `build_world_get_fn_ecs`), searching every archetype that
   could hold `T` (including ones only reachable via `world.add`/`world.remove`, not just the
   originally-spawned set) linearly for a matching entity id. Two real, previously-hit gaps found
   building it: (1) `Option<T>`'s own generic enum template is never even PARSED unless something
   pulls in the `option` stdlib topic, and `use ecs;` alone didn't -- `world.get` returning a real
   `Option<T>` meant `ecs` now needs to transitively depend on `option` too, the same "independent
   of whether the user's own source ever writes it" reasoning `vec`/`phoenix` already established
   for the identical reason; (2) even with `Option<T>` loaded, a caller's own `match result { Some
   {...} => ..., None => ... }` still needs `Checker.hotc`'s own `ensure_instantiated` called on
   the exact concrete `"Option$T"` mono name at least once somewhere in the same compile for its
   variant-ownership table to exist at all -- `check_world_get` calls it explicitly rather than
   relying on some OTHER call site happening to construct a real `Some`/`None` literal first.
7. **A "never runs off the main thread" scheduling constraint** — the real gap found adding a
   second OpenGL-calling system (see the "Status" note at the top, 2026-09-15). The ownership-
   conflict analysis this whole scheduler is built on only ever reasons about HC-visible `&`/
   `&mut` component access -- it has NO concept of "this system's own body also touches a
   thread-affine external resource" (a GL context, here, but the same hazard applies to anything
   else that's only safe from one specific thread). Two systems with zero component conflict
   between them can still be UNSAFE to run in parallel for a reason the conflict graph can never
   see. Real open questions, not yet answered:
   - A per-system marker (`@main_thread` or similar) that forces a system into its own,
     never-parallel group regardless of what its conflict analysis would otherwise permit --
     simplest to specify, but silently loses real parallelism for systems that merely SHARE a
     thread-affine resource by convention (every rendering system, say) unless the AUTHOR
     remembers to mark each one.
   - Should `window`/`graphics`'s own stdlib functions carry some real, checker-visible
     "main-thread-only" fact that AUTOMATICALLY marks any system calling into them, so a caller
     doesn't have to remember the annotation by hand? This is more robust but needs a real way
     for the checker to know a called FUNCTION (not just a directly-borrowed component) has this
     property, and to propagate it through arbitrary call chains inside a system's own body --
     genuinely new analysis, not a small addition.
   - Is "main thread" even the right generalization, or does this need to be "this GROUP of
     systems must all run on the SAME thread as each other" (not necessarily the main one) for
     other thread-affine resources that aren't tied to the main thread specifically? Marshmallow's
     own case happens to need the main thread specifically (GLFW/GL context creation is itself
     main-thread-bound), but a fully general answer shouldn't assume that's always true.
   Worked around in Marshmallow by hand (a single, deliberate, fully sequential `@after` chain
   across every system that touches OpenGL) -- real, but not a fix; the NEXT program that adds a
   second independent rendering system without knowing about this will hit the exact same crash.