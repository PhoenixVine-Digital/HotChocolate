// ECS `Changed<Component>` query filter (see ECS_IDEAS.md's own "Query filters" design note).
// A per-archetype-row `Vec<Bool>` change flag per component is reset to `false` at the start of
// every `world.run_all()` call, then set `true` for any row a `&mut`-taking system's own
// dispatch touches THAT SAME call. `Changed<T>` reads the flag, skipping `run` entirely for a
// row where it's `false` -- real, disclosed tick-boundary rule: a `Changed<T>` reader only sees
// writes from `&mut` systems SCHEDULED BEFORE it, same tick (see Checker.hotc's own header).
//
// Move (writes &mut Transform, skips the Frozen entity via Without<Frozen>) runs before Watcher
// (@after(Move), reads Changed<Transform>) -- so Watcher only ever prints the moving entity,
// every tick, even though BOTH entities have a real Transform component.
//
// Expected output (one line per world.run_all() call -- only the moving entity, never Frozen):
//   1 2
//   2 4
use ecs;

component Transform { x: Int, y: Int }
component Velocity { dx: Int, dy: Int }
component Frozen { marker: Int }

system Move {
    fn run(t: &mut Transform, v: &Velocity, f: Without<Frozen>) {
        t.x = t.x + v.dx;
        t.y = t.y + v.dy;
    }
}

@after(Move)
system Watcher {
    fn run(t: &Transform, c: Changed<Transform>) {
        print("{t.x} {t.y}");
    }
}

fn main() {
    var world = World::new();
    world.spawn(Transform { x: 0, y: 0 }, Velocity { dx: 1, dy: 2 });
    world.spawn(Transform { x: 10, y: 10 }, Velocity { dx: -1, dy: 0 }, Frozen { marker: 1 });
    world.run_all();
    world.run_all();
}
