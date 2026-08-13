// Java interop: `extern class` declares a trusted shape for an existing, externally-compiled
// JVM class -- no classfile introspection, so a wrong declaration surfaces as a runtime
// NoSuchMethodError, not a compile error (same as any FFI declaration file in any language).

extern class Random = "java.util.Random" {
    fn new() -> Self;
    fn nextInt(self, bound: Int) -> Int;
}

extern class JMath = "java.lang.Math" {
    fn max(a: Int, b: Int) -> Int;
    fn abs(a: Int) -> Int;
}

fn main() {
    let big = JMath::max(3, 9);
    print(big);
    print(JMath::abs(-42));

    let r = Random::new();
    var i = 0;
    while i < 3 {
        let roll = r.nextInt(6);
        if roll >= 0 {
            print("rolled ok");
        }
        i = i + 1;
    }
}
