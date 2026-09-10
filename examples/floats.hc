// `1.5` -> Double (Java's own unsuffixed default). `1.5f` -> Float. Fixed 2026-09-08: mixed
// Int/Long/Float/Double arithmetic and comparisons now widen to the "bigger" side automatically
// (`5 * 2.0` -> `10.0`, no `as Double` needed) -- this comment used to say there was no implicit
// promotion at all; that's now only true for the one still-excluded pair, Int+Long together
// (see README's own "Numeric widening" section for why that pair specifically stays excluded).

extern class JMath = "java.lang.Math" {
    fn sqrt(x: Double) -> Double;
    fn abs(x: Float) -> Float;
}

struct Vec3 {
    x: Double,
    y: Double,
    z: Double,
}

impl Vec3 {
    fn length(&self) -> Double {
        return JMath::sqrt(self.x * self.x + self.y * self.y + self.z * self.z);
    }
}

fn main() {
    let a: Float = 3.5f;
    let b: Float = -1.25f;
    print(a + b);
    print(a * 2.0f);
    print(JMath::abs(b));

    let v = Vec3 { x: 3.0, y: 4.0, z: 0.0 };
    print(v.length());

    var i = 0.0;
    while i < 3.0 {
        print(i);
        i = i + 1.0;
    }

    print(1.0 == 1.0);
    print(1.0 != 2.0);
    print(0.1 < 0.2);

    let arr = [1.0, 2.5, 3.75];
    var sum = 0.0;
    for x in arr {
        sum = sum + x;
    }
    print(sum);

    let farr = [1.0f, 2.0f];
    print(farr[0] + farr[1]);

    // Mixed-type widening -- a bare Int literal/local mixed into Float/Double math, either
    // operand order, arithmetic and comparisons alike.
    print(5 * 2.0);   // 10.0 -- Int widened to Double
    print(2.0 * 5);   // 10.0 -- same, other order
    print(5 > 2.0);   // true
    var total = 0.0;
    var k = 0;
    while k < 3 {
        total = total + k * 1.5; // Int loop counter mixed with a Double step, every iteration
        k = k + 1;
    }
    print(total); // 4.5

    // Vec<T>/Registry<T> from the prelude are fully generic -- Vec<Double> works exactly like
    // Vec<Int>, including the wide (2-slot) local-variable handling that only Double needs.
    var nums = vec_of(1.5);
    nums.push(2.5);
    nums.push(-3.0);
    var j = 0;
    while j < nums.length() {
        print(nums.get(j));
        j = j + 1;
    }
}
