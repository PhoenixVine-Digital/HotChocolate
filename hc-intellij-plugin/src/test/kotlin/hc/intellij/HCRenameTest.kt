package hc.intellij

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

// Drives the real rename pipeline (`HCRenameHandler` -> `RenamePsiElementProcessor` ->
// `HCReference.handleElementRename`), invoked exactly the way Shift+F6 would: via
// `renameElementAtCaretUsingHandler`, which goes through real `RenameHandler` discovery rather
// than assuming a target the way `renameElement(explicitTarget, ...)` would -- see
// `HCRenameHandler.kt`'s own header for why that discovery step needed its own handler at all
// (a plain leaf `IDENT` isn't a `PsiNamedElement`, so the platform's generic caret-target lookup
// can't find one on its own from a DECLARATION site, only from a reference/usage site).
class HCRenameTest : BasePlatformTestCase() {
    private fun renameAt(src: String, newName: String): String {
        myFixture.configureByText("t.hotc", src)
        val offset = src.indexOf("<caret>")
        assertTrue("expected a <caret> marker in the test source", offset >= 0)
        myFixture.editor.caretModel.moveToOffset(offset)
        myFixture.renameElementAtCaretUsingHandler(newName)
        return myFixture.file.text
    }

    fun `test rename from a top-level function's own declaration site`() {
        val src = "fn <caret>add(a: Int, b: Int) -> Int { return a + b; }\nfn f() {\n    add(1, 2);\n    add(3, 4);\n}\n"
        val result = renameAt(src, "sum")
        assertTrue(result.contains("fn sum(a: Int, b: Int)"))
        assertTrue(result.contains("sum(1, 2)"))
        assertTrue(result.contains("sum(3, 4)"))
        assertFalse(result.contains("add"))
    }

    fun `test rename from a call site renames the declaration and every other call`() {
        val src = "fn add(a: Int, b: Int) -> Int { return a + b; }\nfn f() {\n    <caret>add(1, 2);\n    add(3, 4);\n}\n"
        val result = renameAt(src, "sum")
        assertTrue(result.contains("fn sum(a: Int, b: Int)"))
        assertTrue(result.contains("sum(1, 2)"))
        assertTrue(result.contains("sum(3, 4)"))
        assertFalse(result.contains("add"))
    }

    fun `test rename a param renames every local use but not the outer function name`() {
        val src = "fn add(<caret>a: Int, b: Int) -> Int { return a + a + b; }\n"
        val result = renameAt(src, "x")
        assertTrue(result.contains("fn add(x: Int, b: Int)"))
        assertTrue(result.contains("return x + x + b;"))
    }

    fun `test rename a struct updates its type references and struct literals`() {
        val src = "struct <caret>Point { x: Int, y: Int, }\nfn f(p: Point) {\n    let q = Point { x: 1, y: 2, };\n}\n"
        val result = renameAt(src, "Coord")
        assertTrue(result.contains("struct Coord {"))
        assertTrue(result.contains("f(p: Coord)"))
        assertTrue(result.contains("Coord { x: 1, y: 2, }"))
        assertFalse(result.contains("Point"))
    }

    fun `test rename a struct field updates field access and struct-literal field names`() {
        val src = "struct Point { <caret>x: Int, y: Int, }\nfn f(p: Point) {\n    let q = Point { x: 1, y: 2, };\n    print(p.x);\n}\n"
        val result = renameAt(src, "px")
        assertTrue(result.contains("struct Point { px: Int, y: Int, }"))
        assertTrue(result.contains("Point { px: 1, y: 2, }"))
        assertTrue(result.contains("print(p.px)"))
    }

    fun `test rename an impl method updates every call site`() {
        val src = "struct S { v: Int, }\nimpl S {\nfn <caret>go(&self) -> Int { return self.v; }\n}\n" +
            "fn f(s: S) {\n    s.go();\n    s.go();\n}\n"
        val result = renameAt(src, "run")
        assertTrue(result.contains("fn run(&self)"))
        assertTrue(result.contains("s.run();\n    s.run();"))
        assertFalse(result.contains("go"))
    }

    fun `test rename an enum variant updates qualified match arms`() {
        val src = "enum Status {\n    <caret>Active,\n    Inactive,\n}\nfn f(s: Status) {\n    match s {\n        Status::Active => {}\n        Status::Inactive => {}\n    }\n}\n"
        val result = renameAt(src, "Running")
        assertTrue(result.contains("Running,"))
        assertTrue(result.contains("Status::Running =>"))
    }

    fun `test rename does not affect an unrelated struct's same-named field`() {
        val src = "struct A { <caret>x: Int, }\nstruct B { x: Int, }\nfn f(a: A, b: B) {\n    print(a.x);\n    print(b.x);\n}\n"
        val result = renameAt(src, "renamed")
        assertTrue(result.contains("struct A { renamed: Int, }"))
        assertTrue(result.contains("struct B { x: Int, }"))
        assertTrue(result.contains("print(a.renamed)"))
        assertTrue(result.contains("print(b.x)"))
    }

    fun `test rename sees usages across same-directory sibling files`() {
        myFixture.addFileToProject("dir/util.hotc", "fn helper(x: Int) -> Int { return x; }\n")
        val mainContent = "fn f() {\n    helper(1);\n}\n"
        val mainFile = myFixture.addFileToProject("dir/main.hotc", mainContent)
        myFixture.configureFromExistingVirtualFile(mainFile.virtualFile)
        myFixture.editor.caretModel.moveToOffset(mainContent.indexOf("helper"))
        myFixture.renameElementAtCaretUsingHandler("assist")
        val mainText = myFixture.file.text
        assertTrue(mainText.contains("assist(1)"))
        val utilText = myFixture.file.containingDirectory.findFile("util.hotc")?.text ?: ""
        assertTrue(utilText.contains("fn assist(x: Int)"))
    }

    // Real-example sweep: proves invoking `HCRenameHandler.isAvailableOnDataContext` at every
    // real position in every real, already-working example file never crashes -- same reasoning
    // as every other sweep in this plugin.
    fun `test real example programs do not crash rename handler availability checks`() {
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
                val offsets = listOf(text.length / 4, text.length / 2, (text.length * 3) / 4).filter { it in 1 until text.length }
                for (offset in offsets) {
                    myFixture.editor.caretModel.moveToOffset(offset)
                    val dataContext = com.intellij.ide.DataManager.getInstance().getDataContext(myFixture.editor.contentComponent)
                    HCRenameHandler().isAvailableOnDataContext(dataContext)
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
