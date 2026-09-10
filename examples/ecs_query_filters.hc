// ECS query filters (see ECS_IDEAS.md's own "Query filters" design note): `Without<Component>`
// excludes any archetype that HAS that component, entirely, at compile-time-derived archetype-
// matching time -- no `&`/`&mut` on the param at all, since it borrows nothing.
//
// Two entities: one plain (Transform + Velocity, "moving"), one with a Frozen marker component
// added (Transform + Velocity + Frozen, "frozen"). `Movement` (no filter) moves BOTH -- it
// doesn't print anything, so that's not directly visible in the output below. `Render` uses
// `Without<Frozen>`, so its own archetype-matching loop skips the frozen entity's archetype
// entirely (never even reaches the per-row loop for it) and only ever prints the moving one.
//
// Expected output (one line -- Render only ever visits the one archetype without Frozen):
//   1 2     <- the moving entity, after Movement's own update (0,0)+(1,2)
use ecs;

component Transform { x: Int, y: Int }
component Velocity { dx: Int, dy: Int }
component Frozen { marker: Int }

system Movement {
    fn run(transforms: &mut Transform, velocities: &Velocity) {
        transforms.x = transforms.x + velocities.dx;
        transforms.y = transforms.y + velocities.dy;
    }
}

@after(Movement)
system Render {
    fn run(transforms: &Transform, frozen: Without<Frozen>) {
        print("{transforms.x} {transforms.y}");
    }
}

fn main() {
    var world = World::new();
    world.spawn(Transform { x: 0, y: 0 }, Velocity { dx: 1, dy: 2 });
    world.spawn(Transform { x: 10, y: 10 }, Velocity { dx: -1, dy: 0 }, Frozen { marker: 1 });
    world.run_all();
}
