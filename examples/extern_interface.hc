// `extern interface` is the other half of Java interop: `extern class` lets HC code call INTO
// existing JVM classes; this lets an HC struct implement an existing JVM *interface* so real
// Java code can call back INTO it -- exactly the shape a Forge/Fabric mod loader needs
// (ModInitializer, event listeners, Runnable-style callbacks, ...).

extern interface Run = "java.lang.Runnable" {
    fn run(&self);
}

extern class JThread = "java.lang.Thread" {
    fn new(target: &dyn Run) -> Self;
    fn start(self);
    fn join(self);
}

struct Greeter {
    name: String,
}
impl Run for Greeter {
    fn run(&self) {
        print("Hello from a real java.lang.Thread, " + self.name + "!");
    }
}

fn main() {
    let g = Greeter { name: "Rin" };
    let t = JThread::new(&g);
    t.start();
    t.join();
    print("main thread done.");
}
