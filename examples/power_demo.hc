// Hot Chocolate power demo -- Phoenix Flight (background task pool) and ECS (ownership-derived
// scheduling) working together in one small program. See PHOENIX_FLIGHT_IDEAS.md/ECS_IDEAS.md
// for the full design behind each piece touched here.

use phoenix;
use ecs;

// ============================================================================
// Part 1: Phoenix Flight -- a real background task pool, not a toy wrapper.
// ============================================================================

fn compute_level_seed() -> Int {
    return 6 * 7;
}

fn run_phoenix_flight_demo() {
    let pool = PhoenixPool::new(2);

    // A real background computation with a REAL, unboxed primitive result -- `join()` used to be
    // reference-types-only; `as Int` now does a genuine box/unbox pair (Codegen.hotc's own
    // gen_cast), not a String round-trip.
    let seed_task = pool.spawn(|| compute_level_seed() as PhoenixObject);
    print("level seed: {seed_task.join() as Int}");

    // Real CompletableFuture-based chaining -- nothing blocks waiting between stages, unlike
    // spawn_after's own dependency-ordered-but-blocking sibling (see examples/phoenix_flight_
    // chaining.hc for that one).
    let chain = pool.spawn_chain(|| "loading assets" as PhoenixObject);
    let chain2 = chain.then(|_| "assets ready" as PhoenixObject);
    print(chain2.join() as String);

    pool.shutdown();
}

// ============================================================================
// Part 2: ECS -- components, systems, real scheduling, real archetype storage.
// ============================================================================

component Transform { x: Int, y: Int }
component Velocity { dx: Int, dy: Int }
component Health { hp: Int }
component Shielded { marker: Int }

// Movement and Damage touch DISJOINT components (Transform+Velocity vs Health) -- the checker's
// own conflict analysis puts them in the SAME parallel group, dispatched onto a real Phoenix
// Flight pool under the hood (Phase B), with nothing in this file asking for that explicitly.
// Real, observable evidence this actually happens: the compiler's own conflict warning below
// fires for Damage/LowHealthAlert (they share Health), but never for Movement/Damage.
system Movement {
    fn run(t: &mut Transform, v: &Velocity) {
        t.x = t.x + v.dx;
        t.y = t.y + v.dy;
    }
}

// `Without<Shielded>` -- a pure, compile-time archetype exclusion. A shielded entity's own
// archetype is skipped entirely; this system's dispatch loop never even reaches it.
system Damage {
    fn run(h: &mut Health, s: Without<Shielded>) {
        h.hp = h.hp - 5;
    }
}

// `@after(Damage)` -- reads Health, which Damage writes, so the checker forces this to run AFTER
// it rather than risk aliasing (and warns about the conflict, since that's real lost
// parallelism). `Changed<Health>` -- a real, per-row RUNTIME filter, distinct from `Without<T>`'s
// compile-time one: only entities Damage ACTUALLY wrote this tick reach the body below, not just
// entities that happen to be low already.
@after(Damage)
system LowHealthAlert {
    fn run(h: &Health, c: Changed<Health>) {
        if h.hp <= 5 {
            print("low health: {h.hp}");
        }
    }
}

fn run_ecs_demo() {
    var world = World::new();
    let e1 = world.spawn(Transform { x: 0, y: 0 }, Velocity { dx: 1, dy: 1 }, Health { hp: 20 });
    world.spawn(Transform { x: 5, y: 5 }, Velocity { dx: -1, dy: 0 }, Health { hp: 8 }, Shielded { marker: 1 });
    let e3 = world.spawn(Transform { x: 9, y: 9 }, Velocity { dx: 0, dy: -1 }, Health { hp: 10 });

    world.run_all(); // tick 1: e1 20->15, e3 10->5 (alert -- just became low), e2 shielded, untouched

    // A real archetype TRANSITION, mid-simulation: e3 finds a shield. Queued -- takes effect at
    // the START of the NEXT world.run_all(), not immediately (see ECS_IDEAS.md's own "Archetype-
    // move mechanics" note on why: applying it right now, between ticks, is safe either way, but
    // the queuing exists so it's ALSO safe to call this from inside a system's own body later).
    world.add(e3, Shielded { marker: 1 });

    world.run_all(); // tick 2: e3 now shielded -- Damage skips it, Changed<Health> false, no
                      // alert even though its hp is still 5 (stale-but-low, correctly not
                      // re-reported). e1 15->10, no alert.
    world.run_all(); // tick 3: e1 10->5 -- alert (just became low).

    world.shutdown(); // required: Movement/Damage's own parallel group created a real thread pool.
}

fn main() {
    run_phoenix_flight_demo();
    run_ecs_demo();
}
