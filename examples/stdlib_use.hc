// `use <topic>;` -- real opt-in to a specific standard-library topic instead of getting the
// whole thing unconditionally. See README's own "Standard library modules" section for the full
// design. A program with NO `use` line anywhere (every other example in this directory) still
// gets the full legacy prelude, unchanged -- this is purely additive.

use registry;
// `registry` alone is enough -- it transitively pulls in `vec` (its own storage) and `option`
// (`get`'s own return type), see `Driver.hotc`'s own `add_stdlib_topic_and_deps`. `Result<T, E>`
// and `read_int`/`read_string` (the other two stdlib topics, `result`/`io`) are NOT available
// here at all -- naming them would be a real "unknown struct or enum variant" error.

fn main() {
    var r = registry_new("rin", 100);
    r.register("kai", 50);
    r.register("rin", 999); // "last write wins" -- overwrites the first "rin" entry, not a duplicate

    let found = r.get("rin");
    if let Option::Some { value } = found {
        print(value); // 999
    } else {
        print(-1);
    }
    print(r.length()); // 2 -- "rin" was overwritten in place, not appended again
}
