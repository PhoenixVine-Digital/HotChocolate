# Hot Chocolate as your first programming language

This document is for someone who has **never written a line of code in any
language before**. If you've programmed in something else already (Java,
Python, Kotlin, anything), skip this and read [TUTORIAL.md](TUTORIAL.md)
instead — it moves faster and compares HC to languages you already know.

This document does the opposite: it explains what code even *is* along the
way, using Hot Chocolate as the example language. It covers less ground than
TUTORIAL.md on purpose — better to deeply understand a handful of ideas than
skim twenty. When you finish this, TUTORIAL.md picks up right where this
leaves off and covers everything else HC can do.

## Table of contents

1. [What programming actually is](#1-what-programming-actually-is)
2. [Setting up](#2-setting-up)
3. [Your first program, explained piece by piece](#3-your-first-program-explained-piece-by-piece)
4. [Variables: storing values with names](#4-variables-storing-values-with-names)
5. [Types: what *kind* of value is it](#5-types-what-kind-of-value-is-it)
6. [Doing math and comparisons](#6-doing-math-and-comparisons)
7. [Functions: reusable recipes](#7-functions-reusable-recipes)
8. [Making decisions: `if`/`else`](#8-making-decisions-ifelse)
9. [Repeating things: loops](#9-repeating-things-loops)
10. [Grouping related data: structs](#10-grouping-related-data-structs)
11. [Ownership: the one genuinely new idea](#11-ownership-the-one-genuinely-new-idea)
12. [Lists of things: arrays](#12-lists-of-things-arrays)
13. [Where to go from here](#13-where-to-go-from-here)

---

## 1. What programming actually is

A computer only ever does one thing: it follows instructions, one at a
time, exactly as written, incredibly fast. "Programming" is writing those
instructions down in a language precise enough that the computer can't
misunderstand you — which is why code looks stricter and more particular
than normal writing. A missing `;` or `}` isn't the computer being fussy for
no reason; it's the computer genuinely not being able to tell where one
instruction ends and the next begins without it.

The file you write your instructions in (ending in `.hc` for this language)
is called **source code**, or just "code." A program called a **compiler**
reads your source code and translates it into something the computer's
processor can actually execute. Hot Chocolate's compiler is a program
called `hotchocolate` (or `hc` for short) — you'll run it from a terminal.

## 2. Setting up

You'll type commands into a **terminal** (also called a "command line" or
"shell") — a text-only way of telling your computer what to do, instead of
clicking things. Build the Hot Chocolate compiler once:

```bash
./gradlew installDist
```

This creates a program at `build/install/hotchocolate/bin/hotchocolate`
(`hotchocolate.bat` on Windows). From now on, to run a `.hc` file you wrote:

```bash
hotchocolate run my_program.hc
```

Whenever this doc says "run `something.hc`," that's the command it means.

## 3. Your first program, explained piece by piece

Create a file named `hello.hc` containing exactly this:

```
fn main() {
    print("Hello, world!");
}
```

Run it (`hotchocolate run hello.hc`) and you'll see `Hello, world!` printed
to your terminal. Let's go through every character of this and say what
it's for:

- `fn` is a keyword — a word with special meaning to the compiler, not just
  a name you made up — that means "I'm about to define a function." A
  **function** is a named block of instructions.
- `main` is the name of this particular function. `main` is special: it's
  the one function the compiler always runs first when your program starts
  — every HC program needs exactly one.
- `()` — parentheses right after a function's name hold that function's
  **parameters**, i.e. values the function needs to be given to do its job.
  `main` doesn't need any, so it's empty.
- `{` and `}` — curly braces mark where the function's body (its actual
  instructions) starts and ends. Everything between them is what runs when
  `main` runs.
- `print("Hello, world!");` is one **statement** — one instruction. `print`
  is a built-in function whose job is "show this text in the terminal." The
  text between the quote marks, `"Hello, world!"`, is the value being
  handed to it. The `;` at the end marks "this instruction is finished" —
  every statement in HC ends with one, the same way an English sentence
  ends with a period.

That's the whole anatomy of a program: a `main` function containing
statements, each ending in `;`, wrapped in `{ }`.

## 4. Variables: storing values with names

A **variable** is a name you give to a value so you can use that value
again later without retyping it. Think of it like a labeled box: you put a
value in the box, write a name on it, and later you can look inside just by
using the name.

```
fn main() {
    let hp = 100;
    print(hp);       // prints 100

    var gold = 50;
    gold = gold + 10; // put a new value in the box: 60
    print(gold);      // prints 60
}
```

- `let` creates a box you **can't** put a new value into later — once set,
  it stays set. Use this by default.
- `var` creates a box you **can** change later, with `name = newValue;`.
  Only reach for `var` when you actually need to change the value after
  creating it (like `gold` above, which goes up).
- `//` starts a **comment** — text the compiler completely ignores, there
  purely for a human reader. Everything from `//` to the end of that line
  is a comment.

Why does HC bother distinguishing "can't change" (`let`) from "can change"
(`var`) instead of just always letting you change anything, the way you
might expect? Because a *lot* of real bugs come from a value changing
somewhere you didn't expect it to. Marking most things `let` means the
compiler can guarantee "this never changes," which makes the rest of your
program easier to reason about — you don't have to go hunting for every
place a value might have been altered.

## 5. Types: what *kind* of value is it

Every value has a **type** — what kind of thing it is, which determines
what you're allowed to do with it. You can't, for instance, add a word and
a number together; the compiler stops you before your program even runs,
because it already knows `"hello"` is text and `5` is a number.

The basic types you'll use constantly:

| Type | A value of this type looks like | What it's for |
|------|-----------------------------------|----------------|
| `Int` | `5`, `-12`, `100` | whole numbers |
| `Double` | `3.14`, `-0.5` | numbers with a decimal point |
| `Bool` | `true` or `false` | yes/no, on/off |
| `String` | `"hello"` | text, always in double quotes |

You almost never have to write the type yourself — HC figures it out from
the value you give it:

```
let hp = 100;        // HC sees 100 and knows: this is an Int
let name = "Rin";     // HC sees "hello" and knows: this is a String
let alive = true;     // HC sees true/false and knows: this is a Bool
```

If you ever do want to spell it out (or the compiler asks you to because it
genuinely can't tell), you write it after a colon:

```
let hp: Int = 100;
```

## 6. Doing math and comparisons

```
fn main() {
    print(2 + 3);   // 5
    print(10 - 4);  // 6
    print(3 * 4);   // 12
    print(10 / 3);  // 3 -- whole numbers divide to a whole number, no decimal
    print(10 % 3);  // 1 -- the *remainder* left over after dividing

    print(5 > 3);    // true
    print(5 == 5);   // true -- "is equal to" (note: TWO equals signs)
    print(5 != 3);   // true -- "is not equal to"
}
```

One easy mistake: `=` (one equals sign) *sets* a value (`gold = 60;`), while
`==` (two equals signs) *asks a question*, "are these equal?", and gives
back `true` or `false`. They look similar but mean completely different
things — this trips up everyone at first.

## 7. Functions: reusable recipes

A function is a named, reusable set of instructions — write the logic once,
run it as many times as you want by name instead of retyping it every time.

```
fn double(n: Int) -> Int {
    return n * 2;
}

fn main() {
    print(double(5));   // 10
    print(double(21));  // 42
}
```

- `(n: Int)` — this function takes one parameter named `n`, and it must be
  an `Int`. Every parameter needs its type spelled out explicitly (unlike a
  `let`/`var`, the compiler won't guess a parameter's type for you).
- `-> Int` — this function *gives back* (returns) an `Int` when it's done.
  Not every function returns something (`main` doesn't, and neither did
  `print` calls above) — you only write `-> Type` when it does.
- `return n * 2;` — computes `n * 2` and hands that value back to whoever
  called the function. `double(5)` runs the function with `n` set to `5`,
  computes `5 * 2`, and the whole expression `double(5)` becomes `10`.

## 8. Making decisions: `if`/`else`

```
fn classify(hp: Int) -> String {
    if hp <= 0 {
        return "dead";
    } else if hp < 30 {
        return "critical";
    } else {
        return "healthy";
    }
}

fn main() {
    print(classify(100));  // "healthy"
    print(classify(15));   // "critical"
    print(classify(0));    // "dead"
}
```

`if condition { ... }` runs the block inside `{ }` only when `condition` is
`true`. `else if` lets you check another condition if the first was false;
plain `else` catches everything else. Only one of the three blocks above
ever actually runs for a given call.

## 9. Repeating things: loops

Loops run a block of instructions over and over instead of you writing it
out repeatedly by hand.

```
fn main() {
    // "while true, keep going" -- runs until i is no longer less than 3
    var i = 0;
    while i < 3 {
        print(i);
        i = i + 1;
    }
    // prints: 0, 1, 2

    // "for each number from 0 up to (not including) 5" -- a counting loop
    for n in 0..5 {
        print(n);
    }
    // prints: 0, 1, 2, 3, 4
}
```

A `while` loop keeps running its block as long as its condition stays
`true` — you're responsible for making sure something inside the loop
eventually makes the condition `false` (here, `i = i + 1;` — forgetting
that line would make the loop run forever). A `for n in 0..5` loop counts
through a range of numbers automatically, which is shorter to write for the
extremely common "do this exactly N times" case.

## 10. Grouping related data: structs

Often you want several values to travel together as one thing — a
"player" isn't just a number or just a word, it's a name *and* a health
value *and* whatever else describes it. A `struct` groups fields like that
into one named type:

```
struct Player {
    name: String,
    hp: Int,
}

fn main() {
    let p = Player { name: "Rin", hp: 100 };
    print(p.name);   // "Rin"
    print(p.hp);      // 100
}
```

`struct Player { name: String, hp: Int, }` defines the *shape* — every
`Player` has a `name` and an `hp`, no more, no less. `Player { name: "Rin",
hp: 100 }` then actually *builds* one, giving a real value to each field.
`p.name` reads a field out of `p` using a dot.

## 11. Ownership: the one genuinely new idea

Everything so far works roughly the way it would in most languages. This
part is different, and it's the actual reason Hot Chocolate exists as its
own language rather than just being "Java with nicer syntax" — so take it
slowly.

**The rule**: when you pass a `struct` value into a function normally, you
give that value away completely. The function you handed it to now owns
it, and you can't use it anymore — not "you're not supposed to," but a
compile error stops you before the program can even run.

```
struct Player { name: String, hp: Int }

fn take(p: Player) {
    print(p.hp);
}

fn main() {
    let p = Player { name: "Rin", hp: 100 };
    take(p);       // p is handed away to take() here
    print(p.hp);   // ERROR: you no longer have p -- you gave it away
}
```

Think of a `struct` value like a physical object — a real sword, not a
photo of one. If you hand someone a sword, you're not holding it anymore
too; it's really *theirs* now. That's exactly what passing a struct by
value does here. (Plain numbers, `Bool`s, and `String`s don't work this
way — those behave like *facts* you can freely repeat to as many people as
you want, not physical objects you only have one of.)

If you just want a function to *look at* your struct without taking it —
by far the more common case — put a `&` in front of the type, both where
the function declares what it wants and where you call it:

```
fn take(p: &Player) {   // "give me a look, not the whole thing"
    print(p.hp);
}

fn main() {
    let p = Player { name: "Rin", hp: 100 };
    take(&p);        // lending it, not giving it away
    print(p.hp);      // fine! you still have it
}
```

`&p` is called **borrowing** — like lending someone a book instead of
giving it to them. They can read it, then it comes right back to you.

If you want the function to be able to *change* your struct (not just look
at it), you need two things: the variable has to be `var` (mutable — see
section 4), and you borrow it with `&mut` ("mutable borrow") instead of
plain `&`:

```
fn heal(p: &mut Player, amount: Int) {
    p.hp = p.hp + amount;
}

fn main() {
    var p = Player { name: "Rin", hp: 50 };
    heal(&mut p, 20);
    print(p.hp);   // 70
}
```

**Why go to all this trouble?** In most languages, if you pass an object
into a function, that function *could* secretly change it, and you'd have
no way to know just by looking at the call — you'd have to go read the
function's whole implementation to find out. HC's compiler forces every
function to say up front, in its own signature, whether it just wants to
look (`&`), wants to change your value (`&mut`), or wants to take it away
entirely (no `&` at all) — and then it's *checked*, not just a comment you
have to trust. This catches a real, common category of bug (two different
parts of a program stepping on the same piece of data without realizing
it) before your program ever runs, instead of during a confusing debugging
session later.

## 12. Lists of things: arrays

An **array** holds several values of the same type, in order, under one
name:

```
fn main() {
    let scores = [10, 20, 30];
    print(scores.length);   // 3 -- how many values are in it
    print(scores[0]);       // 10 -- the first one (counting starts at 0!)
    print(scores[1]);       // 20 -- the second one

    for s in scores {
        print(s);
    }
    // prints: 10, 20, 30
}
```

`scores[0]` is the *first* value, not the zeroth-skip-one — counting
positions in an array starts at `0`, which trips up everyone the first
time (`scores[3]` here would be an error — there is no fourth element).

## 13. Where to go from here

You now know enough to write small real programs: variables, types, math,
functions, `if`/`while`/`for`, structs, borrowing, and arrays. From here,
**[TUTORIAL.md](TUTORIAL.md)** picks up exactly where this leaves off — it
covers methods (functions that belong to a struct), enums and `match`
(picking between a fixed set of options), generics, interfaces, error
handling, and talking to real Java code, all with the same kind of worked,
runnable examples. It's written for someone who already knows *a*
programming language, which — congratulations — is now you.

The best way to actually learn from here is to change things: take any
example above, guess what a small edit will print, then run it and see if
you were right. That loop (guess, run, compare) is most of what
programming actually *is*, day to day, no matter how experienced you get.
