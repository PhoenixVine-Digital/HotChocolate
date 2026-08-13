sealed interface Shape {
    fn area(&self) -> Int;
}

struct Circle { radius: Int }
impl Shape for Circle {
    fn area(&self) -> Int { return self.radius * self.radius * 3; }
}

struct Square { side: Int }
impl Shape for Square {
    fn area(&self) -> Int { return self.side * self.side; }
}

fn describe(s: &dyn Shape) -> Int {
    match s {
        Circle { radius } => { return radius * 100; }
        Square { side } => { return side * 1000; }
    }
}

fn main() {
    let c = Circle { radius: 2 };
    let sq = Square { side: 3 };
    print(c.area());
    print(sq.area());
    print(describe(&c));
    print(describe(&sq));
}
