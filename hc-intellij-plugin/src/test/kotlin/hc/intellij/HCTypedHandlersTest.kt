package hc.intellij

// Drives `myFixture.type(...)` -- real key-by-key typing through the actual editor action
// pipeline, the same one a real keystroke goes through -- so these exercise the REGISTERED
// `HCQuoteHandler`/`HCEnterHandlerDelegate` end to end (plugin.xml wiring included), not just the
// classes' own methods called directly.
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class HCTypedHandlersTest : BasePlatformTestCase() {
    fun `test typing an opening quote auto-inserts the closing quote`() {
        myFixture.configureByText("t.hotc", "fn f() {\n    print(<caret>);\n}\n")
        myFixture.type("\"")
        assertEquals("fn f() {\n    print(\"\");\n}\n", myFixture.editor.document.text)
    }

    fun `test typing the closing quote types through instead of duplicating`() {
        myFixture.configureByText("t.hotc", "fn f() {\n    print(\"<caret>\");\n}\n")
        myFixture.type("\"")
        assertEquals("fn f() {\n    print(\"\");\n}\n", myFixture.editor.document.text)
    }

    fun `test typing text inside an auto-closed string works normally`() {
        myFixture.configureByText("t.hotc", "fn f() {\n    print(<caret>);\n}\n")
        myFixture.type("\"hi")
        assertEquals("fn f() {\n    print(\"hi\");\n}\n", myFixture.editor.document.text)
    }

    fun `test pressing Enter inside a doc comment continues it with a triple-slash prefix`() {
        myFixture.configureByText("t.hotc", "/// first line<caret>\nfn f() {}\n")
        myFixture.type("\n")
        assertTrue(
            "expected the new line to continue with '/// ': ${myFixture.editor.document.text}",
            myFixture.editor.document.text.contains("/// first line\n/// "),
        )
    }

    fun `test pressing Enter inside a doc comment preserves indentation`() {
        myFixture.configureByText("t.hotc", "impl S {\n    /// first line<caret>\n    fn f() {}\n}\n")
        myFixture.type("\n")
        assertTrue(
            "expected the continuation to keep the same indent: ${myFixture.editor.document.text}",
            myFixture.editor.document.text.contains("    /// first line\n    /// "),
        )
    }

    fun `test pressing Enter on a plain line comment does not get triple-slash continuation`() {
        myFixture.configureByText("t.hotc", "// plain comment<caret>\nfn f() {}\n")
        myFixture.type("\n")
        assertFalse("a plain '//' comment should not gain a '///' continuation", myFixture.editor.document.text.contains("///"))
    }

    fun `test pressing Enter outside any comment behaves normally`() {
        myFixture.configureByText("t.hotc", "fn f() {\n    print(1);<caret>\n}\n")
        myFixture.type("\n")
        assertFalse(myFixture.editor.document.text.contains("///"))
        assertTrue(myFixture.editor.document.text.contains("print(1);"))
    }
}
