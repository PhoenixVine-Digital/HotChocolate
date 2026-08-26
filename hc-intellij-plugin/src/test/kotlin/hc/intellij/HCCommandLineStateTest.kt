package hc.intellij

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

class HCCommandLineStateTest : BasePlatformTestCase() {
    private lateinit var tempRoot: File

    override fun setUp() {
        super.setUp()
        tempRoot = File.createTempFile("hc-cmdline-test", "").apply { delete(); mkdirs() }
    }

    override fun tearDown() {
        try {
            tempRoot.deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    fun `test finds the gradlew_bat sibling to the nearest wrapper going up`() {
        val projectRoot = File(tempRoot, "project").apply { mkdirs() }
        File(projectRoot, "gradlew.bat").writeText("")
        val exampleDir = File(projectRoot, "examples/calc").apply { mkdirs() }

        val found = findGradleRoot(exampleDir)

        assertEquals(projectRoot.canonicalFile, found?.canonicalFile)
    }

    fun `test returns null when no gradlew is found in any ancestor`() {
        val looseDir = File(tempRoot, "no-gradle-here/nested").apply { mkdirs() }

        assertNull(findGradleRoot(looseDir))
    }

    fun `test prefers the nearest wrapper over a more distant one`() {
        File(tempRoot, "gradlew.bat").writeText("")
        val innerProjectRoot = File(tempRoot, "inner").apply { mkdirs() }
        File(innerProjectRoot, "gradlew.bat").writeText("")
        val nested = File(innerProjectRoot, "src").apply { mkdirs() }

        val found = findGradleRoot(nested)

        assertEquals(innerProjectRoot.canonicalFile, found?.canonicalFile)
    }
}
