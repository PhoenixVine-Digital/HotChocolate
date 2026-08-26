package hc.intellij

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

// Drives `applyChangeSignature` directly -- the real logic, deliberately factored out of
// `HCChangeSignatureDialog` so it can be tested without ever popping real UI, exactly like
// `HCIntroduceVariableHandler.invoke`/`HCInliner.inlineUsage` elsewhere in this plugin.
class HCChangeSignatureHandlerTest : BasePlatformTestCase() {
    private fun fnDecl(name: String): PsiElement =
        PsiTreeUtil.findChildrenOfType(myFixture.file, PsiElement::class.java)
            .first { it.node?.elementType == HCElementTypes.FN_DECL && declaredName(it)?.text == name }

    private fun change(src: String, fnName: String, model: HCChangeSignatureModel): String {
        myFixture.configureByText("t.hotc", src)
        applyChangeSignature(project, fnDecl(fnName), model)
        return myFixture.file.text
    }

    fun `test renaming a function updates the declaration and every call site`() {
        val src = "fn add(a: Int, b: Int) -> Int {\n    return a + b;\n}\nfn f() {\n    print(add(1, 2));\n}\n"
        val model = HCChangeSignatureModel("sum", "Int", listOf(HCParamSpec("a", "Int", 0), HCParamSpec("b", "Int", 1)))
        val result = change(src, "add", model)
        assertTrue("expected declaration renamed: $result", result.contains("fn sum(a: Int, b: Int) -> Int {"))
        assertTrue("expected call site renamed: $result", result.contains("print(sum(1, 2));"))
        assertFalse(result.contains("add"))
    }

    fun `test reordering parameters reorders both the declaration and every call site argument list`() {
        val src = "fn f(a: Int, b: String) {\n    print(a);\n}\nfn g() {\n    f(1, \"x\");\n}\n"
        val model = HCChangeSignatureModel("f", null, listOf(HCParamSpec("b", "String", 1), HCParamSpec("a", "Int", 0)))
        val result = change(src, "f", model)
        assertTrue("expected reordered declaration: $result", result.contains("fn f(b: String, a: Int) {"))
        assertTrue("expected reordered call site: $result", result.contains("f(\"x\", 1);"))
    }

    fun `test removing a parameter drops it from the declaration and every call site`() {
        val src = "fn f(a: Int, b: Int, c: Int) {\n    print(a);\n}\nfn g() {\n    f(1, 2, 3);\n}\n"
        val model = HCChangeSignatureModel("f", null, listOf(HCParamSpec("a", "Int", 0), HCParamSpec("c", "Int", 2)))
        val result = change(src, "f", model)
        assertTrue("expected b dropped from declaration: $result", result.contains("fn f(a: Int, c: Int) {"))
        assertTrue("expected b's argument dropped from the call site: $result", result.contains("f(1, 3);"))
    }

    fun `test adding a new parameter inserts a placeholder at every call site`() {
        val src = "fn f(a: Int) {\n    print(a);\n}\nfn g() {\n    f(1);\n}\n"
        val model = HCChangeSignatureModel("f", null, listOf(HCParamSpec("a", "Int", 0), HCParamSpec("b", "String", null)))
        val result = change(src, "f", model)
        assertTrue("expected new param in declaration: $result", result.contains("fn f(a: Int, b: String) {"))
        assertTrue("expected placeholder at the call site: $result", result.contains("f(1, /* TODO */);"))
    }

    fun `test self is preserved untouched on a method while its own parameters are still editable`() {
        val src = "struct S { v: Int, }\nimpl S {\nfn go(&self, x: Int) -> Int {\n    return self.v + x;\n}\n}\nfn f(s: S) {\n    print(s.go(5));\n}\n"
        val model = HCChangeSignatureModel("go", "Int", listOf(HCParamSpec("y", "Int", 0)))
        val result = change(src, "go", model)
        assertTrue("expected self preserved, param renamed: $result", result.contains("fn go(&self, y: Int) -> Int {"))
        assertTrue("expected the renamed param's own body reference updated too: $result", result.contains("return self.v + y;"))
        assertTrue("expected the method call site untouched in argument shape: $result", result.contains("print(s.go(5));"))
    }

    fun `test renaming a parameter updates every reference to it inside the function body`() {
        val src = "fn f(a: Int) -> Int {\n    return a + a;\n}\nfn g() {\n    print(f(1));\n}\n"
        val model = HCChangeSignatureModel("f", "Int", listOf(HCParamSpec("total", "Int", 0)))
        val result = change(src, "f", model)
        assertTrue("expected declaration param renamed: $result", result.contains("fn f(total: Int) -> Int {"))
        assertTrue("expected every body reference renamed: $result", result.contains("return total + total;"))
        assertTrue("expected the call site argument untouched: $result", result.contains("print(f(1));"))
    }

    fun `test changing the return type only touches the declaration`() {
        val src = "fn f() -> Int {\n    return 1;\n}\nfn g() {\n    print(f());\n}\n"
        val model = HCChangeSignatureModel("f", "String", emptyList())
        val result = change(src, "f", model)
        assertTrue(result.contains("fn f() -> String {"))
        assertTrue(result.contains("print(f());"))
    }

    fun `test result stays syntactically valid after a combined rename, reorder, and return type change`() {
        val src = "fn add(a: Int, b: Int) -> Int {\n    return a + b;\n}\nfn f() {\n    print(add(1, 2));\n}\n"
        val model = HCChangeSignatureModel("sum", "String", listOf(HCParamSpec("b", "Int", 1), HCParamSpec("a", "Int", 0)))
        val result = change(src, "add", model)
        myFixture.configureByText("t2.hotc", result)
        val parseErrors = PsiTreeUtil.findChildrenOfType(myFixture.file, PsiErrorElement::class.java)
        assertTrue("parse errors after change signature: ${parseErrors.map { it.errorDescription }}", parseErrors.isEmpty())
    }

    // Real-example sweep: proves `buildInitialModel` never crashes for every real function in
    // every real, already-working example file (the initial model is exactly what the dialog
    // would show a user; a crash here would mean the dialog can never even open for real code).
    fun `test real example programs do not crash building the initial change signature model`() {
        val examplesDir = findExamplesDir()
        val files = examplesDir.walkTopDown().filter { it.isFile && (it.extension == "hc" || it.extension == "hotc") }.toList()
        assertTrue("expected to find example .hc/.hotc files under $examplesDir", files.isNotEmpty())
        for (f in files) {
            val text = f.readText()
            if (text.isEmpty()) continue
            myFixture.configureByText(f.name, text)
            for (fn in PsiTreeUtil.findChildrenOfType(myFixture.file, PsiElement::class.java)) {
                if (fn.node?.elementType != HCElementTypes.FN_DECL) continue
                buildInitialModel(fn)
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
