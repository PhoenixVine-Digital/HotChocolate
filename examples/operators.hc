// Operator overloading -- a plain struct's own `impl` method, named per a fixed convention
// (mirroring Rust's own operator-trait method names), lets `+`/`-`/`*`/`/`/`%`/`==`/`!=`/unary
// `-` dispatch to a real user-defined method instead of requiring Int/Long/Float/Double. See
// README's own "Operator overloading" section for the full design and naming convention.

struct Vec2 {
    x: Double,
    y: Double,
}

impl Vec2 {
    fn add(&self, other: Vec2) -> Vec2 {
        return Vec2 { x: self.x + other.x, y: self.y + other.y };
    }
    fn sub(&self, other: Vec2) -> Vec2 {
        return Vec2 { x: self.x - other.x, y: self.y - other.y };
    }
    // The right-hand side doesn't have to be the SAME struct type -- `mul`'s own declared param
    // decides that, exactly like an ordinary method's arg type would.
    fn mul(&self, scalar: Double) -> Vec2 {
        return Vec2 { x: self.x * scalar, y: self.y * scalar };
    }
    fn eq(&self, other: Vec2) -> Bool {
        return self.x == other.x && self.y == other.y;
    }
    fn neg(&self) -> Vec2 {
        return Vec2 { x: -self.x, y: -self.y };
    }

    fn show(&self) -> String {
        return "({self.x}, {self.y})";
    }
}

fn main() {
    let a = Vec2 { x: 1.0, y: 2.0 };
    let b = Vec2 { x: 3.0, y: 4.0 };

    print((a + b).show()); // (4.0, 6.0)
    print((b - a).show()); // (2.0, 2.0)
    print((a * 2.0).show()); // (2.0, 4.0) -- Vec2 * Double, via `mul`'s own param type
    print((-a).show());     // (-1.0, -2.0)

    print(a == Vec2 { x: 1.0, y: 2.0 }); // true -- value equality via `eq`, not reference identity
    print(a != b);                        // true
}
