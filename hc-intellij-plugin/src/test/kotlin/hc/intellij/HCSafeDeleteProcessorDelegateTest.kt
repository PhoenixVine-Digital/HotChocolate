package hc.intellij

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.safeDelete.SafeDeleteProcessor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

// Safe Delete's full interactive pipeline (`SafeDeleteHandler` -> element-set expansion via
// `getAdditionalElementsToDelete` -> `SafeDeleteDialog` -> `SafeDeleteProcessor`) can't be driven
// end-to-end in a headless test -- `SafeDeleteDialog` is a real modal `DialogWrapper` that never
// proceeds under `ApplicationManager.isUnitTestMode()` without a UI, so `SafeDeleteHandler.invoke`
// itself was confirmed (via a throwaway debug test) to silently do nothing in this environment.
// What IS both real and testable: each delegate method's own logic in isolation (confirmed
// correct via other throwaway debug tests), and `SafeDeleteProcessor.createInstance(...).run()`
// invoked directly with an explicit element array -- which DOES fully execute end-to-end, and is
// exactly what `SafeDeleteHandler`'s own real orchestration ultimately calls too, once it has
// finished assembling that same element array via `getAdditionalElementsToDelete`.
class HCSafeDeleteProcessorDelegateTest : BasePlatformTestCase() {
    private val delegate = HCSafeDeleteProcessorDelegate()

    private fun findByNameAndKind(src: String, kind: HCElementType, name: String): PsiElement {
        myFixture.configureByText("t.hotc", src)
        return PsiTreeUtil.findChildrenOfType(myFixture.file, PsiElement::class.java)
            .first { it.node?.elementType == kind && declaredName(it)?.text == name }
    }

    private fun safeDelete(target: PsiElement, extra: List<PsiElement> = emptyList()) {
        val additional = delegate.getAdditionalElementsToDelete(target, listOf(target), false).orEmpty()
        val all = (listOf(target) + additional + extra).distinct().toTypedArray()
        SafeDeleteProcessor.createInstance(project, null, all, false, false).run()
    }

    fun `test handlesElement accepts both the composite declaration and its own name ident`() {
        // Both shapes are real: the composite when invoked programmatically, the bare name IDENT
        // when invoked from the editor caret (confirmed via a throwaway debug test that
        // `BaseRefactoringAction.getElementAtCaret` -- what Safe Delete's own action actually
        // resolves the caret to -- always lands on the ident, never the composite; see
        // `HCSafeDeleteProcessorDelegate.kt`'s own header for the full story).
        val fnDecl = findByNameAndKind("fn add() {}\n", HCElementTypes.FN_DECL, "add")
        assertTrue(delegate.handlesElement(fnDecl))
        val identLeaf = declaredName(fnDecl)!!
        assertTrue(delegate.handlesElement(identLeaf))
    }

    fun `test deleting a whole function from its own name ident resolves and removes the whole declaration`() {
        // Simulates the REAL editor-caret invocation shape end-to-end: `element` is the bare name
        // IDENT (what `BaseRefactoringAction.getElementAtCaret` actually resolves to), not the
        // composite -- this is the exact scenario that was grayed out before this fix.
        val fnDecl = findByNameAndKind("fn add(a: Int, b: Int) -> Int { return a + b; }\nfn f() {\n    add(1, 2);\n}\n", HCElementTypes.FN_DECL, "add")
        val identLeaf = declaredName(fnDecl)!!
        safeDelete(identLeaf)
        val text = myFixture.file.text
        assertFalse(text.contains("fn add"))
        assertTrue(text.contains("add(1, 2)"))
    }

    fun `test refactoring support provider reports safe delete as available from the caret-resolved ident`() {
        val fnDecl = findByNameAndKind("fn add() {}\n", HCElementTypes.FN_DECL, "add")
        val identLeaf = declaredName(fnDecl)!!
        assertTrue(HCRefactoringSupportProvider().isSafeDeleteAvailable(identLeaf))
    }

    fun `test deleting a whole function removes it cleanly end-to-end`() {
        val fnDecl = findByNameAndKind("fn add(a: Int, b: Int) -> Int { return a + b; }\nfn f() {\n    add(1, 2);\n}\n", HCElementTypes.FN_DECL, "add")
        safeDelete(fnDecl)
        val text = myFixture.file.text
        assertFalse(text.contains("fn add"))
        // Safe Delete removes the DECLARATION only -- the call site is left as a real, visible
        // consequence (now an unresolved reference) for the user to see and address, not silently
        // deleted along with it.
        assertTrue(text.contains("add(1, 2)"))
    }

    fun `test deleting a struct removes it cleanly end-to-end`() {
        val structDecl = findByNameAndKind("struct Point { x: Int, }\n", HCElementTypes.STRUCT_DECL, "Point")
        safeDelete(structDecl)
        assertEquals("", myFixture.file.text.trim())
    }

    fun `test deleting a let statement removes it cleanly end-to-end`() {
        val letStmt = findByNameAndKind("fn f() {\n    let unused = 1;\n    print(2);\n}\n", HCElementTypes.LET_STMT, "unused")
        safeDelete(letStmt)
        val text = myFixture.file.text
        assertFalse(text.contains("unused"))
        assertTrue(text.contains("print(2);"))
    }

    fun `test additional elements to delete finds the comma after the first param`() {
        val param = findByNameAndKind("fn add(a: Int, b: Int, c: Int) -> Int { return b; }\n", HCElementTypes.PARAM, "a")
        val additional = delegate.getAdditionalElementsToDelete(param, listOf(param), false)
        assertEquals(1, additional?.size)
        assertEquals(",", additional?.single()?.text)
    }

    fun `test additional elements to delete finds the comma before the last param`() {
        val param = findByNameAndKind("fn add(a: Int, b: Int, c: Int) -> Int { return b; }\n", HCElementTypes.PARAM, "c")
        val additional = delegate.getAdditionalElementsToDelete(param, listOf(param), false)
        assertEquals(1, additional?.size)
        assertEquals(",", additional?.single()?.text)
    }

    fun `test deleting the only param of a single-param function needs no comma cleanup`() {
        val param = findByNameAndKind("fn f(a: Int) {}\n", HCElementTypes.PARAM, "a")
        assertTrue(delegate.getAdditionalElementsToDelete(param, listOf(param), false).isNullOrEmpty())
    }

    fun `test deleting a param end-to-end leaves a syntactically clean parameter list`() {
        val param = findByNameAndKind("fn add(a: Int, b: Int, c: Int) -> Int { return b; }\n", HCElementTypes.PARAM, "b")
        safeDelete(param)
        val text = myFixture.file.text
        assertFalse("leftover comma: $text", text.contains(",,") || text.contains("(,") || text.contains(", )"))
        myFixture.configureByText("t2.hotc", text)
        val parseErrors = PsiTreeUtil.findChildrenOfType(myFixture.file, com.intellij.psi.PsiErrorElement::class.java)
        assertTrue("parse errors after deleting a param: ${parseErrors.map { it.errorDescription }}", parseErrors.isEmpty())
    }

    fun `test deleting a struct field end-to-end leaves a syntactically clean field list`() {
        val field = findByNameAndKind("struct Point { x: Int, y: Int, z: Int, }\n", HCElementTypes.FIELD_DECL, "y")
        safeDelete(field)
        myFixture.configureByText("t2.hotc", myFixture.file.text)
        val parseErrors = PsiTreeUtil.findChildrenOfType(myFixture.file, com.intellij.psi.PsiErrorElement::class.java)
        assertTrue("parse errors after deleting a field: ${parseErrors.map { it.errorDescription }}", parseErrors.isEmpty())
    }

    // Real-example sweep: proves computing `handlesElement`/`getAdditionalElementsToDelete` for
    // every real declaration in every real, already-working example file never crashes.
    fun `test real example programs do not crash safe delete computation`() {
        val examplesDir = findExamplesDir()
        val files = examplesDir.walkTopDown().filter { it.isFile && (it.extension == "hc" || it.extension == "hotc") }.toList()
        assertTrue("expected to find example .hc/.hotc files under $examplesDir", files.isNotEmpty())
        for (f in files) {
            val text = f.readText()
            if (text.isEmpty()) continue
            myFixture.configureByText(f.name, text)
            for (el in PsiTreeUtil.findChildrenOfType(myFixture.file, PsiElement::class.java)) {
                if (!delegate.handlesElement(el)) continue
                delegate.getAdditionalElementsToDelete(el, listOf(el), false)
                delegate.findUsages(el, arrayOf(el), mutableListOf())
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
