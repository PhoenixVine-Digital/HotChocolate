// Phoenix Flight -- task failure + pool lifecycle. See stdlib/phoenix.hotc's own header for the
// full design; this exercises the two things `phoenix_flight.hc` doesn't:
//
// 1. A task that THROWS -- `join()` re-throws the REAL exception (ArithmeticException here),
//    not java.util.concurrent.ExecutionException, so an ordinary `catch` around `task.join()`
//    catches exactly what a synchronous call would have thrown.
// 2. `PhoenixPool::join_all_and_shutdown` -- spawn several tasks, wait for all of them, and let
//    the pool shut itself down in one call, instead of joining each by hand and remembering
//    `shutdown()` separately.
use phoenix;

extern class ArithmeticException = "java.lang.ArithmeticException" {
    fn getMessage(&self) -> String;
}

fn risky(a: Int, b: Int) -> Int {
    return a / b;
}

fn do_work(id: Int) -> Int {
    print("task {id} running");
    return id;
}

fn main() {
    let pool = PhoenixPool::new(2);

    let bad_task = pool.spawn(|| "{risky(10, 0)}" as PhoenixObject);
    try {
        let r = bad_task.join() as String;
        print("unreachable: {r}");
    } catch (e: ArithmeticException) {
        print("caught real exception: {e.getMessage()}");
    }
    pool.shutdown();

    let pool2 = PhoenixPool::new(3);
    var tasks = vec_of(pool2.spawn(|| "{do_work(1)}" as PhoenixObject));
    tasks.push(pool2.spawn(|| "{do_work(2)}" as PhoenixObject));
    tasks.push(pool2.spawn(|| "{do_work(3)}" as PhoenixObject));
    pool2.join_all_and_shutdown(&tasks);
    print("all done");
}
