// A tour of Hot Chocolate: structs+methods, generics, &mut, arrays, for, drop.

struct Player {
    name: String,
    hp: Int,
    level: Int,
}

impl Player {
    fn damage(&mut self, amount: Int) {
        self.hp = self.hp - amount;
    }

    fn is_alive(&self) -> Bool {
        return self.hp > 0;
    }

    fn describe(&self) -> String {
        return self.name;
    }
}

// A generic container -- monomorphizes per element type, so Box<Int> gets a
// real `int` field (no boxing) and Box<Player> gets a real `Player` field.
struct Box<T> {
    value: T,
}

impl<T> Box<T> {
    fn get(&self) -> T {
        return self.value;
    }
}

// A resource with cleanup logic that fires automatically when it goes out of scope.
struct Buff {
    name: String,
}

impl Buff {
    fn drop(&mut self) {
        print("buff expired: " + self.name);
    }
}

fn total_hp(players: &[Player]) -> Int {
    var total = 0;
    for p in players {
        total = total + p.hp;
    }
    return total;
}

fn apply_poison(p: &mut Player) {
    p.damage(5);
}

fn main() {
    var hero = Player { name: "Rin", hp: 100, level: 3 };
    print(hero.describe());
    print(hero.level);

    apply_poison(&mut hero);
    print(hero.hp);
    print(hero.is_alive());

    let boxedLevel = Box { value: hero.level };
    print(boxedLevel.get());

    let party = [
        Player { name: "Rin", hp: 80, level: 3 },
        Player { name: "Kai", hp: 45, level: 2 },
        Player { name: "Mo", hp: 60, level: 4 },
    ];
    print(total_hp(&party));

    var strongest = 0;
    for i in 0..party.length {
        if party[i].level > party[strongest].level {
            strongest = i;
        }
    }
    print(party[strongest].describe());
    print(party[strongest].level);

    let shield = Buff { name: "shield" };
    // shield.drop() fires automatically here, at the end of main.
}
