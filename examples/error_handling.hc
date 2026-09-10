extern class Integer = "java.lang.Integer" {
    static fn parseInt(s: String) -> Int;
}

extern class NumberFormatException = "java.lang.NumberFormatException" {
    fn getMessage(&self) -> String;
}

extern class ArithmeticException = "java.lang.ArithmeticException" {
    fn getMessage(&self) -> String;
}

extern class IllegalStateException = "java.lang.IllegalStateException" {
    fn new(msg: String) -> Self;
    fn getMessage(&self) -> String;
}

fn parse_or_default(s: String, default: Int) -> Int {
    try {
        let n = Integer::parseInt(s);
        return n;
    } catch (e: NumberFormatException) {
        print("caught: " + e.getMessage());
        return default;
    }
}

fn divide(a: Int, b: Int) {
    try {
        let r = a / b;
        print("result: {r}");
    } catch (e: ArithmeticException) {
        print("divide failed: {e.getMessage()}");
    }
}

// `throw` alongside multiple `catch` clauses on one `try` -- each clause is checked in order
// against the real Java exception's runtime type, same as `catch` in Java itself.
fn validate(n: Int) {
    try {
        if n < 0 {
            throw IllegalStateException::new("negative: {n}");
        }
        print("ok: {n}");
    } catch (e: NumberFormatException) {
        print("nfe: {e.getMessage()}");
    } catch (e: IllegalStateException) {
        print("ise: {e.getMessage()}");
    }
}

fn safe_parse(s: String) -> Result<Int, String> {
    try {
        let n = Integer::parseInt(s);
        return Result<Int, String>::Ok { value: n };
    } catch (e: NumberFormatException) {
        return Result<Int, String>::Err { error: "bad number: " + s };
    }
}

fn main() {
    print(parse_or_default("42", -1));
    print(parse_or_default("not a number", -1));
    divide(10, 2);
    divide(10, 0);
    validate(5);
    validate(-3);

    match safe_parse("7") {
        Ok { value } => { print("parsed: {value}"); }
        Err { error } => { print("error: {error}"); }
    }
    match safe_parse("nope") {
        Ok { value } => { print("parsed: {value}"); }
        Err { error } => { print("error: {error}"); }
    }
}
