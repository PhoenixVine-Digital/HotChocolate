package hc.intellij

import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

// Drives the real Find Usages search (`ReferencesSearch.search`, backed by `HCReferencesSearcher`)
// -- same "prove the registration is actually wired up" reasoning as `HCReferencesTest`'s own
// header. `<caret>` marks the DECLARATION to search from; `usageTexts(...)` returns the source text
// of every usage found, in no particular order.
class HCFindUsagesTest : BasePlatformTestCase() {
    private fun usageTexts(src: String): List<String> {
        myFixture.configureByText("t.hotc", src)
        val target = myFixture.file.findElementAt(myFixture.caretOffset)!!
        return ReferencesSearch.search(target).findAll().map { it.element.text }
    }

    fun `test usages of a top-level function are found across multiple call sites`() {
        val src = "fn <caret>add(a: Int, b: Int) -> Int { return a + b; }\n" +
            "fn f() {\n    add(1, 2);\n    add(3, 4);\n}\n"
        val usages = usageTexts(src)
        assertEquals(2, usages.size)
    }

    fun `test usages of a param are found within its own function only`() {
        val src = "fn add(<caret>a: Int, b: Int) -> Int { return a + a + b; }\n"
        val usages = usageTexts(src)
        assertEquals(2, usages.size)
    }

    fun `test usages of a struct are found across struct literals and type references`() {
        val src = "struct <caret>Point { x: Int, y: Int, }\n" +
            "fn f(p: Point) {\n    let q = Point { x: 1, y: 2, };\n}\n"
        val usages = usageTexts(src)
        assertEquals(2, usages.size)
    }

    fun `test usages of a struct field are found via field access`() {
        val src = "struct Point { <caret>x: Int, y: Int, }\n" +
            "fn f(p: Point) {\n    print(p.x);\n    print(p.x);\n}\n"
        val usages = usageTexts(src)
        assertEquals(2, usages.size)
    }

    fun `test usages of an impl method are found via method calls`() {
        val src = "struct S { v: Int, }\nimpl S {\nfn <caret>go(&self) -> Int { return self.v; }\n}\n" +
            "fn f(s: S) {\n    s.go();\n    s.go();\n}\n"
        val usages = usageTexts(src)
        assertEquals(2, usages.size)
    }

    fun `test unrelated struct of the same field name is not counted as a usage`() {
        val src = "struct A { <caret>x: Int, }\nstruct B { x: Int, }\nfn f(a: A, b: B) {\n    print(a.x);\n    print(b.x);\n}\n"
        val usages = usageTexts(src)
        // Only `a.x` -- `b.x` resolves against `B`'s own field, a different declaration entirely.
        assertEquals(1, usages.size)
    }

    fun `test renaming via find usages sees usages across same-directory sibling files`() {
        val mainFile = myFixture.addFileToProject("dir/main.hotc", "fn f() {\n    helper(1);\n}\n")
        myFixture.addFileToProject("dir/util.hotc", "fn <caret>helper(x: Int) -> Int { return x; }\n")
        myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir("dir/util.hotc"))
        val target = myFixture.file.findElementAt(myFixture.caretOffset)!!
        val usages = ReferencesSearch.search(target).findAll()
        assertEquals(1, usages.size)
        assertEquals(mainFile.virtualFile, usages.single().element.containingFile.virtualFile)
    }

    // Real-example sweep: proves Find Usages doesn't crash searching from any real declaration in
    // any real, already-working example file -- directory-grouped `addFileToProject`, same
    // reasoning as `HCAnnotatorTest`/`HCReferencesTest`'s own sweeps.
    fun `test real example programs do not crash find usages`() {
        val examplesDir = findExamplesDir()
        val files = examplesDir.walkTopDown()
            .filter { it.isFile && (it.extension == "hc" || it.extension == "hotc") }
            .toList()
        assertTrue("expected to find example .hc/.hotc files under $examplesDir", files.isNotEmpty())

        val byDir = files.groupBy { it.parentFile }
        for ((dirIndex, group) in byDir.values.withIndex()) {
            val added = group.associateWith { f -> myFixture.addFileToProject("dir$dirIndex/${f.name}", f.readText()) }
            for ((_, psiFile) in added) {
                for (ident in com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(psiFile, com.intellij.psi.PsiElement::class.java)) {
                    if (ident.node?.elementType != HCTokenTypes.IDENT) continue
                    ReferencesSearch.search(ident).findAll()
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
