struct Player { name: String, hp: Int }

fn main() {
    var total = 0;
    for i in 0..5 {
        total = total + i;
    }
    print(total);

    let nums = [10, 20, 30];
    var sum = 0;
    for n in nums {
        sum = sum + n;
    }
    print(sum);

    var count = 0;
    for i in 0..3 {
        for j in 0..3 {
            count = count + 1;
        }
    }
    print(count);

    let players = [
        Player { name: "Rin", hp: 10 },
        Player { name: "Kai", hp: 20 },
    ];
    var hpTotal = 0;
    for p in players {
        hpTotal = hpTotal + p.hp;
    }
    print(hpTotal);
}
