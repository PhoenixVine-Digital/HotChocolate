package hc.intellij

import com.intellij.psi.TokenType
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// No IDE application context needed -- `HCLexer` only touches `LexerBase`/`IElementType`, plain
// platform data classes usable standalone, same reason the real compiler's own `Lexer.kt` has
// nothing IDE-specific about it either. Runs the lexer over a batch of REAL `.hc`/`.hotc` example
// programs already checked into this repo (not synthetic snippets) -- catches the two failure
// modes that actually matter for a highlighter: (1) any real, valid HC source producing a
// `BAD_CHARACTER` token (a real lexer gap, not a highlighting nitpick), and (2) bracket/brace/
// paren counts going unbalanced (a sign the lexer mis-consumed something, e.g. a string literal
// swallowing a following `}` because escape handling drifted).
class HCLexerTest {
    private fun tokenize(text: String): List<Pair<com.intellij.psi.tree.IElementType, String>> {
        val lexer = HCLexer()
        lexer.start(text, 0, text.length, 0)
        val out = mutableListOf<Pair<com.intellij.psi.tree.IElementType, String>>()
        while (true) {
            val t = lexer.tokenType ?: break
            out.add(t to text.substring(lexer.tokenStart, lexer.tokenEnd))
            lexer.advance()
        }
        return out
    }

    @Test
    fun `keywords are classified as KEYWORD`() {
        val tokens = tokenize("fn struct let var if else while").filter { it.first != TokenType.WHITE_SPACE }
        assertTrue(tokens.all { it.first == HCTokenTypes.KEYWORD })
    }

    @Test
    fun `operators and symbols are distinguished`() {
        val tokens = tokenize("a -> b => c :: d .. e ..= f")
            .filter { it.first != TokenType.WHITE_SPACE }
        val types = tokens.map { it.first }
        assertTrue(HCTokenTypes.ARROW in types)
        assertTrue(HCTokenTypes.FATARROW in types)
        assertTrue(HCTokenTypes.COLONCOLON in types)
        assertTrue(HCTokenTypes.DOTDOT in types)
        assertTrue(HCTokenTypes.DOTDOTEQ in types)
    }

    @Test
    fun `strings with escaped quotes are consumed as one token`() {
        val tokens = tokenize("""let s = "a \"b\" c";""").filter { it.first != TokenType.WHITE_SPACE }
        val stringToken = tokens.first { it.first == HCTokenTypes.STRING }
        assertEquals("\"a \\\"b\\\" c\"", stringToken.second)
    }

    @Test
    fun `interpolated strings split into BEGIN, PART, real embedded tokens, and END`() {
        val src = "\"a {x + 1} b\""
        val all = tokenize(src)
        // Full-buffer coverage checked against the UNFILTERED token list -- whitespace inside the
        // embedded expression is itself a real token here, not something to drop.
        assertEquals(src, all.joinToString("") { it.second })

        val tokens = all.filter { it.first != TokenType.WHITE_SPACE }
        val types = tokens.map { it.first }
        assertEquals(
            listOf(
                HCTokenTypes.ISTRING_BEGIN, HCTokenTypes.ISTRING_PART, HCTokenTypes.LBRACE,
                HCTokenTypes.IDENT, HCTokenTypes.OPERATOR, HCTokenTypes.INT, HCTokenTypes.RBRACE,
                HCTokenTypes.ISTRING_PART, HCTokenTypes.ISTRING_END,
            ),
            types,
        )
        assertEquals("x", tokens[3].second)
        assertEquals("1", tokens[5].second)
    }

    @Test
    fun `interpolation with no literal text around it produces no empty PART tokens`() {
        val tokens = tokenize("\"{a}{b}\"").filter { it.first != TokenType.WHITE_SPACE }
        assertTrue(HCTokenTypes.ISTRING_PART !in tokens.map { it.first })
        assertEquals("\"{a}{b}\"", tokens.joinToString("") { it.second })
    }

    @Test
    fun `a string literal nested inside interpolation does not end the outer string early`() {
        val src = "\"outer {\"inner\" + \"concat\"} done\""
        val tokens = tokenize(src)
        assertTrue(tokens.none { it.first == HCTokenTypes.BAD_CHARACTER })
        assertEquals(src, tokens.joinToString("") { it.second })
        // The nested "inner"/"concat" string literals must each still be their own real STRING
        // token (not swallowed as literal text, not mistaken for the outer literal's own close).
        val nestedStrings = tokens.filter { it.first == HCTokenTypes.STRING }
        assertEquals(listOf("\"inner\"", "\"concat\""), nestedStrings.map { it.second })
    }

    @Test
    fun `doc comments and line comments are distinguished`() {
        val tokens = tokenize("/// a doc\n// a plain comment\nfn f() {}")
            .filter { it.first != TokenType.WHITE_SPACE }
        assertEquals(HCTokenTypes.DOC_COMMENT, tokens[0].first)
        assertEquals(HCTokenTypes.LINE_COMMENT, tokens[1].first)
    }

    @Test
    fun `real example programs lex with no bad characters and balanced brackets`() {
        val examplesDir = findExamplesDir()
        val files = examplesDir.walkTopDown()
            .filter { it.isFile && (it.extension == "hc" || it.extension == "hotc") }
            .toList()
        assertTrue(files.isNotEmpty(), "expected to find example .hc/.hotc files under $examplesDir")

        for (f in files) {
            val tokens = tokenize(f.readText())
            val badChars = tokens.filter { it.first == HCTokenTypes.BAD_CHARACTER }
            assertTrue(badChars.isEmpty(), "${f.path}: unexpected BAD_CHARACTER token(s): ${badChars.map { it.second }}")

            var parens = 0; var braces = 0; var brackets = 0
            for ((type, _) in tokens) {
                when (type) {
                    HCTokenTypes.LPAREN -> parens++
                    HCTokenTypes.RPAREN -> parens--
                    HCTokenTypes.LBRACE -> braces++
                    HCTokenTypes.RBRACE -> braces--
                    HCTokenTypes.LBRACKET -> brackets++
                    HCTokenTypes.RBRACKET -> brackets--
                    else -> {}
                }
            }
            assertEquals(0, parens, "${f.path}: unbalanced parens")
            assertEquals(0, braces, "${f.path}: unbalanced braces")
            assertEquals(0, brackets, "${f.path}: unbalanced brackets")
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
