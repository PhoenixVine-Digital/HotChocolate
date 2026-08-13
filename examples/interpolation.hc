struct Enemy {
    name: String,
    health: Int,
}

fn main() {
    let enemy = Enemy { name: "Goblin", health: 42 };
    print("Enemy {enemy.name} has {enemy.health} health");
    print("no interpolation here");
    print("{1 + 2} and {3.5}");
    print("empty braces: {""}, adjacent: {1}{2}");
    let nested = "outer {"inner " + "concat"} done";
    print(nested);
}
