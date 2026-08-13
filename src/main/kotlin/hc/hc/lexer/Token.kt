package hc.lexer

enum class TokType {
    // literals
    INT, LONG, FLOAT, DOUBLE, STRING, IDENT,
    // string interpolation: "a{x}b{y}c" lexes as ISTRING_BEGIN, ISTRING_PART("a"), <x's own
    // tokens>, ISTRING_PART("b"), <y's own tokens>, ISTRING_PART("c"), ISTRING_END -- literal
    // segments (ISTRING_PART) always outnumber embedded expressions by exactly one, alternating
    // start-to-end. The parser leans on its own expression parser naturally stopping once it
    // hits a token it can't extend a continuation with (see `interpolatedString()`).
    ISTRING_BEGIN, ISTRING_PART, ISTRING_END,
    // keywords
    FN, STRUCT, LET, VAR, IF, ELSE, WHILE, FOR, IN, RETURN, TRUE, FALSE, IMPL, MUT, ARENA,
    INTERFACE, DYN, ENUM, MATCH, BY, SEALED, EXTERN, CLASS, MODULE, PUB, OPEN, EXTEND, STATIC, AS, USE,
    TRY, CATCH, THROW, EXTENDS, OVERRIDE, NULL,
    // symbols
    LPAREN, RPAREN, LBRACE, RBRACE, LBRACKET, RBRACKET, COMMA, COLON, COLONCOLON, ARROW, FATARROW, SEMI, DOT, DOTDOT,
    PLUS, MINUS, STAR, SLASH, PERCENT,
    EQ, EQEQ, BANGEQ, LT, LTEQ, GT, GTEQ, BANG, AMP, AMPAMP, PIPEPIPE, QUESTION,
    EOF
}

data class Token(val type: TokType, val text: String, val line: Int)
