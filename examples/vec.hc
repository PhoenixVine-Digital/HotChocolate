fn main() {
    var v = vec_of(10);
    v.push(20);
    v.push(30);
    v.push(40);
    v.push(50);
    var i = 0;
    while i < v.length() {
        print(v.get(i));
        i = i + 1;
    }
    v.set(0, 999);
    print(v.get(0));
}
