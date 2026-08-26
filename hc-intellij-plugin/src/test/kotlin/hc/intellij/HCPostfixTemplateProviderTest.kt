package hc.intellij

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

// Drives each postfix template's real `isApplicable`/`expand` directly (matched by its own
// `example` string, set explicitly by this file's own `HCPostfixTemplateProvider` and so free of
// any assumption about how the platform derives a template's `key`/`id` internally) rather than
// simulating "type `expr.if` then press Tab" -- that keystroke-level detection is the PLATFORM's
// own, already-tested machinery; what's actually being verified here is this plugin's own
// `getTemplateString`/`HCExprAncestorsSelector` logic. Each source string places `<caret>` right
// after the target expression, with no trailing `;` yet -- exactly the mid-edit state a real user
// is in right before typing `.templatename` (the template's own text supplies the semicolon).
class HCPostfixTemplateProviderTest : BasePlatformTestCase() {
    private val provider = HCPostfixTemplateProvider()

    private fun templateByExample(example: String) =
        provider.templates.first { it.example == example }

    private fun expand(example: String, src: String): String {
        myFixture.configureByText("t.hotc", src)
        val offset = myFixture.editor.caretModel.offset
        val context = myFixture.file.findElementAt(offset - 1)!!
        val template = templateByExample(example)
        assertTrue(
            "expected '${template.example}' to be applicable at the caret in: $src",
            template.isApplicable(context, myFixture.editor.document, offset),
        )
        WriteCommandAction.runWriteCommandAction(project) { template.expand(context, myFixture.editor) }
        return myFixture.editor.document.text
    }

    private fun assertParsesCleanly(text: String) {
        myFixture.configureByText("check.hotc", text)
        val errors = PsiTreeUtil.findChildrenOfType(myFixture.file, PsiErrorElement::class.java)
        assertTrue("parse errors after postfix expansion: ${errors.map { it.errorDescription }}\n$text", errors.isEmpty())
    }

    fun `test dot-if wraps a boolean expression as an if condition`() {
        val result = expand("if (expr) {}", "fn f(ready: Bool) {\n    ready<caret>\n}\n")
        assertTrue(result.contains("if (ready) {"))
        assertParsesCleanly(result)
    }

    fun `test dot-while wraps an expression as a while condition`() {
        val result = expand("while (expr) {}", "fn f(ready: Bool) {\n    ready<caret>\n}\n")
        assertTrue(result.contains("while (ready) {"))
        assertParsesCleanly(result)
    }

    fun `test dot-match wraps an expression as a match scrutinee`() {
        val result = expand("match (expr) {}", "fn f(x: Int) {\n    x<caret>\n}\n")
        assertTrue(result.contains("match (x) {"))
        assertParsesCleanly(result)
    }

    fun `test dot-not negates a boolean expression`() {
        // `.not` only substitutes the expression itself (no statement-level punctuation of its
        // own -- matching `!expr` being usable mid-expression, not just as a whole statement), so
        // this source already carries its own trailing `;`, the same as it would for a real user
        // negating an expression that was already a complete statement.
        val result = expand("!expr", "fn f(ready: Bool) {\n    ready<caret>;\n}\n")
        assertTrue(result.contains("!(ready);"))
        assertParsesCleanly(result)
    }

    fun `test dot-let binds an expression to a new local`() {
        val result = expand("let name = expr;", "fn f(x: Int) {\n    x<caret>\n}\n")
        val withName = result.replaceFirst("let  = x;", "let name = x;")
        assertTrue("expected a let binding with x as the initializer: $result", withName.contains("let name = x;"))
        assertParsesCleanly(withName)
    }

    fun `test dot-return wraps an expression in a return statement`() {
        val result = expand("return expr;", "fn f(x: Int) -> Int {\n    x<caret>\n}\n")
        assertTrue(result.contains("return x;"))
        assertParsesCleanly(result)
    }

    fun `test dot-print wraps an expression in a print call`() {
        val result = expand("print(expr);", "fn f(x: Int) {\n    x<caret>\n}\n")
        assertTrue(result.contains("print(x);"))
        assertParsesCleanly(result)
    }

    fun `test both the narrow and the widest enclosing expression are offered for a binary expression`() {
        // The framework defaults to the FIRST (narrowest) candidate absent an interactive chooser
        // -- matching real postfix-template UX elsewhere (e.g. Java's own `.if`) -- so `b` alone
        // is what actually gets wrapped here; what this test really verifies is that
        // `HCExprAncestorsSelector` climbs ancestors far enough to ALSO offer the wider `a + b`,
        // not just the immediate leaf.
        myFixture.configureByText("t.hotc", "fn f(a: Int, b: Int) {\n    a + b<caret>\n}\n")
        val offset = myFixture.editor.caretModel.offset
        val context = myFixture.file.findElementAt(offset - 1)!!
        val expressions = HCExprAncestorsSelector.getExpressions(context, myFixture.editor.document, offset)
        assertTrue("expected both 'b' and 'a + b' offered as candidates: ${expressions.map { it.text }}", expressions.any { it.text == "a + b" })

        val result = expand("print(expr);", "fn f(a: Int, b: Int) {\n    a + b<caret>\n}\n")
        assertTrue("expected the narrowest expression (b) wrapped by default: $result", result.contains("a + print(b);"))
        assertParsesCleanly(result)
    }
}
