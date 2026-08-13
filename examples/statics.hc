// Hot Chocolate's first global mutable value: not a local (no enclosing scope), not a struct
// field (no owning instance). Exists for the whole program's lifetime.

pub static counter: Int = 0;
pub static log: Vec<String> = vec_of("boot");

fn bump() -> Int {
    counter = counter + 1;
    return counter;
}

fn record(msg: String) {
    log.push(msg);
}

fn main() {
    print(bump());
    print(bump());
    print(bump());

    record("hello");
    record("world");
    var i = 0;
    while i < log.length() {
        print(log.get(i));
        i = i + 1;
    }
}
