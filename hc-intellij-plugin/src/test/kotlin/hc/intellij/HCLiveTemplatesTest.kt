package hc.intellij

import com.intellij.codeInsight.template.TemplateManager
import com.intellij.codeInsight.template.impl.TemplateSettings
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

// Drives each registered template through the real `TemplateManager`/`TemplateSettings` (the
// actual platform machinery, not a hand-rolled simulation) -- `startTemplate` already substitutes
// every variable's own `defaultValue` immediately, so the resulting document text can be checked
// (and reparsed for validity) right after, with no further simulated keystrokes needed.
class HCLiveTemplatesTest : BasePlatformTestCase() {
    // `fn`/`fnr`/`struct` are top-level DECLARATIONS -- nesting one inside a function body would
    // be a real, separate parse error (declarations aren't statements) that has nothing to do
    // with whether the template itself is well-formed, so those expand directly at file scope;
    // every other template is itself a STATEMENT, so those expand inside a function body, the
    // context they're actually meant to be used in.
    private fun expand(templateName: String, atTopLevel: Boolean = false): String {
        val surroundingText = if (atTopLevel) "<caret>\n" else "fn f() {\n    <caret>\n}\n"
        myFixture.configureByText("t.hotc", surroundingText)
        val template = TemplateSettings.getInstance().getTemplate(templateName, "HotChocolate")
            ?: throw AssertionError("no registered HotChocolate template named '$templateName'")
        WriteCommandAction.runWriteCommandAction(project) {
            TemplateManager.getInstance(project).startTemplate(myFixture.editor, template)
        }
        return myFixture.editor.document.text
    }

    private fun assertParsesCleanly(text: String) {
        myFixture.configureByText("check.hotc", text)
        val errors = PsiTreeUtil.findChildrenOfType(myFixture.file, PsiErrorElement::class.java)
        assertTrue("parse errors after expanding template: ${errors.map { it.errorDescription }}\n$text", errors.isEmpty())
    }

    fun `test fn template expands to a real function declaration`() {
        val result = expand("fn", atTopLevel = true)
        assertTrue(result.contains("fn name() {"))
        assertParsesCleanly(result)
    }

    fun `test fnr template expands with a return type`() {
        val result = expand("fnr", atTopLevel = true)
        assertTrue(result.contains("fn name() -> Int {"))
        assertParsesCleanly(result)
    }

    fun `test struct template expands to a real struct declaration`() {
        val result = expand("struct", atTopLevel = true)
        assertTrue(result.contains("struct Name {"))
        assertParsesCleanly(result)
    }

    fun `test if template expands to a real if statement`() {
        val result = expand("if")
        assertTrue(result.contains("if true {"))
        assertParsesCleanly(result)
    }

    fun `test ifelse template expands to a real if-else statement`() {
        val result = expand("ifelse")
        assertTrue(result.contains("if true {"))
        assertTrue(result.contains("} else {"))
        assertParsesCleanly(result)
    }

    fun `test while template expands to a real while loop`() {
        val result = expand("while")
        assertTrue(result.contains("while true {"))
        assertParsesCleanly(result)
    }

    fun `test for template expands to a real for loop`() {
        val result = expand("for")
        assertTrue(result.contains("for item in (items) {"))
        assertParsesCleanly(result)
    }

    fun `test match template expands to a real match statement`() {
        val result = expand("match")
        assertTrue(result.contains("match (value) {"))
        assertParsesCleanly(result)
    }

    fun `test let template expands to a real let binding`() {
        val result = expand("let")
        assertTrue(result.contains("let name = 0;"))
        assertParsesCleanly(result)
    }

    fun `test print template expands to a real print call`() {
        val result = expand("print")
        assertTrue(result.contains("print();"))
        assertParsesCleanly(result)
    }
}
