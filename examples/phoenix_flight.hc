// Phoenix Flight -- a real background task pool with compile-time capture safety. See
// README's own "Phoenix Flight" and "Compile-time concurrency safety" sections for the full
// design, and `stdlib/phoenix.hotc`'s own header for the disclosed scope cuts.

use phoenix;

@sendable struct Config {
    multiplier: Int,
}

fn slow_double(x: Int, cfg: Config) -> Int {
    return x * cfg.multiplier;
}

fn main() {
    let pool = PhoenixPool::new(4);
    let cfg = Config { multiplier: 2 };

    // `cfg` is `@sendable` -- a plain Int field, safe to hand across the thread boundary -- so
    // capturing it into the background task is allowed. Capturing a NON-`@sendable` struct here
    // instead would be a real compile error, not a runtime data race waiting to happen.
    let task = pool.spawn(|| "{slow_double(21, cfg)}" as PhoenixObject);
    let result = task.join() as String;
    print(result); // 42

    pool.shutdown();
}
