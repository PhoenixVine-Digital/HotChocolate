// The `?.` (safe navigation) and `?:` (Elvis) operators, layered on top of `Type?` -- see
// README's own "The `?.`/`?:` operators" section for the full design.

struct Player {
    name: String,
}

impl Player {
    fn greet(&self) -> String {
        return "hi " + self.name;
    }
}

fn find_player(has: Bool) -> Player? {
    if has {
        return Player { name: "Rin" };
    }
    return null;
}

fn main() {
    // `?.` -- short-circuits to null if the receiver is null, otherwise calls/reads through it.
    // Args aren't even evaluated on the null path (a real short-circuit, not just a null-check).
    let greeting = find_player(true)?.greet();
    if greeting == null {
        print("no player");
    } else {
        print(greeting);
    }
    print(find_player(false)?.greet() == null); // true -- short-circuited to null

    // `?:` -- Elvis: the left value if non-null, else the right (default) value.
    let guest = Player { name: "Guest" };
    print((find_player(false) ?: guest).greet());
    print((find_player(true) ?: guest).greet());
}
