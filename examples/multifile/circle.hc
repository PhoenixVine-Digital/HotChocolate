struct Circle { radius: Int }
impl Shape for Circle {
    fn area(&self) -> Int { return self.radius * self.radius * 3; }
}
