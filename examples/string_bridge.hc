// A native `String` (`Ty.Str_`) can call any method declared on an `extern class` bound to
// "java.lang.String" -- the checker treats them as call-compatible since they're the exact same
// JVM type underneath. This is what lets ordinary string literals/locals reach real
// `.split()`/`.trim()`/`.substring()` etc. instead of needing a separate, unusable extern type.
extern class JString = "java.lang.String" {
    fn split(&self, regex: String) -> [String];
    fn trim(&self) -> String;
    fn isEmpty(&self) -> Bool;
    fn substring(&self, start: Int, end: Int) -> String;
}

fn main() {
    let s = "  10 20 30  ";
    let trimmed = s.trim();
    print(trimmed);

    let parts = trimmed.split(" ");
    var i = 0;
    while i < parts.length {
        print(parts[i]);
        i = i + 1;
    }

    print(trimmed.isEmpty());
    print("".isEmpty());
    print(trimmed.substring(0, 2));
}
