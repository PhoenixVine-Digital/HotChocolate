// ECS Phase B end-to-end test (see ECS_IDEAS.md's "Explicitly deferred" -> Phase B, now landed)
// -- Health and Shield touch DISJOINT components, so the checker's own conflict analysis puts
// them in the SAME parallel group (no shared warning fires, unlike ecs_run_all_test.hc's own
// Movement/Render pair) -- this exercises the REAL pool.spawn(...)/join() dispatch path, not just
// the size-1-group direct-call path every other ECS test so far has used.
//
// `world.shutdown()` at the end matters here specifically: Phase B's dispatch pool is a REAL
// `PhoenixPool`, REUSED across every `world.run_all()` call rather than recreated each time (a
// real performance fix -- see ECS_IDEAS.md's own "Phase B" writeup) -- which means nothing
// inside `run_all()` itself ever shuts it down anymore. Skip this call and the process hangs
// forever after main() returns (`Executors.newFixedThreadPool`'s worker threads aren't daemon
// threads), the exact same contract `PhoenixPool::shutdown()` already puts on ordinary Phoenix
// Flight callers.
use ecs;

component Health { hp: Int }
component Shield { sp: Int }

system Regen {
    fn run(h: &mut Health) {
        h.hp = h.hp + 1;
    }
}

system Recharge {
    fn run(s: &mut Shield) {
        s.sp = s.sp + 10;
    }
}

// No `@after` needed at all -- `Report` reads BOTH `Health` (written by `Regen`) and `Shield`
// (written by `Recharge`), so the checker's own conflict analysis alone defers it to the next
// round after both of those, with zero explicit ordering hints.
system Report {
    fn run(h: &Health, s: &Shield) {
        print("{h.hp} {s.sp}");
    }
}

fn main() {
    var world = World::new();
    world.spawn(Health { hp: 0 }, Shield { sp: 0 });
    world.run_all();
    world.run_all();
    world.run_all();
    world.shutdown();
}
