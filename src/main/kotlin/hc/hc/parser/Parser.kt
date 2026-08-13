package hc.parser

import hc.ast.*
import hc.lexer.Token
import hc.lexer.TokType

class ParseError(message: String) : RuntimeException(message)

class Parser(private val tokens: List<Token>) {
    private var pos = 0
    // Set once from an optional leading `module a.b.c;` and stamped onto every top-level decl
    // parsed afterward in this file -- see Program's doc comment for why this is per-file, not
    // a single whole-program value the way `package` used to be.
    private var currentModule: String? = null

    fun parseProgram(): Program {
        currentModule = if (check(TokType.MODULE)) moduleDecl() else null
        val structs = mutableListOf<StructDecl>()
        val fns = mutableListOf<FnDecl>()
        val impls = mutableListOf<ImplBlock>()
        val interfaces = mutableListOf<InterfaceDecl>()
        val enums = mutableListOf<EnumDecl>()
        val externs = mutableListOf<ExternClassDecl>()
        val extends = mutableListOf<ExtendBlock>()
        val statics = mutableListOf<StaticDecl>()
        while (!check(TokType.EOF)) {
            val pub = match(TokType.PUB)
            val open = match(TokType.OPEN)
            if (open && !check(TokType.INTERFACE) && !check(TokType.SEALED)) throw err("'open' can only be used on an 'interface'")
            when {
                check(TokType.STRUCT) -> structs += structDecl(pub)
                check(TokType.ARENA) -> structs += arenaStructDecl(pub)
                check(TokType.FN) -> fns += fnDecl(pub)
                check(TokType.STATIC) -> statics += staticDecl(pub)
                check(TokType.IMPL) -> { if (pub) throw err("'pub' doesn't apply to 'impl' blocks -- mark the struct/interface itself 'pub'"); impls += implDecl() }
                check(TokType.INTERFACE) -> interfaces += interfaceDecl(pub, open)
                check(TokType.SEALED) -> interfaces += interfaceDecl(pub, open)
                check(TokType.ENUM) -> enums += enumDecl(pub)
                check(TokType.EXTEND) -> { if (pub) throw err("'pub' doesn't apply to 'extend' blocks -- their methods are always callable wherever the block itself is visible"); extends += extendDecl() }
                check(TokType.EXTERN) && peekAt(1).type == TokType.INTERFACE -> { if (pub) throw err("'pub' doesn't apply to 'extern interface'"); interfaces += externInterfaceDecl() }
                check(TokType.EXTERN) -> { if (pub) throw err("'pub' doesn't apply to 'extern class'"); externs += externClassDecl() }
                check(TokType.MODULE) -> throw err("'module' can only appear once, at the very top of a file")
                else -> throw err("Expected 'fn', 'struct', 'arena struct', 'static', 'impl', 'interface', 'sealed interface', 'enum', 'extend', 'extern class', or 'extern interface'")
            }
        }
        return Program(structs, fns, impls, interfaces, enums, externs, extends, statics)
    }

    // `[pub] static NAME: Type = initExpr;`
    private fun staticDecl(pub: Boolean = false): StaticDecl {
        val line = expect(TokType.STATIC).line
        val name = expect(TokType.IDENT).text
        expect(TokType.COLON)
        val type = typeRef()
        expect(TokType.EQ)
        val init = expression()
        expect(TokType.SEMI)
        return StaticDecl(name, type, init, line, currentModule, pub)
    }

    // `module a.b.c;` -- optional, must be the very first thing in a file if present.
    private fun moduleDecl(): String {
        expect(TokType.MODULE)
        val parts = mutableListOf(expect(TokType.IDENT).text)
        while (match(TokType.DOT)) parts += expect(TokType.IDENT).text
        expect(TokType.SEMI)
        return parts.joinToString(".")
    }

    // `extend Target { fn newMethod(&self, ...) -> T { body } }` -- see ExtendBlock's doc.
    private fun extendDecl(): ExtendBlock {
        val line = expect(TokType.EXTEND).line
        val targetName = expect(TokType.IDENT).text
        expect(TokType.LBRACE)
        val methods = mutableListOf<FnDecl>()
        while (!check(TokType.RBRACE)) {
            val m = fnDecl(pub = false)
            methods += m
        }
        expect(TokType.RBRACE)
        return ExtendBlock(targetName, methods, currentModule, line)
    }

    // Three forms after the binary name -- see the comment on ExternClassDecl for what each
    // produces and when to reach for it:
    //   `extern class Alias = "java.util.ArrayList" { fn new() -> Self; fn add(&mut self, value: T) -> Bool; }`  (explicit)
    //   `extern class Alias = "java.util.ArrayList" use { new, add, get, size };`                                (reflected, eager)
    //   `extern class Alias = "java.util.ArrayList";`                                                            (reflected, lazy)
    private fun externClassDecl(): ExternClassDecl {
        expect(TokType.EXTERN)
        expect(TokType.CLASS)
        val name = expect(TokType.IDENT).text
        expect(TokType.EQ)
        val binaryName = expect(TokType.STRING).text.replace('.', '/')

        if (match(TokType.SEMI)) {
            return ExternClassDecl(name, binaryName, emptyList(), lazyAll = true)
        }
        if (match(TokType.USE)) {
            expect(TokType.LBRACE)
            val useNames = mutableListOf<String>()
            while (!check(TokType.RBRACE)) {
                useNames += expect(TokType.IDENT).text
                if (!check(TokType.RBRACE)) expect(TokType.COMMA)
            }
            expect(TokType.RBRACE)
            expect(TokType.SEMI)
            return ExternClassDecl(name, binaryName, emptyList(), useNames = useNames)
        }

        expect(TokType.LBRACE)
        val methods = mutableListOf<ExternMethodDecl>()
        while (!check(TokType.RBRACE)) {
            val isStatic = match(TokType.STATIC)
            val line = expect(TokType.FN).line
            val mname = expect(TokType.IDENT).text
            val params = paramList()
            var retType: TypeRef? = null
            if (match(TokType.ARROW)) retType = typeRef()
            expect(TokType.SEMI)
            methods += ExternMethodDecl(mname, params, retType, line, isStatic)
        }
        expect(TokType.RBRACE)
        return ExternClassDecl(name, binaryName, methods)
    }

    // `extern interface Alias = "java.lang.Runnable" { fn run(&self); }` -- a declared, trusted
    // shape for an existing JVM *interface*, so a struct can `impl Alias for Struct { }` it and
    // get handed off to real Java code expecting that interface type. Methods are always
    // required (no body -- there's no interface class here to attach a default method to).
    private fun externInterfaceDecl(): InterfaceDecl {
        expect(TokType.EXTERN)
        expect(TokType.INTERFACE)
        val name = expect(TokType.IDENT).text
        expect(TokType.EQ)
        val binaryName = expect(TokType.STRING).text.replace('.', '/')
        expect(TokType.LBRACE)
        val methods = mutableListOf<InterfaceMethodDecl>()
        while (!check(TokType.RBRACE)) {
            val line = expect(TokType.FN).line
            val mname = expect(TokType.IDENT).text
            val params = paramList()
            var retType: TypeRef? = null
            if (match(TokType.ARROW)) retType = typeRef()
            expect(TokType.SEMI)
            methods += InterfaceMethodDecl(mname, params, retType, null, line)
        }
        expect(TokType.RBRACE)
        return InterfaceDecl(name, methods, externBinaryName = binaryName)
    }

    // `enum Name { Variant { field: Type, ... }, UnitVariant, ... }`
    private fun enumDecl(pub: Boolean = false): EnumDecl {
        expect(TokType.ENUM)
        val name = expect(TokType.IDENT).text
        val (typeParams, bounds) = typeParamListWithBounds()
        expect(TokType.LBRACE)
        val variants = mutableListOf<EnumVariant>()
        while (!check(TokType.RBRACE)) {
            val vname = expect(TokType.IDENT).text
            val fields = mutableListOf<FieldDecl>()
            if (match(TokType.LBRACE)) {
                while (!check(TokType.RBRACE)) {
                    val fname = expect(TokType.IDENT).text
                    expect(TokType.COLON)
                    fields += FieldDecl(fname, typeRef())
                    if (!check(TokType.RBRACE)) expect(TokType.COMMA)
                }
                expect(TokType.RBRACE)
            }
            variants += EnumVariant(vname, fields)
            if (!check(TokType.RBRACE)) expect(TokType.COMMA)
        }
        expect(TokType.RBRACE)
        return EnumDecl(name, variants, typeParams, bounds, currentModule, pub)
    }

    // `[pub] [open] [sealed] interface Name [: Super, Super2] { ... }`
    private fun interfaceDecl(pub: Boolean = false, open: Boolean = false): InterfaceDecl {
        val sealed = match(TokType.SEALED)
        expect(TokType.INTERFACE)
        val name = expect(TokType.IDENT).text
        val extends = mutableListOf<String>()
        if (match(TokType.COLON)) {
            extends += expect(TokType.IDENT).text
            while (match(TokType.COMMA)) extends += expect(TokType.IDENT).text
        }
        expect(TokType.LBRACE)
        val methods = mutableListOf<InterfaceMethodDecl>()
        while (!check(TokType.RBRACE)) methods += interfaceMethodDecl()
        expect(TokType.RBRACE)
        return InterfaceDecl(name, methods, extends, sealed, moduleName = currentModule, visible = pub || open, open = open)
    }

    // `fn name(params) -> Ret;` (required) or `fn name(params) -> Ret { body }` (default).
    private fun interfaceMethodDecl(): InterfaceMethodDecl {
        val line = expect(TokType.FN).line
        val name = expect(TokType.IDENT).text
        val params = paramList()
        var retType: TypeRef? = null
        if (match(TokType.ARROW)) retType = typeRef()
        val body = if (check(TokType.LBRACE)) block() else { expect(TokType.SEMI); null }
        return InterfaceMethodDecl(name, params, retType, body, line)
    }

    // `impl [<T>] Name1 [<...>] [by field] { }` (inherent) or
    // `impl [<T>] Interface for Name1 [<...>] [by field] { }`.
    private fun implDecl(): ImplBlock {
        expect(TokType.IMPL)
        val (typeParams, bounds) = typeParamListWithBounds()
        val name1 = expect(TokType.IDENT).text
        // Optional `<T>` on the (struct or interface) name, e.g. `impl<T> Box<T> { ... }` -- for
        // now an impl's type params always map 1:1 onto the struct's own, so this is just consumed.
        if (match(TokType.LT)) {
            while (!check(TokType.GT)) {
                expect(TokType.IDENT)
                if (!check(TokType.GT)) expect(TokType.COMMA)
            }
            expect(TokType.GT)
        }
        var structName = name1
        var interfaceName: String? = null
        if (match(TokType.FOR)) {
            interfaceName = name1
            structName = expect(TokType.IDENT).text
            if (match(TokType.LT)) {
                while (!check(TokType.GT)) {
                    expect(TokType.IDENT)
                    if (!check(TokType.GT)) expect(TokType.COMMA)
                }
                expect(TokType.GT)
            }
        }
        var delegateField: String? = null
        if (match(TokType.BY)) delegateField = expect(TokType.IDENT).text
        expect(TokType.LBRACE)
        val methods = mutableListOf<FnDecl>()
        while (!check(TokType.RBRACE)) methods += fnDecl()
        expect(TokType.RBRACE)
        return ImplBlock(structName, typeParams, methods, interfaceName, delegateField, bounds)
    }

    private fun structDecl(pub: Boolean = false): StructDecl {
        expect(TokType.STRUCT)
        val name = expect(TokType.IDENT).text
        val (typeParams, bounds) = typeParamListWithBounds()
        var superclass: String? = null
        if (match(TokType.EXTENDS)) superclass = expect(TokType.IDENT).text
        expect(TokType.LBRACE)
        val fields = mutableListOf<FieldDecl>()
        while (!check(TokType.RBRACE)) {
            val fname = expect(TokType.IDENT).text
            expect(TokType.COLON)
            val ftype = typeRef()
            fields += FieldDecl(fname, ftype)
            if (!check(TokType.RBRACE)) expect(TokType.COMMA)
        }
        expect(TokType.RBRACE)
        return StructDecl(name, fields, typeParams, typeParamBounds = bounds, moduleName = currentModule, visible = pub, superclass = superclass)
    }

    // `arena struct Name { field: Int, ... }` -- no type params, fields must be
    // Int/Bool (enforced by the checker; this just parses the plain field list).
    private fun arenaStructDecl(pub: Boolean = false): StructDecl {
        expect(TokType.ARENA)
        expect(TokType.STRUCT)
        val name = expect(TokType.IDENT).text
        expect(TokType.LBRACE)
        val fields = mutableListOf<FieldDecl>()
        while (!check(TokType.RBRACE)) {
            val fname = expect(TokType.IDENT).text
            expect(TokType.COLON)
            val ftype = typeRef()
            fields += FieldDecl(fname, ftype)
            if (!check(TokType.RBRACE)) expect(TokType.COMMA)
        }
        expect(TokType.RBRACE)
        return StructDecl(name, fields, typeParams = emptyList(), isArena = true, moduleName = currentModule, visible = pub)
    }

    // `<T, U: Trait, V: Trait1 + Trait2>` -- names in declaration order, plus any bounds.
    private fun typeParamListWithBounds(): Pair<List<String>, Map<String, List<String>>> {
        if (!match(TokType.LT)) return emptyList<String>() to emptyMap()
        val names = mutableListOf<String>()
        val bounds = mutableMapOf<String, List<String>>()
        while (!check(TokType.GT)) {
            val name = expect(TokType.IDENT).text
            names += name
            if (match(TokType.COLON)) {
                val bs = mutableListOf(expect(TokType.IDENT).text)
                while (match(TokType.PLUS)) bs += expect(TokType.IDENT).text
                bounds[name] = bs
            }
            if (!check(TokType.GT)) expect(TokType.COMMA)
        }
        expect(TokType.GT)
        return names to bounds
    }

    private fun fnDecl(pub: Boolean = false): FnDecl {
        val isOverride = match(TokType.OVERRIDE)
        val line = peek().line
        expect(TokType.FN)
        val name = expect(TokType.IDENT).text
        val (typeParams, bounds) = typeParamListWithBounds()
        val params = paramList()
        var retType: TypeRef? = null
        if (match(TokType.ARROW)) {
            retType = typeRef()
        }
        val body = block()
        return FnDecl(name, params, retType, body, line, typeParams, bounds, currentModule, pub, isOverride)
    }

    // `(self, ...)` / `(&self, ...)` / `(&mut self, ...)` / `(name: Type, ...)`.
    private fun paramList(): List<Param> {
        expect(TokType.LPAREN)
        val params = mutableListOf<Param>()
        while (!check(TokType.RPAREN)) {
            if (check(TokType.AMP) && (peekAt(1).text == "self" || (peekAt(1).type == TokType.MUT && peekAt(2).text == "self"))) {
                advance() // &
                val isMut = match(TokType.MUT)
                advance() // self
                params += Param("self", TypeRef("Self", isRef = true, isMut = isMut))
            } else if (check(TokType.IDENT) && peek().text == "self" && peekAt(1).type != TokType.COLON) {
                advance()
                params += Param("self", TypeRef("Self", isRef = false))
            } else {
                val pname = expect(TokType.IDENT).text
                expect(TokType.COLON)
                val ptype = typeRef()
                params += Param(pname, ptype)
            }
            if (!check(TokType.RPAREN)) expect(TokType.COMMA)
        }
        expect(TokType.RPAREN)
        return params
    }

    private fun typeRef(): TypeRef {
        val isRef = match(TokType.AMP)
        val isMut = isRef && match(TokType.MUT)
        val isDyn = match(TokType.DYN)
        if (match(TokType.LBRACKET)) {
            val elem = typeRef()
            expect(TokType.RBRACKET)
            return TypeRef("Array", isRef, listOf(elem), isMut)
        }
        val t = expect(TokType.IDENT)
        val name = t.text
        val typeArgs = mutableListOf<TypeRef>()
        if (match(TokType.LT)) {
            while (!check(TokType.GT)) {
                typeArgs += typeRef()
                if (!check(TokType.GT)) expect(TokType.COMMA)
            }
            expect(TokType.GT)
        }
        val isNullable = match(TokType.QUESTION)
        return TypeRef(name, isRef, typeArgs, isMut, isDyn, isNullable)
    }

    private fun block(): Block {
        expect(TokType.LBRACE)
        val stmts = mutableListOf<Stmt>()
        while (!check(TokType.RBRACE)) {
            stmts += statement()
        }
        expect(TokType.RBRACE)
        return Block(stmts)
    }

    private fun statement(): Stmt {
        return when {
            check(TokType.LET) || check(TokType.VAR) -> letStmt()
            check(TokType.IF) -> ifStmt()
            check(TokType.WHILE) -> whileStmt()
            check(TokType.FOR) -> forStmt()
            check(TokType.MATCH) -> matchStmt()
            check(TokType.RETURN) -> returnStmt()
            check(TokType.TRY) -> tryStmt()
            check(TokType.THROW) -> throwStmt()
            check(TokType.LBRACE) -> Stmt.Nested(block())
            else -> {
                val e = expression()
                expect(TokType.SEMI)
                Stmt.ExprStmt(e)
            }
        }
    }

    private fun tryStmt(): Stmt {
        val line = expect(TokType.TRY).line
        val tryBlock = block()
        val catches = mutableListOf<CatchClause>()
        while (check(TokType.CATCH)) {
            val cline = expect(TokType.CATCH).line
            expect(TokType.LPAREN)
            val varName = expect(TokType.IDENT).text
            expect(TokType.COLON)
            val excType = typeRef()
            expect(TokType.RPAREN)
            val body = block()
            catches += CatchClause(varName, excType, body, cline)
        }
        if (catches.isEmpty()) throw err("'try' needs at least one 'catch' clause")
        return Stmt.Try(tryBlock, catches, line)
    }

    private fun throwStmt(): Stmt {
        val line = expect(TokType.THROW).line
        val e = expression()
        expect(TokType.SEMI)
        return Stmt.Throw(e, line)
    }

    private fun letStmt(): Stmt {
        val line = peek().line
        val mutable = check(TokType.VAR)
        advance() // consume let/var
        val name = expect(TokType.IDENT).text
        var declType: TypeRef? = null
        if (match(TokType.COLON)) declType = typeRef()
        expect(TokType.EQ)
        val init = expression()
        expect(TokType.SEMI)
        return Stmt.Let(name, mutable, declType, init, line)
    }

    private fun ifStmt(): Stmt {
        expect(TokType.IF)
        val cond = expression()
        val thenB = block()
        var elseB: Block? = null
        if (match(TokType.ELSE)) {
            elseB = if (check(TokType.IF)) Block(listOf(ifStmt())) else block()
        }
        return Stmt.If(cond, thenB, elseB)
    }

    private fun whileStmt(): Stmt {
        expect(TokType.WHILE)
        val cond = expression()
        val body = block()
        return Stmt.While(cond, body)
    }

    // `match expr { Variant { a, b } => { }, Other => { }, _ => { } }`
    private fun matchStmt(): Stmt {
        val line = expect(TokType.MATCH).line
        val scrutinee = expression()
        expect(TokType.LBRACE)
        val arms = mutableListOf<MatchArm>()
        while (!check(TokType.RBRACE)) {
            val armLine = peek().line
            var vname: String? = null
            if (match(TokType.IDENT)) {
                vname = tokens[pos - 1].text
                if (vname != "_" && match(TokType.COLONCOLON)) {
                    vname += "::" + expect(TokType.IDENT).text
                }
            } else {
                throw err("Expected variant name or '_' in match arm")
            }
            val bindings = mutableListOf<String>()
            if (match(TokType.LBRACE)) {
                while (!check(TokType.RBRACE)) {
                    bindings += expect(TokType.IDENT).text
                    if (match(TokType.COLON)) {
                        // Named binding: Variant { field: binding }
                        // For now we just skip the field name and keep the binding name
                        // since our Ast.MatchArm only stores a flat list of binding names
                        // mapped to variant fields by order.
                        val bindingName = expect(TokType.IDENT).text
                        bindings.removeAt(bindings.size - 1)
                        bindings += bindingName
                    }
                    if (!check(TokType.RBRACE)) expect(TokType.COMMA)
                }
                expect(TokType.RBRACE)
            }
            expect(TokType.FATARROW)
            val body = block()
            arms += MatchArm(if (vname == "_") null else vname, bindings, body, armLine)
        }
        expect(TokType.RBRACE)
        return Stmt.Match(scrutinee, arms, line)
    }

    // `for x in a..b { }` or `for x in arr { }`
    private fun forStmt(): Stmt {
        val line = expect(TokType.FOR).line
        val varName = expect(TokType.IDENT).text
        expect(TokType.IN)
        val start = expression()
        val iterable = if (match(TokType.DOTDOT)) {
            val end = expression()
            Expr.Range(start, end, line)
        } else {
            start
        }
        val body = block()
        return Stmt.For(varName, iterable, body, line)
    }

    private fun returnStmt(): Stmt {
        val line = peek().line
        expect(TokType.RETURN)
        val e = if (check(TokType.SEMI)) null else expression()
        expect(TokType.SEMI)
        return Stmt.Return(e, line)
    }

    // ---- expressions (precedence climbing) ----

    private fun expression(): Expr = assignment()

    private fun assignment(): Expr {
        val expr = logicalOr()
        if (check(TokType.EQ)) {
            val line = peek().line
            advance()
            val value = assignment()
            if (expr is Expr.Ident) return Expr.Assign(expr.name, value, line)
            if (expr is Expr.FieldAccess) return Expr.FieldAssign(expr.obj, expr.field, value, line)
            if (expr is Expr.Index) return Expr.IndexAssign(expr.arr, expr.index, value, line)
            throw err("Invalid assignment target")
        }
        return expr
    }

    // `||` binds looser than `&&`, which binds looser than `==`/comparisons -- same relative
    // precedence as every C-descended language. Both are real short-circuit operators in
    // codegen (branch-based, not eager-evaluate-both-sides), which is more than a performance
    // nicety here: `i < len && !arr[i].isEmpty()` relies on the right side never evaluating once
    // `i < len` is false, same as `i < lines.length && !lines[i].trim().isEmpty()` in real
    // ported code.
    private fun logicalOr(): Expr {
        var expr = logicalAnd()
        while (check(TokType.PIPEPIPE)) {
            val line = peek().line
            advance()
            expr = Expr.Binary("||", expr, logicalAnd(), line)
        }
        return expr
    }

    private fun logicalAnd(): Expr {
        var expr = equality()
        while (check(TokType.AMPAMP)) {
            val line = peek().line
            advance()
            expr = Expr.Binary("&&", expr, equality(), line)
        }
        return expr
    }

    private fun equality(): Expr {
        var expr = comparison()
        while (check(TokType.EQEQ) || check(TokType.BANGEQ)) {
            val op = advance().text
            val line = peek().line
            expr = Expr.Binary(op, expr, comparison(), line)
        }
        return expr
    }

    private fun comparison(): Expr {
        var expr = term()
        while (check(TokType.LT) || check(TokType.LTEQ) || check(TokType.GT) || check(TokType.GTEQ)) {
            val op = advance().text
            val line = peek().line
            expr = Expr.Binary(op, expr, term(), line)
        }
        return expr
    }

    private fun term(): Expr {
        var expr = factor()
        while (check(TokType.PLUS) || check(TokType.MINUS)) {
            val op = advance().text
            val line = peek().line
            expr = Expr.Binary(op, expr, factor(), line)
        }
        return expr
    }

    private fun factor(): Expr {
        var expr = cast()
        while (check(TokType.STAR) || check(TokType.SLASH) || check(TokType.PERCENT)) {
            val op = advance().text
            val line = peek().line
            expr = Expr.Binary(op, expr, cast(), line)
        }
        return expr
    }

    // `expr as Type` -- binds tighter than the arithmetic operators (`x as Double / y` casts
    // `x` before dividing, matching Rust's own precedence) but looser than unary (`-x as Float`
    // negates first, then casts). Chainable: `x as Float as Int` reads left to right.
    private fun cast(): Expr {
        var expr = unary()
        while (check(TokType.AS)) {
            val line = advance().line
            val target = typeRef()
            expr = Expr.Cast(expr, target, line)
        }
        return expr
    }

    private fun unary(): Expr {
        val line = peek().line
        if (check(TokType.BANG) || check(TokType.MINUS)) {
            val op = advance().text
            return Expr.Unary(op, unary(), line)
        }
        if (check(TokType.AMP)) {
            advance()
            val isMut = match(TokType.MUT)
            return Expr.Borrow(unary(), isMut)
        }
        return callOrPrimary()
    }

    private fun callOrPrimary(): Expr {
        var expr = primary()
        while (true) {
            expr = when {
                check(TokType.LPAREN) -> {
                    if (expr !is Expr.Ident) throw err("Only named functions can be called")
                    advance()
                    val args = mutableListOf<Expr>()
                    while (!check(TokType.RPAREN)) {
                        args += expression()
                        if (!check(TokType.RPAREN)) expect(TokType.COMMA)
                    }
                    val line = expect(TokType.RPAREN).line
                    Expr.Call(expr.name, args, line)
                }
                check(TokType.DOT) -> {
                    advance()
                    val fname = expect(TokType.IDENT).text
                    if (check(TokType.LPAREN)) {
                        advance()
                        val args = mutableListOf<Expr>()
                        while (!check(TokType.RPAREN)) {
                            args += expression()
                            if (!check(TokType.RPAREN)) expect(TokType.COMMA)
                        }
                        val line = expect(TokType.RPAREN).line
                        Expr.MethodCall(expr, fname, args, line)
                    } else {
                        Expr.FieldAccess(expr, fname, peek().line)
                    }
                }
                check(TokType.LBRACKET) -> {
                    advance()
                    val index = expression()
                    val line = expect(TokType.RBRACKET).line
                    Expr.Index(expr, index, line)
                }
                else -> return expr
            }
        }
    }

    // `ISTRING_BEGIN ISTRING_PART(lit0) <expr0 tokens> ISTRING_PART(lit1) ... ISTRING_END` --
    // literal segments and embedded expressions were already split out by the lexer (see
    // Lexer.string()); this just walks that flat shape and re-parses each embedded expression
    // with the ordinary `expression()` entry point. `expression()` naturally stops the moment it
    // hits a token it can't extend (here, always the next ISTRING_PART/ISTRING_END), so no
    // lookahead or bracket-matching is needed on the parser side at all.
    private fun interpolatedString(): Expr {
        val line = expect(TokType.ISTRING_BEGIN).line
        val literals = mutableListOf<String>()
        val exprs = mutableListOf<Expr>()
        literals += expect(TokType.ISTRING_PART).text
        while (!check(TokType.ISTRING_END)) {
            exprs += expression()
            literals += expect(TokType.ISTRING_PART).text
        }
        advance() // ISTRING_END
        return Expr.StringInterp(literals, exprs, line)
    }

    private fun primary(): Expr {
        val t = peek()
        return when (t.type) {
            TokType.INT -> { advance(); Expr.IntLit(t.text.toInt()) }
            TokType.LONG -> { advance(); Expr.LongLit(t.text.toLong()) }
            TokType.FLOAT -> { advance(); Expr.FloatLit(t.text.toFloat()) }
            TokType.DOUBLE -> { advance(); Expr.DoubleLit(t.text.toDouble()) }
            TokType.STRING -> { advance(); Expr.StringLit(t.text) }
            TokType.ISTRING_BEGIN -> interpolatedString()
            TokType.TRUE -> { advance(); Expr.BoolLit(true) }
            TokType.FALSE -> { advance(); Expr.BoolLit(false) }
            TokType.NULL -> { advance(); Expr.NullLit() }
            TokType.LPAREN -> { advance(); val e = expression(); expect(TokType.RPAREN); e }
            TokType.LBRACKET -> arrayLiteralOrRepeat()
            TokType.ARENA -> arenaNewExpr()
            TokType.IDENT -> {
                if (peekAt(1).type == TokType.COLONCOLON) {
                    if (peekAt(3).type == TokType.LBRACE) {
                        structLiteral()
                    } else {
                        staticCall()
                    }
                } else if (peekAt(1).type == TokType.LBRACE && looksLikeStructLiteral()) {
                    // lookahead for struct literal: Ident { ... }
                    structLiteral()
                } else {
                    advance()
                    Expr.Ident(t.text, t.line)
                }
            }
            else -> throw err("Unexpected token '${t.text}'")
        }
    }

    // Disambiguate `Ident {` as a struct literal only when followed by `ident :` inside,
    // or an immediate `}` (empty struct). Prevents swallowing `if x {`.
    private fun looksLikeStructLiteral(): Boolean {
        val save = pos
        advance() // ident
        advance() // lbrace
        val result = check(TokType.RBRACE) ||
            (check(TokType.IDENT) && peekAt(1).type == TokType.COLON)
        pos = save
        return result
    }

    private fun structLiteral(): Expr {
        val name = if (peekAt(1).type == TokType.COLONCOLON) {
            val base = expect(TokType.IDENT).text
            expect(TokType.COLONCOLON)
            val variant = expect(TokType.IDENT).text
            base + "::" + variant
        } else {
            expect(TokType.IDENT).text
        }
        val line = peek().line
        expect(TokType.LBRACE)
        val fields = mutableListOf<Pair<String, Expr>>()
        while (!check(TokType.RBRACE)) {
            val fname = expect(TokType.IDENT).text
            expect(TokType.COLON)
            val fval = expression()
            fields += fname to fval
            if (!check(TokType.RBRACE)) expect(TokType.COMMA)
        }
        expect(TokType.RBRACE)
        return Expr.StructLit(name, fields, line)
    }

    // `TypeName::method(args)` -- an extern class's constructor (`method == "new"`) or static method.
    private fun staticCall(): Expr {
        val typeName = expect(TokType.IDENT).text
        expect(TokType.COLONCOLON)
        val method = expect(TokType.IDENT).text
        expect(TokType.LPAREN)
        val args = mutableListOf<Expr>()
        while (!check(TokType.RPAREN)) {
            args += expression()
            if (!check(TokType.RPAREN)) expect(TokType.COMMA)
        }
        val line = expect(TokType.RPAREN).line
        return Expr.StaticCall(typeName, method, args, line)
    }

    // `[e1, e2, e3]` (literal) or `[value; count]` (repeated value).
    private fun arrayLiteralOrRepeat(): Expr {
        val line = expect(TokType.LBRACKET).line
        val first = expression()
        if (match(TokType.SEMI)) {
            val count = expression()
            expect(TokType.RBRACKET)
            return Expr.ArrayRepeat(first, count, line)
        }
        val elements = mutableListOf(first)
        while (match(TokType.COMMA)) {
            if (check(TokType.RBRACKET)) break
            elements += expression()
        }
        expect(TokType.RBRACKET)
        return Expr.ArrayLit(elements, line)
    }

    // `arena Particle[count]`: allocates an off-heap buffer.
    private fun arenaNewExpr(): Expr {
        expect(TokType.ARENA)
        val name = expect(TokType.IDENT).text
        expect(TokType.LBRACKET)
        val count = expression()
        val line = expect(TokType.RBRACKET).line
        return Expr.ArenaNew(name, count, line)
    }

    // ---- helpers ----

    private fun peek() = tokens[pos]
    private fun peekAt(offset: Int) = tokens[minOf(pos + offset, tokens.size - 1)]
    private fun check(type: TokType) = peek().type == type
    private fun advance(): Token { val t = tokens[pos]; if (pos < tokens.size - 1) pos++; return t }
    private fun match(type: TokType): Boolean { if (check(type)) { advance(); return true }; return false }
    private fun expect(type: TokType): Token {
        if (!check(type)) throw err("Expected $type but found '${peek().text}'")
        return advance()
    }
    private fun err(msg: String) = ParseError("$msg (line ${peek().line})")
}
