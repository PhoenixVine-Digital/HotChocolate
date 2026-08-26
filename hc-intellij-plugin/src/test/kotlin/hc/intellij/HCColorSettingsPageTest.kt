package hc.intellij

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class HCColorSettingsPageTest : BasePlatformTestCase() {
    private val page = HCColorSettingsPage()

    fun `test attribute descriptors are non-empty and each has a real display name`() {
        val descriptors = page.attributeDescriptors
        assertTrue(descriptors.isNotEmpty())
        for (d in descriptors) assertTrue("descriptor with a blank display name", d.displayName.isNotBlank())
    }

    fun `test demo text is real syntactically-valid HotChocolate with no parse errors`() {
        myFixture.configureByText("demo.hotc", page.demoText)
        val errors = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(myFixture.file, com.intellij.psi.PsiErrorElement::class.java)
        assertTrue("demo text has parse errors: ${errors.map { it.errorDescription }}", errors.isEmpty())
    }

    fun `test demo text exercises every attribute key at least once`() {
        myFixture.configureByText("demo.hotc", page.demoText)
        val highlighter = page.highlighter
        val usedKeys = mutableSetOf<com.intellij.openapi.editor.colors.TextAttributesKey>()
        val lexer = highlighter.highlightingLexer
        lexer.start(page.demoText)
        while (lexer.tokenType != null) {
            usedKeys += highlighter.getTokenHighlights(lexer.tokenType)
            lexer.advance()
        }
        for (d in page.attributeDescriptors) {
            assertTrue("demo text never uses the '${d.displayName}' key", d.key in usedKeys)
        }
    }
}
