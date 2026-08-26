package hc.intellij

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

class HCIntroduceVariableHandlerTest : BasePlatformTestCase() {
    private val handler = HCIntroduceVariableHandler()

    private fun introduceAtSelection(src: String, needle: String): String {
        myFixture.configureByText("t.hotc", src)
        val start = src.indexOf(needle)
        assertTrue("expected to find '$needle' in source", start >= 0)
        myFixture.editor.selectionModel.setSelection(start, start + needle.length)
        handler.invoke(project, myFixture.editor, myFixture.file, DataContext.EMPTY_CONTEXT)
        return myFixture.file.text
    }

    private fun introduceAtCaret(src: String, offset: Int): String {
        myFixture.configureByText("t.hotc", src)
        myFixture.editor.caretModel.moveToOffset(offset)
        handler.invoke(project, myFixture.editor, myFixture.file, DataContext.EMPTY_CONTEXT)
        return myFixture.file.text
    }

    fun `test extracting a selected binary expression`() {
        val result = introduceAtSelection("fn f() {\n    print(1 + 2);\n}\n", "1 + 2")
        assertTrue(result.contains("let extracted = 1 + 2;"))
        assertTrue(result.contains("print(extracted);"))
    }

    fun `test extracting at the caret with no selection extracts the whole call, not just the callee`() {
        val src = "fn f() {\n    print(foo());\n}\nfn foo() -> Int { return 1; }\n"
        val result = introduceAtCaret(src, src.indexOf("foo()") + 2)
        assertTrue("expected the whole call extracted: $result", result.contains("let extracted = foo();"))
        assertTrue(result.contains("print(extracted);"))
        assertFalse("must not call the new variable as if it were still a function", result.contains("extracted()"))
    }

    fun `test extracting a method call preserves the receiver`() {
        val src = "struct S { v: Int, }\nimpl S {\nfn go(&self) -> Int { return self.v; }\n}\nfn f(s: S) {\n    print(s.go());\n}\n"
        val result = introduceAtSelection(src, "s.go()")
        assertTrue(result.contains("let extracted = s.go();"))
        assertTrue(result.contains("print(extracted);"))
    }

    fun `test the inserted declaration is placed before the enclosing statement, correctly indented`() {
        val src = "fn f() {\n    if true {\n        print(1 + 2);\n    }\n}\n"
        val result = introduceAtSelection(src, "1 + 2")
        assertTrue("expected the let to be indented to match the call statement: $result", result.contains("        let extracted = 1 + 2;\n        print(extracted);"))
    }

    fun `test extracted expression result is syntactically valid`() {
        val src = "fn f() {\n    print(1 + 2 * 3);\n}\n"
        val result = introduceAtSelection(src, "1 + 2 * 3")
        myFixture.configureByText("t2.hotc", result)
        val parseErrors = PsiTreeUtil.findChildrenOfType(myFixture.file, PsiErrorElement::class.java)
        assertTrue("parse errors after introducing a variable: ${parseErrors.map { it.errorDescription }}", parseErrors.isEmpty())
    }

    fun `test extracting a struct literal`() {
        val src = "struct Point { x: Int, y: Int, }\nfn f() {\n    print(Point { x: 1, y: 2, }.x);\n}\n"
        val result = introduceAtSelection(src, "Point { x: 1, y: 2, }")
        assertTrue(result.contains("let extracted = Point { x: 1, y: 2, };"))
        assertTrue(result.contains("print(extracted.x);"))
    }

    // Real-example sweep: proves invoking the handler at a range of real positions in every real,
    // already-working example file never crashes.
    fun `test real example programs do not crash introduce variable`() {
        val examplesDir = findExamplesDir()
        val files = examplesDir.walkTopDown().filter { it.isFile && (it.extension == "hc" || it.extension == "hotc") }.toList()
        assertTrue("expected to find example .hc/.hotc files under $examplesDir", files.isNotEmpty())
        for (f in files) {
            val text = f.readText()
            if (text.isEmpty()) continue
            myFixture.configureByText(f.name, text)
            val offsets = listOf(text.length / 4, text.length / 2, (text.length * 3) / 4).filter { it in 1 until text.length }
            for (offset in offsets) {
                myFixture.editor.caretModel.moveToOffset(offset)
                myFixture.editor.selectionModel.removeSelection()
                try {
                    handler.invoke(project, myFixture.editor, myFixture.file, DataContext.EMPTY_CONTEXT)
                } finally {
                    // Reset back to the original text so later offsets in the same file stay valid.
                    myFixture.configureByText(f.name, text)
                }
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
