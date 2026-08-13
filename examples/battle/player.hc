struct Player {
    name: String,
    hp: Int,
    maxHp: Int,
    attack: Int,
    potions: Int,
}

impl Player {
    fn isAlive(&self) -> Bool {
        return self.hp > 0;
    }

    fn takeDamage(&mut self, amount: Int) {
        var newHp = self.hp - amount;
        if newHp < 0 {
            newHp = 0;
        }
        self.hp = newHp;
    }

    fn healIfPossible(&mut self) {
        if self.potions > 0 {
            if self.hp < self.maxHp {
                self.potions = self.potions - 1;
                var newHp = self.hp + 15;
                if newHp > self.maxHp {
                    newHp = self.maxHp;
                }
                self.hp = newHp;
                print("You drink a potion.");
            }
        }
    }
}
