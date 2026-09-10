// Real move checking (see README's own "Compile-time safety roadmap" section, Phase 2) --
// passing a struct by value moves it; using the old variable again afterward is a compile error.
// A `&`-declared param only borrows, never moves.

struct Player {
    hp: Int,
}

fn consume(p: Player) -> Int {
    return p.hp;
}

fn peek(p: &Player) -> Int {
    return p.hp;
}

fn main() {
    let a = Player { hp: 10 };
    print("{peek(a)}"); // borrow -- doesn't move `a`
    print("{peek(a)}"); // borrowing again is fine, still not moved

    var b = Player { hp: 20 };
    print("{consume(b)}"); // moves `b`
    b = Player { hp: 30 }; // reassigning gives `b` a fresh value -- un-moves it
    print("{consume(b)}"); // fine again

    // A value moved in only ONE branch of an `if`/`else` is still considered moved afterward
    // (the checker can't know at compile time which branch actually ran) -- but a branch that
    // unconditionally returns is excluded from that, so `d` below stays fine to use after the
    // `if`, since the only path that reaches `print(peek(d))` never took the branch that moved it.
    let c = Player { hp: 40 };
    if c.hp > 100 {
        consume(c);
        return;
    }
    print("{peek(c)}");
}
