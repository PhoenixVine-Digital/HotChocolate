// ECS with ownership-derived scheduling (see ECS_IDEAS.md) -- Phase A, checker slice: components
// and systems register and get validated, a system's read/write set is derived straight from its
// own `&`/`&mut` run params (no separate declaration needed), and the compiler warns (at every
// build, for now -- see ECS_IDEAS.md's own "Still open" #2) whenever two systems can't be
// scheduled in parallel because they both touch a component and at least one writes it.
//
// No `World`/archetype runtime yet, and nothing actually RUNS these systems yet -- that's the
// next slice. This program only exercises registration/validation/the conflict warning.

component Transform { x: Int, y: Int }
component Velocity { dx: Int, dy: Int }

// Movement WRITES Transform and READS Velocity.
system Movement {
    fn run(transforms: &mut Transform, velocities: &Velocity) {
        transforms.x = transforms.x + velocities.dx;
        transforms.y = transforms.y + velocities.dy;
    }
}

// Render only READS Transform -- but Movement WRITES it, so these two conflict (a real warning,
// not an error: they just can't run in parallel, forced sequential -- and `@after(Movement)`
// below is exactly how you'd tell the scheduler the sequential order you actually want).
@after(Movement)
system Render {
    fn run(transforms: &Transform) {
        print("{transforms.x}");
    }
}

fn main() {
    print("registered ok");
}
