package hc.intellij

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class HCCreateFileActionTest : BasePlatformTestCase() {
    private val action = HCCreateFileAction()

    private fun <T> writeAction(block: () -> T): T =
        com.intellij.openapi.application.WriteAction.computeAndWait<T, Throwable> { block() }

    fun `test creating a file without an extension appends the default one`() {
        val dir = myFixture.tempDirFixture.findOrCreateDir("pkg")
        val psiDir = com.intellij.psi.PsiManager.getInstance(project).findDirectory(dir)!!
        val file = writeAction { action.createFile("MyFile", "HotChocolate File", psiDir) }
        assertEquals("MyFile.hotc", file?.name)
        assertEquals(HCLanguage, file?.language)
    }

    fun `test creating a file with an explicit extension keeps it as-is`() {
        val dir = myFixture.tempDirFixture.findOrCreateDir("pkg2")
        val psiDir = com.intellij.psi.PsiManager.getInstance(project).findDirectory(dir)!!
        val file = writeAction { action.createFile("Already.hc", "HotChocolate File", psiDir) }
        assertEquals("Already.hc", file?.name)
    }

    fun `test creating a file with a null name or directory returns null`() {
        assertNull(action.createFile(null, "HotChocolate File", null))
    }
}
