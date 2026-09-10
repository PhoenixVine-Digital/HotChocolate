// ECS runtime end-to-end test (see ECS_IDEAS.md) -- unlike examples/ecs_scheduling.hc (which only
// exercises registration/validation/the conflict warning), this one actually spawns entities and
// runs systems: `world.spawn(...)` builds real archetype storage, `world.run_all()` runs every
// system once per matching entity in dependency order. Expected output, two calls to
// `world.run_all()`:
//   1 2     <- entity 1 after run #1: Transform{0,0} + Velocity{1,2}
//   9 10    <- entity 2 after run #1: Transform{10,10} + Velocity{-1,0}
//   2 4     <- entity 1 after run #2
//   8 10    <- entity 2 after run #2
use ecs;

component Transform { x: Int, y: Int }
component Velocity { dx: Int, dy: Int }

system Movement {
    fn run(transforms: &mut Transform, velocities: &Velocity) {
        transforms.x = transforms.x + velocities.dx;
        transforms.y = transforms.y + velocities.dy;
    }
}

@after(Movement)
system Render {
    fn run(transforms: &Transform) {
        print("{transforms.x} {transforms.y}");
    }
}

fn main() {
    var world = World::new();
    world.spawn(Transform { x: 0, y: 0 }, Velocity { dx: 1, dy: 2 });
    world.spawn(Transform { x: 10, y: 10 }, Velocity { dx: -1, dy: 0 });
    world.run_all();
    world.run_all();
}
