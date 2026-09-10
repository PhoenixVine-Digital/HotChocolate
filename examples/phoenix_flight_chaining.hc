// Phoenix Flight -- spawn_after / spawn_after_all. See stdlib/phoenix.hotc's own header for the
// real trade-off (blocks the CALLER on the dependency, not true async chaining).
use phoenix;

fn step(id: Int) -> Int {
    print("step {id} running");
    return id;
}

fn main() {
    let pool = PhoenixPool::new(4);

    // A -> B: B only starts once A has actually finished.
    let a = pool.spawn(|| "{step(1)}" as PhoenixObject);
    let b = pool.spawn_after(&a, || "{step(2)}" as PhoenixObject);
    print(b.join() as String);

    // (C, D) -> E: E only starts once BOTH C and D have finished.
    let c = pool.spawn(|| "{step(3)}" as PhoenixObject);
    let d = pool.spawn(|| "{step(4)}" as PhoenixObject);
    var deps = vec_of(c);
    deps.push(d);
    let e = pool.spawn_after_all(&deps, || "{step(5)}" as PhoenixObject);
    print(e.join() as String);

    pool.shutdown();
}
