package hc.intellij

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

// `TargetElementUtil.findTargetElement` backs Quick Definition (Ctrl+Shift+I), among other
// platform features. Confirmed via a throwaway debug test that it resolved correctly from a
// USAGE site already (via `HCReferenceContributor`) but came back `null` from a DECLARATION site
// -- `HCTargetElementEvaluator`'s own header has the full story. This test locks that fix in.
class HCTargetElementEvaluatorTest : BasePlatformTestCase() {
    private fun targetAt(src: String): String? {
        myFixture.configureByText("t.hotc", src)
        val offset = src.indexOf("<caret>")
        assertTrue("expected a <caret> marker", offset >= 0)
        myFixture.editor.caretModel.moveToOffset(offset)
        val flags = TargetElementUtil.getInstance().allAccepted
        return TargetElementUtil.findTargetElement(myFixture.editor, flags)?.text
    }

    fun `test resolves from a top-level function's own declaration site`() {
        val src = "fn <caret>add(a: Int, b: Int) -> Int { return a + b; }\n"
        assertEquals("add", targetAt(src))
    }

    fun `test resolves from a param's own declaration site`() {
        val src = "fn add(<caret>a: Int, b: Int) -> Int { return a + b; }\n"
        assertEquals("a", targetAt(src))
    }

    fun `test resolves from a struct's own declaration site`() {
        val src = "struct <caret>Point { x: Int, y: Int, }\n"
        assertEquals("Point", targetAt(src))
    }

    fun `test resolves from a struct field's own declaration site`() {
        val src = "struct Point { <caret>x: Int, y: Int, }\n"
        assertEquals("x", targetAt(src))
    }

    fun `test resolves from an enum variant's own declaration site`() {
        val src = "enum Status {\n    <caret>Active,\n}\n"
        assertEquals("Active", targetAt(src))
    }

    fun `test still resolves from a usage site`() {
        val src = "fn add(a: Int, b: Int) -> Int { return a + b; }\nfn f() {\n    <caret>add(1, 2);\n}\n"
        assertEquals("add", targetAt(src))
    }

    fun `test a struct literal's own variant name is not falsely treated as a declaration anchor`() {
        // The pattern-bind-name / leading-variant-name distinction `isDeclarationAnchor` makes for
        // `VARIANT_PATTERN` also matters here -- a `STRUCT_LIT_EXPR`'s own leading ident is a
        // REFERENCE to the struct, not a declaration, and should still resolve via that reference.
        val src = "struct Point { x: Int, y: Int, }\nfn f() {\n    let p = <caret>Point { x: 1, y: 2, };\n}\n"
        assertEquals("Point", targetAt(src))
    }

    // Real-example sweep: proves target-element resolution never crashes from any real position
    // in any real, already-working example file.
    fun `test real example programs do not crash target element resolution`() {
        val examplesDir = findExamplesDir()
        val files = examplesDir.walkTopDown().filter { it.isFile && (it.extension == "hc" || it.extension == "hotc") }.toList()
        assertTrue("expected to find example .hc/.hotc files under $examplesDir", files.isNotEmpty())
        val flags = TargetElementUtil.getInstance().allAccepted
        for (f in files) {
            val text = f.readText()
            if (text.isEmpty()) continue
            myFixture.configureByText(f.name, text)
            val offsets = listOf(text.length / 4, text.length / 2, (text.length * 3) / 4).filter { it in 1 until text.length }
            for (offset in offsets) {
                myFixture.editor.caretModel.moveToOffset(offset)
                TargetElementUtil.findTargetElement(myFixture.editor, flags)
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
