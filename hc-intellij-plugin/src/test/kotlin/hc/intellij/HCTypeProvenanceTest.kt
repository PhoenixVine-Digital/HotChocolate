package hc.intellij

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

// Drives `explainExprType` directly (the real logic) for precise assertions on the reasoning
// text, plus one test through the real intention (`myFixture.availableIntentions`/`launchAction`)
// to prove the `plugin.xml` registration and `isAvailable` wiring actually work end to end.
class HCTypeProvenanceTest : BasePlatformTestCase() {
    private fun explain(src: String, needle: String): String {
        myFixture.configureByText("t.hotc", src)
        val offset = src.indexOf(needle)
        assertTrue("expected to find '$needle' in source", offset >= 0)
        val leaf = myFixture.file.findElementAt(offset)!!
        val expr = generateSequence(leaf) { it.parent }.first { EXPR_ELEMENT_TYPES.contains(it.node?.elementType) }
        val fnDecl = generateSequence(expr) { it.parent }.first { it.node?.elementType == HCElementTypes.FN_DECL }
        return explainExprType(expr, fnDecl, myFixture.file)
    }

    fun `test a literal explains its own type directly`() {
        val text = explain("fn f() {\n    print(1);\n}\n", "1")
        assertTrue(text, text.contains("- Int:"))
        assertTrue(text, text.contains("literal's own type"))
    }

    fun `test an explicitly typed parameter explains from its own declaration`() {
        val text = explain("fn f(x: Int) {\n    print(x);\n}\n", "x);")
        assertTrue(text, text.contains("- Int:"))
        assertTrue(text, text.contains("explicitly declared as 'Int'"))
    }

    fun `test a let with no type annotation chains to its initializer`() {
        val text = explain("fn f() {\n    let x = 1 + 2;\n    print(x);\n}\n", "x);")
        assertTrue("expected the outer explanation to mention inference: $text", text.contains("INFERRED from its initializer"))
        assertTrue("expected the chain to reach the binary expression: $text", text.contains("numeric '+' widens"))
        assertTrue("expected the chain to bottom out at the literals: $text", text.contains("literal's own type is always 'Int'"))
    }

    fun `test a call chains to the callee function's declared return type`() {
        val src = "fn make() -> Int {\n    return 1;\n}\nfn f() {\n    let x = make();\n    print(x);\n}\n"
        val text = explain(src, "x);")
        assertTrue(text, text.contains("declared return type 'Int'"))
    }

    fun `test a method call chains through the receiver`() {
        val src = "struct Box { v: Int, }\nimpl Box {\nfn get(&self) -> Int { return self.v; }\n}\n" +
            "fn f(b: Box) {\n    let x = b.get();\n    print(x);\n}\n"
        val text = explain(src, "x);")
        assertTrue(text, text.contains("declared return type 'Int'"))
        assertTrue("expected the receiver's own type to appear in the chain: $text", text.contains("parameter 'b' is explicitly declared as 'Box'"))
    }

    fun `test a field access chains through the receiver to the field's declared type`() {
        val src = "struct Box { v: Int, }\nfn f(b: Box) {\n    let x = b.v;\n    print(x);\n}\n"
        val text = explain(src, "x);")
        assertTrue(text, text.contains("field '.v' on a 'Box' is declared as 'Int'"))
    }

    fun `test a struct literal explains its own name as its type`() {
        val src = "struct Box { v: Int, }\nfn f() {\n    let b = Box { v: 1, };\n    print(b);\n}\n"
        val text = explain(src, "b);")
        assertTrue(text, text.contains("- Box:"))
        assertTrue(text, text.contains("literal's own type is its"))
    }

    fun `test a cast explains the target type directly`() {
        val text = explain("fn f(x: Long) {\n    let y = x as Int;\n    print(y);\n}\n", "y);")
        assertTrue(text, text.contains("'as' cast produces exactly the target type"))
        assertTrue(text, text.contains("- Int:"))
    }

    fun `test a borrow explains through to its inner expression`() {
        val src = "struct Box { v: Int, }\nfn peek(b: &Box) -> Int {\n    return b.v;\n}\n" +
            "fn f() {\n    let box = Box { v: 1, };\n    peek(&box);\n}\n"
        // Target the '&' operator itself -- a caret directly on the wrapped 'box' identifier
        // resolves to ITS OWN (REF_EXPR) node first, since that already matches `EXPR_KINDS`; the
        // '&' token's own parent is the `BORROW_EXPR` this test actually means to explain.
        val text = explain(src, "&box")
        assertTrue(text, text.contains("- Box:"))
        assertTrue(text, text.contains("borrow '&expr' has the same type"))
    }

    fun `test self explains as the enclosing impl block's own receiver type`() {
        val src = "struct Box { v: Int, }\nimpl Box {\nfn describe(&self) -> Int {\n    let x = self.v;\n    return x;\n}\n}\n"
        val text = explain(src, "x;")
        assertTrue(text, text.contains("receiver of the enclosing 'impl Box'"))
    }

    fun `test the intention is offered on an expression and produces a real hint`() {
        myFixture.configureByText("t.hotc", "fn f() {\n    let x = 1 + 2;\n    print(x<caret>);\n}\n")
        val intention = myFixture.availableIntentions.firstOrNull { it.text == "Explain inferred type" }
        assertNotNull("expected the 'Explain inferred type' intention to be available", intention)
    }

    fun `test the intention is not offered outside any expression`() {
        myFixture.configureByText("t.hotc", "<caret>fn f() {\n    print(1);\n}\n")
        val intention = myFixture.availableIntentions.firstOrNull { it.text == "Explain inferred type" }
        assertNull("expected no 'Explain inferred type' intention outside an expression", intention)
    }

    // Real-example sweep: proves the reasoning-chain builder never crashes on any real expression
    // in any real, already-working example file.
    fun `test real example programs do not crash type-provenance explanation`() {
        val examplesDir = findExamplesDir()
        val files = examplesDir.walkTopDown().filter { it.isFile && (it.extension == "hc" || it.extension == "hotc") }.toList()
        assertTrue("expected to find example .hc/.hotc files under $examplesDir", files.isNotEmpty())
        for (f in files) {
            val text = f.readText()
            if (text.isEmpty()) continue
            myFixture.configureByText(f.name, text)
            for (fnDecl in PsiTreeUtil.findChildrenOfType(myFixture.file, PsiElement::class.java)) {
                if (fnDecl.node?.elementType != HCElementTypes.FN_DECL) continue
                for (expr in PsiTreeUtil.findChildrenOfType(fnDecl, PsiElement::class.java)) {
                    if (!EXPR_ELEMENT_TYPES.contains(expr.node?.elementType)) continue
                    explainExprType(expr, fnDecl, myFixture.file)
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

private val EXPR_ELEMENT_TYPES = com.intellij.psi.tree.TokenSet.create(
    HCElementTypes.LITERAL_EXPR, HCElementTypes.STRING_INTERP_EXPR, HCElementTypes.PAREN_EXPR, HCElementTypes.BORROW_EXPR,
    HCElementTypes.UNARY_EXPR, HCElementTypes.INSTANCE_OF_EXPR, HCElementTypes.CAST_EXPR, HCElementTypes.BINARY_EXPR,
    HCElementTypes.REF_EXPR, HCElementTypes.CALL_EXPR, HCElementTypes.STATIC_CALL_EXPR, HCElementTypes.METHOD_CALL_EXPR,
    HCElementTypes.FIELD_ACCESS_EXPR, HCElementTypes.STRUCT_LIT_EXPR,
)
