interface Damageable {
    fn damage(&mut self, amount: Int);
    fn hp(&self) -> Int;
}

struct Player { hp_val: Int }
impl Damageable for Player {
    fn damage(&mut self, amount: Int) { self.hp_val = self.hp_val - amount; }
    fn hp(&self) -> Int { return self.hp_val; }
}

fn apply_damage<T: Damageable>(x: &mut T, amount: Int) -> Int {
    x.damage(amount);
    return x.hp();
}

fn main() {
    var p = Player { hp_val: 100 };
    print(apply_damage(&mut p, 30));
}
