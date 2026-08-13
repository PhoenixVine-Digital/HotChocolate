// Option<T> is provided by the language's prelude -- no need to declare it here.

fn find(nums: &[Int], target: Int) -> Option<Int> {
    for i in 0..nums.length {
        if nums[i] == target {
            return Some { value: i };
        }
    }
    return None;
}

fn main() {
    let nums = [10, 20, 30];

    let a = find(&nums, 20);
    match a {
        Some { value } => { print(value); }
        None => { print(-1); }
    }

    let b = find(&nums, 99);
    match b {
        Some { value } => { print(value); }
        None => { print(-1); }
    }

    let s = Some { value: "hello" };
    match s {
        Some { value } => { print(value); }
        None => { print("none"); }
    }
}
