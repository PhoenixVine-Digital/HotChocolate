package hc.intellij

import com.intellij.testFramework.fixtures.BasePlatformTestCase

// Locks in the real type-inference upgrade (`HCTypeInference.kt`) -- these are all shapes the
// OLDER, literal/explicit-annotation-only version of `HCTypeChecks.kt` would have silently skipped
// (returned `null`, "unknown, don't guess") but a real user would expect caught (or correctly left
// alone) now that arbitrary expressions have real types.
class HCTypeInferenceTest : BasePlatformTestCase() {
    private fun errors(src: String): List<String> {
        myFixture.configureByText("t.hotc", src)
        return myFixture.doHighlighting()
            .filter { it.severity == com.intellij.lang.annotation.HighlightSeverity.ERROR }
            .mapNotNull { it.description }
    }

    fun `test let type mismatch is caught through a binary arithmetic expression`() {
        val errs = errors("fn f() {\n    let a = 1;\n    let b = 2;\n    let x: String = a + b;\n}\n")
        assertTrue("expected a type-mismatch error, got: $errs", errs.any { it.contains("type mismatch: expected 'String', got 'Int'") })
    }

    fun `test an untyped let's inferred type is not falsely flagged when used correctly later`() {
        val src = "fn f() {\n    let a = 1;\n    let b = 2;\n    let sum = a + b;\n    let ok: Int = sum;\n}\n"
        assertTrue("unexpected error(s): ${errors(src)}", errors(src).isEmpty())
    }

    fun `test numeric widening produces the wider type`() {
        val errs = errors("fn f() {\n    let a = 1;\n    let b = 2.0;\n    let x: Double = a + b;\n}\n")
        assertTrue("unexpected error(s) for a real Int+Double widening: $errs", errs.isEmpty())
    }

    fun `test string concatenation is inferred as String`() {
        val errs = errors("fn f() {\n    let name = \"a\";\n    let x: Int = name + \"b\";\n}\n")
        assertTrue("expected a type-mismatch error, got: $errs", errs.any { it.contains("type mismatch: expected 'Int', got 'String'") })
    }

    fun `test comparison and logical operators are always Bool`() {
        // Empty blocks (`b {}`) are deliberately avoided here -- a bare identifier IMMEDIATELY
        // followed by `{}` is a genuine, pre-existing grammar ambiguity (`identLed`'s own
        // `looksLikeStructLiteral`: an immediate `}` right after `{` reads as an empty struct
        // literal, not a block), unrelated to this test's own subject.
        val src = "fn f() {\n    let a = 1;\n    let b = 2;\n    if a < b { print(1); }\n    if a == b && true { print(2); }\n}\n"
        assertTrue("unexpected error(s): ${errors(src)}", errors(src).isEmpty())
    }

    fun `test a real Bool condition built from comparisons is not falsely flagged`() {
        val errs = errors("fn f() {\n    let a = 1;\n    while a < 10 {\n        a;\n    }\n}\n")
        assertTrue("unexpected error(s): $errs", errs.none { it.contains("condition must be") })
    }

    fun `test a non-Bool condition built from arithmetic is flagged`() {
        val errs = errors("fn f() {\n    let a = 1;\n    let b = 2;\n    if a + b { print(1); }\n}\n")
        assertTrue("expected a condition-type error, got: $errs", errs.any { it.contains("condition must be 'Bool', got 'Int'") })
    }

    fun `test return type mismatch is caught through a binary expression`() {
        val src = "fn f() -> String {\n    let a = 1;\n    let b = 2;\n    return a + b;\n}\n"
        assertTrue("expected a return-type error, got: ${errors(src)}", errors(src).any { it.contains("expected a return value of type 'String', got 'Int'") })
    }

    fun `test argument type mismatch is caught through an inferred local`() {
        val src = "fn add(a: Int, b: Int) -> Int { return a + b; }\nfn f() {\n    let s = \"x\";\n    add(s, 1);\n}\n"
        assertTrue("expected an argument type error, got: ${errors(src)}", errors(src).any { it.contains("argument 1 to 'add' expects 'Int', got 'String'") })
    }

    fun `test field access resolves through an untyped local's struct-literal type`() {
        val src = "struct Point { x: Int, y: Int, }\nfn f() {\n    let p = Point { x: 1, y: 2, };\n    print(p.x);\n    print(p.z);\n}\n"
        val errs = errors(src)
        assertTrue("expected a no-such-field error for 'z', got: $errs", errs.any { it.contains("no such field 'z' on 'Point'") })
        assertTrue("unexpected error for the real field 'x': $errs", errs.none { it.contains("'x'") })
    }

    fun `test method call resolves through an untyped local's struct-literal type`() {
        val src = "struct S { v: Int, }\nimpl S {\nfn go(&self) -> Int { return self.v; }\n}\n" +
            "fn f() {\n    let s = S { v: 1, };\n    s.go();\n    s.notReal();\n}\n"
        val errs = errors(src)
        assertTrue("expected a no-such-method error, got: $errs", errs.any { it.contains("no such method 'notReal' on 'S'") })
    }

    fun `test a chained method call's own return type is inferred for further field access`() {
        val src = "struct Point { x: Int, }\nstruct Holder { p: Point, }\nimpl Holder {\nfn point(&self) -> Point { return self.p; }\n}\n" +
            "fn f(h: Holder) {\n    print(h.point().x);\n    print(h.point().bogus);\n}\n"
        val errs = errors(src)
        assertTrue("expected a no-such-field error via a chained call, got: $errs", errs.any { it.contains("no such field 'bogus' on 'Point'") })
    }

    fun `test a dyn interface type is never compared against a concrete struct literal`() {
        // Real regression found via the sweep: `&dyn Trait` accepts any implementing struct --
        // comparing the literal's own concrete type name against the interface name directly
        // would be a false positive (`examples/battle/main.hotc`'s real `let first: &dyn Enemy =
        // &Goblin { };`).
        val src = "interface Enemy {\n    fn hp(&self) -> Int;\n}\nstruct Goblin {}\nimpl Enemy for Goblin {\nfn hp(&self) -> Int { return 1; }\n}\n" +
            "fn f() {\n    let first: &dyn Enemy = &Goblin {};\n}\n"
        assertTrue("unexpected error(s) for a real dyn-interface upcast: ${errors(src)}", errors(src).isEmpty())
    }

    fun `test a static call's own return type is inferred`() {
        val src = "struct S { v: Int, }\nimpl S {\nfn make(x: Int) -> S { return S { v: x, }; }\n}\n" +
            "fn f() {\n    let s = S::make(1);\n    print(s.v);\n    print(s.bogus);\n}\n"
        val errs = errors(src)
        assertTrue("expected a no-such-field error via a static-call return type, got: $errs", errs.any { it.contains("no such field 'bogus' on 'S'") })
    }
}
