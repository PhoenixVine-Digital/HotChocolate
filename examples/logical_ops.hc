fn side_effect_true() -> Bool {
    print("right side evaluated");
    return true;
}

fn main() {
    let arr = [1, 2, 3];
    var i = 0;
    // Real short-circuiting: `arr[i]` must never be read once `i < arr.length` is false.
    while i < arr.length && arr[i] < 10 {
        print(arr[i]);
        i = i + 1;
    }

    print(true && true);
    print(true && false);
    print(false && side_effect_true());  // "right side evaluated" never prints
    print(true || side_effect_true());   // "right side evaluated" never prints
    print(false || true);
}
