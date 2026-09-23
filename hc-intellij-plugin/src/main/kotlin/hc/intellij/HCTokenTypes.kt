package hc.intellij

import com.intellij.psi.tree.IElementType

// Mirrors the real compiler's own `Token.kt` `TokType` enum closely (same names, same keyword
// set) so anyone cross-referencing the two isn't surprised -- deliberately NOT identical, though:
// string interpolation (`ISTRING_BEGIN`/`ISTRING_PART`/`ISTRING_END`) is collapsed into a single
// STRING token here rather than lexed as an embedded-expression stream. That's a real, disclosed
// scope cut for this first pass -- syntax highlighting/formatting/tooltips don't need to actually
// parse the interpolated expression, just recognize the literal as one colored region; splitting
// it out only matters once real PSI-based navigation/refactoring is on the table.
class HCTokenType(debugName: String) : IElementType(debugName, HCLanguage)

object HCTokenTypes {
    val IDENT = HCTokenType("IDENT")
    val INT = HCTokenType("INT")
    val LONG = HCTokenType("LONG")
    val CHAR = HCTokenType("CHAR")
    val FLOAT = HCTokenType("FLOAT")
    val DOUBLE = HCTokenType("DOUBLE")
    val STRING = HCTokenType("STRING")
    // A non-interpolated string literal (no `{...}`) is still a single `STRING` token, unchanged.
    // An interpolated one (`"a {x} b"`) instead lexes as `ISTRING_BEGIN` (the opening quote),
    // `ISTRING_PART` (each literal segment, possibly zero-length between two adjacent `{...}`s --
    // see `HCLexer`'s own header for why a zero-length segment is simply never emitted rather than
    // becoming an empty token), the embedded expression's own real tokens (recursively lexed,
    // reusing `LBRACE`/`RBRACE` for the delimiting braces themselves), and `ISTRING_END` (the
    // closing quote) -- mirrors the real compiler's own `Lexer.kt` token shape (same three marker
    // names), which returns a `List<Token>` per string scan rather than emitting them one at a
    // time the way this `LexerBase`-constrained lexer has to.
    val ISTRING_BEGIN = HCTokenType("ISTRING_BEGIN")
    val ISTRING_PART = HCTokenType("ISTRING_PART")
    val ISTRING_END = HCTokenType("ISTRING_END")
    val DOC_COMMENT = HCTokenType("DOC_COMMENT")
    val LINE_COMMENT = HCTokenType("LINE_COMMENT")
    val KEYWORD = HCTokenType("KEYWORD")
    val LPAREN = HCTokenType("LPAREN")
    val RPAREN = HCTokenType("RPAREN")
    val LBRACE = HCTokenType("LBRACE")
    val RBRACE = HCTokenType("RBRACE")
    val LBRACKET = HCTokenType("LBRACKET")
    val RBRACKET = HCTokenType("RBRACKET")
    val COMMA = HCTokenType("COMMA")
    val COLON = HCTokenType("COLON")
    val COLONCOLON = HCTokenType("COLONCOLON")
    val ARROW = HCTokenType("ARROW")
    val FATARROW = HCTokenType("FATARROW")
    val SEMI = HCTokenType("SEMI")
    val DOT = HCTokenType("DOT")
    val DOTDOT = HCTokenType("DOTDOT")
    val DOTDOTEQ = HCTokenType("DOTDOTEQ")
    val OPERATOR = HCTokenType("OPERATOR")
    val AT = HCTokenType("AT")
    val BAD_CHARACTER = HCTokenType("BAD_CHARACTER")

    // Real reserved keywords -- see `Token.kt`'s own list. `true`/`false`/`null` are kept
    // separate (`BOOLEAN`/`NULL_KW`) purely so the highlighter can color them like literals
    // instead of plain control-flow keywords, matching how most language plugins treat them.
    val TRUE = HCTokenType("TRUE")
    val FALSE = HCTokenType("FALSE")
    val NULL_KW = HCTokenType("NULL")

    val KEYWORDS: Set<String> = setOf(
        "fn", "struct", "let", "var", "if", "else", "while", "for", "in", "return", "impl", "mut",
        "arena", "interface", "dyn", "enum", "match", "by", "sealed", "extern", "class", "module",
        "pub", "open", "extend", "static", "as", "try", "catch", "throw", "extends", "override",
        "is", "break", "continue", "dev",
        // **Fixed 2026-09-11** -- `use`/`component`/`system` are real reserved keywords in the
        // actual compiler (`Lexer.hotc`'s own `kw.register("use", USE)`/`("component",
        // COMPONENT)`/`("system", SYSTEM)`), but this plugin's own lexer never learned them --
        // every `use <topic>;` import and every ECS `component`/`system` declaration (both real,
        // load-bearing language features, not fringe syntax) tokenized as a plain `IDENT`, fell
        // through `HCPsiParser`'s own top-level dispatch with no matching branch, and got
        // flagged as a real parse error in the IDE for code the actual compiler accepts cleanly.
        "use", "component", "system",
        // `resource Name { ... }` -- a real reserved keyword too (`Lexer.hotc`'s own `kw.register
        // ("resource", RESOURCE)`), added alongside the compiler's own resource-injection feature.
        "resource",
        // **Fixed 2026-09-23** -- `unit`/`parallel`/`sequence`/`typestate`/`state`/`event`/
        // `handle` are all real reserved keywords in the actual compiler (`Lexer.hotc`'s own
        // `kw.register` calls for each), added across several recent language features (units-
        // as-types, `parallel for`, `sequence` coroutines, `typestate`, events/signals) that this
        // plugin's lexer never learned -- every real use of any of them tokenized as a plain
        // `IDENT`, producing the same false "unexpected token" flagging `use`/`component`/
        // `system` had before the earlier fix right above.
        "unit", "parallel", "sequence", "typestate", "state", "event", "handle",
    )

    val SYMBOLS: Map<String, HCTokenType> = mapOf(
        "(" to LPAREN, ")" to RPAREN, "{" to LBRACE, "}" to RBRACE, "[" to LBRACKET, "]" to RBRACKET,
        "," to COMMA, "::" to COLONCOLON, ":" to COLON, "->" to ARROW, "=>" to FATARROW, ";" to SEMI,
        "..=" to DOTDOTEQ, ".." to DOTDOT, "." to DOT, "@" to AT,
    )

    // Multi-char operators must be tried before their single-char prefix (`==` before `=`, `&&`
    // before `&`, ...) -- `HCLexer` walks this list in order for exactly that reason.
    val OPERATORS: List<String> = listOf(
        "==", "!=", "<=", ">=", "&&", "||", "+", "-", "*", "/", "%", "=", "<", ">", "!", "&", "|", "?",
    )
}
