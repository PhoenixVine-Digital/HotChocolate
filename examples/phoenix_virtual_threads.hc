// Phoenix Flight -- virtual threads. See stdlib/phoenix_virtual.hotc's own header. Gated at
// --target 21 or higher (java.lang.Thread's virtual-thread support is stable since JDK 21).
use phoenix_virtual;

fn work() -> Int {
    print("running on a virtual thread");
    return 42;
}

fn main() {
    let pool = PhoenixVirtualPool::new();
    let task = pool.spawn(|| "{work()}" as PhoenixObject);
    print(task.join() as String);
    pool.shutdown();
}
