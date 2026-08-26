package hc.intellij

import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class HCSurroundDescriptorTest : BasePlatformTestCase() {
    private val descriptor = HCSurroundDescriptor()

    private fun surround(src: String, needle: String, surrounder: com.intellij.lang.surroundWith.Surrounder): String {
        myFixture.configureByText("t.hotc", src)
        val start = src.indexOf(needle)
        assertTrue("expected to find '$needle' in source", start >= 0)
        val end = start + needle.length
        myFixture.editor.selectionModel.setSelection(start, end)
        val elements = descriptor.getElementsToSurround(myFixture.file, start, end)
        assertTrue("expected at least one element to surround in: $src", elements.isNotEmpty())
        surrounder.surroundElements(project, myFixture.editor, elements)
        return myFixture.file.text
    }

    private fun assertParsesCleanly(text: String) {
        myFixture.configureByText("check.hotc", text)
        val errors = PsiTreeUtil.findChildrenOfType(myFixture.file, PsiErrorElement::class.java)
        assertTrue("parse errors after surround: ${errors.map { it.errorDescription }}\n$text", errors.isEmpty())
    }

    fun `test surrounding a statement with if`() {
        val src = "fn f() {\n    print(1);\n}\n"
        val result = surround(src, "print(1);", HCIfSurrounder())
        assertTrue("expected an if wrapping the statement: $result", result.contains("if true {"))
        assertTrue(result.contains("print(1);"))
        assertParsesCleanly(result)
    }

    fun `test surrounding a statement with while`() {
        val src = "fn f() {\n    print(1);\n}\n"
        val result = surround(src, "print(1);", HCWhileSurrounder())
        assertTrue("expected a while wrapping the statement: $result", result.contains("while true {"))
        assertParsesCleanly(result)
    }

    fun `test surrounding a statement with a bare block`() {
        val src = "fn f() {\n    print(1);\n}\n"
        val result = surround(src, "print(1);", HCBlockSurrounder())
        val openBraceCount = result.count { it == '{' }
        assertEquals("expected an extra nested block wrapping print(1): $result", 2, openBraceCount)
        assertTrue(result.contains("print(1);"))
        assertParsesCleanly(result)
    }

    fun `test surrounding a statement with try-catch`() {
        val src = "fn f() {\n    print(1);\n}\n"
        val result = surround(src, "print(1);", HCTrySurrounder())
        assertTrue(result.contains("try {"))
        assertTrue(result.contains("catch (e: Exception) {"))
        assertParsesCleanly(result)
    }

    fun `test surrounding multiple consecutive statements`() {
        val src = "fn f() {\n    print(1);\n    print(2);\n}\n"
        val result = surround(src, "print(1);\n    print(2);", HCIfSurrounder())
        assertTrue(result.contains("print(1);"))
        assertTrue(result.contains("print(2);"))
        assertParsesCleanly(result)
    }

    fun `test real example programs do not crash element collection`() {
        val examplesDir = findExamplesDir()
        val files = examplesDir.walkTopDown().filter { it.isFile && (it.extension == "hc" || it.extension == "hotc") }.toList()
        assertTrue("expected to find example .hc/.hotc files under $examplesDir", files.isNotEmpty())
        for (f in files) {
            val text = f.readText()
            if (text.isEmpty()) continue
            myFixture.configureByText(f.name, text)
            val offsets = listOf(text.length / 4, text.length / 2, (text.length * 3) / 4).filter { it in 1 until text.length }
            for (offset in offsets) {
                descriptor.getElementsToSurround(myFixture.file, offset, offset)
            }
        }
    }

    private fun findExamplesDir(): java.io.File {
        var dir = java.io.File(".").absoluteFile
        repeat(5) {
            val candidate = java.io.File(dir, "examples")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        throw IllegalStateException("could not locate the repo's examples/ directory from ${java.io.File(".").absolutePath}")
    }
}
