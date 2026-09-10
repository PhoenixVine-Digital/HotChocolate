// Real borrow checking (see README's own "Compile-time safety roadmap" section, Phase 3) --
// `&T` can read fields but not assign to them; `&mut T` can assign, but only when the thing
// being borrowed is itself mutably accessible (a `var` local, another `&mut` param, or
// `&mut self`). Exclusivity: within one call, the same variable can't be borrowed `&mut`
// alongside any other borrow of it.

struct Player {
    hp: Int,
}

fn heal(p: &mut Player, amount: Int) {
    p.hp = p.hp + amount; // fine -- p is &mut
}

fn peek(p: &Player) -> Int {
    return p.hp; // fine -- reading through & is always allowed
}

fn both(a: &mut Player, b: &Player) -> Int {
    a.hp = a.hp + 1;
    return b.hp;
}

fn main() {
    var p = Player { hp: 10 };
    heal(&mut p, 5); // fine -- p is var, so &mut p is allowed
    print("{peek(&p)}"); // fine -- multiple shared borrows are always fine

    var q = Player { hp: 1 };
    print("{both(&mut p, &q)}"); // fine -- different variables, no aliasing conflict
}
