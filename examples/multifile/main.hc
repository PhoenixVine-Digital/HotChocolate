fn main() {
    let c = Circle { radius: 2 };
    let sq = Square { side: 3 };
    print(c.area());
    print(sq.area());
    print(describe(&c));
    print(describe(&sq));
}
