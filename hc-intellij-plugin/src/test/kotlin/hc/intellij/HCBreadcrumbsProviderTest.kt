package hc.intellij

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

class HCBreadcrumbsProviderTest : BasePlatformTestCase() {
    private val provider = HCBreadcrumbsProvider()

    private fun crumbsAt(src: String): List<String> {
        myFixture.configureByText("t.hotc", src)
        val offset = src.indexOf("<caret>")
        assertTrue("expected a <caret> marker", offset >= 0)
        var element: com.intellij.psi.PsiElement? = myFixture.file.findElementAt(offset)
        val out = mutableListOf<String>()
        while (element != null) {
            if (provider.acceptElement(element)) out += provider.getElementInfo(element)
            element = element.parent
        }
        return out
    }

    fun `test breadcrumbs for a plain top-level function`() {
        val src = "fn add(a: Int, b: Int) -> Int {\n    return <caret>a + b;\n}\n"
        assertEquals(listOf("fn add"), crumbsAt(src))
    }

    fun `test breadcrumbs for a struct declaration`() {
        val src = "struct <caret>Point { x: Int, y: Int, }\n"
        assertEquals(listOf("struct Point"), crumbsAt(src))
    }

    fun `test breadcrumbs for a method nested inside an impl block`() {
        val src = "struct S { v: Int, }\nimpl S {\nfn go(&self) -> Int {\n    return <caret>self.v;\n}\n}\n"
        assertEquals(listOf("fn go", "impl S"), crumbsAt(src))
    }

    fun `test breadcrumbs for control flow nested inside a function`() {
        val src = "fn f() {\n    if true {\n        while true {\n            <caret>break;\n        }\n    }\n}\n"
        assertEquals(listOf("while", "if", "fn f"), crumbsAt(src))
    }

    fun `test breadcrumbs for a match arm inside a match inside a function`() {
        val src = "enum Status {\n    Active,\n}\nfn f(s: Status) {\n    match s {\n        Status::Active => {\n            <caret>print(\"x\");\n        }\n    }\n}\n"
        assertEquals(listOf("=>", "match", "fn f"), crumbsAt(src))
    }

    // Real-example sweep: proves walking breadcrumbs from every real position in every real
    // example file never crashes.
    fun `test real example programs do not crash breadcrumb computation`() {
        val examplesDir = findExamplesDir()
        val files = examplesDir.walkTopDown().filter { it.isFile && (it.extension == "hc" || it.extension == "hotc") }.toList()
        assertTrue("expected to find example .hc/.hotc files under $examplesDir", files.isNotEmpty())
        for (f in files) {
            val text = f.readText()
            if (text.isEmpty()) continue
            myFixture.configureByText(f.name, text)
            var element: com.intellij.psi.PsiElement? = myFixture.file.findElementAt(text.length / 2)
            while (element != null) {
                if (provider.acceptElement(element)) provider.getElementInfo(element)
                element = element.parent
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
