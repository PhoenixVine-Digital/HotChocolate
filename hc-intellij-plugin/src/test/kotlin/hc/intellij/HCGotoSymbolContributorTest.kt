package hc.intellij

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

// Go to Symbol is deliberately PROJECT-WIDE (see `HCGotoSymbolContributor.kt`'s own header for
// why that's correct here, unlike every OTHER navigation feature in this plugin which stays
// scoped to `filesInScope`) -- these tests exercise it across UNRELATED directories on purpose.
class HCGotoSymbolContributorTest : BasePlatformTestCase() {
    private val contributor = HCGotoSymbolContributor()

    fun `test names are collected across unrelated directories`() {
        myFixture.addFileToProject("dir1/util.hotc", "fn helper() -> Int { return 1; }\nstruct Point { x: Int, }\n")
        myFixture.addFileToProject("dir2/other.hotc", "enum Status {\n    Active,\n}\ninterface Describable {\n    fn describe(&self) -> String;\n}\n")
        val names = contributor.getNames(project, true).toSet()
        assertTrue(names.containsAll(listOf("helper", "Point", "Status", "Active", "Describable", "describe")))
    }

    fun `test a top-level function is found by name`() {
        myFixture.addFileToProject("dir/util.hotc", "fn helper() -> Int { return 1; }\n")
        val items = contributor.getItemsByName("helper", "helper", project, true)
        assertEquals(1, items.size)
        assertEquals("helper", items.single().name)
    }

    fun `test an impl method is found and labeled as a method, not a function`() {
        myFixture.addFileToProject("dir/s.hotc", "struct S { v: Int, }\nimpl S {\nfn go(&self) -> Int { return self.v; }\n}\n")
        val items = contributor.getItemsByName("go", "go", project, true)
        assertEquals(1, items.size)
        assertTrue(items.single().presentation?.locationString?.startsWith("method,") == true)
    }

    fun `test an enum variant is found by name`() {
        myFixture.addFileToProject("dir/status.hotc", "enum Status {\n    Active,\n    Inactive,\n}\n")
        val items = contributor.getItemsByName("Active", "Active", project, true)
        assertEquals(1, items.size)
    }

    fun `test same-named symbols in unrelated directories both show up as distinct results`() {
        // Unlike every other feature in this plugin (resolution/rename/find-usages, all scoped to
        // `filesInScope`), Go to Symbol is a pure navigation aid across the whole project -- both
        // unrelated `Item` structs should be offered, letting the USER pick the right one.
        myFixture.addFileToProject("dirA/a.hotc", "struct Item { weight: Int, }\n")
        myFixture.addFileToProject("dirB/b.hotc", "struct Item { price: Int, }\n")
        val items = contributor.getItemsByName("Item", "Item", project, true)
        assertEquals(2, items.size)
    }

    fun `test navigation opens the correct file at the declaration's offset`() {
        val file = myFixture.addFileToProject("dir/util.hotc", "fn a() {}\nfn helper() -> Int { return 1; }\n")
        val items = contributor.getItemsByName("helper", "helper", project, true)
        val item = items.single()
        assertTrue(item.canNavigate())
        item.navigate(false)
        val openFiles = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFiles
        assertTrue(openFiles.any { it == file.virtualFile })
    }

    // Real-example sweep: proves scanning every real, already-working example file for symbols
    // never crashes.
    fun `test real example programs do not crash goto symbol collection`() {
        val examplesDir = findExamplesDir()
        val files = examplesDir.walkTopDown().filter { it.isFile && (it.extension == "hc" || it.extension == "hotc") }.toList()
        assertTrue("expected to find example .hc/.hotc files under $examplesDir", files.isNotEmpty())
        val byDir = files.groupBy { it.parentFile }
        for ((dirIndex, group) in byDir.values.withIndex()) {
            for (f in group) myFixture.addFileToProject("dir$dirIndex/${f.name}", f.readText())
        }
        val names = contributor.getNames(project, true)
        for (name in names) contributor.getItemsByName(name, name, project, true)
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
