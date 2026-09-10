// Phoenix Flight -- real CompletableFuture-based async chaining. See stdlib/phoenix.hotc's own
// header. Unlike spawn_after (examples/phoenix_flight_chaining.hc), NOTHING blocks waiting for a
// dependency here -- each stage is submitted the instant the one before it finishes.
use phoenix;

fn step_a() -> Int {
    print("step a running");
    return 10;
}

fn step_b(prev: Int) -> Int {
    print("step b running, prev was ignored on purpose");
    return prev + 1;
}

fn step_c(prev: Int) -> Int {
    print("step c running, prev was ignored on purpose");
    return prev + 1;
}

fn main() {
    let pool = PhoenixPool::new(4);

    let chain0 = pool.spawn_chain(|| "{step_a()}" as PhoenixObject);
    let chain1 = chain0.then(|prev| "{step_b(0)}" as PhoenixObject);
    let chain2 = chain1.then(|prev| "{step_c(0)}" as PhoenixObject);
    print(chain2.join() as String);

    pool.shutdown();
}
