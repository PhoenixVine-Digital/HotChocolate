// 1. Generic struct implementing an interface (method doesn't depend on T).
interface Labeled {
    fn label(&self) -> String;
}

struct Box<T> {
    value: T,
    name: String,
}

impl<T> Labeled for Box<T> {
    fn label(&self) -> String {
        return self.name;
    }
}

// 2. Interface inheritance (supertraits).
interface Named {
    fn name(&self) -> String;
}
interface Greeter: Named {
    fn greet(&self) -> String {
        return "Hello, " + self.name();
    }
}

struct Person {
    pname: String,
}
impl Named for Person {
    fn name(&self) -> String { return self.pname; }
}
impl Greeter for Person { }

fn greet_dyn(g: &dyn Greeter) {
    print(g.greet());
}
fn name_dyn(n: &dyn Named) {
    print(n.name());
}

// 3. Two unrelated interfaces share a required method name (both `info`). Widget provides
// exactly one override, under A's impl block -- on the JVM that single instance method
// satisfies both A's and B's requirement for it simultaneously, so `impl B for Widget { }`
// needs no override of its own. (Previously this incorrectly errored "missing info for B".)
interface A { fn info(&self) -> Int; }
interface B { fn info(&self) -> Int; }
struct Widget { }
impl A for Widget { fn info(&self) -> Int { return 1; } }
impl B for Widget { }

fn main() {
    let bi = Box { value: 5, name: "int box" };
    let bp = Box { value: Person { pname: "Kai" }, name: "person box" };
    print(bi.label());
    print(bp.label());

    let p = Person { pname: "Rin" };
    print(p.greet());
    greet_dyn(&p);
    name_dyn(&p); // Person is also `&dyn Named` -- via Greeter's supertrait, transitively

    let w = Widget { };
    print(w.info());
}
