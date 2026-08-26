package hc.intellij

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

// Drives the REAL highlighting pipeline (`myFixture.configureByText` + `doHighlighting()`), not a
// direct call into `HCAnnotator` -- this is what actually proves the `<annotator language=...>`
// registration in `plugin.xml` is wired up correctly, not just that the class itself compiles.
class HCAnnotatorTest : BasePlatformTestCase() {
    private fun errors(src: String): List<String> {
        myFixture.configureByText("t.hotc", src)
        return myFixture.doHighlighting()
            .filter { it.severity == com.intellij.lang.annotation.HighlightSeverity.ERROR }
            .mapNotNull { it.description }
    }

    fun `test unrecognized directive is flagged`() {
        val errs = errors("@bogus\nfn f() {}\n")
        assertTrue("expected an unrecognized-directive error, got: $errs", errs.any { it.contains("known compiler directive") })
    }

    fun `test recognized real annotation string is not flagged`() {
        val errs = errors("@\"java.lang.Deprecated\"\nfn f() {}\n")
        assertTrue("unexpected error(s) for a valid quoted annotation: $errs", errs.isEmpty())
    }

    fun `test at-serializable before fn is flagged`() {
        val errs = errors("@serializable\nfn f() {}\n")
        assertTrue("expected an @serializable-placement error, got: $errs", errs.any { it.contains("@serializable") })
    }

    fun `test at-serializable before struct is not flagged`() {
        val errs = errors("@serializable\nstruct S { x: Int, }\n")
        assertTrue("unexpected error(s): $errs", errs.isEmpty())
    }

    fun `test at-must_use before impl method is not flagged`() {
        val errs = errors("struct S { x: Int, }\nimpl S {\n@must_use\nfn f(&self) -> Int { return 1; }\n}\n")
        assertTrue("unexpected error(s): $errs", errs.isEmpty())
    }

    fun `test at-dev before impl method is flagged`() {
        val errs = errors("struct S { x: Int, }\nimpl S {\n@dev\nfn f(&self) -> Int { return 1; }\n}\n")
        assertTrue("expected an impl-method-annotation error, got: $errs", errs.any { it.contains("only '@must_use'") })
    }

    fun `test break outside a loop is flagged`() {
        val errs = errors("fn f() {\n    break;\n}\n")
        assertTrue("expected a break-outside-loop error, got: $errs", errs.any { it.contains("'break'") })
    }

    fun `test continue outside a loop is flagged`() {
        val errs = errors("fn f() {\n    continue;\n}\n")
        assertTrue("expected a continue-outside-loop error, got: $errs", errs.any { it.contains("'continue'") })
    }

    fun `test break inside a while loop is not flagged`() {
        val errs = errors("fn f() {\n    while true {\n        break;\n    }\n}\n")
        assertTrue("unexpected error(s): $errs", errs.isEmpty())
    }

    fun `test break inside a nested fn inside a loop is still flagged`() {
        // A loop in an OUTER fn doesn't make `break` legal inside some unrelated inner
        // construct -- not directly expressible without lambdas/closures in this grammar, so this
        // just double-checks the FN_DECL boundary stop condition doesn't accidentally let a
        // sibling loop "leak" scope; two independent top-level fns, one with a loop, one without.
        val errs = errors("fn withLoop() {\n    while true {\n        break;\n    }\n}\nfn withoutLoop() {\n    break;\n}\n")
        assertTrue("expected exactly one break-outside-loop error, got: $errs", errs.count { it.contains("'break'") } == 1)
    }

    fun `test duplicate struct name is flagged`() {
        val errs = errors("struct A { x: Int, }\nstruct A { y: Int, }\n")
        assertTrue("expected a duplicate-struct error, got: $errs", errs.any { it.contains("Duplicate struct 'A'") })
    }

    fun `test duplicate function name is flagged`() {
        val errs = errors("fn f() {}\nfn f() {}\n")
        assertTrue("expected a duplicate-function error, got: $errs", errs.any { it.contains("Duplicate function 'f'") })
    }

    fun `test struct and enum variant sharing a name is flagged`() {
        val errs = errors("enum E {\n    A,\n    B,\n}\nstruct A { x: Int, }\n")
        assertTrue("expected a duplicate variant/struct-name error, got: $errs", errs.any { it.contains("Duplicate variant/struct name 'A'") })
    }

    fun `test distinct top-level names are not flagged`() {
        val errs = errors("struct A { x: Int, }\nfn f() {}\nenum E { X, Y, }\n")
        assertTrue("unexpected error(s): $errs", errs.isEmpty())
    }

    fun `test a genuinely undefined reference is flagged`() {
        val errs = errors("fn f() {\n    print(totallyUndefinedName);\n}\n")
        assertTrue("expected an unresolved-reference error, got: $errs", errs.any { it.contains("unresolved reference 'totallyUndefinedName'") })
    }

    fun `test params, let, for, match, catch, and lambda bindings are all recognized`() {
        val src = """
            enum Opt { Some { v: Int }, None, }
            fn f(p: Int) -> Int {
                let a = p;
                var total = a;
                for i in 0..5 {
                    total = total + i;
                }
                match Some { v: 1 } {
                    Some { v } => { total = total + v; }
                    None => {}
                }
                try {
                    total = total + 1;
                } catch (e: RuntimeException) {
                    total = 0;
                }
                let adder = |x, y| x + y;
                return adder(total, a);
            }
        """.trimIndent()
        val errs = errors(src)
        assertTrue("unexpected error(s) for real bindings: $errs", errs.none { it.contains("unresolved reference") })
    }

    fun `test builtins and prelude functions are not flagged`() {
        val src = """
            fn f() {
                print("hi");
                drop(1);
                let s = read_line();
                let v = vec_of(1);
                let r = registry_new("k", 1);
                let n = read_int();
                let t = read_string();
            }
        """.trimIndent()
        val errs = errors(src)
        assertTrue("unexpected error(s) for builtins/prelude fns: $errs", errs.none { it.contains("unresolved reference") })
    }

    fun `test self is recognized inside a method but flagged inside a static method`() {
        val withSelf = errors("struct S { x: Int, }\nimpl S {\nfn f(&self) -> Int { return self.x; }\n}\n")
        assertTrue("unexpected error(s): $withSelf", withSelf.none { it.contains("unresolved reference") })

        // A method is "static" purely by omitting `self`/`&self`/`&mut self` as its first param
        // -- there's no separate `static` keyword inside `impl` at all (see `HCPsiParser.implDecl`'s
        // own header).
        val staticMethod = errors("struct S { x: Int, }\nimpl S {\nfn f() -> Int { return self.x; }\n}\n")
        assertTrue("expected 'self' to be flagged in a static method, got: $staticMethod", staticMethod.any { it.contains("unresolved reference 'self'") })
    }

    // The annotator's own counterpart to `HCParserTest`'s real-example sweep -- proves these
    // three real semantic checks don't FALSELY flag real, already-working code. `bad_test.hotc`
    // is deliberately excluded: per its own header, it's a negative test fixture for the REAL
    // compiler's checker (wrong return types, non-exhaustive matches, ...), not something that's
    // supposed to be free of ALL possible errors -- these three checks might legitimately fire on
    // it for real, unrelated reasons that aren't a regression here.
    // Files are grouped and added to the test project BY DIRECTORY (all of a directory's files
    // added together via `addFileToProject`, mirroring `myFixture`'s own on-disk layout) before
    // any of them is actually highlighted -- necessary for `HCAnnotator.collectTopLevelValueNames`'s
    // own sibling-directory scan (see that fn's own header) to see real siblings at all. A plain
    // `configureByText("t.hotc", ...)` per file (like every other test in this class) would give
    // each file an isolated, sibling-less directory, silently defeating exactly the check this
    // sweep exists to catch.
    fun `test real example programs produce no unexpected annotator errors`() {
        val examplesDir = findExamplesDir()
        val files = examplesDir.walkTopDown()
            .filter { it.isFile && (it.extension == "hc" || it.extension == "hotc") && it.name != "bad_test.hotc" }
            .toList()
        assertTrue("expected to find example .hc/.hotc files under $examplesDir", files.isNotEmpty())

        val byDir = files.groupBy { it.parentFile }
        val failures = StringBuilder()
        for ((dirIndex, group) in byDir.values.withIndex()) {
            val added = group.associateWith { f ->
                myFixture.addFileToProject("dir$dirIndex/${f.name}", f.readText())
            }
            for ((f, psiFile) in added) {
                myFixture.configureFromExistingVirtualFile(psiFile.virtualFile)
                val errs = myFixture.doHighlighting()
                    .filter { it.severity == com.intellij.lang.annotation.HighlightSeverity.ERROR }
                    .mapNotNull { it.description }
                if (errs.isNotEmpty()) failures.append("${f.path}:\n").append(errs.joinToString("\n") { "  $it" }).append("\n")
            }
        }
        assertTrue("real programs with unexpected annotator errors:\n$failures", failures.isEmpty())
    }

    private fun findExamplesDir(): File {
        var dir = File(".").absoluteFile
        repeat(5) {
            val candidate = File(dir, "examples")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        throw IllegalStateException("could not locate the repo's examples/ directory from ${File(".").absolutePath}")
    }
}
