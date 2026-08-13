module test.longs;

fn main() {
    let a = 123L;
    let b = 456L;
    let c = a + b;
    print(c); // 579
    
    if c > 500L {
        print("c is big");
    }
    
    let d = c as Int;
    print(d); // 579
    
    let e = 10000000000L; // exceeds Int range
    print(e);
    
    let f = e / 1000000L;
    print(f); // 10000
}
