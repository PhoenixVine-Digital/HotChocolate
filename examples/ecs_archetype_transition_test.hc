// ECS archetype-transition end-to-end test (see ECS_IDEAS.md's "Archetype-transition mechanics")
// -- world.add(entity, Component{...})/world.remove(entity, Component) move an already-spawned
// entity's row between archetypes for real. Two entities: one spawned WITH Velocity, one WITHOUT.
// The one without gets Velocity added mid-program (moving Transform-only -> Transform+Velocity);
// the one with it gets Velocity removed (moving the other way). Movement only runs against
// entities that currently have both components, so run_all()'s own output proves the moves
// actually took effect on the RIGHT entities, not just that nothing crashed.
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
    let e1 = world.spawn(Transform { x: 0, y: 0 }, Velocity { dx: 1, dy: 1 });
    let e2 = world.spawn(Transform { x: 100, y: 100 });
    print("--- before any transition ---");
    world.run_all(); // only e1 moves (has Velocity); e2 stays put

    world.add(e2, Velocity { dx: 5, dy: 0 });
    world.remove(e1, Velocity);
    print("--- after add/remove ---");
    world.run_all(); // now only e2 moves; e1 is stuck at its last position
    world.run_all(); // e2 moves again; e1 still stuck
}
