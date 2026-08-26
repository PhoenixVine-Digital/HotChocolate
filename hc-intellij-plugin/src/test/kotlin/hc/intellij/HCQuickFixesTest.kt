package hc.intellij

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

class HCQuickFixesTest : BasePlatformTestCase() {
    private fun applyFix(src: String, fixNameSubstring: String): String {
        myFixture.configureByText("t.hotc", src)
        val fix = myFixture.getAllQuickFixes().find { it.text.contains(fixNameSubstring) }
        assertNotNull("expected a quick-fix containing '$fixNameSubstring', got: ${myFixture.getAllQuickFixes().map { it.text }}", fix)
        myFixture.launchAction(fix!!)
        return myFixture.file.text
    }

    fun `test add missing match arms inserts every missing variant`() {
        val src = "enum Status {\n    Active,\n    Inactive,\n    Pending,\n}\n" +
            "fn f(s: Status) {\n    match s {\n        Status::Active => {}\n    }\n}\n"
        val result = applyFix(src, "Add missing match arm")
        assertTrue(result.contains("Status::Inactive => {}"))
        assertTrue(result.contains("Status::Pending => {}"))
        // Applying the fix should make the match exhaustive -- no error left behind.
        myFixture.configureByText("t2.hotc", result)
        val errs = myFixture.doHighlighting().filter { it.severity == com.intellij.lang.annotation.HighlightSeverity.ERROR }
        assertTrue("unexpected leftover error(s) after the fix: ${errs.map { it.description }}", errs.none { it.description?.contains("exhaustive") == true })
    }

    fun `test add missing field inserts a placeholder-typed field`() {
        val src = "struct Point { x: Int, y: Int, }\nfn f(p: Point) {\n    print(p.z);\n}\n"
        val result = applyFix(src, "Add field 'z'")
        assertTrue(result.contains("z: Int,"))
        myFixture.configureByText("t2.hotc", result)
        val errs = myFixture.doHighlighting().filter { it.severity == com.intellij.lang.annotation.HighlightSeverity.ERROR }
        assertTrue("unexpected leftover error(s): ${errs.map { it.description }}", errs.none { it.description?.contains("no such field") == true })
    }

    fun `test add missing method infers real parameter types from the call site`() {
        val src = "struct S { v: Int, }\nimpl S {\nfn existing(&self) -> Int { return self.v; }\n}\n" +
            "fn f(s: S) {\n    s.doThing(1, \"x\");\n}\n"
        val result = applyFix(src, "Add method 'doThing'")
        assertTrue("expected inferred Int/String param types, got: $result", result.contains("fn doThing(&self, arg0: Int, arg1: String) {}"))
    }

    fun `test implement interface method uses the interface's own real param list and return type`() {
        val src = "interface Greeter {\n    fn greet(&self, name: String) -> String;\n}\nstruct S {}\nimpl Greeter for S {\n}\n"
        val result = applyFix(src, "Implement 'greet'")
        assertTrue("expected the real param list/return type, got: $result", result.contains("fn greet(&self, name: String) -> String {}"))
        myFixture.configureByText("t2.hotc", result)
        val errs = myFixture.doHighlighting().filter { it.severity == com.intellij.lang.annotation.HighlightSeverity.ERROR }
        assertTrue("unexpected leftover error(s): ${errs.map { it.description }}", errs.none { it.description?.contains("missing implementation") == true })
    }

    fun `test no create-method fix is offered when the struct has no inherent impl block`() {
        // Only an interface impl exists for `S` -- adding an inherent method there would be
        // wrong, so no fix should be offered at all (falls back to the plain error).
        val src = "interface Marker {\n    fn mark(&self);\n}\nstruct S {}\nimpl Marker for S {\nfn mark(&self) {}\n}\n" +
            "fn f(s: S) {\n    s.notReal();\n}\n"
        myFixture.configureByText("t.hotc", src)
        val fixes = myFixture.getAllQuickFixes()
        assertTrue("did not expect a create-method fix here: ${fixes.map { it.text }}", fixes.none { it.text.contains("Add method") })
    }

    // Real-example sweep: proves computing available quick-fixes across every real,
    // already-working example file never crashes (these files have no errors, so no fixes are
    // expected -- this only guards against a crash while COMPUTING intentions).
    fun `test real example programs do not crash quick-fix computation`() {
        val examplesDir = findExamplesDir()
        val files = examplesDir.walkTopDown().filter { it.isFile && (it.extension == "hc" || it.extension == "hotc") }.toList()
        assertTrue("expected to find example .hc/.hotc files under $examplesDir", files.isNotEmpty())
        for (f in files) {
            val text = f.readText()
            if (text.isEmpty()) continue
            myFixture.configureByText(f.name, text)
            myFixture.doHighlighting()
            myFixture.getAllQuickFixes()
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
