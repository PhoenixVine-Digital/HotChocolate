struct Box<T> {
    value: T,
}

struct Player {
    name: String,
    hp: Int,
}

fn identity<T>(x: T) -> T {
    return x;
}

fn unwrap<T>(b: Box<T>) -> T {
    return b.value;
}

fn main() {
    let a = identity(5);
    print(a);

    let s = identity("hi");
    print(s);

    let boxInt = Box { value: 42 };
    print(unwrap(boxInt));

    let p = Player { name: "Rin", hp: 100 };
    let boxPlayer = Box { value: p };
    let unwrapped = unwrap(boxPlayer);
    print(unwrapped.hp);
}
