enum Shape {
    Circle { radius: Int },
    Square { side: Int },
    Point,
}

fn area(s: Shape) -> Int {
    var result = 0;
    match s {
        Circle { radius } => { result = radius * radius * 3; }
        Square { side } => { result = side * side; }
        Point => { result = 0; }
    }
    return result;
}

fn describe(s: Shape) -> String {
    match s {
        Circle { radius } => { return "circle"; }
        _ => { return "other"; }
    }
}

fn main() {
    print(area(Circle { radius: 2 }));
    print(area(Square { side: 3 }));
    print(area(Point));
    print(describe(Circle { radius: 1 }));
    print(describe(Square { side: 1 }));
}
