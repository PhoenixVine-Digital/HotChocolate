struct Player {
    name: String,
    hp: Int,
}

fn damage(p: &Player, amount: Int) -> Int {
    return p.hp - amount;
}

fn greet(name: String) {
    print("Hello, " + name + "!");
}

fn main() {
    greet("Hot Chocolate");

    let p = Player { name: "Rin", hp: 100 };
    let remaining = damage(&p, 30);
    print(remaining);

    var i = 0;
    while i < 3 {
        print(i);
        i = i + 1;
    }

    if remaining > 50 {
        print("still healthy");
    } else {
        print("hurt");
    }
}
