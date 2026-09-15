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
