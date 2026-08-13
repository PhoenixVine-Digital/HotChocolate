// Extends a real, ordinary JDK class (not a mock/interface) to prove `extends`/`override`
// work end-to-end: real superclass linkage, real constructor forwarding, real virtual dispatch.
extern class ArrayList = "java.util.ArrayList" {
    fn new() -> Self;
    fn size(&self) -> Int;
}

struct LoudList extends ArrayList {}

impl LoudList {
    override fn size(&self) -> Int {
        print("size() called!");
        return 42;
    }
}

fn main() {
    let list = LoudList::new();
    // Calling .size() should print "size() called!" then return 42 -- proving the override
    // is a real instance method reached via ordinary JVM virtual dispatch, not just something
    // that happens to compile.
    print(list.size());
}
