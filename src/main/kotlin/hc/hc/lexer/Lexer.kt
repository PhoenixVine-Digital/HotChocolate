package hc.lexer

class LexError(message: String) : RuntimeException(message)

private val KEYWORDS = mapOf(
    "fn" to TokType.FN,
    "struct" to TokType.STRUCT,
    "let" to TokType.LET,
    "var" to TokType.VAR,
    "if" to TokType.IF,
    "else" to TokType.ELSE,
    "while" to TokType.WHILE,
    "for" to TokType.FOR,
    "in" to TokType.IN,
    "return" to TokType.RETURN,
    "true" to TokType.TRUE,
    "false" to TokType.FALSE,
    "impl" to TokType.IMPL,
    "mut" to TokType.MUT,
    "arena" to TokType.ARENA,
    "interface" to TokType.INTERFACE,
    "dyn" to TokType.DYN,
    "enum" to TokType.ENUM,
    "match" to TokType.MATCH,
    "by" to TokType.BY,
    "sealed" to TokType.SEALED,
    "extern" to TokType.EXTERN,
    "class" to TokType.CLASS,
    "module" to TokType.MODULE,
    "pub" to TokType.PUB,
    "open" to TokType.OPEN,
    "extend" to TokType.EXTEND,
    "static" to TokType.STATIC,
    "as" to TokType.AS,
    // Deliberately NOT a keyword -- "use" only means anything special immediately after an
    // `extern class Alias = "binary.Name"` header (the eager-reflection form), a single
    // context the parser checks for by peeking an IDENT's text (see externClassDecl) rather
    // than reserving the word globally. A real Java method can be (and, for `Item.use(...)`,
    // is) named exactly "use" -- reserving it as a hard keyword would make that name
    // undeclarable in an extern class's method list, a genuine collision found while porting
    // CopyToolItem.hc.
    "try" to TokType.TRY,
    "catch" to TokType.CATCH,
    "throw" to TokType.THROW,
    "extends" to TokType.EXTENDS,
    "override" to TokType.OVERRIDE,
    "null" to TokType.NULL,
    "is" to TokType.IS,
)

class Lexer(private val src: String) {
    private var pos = 0
    private var line = 1

    fun tokenize(): List<Token> {
        val out = mutableListOf<Token>()
        while (true) {
            skipTrivia()
            if (isAtEnd()) {
                out += Token(TokType.EOF, "", line)
                break
            }
            out += nextToken()
        }
        return out
    }

    private fun nextToken(): List<Token> {
        val startLine = line
        val c = advance()
        return when {
            c.isDigit() -> listOf(number(c, startLine))
            c.isLetter() || c == '_' -> listOf(identifier(startLine))
            c == '"' -> string(startLine)
            else -> listOf(symbol(c, startLine))
        }
    }

    private fun skipTrivia() {
        while (!isAtEnd()) {
            val c = peek()
            when {
                c == '\n' -> { line++; pos++ }
                c.isWhitespace() -> pos++
                c == '/' && peekNext() == '/' -> {
                    while (!isAtEnd() && peek() != '\n') pos++
                }
                else -> return
            }
        }
    }

    private fun symbol(c: Char, startLine: Int): Token {
        fun tok(t: TokType, text: String) = Token(t, text, startLine)
        return when (c) {
            '(' -> tok(TokType.LPAREN, "(")
            ')' -> tok(TokType.RPAREN, ")")
            '{' -> tok(TokType.LBRACE, "{")
            '}' -> tok(TokType.RBRACE, "}")
            '[' -> tok(TokType.LBRACKET, "[")
            ']' -> tok(TokType.RBRACKET, "]")
            ',' -> tok(TokType.COMMA, ",")
            ':' -> if (match(':')) tok(TokType.COLONCOLON, "::") else tok(TokType.COLON, ":")
            ';' -> tok(TokType.SEMI, ";")
            '.' -> if (match('.')) tok(TokType.DOTDOT, "..") else tok(TokType.DOT, ".")
            '+' -> tok(TokType.PLUS, "+")
            '-' -> if (match('>')) tok(TokType.ARROW, "->") else tok(TokType.MINUS, "-")
            '*' -> tok(TokType.STAR, "*")
            '/' -> tok(TokType.SLASH, "/")
            '%' -> tok(TokType.PERCENT, "%")
            '?' -> tok(TokType.QUESTION, "?")
            '@' -> tok(TokType.AT, "@")
            '&' -> if (match('&')) tok(TokType.AMPAMP, "&&") else tok(TokType.AMP, "&")
            '|' -> if (match('|')) tok(TokType.PIPEPIPE, "||") else throw LexError("Unexpected character '|' at line $startLine")
            '=' -> when {
                match('=') -> tok(TokType.EQEQ, "==")
                match('>') -> tok(TokType.FATARROW, "=>")
                else -> tok(TokType.EQ, "=")
            }
            '!' -> if (match('=')) tok(TokType.BANGEQ, "!=") else tok(TokType.BANG, "!")
            '<' -> if (match('=')) tok(TokType.LTEQ, "<=") else tok(TokType.LT, "<")
            '>' -> if (match('=')) tok(TokType.GTEQ, ">=") else tok(TokType.GT, ">")
            else -> throw LexError("Unexpected character '$c' at line $startLine")
        }
    }

    // `123` -> Int. `123L` -> Long. `1.5` -> Double (Java's own unsuffixed default).
    // `1.5f`/`1.5F` -> Float, matching Java's own literal suffix convention --
    // deliberately, since the whole point of adding these is to interop with real
    // Java/Minecraft signatures that already distinguish the two, and a reader coming
    // from a Java signature comment should recognize the literal.
    // Hex literals: `0xABC` -> Int.
    private fun number(c: Char, startLine: Int): Token {
        val start = pos - 1
        if (c == '0' && (peek() == 'x' || peek() == 'X')) {
            pos++ // consume 'x'
            val hexStart = pos
            while (!isAtEnd() && (peek().isDigit() || (peek().lowercaseChar() in 'a'..'f'))) pos++
            val text = src.substring(hexStart, pos)
            // Use toLong(16) then toInt() to handle unsigned 32-bit hex values like 0xFFFFFFFF
            return Token(TokType.INT, text.toLong(16).toInt().toString(), startLine)
        }
        while (!isAtEnd() && peek().isDigit()) pos++
        if (!isAtEnd() && (peek() == 'l' || peek() == 'L')) {
            val text = src.substring(start, pos)
            pos++ // consume 'l'/'L'
            return Token(TokType.LONG, text, startLine)
        }
        if (!isAtEnd() && peek() == '.' && peekNext().isDigit()) {
            pos++ // consume '.'
            while (!isAtEnd() && peek().isDigit()) pos++
            if (!isAtEnd() && (peek() == 'f' || peek() == 'F')) {
                val text = src.substring(start, pos)
                pos++ // consume the suffix
                return Token(TokType.FLOAT, text, startLine)
            }
            return Token(TokType.DOUBLE, src.substring(start, pos), startLine)
        }
        return Token(TokType.INT, src.substring(start, pos), startLine)
    }

    private fun identifier(startLine: Int): Token {
        val start = pos - 1
        while (!isAtEnd() && (peek().isLetterOrDigit() || peek() == '_')) pos++
        val text = src.substring(start, pos)
        val kw = KEYWORDS[text]
        return Token(kw ?: TokType.IDENT, text, startLine)
    }

    // Scans one string literal. No `{` anywhere in it -> exactly the old behavior, a single
    // STRING token (fully backward compatible). Otherwise -> an ISTRING_BEGIN/ISTRING_PART/
    // ISTRING_END token sequence with each `{expr}`'s own real tokens spliced in between,
    // recursively lexed by a fresh `Lexer` over just that substring -- see interpolatedString().
    private fun string(startLine: Int): List<Token> {
        val sb = StringBuilder()
        val parts = mutableListOf<String>()
        val exprToks = mutableListOf<List<Token>>()
        while (!isAtEnd() && peek() != '"') {
            if (peek() == '{') {
                pos++ // consume '{'
                val exprStart = pos
                val exprEnd = findMatchingBrace(startLine)
                val exprSrc = src.substring(exprStart, exprEnd)
                parts += sb.toString()
                sb.clear()
                exprToks += Lexer(exprSrc).tokenize().dropLast(1) // drop that sub-lex's own EOF
                pos = exprEnd + 1 // past the matching '}'
                continue
            }
            var ch = advance()
            if (ch == '\\' && !isAtEnd()) {
                ch = when (val esc = advance()) {
                    'n' -> '\n'
                    't' -> '\t'
                    '"' -> '"'
                    '\\' -> '\\'
                    '{' -> '{'
                    '}' -> '}'
                    else -> esc
                }
            }
            sb.append(ch)
        }
        if (isAtEnd()) throw LexError("Unterminated string at line $startLine")
        advance() // closing quote
        if (parts.isEmpty()) {
            return listOf(Token(TokType.STRING, sb.toString(), startLine))
        }
        parts += sb.toString()
        val out = mutableListOf<Token>()
        out += Token(TokType.ISTRING_BEGIN, "", startLine)
        out += Token(TokType.ISTRING_PART, parts[0], startLine)
        for (i in exprToks.indices) {
            out += exprToks[i]
            out += Token(TokType.ISTRING_PART, parts[i + 1], startLine)
        }
        out += Token(TokType.ISTRING_END, "", startLine)
        return out
    }

    // Called with `pos` just past a `{` opened inside a string literal; returns the index of
    // its matching `}`, tracking nested `{`/`}` depth and skipping over any nested string
    // literal's own contents (so a struct literal or a nested interpolated string inside the
    // `{...}` doesn't confuse the brace count or trip over the outer string's own closing `"`).
    private fun findMatchingBrace(startLine: Int): Int {
        var depth = 1
        while (!isAtEnd()) {
            when (peek()) {
                '{' -> { depth++; pos++ }
                '}' -> { depth--; pos++; if (depth == 0) return pos - 1 }
                '"' -> {
                    pos++
                    while (!isAtEnd() && peek() != '"') {
                        if (peek() == '\\') pos++
                        pos++
                    }
                    if (isAtEnd()) throw LexError("Unterminated string at line $startLine")
                    pos++ // closing quote of the nested string
                }
                '\n' -> { line++; pos++ }
                else -> pos++
            }
        }
        throw LexError("Unterminated '{' interpolation at line $startLine")
    }

    private fun isAtEnd() = pos >= src.length
    private fun peek() = if (isAtEnd()) ' ' else src[pos]
    private fun peekNext() = if (pos + 1 >= src.length) ' ' else src[pos + 1]
    private fun advance() = src[pos++]
    private fun match(expected: Char): Boolean {
        if (isAtEnd() || src[pos] != expected) return false
        pos++
        return true
    }
}
