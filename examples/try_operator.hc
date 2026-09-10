// Tests the ? operator with Result<T, E> -- fixed 2026-09-04, see README's own "The ? operator"
// section. Chains two ?-unwraps across a nested call (divide_twice -> safe_divide, twice),
// early-returning the first Err encountered.

fn safe_divide(a: Int, b: Int) -> Result<Int, String> {
    if b == 0 {
        return Result<Int, String>::Err { error: "division by zero" };
    }
    return Result<Int, String>::Ok { value: a / b };
}

// This function uses ? to unwrap Results and early-return on error
fn divide_twice(a: Int, b: Int, c: Int) -> Result<Int, String> {
    let first = safe_divide(a, b)?;    // ? unwraps Ok or returns Err early
    let result = safe_divide(first, c)?;
    return Result<Int, String>::Ok { value: result };
}

fn main() {
    match divide_twice(100, 5, 2) {
        Ok { value } => { print("100 / 5 / 2 = {value}"); }
        Err { error } => { print("Error: {error}"); }
    }

    match divide_twice(100, 0, 2) {
        Ok { value } => { print("Result: {value}"); }
        Err { error } => { print("Error caught: {error}"); }
    }
}
