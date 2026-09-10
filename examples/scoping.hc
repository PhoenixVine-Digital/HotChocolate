// Real lexical scoping (see README's own "Compile-time safety roadmap" section, Phase 1) --
// a `let` inside a nested block is invisible once that block ends, and correctly shadows (rather
// than permanently overwrites) an outer local of the same name for the block's own duration.

fn main() {
    let x: Int = 1;
    if true {
        let x: String = "hi"; // shadows the outer `x` -- only inside this block
        print(x);
    }
    print("{x}"); // outer `x` again, still Int -- prints "1", not "hi"

    for i in 0..3 {
        let y: Int = i * 2; // scoped to the loop body -- gone once the loop ends
        print("{y}");
    }
}
