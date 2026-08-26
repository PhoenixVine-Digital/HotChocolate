package hc.intellij

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

// Drives the real reference-resolution pipeline via `myFixture.getReferenceAtCaretPosition()` --
// same "prove the registration is actually wired up" reasoning as `HCAnnotatorTest`'s own header.
// `<caret>` marks where the cursor sits (consumed by `configureByText`); `resolvedText(...)`
// returns the exact source text of whatever the reference under the caret resolves to, or null.
class HCReferencesTest : BasePlatformTestCase() {
    private fun resolvedText(src: String): String? {
        myFixture.configureByText("t.hotc", src)
        val target = myFixture.getReferenceAtCaretPosition()?.resolve() ?: return null
        return target.text
    }

    fun `test local param reference resolves to its param declaration`() {
        val src = "fn add(a: Int, b: Int) -> Int { return <caret>a + b; }\n"
        assertEquals("a", resolvedText(src))
    }

    fun `test local let reference resolves to its let declaration`() {
        val src = "fn f() {\n    let x = 1;\n    print(<caret>x);\n}\n"
        assertEquals("x", resolvedText(src))
    }

    fun `test call to a top-level function resolves to its fn declaration`() {
        val src = "fn add(a: Int, b: Int) -> Int { return a + b; }\nfn f() { <caret>add(1, 2); }\n"
        val target = resolvedText(src)
        assertEquals("add", target)
    }

    fun `test lambda bound to a local resolves through the local, not a top-level fn`() {
        val src = "fn f() {\n    let adder = |x, y| x + y;\n    <caret>adder(1, 2);\n}\n"
        assertEquals("adder", resolvedText(src))
    }

    fun `test type reference in a param resolves to the struct declaration`() {
        val src = "struct Point { x: Int, y: Int, }\nfn f(p: <caret>Point) {}\n"
        assertEquals("Point", resolvedText(src))
    }

    fun `test generic type reference resolves to the base struct declaration`() {
        val src = "struct Box<T> { value: T, }\nfn f() {\n    let b: <caret>Box<Int> = Box { value: 1, };\n}\n"
        assertEquals("Box", resolvedText(src))
    }

    fun `test struct literal name resolves to the struct declaration`() {
        val src = "struct Point { x: Int, y: Int, }\nfn f() {\n    let p = <caret>Point { x: 1, y: 2, };\n}\n"
        assertEquals("Point", resolvedText(src))
    }

    fun `test bare enum variant literal resolves to the enum variant declaration`() {
        val src = "enum Opt { Some { v: Int }, None, }\nfn f() {\n    let o = <caret>Some { v: 1, };\n}\n"
        assertEquals("Some", resolvedText(src))
    }

    fun `test struct literal field name resolves to the field declaration`() {
        val src = "struct Point { x: Int, y: Int, }\nfn f() {\n    let p = Point { <caret>x: 1, y: 2, };\n}\n"
        assertEquals("x", resolvedText(src))
    }

    fun `test field access on a typed local resolves to the field declaration`() {
        val src = "struct Point { x: Int, y: Int, }\nfn f(p: Point) {\n    print(p.<caret>x);\n}\n"
        assertEquals("x", resolvedText(src))
    }

    fun `test method call on self resolves to the method declaration`() {
        val src = "struct S { x: Int, }\nimpl S {\nfn go(&self) -> Int { return self.x; }\nfn other(&self) -> Int { return self.<caret>go(); }\n}\n"
        assertEquals("go", resolvedText(src))
    }

    fun `test static method call resolves through the alias to the impl method declaration`() {
        val src = "struct S { x: Int, }\nimpl S {\nfn make(v: Int) -> Int { return v; }\n}\nfn f() { S::<caret>make(1); }\n"
        assertEquals("make", resolvedText(src))
    }

    fun `test static call alias itself resolves to the struct declaration`() {
        val src = "struct S { x: Int, }\nimpl S {\nfn make(v: Int) -> Int { return v; }\n}\nfn f() { <caret>S::make(1); }\n"
        assertEquals("S", resolvedText(src))
    }

    fun `test generic method call on a generic-typed local resolves to the impl method declaration`() {
        val src = "struct Box<T> { value: T, }\nimpl<T> Box<T> {\nfn get(&self) -> T { return self.value; }\n}\n" +
            "fn f() {\n    let b: Box<Int> = Box { value: 1, };\n    b.<caret>get();\n}\n"
        assertEquals("get", resolvedText(src))
    }

    fun `test ambiguous method name across unrelated structs does not resolve`() {
        val src = "struct A { }\nimpl A {\nfn go(&self) -> Int { return 1; }\n}\n" +
            "struct B { }\nimpl B {\nfn go(&self) -> Int { return 2; }\n}\n" +
            "fn f() {\n    let x = makeSomething();\n    x.<caret>go();\n}\nfn makeSomething() -> Int { return 1; }\n"
        assertNull(resolvedText(src))
    }

    fun `test qualified match-arm pattern resolves to the enum variant declaration`() {
        val src = "enum Status {\n    Active,\n    Inactive,\n}\nfn f(s: Status) {\n    match s {\n        Status::<caret>Active => {}\n        Status::Inactive => {}\n    }\n}\n"
        assertEquals("Active", resolvedText(src))
    }

    fun `test match-arm pattern's enum alias resolves to the enum declaration`() {
        val src = "enum Status {\n    Active,\n}\nfn f(s: Status) {\n    match s {\n        <caret>Status::Active => {}\n    }\n}\n"
        assertEquals("Status", resolvedText(src))
    }

    // Real-example sweep: proves reference resolution doesn't CRASH on any real, already-working
    // example file -- doesn't assert every reference resolves (many legitimately won't, e.g. extern
    // class members), just that touching every ident's reference is safe. Directory-grouped
    // `addFileToProject`, same reasoning as `HCAnnotatorTest`'s own sweep header.
    fun `test real example programs do not crash reference resolution`() {
        val examplesDir = findExamplesDir()
        val files = examplesDir.walkTopDown()
            .filter { it.isFile && (it.extension == "hc" || it.extension == "hotc") }
            .toList()
        assertTrue("expected to find example .hc/.hotc files under $examplesDir", files.isNotEmpty())

        val byDir = files.groupBy { it.parentFile }
        for ((dirIndex, group) in byDir.values.withIndex()) {
            val added = group.associateWith { f -> myFixture.addFileToProject("dir$dirIndex/${f.name}", f.readText()) }
            for ((_, psiFile) in added) {
                for (ident in com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(psiFile, com.intellij.psi.PsiElement::class.java)) {
                    if (ident.node?.elementType != HCTokenTypes.IDENT) continue
                    for (ref in ident.references) {
                        ref.resolve()
                    }
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
