module net.example.hcdemo;

struct Player {
    name: String,
    hp: Int,
}

interface Greeter {
    fn greet(&self) -> String;
}
impl Greeter for Player {
    fn greet(&self) -> String { return "hi, " + self.name; }
}

enum Shape {
    Circle { radius: Int },
    Point,
}

fn describe(s: Shape) -> String {
    match s {
        Circle { radius } => { return "circle"; }
        Point => { return "point"; }
    }
}

fn main() {
    let p = Player { name: "Rin", hp: 10 };
    print(p.greet());
    print(describe(Circle { radius: 3 }));
    print(describe(Point));
}
