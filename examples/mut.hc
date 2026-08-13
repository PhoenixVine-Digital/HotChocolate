struct Player {
    name: String,
    hp: Int,
}

impl Player {
    fn heal(&mut self, amount: Int) {
        self.hp = self.hp + amount;
    }

    fn hp(&self) -> Int {
        return self.hp;
    }
}

fn zero_out(p: &mut Player) {
    p.hp = 0;
}

fn main() {
    var p = Player { name: "Rin", hp: 50 };
    p.heal(20);
    print(p.hp());

    zero_out(&mut p);
    print(p.hp());
}
