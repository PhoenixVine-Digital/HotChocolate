struct Square { side: Int }
impl Shape for Square {
    fn area(&self) -> Int { return self.side * self.side; }
}
