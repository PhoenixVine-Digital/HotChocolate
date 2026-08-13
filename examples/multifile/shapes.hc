// Multi-file demo: `hc run examples/multifile` compiles every .hc file in this directory as
// one flat program (no import statements, one global namespace) -- see README's "Multi-file
// projects" section. This file declares the sealed interface; its implementers live in
// separate files (circle.hc, square.hc), and `sealed` still guarantees the implementer set is
// closed across the *whole compiled directory*, not just this one file.

sealed interface Shape {
    fn area(&self) -> Int;
}

fn describe(s: &dyn Shape) -> Int {
    match s {
        Circle { radius } => { return radius * 100; }
        Square { side } => { return side * 1000; }
    }
}
