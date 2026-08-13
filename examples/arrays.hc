fn sum(arr: &[Int]) -> Int {
    var total = 0;
    var i = 0;
    while i < arr.length {
        total = total + arr[i];
        i = i + 1;
    }
    return total;
}

fn first<T>(arr: &[T]) -> T {
    return arr[0];
}

fn main() {
    let nums = [1, 2, 3, 4, 5];
    print(nums.length);
    print(sum(&nums));
    print(first(&nums));

    var board = [0; 5];
    board[2] = 99;
    print(board[2]);
    print(board.length);

    let names = ["Rin", "Kai"];
    print(first(&names));
}
