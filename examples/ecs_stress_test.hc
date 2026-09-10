// ECS performance stress test -- @profile'd systems, many entities, many ticks. Real motivating
// question (see ECS_IDEAS.md's own "Explicitly deferred" list): is archetype-transition/
// iteration performance an ACTUAL bottleneck at realistic scale, or premature to optimize?
use ecs;

component Transform { x: Int, y: Int }
component Velocity { dx: Int, dy: Int }
component Health { hp: Int }
component Tag { marker: Int }

@profile
system Movement {
    fn run(t: &mut Transform, v: &Velocity) {
        t.x = t.x + v.dx;
        t.y = t.y + v.dy;
    }
}

@profile
system Damage {
    fn run(h: &mut Health) {
        h.hp = h.hp - 1;
    }
}

@after(Damage)
@profile
system Regen {
    fn run(h: &mut Health, c: Changed<Health>) {
        if h.hp < 0 {
            h.hp = 100;
        }
    }
}

fn main() {
    var world = World::new();
    var i = 0;
    while i < 50000 {
        world.spawn(Transform { x: i, y: i }, Velocity { dx: 1, dy: -1 }, Health { hp: 100 });
        i = i + 1;
    }
    var j = 0;
    while j < 50000 {
        world.spawn(Transform { x: j, y: j }, Velocity { dx: 1, dy: -1 }, Health { hp: 100 }, Tag { marker: 1 });
        j = j + 1;
    }

    var t = 0;
    while t < 200 {
        world.run_all();
        t = t + 1;
    }
    world.shutdown();
    print("stress test done: 100000 entities, 200 ticks");
}
