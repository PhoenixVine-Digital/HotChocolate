package hc.intellij

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.inline.GenericInlineHandler
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

// Drives the real end-to-end flow via `GenericInlineHandler.invoke` -- the actual platform
// orchestrator (usage discovery via `ReferencesSearch`, already real/backed by
// `HCReferencesSearcher`; per-usage `Inliner.inlineUsage`; then `removeDefinition`), NOT a
// hand-rolled simulation. `<refactoring.inlineHandler>` -- the real EP tag, found via a constant
// pool string in `InlineHandlers.class` after `<lang.inlineHandler>` (the intuitive-but-wrong
// guess) registered nothing at all (confirmed via a throwaway debug test:
// `InlineHandlers.getInlineHandlers(HCLanguage)` came back empty).
class HCInlineHandlerTest : BasePlatformTestCase() {
    private fun letIdent(): PsiElement {
        val letStmt = PsiTreeUtil.findChildrenOfType(myFixture.file, PsiElement::class.java)
            .first { it.node?.elementType == HCElementTypes.LET_STMT }
        return declaredName(letStmt)!!
    }

    private fun inline(src: String): String {
        myFixture.configureByText("t.hotc", src)
        val ok = GenericInlineHandler.invoke(letIdent(), myFixture.editor, HCInlineHandler())
        assertTrue("expected the inline to succeed", ok)
        return myFixture.file.text
    }

    fun `test inlining a binary expression parenthesizes every usage for precedence safety`() {
        val result = inline("fn f() {\n    let x = 1 + 2;\n    print(x);\n    print(x * 3);\n}\n")
        assertFalse(result.contains("let x"))
        assertTrue(result.contains("print((1 + 2));"))
        assertTrue(result.contains("print((1 + 2) * 3);"))
    }

    fun `test inlining an atomic literal needs no parens`() {
        val result = inline("fn f() {\n    let x = 5;\n    print(x);\n}\n")
        assertTrue(result.contains("print(5);"))
        assertFalse("expected no EXTRA parens beyond print's own call parens: $result", result.contains("((5))"))
    }

    fun `test inlining a call result needs no parens`() {
        val result = inline("fn foo() -> Int { return 1; }\nfn f() {\n    let x = foo();\n    print(x);\n}\n")
        assertTrue(result.contains("print(foo());"))
    }

    fun `test inlining a struct literal used as a field-access receiver`() {
        val src = "struct Point { x: Int, y: Int, }\nfn f() {\n    let p = Point { x: 1, y: 2, };\n    print(p.x);\n}\n"
        val result = inline(src)
        assertTrue(result.contains("print(Point { x: 1, y: 2, }.x);"))
    }

    fun `test inlining with a single usage removes the let and replaces the one usage`() {
        val result = inline("fn f() {\n    let x = 42;\n    print(x);\n}\n")
        assertEquals("fn f() {\n    print(42);\n}\n", result)
    }

    fun `test canInlineElement accepts a let-bound local and rejects other declarations`() {
        val handler = HCInlineHandler()
        myFixture.configureByText("t.hotc", "fn add(a: Int) -> Int { return a; }\nfn f() {\n    let x = 1;\n    print(x);\n}\n")
        val letIdent = letIdent()
        assertTrue(handler.canInlineElement(letIdent))

        val paramIdent = PsiTreeUtil.findChildrenOfType(myFixture.file, PsiElement::class.java)
            .first { it.node?.elementType == HCTokenTypes.IDENT && it.text == "a" && it.parent?.node?.elementType == HCElementTypes.PARAM }
        assertFalse("a function parameter is not (yet) inlinable", handler.canInlineElement(paramIdent))

        val fnIdent = PsiTreeUtil.findChildrenOfType(myFixture.file, PsiElement::class.java)
            .first { it.node?.elementType == HCTokenTypes.IDENT && it.text == "add" }
        assertFalse("a function declaration is not inlinable", handler.canInlineElement(fnIdent))
    }

    // Real-example sweep: proves `canInlineElement`/`prepareInlineElement`/`createInliner` never
    // crash for every real `let` in every real, already-working example file.
    fun `test real example programs do not crash inline computation`() {
        val examplesDir = findExamplesDir()
        val files = examplesDir.walkTopDown().filter { it.isFile && (it.extension == "hc" || it.extension == "hotc") }.toList()
        assertTrue("expected to find example .hc/.hotc files under $examplesDir", files.isNotEmpty())
        val handler = HCInlineHandler()
        for (f in files) {
            val text = f.readText()
            if (text.isEmpty()) continue
            myFixture.configureByText(f.name, text)
            for (letStmt in PsiTreeUtil.findChildrenOfType(myFixture.file, PsiElement::class.java)) {
                if (letStmt.node?.elementType != HCElementTypes.LET_STMT) continue
                val ident = declaredName(letStmt) ?: continue
                if (!handler.canInlineElement(ident)) continue
                val settings = handler.prepareInlineElement(ident, myFixture.editor, false) ?: continue
                handler.createInliner(ident, settings)
            }
        }
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
