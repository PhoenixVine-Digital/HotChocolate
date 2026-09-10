// Phoenix Flight -- cancellation + join_all. See stdlib/phoenix.hotc's own header.
use phoenix;

extern class PhoenixThread = "java.lang.Thread" {
    static fn sleep(millis: Long) -> Unit;
}

fn slow(id: Int) -> Int {
    PhoenixThread::sleep(200 as Long);
    print("slow task {id} finished");
    return id;
}

fn quick(id: Int) -> Int {
    print("quick task {id} finished");
    return id;
}

fn main() {
    // A single-thread pool, so the second task can only START once the first one finishes --
    // cancelling it right after spawning (before the first task's own 200ms sleep is up) means
    // it never runs at all.
    let pool = PhoenixPool::new(1);
    let t1 = pool.spawn(|| "{slow(1)}" as PhoenixObject);
    let t2 = pool.spawn(|| "{quick(2)}" as PhoenixObject);
    let cancelled = t2.cancel();
    print("cancel() returned {cancelled}");
    print("t2.is_cancelled() = {t2.is_cancelled()}");
    t1.join();
    print("t1.is_done() = {t1.is_done()}");
    pool.shutdown();

    // join_all: several tasks on a fresh pool, every result collected in order.
    let pool2 = PhoenixPool::new(3);
    var tasks = vec_of(pool2.spawn(|| "{quick(10)}" as PhoenixObject));
    tasks.push(pool2.spawn(|| "{quick(20)}" as PhoenixObject));
    tasks.push(pool2.spawn(|| "{quick(30)}" as PhoenixObject));
    let results = join_all(&tasks);
    var i = 0;
    while i < results.length() {
        print(results.get(i) as String);
        i = i + 1;
    }
    pool2.shutdown();
}
