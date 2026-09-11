package hc.intellij

import com.intellij.lang.ASTNode
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.psi.tree.IElementType

// A real recursive-descent parser, mirroring the real compiler's own `Parser.kt` grammar shape
// (same precedence chain, same statement/declaration forms) closely enough to be genuinely useful
// for reformatting/tooltips/hints against real nested structure -- replacing `HCParser`'s earlier
// "flat token list" placeholder (see that class's own now-superseded header). `PsiBuilder.error()`
// is used throughout for anything the grammar can't make sense of, which IntelliJ renders as a
// real red-underline syntax error for free, no separate annotator needed -- this is what makes a
// real parser the natural first step toward "errors soon," even before any actual type checking.
//
// **Scope cuts, disclosed, not hidden** (each is a real, separate follow-up):
// - `@` annotations/directives are consumed generically (any `@name` or `@"string"`, optionally
//   followed by a parenthesized, comma-separated argument list) rather than validated against the
//   real compiler's specific directive set (`@entry`, `@must_use`, `@serializable`, `@dev`).
//
// Everything else originally disclosed here (lambdas, `arena Name[count]` array allocation,
// string interpolation, `Type.class` literals) is now implemented -- see `primary`'s own
// `"|"`/`"||"`/`"arena"` branches, `interpolatedStringExpr`/`HCLexer.lexStringOrInterp`, and
// `callOrPrimary`'s own `.class` branch, respectively.
//
// One earlier "scope cut" turned out not to be real and is worth naming so it doesn't get
// reintroduced: a generic struct/enum literal used directly as an expression (`Box<Int> { x: 1
// }`) does NOT need special disambiguation from `<` as a comparison operator, because the real
// language never writes explicit type arguments at a construction site at all -- every real
// example (`generics.hc`, `showcase.hc`, `interfaces2.hc`, `methods.hc`) constructs a generic
// struct as bare `Box { value: 42 }`, type argument inferred from context, same as this parser's
// ordinary (non-generic) struct-literal path already handles. The real compiler's own `Parser.kt`
// has no generic-literal-vs-comparison special case either (confirmed by its absence) -- this was
// a false assumption carried over from the self-hosted compiler subset's OWN different, stricter
// grammar (`Vec<Int> { ... }`/`Option<Int>::Some { ... }`, explicit type args required there),
// not a real gap in this parser.
object HCPsiParser : PsiParser {
    override fun parse(root: IElementType, b: PsiBuilder): ASTNode {
        val rootMarker = b.mark()
        while (!b.eof()) {
            val before = b.currentOffset
            topLevelItem(b)
            if (b.currentOffset == before && !b.eof()) {
                b.error("unexpected token")
                b.advanceLexer()
            }
        }
        rootMarker.done(root)
        return b.treeBuilt
    }

    // === Helpers ===

    private fun PsiBuilder.atKw(text: String): Boolean = tokenType == HCTokenTypes.KEYWORD && tokenText == text
    private fun PsiBuilder.atIdent(text: String): Boolean = tokenType == HCTokenTypes.IDENT && tokenText == text

    private fun expect(b: PsiBuilder, type: IElementType, what: String): Boolean {
        if (b.tokenType == type) {
            b.advanceLexer()
            return true
        }
        b.error("expected $what")
        return false
    }

    private fun expectKw(b: PsiBuilder, text: String) {
        if (b.atKw(text)) {
            b.advanceLexer()
        } else {
            b.error("expected '$text'")
        }
    }

    private fun expectOp(b: PsiBuilder, text: String) {
        if (b.tokenType == HCTokenTypes.OPERATOR && b.tokenText == text) {
            b.advanceLexer()
        } else {
            b.error("expected '$text'")
        }
    }

    private inline fun node(b: PsiBuilder, type: IElementType, body: () -> Unit) {
        val m = b.mark()
        body()
        m.done(type)
    }

    // Consumes zero or more `@name(...)`/`@"binary.Name"(...)` leading markers -- see this file's
    // own header for why these aren't individually validated.
    private fun leadingAnnotations(b: PsiBuilder) {
        while (b.tokenType == HCTokenTypes.AT) {
            node(b, HCElementTypes.ANNOTATION) {
                b.advanceLexer() // @
                if (b.tokenType == HCTokenTypes.STRING || b.tokenType == HCTokenTypes.IDENT || b.tokenType == HCTokenTypes.KEYWORD) {
                    b.advanceLexer()
                } else {
                    b.error("expected an annotation/directive name after '@'")
                }
                if (b.tokenType == HCTokenTypes.LPAREN) {
                    b.advanceLexer()
                    while (b.tokenType != HCTokenTypes.RPAREN && !b.eof()) {
                        expression(b)
                        if (b.tokenType == HCTokenTypes.COLON) {
                            b.advanceLexer()
                            expression(b)
                        }
                        if (b.tokenType != HCTokenTypes.RPAREN) {
                            if (b.tokenType != HCTokenTypes.COMMA) break
                            b.advanceLexer()
                        }
                    }
                    expect(b, HCTokenTypes.RPAREN, "')'")
                }
            }
        }
    }

    // === Top level ===

    private fun topLevelItem(b: PsiBuilder) {
        leadingAnnotations(b)
        b.atKw("pub").let { if (it) b.advanceLexer() }
        b.atKw("open").let { if (it) b.advanceLexer() }
        when {
            b.atKw("use") -> useDecl(b)
            b.atKw("module") -> moduleDecl(b)
            b.atKw("struct") -> structDecl(b)
            b.atKw("arena") -> arenaStructDecl(b)
            b.atKw("component") -> componentDecl(b)
            b.atKw("system") -> systemDecl(b)
            b.atKw("fn") -> fnDecl(b)
            b.atKw("static") -> staticDecl(b)
            b.atKw("impl") -> implDecl(b)
            b.atKw("interface") || b.atKw("sealed") -> interfaceDecl(b)
            b.atKw("enum") -> enumDecl(b)
            b.atKw("extend") -> extendDecl(b)
            b.atKw("extern") -> externClassOrInterfaceDecl(b)
            b.atKw("override") -> { b.advanceLexer(); fnDecl(b) }
            b.tokenType == HCTokenTypes.DOC_COMMENT -> b.advanceLexer()
            else -> {} // let the caller's zero-progress guard turn this into one error token
        }
    }

    // `use vec;` -- a bare topic name, no path/namespace syntax at all (see `Ast.hc`'s own
    // `Program.uses` header and `Parser.hotc`'s own real `USE` branch, which this mirrors
    // exactly). Real compiler-recognized topics as of this writing: `vec`/`option`/`result`/
    // `registry`/`io`/`phoenix`/`phoenix_virtual`/`ecs`/`math` -- not enforced here at the
    // PARSE level (same "trust it, let a later pass reject an unknown one" leniency this whole
    // file already uses for other names).
    private fun useDecl(b: PsiBuilder) = node(b, HCElementTypes.USE_DECL) {
        expectKw(b, "use")
        expect(b, HCTokenTypes.IDENT, "a stdlib topic name")
        expect(b, HCTokenTypes.SEMI, "';'")
    }

    // `component Name { field: Type, ... }` -- see `Ast.hc`'s own `ComponentDecl` header. Field
    // syntax reuses `fieldList` directly, same shape a plain `struct`'s own fields already use
    // (`Parser.hotc`'s own `component_decl` reuses `struct_fields()` for the identical reason).
    private fun componentDecl(b: PsiBuilder) = node(b, HCElementTypes.COMPONENT_DECL) {
        expectKw(b, "component")
        expect(b, HCTokenTypes.IDENT, "a component name")
        fieldList(b)
    }

    // `system Name { fn run(a: &mut A, b: &B) { ... } }` -- see `Ast.hc`'s own `SystemDecl`
    // header. `run` never declares its own return type (always `Unit`) -- unlike `fnDecl`, no
    // optional `-> Ret` here, matching `Parser.hotc`'s own `system_decl` exactly. `@after(...)`/
    // `@before(...)`/`@profile` (if present) were already consumed generically by `leadingAnnotations`
    // before this ever runs -- same "consumed generically, not individually validated" approach
    // this whole file already takes for every other annotation/directive.
    private fun systemDecl(b: PsiBuilder) = node(b, HCElementTypes.SYSTEM_DECL) {
        expectKw(b, "system")
        expect(b, HCTokenTypes.IDENT, "a system name")
        expect(b, HCTokenTypes.LBRACE, "'{'")
        expectKw(b, "fn")
        expect(b, HCTokenTypes.IDENT, "'run'")
        paramList(b)
        block(b)
        expect(b, HCTokenTypes.RBRACE, "'}'")
    }

    private fun moduleDecl(b: PsiBuilder) = node(b, HCElementTypes.MODULE_DECL) {
        expectKw(b, "module")
        expect(b, HCTokenTypes.IDENT, "a module path")
        while (b.tokenType == HCTokenTypes.DOT) {
            b.advanceLexer()
            expect(b, HCTokenTypes.IDENT, "an identifier")
        }
        expect(b, HCTokenTypes.SEMI, "';'")
    }

    private fun typeParamList(b: PsiBuilder) {
        if (b.tokenType != HCTokenTypes.OPERATOR || b.tokenText != "<") return
        node(b, HCElementTypes.TYPE_PARAM_LIST) {
            b.advanceLexer() // <
            while (b.tokenText != ">" && !b.eof()) {
                expect(b, HCTokenTypes.IDENT, "a type parameter name")
                if (b.tokenType == HCTokenTypes.COLON) {
                    b.advanceLexer()
                    expect(b, HCTokenTypes.IDENT, "a bound")
                    while (b.tokenType == HCTokenTypes.OPERATOR && b.tokenText == "+") {
                        b.advanceLexer()
                        expect(b, HCTokenTypes.IDENT, "a bound")
                    }
                }
                if (b.tokenText != ">") {
                    if (b.tokenType != HCTokenTypes.COMMA) break
                    b.advanceLexer()
                }
            }
            if (b.tokenText == ">") b.advanceLexer() else b.error("expected '>'")
        }
    }

    private fun fieldList(b: PsiBuilder) {
        expect(b, HCTokenTypes.LBRACE, "'{'")
        while (b.tokenType != HCTokenTypes.RBRACE && !b.eof()) {
            val before = b.currentOffset
            node(b, HCElementTypes.FIELD_DECL) {
                expect(b, HCTokenTypes.IDENT, "a field name")
                expect(b, HCTokenTypes.COLON, "':'")
                typeRef(b)
            }
            if (b.tokenType != HCTokenTypes.RBRACE) {
                if (b.tokenType == HCTokenTypes.COMMA) b.advanceLexer() else b.error("expected ',' or '}'")
            }
            if (b.currentOffset == before) { b.error("unexpected token"); b.advanceLexer() }
        }
        expect(b, HCTokenTypes.RBRACE, "'}'")
    }

    private fun structDecl(b: PsiBuilder) = node(b, HCElementTypes.STRUCT_DECL) {
        expectKw(b, "struct")
        expect(b, HCTokenTypes.IDENT, "a struct name")
        typeParamList(b)
        if (b.atKw("extends")) { b.advanceLexer(); expect(b, HCTokenTypes.IDENT, "a superclass name") }
        fieldList(b)
    }

    private fun arenaStructDecl(b: PsiBuilder) = node(b, HCElementTypes.STRUCT_DECL) {
        expectKw(b, "arena")
        expectKw(b, "struct")
        expect(b, HCTokenTypes.IDENT, "a struct name")
        fieldList(b)
    }

    private fun staticDecl(b: PsiBuilder) = node(b, HCElementTypes.STATIC_DECL) {
        expectKw(b, "static")
        expect(b, HCTokenTypes.IDENT, "a static name")
        expect(b, HCTokenTypes.COLON, "':'")
        typeRef(b)
        expectOp(b, "=")
        expression(b)
        expect(b, HCTokenTypes.SEMI, "';'")
    }

    private fun paramList(b: PsiBuilder) = node(b, HCElementTypes.PARAM_LIST) {
        expect(b, HCTokenTypes.LPAREN, "'('")
        while (b.tokenType != HCTokenTypes.RPAREN && !b.eof()) {
            val before = b.currentOffset
            node(b, HCElementTypes.PARAM) {
                if (b.tokenType == HCTokenTypes.OPERATOR && b.tokenText == "&") {
                    b.advanceLexer()
                    if (b.atKw("mut")) b.advanceLexer()
                    if (b.atIdent("self")) b.advanceLexer() else b.error("expected 'self'")
                } else if (b.atIdent("self")) {
                    b.advanceLexer()
                } else {
                    expect(b, HCTokenTypes.IDENT, "a parameter name")
                    expect(b, HCTokenTypes.COLON, "':'")
                    typeRef(b)
                }
            }
            if (b.tokenType != HCTokenTypes.RPAREN) {
                if (b.tokenType == HCTokenTypes.COMMA) b.advanceLexer() else b.error("expected ',' or ')'")
            }
            if (b.currentOffset == before) { b.error("unexpected token"); b.advanceLexer() }
        }
        expect(b, HCTokenTypes.RPAREN, "')'")
    }

    // Explicit `Unit` return type -- needed because this is self-recursive (`[T]`/`Vec<T>`
    // element types call back into `typeRef`), and Kotlin can't infer an expression-bodied
    // function's return type from a body that calls the function being inferred.
    private fun typeRef(b: PsiBuilder): Unit = node(b, HCElementTypes.TYPE_REF) {
        if (b.tokenType == HCTokenTypes.OPERATOR && b.tokenText == "&") {
            b.advanceLexer()
            if (b.atKw("mut")) b.advanceLexer()
        }
        if (b.atKw("dyn")) b.advanceLexer()
        if (b.tokenType == HCTokenTypes.LBRACKET) {
            b.advanceLexer()
            typeRef(b)
            expect(b, HCTokenTypes.RBRACKET, "']'")
        } else {
            expect(b, HCTokenTypes.IDENT, "a type name")
            if (b.tokenType == HCTokenTypes.OPERATOR && b.tokenText == "<") {
                b.advanceLexer()
                while (b.tokenText != ">" && !b.eof()) {
                    typeRef(b)
                    if (b.tokenText != ">") {
                        if (b.tokenType != HCTokenTypes.COMMA) break
                        b.advanceLexer()
                    }
                }
                if (b.tokenText == ">") b.advanceLexer() else b.error("expected '>'")
            }
        }
        if (b.tokenType == HCTokenTypes.OPERATOR && b.tokenText == "?") b.advanceLexer()
    }

    private fun fnDecl(b: PsiBuilder) = node(b, HCElementTypes.FN_DECL) {
        expectKw(b, "fn")
        expect(b, HCTokenTypes.IDENT, "a function name")
        typeParamList(b)
        paramList(b)
        if (b.tokenType == HCTokenTypes.ARROW) {
            b.advanceLexer()
            typeRef(b)
        }
        block(b)
    }

    // `impl [<T>] Name1 [<Args>] [for Name2 [<Args>]] [by field] { methods }` -- `for` makes this
    // an interface implementation (`Name1` the interface, `Name2` the struct); `by field` is
    // delegation (see the real compiler's own `implDecl` for both). A method inside the body CAN
    // start with a leading `static` keyword (`static fn new(...) -> Self { ... }`, the ordinary
    // constructor pattern every stdlib struct uses) -- the real compiler's own `impl_decl`
    // (`selfhost/parser/Parser.hotc`) explicitly branches on `self.check(STATIC)` to route into
    // `static_method_decl()` vs `method_decl()`, confirming this is real, common syntax, not an
    // edge case. A method WITHOUT `static` is still only "static" in the deeper checker sense by
    // not declaring a `self`/`&self`/`&mut self` first parameter (no keyword needed there
    // either) -- but the keyword itself, when present, is real and must be consumed here, same as
    // `externMember`'s own identical `if (b.atKw("static")) b.advanceLexer()` right below.
    private fun implDecl(b: PsiBuilder) = node(b, HCElementTypes.IMPL_DECL) {
        expectKw(b, "impl")
        typeParamList(b)
        expect(b, HCTokenTypes.IDENT, "a struct/interface name")
        typeParamList(b)
        if (b.atKw("for")) {
            b.advanceLexer()
            expect(b, HCTokenTypes.IDENT, "a struct name")
            typeParamList(b)
        }
        if (b.atKw("by")) {
            b.advanceLexer()
            expect(b, HCTokenTypes.IDENT, "a delegate field name")
        }
        expect(b, HCTokenTypes.LBRACE, "'{'")
        while (b.tokenType != HCTokenTypes.RBRACE && !b.eof()) {
            val before = b.currentOffset
            leadingAnnotations(b)
            if (b.atKw("override")) b.advanceLexer()
            if (b.atKw("static")) b.advanceLexer()
            if (b.atKw("fn")) fnDecl(b)
            if (b.currentOffset == before) { b.error("expected a method"); b.advanceLexer() }
        }
        expect(b, HCTokenTypes.RBRACE, "'}'")
    }

    private fun extendDecl(b: PsiBuilder) = node(b, HCElementTypes.EXTEND_DECL) {
        expectKw(b, "extend")
        expect(b, HCTokenTypes.IDENT, "a target type name")
        expect(b, HCTokenTypes.LBRACE, "'{'")
        while (b.tokenType != HCTokenTypes.RBRACE && !b.eof()) {
            val before = b.currentOffset
            if (b.atKw("fn")) fnDecl(b)
            if (b.currentOffset == before) { b.error("expected a method"); b.advanceLexer() }
        }
        expect(b, HCTokenTypes.RBRACE, "'}'")
    }

    private fun enumDecl(b: PsiBuilder) = node(b, HCElementTypes.ENUM_DECL) {
        expectKw(b, "enum")
        expect(b, HCTokenTypes.IDENT, "an enum name")
        typeParamList(b)
        expect(b, HCTokenTypes.LBRACE, "'{'")
        while (b.tokenType != HCTokenTypes.RBRACE && !b.eof()) {
            val before = b.currentOffset
            node(b, HCElementTypes.ENUM_VARIANT) {
                expect(b, HCTokenTypes.IDENT, "a variant name")
                if (b.tokenType == HCTokenTypes.LBRACE) fieldList(b)
            }
            if (b.tokenType != HCTokenTypes.RBRACE) {
                if (b.tokenType == HCTokenTypes.COMMA) b.advanceLexer() else b.error("expected ',' or '}'")
            }
            if (b.currentOffset == before) { b.error("unexpected token"); b.advanceLexer() }
        }
        expect(b, HCTokenTypes.RBRACE, "'}'")
    }

    // `fn name(params) -> Ret;` (required) or `fn name(params) -> Ret { body }` (a default
    // implementation -- real, easy to miss since it looks identical to an ordinary top-level
    // `fn` right up until the `;`-vs-`{` fork at the very end).
    private fun interfaceMethodSig(b: PsiBuilder) = node(b, HCElementTypes.INTERFACE_METHOD_SIG) {
        expectKw(b, "fn")
        expect(b, HCTokenTypes.IDENT, "a method name")
        paramList(b)
        if (b.tokenType == HCTokenTypes.ARROW) { b.advanceLexer(); typeRef(b) }
        if (b.tokenType == HCTokenTypes.LBRACE) block(b) else expect(b, HCTokenTypes.SEMI, "';'")
    }

    private fun interfaceBody(b: PsiBuilder) {
        expect(b, HCTokenTypes.LBRACE, "'{'")
        while (b.tokenType != HCTokenTypes.RBRACE && !b.eof()) {
            val before = b.currentOffset
            interfaceMethodSig(b)
            if (b.currentOffset == before) { b.error("expected a method signature"); b.advanceLexer() }
        }
        expect(b, HCTokenTypes.RBRACE, "'}'")
    }

    private fun interfaceDecl(b: PsiBuilder) = node(b, HCElementTypes.INTERFACE_DECL) {
        if (b.atKw("sealed")) b.advanceLexer()
        expectKw(b, "interface")
        expect(b, HCTokenTypes.IDENT, "an interface name")
        if (b.tokenType == HCTokenTypes.COLON) {
            b.advanceLexer()
            expect(b, HCTokenTypes.IDENT, "an extended interface name")
            while (b.tokenType == HCTokenTypes.COMMA) {
                b.advanceLexer()
                expect(b, HCTokenTypes.IDENT, "an extended interface name")
            }
        }
        interfaceBody(b)
    }

    private fun externClassOrInterfaceDecl(b: PsiBuilder) {
        if (b.lookAheadIsKw(1, "interface")) {
            node(b, HCElementTypes.INTERFACE_DECL) {
                expectKw(b, "extern")
                expectKw(b, "interface")
                expect(b, HCTokenTypes.IDENT, "an interface name")
                expectOp(b, "=")
                expect(b, HCTokenTypes.STRING, "a binary interface name")
                interfaceBody(b)
            }
            return
        }
        node(b, HCElementTypes.EXTERN_CLASS_DECL) {
            expectKw(b, "extern")
            expectKw(b, "class")
            expect(b, HCTokenTypes.IDENT, "an alias name")
            expectOp(b, "=")
            expect(b, HCTokenTypes.STRING, "a binary class name")
            // Optional TRAILING `interface` marker (`extern class Name = "..." interface { }`,
            // e.g. `JCharSequenceWin`/`PhoenixTaskList`) -- changes instance-call dispatch to
            // INVOKEINTERFACE; unrelated to the LEADING `extern interface Name = "..." { }` form
            // above, which is a different declaration kind entirely. Mirrors the real compiler's
            // own `extern_class_decl` (`selfhost/parser/Parser.hotc`), which checks for this
            // right here, before ever looking at what follows for the body.
            if (b.atKw("interface")) b.advanceLexer()
            when {
                b.tokenType == HCTokenTypes.LBRACE -> {
                    b.advanceLexer()
                    while (b.tokenType != HCTokenTypes.RBRACE && !b.eof()) {
                        val before = b.currentOffset
                        externMember(b)
                        if (b.currentOffset == before) { b.error("expected a method/constructor"); b.advanceLexer() }
                    }
                    expect(b, HCTokenTypes.RBRACE, "'}'")
                }
                b.atIdent("use") -> {
                    b.advanceLexer()
                    expect(b, HCTokenTypes.LBRACE, "'{'")
                    while (b.tokenType != HCTokenTypes.RBRACE && !b.eof()) {
                        expect(b, HCTokenTypes.IDENT, "a member name")
                        if (b.tokenType == HCTokenTypes.COMMA) b.advanceLexer() else break
                    }
                    expect(b, HCTokenTypes.RBRACE, "'}'")
                    expect(b, HCTokenTypes.SEMI, "';'")
                }
                else -> expect(b, HCTokenTypes.SEMI, "';'")
            }
        }
    }

    // A member is a FIELD (`[static] NAME: Type;`, e.g. `static GL_DEPTH_TEST: Int;`) whenever the
    // token after an optional leading `static` is NOT `fn` -- mirrors the real compiler's own
    // `is_extern_field_next` (`selfhost/parser/Parser.hotc`) exactly: peek past `static`, check
    // for `fn`, and only THEN commit to a method parse. Missing this (an unconditional `expectKw
    // (b, "fn")` right after `static`) is a real bug that broke every `extern class` with a
    // static field -- which is most of them (`GL11`'s own `GL_COLOR_BUFFER_BIT`, etc.) --
    // producing "expected 'fn'" and cascading errors through the rest of the file.
    private fun externMember(b: PsiBuilder) {
        val isField = if (b.atKw("static")) !b.lookAheadIsKw(1, "fn") else !b.atKw("fn")
        if (isField) {
            node(b, HCElementTypes.EXTERN_FIELD_DECL) {
                if (b.atKw("static")) b.advanceLexer()
                expect(b, HCTokenTypes.IDENT, "a field name")
                expect(b, HCTokenTypes.COLON, "':'")
                typeRef(b)
                expect(b, HCTokenTypes.SEMI, "';'")
            }
            return
        }
        node(b, HCElementTypes.EXTERN_METHOD_DECL) {
            if (b.atKw("static")) b.advanceLexer()
            expectKw(b, "fn")
            expect(b, HCTokenTypes.IDENT, "a method name")
            paramList(b)
            if (b.tokenType == HCTokenTypes.ARROW) { b.advanceLexer(); typeRef(b) }
            expect(b, HCTokenTypes.SEMI, "';'")
        }
    }

    // === Blocks / statements ===

    private fun block(b: PsiBuilder) = node(b, HCElementTypes.BLOCK) {
        expect(b, HCTokenTypes.LBRACE, "'{'")
        while (b.tokenType != HCTokenTypes.RBRACE && !b.eof()) {
            val before = b.currentOffset
            statement(b)
            if (b.currentOffset == before) { b.error("unexpected token"); b.advanceLexer() }
        }
        expect(b, HCTokenTypes.RBRACE, "'}'")
    }

    private fun statement(b: PsiBuilder) {
        when {
            b.atKw("let") || b.atKw("var") -> letStmt(b)
            b.atKw("if") -> ifOrIfLetOrDevStmt(b)
            b.atKw("while") -> whileStmt(b)
            b.atKw("for") -> forStmt(b)
            b.atKw("match") -> matchStmt(b)
            b.atKw("return") -> node(b, HCElementTypes.RETURN_STMT) {
                b.advanceLexer()
                if (b.tokenType != HCTokenTypes.SEMI) expression(b)
                expect(b, HCTokenTypes.SEMI, "';'")
            }
            b.atKw("break") -> node(b, HCElementTypes.BREAK_STMT) { b.advanceLexer(); expect(b, HCTokenTypes.SEMI, "';'") }
            b.atKw("continue") -> node(b, HCElementTypes.CONTINUE_STMT) { b.advanceLexer(); expect(b, HCTokenTypes.SEMI, "';'") }
            b.atKw("try") -> tryStmt(b)
            b.atKw("throw") -> node(b, HCElementTypes.THROW_STMT) { b.advanceLexer(); expression(b); expect(b, HCTokenTypes.SEMI, "';'") }
            b.tokenType == HCTokenTypes.LBRACE -> block(b)
            else -> node(b, HCElementTypes.EXPR_STMT) {
                expression(b)
                expect(b, HCTokenTypes.SEMI, "';'")
            }
        }
    }

    private fun tryStmt(b: PsiBuilder) = node(b, HCElementTypes.TRY_STMT) {
        expectKw(b, "try")
        block(b)
        var any = false
        while (b.atKw("catch")) {
            any = true
            node(b, HCElementTypes.CATCH_CLAUSE) {
                b.advanceLexer()
                expect(b, HCTokenTypes.LPAREN, "'('")
                expect(b, HCTokenTypes.IDENT, "a catch variable name")
                expect(b, HCTokenTypes.COLON, "':'")
                typeRef(b)
                expect(b, HCTokenTypes.RPAREN, "')'")
                block(b)
            }
        }
        if (!any) b.error("'try' needs at least one 'catch' clause")
    }

    private fun letStmt(b: PsiBuilder) = node(b, HCElementTypes.LET_STMT) {
        b.advanceLexer() // let/var
        expect(b, HCTokenTypes.IDENT, "a variable name")
        if (b.tokenType == HCTokenTypes.COLON) { b.advanceLexer(); typeRef(b) }
        expectOp(b, "=")
        expression(b)
        expect(b, HCTokenTypes.SEMI, "';'")
    }

    private fun ifOrIfLetOrDevStmt(b: PsiBuilder) {
        when {
            b.lookAheadIsKw(1, "let") -> ifLetStmt(b)
            b.lookAheadIsKw(1, "dev") -> devIfStmt(b)
            else -> ifStmt(b)
        }
    }

    private fun ifStmt(b: PsiBuilder) = node(b, HCElementTypes.IF_STMT) {
        expectKw(b, "if")
        expression(b)
        block(b)
        if (b.atKw("else")) {
            b.advanceLexer()
            if (b.atKw("if")) ifOrIfLetOrDevStmt(b) else block(b)
        }
    }

    private fun devIfStmt(b: PsiBuilder) = node(b, HCElementTypes.DEV_IF_STMT) {
        expectKw(b, "if")
        expectKw(b, "dev")
        block(b)
        if (b.atKw("else")) {
            b.advanceLexer()
            if (b.atKw("if")) ifOrIfLetOrDevStmt(b) else block(b)
        }
    }

    private fun ifLetStmt(b: PsiBuilder) = node(b, HCElementTypes.IF_LET_STMT) {
        expectKw(b, "if")
        expectKw(b, "let")
        variantPattern(b)
        expectOp(b, "=")
        expression(b)
        block(b)
        if (b.atKw("else")) {
            b.advanceLexer()
            if (b.atKw("if")) ifOrIfLetOrDevStmt(b) else block(b)
        }
    }

    // `Variant`, `Variant::Nested`, `Variant { a, b }`, `Variant { field: binding }`, or `_`
    // (`_` is an ordinary `IDENT` in this lexer -- `_` is a valid Java identifier-start character
    // -- so no special-casing is needed here at all, unlike this file's own first, incorrect
    // attempt which checked for an `OPERATOR` token that `_` never actually lexes as).
    private fun variantPattern(b: PsiBuilder) = node(b, HCElementTypes.VARIANT_PATTERN) {
        expect(b, HCTokenTypes.IDENT, "a variant name")
        if (b.tokenType == HCTokenTypes.COLONCOLON) {
            b.advanceLexer()
            expect(b, HCTokenTypes.IDENT, "a nested variant name")
        }
        if (b.tokenType == HCTokenTypes.LBRACE) {
            b.advanceLexer()
            while (b.tokenType != HCTokenTypes.RBRACE && !b.eof()) {
                expect(b, HCTokenTypes.IDENT, "a field name")
                if (b.tokenType == HCTokenTypes.COLON) { b.advanceLexer(); expect(b, HCTokenTypes.IDENT, "a bind name") }
                if (b.tokenType != HCTokenTypes.RBRACE) {
                    if (b.tokenType == HCTokenTypes.COMMA) b.advanceLexer() else break
                }
            }
            expect(b, HCTokenTypes.RBRACE, "'}'")
        }
    }

    // `for`/`while` bodies (unlike `if`) allow a brace-less single statement -- see the real
    // compiler's own `blockOrSingleStmt` header for why `if` deliberately doesn't get this too.
    // Always wrapped in a `BLOCK` node here either way, so callers/formatting don't need to know
    // which form was actually written.
    private fun blockOrSingleStmt(b: PsiBuilder) {
        if (b.tokenType == HCTokenTypes.LBRACE) {
            block(b)
        } else {
            node(b, HCElementTypes.BLOCK) { statement(b) }
        }
    }

    private fun whileStmt(b: PsiBuilder) = node(b, HCElementTypes.WHILE_STMT) {
        expectKw(b, "while")
        expression(b)
        blockOrSingleStmt(b)
    }

    // `for x in a..b { }` (exclusive) / `for x in a..=b { }` (inclusive) / `for x in arr { }` --
    // `..`/`..=` are NOT part of the general expression precedence chain at all (matching the
    // real compiler's own `forStmt`), only recognized right here, immediately after the loop's
    // start expression.
    private fun forStmt(b: PsiBuilder) = node(b, HCElementTypes.FOR_STMT) {
        expectKw(b, "for")
        expect(b, HCTokenTypes.IDENT, "a loop variable name")
        expectKw(b, "in")
        expression(b)
        if (b.tokenType == HCTokenTypes.DOTDOT || b.tokenType == HCTokenTypes.DOTDOTEQ) {
            b.advanceLexer()
            expression(b)
        }
        blockOrSingleStmt(b)
    }

    private fun matchStmt(b: PsiBuilder) = node(b, HCElementTypes.MATCH_STMT) {
        expectKw(b, "match")
        expression(b)
        expect(b, HCTokenTypes.LBRACE, "'{'")
        while (b.tokenType != HCTokenTypes.RBRACE && !b.eof()) {
            val before = b.currentOffset
            matchArm(b)
            if (b.currentOffset == before) { b.error("expected a match arm"); b.advanceLexer() }
        }
        expect(b, HCTokenTypes.RBRACE, "'}'")
    }

    // A match arm's pattern is either a LITERAL (int/long/string/bool, optionally negative -- see
    // the real compiler's own `literalPattern`) tried first, or a `variantPattern` -- both
    // `matchArm`/`matchExprArm` share this exact dispatch.
    private fun matchPattern(b: PsiBuilder) {
        val isNegativeNumber = b.tokenType == HCTokenTypes.OPERATOR && b.tokenText == "-" &&
            (b.lookAhead(1) == HCTokenTypes.INT || b.lookAhead(1) == HCTokenTypes.LONG)
        if (isNegativeNumber || b.tokenType in LITERAL_TOKENS) {
            node(b, HCElementTypes.LITERAL_EXPR) {
                if (isNegativeNumber) b.advanceLexer()
                b.advanceLexer()
            }
        } else {
            variantPattern(b)
        }
    }

    private fun matchArm(b: PsiBuilder) = node(b, HCElementTypes.MATCH_ARM) {
        matchPattern(b)
        expect(b, HCTokenTypes.FATARROW, "'=>'")
        block(b)
        if (b.tokenType == HCTokenTypes.COMMA) b.advanceLexer()
    }

    // === Expressions ===

    private fun expression(b: PsiBuilder) = assignment(b)

    private fun assignment(b: PsiBuilder) {
        val m = b.mark()
        elvis(b)
        if (b.tokenType == HCTokenTypes.OPERATOR && b.tokenText == "=") {
            b.advanceLexer()
            assignment(b)
            m.done(HCElementTypes.ASSIGN_EXPR)
        } else {
            m.drop()
        }
    }

    // `x ?: default` -- Elvis, one precedence tier below `assignment`, above `logicalOr` --
    // matches `Parser.hotc`'s own `elvis()` exactly. Right-associative (`a ?: b ?: c` is `a ?: (b
    // ?: c)`), same convention `assignment` itself already uses for chained `=`.
    private fun elvis(b: PsiBuilder) {
        val m = b.mark()
        logicalOr(b)
        if (b.tokenType == HCTokenTypes.OPERATOR && b.tokenText == "?" && b.lookAhead(1) == HCTokenTypes.COLON) {
            b.advanceLexer() // ?
            b.advanceLexer() // :
            elvis(b)
            m.done(HCElementTypes.ELVIS_EXPR)
        } else {
            m.drop()
        }
    }

    // Standard IntelliJ left-recursive-rule idiom throughout this section (binary operator chains,
    // `as`/`is` chains, and the call/field/method/index chain below): `marker.precede()` -- called
    // on the STILL-OPEN marker, before its own `done()` -- opens a NEW marker starting at the same
    // offset as the original. Closing the original right after (`marker.done(type)`) wraps
    // everything parsed so far into one node; reassigning `marker` to the preceding marker lets the
    // NEXT iteration wrap that whole node as its own left operand, giving genuine left-associative
    // nesting (`a - b - c` parses as `(a - b) - c`, not one flat 5-child node). The final still-open
    // marker is `drop()`-ped once the loop ends -- either it was never entered (single operand, no
    // wrapper needed) or its own content is already fully captured by the last `done()` call.
    private fun binaryLevel(b: PsiBuilder, ops: Set<String>, next: (PsiBuilder) -> Unit) {
        var marker = b.mark()
        next(b)
        while (b.tokenType == HCTokenTypes.OPERATOR && b.tokenText in ops) {
            b.advanceLexer()
            next(b)
            val precede = marker.precede()
            marker.done(HCElementTypes.BINARY_EXPR)
            marker = precede
        }
        marker.drop()
    }

    private fun logicalOr(b: PsiBuilder) = binaryLevel(b, setOf("||"), ::logicalAnd)
    private fun logicalAnd(b: PsiBuilder) = binaryLevel(b, setOf("&&"), ::bitwiseOr)
    // Bitwise `|`/`&` -- two real, missing precedence levels (between `&&` and `==`, exactly
    // matching the real compiler's own `logical_and`/`bitwise_or`/`bitwise_and`/`equality` chain
    // in `selfhost/parser/Parser.hotc`). Real gap found on `window.hotc`'s own `GL11::glClear(
    // GL11::GL_COLOR_BUFFER_BIT | GL11::GL_DEPTH_BUFFER_BIT)` -- a bare infix `|` in expression
    // position had no precedence level to be recognized at all, so it fell through to "expected
    // ')'" and cascaded. Binary `&` here is unambiguous with the UNARY reference `&expr`/`&mut
    // expr` `unary()` handles below (line ~787 at time of writing): this level only ever fires
    // once a LEFT operand is already parsed and the parser is looking for an infix operator, a
    // position a leading unary `&` never appears in.
    private fun bitwiseOr(b: PsiBuilder) = binaryLevel(b, setOf("|"), ::bitwiseAnd)
    private fun bitwiseAnd(b: PsiBuilder) = binaryLevel(b, setOf("&"), ::equality)
    private fun equality(b: PsiBuilder) = binaryLevel(b, setOf("==", "!="), ::comparison)
    private fun comparison(b: PsiBuilder) = binaryLevel(b, setOf("<", "<=", ">", ">="), ::term)
    private fun term(b: PsiBuilder) = binaryLevel(b, setOf("+", "-"), ::factor)
    private fun factor(b: PsiBuilder) = binaryLevel(b, setOf("*", "/", "%"), ::castExpr)

    private fun castExpr(b: PsiBuilder) {
        var marker = b.mark()
        unary(b)
        while (b.atKw("as") || b.atKw("is")) {
            val kind = if (b.atKw("as")) HCElementTypes.CAST_EXPR else HCElementTypes.INSTANCE_OF_EXPR
            b.advanceLexer()
            typeRef(b)
            val precede = marker.precede()
            marker.done(kind)
            marker = precede
        }
        marker.drop()
    }

    private fun unary(b: PsiBuilder) {
        if (b.tokenType == HCTokenTypes.OPERATOR && (b.tokenText == "!" || b.tokenText == "-")) {
            val m = b.mark()
            b.advanceLexer()
            unary(b)
            m.done(HCElementTypes.UNARY_EXPR)
            return
        }
        if (b.tokenType == HCTokenTypes.OPERATOR && b.tokenText == "&") {
            val m = b.mark()
            b.advanceLexer()
            if (b.atKw("mut")) b.advanceLexer()
            unary(b)
            m.done(HCElementTypes.BORROW_EXPR)
            return
        }
        tryPostfix(b)
    }

    // `expr?` -- the try operator, a tight postfix applying right after any `.field`/`.method()`/
    // `[index]` chain (`callOrPrimary` already resolved) -- matches `Parser.hotc`'s own `try_
    // postfix()` exactly, including the SAME `?:` guard: `x ?: y`'s own `?` belongs to `elvis`
    // (above `assignment`), not here, so a `?` immediately followed by `:` is left alone for
    // `elvis` to consume instead of being wrapped into a dangling `TRY_OP_EXPR`. `x?.y`'s own `?`
    // is ALREADY consumed by `callOrPrimary`'s own postfix loop (the `?` + `.` branch) by the time
    // this loop ever runs, so it never reaches here unconsumed either. `expr??` (double try)
    // parses fine as two nested `TRY_OP_EXPR` nodes -- same "correct by the desugaring rule, just
    // unusual" shape the real compiler's own design doc calls out explicitly.
    private fun tryPostfix(b: PsiBuilder) {
        var marker = b.mark()
        callOrPrimary(b)
        while (b.tokenType == HCTokenTypes.OPERATOR && b.tokenText == "?" && b.lookAhead(1) != HCTokenTypes.COLON) {
            b.advanceLexer()
            val precede = marker.precede()
            marker.done(HCElementTypes.TRY_OP_EXPR)
            marker = precede
        }
        marker.drop()
    }

    // `foo(...)` (a bare call) only ever applies to the VERY FIRST link in the chain, and only
    // when the primary it's wrapping was a bare identifier (`expr !is Ident` is a real parse
    // error in the real compiler too -- "Only named functions can be called") -- every subsequent
    // link is `.field`/`.method(...)`/`[index]`/`.class`, which can repeat freely and can itself
    // follow a call (`foo().bar[0]`). `isBareIdent` tracks whether the accumulated expression is
    // STILL just the original bare name (true only before the first link fires) -- `.class` only
    // makes sense right off a bare type name (`Foo.class`), never off a chained result
    // (`foo().class` is nonsensical), same restriction the real compiler's own `Expr.ClassLit`
    // enforces (`expr !is Expr.Ident` there is a hard parse error, not just a checker warning).
    private fun callOrPrimary(b: PsiBuilder) {
        var marker = b.mark()
        val startedAsIdent = b.tokenType == HCTokenTypes.IDENT
        var isBareIdent = startedAsIdent
        primary(b)
        if (startedAsIdent && b.tokenType == HCTokenTypes.LPAREN) {
            argList(b)
            val precede = marker.precede()
            marker.done(HCElementTypes.CALL_EXPR)
            marker = precede
            isBareIdent = false
        }
        while (true) {
            when {
                b.tokenType == HCTokenTypes.DOT && b.lookAheadIsKw(1, "class") -> {
                    if (!isBareIdent) b.error("'.class' can only follow a bare type name")
                    b.advanceLexer() // .
                    b.advanceLexer() // class
                    val precede = marker.precede()
                    marker.done(HCElementTypes.CLASS_LIT_EXPR)
                    marker = precede
                    isBareIdent = false
                }
                b.tokenType == HCTokenTypes.DOT -> {
                    b.advanceLexer()
                    expect(b, HCTokenTypes.IDENT, "a field/method name")
                    val isCall = b.tokenType == HCTokenTypes.LPAREN
                    if (isCall) argList(b)
                    val precede = marker.precede()
                    marker.done(if (isCall) HCElementTypes.METHOD_CALL_EXPR else HCElementTypes.FIELD_ACCESS_EXPR)
                    marker = precede
                    isBareIdent = false
                }
                b.tokenType == HCTokenTypes.LBRACKET -> {
                    b.advanceLexer()
                    expression(b)
                    expect(b, HCTokenTypes.RBRACKET, "']'")
                    val precede = marker.precede()
                    marker.done(HCElementTypes.INDEX_EXPR)
                    marker = precede
                    isBareIdent = false
                }
                // `x?.field` / `x?.method(args)` -- safe navigation, matches `Parser.hotc`'s own
                // postfix-loop `QUESTION` + `DOT` branch exactly. Consumed HERE, alongside plain
                // `.`, NOT deferred to `tryPostfix`'s own `?` loop -- by the time that loop runs,
                // this branch has already eaten it.
                b.tokenType == HCTokenTypes.OPERATOR && b.tokenText == "?" && b.lookAhead(1) == HCTokenTypes.DOT -> {
                    b.advanceLexer() // ?
                    b.advanceLexer() // .
                    expect(b, HCTokenTypes.IDENT, "a field/method name")
                    val isCall = b.tokenType == HCTokenTypes.LPAREN
                    if (isCall) argList(b)
                    val precede = marker.precede()
                    marker.done(if (isCall) HCElementTypes.SAFE_CALL_EXPR else HCElementTypes.SAFE_FIELD_ACCESS_EXPR)
                    marker = precede
                    isBareIdent = false
                }
                else -> { marker.drop(); return }
            }
        }
    }

    private fun argList(b: PsiBuilder) = node(b, HCElementTypes.ARG_LIST) {
        expect(b, HCTokenTypes.LPAREN, "'('")
        while (b.tokenType != HCTokenTypes.RPAREN && !b.eof()) {
            val before = b.currentOffset
            expression(b)
            if (b.tokenType != HCTokenTypes.RPAREN) {
                if (b.tokenType == HCTokenTypes.COMMA) b.advanceLexer() else break
            }
            if (b.currentOffset == before) { b.error("unexpected token"); b.advanceLexer() }
        }
        expect(b, HCTokenTypes.RPAREN, "')'")
    }

    private fun primary(b: PsiBuilder) {
        when {
            b.tokenType in LITERAL_TOKENS -> node(b, HCElementTypes.LITERAL_EXPR) { b.advanceLexer() }
            b.tokenType == HCTokenTypes.ISTRING_BEGIN -> interpolatedStringExpr(b)
            b.tokenType == HCTokenTypes.LPAREN -> node(b, HCElementTypes.PAREN_EXPR) {
                b.advanceLexer()
                expression(b)
                expect(b, HCTokenTypes.RPAREN, "')'")
            }
            b.tokenType == HCTokenTypes.LBRACKET -> arrayLiteral(b)
            b.atKw("arena") -> node(b, HCElementTypes.CALL_EXPR) {
                b.advanceLexer()
                expect(b, HCTokenTypes.IDENT, "an arena struct name")
                expect(b, HCTokenTypes.LBRACKET, "'['")
                expression(b)
                expect(b, HCTokenTypes.RBRACKET, "']'")
            }
            b.atKw("if") -> node(b, HCElementTypes.IF_EXPR) {
                b.advanceLexer()
                expression(b)
                block(b)
                if (b.atKw("else")) {
                    b.advanceLexer()
                    if (b.atKw("if")) { primary(b) } else block(b)
                } else {
                    b.error("an 'if' used as an expression needs an 'else'")
                }
            }
            b.atKw("match") -> node(b, HCElementTypes.MATCH_EXPR) {
                b.advanceLexer()
                expression(b)
                expect(b, HCTokenTypes.LBRACE, "'{'")
                while (b.tokenType != HCTokenTypes.RBRACE && !b.eof()) {
                    val before = b.currentOffset
                    matchExprArm(b)
                    if (b.currentOffset == before) { b.error("expected a match arm"); b.advanceLexer() }
                }
                expect(b, HCTokenTypes.RBRACE, "'}'")
            }
            // `|| body` (zero params) / `|x, y| body` (one or more) -- both start with the same
            // `|`-shaped token(s) the lexer already gives `||` as ONE `OPERATOR` ("||", not two
            // separate `|`s), so the zero-param case is a single-token check with no ambiguity
            // against `logicalOr`'s own use of `||` (that's a BINARY operator position, reached
            // only after a left operand already parsed -- `primary()` is only ever entered where
            // a NEW expression is starting, same reasoning the real compiler's own header for this
            // exact case gives).
            b.tokenType == HCTokenTypes.OPERATOR && b.tokenText == "||" -> node(b, HCElementTypes.LAMBDA_EXPR) {
                b.advanceLexer()
                expression(b)
            }
            b.tokenType == HCTokenTypes.OPERATOR && b.tokenText == "|" -> node(b, HCElementTypes.LAMBDA_EXPR) {
                b.advanceLexer()
                while (!(b.tokenType == HCTokenTypes.OPERATOR && b.tokenText == "|") && !b.eof()) {
                    expect(b, HCTokenTypes.IDENT, "a lambda parameter name")
                    if (!(b.tokenType == HCTokenTypes.OPERATOR && b.tokenText == "|")) {
                        if (b.tokenType == HCTokenTypes.COMMA) b.advanceLexer() else break
                    }
                }
                if (b.tokenType == HCTokenTypes.OPERATOR && b.tokenText == "|") b.advanceLexer() else b.error("expected '|'")
                expression(b)
            }
            b.tokenType == HCTokenTypes.IDENT -> identLed(b)
            else -> {
                b.error("expected an expression")
            }
        }
    }

    // Same arm grammar as `matchArm` -- each arm's body is still a normal `{ }` block even in
    // expression position (see the real compiler's own `matchExpr` header), NOT a bare expression.
    private fun matchExprArm(b: PsiBuilder) = node(b, HCElementTypes.MATCH_ARM) {
        matchPattern(b)
        expect(b, HCTokenTypes.FATARROW, "'=>'")
        block(b)
        if (b.tokenType == HCTokenTypes.COMMA) b.advanceLexer()
    }

    // `ISTRING_BEGIN ISTRING_PART? LBRACE <expr tokens> RBRACE ISTRING_PART? ... ISTRING_END` --
    // literal segments and embedded expressions were already split out by `HCLexer`'s own
    // `lexStringOrInterp`, which reuses the ordinary `LBRACE`/`RBRACE` token types for each
    // interpolation's own delimiting braces (see that fn's own header) -- this walks that flat
    // shape and re-parses each embedded expression with the ordinary `expression()` entry point,
    // same as the real compiler's own `interpolatedString()`, EXPLICITLY consuming those two
    // brace tokens itself (every token needs some parser rule to consume it; `expression()` has
    // no `LBRACE`-as-primary case at all, so leaving them for it to trip over is a real bug, not
    // a harmless no-op -- found and fixed via `HCParserTest`'s own real-example sweep). Each
    // `ISTRING_PART` is consumed only when the lexer actually produced one -- an empty literal
    // segment (interpolation right at the start/end of the string, or two adjacent `{...}`s with
    // nothing between them) never gets a token at all (see `HCLexer`'s own header for why), so
    // requiring one unconditionally here would misfire on exactly those real, common shapes
    // (`"{x}"`, `"{a}{b}"`).
    private fun interpolatedStringExpr(b: PsiBuilder) = node(b, HCElementTypes.STRING_INTERP_EXPR) {
        expect(b, HCTokenTypes.ISTRING_BEGIN, "the start of an interpolated string")
        if (b.tokenType == HCTokenTypes.ISTRING_PART) b.advanceLexer()
        while (b.tokenType != HCTokenTypes.ISTRING_END && !b.eof()) {
            val before = b.currentOffset
            expect(b, HCTokenTypes.LBRACE, "'{'")
            expression(b)
            expect(b, HCTokenTypes.RBRACE, "'}'")
            if (b.tokenType == HCTokenTypes.ISTRING_PART) b.advanceLexer()
            if (b.currentOffset == before) { b.error("unexpected token"); b.advanceLexer() }
        }
        expect(b, HCTokenTypes.ISTRING_END, "the end of an interpolated string")
    }

    // `[e1, e2, ...]` / `[value; count]` -- both start identically (`[`, then one expression), so
    // this dispatches on whether a `;` or `,`/`]` follows that first expression, same lookahead
    // shape the real parser uses.
    private fun arrayLiteral(b: PsiBuilder) = node(b, HCElementTypes.ARRAY_LIT_EXPR) {
        b.advanceLexer() // [
        if (b.tokenType != HCTokenTypes.RBRACKET) {
            expression(b)
            if (b.tokenType == HCTokenTypes.SEMI) {
                b.advanceLexer()
                expression(b)
            } else {
                while (b.tokenType == HCTokenTypes.COMMA) {
                    b.advanceLexer()
                    if (b.tokenType == HCTokenTypes.RBRACKET) break
                    expression(b)
                }
            }
        }
        expect(b, HCTokenTypes.RBRACKET, "']'")
    }

    // `Ident {` alone is genuinely ambiguous with a bare block (`if x { ... }`'s own `then`
    // branch) -- resolved the same way the real compiler's own `looksLikeStructLiteral` does, by
    // lookahead INSIDE the braces rather than a parser-wide "no struct literals here" mode: only
    // an immediate `}` (empty literal) or `ident :` (a field initializer) right after the `{`
    // counts. This is more precise than a blanket "disabled inside if/while/match/for conditions"
    // flag (this file's own first attempt) -- it only refuses to commit to a struct literal
    // reading in the specific shape that's actually ambiguous, so it stays correct even for a
    // struct literal genuinely used as a condition (`if Flags { ready: true } == other { }`).
    private fun looksLikeStructLiteral(b: PsiBuilder): Boolean {
        val inner = b.lookAhead(1)
        if (inner == HCTokenTypes.RBRACE) return true
        return inner == HCTokenTypes.IDENT && b.lookAhead(2) == HCTokenTypes.COLON
    }

    // Same disambiguation, shifted past a `Base::Variant` prefix -- `b` sits AT the `::` token.
    private fun looksLikeVariantStructLiteral(b: PsiBuilder): Boolean {
        if (b.lookAhead(1) != HCTokenTypes.IDENT || b.lookAhead(2) != HCTokenTypes.LBRACE) return false
        val inner = b.lookAhead(3)
        if (inner == HCTokenTypes.RBRACE) return true
        return inner == HCTokenTypes.IDENT && b.lookAhead(4) == HCTokenTypes.COLON
    }

    private fun structLitFields(b: PsiBuilder) {
        expect(b, HCTokenTypes.LBRACE, "'{'")
        while (b.tokenType != HCTokenTypes.RBRACE && !b.eof()) {
            val before = b.currentOffset
            node(b, HCElementTypes.FIELD_INIT) {
                expect(b, HCTokenTypes.IDENT, "a field name")
                expect(b, HCTokenTypes.COLON, "':'")
                expression(b)
            }
            if (b.tokenType != HCTokenTypes.RBRACE) {
                if (b.tokenType == HCTokenTypes.COMMA) b.advanceLexer() else break
            }
            if (b.currentOffset == before) { b.error("unexpected token"); b.advanceLexer() }
        }
        expect(b, HCTokenTypes.RBRACE, "'}'")
    }

    // An identifier can start (a) `Alias::method(args)` (a static call), (b) `Alias::FIELD` (a
    // static field read -- no call, no braces), (c) `Base::Variant { ... }` (a qualified enum-
    // variant literal), (d) `Name { ... }` (a plain struct/variant literal), or (e) a plain
    // reference -- see the real compiler's own `primary()` `IDENT` branch for the exact same
    // five-way split this mirrors.
    private fun identLed(b: PsiBuilder) {
        val m = b.mark()
        b.advanceLexer() // the identifier itself
        when {
            // `Name<Arg> { ... }` -- a single-type-argument generic struct/enum-variant literal
            // (`Vec<Int> { ... }`). Non-consuming lookahead from the current `<`, same bounded
            // shape `Parser.hotc`'s own `looks_like_generic_lit` checks (`LT IDENT GT LBRACE`,
            // matched here as `lookAhead(1)`/`lookAheadIsOp(2, ">")`/`lookAhead(3)`), so this
            // never misparses `x < y` (a real less-than comparison) as the start of one.
            b.tokenType == HCTokenTypes.OPERATOR && b.tokenText == "<" &&
                b.lookAhead(1) == HCTokenTypes.IDENT && b.lookAheadIsOp(2, ">") && b.lookAhead(3) == HCTokenTypes.LBRACE -> {
                b.advanceLexer() // <
                expect(b, HCTokenTypes.IDENT, "a type argument")
                expectOp(b, ">")
                structLitFields(b)
                m.done(HCElementTypes.STRUCT_LIT_EXPR)
            }
            // `Base<Arg>::Variant { ... }` -- explicitly-qualified generic-enum-variant
            // construction (`Option<Int>::Some { ... }`). Checked before the plain `<Arg>` shape
            // above's own STRUCT_LIT reading would ever get a chance to misfire, matching
            // `Parser.hotc`'s own `looks_like_generic_variant` (`LT IDENT GT COLONCOLON IDENT
            // LBRACE`, 6 tokens from the current `<`).
            b.tokenType == HCTokenTypes.OPERATOR && b.tokenText == "<" &&
                b.lookAhead(1) == HCTokenTypes.IDENT && b.lookAheadIsOp(2, ">") && b.lookAhead(3) == HCTokenTypes.COLONCOLON &&
                b.lookAhead(4) == HCTokenTypes.IDENT && b.lookAhead(5) == HCTokenTypes.LBRACE -> {
                b.advanceLexer() // <
                expect(b, HCTokenTypes.IDENT, "a type argument")
                expectOp(b, ">")
                expect(b, HCTokenTypes.COLONCOLON, "'::'")
                expect(b, HCTokenTypes.IDENT, "a variant name")
                structLitFields(b)
                m.done(HCElementTypes.STRUCT_LIT_EXPR)
            }
            // `Base<Arg1, Arg2>::Variant { ... }` -- the two-type-argument shape (`Result<Int,
            // String>::Ok { ... }`, see `Ast.hc`'s own `EnumDecl.type_param2` header). Mutually
            // exclusive with the one-arg shape above purely by TOKEN SHAPE (position 2 is `,`
            // here, `>` there) -- matches `Parser.hotc`'s own `looks_like_generic_variant2` (`LT
            // IDENT COMMA IDENT GT COLONCOLON IDENT LBRACE`, 8 tokens from the current `<`).
            b.tokenType == HCTokenTypes.OPERATOR && b.tokenText == "<" &&
                b.lookAhead(1) == HCTokenTypes.IDENT && b.lookAhead(2) == HCTokenTypes.COMMA && b.lookAhead(3) == HCTokenTypes.IDENT &&
                b.lookAheadIsOp(4, ">") && b.lookAhead(5) == HCTokenTypes.COLONCOLON &&
                b.lookAhead(6) == HCTokenTypes.IDENT && b.lookAhead(7) == HCTokenTypes.LBRACE -> {
                b.advanceLexer() // <
                expect(b, HCTokenTypes.IDENT, "a type argument")
                expect(b, HCTokenTypes.COMMA, "','")
                expect(b, HCTokenTypes.IDENT, "a type argument")
                expectOp(b, ">")
                expect(b, HCTokenTypes.COLONCOLON, "'::'")
                expect(b, HCTokenTypes.IDENT, "a variant name")
                structLitFields(b)
                m.done(HCElementTypes.STRUCT_LIT_EXPR)
            }
            b.tokenType == HCTokenTypes.COLONCOLON && looksLikeVariantStructLiteral(b) -> {
                b.advanceLexer() // ::
                expect(b, HCTokenTypes.IDENT, "a variant name")
                structLitFields(b)
                m.done(HCElementTypes.STRUCT_LIT_EXPR)
            }
            b.tokenType == HCTokenTypes.COLONCOLON && b.lookAhead(2) == HCTokenTypes.LPAREN -> {
                b.advanceLexer() // ::
                expect(b, HCTokenTypes.IDENT, "a method/constructor name")
                argList(b)
                m.done(HCElementTypes.STATIC_CALL_EXPR)
            }
            b.tokenType == HCTokenTypes.COLONCOLON -> {
                b.advanceLexer() // ::
                expect(b, HCTokenTypes.IDENT, "a static field name")
                m.done(HCElementTypes.FIELD_ACCESS_EXPR)
            }
            b.tokenType == HCTokenTypes.LBRACE && looksLikeStructLiteral(b) -> {
                structLitFields(b)
                m.done(HCElementTypes.STRUCT_LIT_EXPR)
            }
            else -> m.done(HCElementTypes.REF_EXPR)
        }
    }

    private val LITERAL_TOKENS = setOf(
        HCTokenTypes.INT, HCTokenTypes.LONG, HCTokenTypes.FLOAT, HCTokenTypes.DOUBLE,
        HCTokenTypes.STRING, HCTokenTypes.TRUE, HCTokenTypes.FALSE, HCTokenTypes.NULL_KW,
    )

    private fun PsiBuilder.lookAheadIsKw(steps: Int, text: String): Boolean {
        val t = lookAhead(steps)
        if (t != HCTokenTypes.KEYWORD) return false
        val m = mark()
        repeat(steps) { advanceLexer() }
        val matches = atKw(text)
        m.rollbackTo()
        return matches
    }

    // Same idea as `lookAheadIsKw` right above, for an `OPERATOR`-typed token's own TEXT --
    // `lookAhead(steps)` alone only ever gives a TOKEN TYPE, and `<`/`>` share the single
    // `OPERATOR` type here (see `HCTokenTypes.OPERATORS`), so distinguishing "is the token N
    // steps ahead specifically `>`" needs this. Used by `identLed`'s own `<Arg>`/`<Arg1, Arg2>`
    // generic-literal/qualified-variant lookaheads, below.
    private fun PsiBuilder.lookAheadIsOp(steps: Int, text: String): Boolean {
        val t = lookAhead(steps)
        if (t != HCTokenTypes.OPERATOR) return false
        val m = mark()
        repeat(steps) { advanceLexer() }
        val matches = tokenType == HCTokenTypes.OPERATOR && tokenText == text
        m.rollbackTo()
        return matches
    }
}
