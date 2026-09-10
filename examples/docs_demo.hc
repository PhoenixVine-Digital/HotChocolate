struct Enemy {
    health: Int,
}

/// Attacks an enemy and returns the resulting damage.
/// @param target The enemy being attacked.
/// @param power How hard the attack hits.
/// @returns The damage dealt.
/// @example attack(&goblin, 5)
/// @warning Does not check if the enemy is already dead.
/// @see Enemy.health
/// @deprecated Use attack_with_crit instead.
fn attack(target: &Enemy, power: Int) -> Int {
    return power * 2;
}

fn main() {
    let e = Enemy { health: 100 };
    print(attack(&e, 5));
}
