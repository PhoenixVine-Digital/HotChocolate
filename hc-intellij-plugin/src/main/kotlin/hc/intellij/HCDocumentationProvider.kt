package hc.intellij

import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.psi.PsiElement

// Real symbol resolution (knowing that THIS `foo` identifier is a call to THAT `fn foo` three
// files away) needs a real PSI tree with actual reference/resolve support, which `HCParserDefinition`'s
// own header discloses this first pass doesn't have. What's implemented here instead is a
// text-scanning heuristic, scoped to the CURRENT file only: for an identifier that's immediately
// followed by `(` or immediately preceded by `struct`/`fn` (i.e. plausibly this identifier's own
// declaration site, or a same-named declaration elsewhere in the file), walk upward from the
// nearest `fn <name>`/`struct <name>` match and collect any contiguous immediately-preceding `///`
// doc-comment lines, same attachment rule the real compiler's own `Ast.kt` `DocComment` uses. A
// real, disclosed limitation: this can surface the WRONG declaration if two same-named symbols
// exist in one file (shadowing, overloads this subset doesn't have anyway) -- acceptable for a
// hover convenience, not something correctness-sensitive code should ever depend on.
class HCDocumentationProvider : AbstractDocumentationProvider() {
    override fun generateDoc(element: PsiElement, originalElement: PsiElement?): String? {
        val text = element.text ?: return null
        keywordDoc(text)?.let { return it }
        return lookupDeclarationDoc(element)
    }

    override fun getQuickNavigateInfo(element: PsiElement, originalElement: PsiElement?): String? {
        val text = element.text ?: return null
        return if (text in HCTokenTypes.KEYWORDS) "keyword <b>$text</b>" else null
    }

    private fun lookupDeclarationDoc(element: PsiElement): String? {
        val file = element.containingFile ?: return null
        val fileText = file.text
        val name = element.text
        if (name.isBlank() || name[0].isDigit()) return null

        for (kw in listOf("fn", "struct", "enum")) {
            val needle = "$kw $name"
            var idx = fileText.indexOf(needle)
            while (idx >= 0) {
                // Confirm this is really a declaration, not a substring match mid-identifier
                // (e.g. `fn foo2` shouldn't match a lookup for `foo`) or mid-call (`foo(...)`
                // preceded coincidentally by the text "fn " somewhere unrelated).
                val afterName = idx + needle.length
                val boundaryOk = afterName >= fileText.length || !fileText[afterName].isLetterOrDigit() && fileText[afterName] != '_'
                val beforeOk = idx == 0 || !fileText[idx - 1].isLetterOrDigit()
                if (boundaryOk && beforeOk) {
                    val doc = extractDocCommentBefore(fileText, idx)
                    val header = "<b>$kw $name</b>"
                    return if (doc != null) "$header<br/>$doc" else header
                }
                idx = fileText.indexOf(needle, idx + 1)
            }
        }
        return null
    }

    // Walks backward from `declStart` over blank lines it tolerates none of (a doc comment block
    // must be IMMEDIATELY adjacent, same rule the real compiler's own lexer/parser pairing uses)
    // collecting contiguous `/// ...` lines, then reverses them back into source order.
    private fun extractDocCommentBefore(fileText: String, declStart: Int): String? {
        var lineStart = fileText.lastIndexOf('\n', declStart - 1) + 1
        val lines = mutableListOf<String>()
        while (true) {
            val prevLineEnd = lineStart - 1
            if (prevLineEnd < 0) break
            val prevLineStart = fileText.lastIndexOf('\n', prevLineEnd - 1) + 1
            val prevLine = fileText.substring(prevLineStart, prevLineEnd).trim()
            if (!prevLine.startsWith("///")) break
            lines.add(0, prevLine.removePrefix("///").trim())
            lineStart = prevLineStart
        }
        return if (lines.isEmpty()) null else lines.joinToString("<br/>") { it.ifBlank { "&nbsp;" } }
    }

    private fun keywordDoc(text: String): String? = KEYWORD_DOCS[text]?.let { "<b>$text</b><br/>$it" }

    companion object {
        private val KEYWORD_DOCS: Map<String, String> = mapOf(
            "fn" to "Declares a function.",
            "struct" to "Declares a plain data type (fields only, no inheritance).",
            "impl" to "Declares methods (and, without `self`, static functions) for a struct.",
            "let" to "Declares an immutable local binding.",
            "var" to "Declares a mutable local binding.",
            "if" to "Conditional branch.",
            "else" to "Alternate branch of an `if`.",
            "while" to "Loops while a condition holds.",
            "for" to "Iterates a range or collection.",
            "in" to "Introduces the source of a `for` loop.",
            "return" to "Returns a value (or nothing) from the enclosing function.",
            "mut" to "Marks a `&mut` reference or a `mut self` receiver as mutable.",
            "arena" to "Declares an `arena struct` — an off-heap, fixed-layout value type backed by `java.lang.foreign` (needs JDK 22+ to run).",
            "interface" to "Declares an interface (a set of method signatures a `struct` can implement).",
            "dyn" to "A dynamically-dispatched reference to an interface type, e.g. `&dyn Shape`.",
            "enum" to "Declares a sum type: a fixed set of named variants, optionally carrying fields.",
            "match" to "Pattern-matches an enum value against its variants.",
            "sealed" to "Marks an interface whose implementers must all be declared in the same compilation unit.",
            "extern" to "Declares a binding to a real JVM class (`extern class Alias = \"binary/Name\" { ... }`).",
            "class" to "Used in `extern class` — names the real JVM class an alias binds to.",
            "module" to "Declares this file's module path (organizational only — does not gate visibility).",
            "pub" to "Marks a declaration visible outside its own file/module.",
            "static" to "Declares a top-level constant (`pub static NAME: Type = expr;`) or a static method inside `impl`.",
            "as" to "Casts an expression to another type, e.g. `x as Int`.",
            "try" to "Begins a block whose real JVM exceptions can be caught by a following `catch`.",
            "catch" to "Catches a real JVM exception thrown inside the preceding `try` block.",
            "throw" to "Throws a real JVM exception.",
            "extends" to "Declares a struct's real JVM superclass.",
            "override" to "Marks a method as overriding a superclass/interface method.",
            "is" to "Type-check operator.",
            "break" to "Exits the nearest enclosing loop.",
            "continue" to "Skips to the next iteration of the nearest enclosing loop.",
            "dev" to "Guards code (`if dev { }`) that's stripped entirely in a `--release` build.",
            "true" to "Boolean literal.",
            "false" to "Boolean literal.",
            "null" to "Null literal (only valid for nullable `extern class`/`String` types).",
        )
    }
}
