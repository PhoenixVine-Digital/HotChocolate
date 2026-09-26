package hc.intellij

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

// "Why is this type inferred?" -- a real provenance TRAIL for `HCTypeInference.kt`'s own
// `inferExprType`, not just its bare `String?` answer. Deliberately a SEPARATE, parallel
// implementation rather than a restructuring of `inferExprType`/`collectLocalVarTypes` themselves
// (both used extensively, by many already-tested checks throughout `HCTypeChecks.kt`) -- mirrors
// this whole plugin's own established precedent (`HCReferences.kt`'s own header: "small,
// deliberately duplicated copies... rather than factoring out a shared internal module") of
// choosing duplication over a shared refactor specifically to keep zero risk of regressing
// existing, working checks. Every branch here mirrors `inferExprType`'s own exactly (same cases,
// same `null`-means-"genuinely unknown" discipline), just additionally explaining ITSELF as it
// goes.
internal data class HCTypeReason(
    val type: String?,
    val anchor: PsiElement,
    val explanation: String,
    val children: List<HCTypeReason> = emptyList(),
)

private fun unknown(expr: PsiElement, why: String): HCTypeReason = HCTypeReason(null, expr, why)

// Mirrors `collectLocalVarTypes`'s own exact scope/order (`self` from the enclosing `impl`, then
// params, then `let`s in document order so a later `let` can depend on an earlier one already
// being in `out`) but keeps a full `HCTypeReason` per name instead of a bare type string.
internal fun localVarTypeReasons(fnDecl: PsiElement, enclosingStruct: String?, ctx: HCTypeContext): Map<String, HCTypeReason> {
    val out = mutableMapOf<String, HCTypeReason>()
    enclosingStruct?.let {
        out["self"] = HCTypeReason(it, fnDecl, "'self' is the receiver of the enclosing 'impl $it' block")
    }
    val paramList = directChildren(fnDecl).firstOrNull { it.node?.elementType == HCElementTypes.PARAM_LIST }
    for (param in paramList?.let { directChildren(it) }.orEmpty().filter { it.node?.elementType == HCElementTypes.PARAM }) {
        val name = declaredName(param)?.text ?: continue
        if (name == "self") continue
        val typeRef = directChildren(param).firstOrNull { it.node?.elementType == HCElementTypes.TYPE_REF } ?: continue
        val ty = baseTypeName(typeRef) ?: continue
        out[name] = HCTypeReason(ty, typeRef, "parameter '$name' is explicitly declared as '$ty'")
    }
    for (letStmt in elementsOfType(fnDecl, HCElementTypes.LET_STMT)) {
        val name = declaredName(letStmt)?.text ?: continue
        val typeRef = directChildren(letStmt).firstOrNull { it.node?.elementType == HCElementTypes.TYPE_REF }
        val explicit = typeRef?.let { baseTypeName(it) }
        if (explicit != null) {
            out[name] = HCTypeReason(explicit, typeRef, "'$name' is explicitly declared as '$explicit'")
            continue
        }
        val initExpr = letInitExprOf(letStmt) ?: continue
        val initReason = inferExprTypeWithReason(initExpr, out, ctx)
        if (initReason.type != null) {
            out[name] = HCTypeReason(
                initReason.type, letStmt,
                "'$name' has no explicit type annotation, so its type is INFERRED from its initializer",
                listOf(initReason),
            )
        }
    }
    return out
}

internal fun inferExprTypeWithReason(expr: PsiElement, localReasons: Map<String, HCTypeReason>, ctx: HCTypeContext): HCTypeReason =
    when (expr.node?.elementType) {
        HCElementTypes.LITERAL_EXPR -> {
            val ty = literalExprType(expr)
            if (ty != null) HCTypeReason(ty, expr, "a literal's own type is always '$ty'") else unknown(expr, "not a literal type this pass recognizes")
        }
        HCElementTypes.STRING_INTERP_EXPR -> HCTypeReason("String", expr, "a string interpolation always produces 'String'")
        HCElementTypes.PAREN_EXPR -> {
            val inner = realExprChildrenOf(expr).firstOrNull()
            if (inner == null) unknown(expr, "empty parenthesized expression")
            else inferExprTypeWithReason(inner, localReasons, ctx).let {
                HCTypeReason(it.type, expr, "a parenthesized expression '(...)' has the same type as its inner expression", listOf(it))
            }
        }
        HCElementTypes.BORROW_EXPR -> {
            val inner = realExprChildrenOf(expr).firstOrNull()
            if (inner == null) unknown(expr, "empty borrow expression")
            else inferExprTypeWithReason(inner, localReasons, ctx).let {
                HCTypeReason(it.type, expr, "a borrow '&expr' has the same type as the borrowed expression", listOf(it))
            }
        }
        HCElementTypes.UNARY_EXPR -> {
            val op = directChildren(expr).firstOrNull { it.node?.elementType == HCTokenTypes.OPERATOR }?.text
            val operand = realExprChildrenOf(expr).firstOrNull()
            if (operand == null) {
                unknown(expr, "no operand found")
            } else {
                val operandReason = inferExprTypeWithReason(operand, localReasons, ctx)
                if (op == "!") {
                    if (operandReason.type == "Bool") {
                        HCTypeReason("Bool", expr, "logical negation '!' requires and produces 'Bool'", listOf(operandReason))
                    } else {
                        HCTypeReason(null, expr, "logical negation '!' requires a 'Bool' operand", listOf(operandReason))
                    }
                } else {
                    HCTypeReason(operandReason.type, expr, "unary '$op' preserves its operand's type", listOf(operandReason))
                }
            }
        }
        HCElementTypes.INSTANCE_OF_EXPR -> HCTypeReason("Bool", expr, "an 'is' type check always produces 'Bool'")
        HCElementTypes.CAST_EXPR -> {
            val typeRef = realExprChildrenOf(expr).firstOrNull { it.node?.elementType == HCElementTypes.TYPE_REF }
            val ty = typeRef?.let { simpleTypeName(it) }
            if (ty != null) HCTypeReason(ty, expr, "an 'as' cast produces exactly the target type written after 'as' ('$ty')")
            else unknown(expr, "the cast's target type couldn't be determined precisely (an array/generic/dyn type)")
        }
        HCElementTypes.BINARY_EXPR -> {
            val op = binaryOpText(expr)
            val operands = realExprChildrenOf(expr)
            when {
                op in setOf("&&", "||", "==", "!=", "<", "<=", ">", ">=") ->
                    HCTypeReason("Bool", expr, "'$op' is a comparison/logical operator, which always produces 'Bool'")
                op in setOf("+", "-", "*", "/", "%") && operands.size == 2 -> {
                    val left = inferExprTypeWithReason(operands[0], localReasons, ctx)
                    val right = inferExprTypeWithReason(operands[1], localReasons, ctx)
                    if (op == "+" && (left.type == "String" || right.type == "String")) {
                        HCTypeReason("String", expr, "'+' with a 'String' on either side concatenates, producing 'String'", listOf(left, right))
                    } else if (left.type != null && right.type != null) {
                        val widened = widenNumeric(left.type, right.type)
                        if (widened != null) {
                            HCTypeReason(widened, expr, "numeric '$op' widens its operands to the wider of '${left.type}' and '${right.type}'", listOf(left, right))
                        } else {
                            HCTypeReason(null, expr, "'${left.type}'/'${right.type}' aren't both numeric primitives this pass widens", listOf(left, right))
                        }
                    } else {
                        HCTypeReason(null, expr, "one operand's type couldn't be determined", listOf(left, right))
                    }
                }
                else -> unknown(expr, "operator '$op' isn't one this pass infers a result type for")
            }
        }
        HCElementTypes.REF_EXPR -> {
            val name = directChildren(expr).firstOrNull { it.node?.elementType == HCTokenTypes.IDENT }?.text
            localReasons[name] ?: unknown(expr, "'$name' isn't a local/param/self with a known type")
        }
        HCElementTypes.CALL_EXPR -> {
            val callee = directChildren(expr).firstOrNull { it.node?.elementType == HCElementTypes.REF_EXPR }
            val name = callee?.let { declaredName(it)?.text ?: it.text }
            val sig = ctx.topLevelFns[name]
            if (sig?.retType != null) HCTypeReason(sig.retType, expr, "calling '$name(...)' produces its declared return type '${sig.retType}'")
            else unknown(expr, "'$name's return type isn't a simple, known type name")
        }
        HCElementTypes.STATIC_CALL_EXPR -> {
            val idents = directChildren(expr).filter { it.node?.elementType == HCTokenTypes.IDENT }
            val sig = if (idents.size < 2) null else ctx.structMethods[idents[1].text]?.firstOrNull { it.first == idents[0].text }?.second
            if (sig?.retType != null) {
                HCTypeReason(sig.retType, expr, "calling the static method '${idents[0].text}::${idents[1].text}(...)' produces its declared return type '${sig.retType}'")
            } else {
                unknown(expr, "this static method's return type couldn't be resolved")
            }
        }
        HCElementTypes.METHOD_CALL_EXPR -> {
            val kids = directChildren(expr)
            val dotIdx = kids.indexOfFirst { it.node?.elementType == HCTokenTypes.DOT }
            val receiver = kids.getOrNull(dotIdx - 1)
            val methodIdent = kids.getOrNull(dotIdx + 1)
            if (dotIdx < 0 || receiver == null || methodIdent == null) {
                unknown(expr, "malformed method call")
            } else {
                val recvReason = inferExprTypeWithReason(receiver, localReasons, ctx)
                val sig = recvReason.type?.let { rt -> ctx.structMethods[methodIdent.text]?.firstOrNull { it.first == rt }?.second }
                if (sig?.retType != null) {
                    HCTypeReason(
                        sig.retType, expr,
                        "calling '.${methodIdent.text}(...)' on a '${recvReason.type}' receiver produces its declared return type '${sig.retType}'",
                        listOf(recvReason),
                    )
                } else {
                    HCTypeReason(null, expr, "the receiver's type or this method's return type couldn't be resolved", listOf(recvReason))
                }
            }
        }
        HCElementTypes.FIELD_ACCESS_EXPR -> {
            val kids = directChildren(expr)
            if (kids.any { it.node?.elementType == HCTokenTypes.COLONCOLON }) {
                unknown(expr, "a qualified 'Type::field' access isn't traced by this pass")
            } else {
                val dotIdx = kids.indexOfFirst { it.node?.elementType == HCTokenTypes.DOT }
                val receiver = kids.getOrNull(dotIdx - 1)
                val fieldIdent = kids.getOrNull(dotIdx + 1)
                if (dotIdx < 0 || receiver == null || fieldIdent == null) {
                    unknown(expr, "malformed field access")
                } else {
                    val recvReason = inferExprTypeWithReason(receiver, localReasons, ctx)
                    val fieldTy = recvReason.type?.let { fieldTypeOf(it, fieldIdent.text, ctx.file) }
                    if (fieldTy != null) {
                        HCTypeReason(
                            fieldTy, expr,
                            "field '.${fieldIdent.text}' on a '${recvReason.type}' is declared as '$fieldTy'",
                            listOf(recvReason),
                        )
                    } else {
                        HCTypeReason(null, expr, "the receiver's type or this field's declared type couldn't be resolved", listOf(recvReason))
                    }
                }
            }
        }
        HCElementTypes.STRUCT_LIT_EXPR -> {
            val ty = structLitType(expr, ctx.file)
            if (ty != null) HCTypeReason(ty, expr, "a struct/variant literal's own type is its (qualified) name '$ty'")
            else unknown(expr, "this literal's name doesn't resolve to a real struct/enum variant")
        }
        else -> unknown(expr, "this expression kind isn't one this pass infers a type for")
    }

// Renders a reason tree as plain, indented text -- the ROOT expression's own type/explanation
// first, then each contributing sub-expression nested one level deeper, recursively. Deliberately
// plain text (not HTML) so the core logic is trivially testable on its own; the intention that
// surfaces this wraps it for display.
internal fun renderTypeReason(reason: HCTypeReason, depth: Int = 0): String {
    val indent = "  ".repeat(depth)
    val typeText = reason.type ?: "unknown"
    val sb = StringBuilder("$indent- $typeText: ${reason.explanation}\n")
    for (child in reason.children) sb.append(renderTypeReason(child, depth + 1))
    return sb.toString()
}

internal fun explainExprType(expr: PsiElement, fnDecl: PsiElement, file: PsiFile): String {
    val topLevelFns = collectTopLevelFnSigs(file)
    val structMethods = collectStructMethodSigs(file)
    val ctx = HCTypeContext(file, topLevelFns, structMethods)
    val enclosingStruct = fnDecl.parent
        ?.takeIf { it.node?.elementType == HCElementTypes.IMPL_DECL }
        ?.let { implTargetStructName(it) }
    val localReasons = localVarTypeReasons(fnDecl, enclosingStruct, ctx)
    return renderTypeReason(inferExprTypeWithReason(expr, localReasons, ctx))
}
