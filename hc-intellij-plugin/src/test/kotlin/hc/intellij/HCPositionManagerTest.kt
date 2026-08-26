package hc.intellij

import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.fixtures.BasePlatformTestCase

// Exercises the one piece of `HCPositionManager` that doesn't need a live JDI virtual machine to
// verify: resolving the `sourceName` a compiled class carries (from `CodeGen.kt`'s own
// `visitSource`, e.g. "Calculator.hotc") back to the real `PsiFile` in the project. The
// JDI-dependent methods (`getSourcePosition`/`locationsOfLine`/`createPrepareRequest`) need an
// actual attached debug process to exercise meaningfully and aren't covered here.
class HCPositionManagerTest : BasePlatformTestCase() {
    fun `test a compiled sourceName resolves back to the real hotc file by name`() {
        val added = myFixture.addFileToProject("dir/Calculator.hotc", "fn main() {\n    print(1);\n}\n")

        val found = FilenameIndex.getVirtualFilesByName(project, "Calculator.hotc", GlobalSearchScope.allScope(project))
            .firstOrNull()?.let { PsiManager.getInstance(project).findFile(it) }

        assertEquals(added.virtualFile, found?.virtualFile)
    }

    fun `test an unrelated file name does not resolve`() {
        myFixture.addFileToProject("dir/Calculator.hotc", "fn main() {}\n")

        val found = FilenameIndex.getVirtualFilesByName(project, "NoSuchFile.hotc", GlobalSearchScope.allScope(project))

        assertTrue(found.isEmpty())
    }
}
