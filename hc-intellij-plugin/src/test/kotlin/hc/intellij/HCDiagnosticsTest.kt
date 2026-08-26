package hc.intellij

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase

// Drives the REAL highlighting pipeline end to end (same reasoning as `HCAnnotatorTest`'s own
// header) -- covers the six diagnostics added on top of the earlier checks: unreachable code,
// unused variable, unused function, shadowing, `sealed interface` match exhaustiveness, and
// conservative move-checker surfacing. Every "negative" test here matters just as much as the
// "positive" ones: each documents a real, common, VALID pattern this check must stay quiet on --
// getting one of these wrong is a false positive, the one thing every check in this file is
// designed around avoiding.
class HCDiagnosticsTest : BasePlatformTestCase() {
    private fun warnings(src: String): List<String> {
        myFixture.configureByText("t.hotc", src)
        return myFixture.doHighlighting()
            .filter { it.severity == HighlightSeverity.WARNING }
            .mapNotNull { it.description }
    }

    private fun errors(src: String): List<String> {
        myFixture.configureByText("t.hotc", src)
        return myFixture.doHighlighting()
            .filter { it.severity == HighlightSeverity.ERROR }
            .mapNotNull { it.description }
    }

    // === Unreachable code ===

    fun `test code directly after return is unreachable`() {
        val warns = warnings("fn f() -> Int {\n    return 1;\n    print(2);\n}\n")
        assertTrue("expected unreachable code, got: $warns", warns.any { it.contains("unreachable code") })
    }

    fun `test code directly after break or continue is unreachable`() {
        val warns = warnings("fn f() {\n    while true {\n        break;\n        print(1);\n    }\n}\n")
        assertTrue("expected unreachable code after break, got: $warns", warns.any { it.contains("unreachable code") })
    }

    fun `test code in a sibling if branch after an unrelated return is not flagged`() {
        val warns = warnings("fn f(x: Bool) -> Int {\n    if x {\n        return 1;\n    }\n    return 2;\n}\n")
        assertTrue("a statement after the WHOLE if-statement (not after a return in the SAME block) is reachable: $warns", warns.none { it.contains("unreachable code") })
    }

    fun `test normal sequential statements are not flagged`() {
        val warns = warnings("fn f() {\n    print(1);\n    print(2);\n    print(3);\n}\n")
        assertTrue(warns.none { it.contains("unreachable") })
    }

    // === Unused variable ===

    fun `test an unused let binding is flagged`() {
        val warns = warnings("fn f() {\n    let x = 1;\n}\n")
        assertTrue("expected unused variable, got: $warns", warns.any { it.contains("variable 'x' is never used") })
    }

    fun `test a used let binding is not flagged`() {
        val warns = warnings("fn f() {\n    let x = 1;\n    print(x);\n}\n")
        assertTrue(warns.none { it.contains("variable 'x' is never used") })
    }

    fun `test an underscore-prefixed binding is never flagged as unused`() {
        val warns = warnings("fn f() {\n    let _dummy = read_line_stub();\n}\nfn read_line_stub() -> Int { return 1; }\n")
        assertTrue(warns.none { it.contains("variable '_dummy' is never used") })
    }

    fun `test a variable used only inside a lambda is not flagged`() {
        val warns = warnings("fn f() {\n    let x = 1;\n    let g = |y| { x + y };\n}\n")
        assertTrue("expected x's use inside the lambda body to count: $warns", warns.none { it.contains("variable 'x' is never used") })
    }

    // === Unused function ===

    fun `test a non-pub top-level function with no callers is flagged`() {
        val warns = warnings("fn helper() -> Int {\n    return 1;\n}\nfn main() {\n    print(1);\n}\n")
        assertTrue("expected unused function, got: $warns", warns.any { it.contains("function 'helper' is never used") })
    }

    fun `test a called function is not flagged`() {
        val warns = warnings("fn helper() -> Int {\n    return 1;\n}\nfn main() {\n    print(helper());\n}\n")
        assertTrue(warns.none { it.contains("is never used") })
    }

    fun `test main is never flagged even with no callers`() {
        val warns = warnings("fn main() {\n    print(1);\n}\n")
        assertTrue(warns.none { it.contains("function 'main'") })
    }

    fun `test a pub function is never flagged`() {
        val warns = warnings("pub fn api() -> Int {\n    return 1;\n}\nfn main() {\n    print(1);\n}\n")
        assertTrue(warns.none { it.contains("function 'api'") })
    }

    fun `test an entry-annotated function is never flagged`() {
        val warns = warnings("@entry(\"mod\")\nfn onLoad() {\n    print(1);\n}\n")
        assertTrue(warns.none { it.contains("function 'onLoad'") })
    }

    fun `test an impl method is never flagged by the unused-function check`() {
        val warns = warnings("struct S { v: Int, }\nimpl S {\nfn unused_method(&self) -> Int { return self.v; }\n}\nfn main() { print(1); }\n")
        assertTrue("impl methods are out of scope for this check entirely: $warns", warns.none { it.contains("is never used") })
    }

    // === Shadowing ===

    fun `test a nested let shadowing an outer let is flagged`() {
        val warns = warnings("fn f() {\n    let x = 1;\n    if true {\n        let x = 2;\n        print(x);\n    }\n}\n")
        assertTrue("expected a shadow warning, got: $warns", warns.any { it.contains("'x' shadows a declaration") })
    }

    fun `test a let shadowing a parameter is flagged`() {
        val warns = warnings("fn f(x: Int) {\n    let x = x + 1;\n    print(x);\n}\n")
        assertTrue("expected a shadow warning, got: $warns", warns.any { it.contains("'x' shadows a declaration") })
    }

    fun `test sibling if-else branches each declaring their own let with the same name are not flagged`() {
        val warns = warnings("fn f(cond: Bool) {\n    if cond {\n        let x = 1;\n        print(x);\n    } else {\n        let x = 2;\n        print(x);\n    }\n}\n")
        assertTrue("sibling scopes are NOT nested inside each other, must never be flagged: $warns", warns.none { it.contains("shadows") })
    }

    fun `test two lets with different names in the same function are not flagged`() {
        val warns = warnings("fn f() {\n    let x = 1;\n    let y = 2;\n    print(x);\n    print(y);\n}\n")
        assertTrue(warns.none { it.contains("shadows") })
    }

    fun `test loop variables in sibling for loops with the same name are not flagged`() {
        val warns = warnings("fn f(a: [Int], b: [Int]) {\n    for x in a {\n        print(x);\n    }\n    for x in b {\n        print(x);\n    }\n}\n")
        assertTrue("sibling for-loops are not nested, must never be flagged: $warns", warns.none { it.contains("shadows") })
    }

    // === Sealed interface match exhaustiveness ===

    private val sealedInterfaceSrc = "sealed interface Shape {\n    fn area(&self) -> Int;\n}\n" +
        "struct Circle { radius: Int, }\nimpl Shape for Circle {\nfn area(&self) -> Int { return self.radius; }\n}\n" +
        "struct Square { side: Int, }\nimpl Shape for Square {\nfn area(&self) -> Int { return self.side; }\n}\n"

    fun `test a match missing a struct arm for a sealed interface is not exhaustive`() {
        val src = sealedInterfaceSrc + "fn describe(s: &dyn Shape) -> Int {\n    match s {\n        Circle { radius } => { return radius; }\n    }\n}\n"
        val errs = errors(src)
        assertTrue("expected a not-exhaustive error, got: $errs", errs.any { it.contains("isn't exhaustive") && it.contains("Square") })
    }

    fun `test a match covering every implementing struct is exhaustive`() {
        val src = sealedInterfaceSrc +
            "fn describe(s: &dyn Shape) -> Int {\n    match s {\n        Circle { radius } => { return radius; }\n        Square { side } => { return side; }\n    }\n}\n"
        val errs = errors(src)
        assertTrue("expected no exhaustiveness error: $errs", errs.none { it.contains("isn't exhaustive") })
    }

    fun `test a match with a wildcard arm is exhaustive even with structs missing`() {
        val src = sealedInterfaceSrc +
            "fn describe(s: &dyn Shape) -> Int {\n    match s {\n        Circle { radius } => { return radius; }\n        _ => { return 0; }\n    }\n}\n"
        val errs = errors(src)
        assertTrue("expected no exhaustiveness error with a wildcard present: $errs", errs.none { it.contains("isn't exhaustive") })
    }

    // === Move-checker surfacing ===

    private val movableStructSrc = "struct Bag { v: Int, }\nfn take(b: Bag) -> Int {\n    return b.v;\n}\n"

    fun `test passing the same struct-typed local by value twice is flagged`() {
        val src = movableStructSrc + "fn f() {\n    let bag = Bag { v: 1, };\n    take(bag);\n    take(bag);\n}\n"
        val warns = warnings(src)
        assertTrue("expected a possible-move warning, got: $warns", warns.any { it.contains("possible use of 'bag'") })
    }

    fun `test borrowing the same struct-typed local twice is not flagged`() {
        val src = "struct Bag { v: Int, }\nfn peek(b: &Bag) -> Int {\n    return b.v;\n}\n" +
            "fn f() {\n    let bag = Bag { v: 1, };\n    peek(&bag);\n    peek(&bag);\n}\n"
        val warns = warnings(src)
        assertTrue("a borrowed argument is never consumed, must never be flagged: $warns", warns.none { it.contains("possible use of") })
    }

    fun `test reassigning the variable between two by-value uses is not flagged`() {
        val src = movableStructSrc + "fn f() {\n    var bag = Bag { v: 1, };\n    take(bag);\n    bag = Bag { v: 2, };\n    take(bag);\n}\n"
        val warns = warnings(src)
        assertTrue("a fresh value from reassignment must never be flagged as still-moved: $warns", warns.none { it.contains("possible use of") })
    }

    fun `test passing a Copy type like Int by value twice is not flagged`() {
        val src = "fn take(n: Int) -> Int {\n    return n;\n}\nfn f() {\n    let n = 1;\n    take(n);\n    take(n);\n}\n"
        val warns = warnings(src)
        assertTrue("Int is Copy, must never be flagged: $warns", warns.none { it.contains("possible use of") })
    }

    fun `test a single by-value use is not flagged`() {
        val src = movableStructSrc + "fn f() {\n    let bag = Bag { v: 1, };\n    take(bag);\n}\n"
        val warns = warnings(src)
        assertTrue(warns.none { it.contains("possible use of") })
    }

    fun `test uses split across an if branch are not flagged (documented scope cut)`() {
        // Each branch gets fresh, independent move tracking -- see `checkMovesInBlock`'s own
        // header on why this is a deliberate miss, not something this test expects to catch.
        val src = movableStructSrc + "fn f(cond: Bool) {\n    let bag = Bag { v: 1, };\n    if cond {\n        take(bag);\n    }\n    take(bag);\n}\n"
        val warns = warnings(src)
        assertTrue(warns.none { it.contains("possible use of") })
    }
}
