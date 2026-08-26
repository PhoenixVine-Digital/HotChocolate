package hc.intellij

import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

class HCStructureViewTest : BasePlatformTestCase() {
    private fun outline(src: String): List<String> {
        myFixture.configureByText("t.hotc", src)
        val builder = HCStructureViewFactory().getStructureViewBuilder(myFixture.file) as TreeBasedStructureViewBuilder
        val model = builder.createStructureViewModel(null)
        val out = mutableListOf<String>()
        fun dump(el: StructureViewTreeElement, indent: String) {
            out += indent + el.presentation.presentableText
            for (c in el.children) dump(c as StructureViewTreeElement, "$indent  ")
        }
        dump(model.root, "")
        // Drop the file-name root itself and the one level of indent every top-level member gets
        // as its child.
        return out.drop(1).map { it.removePrefix("  ") }
    }

    fun `test outline lists top-level declarations`() {
        val src = "struct Point { x: Int, y: Int, }\nfn f() -> Int { return 1; }\nenum Status { Active, }\n"
        val outline = outline(src)
        assertEquals(listOf("Point", "  x: Int", "  y: Int", "fn f()", "Status", "  Active"), outline)
    }

    fun `test outline nests impl methods under the impl block`() {
        val src = "struct S { v: Int, }\nimpl S {\nfn go(&self) -> Int { return self.v; }\nfn other(&self) -> Int { return 1; }\n}\n"
        val outline = outline(src)
        assertEquals(listOf("S", "  v: Int", "impl S", "  fn go()", "  fn other()"), outline)
    }

    fun `test outline labels an interface impl with its interface and target`() {
        val src = "interface Describable {\n    fn describe(&self) -> String;\n}\nstruct S {}\nimpl Describable for S {\nfn describe(&self) -> String { return \"s\"; }\n}\n"
        val outline = outline(src)
        assertTrue(outline.any { it.trim() == "impl Describable for S" })
    }

    fun `test outline shows enum variants and interface method signatures as children`() {
        val src = "enum Status {\n    Active,\n    Inactive,\n}\ninterface Describable {\n    fn describe(&self) -> String;\n}\n"
        val outline = outline(src)
        assertEquals(listOf("Status", "  Active", "  Inactive", "Describable", "  fn describe()"), outline)
    }

    // Real-example sweep: proves building a structure view for every real, already-working
    // example file never crashes.
    fun `test real example programs do not crash structure view construction`() {
        val examplesDir = findExamplesDir()
        val files = examplesDir.walkTopDown().filter { it.isFile && (it.extension == "hc" || it.extension == "hotc") }.toList()
        assertTrue("expected to find example .hc/.hotc files under $examplesDir", files.isNotEmpty())
        for (f in files) {
            val text = f.readText()
            if (text.isEmpty()) continue
            outline(text)
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
