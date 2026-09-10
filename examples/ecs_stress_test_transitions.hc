// Archetype-transition stress test -- the SPECIFIC deferred concern in ECS_IDEAS.md's own
// "Explicitly deferred" list ("Archetype-transition performance work (batching moves, avoiding
// reallocation churn)"). Every entity gets a Tag added, then removed, every single tick -- the
// worst realistic case for the swap-with-last+pop move machinery.
use ecs;

component Position { x: Int }
component Tag { marker: Int }

@profile
system Noop {
    fn run(p: &Position) {
    }
}

fn main() {
    var world = World::new();
    var i = 0;
    var ids = vec_of(0);
    while i < 20000 {
        let e = world.spawn(Position { x: i });
        if i == 0 {
            ids.set(0, e);
        } else {
            ids.push(e);
        }
        i = i + 1;
    }

    var t = 0;
    while t < 50 {
        var a = 0;
        while a < ids.length() {
            world.add(ids.get(a), Tag { marker: 1 });
            a = a + 1;
        }
        world.run_all(); // flush applies all 2000 queued adds
        var r = 0;
        while r < ids.length() {
            world.remove(ids.get(r), Tag);
            r = r + 1;
        }
        world.run_all(); // flush applies all 2000 queued removes
        t = t + 1;
    }
    print("transition stress test done: 2000 entities, 20 add/remove cycles each");
}
