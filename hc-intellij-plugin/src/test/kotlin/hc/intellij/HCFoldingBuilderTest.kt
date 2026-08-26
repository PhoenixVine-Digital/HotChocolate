package hc.intellij

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

// Drives `HCFoldingBuilder.buildFoldRegions` directly against the PSI file/document rather than
// through the editor's own `FoldingModel` -- `CodeFoldingManager.buildInitialFoldings` asserts it
// is NEVER called from the EDT (the thread every test method here actually runs on), so going
// through the full editor pipeline isn't viable in-process; calling the builder directly is also
// a more focused test of the class actually written here.
class HCFoldingBuilderTest : BasePlatformTestCase() {
    private val builder = HCFoldingBuilder()

    private fun fold(text: String): List<com.intellij.lang.folding.FoldingDescriptor> {
        myFixture.configureByText("t.hotc", text)
        val document = myFixture.editor.document
        return builder.buildFoldRegions(myFixture.file, document, false).toList()
    }

    fun `test a multi-line function body folds to braces`() {
        val regions = fold("fn f() {\n    print(1);\n    print(2);\n}\n")
        assertTrue("expected a fold region for the function body", regions.any { it.placeholderText == "{...}" })
    }

    fun `test a single-line body does not fold`() {
        val regions = fold("fn f() { print(1); }\n")
        assertTrue("a single-line block should not produce a fold region", regions.isEmpty())
    }

    fun `test a run of consecutive line comments folds together`() {
        val regions = fold("// first\n// second\n// third\nfn f() {\n    print(1);\n}\n")
        assertTrue(
            "expected a folded comment run",
            regions.any { it.placeholderText?.startsWith("// first") == true },
        )
    }

    fun `test a single comment line does not fold on its own`() {
        val regions = fold("// only one\nfn f() {}\n")
        assertTrue(regions.none { it.placeholderText?.contains("only one") == true })
    }

    fun `test folding never crashes on any real example file`() {
        val examplesDir = findExamplesDir()
        val files = examplesDir.walkTopDown().filter { it.isFile && (it.extension == "hc" || it.extension == "hotc") }.toList()
        assertTrue("expected to find example .hc/.hotc files under $examplesDir", files.isNotEmpty())
        for (f in files) {
            val text = f.readText()
            if (text.isEmpty()) continue
            fold(text)
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
