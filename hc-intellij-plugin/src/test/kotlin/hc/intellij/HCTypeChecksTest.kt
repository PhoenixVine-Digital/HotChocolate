package hc.intellij

import com.intellij.testFramework.fixtures.BasePlatformTestCase

// Drives the real highlighting pipeline, same as `HCAnnotatorTest` -- see that class's own header
// for why (proves the registration is actually wired up, not just that the class compiles).
class HCTypeChecksTest : BasePlatformTestCase() {
    private fun errors(src: String): List<String> {
        myFixture.configureByText("t.hotc", src)
        return myFixture.doHighlighting()
            .filter { it.severity == com.intellij.lang.annotation.HighlightSeverity.ERROR }
            .mapNotNull { it.description }
    }

    fun `test call with too few arguments is flagged`() {
        val errs = errors("fn add(a: Int, b: Int) -> Int { return a + b; }\nfn f() { add(1); }\n")
        assertTrue("expected an arg-count error, got: $errs", errs.any { it.contains("expects 2 argument(s), got 1") })
    }

    fun `test call with too many arguments is flagged`() {
        val errs = errors("fn add(a: Int, b: Int) -> Int { return a + b; }\nfn f() { add(1, 2, 3); }\n")
        assertTrue("expected an arg-count error, got: $errs", errs.any { it.contains("expects 2 argument(s), got 3") })
    }

    fun `test call with correct argument count is not flagged`() {
        val errs = errors("fn add(a: Int, b: Int) -> Int { return a + b; }\nfn f() { add(1, 2); }\n")
        assertTrue("unexpected error(s): $errs", errs.none { it.contains("expects") })
    }

    fun `test call to an unknown function is not flagged by this check`() {
        // Not this check's job -- `HCAnnotator`'s own undefined-reference check already covers
        // "does this name exist at all"; a call to a genuinely unknown name should produce THAT
        // error, not a confusing arg-count error on top of it.
        val errs = errors("fn f() { totallyUnknownFn(1, 2, 3); }\n")
        assertTrue("unexpected arg-count error for an unknown callee: $errs", errs.none { it.contains("expects") })
        assertTrue("expected an unresolved-reference error", errs.any { it.contains("unresolved reference") })
    }

    fun `test static method call with wrong argument count is flagged`() {
        val src = "struct S { x: Int, }\nimpl S {\nfn make(v: Int) -> Int { return v; }\n}\nfn f() { S::make(); }\n"
        val errs = errors(src)
        assertTrue("expected an arg-count error, got: $errs", errs.any { it.contains("S::make") && it.contains("expects 1 argument(s), got 0") })
    }

    fun `test method call with wrong argument count is flagged when unambiguous`() {
        val src = "struct S { x: Int, }\nimpl S {\nfn addTo(&self, n: Int) -> Int { return self.x + n; }\n}\n" +
            "fn f(s: S) { s.addTo(1, 2); }\n"
        val errs = errors(src)
        assertTrue("expected an arg-count error, got: $errs", errs.any { it.contains("'addTo' expects 1 argument(s), got 2") })
    }

    // A TYPED receiver (`a: A`, an explicit param type) resolves PRECISELY now, even when the
    // method name also exists on an unrelated struct -- exact struct match wins over ambiguity.
    fun `test method call on a typed receiver resolves precisely even when the name exists on another struct`() {
        val src = """
            struct A { x: Int, }
            struct B { y: Int, }
            impl A {
                fn go(&self, n: Int) -> Int { return n; }
            }
            impl B {
                fn go(&self, n: Int, m: Int) -> Int { return n + m; }
            }
            fn f(a: A) { a.go(1, 2, 3); }
        """.trimIndent()
        val errs = errors(src)
        assertTrue("expected an arg-count error resolved against A's own 'go', got: $errs", errs.any { it.contains("'go' expects 1 argument(s), got 3") })
    }

    // An UNTYPED receiver (no explicit param/let type to resolve from) falls back to "is this
    // method name unambiguous across every struct in scope" -- genuinely ambiguous here (A and B
    // both declare `go`), so this stays silent rather than guessing which one was meant.
    fun `test method call through a chained call's return type is now precisely resolved`() {
        // `makeA()`'s own return type is real type inference now (`HCTypeInference.kt`), not an
        // "unknown receiver" the way it used to be -- `go` resolves precisely to `A`'s own
        // 1-param `go`, so the wrong argument count here is a REAL error, not a false positive.
        val src = """
            struct A { x: Int, }
            struct B { y: Int, }
            impl A {
                fn go(&self, n: Int) -> Int { return n; }
            }
            impl B {
                fn go(&self, n: Int, m: Int) -> Int { return n + m; }
            }
            fn makeA() -> A { return A { x: 1, }; }
            fn f() { makeA().go(1, 2, 3); }
        """.trimIndent()
        val errs = errors(src)
        assertTrue("expected an arg-count error now that the receiver type resolves, got: $errs", errs.any { it.contains("'go' expects 1 argument(s), got 3") })
    }

    fun `test method call is not flagged when the receiver type is genuinely unresolvable`() {
        // A lambda's own parameter has no declared type at all and is never inferred -- a real,
        // still-unresolvable receiver, unlike the chained-call-return-type case above.
        val src = """
            struct A { x: Int, }
            struct B { y: Int, }
            impl A {
                fn go(&self, n: Int) -> Int { return n; }
            }
            impl B {
                fn go(&self, n: Int, m: Int) -> Int { return n + m; }
            }
            fn f() {
                let apply = |x| x.go(1, 2, 3);
            }
        """.trimIndent()
        val errs = errors(src)
        assertTrue("expected no arg-count error for a genuinely unresolvable receiver: $errs", errs.none { it.contains("expects") })
    }

    fun `test let with mismatched literal type is flagged`() {
        val errs = errors("fn f() {\n    let x: Int = \"oops\";\n}\n")
        assertTrue("expected a type-mismatch error, got: $errs", errs.any { it.contains("type mismatch: expected 'Int', got 'String'") })
    }

    fun `test let with matching literal type is not flagged`() {
        val errs = errors("fn f() {\n    let x: Int = 5;\n    let s: String = \"ok\";\n    let b: Bool = true;\n}\n")
        assertTrue("unexpected error(s): $errs", errs.none { it.contains("type mismatch") })
    }

    fun `test let with array or generic declared type is never flagged (too complex, skipped)`() {
        val errs = errors("fn f() {\n    let xs: [Int] = 5;\n}\n")
        assertTrue("unexpected error(s) for an array type this check should skip: $errs", errs.none { it.contains("type mismatch") })
    }

    fun `test call argument with mismatched literal type is flagged`() {
        val errs = errors("fn greet(name: String) {}\nfn f() { greet(5); }\n")
        assertTrue("expected an argument-type-mismatch error, got: $errs", errs.any { it.contains("argument 1 to 'greet' expects 'String', got 'Int'") })
    }

    fun `test call argument with matching literal type is not flagged`() {
        val errs = errors("fn greet(name: String) {}\nfn f() { greet(\"world\"); }\n")
        assertTrue("unexpected error(s): $errs", errs.none { it.contains("argument") && it.contains("expects") })
    }

    // === struct-literal field checks ===

    fun `test struct literal with unknown field is flagged`() {
        val errs = errors("struct S { x: Int, }\nfn f() { let s = S { x: 1, y: 2, }; }\n")
        assertTrue("expected an unknown-field error, got: $errs", errs.any { it.contains("no such field 'y' on 'S'") })
    }

    fun `test struct literal missing a required field is flagged`() {
        val errs = errors("struct S { x: Int, y: Int, }\nfn f() { let s = S { x: 1, }; }\n")
        assertTrue("expected a missing-field error, got: $errs", errs.any { it.contains("missing field(s) in 'S' literal: y") })
    }

    fun `test struct literal with duplicate field is flagged`() {
        val errs = errors("struct S { x: Int, }\nfn f() { let s = S { x: 1, x: 2, }; }\n")
        assertTrue("expected a duplicate-field error, got: $errs", errs.any { it.contains("duplicate field 'x' in 'S' literal") })
    }

    fun `test struct literal with exactly the right fields is not flagged`() {
        val errs = errors("struct S { x: Int, y: Int, }\nfn f() { let s = S { x: 1, y: 2, }; }\n")
        assertTrue("unexpected error(s): $errs", errs.none { it.contains("field") })
    }

    fun `test enum variant literal fields are checked the same way`() {
        val errs = errors("enum E {\n    V { a: Int, },\n}\nfn f() { let e = V { a: 1, b: 2, }; }\n")
        assertTrue("expected an unknown-field error on an enum variant, got: $errs", errs.any { it.contains("no such field 'b' on 'V'") })
    }

    // === return-value checks ===

    fun `test bare return in a non-Unit fn is flagged`() {
        val errs = errors("fn f() -> Int {\n    return;\n}\n")
        assertTrue("expected a missing-return-value error, got: $errs", errs.any { it.contains("expected a return value of type 'Int'") })
    }

    fun `test return with a value in a non-Unit fn is not flagged`() {
        val errs = errors("fn f() -> Int {\n    return 1;\n}\n")
        assertTrue("unexpected error(s): $errs", errs.none { it.contains("return value") })
    }

    fun `test bare return in a Unit fn is not flagged`() {
        val errs = errors("fn f() {\n    return;\n}\n")
        assertTrue("unexpected error(s): $errs", errs.none { it.contains("return value") })
    }

    // === literal-condition checks ===

    fun `test non-Bool literal if condition is flagged`() {
        val errs = errors("fn f() {\n    if 5 {\n    }\n}\n")
        assertTrue("expected a condition-type error, got: $errs", errs.any { it.contains("condition must be 'Bool', got 'Int'") })
    }

    fun `test Bool literal if condition is not flagged`() {
        val errs = errors("fn f() {\n    if true {\n    }\n}\n")
        assertTrue("unexpected error(s): $errs", errs.none { it.contains("condition must be") })
    }

    fun `test non-literal if condition is never flagged by this check`() {
        val errs = errors("fn f(x: Int) {\n    if x {\n    }\n}\n")
        assertTrue("unexpected error(s) for a non-literal condition this check should skip: $errs", errs.none { it.contains("condition must be") })
    }

    // === field/method access on a known-typed receiver ===

    fun `test field access on a typed param with an unknown field is flagged`() {
        val errs = errors("struct S { x: Int, }\nfn f(s: S) {\n    let y = s.z;\n}\n")
        assertTrue("expected an unknown-field error, got: $errs", errs.any { it.contains("no such field 'z' on 'S'") })
    }

    fun `test field access on a typed param with a real field is not flagged`() {
        val errs = errors("struct S { x: Int, }\nfn f(s: S) {\n    let y = s.x;\n}\n")
        assertTrue("unexpected error(s): $errs", errs.none { it.contains("no such field") })
    }

    fun `test self field access resolves against the enclosing impl's own struct`() {
        val bad = errors("struct S { x: Int, }\nimpl S {\nfn f(&self) -> Int { return self.z; }\n}\n")
        assertTrue("expected an unknown-field error via self, got: $bad", bad.any { it.contains("no such field 'z' on 'S'") })

        val good = errors("struct S { x: Int, }\nimpl S {\nfn f(&self) -> Int { return self.x; }\n}\n")
        assertTrue("unexpected error(s): $good", good.none { it.contains("no such field") })
    }

    fun `test method call on a typed receiver with an unknown method name is flagged`() {
        val errs = errors("struct S { x: Int, }\nimpl S {\nfn go(&self) -> Int { return self.x; }\n}\nfn f(s: S) { s.notReal(); }\n")
        assertTrue("expected a no-such-method error, got: $errs", errs.any { it.contains("no such method 'notReal' on 'S'") })
    }

    fun `test field access on an untyped local is never flagged by this check`() {
        // `y` has no explicit type -- inferred from `s`'s own field access, which this pass
        // doesn't chase -- so `y.whatever` must stay silent rather than guessing.
        val errs = errors("struct S { x: Int, }\nfn f(s: S) {\n    let y = s.x;\n    let z = y.whatever;\n}\n")
        assertTrue("unexpected error(s) for an untyped local this check should skip: $errs", errs.none { it.contains("no such field") })
    }

    // === return-value TYPE checks ===

    fun `test returning a wrong-typed literal is flagged`() {
        val errs = errors("fn f() -> Int {\n    return \"oops\";\n}\n")
        assertTrue("expected a return-type-mismatch error, got: $errs", errs.any { it.contains("expected a return value of type 'Int', got 'String'") })
    }

    fun `test returning a literal from an implicit-Unit fn is flagged`() {
        // No `-> T` at all is a real, common, unambiguous "Unit" -- see `checkReturnValues`'s own
        // header for why this needed its own fix (the ORIGINAL version only checked fns with an
        // EXPLICIT `-> T`, silently skipping the far more common implicit-Unit shape entirely).
        val errs = errors("fn f() {\n    return 5;\n}\n")
        assertTrue("expected an unexpected-return-value error, got: $errs", errs.any { it.contains("unexpected return value: this function returns 'Unit'") })
    }

    fun `test returning a correctly-typed literal is not flagged`() {
        val errs = errors("fn f() -> Int {\n    return 5;\n}\n")
        assertTrue("unexpected error(s): $errs", errs.none { it.contains("return value") })
    }

    fun `test returning a non-literal value is never flagged by this check`() {
        val errs = errors("fn f(x: Int) -> Int {\n    return x;\n}\n")
        assertTrue("unexpected error(s) for a non-literal return this check should skip: $errs", errs.none { it.contains("return value") })
    }

    // === interface implementation checks ===

    fun `test missing required interface method is flagged`() {
        val src = "interface Renderable {\n    fn render(&self) -> String;\n}\nstruct S {}\nimpl Renderable for S {\n}\n"
        val errs = errors(src)
        assertTrue("expected a missing-implementation error, got: $errs", errs.any { it.contains("missing implementation of 'render' required by interface 'Renderable'") })
    }

    fun `test interface method with a default body does not need to be implemented`() {
        val src = "interface Greeter {\n    fn hello(&self) -> String { return \"hi\"; }\n}\nstruct S {}\nimpl Greeter for S {\n}\n"
        val errs = errors(src)
        assertTrue("unexpected error(s): $errs", errs.none { it.contains("missing implementation") })
    }

    fun `test implemented method with correct signature is not flagged`() {
        val src = "interface Renderable {\n    fn render(&self) -> String;\n}\nstruct S {}\nimpl Renderable for S {\n" +
            "fn render(&self) -> String { return \"s\"; }\n}\n"
        val errs = errors(src)
        assertTrue("unexpected error(s): $errs", errs.none { it.contains("interface") })
    }

    fun `test implemented method with wrong return type is flagged`() {
        val src = "interface Renderable {\n    fn render(&self) -> String;\n}\nstruct S {}\nimpl Renderable for S {\n" +
            "fn render(&self) -> Int { return 1; }\n}\n"
        val errs = errors(src)
        assertTrue("expected a return-type-override error, got: $errs", errs.any { it.contains("'render' overrides interface 'Renderable' with return type 'Int', expected 'String'") })
    }

    fun `test implemented method with wrong param count is flagged`() {
        val src = "interface Adder {\n    fn add(&self, n: Int) -> Int;\n}\nstruct S {}\nimpl Adder for S {\n" +
            "fn add(&self, n: Int, m: Int) -> Int { return n + m; }\n}\n"
        val errs = errors(src)
        assertTrue("expected a param-count-override error, got: $errs", errs.any { it.contains("'add' overrides interface 'Adder' with 2 param(s), expected 1") })
    }

    fun `test inherent impl with no for-clause is never checked by this check`() {
        val errs = errors("struct S { x: Int, }\nimpl S {\nfn go(&self) -> Int { return self.x; }\n}\n")
        assertTrue("unexpected error(s): $errs", errs.none { it.contains("interface") })
    }

    // -- generics resolution --

    private val boxSrc = "struct Box<T> { value: T, }\n" +
        "impl<T> Box<T> {\n" +
        "    fn get(&self) -> T { return self.value; }\n" +
        "    fn set(&mut self, v: T) {}\n" +
        "}\n"

    fun `test method call on a generic-typed local is not falsely flagged`() {
        val src = boxSrc + "fn f() {\n    let b: Box<Int> = Box { value: 5, };\n    b.get();\n    b.set(1);\n}\n"
        val errs = errors(src)
        assertTrue("unexpected error(s) for real generic method calls: $errs", errs.isEmpty())
    }

    fun `test unknown method on a generic-typed local is still flagged`() {
        val src = boxSrc + "fn f() {\n    let b: Box<Int> = Box { value: 5, };\n    b.notReal();\n}\n"
        val errs = errors(src)
        assertTrue("expected a no-such-method error, got: $errs", errs.any { it.contains("notReal") })
    }

    fun `test wrong argument count on a generic method is still flagged`() {
        val src = boxSrc + "fn f() {\n    let b: Box<Int> = Box { value: 5, };\n    b.set();\n}\n"
        val errs = errors(src)
        assertTrue("expected an arg-count error, got: $errs", errs.any { it.contains("expects 1 argument(s), got 0") })
    }

    fun `test passing a real-typed argument to a generic param is not falsely flagged as a type mismatch`() {
        // `v: T` must be treated as "unknown type", never compared against `Int` as if `T` were a
        // literal type name -- this is the false-positive this whole feature was designed to avoid.
        val src = boxSrc + "fn f() {\n    let b: Box<Int> = Box { value: 5, };\n    b.set(1);\n}\n"
        val errs = errors(src)
        assertTrue("unexpected error(s): $errs", errs.none { it.contains("expected") && it.contains("got") })
    }

    fun `test generic top-level function is not falsely flagged on its own type-parameter usage`() {
        val src = "fn identity<T>(x: T) -> T { return x; }\nfn f() {\n    identity(1);\n    identity(\"s\");\n}\n"
        val errs = errors(src)
        assertTrue("unexpected error(s) for a real generic fn: $errs", errs.isEmpty())
    }

    fun `test plain interface implemented for a generic struct is not falsely flagged`() {
        // Interfaces themselves have no `<T>` syntax in this grammar (unlike `struct`/`impl`/`fn`)
        // -- only the implementing struct is generic here.
        val src = "interface Describable {\n    fn describe(&self) -> String;\n}\n" +
            "struct Box<T> { value: T, }\n" +
            "impl<T> Describable for Box<T> {\n" +
            "    fn describe(&self) -> String { return \"a box\"; }\n" +
            "}\n"
        val errs = errors(src)
        assertTrue("unexpected error(s) for a real interface impl on a generic struct: $errs", errs.isEmpty())
    }

    // -- match exhaustiveness --

    private val statusSrc = "enum Status {\n    Active,\n    Inactive,\n    Pending,\n}\n"

    fun `test non-exhaustive match on an enum param is flagged with the missing variants`() {
        val src = statusSrc + "fn describe(status: Status) -> String {\n" +
            "    match status {\n        Status::Active => { return \"active\"; }\n    }\n}\n"
        val errs = errors(src)
        assertTrue(
            "expected a non-exhaustive-match error naming the missing variants, got: $errs",
            errs.any { it.contains("match on 'Status' isn't exhaustive") && it.contains("Inactive") && it.contains("Pending") },
        )
    }

    fun `test exhaustive match covering every variant is not flagged`() {
        val src = statusSrc + "fn describe(status: Status) -> String {\n" +
            "    match status {\n" +
            "        Status::Active => { return \"active\"; }\n" +
            "        Status::Inactive => { return \"inactive\"; }\n" +
            "        Status::Pending => { return \"pending\"; }\n" +
            "    }\n}\n"
        val errs = errors(src)
        assertTrue("unexpected error(s): $errs", errs.none { it.contains("exhaustive") })
    }

    fun `test match with a wildcard arm is not flagged even when variants are missing`() {
        val src = statusSrc + "fn describe(status: Status) -> String {\n" +
            "    match status {\n        Status::Active => { return \"active\"; }\n        _ => { return \"other\"; }\n    }\n}\n"
        val errs = errors(src)
        assertTrue("unexpected error(s): $errs", errs.none { it.contains("exhaustive") })
    }

    fun `test unreachable arm after a wildcard is flagged`() {
        val src = statusSrc + "fn describe(status: Status) -> String {\n" +
            "    match status {\n        _ => { return \"other\"; }\n        Status::Active => { return \"active\"; }\n    }\n}\n"
        val errs = errors(src)
        assertTrue("expected an unreachable-arm error, got: $errs", errs.any { it.contains("unreachable arm after wildcard") })
    }

    fun `test duplicate arm for the same variant is flagged`() {
        val src = statusSrc + "fn describe(status: Status) -> String {\n" +
            "    match status {\n" +
            "        Status::Active => { return \"active\"; }\n" +
            "        Status::Active => { return \"active again\"; }\n" +
            "        Status::Inactive => { return \"inactive\"; }\n" +
            "        Status::Pending => { return \"pending\"; }\n" +
            "    }\n}\n"
        val errs = errors(src)
        assertTrue("expected a duplicate-arm error, got: $errs", errs.any { it.contains("duplicate arm for variant 'Active'") })
    }

    fun `test unknown variant name in a match arm is flagged`() {
        val src = statusSrc + "fn describe(status: Status) -> String {\n" +
            "    match status {\n        Status::NotReal => { return \"?\"; }\n        _ => { return \"other\"; }\n    }\n}\n"
        val errs = errors(src)
        assertTrue("expected a not-a-variant error, got: $errs", errs.any { it.contains("'NotReal' is not a variant of 'Status'") })
    }

    fun `test match on a value of unknown type is not checked at all`() {
        // Scrutinee is a chained call result, not a bare typed local -- this pass deliberately
        // stays silent rather than guessing (see `scrutineeEnumType`'s own header).
        val src = statusSrc + "fn describe() -> String {\n" +
            "    match makeStatus() {\n        Status::Active => { return \"active\"; }\n    }\n}\n" +
            "fn makeStatus() -> Status { return Status::Active; }\n"
        val errs = errors(src)
        assertTrue("unexpected error(s): $errs", errs.none { it.contains("exhaustive") })
    }

    fun `test match expression form is also checked for exhaustiveness`() {
        val src = statusSrc + "fn describe(status: Status) -> Int {\n" +
            "    let result = match status {\n        Status::Active => { 1 }\n    };\n    return result;\n}\n"
        val errs = errors(src)
        assertTrue(
            "expected a non-exhaustive-match error on the expression form, got: $errs",
            errs.any { it.contains("match on 'Status' isn't exhaustive") },
        )
    }
}
