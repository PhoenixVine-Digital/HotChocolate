package hc.parser

import hc.ast.*
import hc.lexer.Token
import hc.lexer.TokType

class ParseError(message: String) : RuntimeException(message)

class Parser(private val tokens: List<Token>) {
    private var pos = 0

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
            val doc = docComment()
            val (annotations, serializable, entry, mustUse, dev) = leadingMarkers()
            val pub = match(TokType.PUB)
            val open = match(TokType.OPEN)
            if (open && !check(TokType.INTERFACE) && !check(TokType.SEALED)) throw err("'open' can only be used on an 'interface'")
            if (annotations.isNotEmpty() && !check(TokType.STRUCT) && !check(TokType.FN)) {
                throw err("annotations can only precede a top-level 'struct' or 'fn'")
            }
            if (serializable && !check(TokType.STRUCT)) {
                throw err("'@serializable' can only precede a top-level 'struct'")
            }
            if (entry != null && !check(TokType.FN)) {
                throw err("'@entry(...)' can only precede a top-level 'fn'")
            }
            if (mustUse && !check(TokType.FN)) {
                throw err("'@must_use' can only precede a top-level 'fn'")
            }
            if (dev && !check(TokType.FN)) {
                throw err("'@dev' can only precede a top-level 'fn'")
            }
            if (doc != null && !check(TokType.STRUCT) && !check(TokType.FN)) {
                throw err("a '///' doc comment can only precede a top-level 'struct' or 'fn'")
            }
            when {
                check(TokType.STRUCT) -> structs += structDecl(pub, annotations, serializable, doc)
                check(TokType.ARENA) -> structs += arenaStructDecl(pub)
                check(TokType.FN) -> fns += fnDecl(pub, annotations, entry, mustUse, doc, dev)
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

    private fun moduleDecl(): String {
        expect(TokType.MODULE)
        val parts = mutableListOf(expect(TokType.IDENT).text)
        while (match(TokType.DOT)) parts += expect(TokType.IDENT).text
        expect(TokType.SEMI)
        return parts.joinToString(".")
    }

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

    private fun externClassDecl(): ExternClassDecl {
        expect(TokType.EXTERN)
        expect(TokType.CLASS)
        val name = expect(TokType.IDENT).text
        expect(TokType.EQ)
        val binaryName = expect(TokType.STRING).text.replace('.', '/')

        val isInterface = match(TokType.INTERFACE)

        if (match(TokType.SEMI)) {
            return ExternClassDecl(name, binaryName, emptyList(), lazyAll = true, isInterface = isInterface)
        }

        if (check(TokType.IDENT) && peek().text == "use") {
            advance()
            expect(TokType.LBRACE)
            val useNames = mutableListOf<String>()
            while (!check(TokType.RBRACE)) {
                useNames += expect(TokType.IDENT).text
                if (!check(TokType.RBRACE)) expect(TokType.COMMA)
            }
            expect(TokType.RBRACE)
            expect(TokType.SEMI)
            return ExternClassDecl(name, binaryName, emptyList(), useNames = useNames, isInterface = isInterface)
        }

        expect(TokType.LBRACE)
        val methods = mutableListOf<ExternMethodDecl>()
        val fields = mutableListOf<ExternFieldDecl>()
        while (!check(TokType.RBRACE)) {
            val isStatic = match(TokType.STATIC)
            if (!check(TokType.FN)) {

                val fline = peek().line
                val fname = expect(TokType.IDENT).text
                expect(TokType.COLON)
                val ftype = typeRef()
                expect(TokType.SEMI)
                fields += ExternFieldDecl(fname, ftype, fline, isStatic)
                continue
            }
            val line = expect(TokType.FN).line
            val mname = expect(TokType.IDENT).text
            val params = paramList()
            var retType: TypeRef? = null
            if (match(TokType.ARROW)) retType = typeRef()
            expect(TokType.SEMI)
            methods += ExternMethodDecl(mname, params, retType, line, isStatic)
        }
        expect(TokType.RBRACE)
        return ExternClassDecl(name, binaryName, methods, fields = fields, isInterface = isInterface)
    }

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

    private fun interfaceMethodDecl(): InterfaceMethodDecl {
        val line = expect(TokType.FN).line
        val name = expect(TokType.IDENT).text
        val params = paramList()
        var retType: TypeRef? = null
        if (match(TokType.ARROW)) retType = typeRef()
        val body = if (check(TokType.LBRACE)) block() else { expect(TokType.SEMI); null }
        return InterfaceMethodDecl(name, params, retType, body, line)
    }

    private fun implDecl(): ImplBlock {
        expect(TokType.IMPL)
        val (typeParams, bounds) = typeParamListWithBounds()
        val name1 = expect(TokType.IDENT).text

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
        while (!check(TokType.RBRACE)) {

            var mustUse = false
            if (match(TokType.AT)) {
                if (!(check(TokType.IDENT) && peek().text == "must_use")) throw err("only '@must_use' is supported before an impl method")
                advance()
                mustUse = true
            }
            methods += fnDecl(mustUse = mustUse)
        }
        expect(TokType.RBRACE)
        return ImplBlock(structName, typeParams, methods, interfaceName, delegateField, bounds)
    }

    private fun structDecl(pub: Boolean = false, annotations: List<AnnotationUse> = emptyList(), serializable: Boolean = false, docComment: DocComment? = null): StructDecl {
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
        return StructDecl(name, fields, typeParams, typeParamBounds = bounds, moduleName = currentModule, visible = pub, superclass = superclass, annotations = annotations, serializable = serializable, docComment = docComment)
    }

    private data class LeadingMarkers(val annotations: List<AnnotationUse>, val serializable: Boolean, val entry: EntryDirective?, val mustUse: Boolean, val dev: Boolean)

    private fun docComment(): DocComment? {
        if (!check(TokType.DOC_COMMENT)) return null
        val startLine = peek().line
        val summary = StringBuilder()
        val params = mutableListOf<Pair<String, String>>()
        var returns: String? = null
        val examples = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val sees = mutableListOf<DocSeeRef>()
        var deprecated: String? = null
        var section = "summary" 
        fun tagBody(text: String, tag: String): String? {
            if (!text.startsWith(tag)) return null
            if (text.length > tag.length && !text[tag.length].isWhitespace()) return null 
            return text.substring(tag.length).trim()
        }
        while (check(TokType.DOC_COMMENT)) {
            val line = peek().line
            val text = advance().text
            val paramBody = tagBody(text, "@param")
            when {
                paramBody != null -> {
                    val sp = paramBody.indexOfFirst { it.isWhitespace() }
                    val pname = if (sp < 0) paramBody else paramBody.substring(0, sp)
                    val ptext = if (sp < 0) "" else paramBody.substring(sp).trim()
                    params += pname to ptext
                    section = "param"
                }
                tagBody(text, "@returns") != null -> { returns = tagBody(text, "@returns"); section = "returns" }
                tagBody(text, "@example") != null -> { examples += tagBody(text, "@example")!!; section = "example" }
                tagBody(text, "@warning") != null -> { warnings += tagBody(text, "@warning")!!; section = "warning" }
                tagBody(text, "@see") != null -> { sees += DocSeeRef(tagBody(text, "@see")!!, line); section = "see" }
                tagBody(text, "@deprecated") != null -> { deprecated = tagBody(text, "@deprecated"); section = "deprecated" }
                else -> when (section) {
                    "summary" -> { if (summary.isNotEmpty()) summary.append(' '); summary.append(text) }
                    "param" -> { val (n, t) = params.last(); params[params.size - 1] = n to (if (t.isEmpty()) text else "$t $text") }
                    "returns" -> returns = if (returns.isNullOrEmpty()) text else "$returns $text"
                    "example" -> examples[examples.size - 1] = "${examples.last()} $text".trim()
                    "warning" -> warnings[warnings.size - 1] = "${warnings.last()} $text".trim()
                    "deprecated" -> deprecated = if (deprecated.isNullOrEmpty()) text else "$deprecated $text"
                    "see" -> {} 
                }
            }
        }
        return DocComment(summary.toString(), params, returns, examples, warnings, sees, deprecated, startLine)
    }

    private fun leadingMarkers(): LeadingMarkers {
        val out = mutableListOf<AnnotationUse>()
        var serializable = false
        var entry: EntryDirective? = null
        var mustUse = false
        var dev = false
        while (check(TokType.AT)) {
            val line = expect(TokType.AT).line
            if (check(TokType.STRING)) {
                val binaryName = advance().text.replace('.', '/')
                val args = mutableListOf<Pair<String, AnnotationValue>>()
                if (match(TokType.LPAREN)) {
                    while (!check(TokType.RPAREN)) {
                        val argName = expect(TokType.IDENT).text
                        expect(TokType.COLON)
                        args += argName to annotationValue()
                        if (!check(TokType.RPAREN)) expect(TokType.COMMA)
                    }
                    expect(TokType.RPAREN)
                }
                out += AnnotationUse(binaryName, args, line)
            } else if (check(TokType.IDENT) && peek().text == "serializable") {
                advance()
                serializable = true
            } else if (check(TokType.IDENT) && peek().text == "must_use") {
                advance()
                mustUse = true
            } else if (check(TokType.DEV)) {
                advance()
                dev = true
            } else if (check(TokType.IDENT) && peek().text == "entry") {
                advance()
                expect(TokType.LPAREN)
                val target = expect(TokType.STRING).text
                val entryArgs = mutableListOf<Pair<String, AnnotationValue>>()
                while (match(TokType.COMMA)) {
                    val argName = expect(TokType.IDENT).text
                    expect(TokType.COLON)
                    entryArgs += argName to annotationValue()
                }
                expect(TokType.RPAREN)
                entry = EntryDirective(target, entryArgs, line)
            } else {
                throw err("expected a quoted annotation name (@\"binary.Name\") or a known compiler directive (@serializable, @must_use, @dev, @entry(\"target\", ...)) after '@'")
            }
        }
        return LeadingMarkers(out, serializable, entry, mustUse, dev)
    }

    private fun annotationValue(): AnnotationValue {

        if (check(TokType.ENUM)) {
            advance()
            expect(TokType.LPAREN)
            val enumBinaryName = expect(TokType.STRING).text.replace('.', '/')
            expect(TokType.COMMA)
            val constName = expect(TokType.STRING).text
            expect(TokType.RPAREN)
            return AnnotationValue.EnumConst(enumBinaryName, constName)
        }
        if (check(TokType.LBRACKET)) {
            advance()
            val values = mutableListOf<AnnotationValue>()
            while (!check(TokType.RBRACKET)) {
                values += annotationValue()
                if (!check(TokType.RBRACKET)) expect(TokType.COMMA)
            }
            expect(TokType.RBRACKET)
            return AnnotationValue.Arr(values)
        }
        return AnnotationValue.Str(expect(TokType.STRING).text)
    }

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

    private fun fnDecl(pub: Boolean = false, annotations: List<AnnotationUse> = emptyList(), entry: EntryDirective? = null, mustUse: Boolean = false, docComment: DocComment? = null, dev: Boolean = false): FnDecl {
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
        return FnDecl(name, params, retType, body, line, typeParams, bounds, currentModule, pub, isOverride, annotations, entry = entry, mustUse = mustUse, docComment = docComment, dev = dev)
    }

    private fun paramList(): List<Param> {
        expect(TokType.LPAREN)
        val params = mutableListOf<Param>()
        while (!check(TokType.RPAREN)) {
            if (check(TokType.AMP) && (peekAt(1).text == "self" || (peekAt(1).type == TokType.MUT && peekAt(2).text == "self"))) {
                advance() 
                val isMut = match(TokType.MUT)
                advance() 
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

    private fun blockOrSingleStmt(): Block =
        if (check(TokType.LBRACE)) block() else Block(listOf(statement()))

    private fun statement(): Stmt {
        return when {
            check(TokType.LET) || check(TokType.VAR) -> letStmt()
            check(TokType.IF) -> ifOrIfLetOrDevStmt()
            check(TokType.WHILE) -> whileStmt()
            check(TokType.FOR) -> forStmt()
            check(TokType.MATCH) -> matchStmt()
            check(TokType.RETURN) -> returnStmt()
            check(TokType.BREAK) -> breakStmt()
            check(TokType.CONTINUE) -> continueStmt()
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
        advance() 
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
            elseB = if (check(TokType.IF)) Block(listOf(ifOrIfLetOrDevStmt())) else block()
        }
        return Stmt.If(cond, thenB, elseB)
    }

    private fun ifOrIfLetOrDevStmt(): Stmt = when (peekAt(1).type) {
        TokType.LET -> ifLetStmt()
        TokType.DEV -> devIfStmt()
        else -> ifStmt()
    }

    private fun devIfStmt(): Stmt {
        val line = expect(TokType.IF).line
        expect(TokType.DEV)
        val thenB = block()
        var elseB: Block? = null
        if (match(TokType.ELSE)) {
            elseB = if (check(TokType.IF)) Block(listOf(ifOrIfLetOrDevStmt())) else block()
        }
        return Stmt.DevIf(thenB, elseB, line)
    }

    private fun ifLetStmt(): Stmt {
        val line = expect(TokType.IF).line
        expect(TokType.LET)
        val (vname, bindings) = variantPattern()
        expect(TokType.EQ)
        val scrutinee = expression()
        val thenB = block()
        val elseArmBody = if (match(TokType.ELSE)) {
            if (check(TokType.IF)) Block(listOf(ifOrIfLetOrDevStmt())) else block()
        } else {
            Block(emptyList())
        }
        val arms = listOf(
            MatchArm(vname, bindings, thenB, line),
            MatchArm(null, emptyList(), elseArmBody, line),
        )
        return Stmt.Match(scrutinee, arms, line)
    }

    private fun literalPattern(): Expr? {
        val t = peek()
        return when {
            t.type == TokType.INT -> { advance(); Expr.IntLit(t.text.toInt()) }
            t.type == TokType.LONG -> { advance(); Expr.LongLit(t.text.toLong()) }
            t.type == TokType.STRING -> { advance(); Expr.StringLit(t.text) }
            t.type == TokType.TRUE -> { advance(); Expr.BoolLit(true) }
            t.type == TokType.FALSE -> { advance(); Expr.BoolLit(false) }
            t.type == TokType.MINUS && peekAt(1).type == TokType.INT -> {
                advance(); val n = advance(); Expr.IntLit(-n.text.toInt())
            }
            t.type == TokType.MINUS && peekAt(1).type == TokType.LONG -> {
                advance(); val n = advance(); Expr.LongLit(-n.text.toLong())
            }
            else -> null
        }
    }

    private fun variantPattern(): Pair<String?, List<String>> {
        var vname: String? = null
        if (match(TokType.IDENT)) {
            vname = tokens[pos - 1].text
            if (vname != "_" && match(TokType.COLONCOLON)) {
                vname += "::" + expect(TokType.IDENT).text
            }
        } else {
            throw err("Expected variant name or '_' in pattern")
        }
        val bindings = mutableListOf<String>()
        if (match(TokType.LBRACE)) {
            while (!check(TokType.RBRACE)) {
                bindings += expect(TokType.IDENT).text
                if (match(TokType.COLON)) {

                    val bindingName = expect(TokType.IDENT).text
                    bindings.removeAt(bindings.size - 1)
                    bindings += bindingName
                }
                if (!check(TokType.RBRACE)) expect(TokType.COMMA)
            }
            expect(TokType.RBRACE)
        }
        return (if (vname == "_") null else vname) to bindings
    }

    private fun whileStmt(): Stmt {
        expect(TokType.WHILE)
        val cond = expression()
        val body = blockOrSingleStmt()
        return Stmt.While(cond, body)
    }

    private fun matchStmt(): Stmt {
        val line = expect(TokType.MATCH).line
        val scrutinee = expression()
        expect(TokType.LBRACE)
        val arms = mutableListOf<MatchArm>()
        while (!check(TokType.RBRACE)) {
            val armLine = peek().line
            val lit = literalPattern()
            val (vname, bindings) = if (lit != null) null to emptyList<String>() else variantPattern()
            expect(TokType.FATARROW)
            val body = block()
            arms += MatchArm(vname, bindings, body, armLine, literal = lit)
        }
        expect(TokType.RBRACE)
        return Stmt.Match(scrutinee, arms, line)
    }

    private fun ifExpr(): Expr {
        val line = expect(TokType.IF).line
        val cond = expression()
        val thenB = block()
        expect(TokType.ELSE)

        val elseB = if (check(TokType.IF)) Block(listOf(Stmt.ExprStmt(ifExpr()))) else block()
        return Expr.If(cond, thenB, elseB, line)
    }

    private fun matchExpr(): Expr {
        val line = expect(TokType.MATCH).line
        val scrutinee = expression()
        expect(TokType.LBRACE)
        val arms = mutableListOf<MatchArm>()
        while (!check(TokType.RBRACE)) {
            val armLine = peek().line
            val lit = literalPattern()
            val (vname, bindings) = if (lit != null) null to emptyList<String>() else variantPattern()
            expect(TokType.FATARROW)
            val body = block()
            arms += MatchArm(vname, bindings, body, armLine, literal = lit)
        }
        expect(TokType.RBRACE)
        return Expr.Match(scrutinee, arms, line)
    }

    private fun forStmt(): Stmt {
        val line = expect(TokType.FOR).line
        val varName = expect(TokType.IDENT).text
        expect(TokType.IN)
        val start = expression()
        val iterable = when {
            match(TokType.DOTDOT) -> Expr.Range(start, expression(), line, inclusive = false)
            match(TokType.DOTDOTEQ) -> Expr.Range(start, expression(), line, inclusive = true)
            else -> start
        }
        val body = blockOrSingleStmt()
        return Stmt.For(varName, iterable, body, line)
    }

    private fun returnStmt(): Stmt {
        val line = peek().line
        expect(TokType.RETURN)
        val e = if (check(TokType.SEMI)) null else expression()
        expect(TokType.SEMI)
        return Stmt.Return(e, line)
    }

    private fun breakStmt(): Stmt {
        val line = expect(TokType.BREAK).line
        expect(TokType.SEMI)
        return Stmt.Break(line)
    }

    private fun continueStmt(): Stmt {
        val line = expect(TokType.CONTINUE).line
        expect(TokType.SEMI)
        return Stmt.Continue(line)
    }

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

    private fun cast(): Expr {
        var expr = unary()
        while (check(TokType.AS) || check(TokType.IS)) {
            if (check(TokType.AS)) {
                val line = advance().line
                val target = typeRef()
                expr = Expr.Cast(expr, target, line)
            } else {
                val line = advance().line
                val target = typeRef()
                expr = Expr.InstanceOf(expr, target, line)
            }
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
                check(TokType.DOT) && peekAt(1).type == TokType.CLASS -> {

                    val line = advance().line 
                    advance() 
                    if (expr !is Expr.Ident) throw err("'.class' can only follow a bare type name")
                    Expr.ClassLit(expr.name, line)
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

    private fun interpolatedString(): Expr {
        val line = expect(TokType.ISTRING_BEGIN).line
        val literals = mutableListOf<String>()
        val exprs = mutableListOf<Expr>()
        literals += expect(TokType.ISTRING_PART).text
        while (!check(TokType.ISTRING_END)) {
            exprs += expression()
            literals += expect(TokType.ISTRING_PART).text
        }
        advance() 
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
            TokType.IF -> ifExpr()
            TokType.MATCH -> matchExpr()

            TokType.PIPEPIPE -> { val line = advance().line; Expr.Lambda(emptyList(), expression(), line) }
            TokType.PIPE -> lambdaExpr()
            TokType.IDENT -> {
                if (peekAt(1).type == TokType.COLONCOLON) {
                    when {

                        peekAt(3).type == TokType.LBRACE && looksLikeVariantStructLiteral() -> structLiteral()
                        peekAt(3).type == TokType.LPAREN -> staticCall()
                        else -> staticFieldGet()
                    }
                } else if (peekAt(1).type == TokType.LBRACE && looksLikeStructLiteral()) {
                    
                    structLiteral()
                } else {
                    advance()
                    Expr.Ident(t.text, t.line)
                }
            }
            else -> throw err("Unexpected token '${t.text}'")
        }
    }

    private fun looksLikeStructLiteral(): Boolean {
        val save = pos
        advance() 
        advance() 
        val result = check(TokType.RBRACE) ||
            (check(TokType.IDENT) && peekAt(1).type == TokType.COLON)
        pos = save
        return result
    }

    private fun looksLikeVariantStructLiteral(): Boolean {
        val save = pos
        advance() 
        advance() 
        advance() 
        advance() 
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

    private fun staticFieldGet(): Expr {
        val typeName = expect(TokType.IDENT).text
        expect(TokType.COLONCOLON)
        val field = expect(TokType.IDENT)
        return Expr.StaticFieldGet(typeName, field.text, field.line)
    }

    private fun lambdaExpr(): Expr {
        val line = expect(TokType.PIPE).line
        val params = mutableListOf<String>()
        while (!check(TokType.PIPE)) {
            params += expect(TokType.IDENT).text
            if (!check(TokType.PIPE)) expect(TokType.COMMA)
        }
        expect(TokType.PIPE)
        return Expr.Lambda(params, expression(), line)
    }

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

    private fun arenaNewExpr(): Expr {
        expect(TokType.ARENA)
        val name = expect(TokType.IDENT).text
        expect(TokType.LBRACKET)
        val count = expression()
        val line = expect(TokType.RBRACKET).line
        return Expr.ArenaNew(name, count, line)
    }

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
