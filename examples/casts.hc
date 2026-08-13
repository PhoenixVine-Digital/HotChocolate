fn main() {
    let i = 7;
    let f = i as Float;
    let d = i as Double;
    print(f);
    print(d);

    let x = 3.9;
    print(x as Int);   // truncates toward zero, like Java's (int) cast

    let y = -3.9;
    print(y as Int);

    let z = 2.5f as Double as Int;
    print(z);
}
