package hc.intellij

import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class HCNewProjectActionTest : BasePlatformTestCase() {
    fun `test settings gradle content wires the zero-clone eachPlugin resolution and project name`() {
        val content = settingsGradleContent("my-cool-project")
        assertTrue(content.contains("rootProject.name = \"my-cool-project\""))
        assertTrue(content.contains("maven { url = uri(\"https://jitpack.io\") }"))
        assertTrue(content.contains("if (requested.id.id == \"hc\")"))
        assertTrue(content.contains("useModule(\"com.github.P-H-O-E-N-I-X-PackForge.HotChocolate:hc-gradle-plugin:\${requested.version}\")"))
    }

    fun `test build gradle content applies the requested compiler version consistently`() {
        val content = buildGradleContent("v9.9.9")
        assertTrue(content.contains("id(\"hc\") version \"v9.9.9\""))
        assertTrue(content.contains("version = \"v9.9.9\""))
        assertTrue(content.contains("sourceDir(\"src/main/hc\")"))
    }

    // The most important real check: the scaffolded starter file must actually be valid
    // HotChocolate, not just plausible-looking text -- parsed through this plugin's own real
    // parser, the same one every other regression sweep in this suite uses.
    fun `test the starter hotc file is syntactically valid HotChocolate`() {
        myFixture.configureByText("Main.hotc", starterHotcContent())
        val parseErrors = PsiTreeUtil.findChildrenOfType(myFixture.file, PsiErrorElement::class.java)
        assertTrue("parse errors in the starter file: ${parseErrors.map { it.errorDescription }}", parseErrors.isEmpty())
    }
}
