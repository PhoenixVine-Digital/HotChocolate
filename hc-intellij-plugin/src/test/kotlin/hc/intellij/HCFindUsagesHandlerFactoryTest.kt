package hc.intellij

import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.usageView.UsageInfo
import java.io.File

// "Search for text occurrences" (the Find Usages dialog checkbox) -- confirmed via a throwaway
// debug test that `HCFindUsagesHandler.processElementUsages` correctly returns both the real
// reference usage AND the string/comment text matches. Driven directly against the handler
// (bypassing the actual dialog UI, same reasoning as `HCRenameTest`'s own header) since the
// checkbox itself isn't meaningfully unit-testable.
class HCFindUsagesHandlerFactoryTest : BasePlatformTestCase() {
    private fun findUsages(src: String, searchText: Boolean): List<UsageInfo> {
        myFixture.configureByText("t.hotc", src)
        val offset = src.indexOf("fn add(") + "fn ".length
        val target = myFixture.file.findElementAt(offset)!!
        val handler = HCFindUsagesHandlerFactory().createFindUsagesHandler(target, false)
        val options = handler.findUsagesOptions
        options.isUsages = true
        options.isSearchForTextOccurrences = searchText
        options.searchScope = GlobalSearchScope.allScope(project)
        val results = mutableListOf<UsageInfo>()
        handler.processElementUsages(target, { results.add(it); true }, options)
        return results
    }

    fun `test text occurrences are included when the option is enabled`() {
        val src = "fn add(a: Int, b: Int) -> Int { return a + b; }\n" +
            "// calls add below\n" +
            "fn f() {\n    add(1, 2);\n    let s = \"add is great\";\n}\n"
        val results = findUsages(src, searchText = true)
        assertEquals(3, results.size)
        assertTrue(results.any { it.element?.node?.elementType == HCElementTypes.REF_EXPR })
        assertTrue(results.any { it.element?.node?.elementType == HCTokenTypes.LINE_COMMENT })
        assertTrue(results.any { it.element?.node?.elementType == HCTokenTypes.STRING })
    }

    fun `test text occurrences are excluded when the option is disabled`() {
        val src = "fn add(a: Int, b: Int) -> Int { return a + b; }\n" +
            "// calls add below\n" +
            "fn f() {\n    add(1, 2);\n    let s = \"add is great\";\n}\n"
        val results = findUsages(src, searchText = false)
        assertEquals(1, results.size)
        assertEquals(HCElementTypes.REF_EXPR, results.single().element?.node?.elementType)
    }

    fun `test a coincidental substring match in an unrelated word is still a real text match`() {
        // Plain substring matching, not word-boundary matching -- `"addition"` containing `add`
        // is a real (if noisy) text match, same as the platform's own generic text-occurrence
        // search would produce for any language.
        val src = "fn add(a: Int, b: Int) -> Int { return a + b; }\nfn f() {\n    let s = \"addition\";\n}\n"
        val results = findUsages(src, searchText = true)
        assertTrue(results.any { it.element?.text == "\"addition\"" })
    }

    fun `test text occurrences are scoped to the same directory, not the whole project`() {
        myFixture.addFileToProject("other/unrelated.hotc", "// mentions add here too\n")
        val mainFile = myFixture.addFileToProject("dir/main.hotc", "fn add(a: Int, b: Int) -> Int { return a + b; }\n// mentions add here\n")
        myFixture.configureFromExistingVirtualFile(mainFile.virtualFile)
        val offset = "fn add(a: Int, b: Int) -> Int { return a + b; }\n// mentions add here\n".indexOf("fn add(") + "fn ".length
        val target = myFixture.file.findElementAt(offset)!!
        val handler = HCFindUsagesHandlerFactory().createFindUsagesHandler(target, false)
        val options = handler.findUsagesOptions
        options.isUsages = true
        options.isSearchForTextOccurrences = true
        options.searchScope = GlobalSearchScope.allScope(project)
        val results = mutableListOf<UsageInfo>()
        handler.processElementUsages(target, { results.add(it); true }, options)
        assertTrue("unrelated file's own text match should not be included: $results", results.none { it.virtualFile?.name == "unrelated.hotc" })
    }

    // Real-example sweep: proves the handler never crashes searching from any real declaration in
    // any real, already-working example file.
    fun `test real example programs do not crash text-occurrence find usages`() {
        val examplesDir = findExamplesDir()
        val files = examplesDir.walkTopDown().filter { it.isFile && (it.extension == "hc" || it.extension == "hotc") }.toList()
        assertTrue("expected to find example .hc/.hotc files under $examplesDir", files.isNotEmpty())
        val byDir = files.groupBy { it.parentFile }
        for ((dirIndex, group) in byDir.values.withIndex()) {
            val added = group.associateWith { f -> myFixture.addFileToProject("dir$dirIndex/${f.name}", f.readText()) }
            for ((_, psiFile) in added) {
                for (ident in com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(psiFile, com.intellij.psi.PsiElement::class.java)) {
                    if (ident.node?.elementType != HCTokenTypes.IDENT) continue
                    val handler = HCFindUsagesHandlerFactory().createFindUsagesHandler(ident, false)
                    val options = handler.findUsagesOptions
                    options.isUsages = true
                    options.isSearchForTextOccurrences = true
                    options.searchScope = GlobalSearchScope.allScope(project)
                    handler.processElementUsages(ident, { true }, options)
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
