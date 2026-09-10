// Phoenix Flight -- a task result that's a real primitive (Int/Bool), not a String. See
// stdlib/phoenix.hotc's own header: task.join() returns a raw PhoenixObject (java.lang.Object-
// erased). `task.join() as Int` now does a genuine Integer.valueOf/.intValue() box/unbox pair
// (Codegen.hotc's own gen_cast), instead of the old "reference types only" scope cut.
use phoenix;

fn compute() -> Int {
    return 6 * 7;
}

fn is_even(n: Int) -> Bool {
    return n / 2 * 2 == n;
}

fn main() {
    let pool = PhoenixPool::new(2);

    let int_task = pool.spawn(|| compute() as PhoenixObject);
    print(int_task.join() as Int);

    let bool_task = pool.spawn(|| is_even(10) as PhoenixObject);
    print(bool_task.join() as Bool);

    pool.shutdown();
}
