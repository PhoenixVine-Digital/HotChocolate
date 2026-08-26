package hc.intellij

import com.intellij.psi.PsiElement
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.utils.parameterInfo.MockCreateParameterInfoContext
import com.intellij.testFramework.utils.parameterInfo.MockParameterInfoUIContext
import java.io.File

// Drives the real handler via the platform's own `MockCreateParameterInfoContext`/
// `MockParameterInfoUIContext` test utilities -- discovered via jar archaeology after
// `myFixture.getParameterInfo()` turned out not to exist in this SDK; these mocks are the real,
// intended way to unit-test a `ParameterInfoHandler` without a live UI popup.
class HCParameterInfoHandlerTest : BasePlatformTestCase() {
    private val handler = HCParameterInfoHandler()

    private fun paramInfoAt(src: String, marker: String): String? {
        myFixture.configureByText("t.hotc", src)
        myFixture.editor.caretModel.moveToOffset(src.indexOf(marker))
        val ctx = MockCreateParameterInfoContext(myFixture.editor, myFixture.file)
        val element = handler.findElementForParameterInfo(ctx) ?: return null
        val items = ctx.itemsToShow
        if (items.isNullOrEmpty()) return null
        val uiCtx = MockParameterInfoUIContext(element)
        handler.updateUI(items[0] as HCParamInfo, uiCtx)
        return uiCtx.text
    }

    fun `test top-level function shows its own params`() {
        val src = "fn add(a: Int, b: Int) -> Int { return a + b; }\nfn f() {\n    add(1, 2);\n}\n"
        assertEquals("a: Int, b: Int", paramInfoAt(src, "2)"))
    }

    fun `test method call resolves through the receiver's own type`() {
        val src = "struct S { v: Int, }\nimpl S {\nfn go(&self, n: Int, m: Int) -> Int { return n + m; }\n}\n" +
            "fn f(s: S) {\n    s.go(1, 2);\n}\n"
        assertEquals("n: Int, m: Int", paramInfoAt(src, "2)"))
    }

    fun `test static call resolves through the explicit type alias`() {
        val src = "struct S { v: Int, }\nimpl S {\nfn make(x: Int) -> S { return S { v: x, }; }\n}\n" +
            "fn f() {\n    S::make(1);\n}\n"
        assertEquals("x: Int", paramInfoAt(src, "1)"))
    }

    fun `test self is excluded from the shown parameter list`() {
        val src = "struct S { v: Int, }\nimpl S {\nfn go(&self, n: Int) -> Int { return n; }\n}\nfn f(s: S) {\n    s.go(1);\n}\n"
        assertEquals("n: Int", paramInfoAt(src, "1)"))
    }

    fun `test a function with no parameters shows the no-parameters placeholder`() {
        val src = "fn f() -> Int { return 1; }\nfn g() {\n    f();\n}\n"
        assertEquals("<no parameters>", paramInfoAt(src, "();\n}"))
    }

    fun `test the current parameter index highlights the right parameter`() {
        val src = "fn add(a: Int, b: Int, c: Int) -> Int { return a + b + c; }\nfn f() {\n    add(1, 2, 3);\n}\n"
        myFixture.configureByText("t.hotc", src)
        myFixture.editor.caretModel.moveToOffset(src.indexOf("3)"))
        val ctx = MockCreateParameterInfoContext(myFixture.editor, myFixture.file)
        val element = handler.findElementForParameterInfo(ctx)!!
        val items = ctx.itemsToShow!!
        val uiCtx = MockParameterInfoUIContext(element)
        uiCtx.setCurrentParameterIndex(2)
        handler.updateUI(items[0] as HCParamInfo, uiCtx)
        val highlighted = uiCtx.text.substring(uiCtx.highlightStart, uiCtx.highlightEnd)
        assertEquals("c: Int", highlighted)
    }

    fun `test resolving an unresolvable call returns no parameter info`() {
        val src = "fn f() {\n    totallyUnknownFn(1, 2);\n}\n"
        myFixture.configureByText("t.hotc", src)
        myFixture.editor.caretModel.moveToOffset(src.indexOf("2)"))
        val ctx = MockCreateParameterInfoContext(myFixture.editor, myFixture.file)
        assertNull(handler.findElementForParameterInfo(ctx))
    }

    // Real-example sweep: proves invoking parameter-info lookup at every real ARG_LIST position
    // in every real, already-working example file never crashes.
    fun `test real example programs do not crash parameter info lookup`() {
        val examplesDir = findExamplesDir()
        val files = examplesDir.walkTopDown().filter { it.isFile && (it.extension == "hc" || it.extension == "hotc") }.toList()
        assertTrue("expected to find example .hc/.hotc files under $examplesDir", files.isNotEmpty())
        val byDir = files.groupBy { it.parentFile }
        for ((dirIndex, group) in byDir.values.withIndex()) {
            val added = group.associateWith { f -> myFixture.addFileToProject("dir$dirIndex/${f.name}", f.readText()) }
            for ((_, psiFile) in added) {
                myFixture.configureFromExistingVirtualFile(psiFile.virtualFile)
                for (argList in com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(psiFile, PsiElement::class.java)) {
                    if (argList.node?.elementType != HCElementTypes.ARG_LIST) continue
                    myFixture.editor.caretModel.moveToOffset(argList.textRange.startOffset + 1)
                    val ctx = MockCreateParameterInfoContext(myFixture.editor, myFixture.file)
                    val element = handler.findElementForParameterInfo(ctx) ?: continue
                    val items = ctx.itemsToShow ?: continue
                    if (items.isEmpty()) continue
                    handler.updateUI(items[0] as HCParamInfo, MockParameterInfoUIContext(element))
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
