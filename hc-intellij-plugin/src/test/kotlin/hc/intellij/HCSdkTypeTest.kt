package hc.intellij

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

class HCSdkTypeTest : BasePlatformTestCase() {
    private lateinit var tempRoot: File
    private val sdkType = HCSdkType()

    override fun setUp() {
        super.setUp()
        tempRoot = File.createTempFile("hc-sdk-test", "").apply { delete(); mkdirs() }
    }

    override fun tearDown() {
        try {
            tempRoot.deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    fun `test a real installDist-shaped directory is a valid sdk home`() {
        val lib = File(tempRoot, "lib").apply { mkdirs() }
        File(lib, "hotchocolate.jar").writeText("")
        File(lib, "asm-9.7.jar").writeText("")
        File(lib, "kotlin-stdlib-1.9.24.jar").writeText("")

        assertTrue(sdkType.isValidSdkHome(tempRoot.path))
    }

    fun `test a directory missing hotchocolate_jar is not a valid sdk home`() {
        val lib = File(tempRoot, "lib").apply { mkdirs() }
        File(lib, "asm-9.7.jar").writeText("")

        assertFalse(sdkType.isValidSdkHome(tempRoot.path))
    }

    fun `test a directory with no lib folder at all is not a valid sdk home`() {
        assertFalse(sdkType.isValidSdkHome(tempRoot.path))
    }

    fun `test getVersionString honestly returns null rather than guessing`() {
        val lib = File(tempRoot, "lib").apply { mkdirs() }
        File(lib, "hotchocolate.jar").writeText("")

        assertNull(sdkType.getVersionString(tempRoot.path))
    }
}
