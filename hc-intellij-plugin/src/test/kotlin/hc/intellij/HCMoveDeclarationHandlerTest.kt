package hc.intellij

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

// Drives `moveTopLevelDeclaration` directly -- the real logic, deliberately factored out of
// `HCMoveDeclarationHandler`/its dialog so it can be tested without ever popping real UI, exactly
// like `applyChangeSignature`/`HCIntroduceVariableHandler.invoke` elsewhere in this plugin.
class HCMoveDeclarationHandlerTest : BasePlatformTestCase() {
    private fun fnDecl(file: PsiFile, name: String): PsiElement =
        PsiTreeUtil.findChildrenOfType(file, PsiElement::class.java)
            .first { it.node?.elementType == HCElementTypes.FN_DECL && declaredName(it)?.text == name }

    fun `test moving a function relocates it from the source file into the destination file`() {
        val srcContent = "fn helper() -> Int {\n    return 1;\n}\n\nfn main() {\n    print(helper());\n}\n"
        val srcFile = myFixture.addFileToProject("dir/main.hotc", srcContent)
        val destFile = myFixture.addFileToProject("dir/util.hotc", "")

        moveTopLevelDeclaration(project, fnDecl(srcFile, "helper"), destFile)

        assertEquals("fn main() {\n    print(helper());\n}\n", srcFile.text)
        assertEquals("fn helper() -> Int {\n    return 1;\n}\n", destFile.text)
    }

    fun `test moving into a non-empty destination file appends after a single blank line`() {
        val srcFile = myFixture.addFileToProject("dir/main.hotc", "fn helper() -> Int {\n    return 1;\n}\n")
        val destFile = myFixture.addFileToProject("dir/util.hotc", "fn existing() -> Int {\n    return 2;\n}\n")

        moveTopLevelDeclaration(project, fnDecl(srcFile, "helper"), destFile)

        assertEquals(
            "fn existing() -> Int {\n    return 2;\n}\n\nfn helper() -> Int {\n    return 1;\n}\n",
            destFile.text,
        )
    }

    fun `test a leading pub modifier and doc comment travel with the moved declaration`() {
        val srcContent = "/// docs for helper\npub fn helper() -> Int {\n    return 1;\n}\n\nfn main() {}\n"
        val srcFile = myFixture.addFileToProject("dir/main.hotc", srcContent)
        val destFile = myFixture.addFileToProject("dir/util.hotc", "")

        moveTopLevelDeclaration(project, fnDecl(srcFile, "helper"), destFile)

        assertEquals("fn main() {}\n", srcFile.text)
        assertTrue("expected doc comment carried along: ${destFile.text}", destFile.text.startsWith("/// docs for helper\n"))
        assertTrue("expected 'pub' carried along: ${destFile.text}", destFile.text.contains("pub fn helper() -> Int {"))
    }

    fun `test a leading annotation travels with the moved declaration`() {
        val srcContent = "@dev\nfn helper() -> Int {\n    return 1;\n}\nfn main() {}\n"
        val srcFile = myFixture.addFileToProject("dir/main.hotc", srcContent)
        val destFile = myFixture.addFileToProject("dir/util.hotc", "")

        moveTopLevelDeclaration(project, fnDecl(srcFile, "helper"), destFile)

        assertTrue("expected annotation carried along: ${destFile.text}", destFile.text.startsWith("@dev\nfn helper()"))
        assertFalse("expected annotation removed from source: ${srcFile.text}", srcFile.text.contains("@dev"))
    }

    fun `test moving does not disturb an unrelated preceding declaration across a blank line`() {
        val srcContent = "fn unrelated() -> Int {\n    return 0;\n}\n\nfn helper() -> Int {\n    return 1;\n}\n"
        val srcFile = myFixture.addFileToProject("dir/main.hotc", srcContent)
        val destFile = myFixture.addFileToProject("dir/util.hotc", "")

        moveTopLevelDeclaration(project, fnDecl(srcFile, "helper"), destFile)

        assertEquals("fn unrelated() -> Int {\n    return 0;\n}\n", srcFile.text)
        assertEquals("fn helper() -> Int {\n    return 1;\n}\n", destFile.text)
    }

    fun `test cross file references to the moved declaration still resolve after the move`() {
        val srcFile = myFixture.addFileToProject("dir/main.hotc", "fn helper() -> Int {\n    return 1;\n}\nfn main() {\n    print(helper());\n}\n")
        val destFile = myFixture.addFileToProject("dir/util.hotc", "")

        moveTopLevelDeclaration(project, fnDecl(srcFile, "helper"), destFile)

        val liveSrcFile = com.intellij.psi.PsiManager.getInstance(project).findFile(srcFile.virtualFile)!!
        val callIdent = liveSrcFile.findElementAt(liveSrcFile.text.indexOf("helper()") + 2)!!
        val ref = callIdent.parent.references.firstOrNull()
        val resolved = ref?.resolve()
        assertNotNull("expected the call in main.hotc to still resolve to helper() after the move", resolved)
        assertEquals("util.hotc", resolved?.containingFile?.name)
    }

    fun `test both files stay syntactically valid after the move`() {
        val srcFile = myFixture.addFileToProject(
            "dir/main.hotc",
            "struct Point { x: Int, y: Int, }\nfn main() {\n    print(1);\n}\n",
        )
        val destFile = myFixture.addFileToProject("dir/util.hotc", "fn existing() {}\n")

        val structDecl = PsiTreeUtil.findChildrenOfType(srcFile, PsiElement::class.java)
            .first { it.node?.elementType == HCElementTypes.STRUCT_DECL }
        moveTopLevelDeclaration(project, structDecl, destFile)

        for (f in listOf(srcFile, destFile)) {
            myFixture.configureByText(f.name, f.text)
            val parseErrors = PsiTreeUtil.findChildrenOfType(myFixture.file, PsiErrorElement::class.java)
            assertTrue("parse errors in ${f.name} after move: ${parseErrors.map { it.errorDescription }}", parseErrors.isEmpty())
        }
    }

    // Real-example sweep: proves moving every real top-level declaration out of every real,
    // already-working example file (into a fresh, initially-empty sibling file) never crashes and
    // always leaves BOTH files syntactically valid.
    fun `test real example programs do not crash or corrupt syntax when every declaration is moved`() {
        val examplesDir = findExamplesDir()
        val files = examplesDir.walkTopDown().filter { it.isFile && (it.extension == "hc" || it.extension == "hotc") }.toList()
        assertTrue("expected to find example .hc/.hotc files under $examplesDir", files.isNotEmpty())
        for ((i, f) in files.withIndex()) {
            val text = f.readText()
            if (text.isEmpty()) continue
            val srcFile = myFixture.addFileToProject("sweep$i/src.hotc", text)
            val declKinds = setOf(
                HCElementTypes.FN_DECL, HCElementTypes.STRUCT_DECL, HCElementTypes.ENUM_DECL, HCElementTypes.INTERFACE_DECL,
                HCElementTypes.EXTERN_CLASS_DECL, HCElementTypes.STATIC_DECL, HCElementTypes.IMPL_DECL, HCElementTypes.EXTEND_DECL,
            )
            // Re-fetches the live PSI file fresh before EVERY move rather than reusing a
            // once-collected element list: `moveTopLevelDeclaration` commits a document edit (via
            // the earlier move), which reparses `srcFile` and invalidates any `PsiElement`
            // reference collected before that -- the same "one rename/edit at a time, re-fetch
            // afterward" concern `applyChangeSignature`'s own per-param rename loop already has to
            // account for.
            val vFile = srcFile.virtualFile
            var destIdx = 0
            while (true) {
                val livePsiFile = com.intellij.psi.PsiManager.getInstance(project).findFile(vFile) ?: break
                val decl = PsiTreeUtil.findChildrenOfType(livePsiFile, PsiElement::class.java)
                    .firstOrNull { it.node?.elementType in declKinds && it.parent === livePsiFile } ?: break
                val destFile = myFixture.addFileToProject("sweep$i/dest${destIdx++}.hotc", "")
                moveTopLevelDeclaration(project, decl, destFile)
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
