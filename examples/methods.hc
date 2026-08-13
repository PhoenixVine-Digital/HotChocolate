struct Player {
    name: String,
    hp: Int,
}

impl Player {
    fn damage(&self, amount: Int) -> Int {
        return self.hp - amount;
    }

    fn describe(self) -> String {
        return self.name;
    }
}

struct Box<T> {
    value: T,
}

impl<T> Box<T> {
    fn get(&self) -> T {
        return self.value;
    }
}

fn main() {
    let p = Player { name: "Rin", hp: 100 };
    print(p.damage(30));
    print(p.describe());

    let b = Box { value: 7 };
    print(b.get());

    let bp = Box { value: Player { name: "Kai", hp: 50 } };
    print(bp.get().hp);
}
