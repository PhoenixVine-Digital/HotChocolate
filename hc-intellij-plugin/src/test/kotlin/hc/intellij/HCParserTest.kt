package hc.intellij

import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

// A real IDE application/project context IS needed here (unlike `HCLexerTest`) --
// `PsiFileFactory` drives the real parsing pipeline end to end, including the whitespace/comment
// binding `HCParserDefinition.getWhitespaceTokens()`/`getCommentTokens()` register, which
// `HCPsiParser` depends on implicitly (comment/whitespace tokens never reach its own grammar
// checks -- see that file's own header). `BasePlatformTestCase` boots exactly that context.
//
// This is the parser's own counterpart to `HCLexerTest`'s real-example sweep: proves the new
// grammar doesn't FALSELY reject real, already-working `.hc`/`.hotc` programs (a `PsiErrorElement`
// anywhere in a real file's tree is a real parser gap, not a nitpick -- IntelliJ renders every one
// as a live red-underline syntax error the moment this parser is wired up).
class HCParserTest : BasePlatformTestCase() {
    fun `test real example programs parse with no syntax errors`() {
        val examplesDir = findExamplesDir()
        val files = examplesDir.walkTopDown()
            .filter { it.isFile && (it.extension == "hc" || it.extension == "hotc") }
            .toList()
        assertTrue("expected to find example .hc/.hotc files under $examplesDir", files.isNotEmpty())

        val failures = StringBuilder()
        for (f in files) {
            val psiFile = PsiFileFactory.getInstance(project).createFileFromText(f.name, HCLanguage, f.readText())
            val errors = PsiTreeUtil.findChildrenOfType(psiFile, PsiErrorElement::class.java)
            if (errors.isNotEmpty()) {
                failures.append("${f.path}:\n")
                for (e in errors) {
                    val line = psiFile.viewProvider.document?.getLineNumber(e.textOffset)?.plus(1)
                    failures.append("  line $line: ${e.errorDescription} (near '${e.text.take(20)}')\n")
                }
            }
        }
        assertTrue("real programs with unexpected parse errors:\n$failures", failures.isEmpty())
    }

    // A dedicated, minimal check for interpolated strings specifically -- the real-example sweep
    // above already exercises this (`interpolation.hc`, `argv_test.hotc`, ...), but this pins the
    // exact node shape down directly rather than relying only on "no PsiErrorElement anywhere,"
    // and covers a couple of edge shapes (interpolation at the very start of the literal, a
    // nested string literal inside the embedded expression) real example files don't happen to.
    fun `test interpolated strings parse with no syntax errors and produce a real embedded expression tree`() {
        for (src in listOf(
            "fn f() { print(\"a {x + 1} b\"); }",
            "fn f() { print(\"{x}\"); }",
            "fn f() { print(\"{a}{b}\"); }",
            "fn f() { print(\"outer {\"inner\" + \"concat\"} done\"); }",
        )) {
            val psiFile = PsiFileFactory.getInstance(project).createFileFromText("t.hc", HCLanguage, src)
            val errors = PsiTreeUtil.findChildrenOfType(psiFile, PsiErrorElement::class.java)
            assertTrue("unexpected parse error(s) in `$src`: ${errors.map { it.errorDescription }}", errors.isEmpty())
            val allElements = PsiTreeUtil.findChildrenOfType(psiFile, com.intellij.psi.PsiElement::class.java)
            val hasInterpNode = allElements.any { it.node?.elementType == HCElementTypes.STRING_INTERP_EXPR }
            assertTrue("expected a STRING_INTERP_EXPR node in `$src`", hasInterpNode)
        }
    }

    // `Type.class` -- must parse as one `CLASS_LIT_EXPR`, and must NOT be confused with an
    // ordinary `.field`/`.method(...)` chain link even though `class` is a real keyword sitting
    // right where a field/method name would otherwise go.
    fun `test Type-dot-class literals parse as CLASS_LIT_EXPR`() {
        for (src in listOf(
            "fn f() { print(Foo.class); }",
            "extern class J = \"java.lang.Object\" {}\nfn f() { doThing(J.class); }",
        )) {
            val psiFile = PsiFileFactory.getInstance(project).createFileFromText("t.hc", HCLanguage, src)
            val errors = PsiTreeUtil.findChildrenOfType(psiFile, PsiErrorElement::class.java)
            assertTrue("unexpected parse error(s) in `$src`: ${errors.map { it.errorDescription }}", errors.isEmpty())
            val allElements = PsiTreeUtil.findChildrenOfType(psiFile, com.intellij.psi.PsiElement::class.java)
            val hasClassLit = allElements.any { it.node?.elementType == HCElementTypes.CLASS_LIT_EXPR }
            assertTrue("expected a CLASS_LIT_EXPR node in `$src`", hasClassLit)
        }
    }

    // `resource Name { ... }` + `world.set_resource(...)` -- the ECS resource-injection feature
    // (see `Ast.hc`'s own `Program.resources` header). Same shape as `component`, just its own
    // keyword and element type (`RESOURCE_DECL`) -- pins that down directly, same reasoning the
    // interpolated-string/`Type.class` tests above already use.
    fun `test resource declarations parse as RESOURCE_DECL`() {
        val src = "resource DeltaTime { seconds: Int }\nfn f() { var world = World::new(); world.set_resource(DeltaTime { seconds: 1 }); }"
        val psiFile = PsiFileFactory.getInstance(project).createFileFromText("t.hc", HCLanguage, src)
        val errors = PsiTreeUtil.findChildrenOfType(psiFile, PsiErrorElement::class.java)
        assertTrue("unexpected parse error(s) in `$src`: ${errors.map { it.errorDescription }}", errors.isEmpty())
        val allElements = PsiTreeUtil.findChildrenOfType(psiFile, com.intellij.psi.PsiElement::class.java)
        val hasResourceDecl = allElements.any { it.node?.elementType == HCElementTypes.RESOURCE_DECL }
        assertTrue("expected a RESOURCE_DECL node in `$src`", hasResourceDecl)
    }

    // **Added 2026-09-23** -- `unit`/`typestate`/`event`/`handle`/`parallel`/`sequence` were all
    // real reserved keywords in the actual compiler that this plugin's lexer/parser never learned
    // (see `HCTokenTypes.KEYWORDS`'s own header) -- every real use of any of them previously
    // tokenized as a plain `IDENT` and produced real, false parse-error squiggles. One test per
    // shape, same reasoning `test resource declarations parse as RESOURCE_DECL` above already
    // uses, pinning down both "no parse error" and "the right element type" together.
    fun `test unit declarations parse as UNIT_DECL`() {
        assertParsesAs("unit Meters(Float);\n", HCElementTypes.UNIT_DECL)
    }

    fun `test typestate declarations parse as TYPESTATE_DECL with nested STATE_DECL`() {
        val src = "typestate Door {\n    state Open { }\n    state Closed { }\n    impl Open { fn close(&self) -> Closed { return Closed { }; } }\n}\n"
        assertParsesAs(src, HCElementTypes.TYPESTATE_DECL)
        assertParsesAs(src, HCElementTypes.STATE_DECL)
    }

    fun `test event and handle declarations parse as EVENT_DECL and HANDLE_DECL`() {
        val src = "event Died(cause: String);\nhandle Died(cause) { print(cause); }\n"
        assertParsesAs(src, HCElementTypes.EVENT_DECL)
        assertParsesAs(src, HCElementTypes.HANDLE_DECL)
    }

    fun `test parallel-for and sequence statements parse as PARALLEL_STMT and SEQUENCE_STMT`() {
        assertParsesAs("fn f(xs: [Int]) { parallel for x in xs { print(x); } }\n", HCElementTypes.PARALLEL_STMT)
        assertParsesAs("fn f() { sequence { print(1); } }\n", HCElementTypes.SEQUENCE_STMT)
    }

    // Array slicing, `arr[start..end]` / `arr[start..=end]` -- see the real compiler's own
    // `Ast.hc` `Expr.Slice` header. Both the exclusive and inclusive spellings, plus confirming a
    // plain `arr[i]` still parses as the ordinary `INDEX_EXPR` (not a false-positive SLICE_EXPR).
    fun `test array slicing parses as SLICE_EXPR`() {
        assertParsesAs("fn f(xs: [Int]) { let ys = xs[1..3]; }\n", HCElementTypes.SLICE_EXPR)
        assertParsesAs("fn f(xs: [Int]) { let ys = xs[1..=3]; }\n", HCElementTypes.SLICE_EXPR)
    }

    fun `test plain array indexing still parses as INDEX_EXPR, not SLICE_EXPR`() {
        val psiFile = PsiFileFactory.getInstance(project).createFileFromText("t.hotc", HCLanguage, "fn f(xs: [Int]) { let y = xs[1]; }\n")
        val errors = PsiTreeUtil.findChildrenOfType(psiFile, PsiErrorElement::class.java)
        assertTrue("unexpected parse error(s): ${errors.map { it.errorDescription }}", errors.isEmpty())
        val allElements = PsiTreeUtil.findChildrenOfType(psiFile, com.intellij.psi.PsiElement::class.java)
        assertTrue(allElements.any { it.node?.elementType == HCElementTypes.INDEX_EXPR })
        assertTrue(allElements.none { it.node?.elementType == HCElementTypes.SLICE_EXPR })
    }

    // `'a'`, `'\n'`, `'\''` -- see the real compiler's own `Lexer.hotc` `char_literal` header.
    fun `test char literals parse as LITERAL_EXPR with a CHAR token`() {
        for (src in listOf(
            "fn f() { let c = 'a'; }\n",
            "fn f() { let c = '\\n'; }\n",
            "fn f() { let c = '\\''; }\n",
        )) {
            val psiFile = PsiFileFactory.getInstance(project).createFileFromText("t.hotc", HCLanguage, src)
            val errors = PsiTreeUtil.findChildrenOfType(psiFile, PsiErrorElement::class.java)
            assertTrue("unexpected parse error(s) in `$src`: ${errors.map { it.errorDescription }}", errors.isEmpty())
            val allElements = PsiTreeUtil.findChildrenOfType(psiFile, com.intellij.psi.PsiElement::class.java)
            assertTrue("expected a CHAR token in `$src`", allElements.any { it.node?.elementType == HCTokenTypes.CHAR })
        }
    }

    // Bitwise `^` and real `<<`/`>>`/`>>>` shift -- see `HCTokenTypes.OPERATORS`'s and
    // `HCPsiParser.shift`'s own headers for why `>>`/`>>>` are assembled from consecutive `>`
    // tokens rather than being their own lexer token (nested-generic-closing ambiguity). Also
    // confirms a nested generic type annotation's closing `>>` still parses correctly as a TYPE,
    // not swallowed as a shift operator -- same sanity check the real compiler's own
    // `bitwise_shift_xor.hotc` example performs.
    fun `test bitwise xor and shift operators parse with no syntax errors`() {
        for (src in listOf(
            "fn f() { print(5 ^ 3); }\n",
            "fn f() { print(1 << 4); }\n",
            "fn f() { print(16 >> 2); }\n",
            "fn f() { print(-1 >>> 28); }\n",
            "fn f() { let bigA: Long = 1L << 40; }\n",
            // A SECOND `<<` use in the same file, immediately after the first -- the shape that
            // actually caught a real bug: `shift`'s own token-count-to-advance-by originally used
            // `op.length` (2 for the string "<<"), but `<<` is ONE real lexer token, not two, so
            // that advanced past the operand following it too, corrupting every later argument.
            "fn f() { print(1 << 4); print(3 << 2); }\n",
        )) {
            val psiFile = PsiFileFactory.getInstance(project).createFileFromText("t.hotc", HCLanguage, src)
            val errors = PsiTreeUtil.findChildrenOfType(psiFile, PsiErrorElement::class.java)
            assertTrue("unexpected parse error(s) in `$src`: ${errors.map { it.errorDescription }}", errors.isEmpty())
        }
    }

    fun `test nested generic closing shift-shaped angle brackets still parse as a type`() {
        val src = "fn f() {\n    var outer: Vec<Int> = vec_of(1);\n    var nested: Vec<Vec<Int>> = vec_of(outer);\n}\n"
        val psiFile = PsiFileFactory.getInstance(project).createFileFromText("t.hotc", HCLanguage, src)
        val errors = PsiTreeUtil.findChildrenOfType(psiFile, PsiErrorElement::class.java)
        assertTrue("unexpected parse error(s) in `$src`: ${errors.map { it.errorDescription }}", errors.isEmpty())
    }

    // `Goblin(hp)` -- positional match-pattern sugar, see `HCPsiParser.variantPattern`'s own
    // header.
    fun `test positional match patterns parse as VARIANT_PATTERN`() {
        val src = "enum Enemy {\n    Goblin { hp: Int },\n    Ghost,\n}\nfn f(e: Enemy) -> String {\n    match e {\n        Goblin(hp) => { return \"g\"; }\n        Ghost => { return \"gh\"; }\n    }\n}\n"
        assertParsesAs(src, HCElementTypes.VARIANT_PATTERN)
    }

    // `[result_expr for var_name in iter_expr]` / `... if cond` -- list comprehensions, see
    // `HCPsiParser.arrayLiteral`'s own header.
    fun `test list comprehensions parse as COMPREHENSION_EXPR`() {
        assertParsesAs("fn f(xs: [Int]) { let ys: Vec<Int> = [x * x for x in xs]; }\n", HCElementTypes.COMPREHENSION_EXPR)
        assertParsesAs("fn f(xs: [Int]) { let ys: Vec<Int> = [x for x in xs if x > 2]; }\n", HCElementTypes.COMPREHENSION_EXPR)
    }

    fun `test plain array literals and array-repeat still parse as ARRAY_LIT_EXPR, not COMPREHENSION_EXPR`() {
        val psiFile = PsiFileFactory.getInstance(project).createFileFromText("t.hotc", HCLanguage, "fn f() { let a = [1, 2, 3]; let b = [0; 5]; let c: [Int] = []; }\n")
        val errors = PsiTreeUtil.findChildrenOfType(psiFile, PsiErrorElement::class.java)
        assertTrue("unexpected parse error(s): ${errors.map { it.errorDescription }}", errors.isEmpty())
        val allElements = PsiTreeUtil.findChildrenOfType(psiFile, com.intellij.psi.PsiElement::class.java)
        assertEquals(3, allElements.count { it.node?.elementType == HCElementTypes.ARRAY_LIT_EXPR })
        assertTrue(allElements.none { it.node?.elementType == HCElementTypes.COMPREHENSION_EXPR })
    }

    private fun assertParsesAs(src: String, expected: com.intellij.psi.tree.IElementType) {
        val psiFile = PsiFileFactory.getInstance(project).createFileFromText("t.hotc", HCLanguage, src)
        val errors = PsiTreeUtil.findChildrenOfType(psiFile, PsiErrorElement::class.java)
        assertTrue("unexpected parse error(s) in `$src`: ${errors.map { it.errorDescription }}", errors.isEmpty())
        val allElements = PsiTreeUtil.findChildrenOfType(psiFile, com.intellij.psi.PsiElement::class.java)
        assertTrue("expected a $expected node in `$src`", allElements.any { it.node?.elementType == expected })
    }

    // Same sweep as the real-example-programs test above, but over `stdlib/*.hotc` -- NOT covered
    // by that one (it only walks `examples/`). This is exactly the class of file that broke and
    // went uncaught: every `extern class` with a `static NAME: Type;` FIELD (not a method) --
    // `window.hotc`'s own `GL11::GL_COLOR_BUFFER_BIT`, etc. -- tripped `externMember`'s missing
    // static-field lookahead (see that function's own header) with zero examples/ coverage to
    // catch it, since no example file happens to declare an `extern class` with a static field.
    fun `test real stdlib files parse with no syntax errors`() {
        val stdlibDir = findRepoDir("stdlib")
        val files = stdlibDir.walkTopDown()
            .filter { it.isFile && (it.extension == "hc" || it.extension == "hotc") }
            .toList()
        assertTrue("expected to find stdlib .hc/.hotc files under $stdlibDir", files.isNotEmpty())

        val failures = StringBuilder()
        for (f in files) {
            val psiFile = PsiFileFactory.getInstance(project).createFileFromText(f.name, HCLanguage, f.readText())
            val errors = PsiTreeUtil.findChildrenOfType(psiFile, PsiErrorElement::class.java)
            if (errors.isNotEmpty()) {
                failures.append("${f.path}:\n")
                for (e in errors) {
                    val line = psiFile.viewProvider.document?.getLineNumber(e.textOffset)?.plus(1)
                    failures.append("  line $line: ${e.errorDescription} (near '${e.text.take(20)}')\n")
                }
            }
        }
        assertTrue("real stdlib files with unexpected parse errors:\n$failures", failures.isEmpty())
    }

    private fun findExamplesDir(): File = findRepoDir("examples")

    private fun findRepoDir(name: String): File {
        var dir = File(".").absoluteFile
        repeat(5) {
            val candidate = File(dir, name)
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        throw IllegalStateException("could not locate the repo's $name/ directory from ${File(".").absolutePath}")
    }
}
