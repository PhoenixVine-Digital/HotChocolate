interface Describable {
    fn describe(&self) -> String;
}

struct Engine { model: String }
impl Describable for Engine {
    fn describe(&self) -> String { return self.model; }
}

struct Car {
    engine: Engine,
    brand: String,
}

// Car forwards Describable entirely to its `engine` field -- no method body written here.
impl Describable for Car by engine { }

fn announce(d: &dyn Describable) { print(d.describe()); }

fn main() {
    let c = Car { engine: Engine { model: "V8" }, brand: "Foo" };
    print(c.describe());
    announce(&c);
}
