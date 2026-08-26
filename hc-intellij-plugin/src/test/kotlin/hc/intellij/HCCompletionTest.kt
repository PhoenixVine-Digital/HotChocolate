package hc.intellij

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

// Drives the real completion pipeline (`myFixture.completeBasic()`) -- same "prove the
// registration is actually wired up" reasoning as `HCReferencesTest`'s own header. `<caret>` marks
// where completion is invoked.
class HCCompletionTest : BasePlatformTestCase() {
    private fun complete(src: String): List<String> {
        myFixture.configureByText("t.hotc", src)
        myFixture.completeBasic()
        return myFixture.lookupElementStrings ?: emptyList()
    }

    fun `test general completion suggests keywords, locals, and top-level names`() {
        val src = "struct Point { x: Int, y: Int, }\nfn f() {\n    let p = 1;\n    <caret>\n}\n"
        val items = complete(src)
        assertTrue("expected keyword 'if'", items.contains("if"))
        assertTrue("expected local 'p'", items.contains("p"))
        assertTrue("expected struct name 'Point'", items.contains("Point"))
    }

    fun `test general completion includes top-level functions and enum variants`() {
        val src = "enum Status {\n    Active,\n    Inactive,\n}\nfn helper() -> Int { return 1; }\nfn f() {\n    <caret>\n}\n"
        val items = complete(src)
        assertTrue("expected fn 'helper'", items.contains("helper"))
        assertTrue("expected enum variant 'Active'", items.contains("Active"))
    }

    fun `test type position completion suggests primitives and struct-enum-interface names`() {
        val src = "struct Point { x: Int, y: Int, }\nenum Status { Active, }\ninterface Describable { fn d(&self) -> String; }\n" +
            "fn f(p: <caret>) {}\n"
        val items = complete(src)
        assertTrue("expected primitive 'Int'", items.contains("Int"))
        assertTrue("expected struct 'Point'", items.contains("Point"))
        assertTrue("expected enum 'Status'", items.contains("Status"))
        assertTrue("expected interface 'Describable'", items.contains("Describable"))
    }

    fun `test type position completion does not suggest local variable names`() {
        val src = "struct Point { x: Int, y: Int, }\nfn f() {\n    let myLocal = 1;\n    let q: <caret>\n}\n"
        val items = complete(src)
        assertFalse("did not expect local 'myLocal' in a type position", items.contains("myLocal"))
    }

    fun `test member completion on a typed receiver suggests exactly its own fields and methods`() {
        val src = "struct Point { x: Int, y: Int, }\nimpl Point {\nfn go(&self) -> Int { return self.x; }\n}\n" +
            "struct Other { z: Int, }\nfn f(p: Point) {\n    p.<caret>\n}\n"
        val items = complete(src)
        assertTrue(items.contains("x"))
        assertTrue(items.contains("y"))
        assertTrue(items.contains("go"))
        assertFalse("unrelated struct's field should not be suggested", items.contains("z"))
    }

    fun `test member completion on self inside a method suggests the enclosing struct's own members`() {
        val src = "struct Point { x: Int, y: Int, }\nimpl Point {\nfn dump(&self) -> Int {\n    return self.<caret>\n}\n}\n"
        val items = complete(src)
        assertTrue(items.contains("x"))
        assertTrue(items.contains("y"))
        assertTrue(items.contains("dump"))
    }

    fun `test member completion with unknown receiver type falls back to every field and method in scope`() {
        val src = "struct A { p: Int, }\nimpl A {\nfn onlyA(&self) -> Int { return 1; }\n}\n" +
            "struct B { q: Int, }\nfn makeSomething() -> Int { return 1; }\nfn f() {\n    let x = makeSomething();\n    x.<caret>\n}\n"
        val items = complete(src)
        assertTrue("expected fallback to include A's field", items.contains("p"))
        assertTrue("expected fallback to include B's field", items.contains("q"))
        assertTrue("expected fallback to include A's method", items.contains("onlyA"))
    }

    fun `test general completion sees top-level names from same-directory sibling files`() {
        // `addFileToProject` does NOT interpret `<caret>` markers the way `configureByText` does
        // (and `configureByText` gives each file an isolated, sibling-less directory -- see
        // `HCAnnotatorTest`'s own sweep header for why that matters here) -- the caret is placed
        // manually instead.
        myFixture.addFileToProject("dir/util.hotc", "fn helper() -> Int { return 1; }\n")
        val mainContent = "fn f() {\n    HERE\n}\n"
        val mainFile = myFixture.addFileToProject("dir/main.hotc", mainContent)
        myFixture.configureFromExistingVirtualFile(mainFile.virtualFile)
        myFixture.editor.caretModel.moveToOffset(mainContent.indexOf("HERE"))
        myFixture.completeBasic()
        val items = myFixture.lookupElementStrings ?: emptyList()
        assertTrue("expected sibling file's fn 'helper'", items.contains("helper"))
    }

    // Real-example sweep: proves invoking completion at every real position in every real,
    // already-working example file doesn't crash -- same reasoning as every other sweep in this
    // plugin. Doesn't assert on the RESULTS (too varied to usefully assert on), just that
    // `completeBasic()` never throws.
    fun `test real example programs do not crash completion`() {
        val examplesDir = findExamplesDir()
        val files = examplesDir.walkTopDown()
            .filter { it.isFile && (it.extension == "hc" || it.extension == "hotc") }
            .toList()
        assertTrue("expected to find example .hc/.hotc files under $examplesDir", files.isNotEmpty())

        val byDir = files.groupBy { it.parentFile }
        for ((dirIndex, group) in byDir.values.withIndex()) {
            val added = group.associateWith { f -> myFixture.addFileToProject("dir$dirIndex/${f.name}", f.readText()) }
            for ((f, psiFile) in added) {
                val text = f.readText()
                if (text.isEmpty()) continue
                myFixture.configureFromExistingVirtualFile(psiFile.virtualFile)
                // A handful of representative offsets rather than every single one -- this sweep
                // exists to catch a CRASH, not to exhaustively enumerate every completion result.
                val offsets = listOf(text.length / 4, text.length / 2, (text.length * 3) / 4).filter { it in 1 until text.length }
                for (offset in offsets) {
                    myFixture.editor.caretModel.moveToOffset(offset)
                    myFixture.completeBasic()
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
