// `read_line()` is a built-in, like `print` -- reads one line from stdin, returns it as a
// String. Backed by a single persistent BufferedReader over System.in for the program's whole
// lifetime (see CodeGen.genProgramClass), so successive calls each get the next line.

extern class Integer = "java.lang.Integer" {
    fn parseInt(s: String) -> Int;
}

fn main() {
    print("What's your name?");
    let name = read_line();
    print("Hello, " + name + "!");

    print("Pick a number:");
    let raw = read_line();
    let n = Integer::parseInt(raw);
    print("Double that is:");
    print(n * 2);
}
