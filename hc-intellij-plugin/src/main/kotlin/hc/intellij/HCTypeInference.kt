package hc.intellij

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace

// Real type inference for arbitrary expressions -- the follow-up `HCTypeChecks.kt`'s own original
// header called out as the natural next step ("this is nowhere near the real compiler's own
// `Checker.kt`... no inference for anything but a literal or an explicitly-typed name"). This file
// closes that gap for a real, useful subset: literals, parenthesized/borrowed/cast expressions,
// unary/binary arithmetic and comparison operators (with numeric widening), string interpolation,
// local variable lookups, and CALL/METHOD_CALL/STATIC_CALL/FIELD_ACCESS/STRUCT_LIT results --
// recursively, so `self.other().total + 1` or `let x = a.get() + b;` are now typed just as
// precisely as an explicit annotation would be.
//
// Same governing principle as every other check in this plugin: `null` means "genuinely unknown,"
// never a guess -- arrays, generics, lambdas, `match`/`if` used as expressions, and anything whose
// receiver/callee itself can't be resolved all stay `null` rather than risk a wrong answer feeding
// a false positive into a check built on top of this. `HCTypeContext` bundles the whole-file maps
// (`collectTopLevelFnSigs`/`collectStructMethodSigs`) so recursive inference calls don't
// re-scan the file on every single sub-expression.
internal data class HCTypeContext(
    val file: PsiFile,
    val topLevelFns: Map<String, HCFnSig>,
    val structMethods: Map<String, List<Pair<String, HCFnSig>>>,
)

private fun isRealExprChild(el: PsiElement): Boolean {
    if (el is PsiWhiteSpace) return false
    return when (el.node?.elementType) {
        HCTokenTypes.LPAREN, HCTokenTypes.RPAREN, HCTokenTypes.OPERATOR, HCTokenTypes.KEYWORD,
        HCTokenTypes.COMMA, HCTokenTypes.LINE_COMMENT, HCTokenTypes.DOC_COMMENT,
        -> false
        else -> true
    }
}

// The real operand(s) of a `PAREN_EXPR`/`BORROW_EXPR`/`UNARY_EXPR`/`CAST_EXPR`/`INSTANCE_OF_EXPR`/
// `BINARY_EXPR` -- everything that isn't punctuation, an operator token, or a `&`/`mut`/`as`/`is`
// keyword. For `BINARY_EXPR` this gives exactly `[left, right]` (its own `OPERATOR` token is
// filtered out, read separately where the operator text itself matters); for the others, exactly
// the one real operand (plus, for `CAST_EXPR`/`INSTANCE_OF_EXPR`, the trailing `TYPE_REF` too).
internal fun realExprChildrenOf(el: PsiElement): List<PsiElement> = directChildren(el).filter { isRealExprChild(it) }

private val NUMERIC_WIDENING_ORDER = listOf("Int", "Long", "Float", "Double")

// Simplified widening: the wider of the two numeric types wins (`Int + Double` -> `Double`).
// `null` if either side isn't one of the four numeric primitives this pass tracks at all.
internal fun widenNumeric(a: String, b: String): String? {
    val ai = NUMERIC_WIDENING_ORDER.indexOf(a)
    val bi = NUMERIC_WIDENING_ORDER.indexOf(b)
    if (ai < 0 || bi < 0) return null
    return NUMERIC_WIDENING_ORDER[maxOf(ai, bi)]
}

internal fun fieldTypeOf(structName: String, fieldName: String, file: PsiFile): String? {
    for (f in filesInScope(file)) {
        for (decl in directChildren(f)) {
            if (decl.node?.elementType != HCElementTypes.STRUCT_DECL || declaredName(decl)?.text != structName) continue
            for (fieldDecl in directChildren(decl).filter { it.node?.elementType == HCElementTypes.FIELD_DECL }) {
                if (declaredName(fieldDecl)?.text != fieldName) continue
                val typeRef = directChildren(fieldDecl).firstOrNull { it.node?.elementType == HCElementTypes.TYPE_REF } ?: return null
                return simpleTypeName(typeRef)
            }
        }
    }
    return null
}

// The ENUM name that owns a bare (unqualified) variant literal (`Some { v: 1 }`, no `Base::`
// prefix) -- only when the variant name is unambiguous across every enum in scope, same "give up
// rather than guess" rule `HCReferences.findUniqueEnumVariant` uses for the same shape elsewhere.
private fun enumNameOwningVariant(variantName: String, file: PsiFile): String? {
    val matches = mutableSetOf<String>()
    for (f in filesInScope(file)) {
        for (decl in directChildren(f).filter { it.node?.elementType == HCElementTypes.ENUM_DECL }) {
            val enumName = declaredName(decl)?.text ?: continue
            if (directChildren(decl).any { it.node?.elementType == HCElementTypes.ENUM_VARIANT && declaredName(it)?.text == variantName }) {
                matches += enumName
            }
        }
    }
    return matches.singleOrNull()
}

// `Name { ... }` / `Base::Variant { ... }` -- the literal's own type IS its (qualified) base name,
// but only trusted once confirmed to actually resolve to a real struct/enum, since a typo'd name
// here is a separate, already-covered concern (`checkStructLiteralFields`'s own "stays silent on
// an unresolvable type name" note) this pass shouldn't compound with a wrong inferred type.
internal fun structLitType(lit: PsiElement, file: PsiFile): String? {
    val idents = directChildren(lit).filter { it.node?.elementType == HCTokenTypes.IDENT }
    val firstName = idents.firstOrNull()?.text ?: return null
    val isQualified = directChildren(lit).any { it.node?.elementType == HCTokenTypes.COLONCOLON }
    if (isQualified) return if (enumDeclByName(firstName, file) != null) firstName else null
    if (enumDeclByName(firstName, file) != null) return null // a plain struct literal never names an enum directly
    resolveStructNameIfReal(firstName, file)?.let { return it }
    return enumNameOwningVariant(firstName, file)
}

private fun resolveStructNameIfReal(name: String, file: PsiFile): String? {
    for (f in filesInScope(file)) {
        for (decl in directChildren(f)) {
            if (decl.node?.elementType == HCElementTypes.STRUCT_DECL && declaredName(decl)?.text == name) return name
        }
    }
    return null
}

internal fun inferExprType(expr: PsiElement, localTypes: Map<String, String>, ctx: HCTypeContext): String? = when (expr.node?.elementType) {
    HCElementTypes.LITERAL_EXPR -> literalExprType(expr)
    HCElementTypes.STRING_INTERP_EXPR -> "String"
    HCElementTypes.PAREN_EXPR, HCElementTypes.BORROW_EXPR ->
        realExprChildrenOf(expr).firstOrNull()?.let { inferExprType(it, localTypes, ctx) }
    HCElementTypes.UNARY_EXPR -> {
        val op = directChildren(expr).firstOrNull { it.node?.elementType == HCTokenTypes.OPERATOR }?.text
        val operandType = realExprChildrenOf(expr).firstOrNull()?.let { inferExprType(it, localTypes, ctx) }
        if (op == "!") operandType?.takeIf { it == "Bool" } else operandType
    }
    HCElementTypes.INSTANCE_OF_EXPR -> "Bool"
    HCElementTypes.CAST_EXPR ->
        realExprChildrenOf(expr).firstOrNull { it.node?.elementType == HCElementTypes.TYPE_REF }?.let { simpleTypeName(it) }
    HCElementTypes.BINARY_EXPR -> {
        val op = directChildren(expr).firstOrNull { it.node?.elementType == HCTokenTypes.OPERATOR }?.text
        val operands = realExprChildrenOf(expr)
        when (op) {
            "&&", "||", "==", "!=", "<", "<=", ">", ">=" -> "Bool"
            "+", "-", "*", "/", "%" -> {
                if (operands.size != 2) null
                else {
                    val left = inferExprType(operands[0], localTypes, ctx)
                    val right = inferExprType(operands[1], localTypes, ctx)
                    if (op == "+" && (left == "String" || right == "String")) "String"
                    else if (left != null && right != null) widenNumeric(left, right)
                    else null
                }
            }
            else -> null
        }
    }
    HCElementTypes.REF_EXPR -> {
        val name = directChildren(expr).firstOrNull { it.node?.elementType == HCTokenTypes.IDENT }?.text
        localTypes[name]
    }
    HCElementTypes.CALL_EXPR -> {
        val callee = directChildren(expr).firstOrNull { it.node?.elementType == HCElementTypes.REF_EXPR }
        val name = callee?.let { declaredName(it)?.text ?: it.text }
        ctx.topLevelFns[name]?.retType
    }
    HCElementTypes.STATIC_CALL_EXPR -> {
        val idents = directChildren(expr).filter { it.node?.elementType == HCTokenTypes.IDENT }
        if (idents.size < 2) null else ctx.structMethods[idents[1].text]?.firstOrNull { it.first == idents[0].text }?.second?.retType
    }
    HCElementTypes.METHOD_CALL_EXPR -> {
        val kids = directChildren(expr)
        val dotIdx = kids.indexOfFirst { it.node?.elementType == HCTokenTypes.DOT }
        val receiver = kids.getOrNull(dotIdx - 1)
        val methodIdent = kids.getOrNull(dotIdx + 1)
        val recvType = receiver?.let { inferExprType(it, localTypes, ctx) }
        if (dotIdx < 0 || recvType == null || methodIdent == null) null
        else ctx.structMethods[methodIdent.text]?.firstOrNull { it.first == recvType }?.second?.retType
    }
    HCElementTypes.FIELD_ACCESS_EXPR -> {
        val kids = directChildren(expr)
        if (kids.any { it.node?.elementType == HCTokenTypes.COLONCOLON }) {
            null
        } else {
            val dotIdx = kids.indexOfFirst { it.node?.elementType == HCTokenTypes.DOT }
            val receiver = kids.getOrNull(dotIdx - 1)
            val fieldIdent = kids.getOrNull(dotIdx + 1)
            val recvType = receiver?.let { inferExprType(it, localTypes, ctx) }
            if (dotIdx < 0 || recvType == null || fieldIdent == null) null else fieldTypeOf(recvType, fieldIdent.text, ctx.file)
        }
    }
    HCElementTypes.STRUCT_LIT_EXPR -> structLitType(expr, ctx.file)
    else -> null
}
