package hc.intellij

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil

// The first slice of real TYPE checking (as opposed to `HCAnnotator`'s earlier name-resolution-
// only checks) -- deliberately narrow, chosen specifically to stay safe without a real type
// inferencer: argument COUNTS (no type inference needed at all, just counting -- see this file's
// own header on each check for why each is precise or deliberately conservative), plus type
// mismatches ONLY where both sides are confidently known (an explicit `let x: T = ...` type
// annotation, a literal's own unambiguous type, or a param's own explicit declared type) --
// arrays, generics, `dyn`, interfaces, and extern classes are all deliberately skipped (returns
// `null` = "unknown, don't guess" from `simpleTypeName`) rather than risking a wrong call on real,
// valid code. A real, disclosed follow-up: this is nowhere near the real compiler's own
// `Checker.kt` (no generics resolution, no interface/superclass method lookup, no flow-sensitive
// narrowing, no inference for anything but a literal or an explicitly-typed name) -- it only ever
// flags something when it's genuinely confident, same principle `HCAnnotator`'s own undefined-
// reference check already established.
internal data class HCFnSig(val paramTypes: List<String?>, val retType: String?, val nameAnchor: PsiElement)

// `directChildren`/`elementsOfType`/`declaredName` reused from `HCReferences.kt` (widened to
// `internal` specifically for this kind of cross-file reuse once a fourth file -- the completion
// contributor -- needed the exact same tree-walking primitives; see that file's own header).

private fun error(holder: AnnotationHolder, anchor: PsiElement, message: String) {
    holder.newAnnotation(HighlightSeverity.ERROR, message).range(anchor).create()
}

private fun errorWithFix(holder: AnnotationHolder, anchor: PsiElement, message: String, fix: IntentionAction) {
    holder.newAnnotation(HighlightSeverity.ERROR, message).range(anchor).withFix(fix).create()
}

private fun warn(holder: AnnotationHolder, anchor: PsiElement, message: String) {
    holder.newAnnotation(HighlightSeverity.WARNING, message).range(anchor).create()
}

// Only a BARE type name (`Int`, `Foo`, optionally `&`-prefixed/`?`-suffixed/`dyn`-qualified) --
// arrays (`[T]`) and generics (`Vec<T>`) return `null` (unknown, skip) rather than attempting
// element-wise/type-argument comparison this pass doesn't do.
internal fun simpleTypeName(typeRef: PsiElement): String? {
    val kids = directChildren(typeRef)
    if (kids.any { it.node?.elementType == HCTokenTypes.LBRACKET }) return null
    if (kids.any { it.node?.elementType == HCTokenTypes.OPERATOR && it.text == "<" }) return null
    // `&dyn Interface` accepts ANY struct implementing that interface, not just one exact type --
    // comparing a concrete struct literal's own type name against the interface name directly
    // would be a real, false type-mismatch (found via the real-example sweep: `examples/battle/
    // main.hotc`'s `let first: &dyn Enemy = &Goblin { };` -- a real, valid upcast). This file's
    // own original header already claimed `dyn` was skipped; it wasn't actually implemented until
    // this fix.
    if (kids.any { it.node?.elementType == HCTokenTypes.KEYWORD && it.text == "dyn" }) return null
    return kids.firstOrNull { it.node?.elementType == HCTokenTypes.IDENT }?.text
}

// Like `simpleTypeName`, but returns the BASE name for a generic type too (`Box<Int>` -> "Box"),
// discarding the type argument rather than bailing to `null`. Arrays still return `null` -- there's
// no meaningful "base name" for `[T]` the way there is for `Box<T>`. Used ONLY where a plain NAME
// lookup matters (receiver-type resolution in `collectLocalVarTypes`, to unlock field/method-access
// checking on generic-typed locals) -- NEVER where exact type equality matters (arg-type-mismatch,
// let-type-mismatch), since `Box<Int>` and `Box<String>` are NOT the same type even though they
// share a base name.
internal fun baseTypeName(typeRef: PsiElement): String? {
    val kids = directChildren(typeRef)
    if (kids.any { it.node?.elementType == HCTokenTypes.LBRACKET }) return null
    return kids.firstOrNull { it.node?.elementType == HCTokenTypes.IDENT }?.text
}

// A literal's own type is always unambiguous -- the one case this whole file can be fully
// confident about without any annotation to lean on. `NULL_KW` is deliberately excluded (only
// meaningful against a nullable `extern class`/`String` type, which `simpleTypeName` doesn't
// resolve nullability for anyway).
internal fun literalExprType(expr: PsiElement): String? {
    if (expr.node?.elementType != HCElementTypes.LITERAL_EXPR) return null
    return when (expr.firstChild?.node?.elementType) {
        HCTokenTypes.INT -> "Int"
        HCTokenTypes.LONG -> "Long"
        HCTokenTypes.FLOAT -> "Float"
        HCTokenTypes.DOUBLE -> "Double"
        HCTokenTypes.STRING -> "String"
        HCTokenTypes.CHAR -> "Char"
        HCTokenTypes.TRUE, HCTokenTypes.FALSE -> "Bool"
        else -> null
    }
}

// A declaration's OWN (first, direct-child) `TYPE_PARAM_LIST` -- for `FN_DECL`, that's a generic
// fn's own `<T>` (`fn identity<T>(x: T) -> T`); for `IMPL_DECL`, that's the impl BLOCK's own
// introduced params (`impl<T> Box<T> { }`'s FIRST `<T>`, not the second one instantiating `Box`'s
// own generic argument -- `firstOrNull` picks it correctly since it appears first in the token
// stream). Names collected here are placeholders, NOT real types -- see `fnSigOf`'s own header
// for why a param/return type that's just a bare type parameter name must be treated as
// "unknown," not compared against a literal's real type as if `T` were some actual struct.
private fun ownTypeParamNames(decl: PsiElement): Set<String> {
    val tpl = directChildren(decl).firstOrNull { it.node?.elementType == HCElementTypes.TYPE_PARAM_LIST } ?: return emptySet()
    return directChildren(tpl).filter { it.node?.elementType == HCTokenTypes.IDENT }.map { it.text }.toSet()
}

// `paramList`'s own params, `self` excluded (call sites never pass it explicitly) -- the
// RESULT LIST LENGTH is the real, unconditionally-trustworthy part (argument-count checks use
// it directly); each element's TYPE is `null` whenever that param's own declared type isn't a
// `simpleTypeName` (array/generic) OR is itself one of `genericParams` (a bare type-parameter
// placeholder, e.g. `value: T` inside `struct Box<T>` -- real, but not a literal type name a
// value's own type could ever be compared against), so a type-mismatch check naturally skips
// exactly those.
private fun paramTypesOf(paramList: PsiElement, genericParams: Set<String>): List<String?> {
    val out = mutableListOf<String?>()
    for (param in directChildren(paramList).filter { it.node?.elementType == HCElementTypes.PARAM }) {
        if (declaredName(param)?.text == "self") continue
        val typeRef = directChildren(param).firstOrNull { it.node?.elementType == HCElementTypes.TYPE_REF }
        val name = typeRef?.let { simpleTypeName(it) }
        out.add(if (name != null && name in genericParams) null else name)
    }
    return out
}

// `extraGenericParams` -- the ENCLOSING `impl`'s own type params (`impl<T> Box<T> { fn get(&self)
// -> T { ... } }`'s `T`), passed in by `collectStructMethodSigs` since a method itself doesn't
// carry that context. Unioned with the fn/method's OWN type params (`ownTypeParamNames(fnDecl)`,
// handling a directly-generic fn like `fn identity<T>(x: T) -> T` with no extra plumbing needed
// at the call site at all) -- see `paramTypesOf`'s own header for why a name in this set becomes
// `null` rather than a literal type name.
private fun fnSigOf(fnDecl: PsiElement, extraGenericParams: Set<String> = emptySet()): HCFnSig? {
    val nameAnchor = declaredName(fnDecl) ?: return null
    val paramList = directChildren(fnDecl).firstOrNull { it.node?.elementType == HCElementTypes.PARAM_LIST } ?: return null
    val genericParams = ownTypeParamNames(fnDecl) + extraGenericParams
    val retTypeRef = directChildren(fnDecl).firstOrNull { it.node?.elementType == HCElementTypes.TYPE_REF }
    val retTypeRaw = retTypeRef?.let { simpleTypeName(it) } ?: "Unit"
    val retType = if (retTypeRaw in genericParams) null else retTypeRaw
    return HCFnSig(paramTypesOf(paramList, genericParams), retType, nameAnchor)
}

// `implTargetStructName` reused from `HCReferences.kt` (widened to `internal`, same reasoning as
// `directChildren`/`declaredName`/`elementsOfType` above).

// The INTERFACE name of an `impl Trait for Foo { }` block -- `null` for an inherent `impl Foo { }`
// with no `for` clause at all (nothing to check against).
private fun implInterfaceName(implDecl: PsiElement): String? {
    val kids = directChildren(implDecl)
    if (kids.none { it.node?.elementType == HCTokenTypes.KEYWORD && it.text == "for" }) return null
    return kids.firstOrNull { it.node?.elementType == HCTokenTypes.IDENT }?.text
}

// `filesInScope` reused from `HCReferences.kt` (see `HCAnnotator.collectTopLevelValueNames`'s own
// header for why this scope is real, not hypothetical: one shared flat namespace per directory).

// `HCFnSig.nameAnchor` is a real `PsiElement` (reference-equality only, never `equals` across two
// separately-parsed occurrences even for byte-identical source) -- signature AGREEMENT across
// files is judged on `(paramTypes, retType)` alone, same "differing shape under the same name is
// ambiguous, don't guess" reasoning `collectAgreeing`'s own header lays out.
private fun sigsAgree(a: HCFnSig, b: HCFnSig) = a.paramTypes == b.paramTypes && a.retType == b.retType

internal fun collectTopLevelFnSigs(file: PsiFile): Map<String, HCFnSig> {
    val seen = mutableMapOf<String, MutableList<HCFnSig>>()
    for (f in filesInScope(file)) {
        for (fnDecl in directChildren(f).filter { it.node?.elementType == HCElementTypes.FN_DECL }) {
            val sig = fnSigOf(fnDecl) ?: continue
            seen.getOrPut(sig.nameAnchor.text) { mutableListOf() }.add(sig)
        }
    }
    return seen.filterValues { sigs -> sigs.all { sigsAgree(it, sigs[0]) } }.mapValues { it.value[0] }
}

// `Pair<structName, HCFnSig>` per method name -- lets `STATIC_CALL_EXPR` (which names its struct
// explicitly) resolve precisely, while `METHOD_CALL_EXPR` (no receiver-type inference at all --
// same disclosed limitation `HCInlayHintsProvider` already has) only resolves when a name is
// UNAMBIGUOUS across every struct in scope. Entries are further deduped per `(structName,
// methodName)` -- same cross-file ambiguity concern `collectAgreeing`'s own header covers, scoped
// down here since the struct name itself already narrows most accidental collisions.
// `@derive(Eq, Hash, Snapshot)` on a `struct` -- the trait names it lists (see `Parser.hotc`'s own
// `derive_impls` header for the real synthesized signatures this mirrors). Walks BACKWARD through
// preceding siblings the same way `HCAnnotator.nextSignificantSibling` walks FORWARD (an
// `ANNOTATION`/`pub`/`open`/`priv`/whitespace sibling never breaks the scan; anything else does),
// since `leadingAnnotations` (see that fn's own header) makes every `@...` its own separate
// sibling node, never a child of the declaration it precedes.
private fun precedingDeriveTraits(structDecl: PsiElement): List<String> {
    var prev = structDecl.prevSibling
    while (prev != null) {
        val et = prev.node?.elementType
        when {
            prev is PsiWhiteSpace -> {}
            et == HCTokenTypes.KEYWORD && (prev.text == "pub" || prev.text == "open" || prev.text == "priv") -> {}
            et == HCElementTypes.ANNOTATION -> {
                if (directChildren(prev).getOrNull(1)?.text == "derive") {
                    return directChildren(prev).filter { it.node?.elementType == HCElementTypes.REF_EXPR }.mapNotNull { declaredName(it)?.text }
                }
            }
            else -> return emptyList()
        }
        prev = prev.prevSibling
    }
    return emptyList()
}

internal fun collectStructMethodSigs(file: PsiFile): Map<String, List<Pair<String, HCFnSig>>> {
    val seen = mutableMapOf<Pair<String, String>, MutableList<HCFnSig>>()
    for (f in filesInScope(file)) {
        for (implDecl in directChildren(f).filter { it.node?.elementType == HCElementTypes.IMPL_DECL }) {
            val structName = implTargetStructName(implDecl) ?: continue
            val implGenericParams = ownTypeParamNames(implDecl)
            for (fnDecl in directChildren(implDecl).filter { it.node?.elementType == HCElementTypes.FN_DECL }) {
                val sig = fnSigOf(fnDecl, implGenericParams) ?: continue
                seen.getOrPut(structName to sig.nameAnchor.text) { mutableListOf() }.add(sig)
            }
        }
        // Real signatures `Parser.hotc`'s own `derive_impls` synthesizes, field by field, into a
        // real `impl Eq for Name`/`impl Hashable for Name`/plain `impl Name` block -- never
        // present as source `FN_DECL`s anywhere, so the loop above can't find them. `nameAnchor`
        // reuses the struct's own declared-name token (there's no real per-method token to point
        // at) -- fine for this checker's own purposes (arg-count/type matching), which only ever
        // reads `nameAnchor.text` for the "every occurrence agrees" cross-file key, never its
        // position for anything derive-specific.
        for (structDecl in directChildren(f).filter { it.node?.elementType == HCElementTypes.STRUCT_DECL }) {
            val structName = declaredName(structDecl)?.text ?: continue
            val traits = precedingDeriveTraits(structDecl)
            if (traits.isEmpty()) continue
            val anchor = declaredName(structDecl) ?: continue
            if ("Eq" in traits) {
                seen.getOrPut(structName to "equals") { mutableListOf() }.add(HCFnSig(listOf(structName), "Bool", anchor))
            }
            if ("Hash" in traits) {
                seen.getOrPut(structName to "hash_key") { mutableListOf() }.add(HCFnSig(emptyList(), "Int", anchor))
            }
            if ("Snapshot" in traits) {
                seen.getOrPut(structName to "snapshot") { mutableListOf() }.add(HCFnSig(emptyList(), structName, anchor))
                seen.getOrPut(structName to "restore") { mutableListOf() }.add(HCFnSig(listOf(structName), "Unit", anchor))
            }
        }
    }
    val out = mutableMapOf<String, MutableList<Pair<String, HCFnSig>>>()
    for ((key, sigs) in seen) {
        if (sigs.all { sigsAgree(it, sigs[0]) }) {
            out.getOrPut(key.second) { mutableListOf() }.add(key.first to sigs[0])
        }
    }
    return out
}

// `INTERFACE_METHOD_SIG`'s own shape (`HCPsiParser.interfaceMethodSig`): `fn name(params) [->
// Ret] ( { body } | ; )` -- the boolean is whether it has a default body (`{ }`, real) vs. being
// required (`;`) -- an implementer only NEEDS to provide the required ones.
private fun interfaceMethodSigOf(sigNode: PsiElement, extraGenericParams: Set<String>): Triple<String, HCFnSig, Boolean>? {
    val nameAnchor = declaredName(sigNode) ?: return null
    val paramList = directChildren(sigNode).firstOrNull { it.node?.elementType == HCElementTypes.PARAM_LIST } ?: return null
    val genericParams = ownTypeParamNames(sigNode) + extraGenericParams
    val retTypeRef = directChildren(sigNode).firstOrNull { it.node?.elementType == HCElementTypes.TYPE_REF }
    val retTypeRaw = retTypeRef?.let { simpleTypeName(it) } ?: "Unit"
    val retType = if (retTypeRaw in genericParams) null else retTypeRaw
    val hasDefaultBody = directChildren(sigNode).any { it.node?.elementType == HCElementTypes.BLOCK }
    return Triple(nameAnchor.text, HCFnSig(paramTypesOf(paramList, genericParams), retType, nameAnchor), hasDefaultBody)
}

// interfaceName -> methodName -> (signature, hasDefaultBody). Same cross-file "only keep it when
// every occurrence agrees" safety as `collectTopLevelFnSigs`/`collectStructMethodSigs` -- an
// interface name colliding across two unrelated files in one flat directory is less likely than a
// struct/fn name doing so, but the failure mode (silently checking against the WRONG interface's
// shape) is exactly as bad, so it gets the same treatment rather than assuming it can't happen.
private fun collectInterfaceSigs(file: PsiFile): Map<String, Map<String, Pair<HCFnSig, Boolean>>> {
    val seen = mutableMapOf<String, MutableList<Map<String, Pair<HCFnSig, Boolean>>>>()
    for (f in filesInScope(file)) {
        for (ifaceDecl in directChildren(f).filter { it.node?.elementType == HCElementTypes.INTERFACE_DECL }) {
            val name = declaredName(ifaceDecl)?.text ?: continue
            val ifaceGenericParams = ownTypeParamNames(ifaceDecl)
            val methods = directChildren(ifaceDecl)
                .filter { it.node?.elementType == HCElementTypes.INTERFACE_METHOD_SIG }
                .mapNotNull { interfaceMethodSigOf(it, ifaceGenericParams) }
                .associate { (mName, sig, hasDefault) -> mName to (sig to hasDefault) }
            seen.getOrPut(name) { mutableListOf() }.add(methods)
        }
    }
    // "Agrees" here means the same method NAMES with the same (paramTypes, retType) each --
    // `hasDefaultBody`/anchor differences don't matter for this comparison.
    fun methodsAgree(a: Map<String, Pair<HCFnSig, Boolean>>, b: Map<String, Pair<HCFnSig, Boolean>>): Boolean {
        if (a.keys != b.keys) return false
        return a.keys.all { sigsAgree(a.getValue(it).first, b.getValue(it).first) }
    }
    return seen.filterValues { occurrences -> occurrences.all { methodsAgree(it, occurrences[0]) } }
        .mapValues { it.value[0] }
}

// `impl Trait for Foo { }` -- every method `Trait` declares WITHOUT a default body must be
// implemented; an implemented method's own param count and return type (where both sides are
// confidently known -- `simpleTypeName`) must match the interface's own declared signature. Both
// checks are anchored on the interface's own explicit name in the `impl` header (no receiver-type
// inference needed at all -- the interface being implemented is right there in the syntax).
private fun checkInterfaceImplementations(file: PsiFile, holder: AnnotationHolder) {
    val interfaces = collectInterfaceSigs(file)
    for (implDecl in elementsOfType(file, HCElementTypes.IMPL_DECL)) {
        val ifaceName = implInterfaceName(implDecl) ?: continue
        val ifaceMethods = interfaces[ifaceName] ?: continue
        val ifaceNameAnchor = directChildren(implDecl).firstOrNull { it.node?.elementType == HCTokenTypes.IDENT } ?: implDecl

        val implMethods = directChildren(implDecl)
            .filter { it.node?.elementType == HCElementTypes.FN_DECL }
            .mapNotNull { fnSigOf(it) }
            .associateBy { it.nameAnchor.text }

        for ((methodName, ifaceEntry) in ifaceMethods) {
            val (ifaceSig, hasDefault) = ifaceEntry
            val implSig = implMethods[methodName]
            if (implSig == null) {
                if (!hasDefault) {
                    val message = "missing implementation of '$methodName' required by interface '$ifaceName'"
                    // `ifaceSig.nameAnchor`'s own PARENT is the real `INTERFACE_METHOD_SIG` node
                    // -- its `PARAM_LIST`'s raw text already has the real param names/types
                    // (parens included), no reconstruction needed.
                    val sigNode = ifaceSig.nameAnchor.parent
                    val paramListText = sigNode?.let { directChildren(it).firstOrNull { c -> c.node?.elementType == HCElementTypes.PARAM_LIST } }?.text
                    if (paramListText != null) {
                        val retTypeText = directChildren(sigNode).firstOrNull { it.node?.elementType == HCElementTypes.TYPE_REF }?.text
                        errorWithFix(holder, ifaceNameAnchor, message, HCImplementInterfaceMethodFix(implDecl, methodName, paramListText, retTypeText))
                    } else {
                        error(holder, ifaceNameAnchor, message)
                    }
                }
                continue
            }
            if (implSig.paramTypes.size != ifaceSig.paramTypes.size) {
                error(
                    holder, implSig.nameAnchor,
                    "'$methodName' overrides interface '$ifaceName' with ${implSig.paramTypes.size} param(s), expected ${ifaceSig.paramTypes.size}",
                )
            }
            if (implSig.retType != null && ifaceSig.retType != null && implSig.retType != ifaceSig.retType) {
                error(
                    holder, implSig.nameAnchor,
                    "'$methodName' overrides interface '$ifaceName' with return type '${implSig.retType}', expected '${ifaceSig.retType}'",
                )
            }
        }
    }
}

private fun argListOf(callLike: PsiElement): PsiElement? =
    directChildren(callLike).firstOrNull { it.node?.elementType == HCElementTypes.ARG_LIST }

private val PUNCTUATION = setOf(HCTokenTypes.LPAREN, HCTokenTypes.RPAREN, HCTokenTypes.COMMA)

private fun argumentsOf(argList: PsiElement): List<PsiElement> =
    directChildren(argList).filter {
        it !is com.intellij.psi.PsiWhiteSpace && it.node?.elementType !in PUNCTUATION &&
            it.node?.elementType != HCTokenTypes.LINE_COMMENT && it.node?.elementType != HCTokenTypes.DOC_COMMENT
    }

private fun checkArgsAgainstSig(
    holder: AnnotationHolder, calleeAnchor: PsiElement, calleeName: String, args: List<PsiElement>, sig: HCFnSig,
    localTypes: Map<String, String>, ctx: HCTypeContext,
) {
    if (args.size != sig.paramTypes.size) {
        error(holder, calleeAnchor, "'$calleeName' expects ${sig.paramTypes.size} argument(s), got ${args.size}")
        return
    }
    for (i in args.indices) {
        val declared = sig.paramTypes[i] ?: continue
        val actual = inferExprType(args[i], localTypes, ctx) ?: continue
        if (declared != actual) {
            error(holder, args[i], "argument ${i + 1} to '$calleeName' expects '$declared', got '$actual'")
        }
    }
}

// Move-checker surfacing -- a deliberately narrow slice of the real compiler's own full flow-
// sensitive move analysis (`Checker.kt`'s own `moved: Map<String, Boolean>` threaded through
// every statement/expression kind, correctly merging branches and loop back-edges). Real,
// disclosed scope cuts, each chosen so the failure mode is a MISSED case, never a false positive:
// - Only tracks a STRAIGHT-LINE run of statements within ONE block. Entering any nested block
//   (an `if`/`while`/`for`/`try` body, a `match` arm, a `catch` clause) starts a completely FRESH,
//   independent move-set for that block rather than merging/carrying state in OR back out --
//   avoids the hard part (real branch-completeness/loop-fixpoint analysis) entirely, at the cost
//   of never catching a move that spans a branch.
// - Only recognizes a "consuming" (by-value) use in the SIMPLEST real shapes: a bare local name
//   passed directly as a call/method-call/static-call ARGUMENT, or as a `let`/`return`'s own
//   value, with NOTHING else in between (no `&expr`, no field access, no nested call wrapping it
//   -- see `collectFromExpr`'s own header for exactly which shapes it recognizes). A move buried
//   inside a more complex expression (a struct literal field, an array literal element, an
//   assignment's own RHS) is silently not tracked at all.
// - Only fires for a local whose TYPE resolves (via `localTypes`, the same conservative source
//   every other check in this file already relies on) to a real `struct`/`enum` declared in
//   scope -- `String`/`Int`/etc. never trigger this at all (they're `isCopy()` in the real
//   compiler too, see `Types.kt`), and an unresolvable type is silently skipped rather than
//   guessed.
// Reported as a WARNING, not an ERROR (unlike every other check in this file) specifically
// because of how much this pass DOESN'T see -- a definitive "use of moved value" claim isn't
// warranted from a check this incomplete; "possible" is the honest framing.
private fun isNonCopyLocalType(varName: String, localTypes: Map<String, String>, file: PsiFile): Boolean {
    val typeName = localTypes[varName] ?: return false
    return structDeclByName(typeName, file) != null || enumDeclByName(typeName, file) != null
}

private fun bareRefName(expr: PsiElement?): String? =
    expr?.takeIf { it.node?.elementType == HCElementTypes.REF_EXPR }?.text

// The exhaustive list of "consuming" shapes this pass recognizes -- a call/method-call/static-
// call's own ARGUMENTS (each checked independently; a non-bare-ref argument, e.g. `foo(&s)` or
// `foo(s.field)`, is correctly left alone) or the expression itself being a bare ref (covers a
// `let`/`return`'s own value once the caller passes the right sub-expression in).
private fun collectFromExpr(expr: PsiElement, out: MutableList<Pair<PsiElement, String>>) {
    bareRefName(expr)?.let { out.add(expr to it); return }
    when (expr.node?.elementType) {
        HCElementTypes.CALL_EXPR, HCElementTypes.METHOD_CALL_EXPR, HCElementTypes.STATIC_CALL_EXPR -> {
            val argList = directChildren(expr).firstOrNull { it.node?.elementType == HCElementTypes.ARG_LIST } ?: return
            for (arg in argumentsOf(argList)) {
                bareRefName(arg)?.let { out.add(arg to it) }
            }
        }
        else -> {}
    }
}

private fun consumingUsesInStmt(stmt: PsiElement): List<Pair<PsiElement, String>> {
    val out = mutableListOf<Pair<PsiElement, String>>()
    when (stmt.node?.elementType) {
        HCElementTypes.LET_STMT -> letInitExprOf(stmt)?.let { collectFromExpr(it, out) }
        HCElementTypes.EXPR_STMT -> {
            val expr = directChildren(stmt).firstOrNull { it !is PsiWhiteSpace && it.node?.elementType != HCTokenTypes.SEMI }
            expr?.let { collectFromExpr(it, out) }
        }
        HCElementTypes.RETURN_STMT -> {
            val expr = directChildren(stmt).firstOrNull {
                it !is PsiWhiteSpace && it.node?.elementType != HCTokenTypes.KEYWORD && it.node?.elementType != HCTokenTypes.SEMI
            }
            expr?.let { collectFromExpr(it, out) }
        }
        else -> {}
    }
    return out
}

private fun checkPossibleMoves(fnDecl: PsiElement, localTypes: Map<String, String>, file: PsiFile, holder: AnnotationHolder) {
    val body = directChildren(fnDecl).firstOrNull { it.node?.elementType == HCElementTypes.BLOCK } ?: return
    checkMovesInBlock(body, localTypes, file, holder)
}

private fun checkMovesInBlock(block: PsiElement, localTypes: Map<String, String>, file: PsiFile, holder: AnnotationHolder) {
    val moved = mutableSetOf<String>()
    for (stmt in directChildren(block)) {
        for ((anchor, name) in consumingUsesInStmt(stmt)) {
            if (!isNonCopyLocalType(name, localTypes, file)) continue
            if (name in moved) {
                warn(holder, anchor, "possible use of '$name' after it was moved (passed by value) earlier in this block")
            }
            moved.add(name)
        }
        // A reassignment (`x = ...;`) gives the variable a fresh value -- matches the real
        // checker's own `Expr.Assign` handling (`Checker.kt`: `out[expr.name] = false`).
        if (stmt.node?.elementType == HCElementTypes.EXPR_STMT) {
            val assign = directChildren(stmt).firstOrNull { it.node?.elementType == HCElementTypes.ASSIGN_EXPR }
            val lhsName = assign?.let { directChildren(it).firstOrNull { c -> c.node?.elementType == HCElementTypes.REF_EXPR }?.text }
            lhsName?.let { moved.remove(it) }
        }
        // Every nested block starts fresh, independent tracking -- see this check's own header.
        for (child in directChildren(stmt)) {
            if (child.node?.elementType == HCElementTypes.BLOCK) checkMovesInBlock(child, localTypes, file, holder)
        }
        if (stmt.node?.elementType == HCElementTypes.MATCH_STMT) {
            for (arm in directChildren(stmt).filter { it.node?.elementType == HCElementTypes.MATCH_ARM }) {
                for (armChild in directChildren(arm)) {
                    if (armChild.node?.elementType == HCElementTypes.BLOCK) checkMovesInBlock(armChild, localTypes, file, holder)
                }
            }
        }
        if (stmt.node?.elementType == HCElementTypes.TRY_STMT) {
            for (catchClause in directChildren(stmt).filter { it.node?.elementType == HCElementTypes.CATCH_CLAUSE }) {
                for (cChild in directChildren(catchClause)) {
                    if (cChild.node?.elementType == HCElementTypes.BLOCK) checkMovesInBlock(cChild, localTypes, file, holder)
                }
            }
        }
    }
}

// The real orchestrator: two file-wide checks that need no per-function context at all (a struct
// literal's own field list, an interface impl's own signature match), then everything else run
// ONCE PER FUNCTION with that function's own `localTypes` (now real type inference, not just
// explicit annotations -- see `HCTypeInference.kt`'s own header) computed a single time and
// threaded through every check that needs it, rather than each check recomputing its own partial
// view of "what do we know about this fn's locals" the way earlier versions of this file did.
fun checkArgCountsAndBasicTypes(file: PsiFile, holder: AnnotationHolder) {
    val topLevelFns = collectTopLevelFnSigs(file)
    val structMethods = collectStructMethodSigs(file)
    val ctx = HCTypeContext(file, topLevelFns, structMethods)
    val fieldSets = collectFieldSets(file)

    checkStructLiteralFields(file, holder)
    checkInterfaceImplementations(file, holder)

    for (fnDecl in elementsOfType(file, HCElementTypes.FN_DECL)) {
        val enclosingStruct = fnDecl.parent
            ?.takeIf { it.node?.elementType == HCElementTypes.IMPL_DECL }
            ?.let { implTargetStructName(it) }
        val localTypes = collectLocalVarTypes(fnDecl, enclosingStruct, ctx)

        for (call in elementsOfType(fnDecl, HCElementTypes.CALL_EXPR)) {
            val callee = directChildren(call).firstOrNull { it.node?.elementType == HCElementTypes.REF_EXPR } ?: continue
            val name = declaredName(callee)?.text ?: callee.text
            val sig = topLevelFns[name] ?: continue
            val argList = argListOf(call) ?: continue
            checkArgsAgainstSig(holder, callee, name, argumentsOf(argList), sig, localTypes, ctx)
        }

        for (call in elementsOfType(fnDecl, HCElementTypes.STATIC_CALL_EXPR)) {
            val idents = directChildren(call).filter { it.node?.elementType == HCTokenTypes.IDENT }
            if (idents.size < 2) continue
            val (typeIdent, methodIdent) = idents[0] to idents[1]
            val candidates = structMethods[methodIdent.text]?.filter { it.first == typeIdent.text } ?: continue
            val sig = candidates.singleOrNull()?.second ?: continue
            val argList = argListOf(call) ?: continue
            checkArgsAgainstSig(holder, methodIdent, "${typeIdent.text}::${methodIdent.text}", argumentsOf(argList), sig, localTypes, ctx)
        }

        checkLetTypeMismatchesIn(fnDecl, localTypes, ctx, holder)
        checkReturnValuesIn(fnDecl, localTypes, ctx, holder)
        checkConditionTypesIn(fnDecl, localTypes, ctx, holder)
        checkFieldAndMethodAccessIn(fnDecl, localTypes, ctx, fieldSets, structMethods, holder)
        checkPossibleMoves(fnDecl, localTypes, file, holder)
        for (matchNode in elementsOfType(fnDecl, HCElementTypes.MATCH_STMT)) checkMatchArms(matchNode, localTypes, file, holder)
        for (matchNode in elementsOfType(fnDecl, HCElementTypes.MATCH_EXPR)) checkMatchArms(matchNode, localTypes, file, holder)
    }
}

internal fun enumDeclByName(name: String, file: PsiFile): PsiElement? {
    for (f in filesInScope(file)) {
        for (decl in directChildren(f)) {
            if (decl.node?.elementType == HCElementTypes.ENUM_DECL && declaredName(decl)?.text == name) return decl
        }
    }
    return null
}

private fun enumVariantNames(enumDecl: PsiElement): List<String> =
    directChildren(enumDecl).filter { it.node?.elementType == HCElementTypes.ENUM_VARIANT }.mapNotNull { declaredName(it)?.text }

// `null` = a wildcard `_` arm (matches the real compiler's own `MatchArm.variantName == null`
// convention -- `Parser.kt`'s own `vname == "_" -> null`; `_` never takes a `::` qualifier either)
// OR a literal-pattern arm on a match this check has already confirmed is over an ENUM value (a
// real type error the real compiler's own checker catches, out of scope here -- this pass just
// leaves such an arm out of the covered-variants count rather than flagging it itself).
private fun patternVariantName(pattern: PsiElement): String? {
    if (pattern.node?.elementType != HCElementTypes.VARIANT_PATTERN) return null
    val kids = directChildren(pattern)
    val firstIdent = kids.firstOrNull { it.node?.elementType == HCTokenTypes.IDENT } ?: return null
    val colonColonIdx = kids.indexOfFirst { it.node?.elementType == HCTokenTypes.COLONCOLON }
    if (colonColonIdx >= 0) return kids.drop(colonColonIdx + 1).firstOrNull { it.node?.elementType == HCTokenTypes.IDENT }?.text
    return if (firstIdent.text == "_") null else firstIdent.text
}

// The scrutinee's own enum type -- only known when it's a bare local/param/`self` with an
// EXPLICIT declared enum type (`collectLocalVarTypes`'s own conservative scope, see that fn's own
// header); a chained/computed scrutinee expression (`makeStatus()`, `self.status`, ...) is left
// unchecked entirely rather than guessed.
private fun scrutineeEnumType(scrutinee: PsiElement, localTypes: Map<String, String>, file: PsiFile): Pair<String, PsiElement>? {
    if (scrutinee.node?.elementType != HCElementTypes.REF_EXPR) return null
    val typeName = localTypes[scrutinee.text] ?: return null
    val enumDecl = enumDeclByName(typeName, file) ?: return null
    return typeName to enumDecl
}

// `interface Name { }` / `sealed interface Name { }` -- a leading `sealed` is a bare preceding
// KEYWORD child of `INTERFACE_DECL` (see `HCPsiParser.interfaceDecl`'s own header), not a separate
// node kind or flag of its own.
private fun sealedInterfaceDeclByName(name: String, file: PsiFile): PsiElement? {
    for (f in filesInScope(file)) {
        for (decl in directChildren(f)) {
            if (decl.node?.elementType != HCElementTypes.INTERFACE_DECL) continue
            if (declaredName(decl)?.text != name) continue
            if (directChildren(decl).firstOrNull()?.text == "sealed") return decl
        }
    }
    return null
}

// Every struct with a REAL `impl InterfaceName for StructName { }` (an `extend`/plain `impl
// StructName { }` with no `for` doesn't implement anything) -- `HCPsiParser.implDecl`'s own shape
// is `[KEYWORD"impl", (TYPE_PARAM_LIST)?, IDENT(name1), (TYPE_PARAM_LIST)?, (KEYWORD"for",
// IDENT(name2), ...)?, ...]`, so `name1` is the INTERFACE and `name2` (only present with a real
// `for`) is the implementing struct -- deliberately NOT the same lookup `implTargetStructName`
// (elsewhere in this file) does, since that one is answering "what struct owns this impl's
// methods" (works for BOTH `impl Struct { }` and `impl Interface for Struct { }`), not "does this
// impl implement a specific named interface" the way exhaustiveness needs.
private fun structsImplementing(interfaceName: String, file: PsiFile): List<String> {
    val result = mutableListOf<String>()
    for (f in filesInScope(file)) {
        for (impl in directChildren(f).filter { it.node?.elementType == HCElementTypes.IMPL_DECL }) {
            val idents = directChildren(impl).filter { it.node?.elementType == HCTokenTypes.IDENT }
            val forIdx = directChildren(impl).indexOfFirst { it.node?.elementType == HCTokenTypes.KEYWORD && it.text == "for" }
            if (forIdx < 0) continue
            val interfaceIdent = idents.firstOrNull() ?: continue
            if (interfaceIdent.text != interfaceName) continue
            val structIdent = directChildren(impl).drop(forIdx + 1).firstOrNull { it.node?.elementType == HCTokenTypes.IDENT } ?: continue
            result.add(structIdent.text)
        }
    }
    return result
}

// The scrutinee's own `&dyn Interface`/`dyn Interface` type -- reuses the SAME `localTypes` entry
// `scrutineeEnumType` does (`collectLocalVarTypes` populates it via `baseTypeName`, which -- unlike
// `simpleTypeName` -- does NOT skip a `dyn`-qualified type, so a `&dyn Shape`-typed param already
// resolves to plain "Shape" there with no extra plumbing needed here).
private fun scrutineeInterfaceType(scrutinee: PsiElement, localTypes: Map<String, String>, file: PsiFile): Pair<String, PsiElement>? {
    if (scrutinee.node?.elementType != HCElementTypes.REF_EXPR) return null
    val typeName = localTypes[scrutinee.text] ?: return null
    val interfaceDecl = sealedInterfaceDeclByName(typeName, file) ?: return null
    return typeName to interfaceDecl
}

// Mirrors the real compiler's own `Checker.checkMatch` enum branch closely, including its exact
// "isn't exhaustive" wording (`Checker.kt`'s own `errors += "...isn't exhaustive, missing
// ${missing} (add arms or a '_' wildcard)"`) so a user sees the SAME message in the IDE as from a
// real `hc build` -- `missing` deliberately rendered the same way Kotlin renders a `Set<String>`
// (`[A, B]`) rather than reformatted, for exactly that reason. Shared between an enum scrutinee and
// a `sealed interface` one -- `examples/sealed.hc`'s own `match s { Circle { radius } => ...
// Square { side } => ... }` (`s: &dyn Shape`) uses the EXACT SAME `VARIANT_PATTERN` shape a real
// enum-variant match arm does (a struct's own name where an enum's variant name would be), so
// `patternVariantName`/the covered-set/wildcard/duplicate-arm logic below is already correct for
// both without any changes -- only WHERE the "allVariants"/type-label pair comes from differs.
private fun checkMatchArms(matchNode: PsiElement, localTypes: Map<String, String>, file: PsiFile, holder: AnnotationHolder) {
    val scrutinee = directChildren(matchNode).firstOrNull {
        it.node?.elementType != HCTokenTypes.KEYWORD && it !is PsiWhiteSpace
    } ?: return
    val enumMatch = scrutineeEnumType(scrutinee, localTypes, file)
    val isInterface = enumMatch == null
    val (typeName, allVariants) = if (enumMatch != null) {
        enumMatch.first to enumVariantNames(enumMatch.second)
    } else {
        val interfaceMatch = scrutineeInterfaceType(scrutinee, localTypes, file) ?: return
        interfaceMatch.first to structsImplementing(interfaceMatch.first, file)
    }
    val matchKeyword = directChildren(matchNode).firstOrNull { it.node?.elementType == HCTokenTypes.KEYWORD } ?: matchNode
    val covered = mutableSetOf<String>()
    var hasWildcard = false
    for (arm in directChildren(matchNode).filter { it.node?.elementType == HCElementTypes.MATCH_ARM }) {
        val pattern = arm.firstChild ?: continue
        if (hasWildcard) {
            error(holder, pattern, "unreachable arm after wildcard '_'")
            continue
        }
        val variantName = patternVariantName(pattern)
        if (variantName == null) {
            if (pattern.node?.elementType == HCElementTypes.VARIANT_PATTERN) hasWildcard = true
            continue
        }
        if (variantName !in allVariants) {
            error(holder, pattern, "'$variantName' is not a variant of '$typeName'")
            continue
        }
        if (!covered.add(variantName)) {
            error(holder, pattern, "duplicate arm for variant '$variantName'")
        }
    }
    if (!hasWildcard) {
        val missing = allVariants.toSet() - covered
        if (missing.isNotEmpty()) {
            errorWithFix(
                holder, matchKeyword, "match on '$typeName' isn't exhaustive, missing $missing (add arms or a '_' wildcard)",
                HCAddMissingMatchArmsFix(matchNode, typeName, missing.toList(), isInterface),
            )
        }
    }
}

// The last non-whitespace, non-`;` child of a `LET_STMT` -- see `HCPsiParser.letStmt`'s own shape
// (`[KEYWORD, IDENT(name), (COLON, TYPE_REF)?, OPERATOR("="), <init expr>, SEMI]`): the init
// expression is unconditionally the last real child regardless of whether an explicit type
// annotation is present, so no positional guessing based on which shape it is is needed.
internal fun letInitExprOf(letStmt: PsiElement): PsiElement? =
    directChildren(letStmt).lastOrNull { it !is PsiWhiteSpace && it.node?.elementType != HCTokenTypes.SEMI }

// The type of every local in `fnDecl` this pass can determine -- `self` -> the enclosing `impl`
// block's own target struct; a `PARAM`'s own EXPLICIT `TYPE_REF`; and, now, a `LET_STMT` WITHOUT
// an explicit type gets its type INFERRED from its own init expression (`HCTypeInference.kt`'s
// own `inferExprType`) instead of being silently skipped the way earlier versions of this
// function did. Processed in DOCUMENT ORDER so a later `let`'s inferred type can depend on an
// earlier one already being in `out` (`let a = 1; let b = a + 1;`) -- a forward reference (a `let`
// depending on one declared LATER) simply won't resolve, same as it wouldn't at runtime either.
private fun collectLocalVarTypes(fnDecl: PsiElement, enclosingStruct: String?, ctx: HCTypeContext): Map<String, String> {
    val out = mutableMapOf<String, String>()
    enclosingStruct?.let { out["self"] = it }
    val paramList = directChildren(fnDecl).firstOrNull { it.node?.elementType == HCElementTypes.PARAM_LIST }
    for (param in paramList?.let { directChildren(it) }.orEmpty().filter { it.node?.elementType == HCElementTypes.PARAM }) {
        val name = declaredName(param)?.text ?: continue
        if (name == "self") continue
        val typeRef = directChildren(param).firstOrNull { it.node?.elementType == HCElementTypes.TYPE_REF } ?: continue
        baseTypeName(typeRef)?.let { out[name] = it }
    }
    for (letStmt in elementsOfType(fnDecl, HCElementTypes.LET_STMT)) {
        val name = declaredName(letStmt)?.text ?: continue
        val typeRef = directChildren(letStmt).firstOrNull { it.node?.elementType == HCElementTypes.TYPE_REF }
        val explicit = typeRef?.let { baseTypeName(it) }
        if (explicit != null) {
            out[name] = explicit
            continue
        }
        val inferred = letInitExprOf(letStmt)?.let { inferExprType(it, out, ctx) }
        if (inferred != null) out[name] = inferred
    }
    return out
}

// `recv.field` / `recv.method(args)`, but only when `recv` is a BARE local/param/`self` whose
// type this pass actually knows (`collectLocalVarTypes`) -- a chained receiver (`foo().bar.baz`)
// or an untyped local is left alone entirely, same conservative "unknown, don't guess" principle
// as everywhere else here. This SUPERSEDES the plain "unambiguous method name across every
// struct" fallback `checkArgCountsAndBasicTypes` used to apply to EVERY `METHOD_CALL_EXPR`
// regardless of receiver -- when the receiver's own type is known, resolution is precise (exact
// struct match, not "happens to be the only struct with this method name anywhere"), and a
// genuinely unknown method name on a KNOWN struct type is now flagged for real, not just skipped.
private fun structDeclByName(name: String, file: PsiFile): PsiElement? {
    for (f in filesInScope(file)) {
        for (decl in directChildren(f)) {
            if (decl.node?.elementType == HCElementTypes.STRUCT_DECL && declaredName(decl)?.text == name) return decl
        }
    }
    return null
}

// The first INHERENT `impl` block (no `for` clause) for a struct -- where "add a plain method"
// fix belongs. If a struct only has interface `impl`s (or none at all), the fix is simply not
// offered (falls back to the plain error) rather than guessing which block a brand-new inherent
// method should go in, or fabricating a whole new `impl S { }` block from scratch.
private fun inherentImplDeclByName(structName: String, file: PsiFile): PsiElement? {
    for (f in filesInScope(file)) {
        for (implDecl in directChildren(f).filter { it.node?.elementType == HCElementTypes.IMPL_DECL }) {
            if (implTargetStructName(implDecl) == structName && implInterfaceName(implDecl) == null) return implDecl
        }
    }
    return null
}

private fun checkFieldAndMethodAccessIn(
    fnDecl: PsiElement, localTypes: Map<String, String>, ctx: HCTypeContext,
    fieldSets: Map<String, Set<String>>, structMethods: Map<String, List<Pair<String, HCFnSig>>>, holder: AnnotationHolder,
) {
    for (access in elementsOfType(fnDecl, HCElementTypes.FIELD_ACCESS_EXPR)) {
        val receiver = access.firstChild ?: continue
        val recvType = inferExprType(receiver, localTypes, ctx) ?: continue
        val fields = fieldSets[recvType] ?: continue
        val fieldName = directChildren(access).drop(1).firstOrNull { it.node?.elementType == HCTokenTypes.IDENT } ?: continue
        if (fieldName.text !in fields) {
            val message = "no such field '${fieldName.text}' on '$recvType'"
            val structDecl = structDeclByName(recvType, ctx.file)
            if (structDecl != null) {
                errorWithFix(holder, fieldName, message, HCCreateFieldFix(structDecl, recvType, fieldName.text))
            } else {
                error(holder, fieldName, message)
            }
        }
    }

    for (call in elementsOfType(fnDecl, HCElementTypes.METHOD_CALL_EXPR)) {
        val receiver = call.firstChild ?: continue
        val methodIdent = directChildren(call).drop(1).firstOrNull { it.node?.elementType == HCTokenTypes.IDENT } ?: continue
        val argList = argListOf(call) ?: continue
        val recvType = inferExprType(receiver, localTypes, ctx)
        if (recvType != null) {
            if (recvType !in fieldSets) continue // e.g. an extern-class-typed param -- not tracked here at all
            val exact = structMethods[methodIdent.text]?.firstOrNull { it.first == recvType }
            if (exact == null) {
                val message = "no such method '${methodIdent.text}' on '$recvType'"
                val implDecl = inherentImplDeclByName(recvType, ctx.file)
                if (implDecl != null) {
                    val argTypes = argumentsOf(argList).map { inferExprType(it, localTypes, ctx) }
                    errorWithFix(holder, methodIdent, message, HCCreateMethodFix(implDecl, recvType, methodIdent.text, argTypes))
                } else {
                    error(holder, methodIdent, message)
                }
            } else {
                checkArgsAgainstSig(holder, methodIdent, methodIdent.text, argumentsOf(argList), exact.second, localTypes, ctx)
            }
        } else {
            // Receiver type unknown -- fall back to "the method name is unambiguous across
            // every struct in scope," same as before this fn existed.
            val sig = structMethods[methodIdent.text]?.singleOrNull()?.second ?: continue
            checkArgsAgainstSig(holder, methodIdent, methodIdent.text, argumentsOf(argList), sig, localTypes, ctx)
        }
    }
}

// `let x: T = <expr>;` -- the declared type is explicit; the init expression's own type is now
// real inference (`inferExprType`), not just a literal's unambiguous type, so `let x: Int = a +
// b;` is checked too, not only `let x: Int = 5;`.
private fun checkLetTypeMismatchesIn(fnDecl: PsiElement, localTypes: Map<String, String>, ctx: HCTypeContext, holder: AnnotationHolder) {
    for (letStmt in elementsOfType(fnDecl, HCElementTypes.LET_STMT)) {
        val declared = directChildren(letStmt)
            .firstOrNull { it.node?.elementType == HCElementTypes.TYPE_REF }
            ?.let { simpleTypeName(it) } ?: continue
        val initExpr = letInitExprOf(letStmt) ?: continue
        val actual = inferExprType(initExpr, localTypes, ctx) ?: continue
        if (declared != actual) {
            error(holder, initExpr, "type mismatch: expected '$declared', got '$actual'")
        }
    }
}

// Collects every (name, value) occurrence seen, then keeps a name only when EVERY occurrence
// agrees -- the "immediate containing directory" sibling scope (`filesInScope`) is a real, useful
// heuristic for a genuine multi-file sub-project (`examples/multifile/`, `examples/registries/`,
// `examples/battle/`), but `examples/` itself is also full of many UNRELATED single-file programs
// that happen to share one flat folder purely for repo organization -- two of them declaring an
// unrelated `Item` with different fields is real, not hypothetical (found via this file's own
// real-example sweep: `inventory_system.hotc`'s `Item` and `stress_test.hotc`'s `Item` share a
// name and nothing else). Silently letting the LAST file checked win would make one or the
// other's real, correct code start failing depending on iteration order -- ambiguous same-name-
// different-shape is exactly the "don't guess" case every other check in this file already
// treats the same way (`METHOD_CALL_EXPR` resolution, `HCInlayHintsProvider`'s own disclosed
// no-overload-resolution limitation).
private fun <T> collectAgreeing(entries: Sequence<Pair<String, T>>): Map<String, T> {
    val seen = mutableMapOf<String, MutableList<T>>()
    for ((name, value) in entries) seen.getOrPut(name) { mutableListOf() }.add(value)
    return seen.filterValues { it.distinct().size == 1 }.mapValues { it.value.first() }
}

// Field name -> declared field set, for every plain struct AND every enum variant (both use
// `FIELD_DECL` children -- `ENUM_VARIANT`'s own field list is parsed by the exact same
// `fieldList` helper `STRUCT_DECL` uses, see `HCPsiParser`'s own header) across the file+sibling
// scope. One flat map keyed by bare name -- a `STRUCT_LIT_EXPR` (`identLed`'s own header) can
// name either kind and the two namespaces are already required to be disjoint (`HCAnnotator`'s
// own "Duplicate variant/struct name" check), so no kind tag is needed to look one up safely.
private fun collectFieldSets(file: PsiFile): Map<String, Set<String>> {
    fun fieldsOf(owner: PsiElement) = directChildren(owner)
        .filter { it.node?.elementType == HCElementTypes.FIELD_DECL }
        .mapNotNull { declaredName(it)?.text }
        .toSet()
    val entries = sequence {
        for (f in filesInScope(file)) {
            for (structDecl in directChildren(f).filter { it.node?.elementType == HCElementTypes.STRUCT_DECL }) {
                declaredName(structDecl)?.text?.let { yield(it to fieldsOf(structDecl)) }
            }
            for (enumDecl in directChildren(f).filter { it.node?.elementType == HCElementTypes.ENUM_DECL }) {
                for (variant in directChildren(enumDecl).filter { it.node?.elementType == HCElementTypes.ENUM_VARIANT }) {
                    declaredName(variant)?.text?.let { yield(it to fieldsOf(variant)) }
                }
            }
        }
    }
    return collectAgreeing(entries)
}

// `Name { field: expr, ... }` / `Base::Variant { field: expr, ... }` -- checks the field list
// against the struct's/variant's own declared fields: unknown field name, duplicate field name,
// and a declared field that's simply missing. All three are checkable directly off the literal's
// OWN explicit type name (no inference needed -- the type is right there in the syntax), which is
// why this is safe where a general "are these VALUES the right type" check wouldn't be. If the
// name isn't found in `fieldSets` at all (an unresolvable/typo'd type name), this stays silent --
// `HCAnnotator`'s own undefined-reference check doesn't cover struct-literal leads either (see
// that check's own header), so piling an extra, possibly-wrong error on top here isn't worth it.
private fun checkStructLiteralFields(file: PsiFile, holder: AnnotationHolder) {
    val fieldSets = collectFieldSets(file)
    for (lit in elementsOfType(file, HCElementTypes.STRUCT_LIT_EXPR)) {
        val idents = directChildren(lit).filter { it.node?.elementType == HCTokenTypes.IDENT }
        // Qualified (`Base<Arg>::Variant { ... }`, `Base<Arg1, Arg2>::Variant { ... }`): the LAST
        // ident before any `FIELD_INIT` is the real variant name. Otherwise (`Name { ... }` or
        // `Name<Arg> { ... }`): the FIRST ident is the real struct name -- a real, previously-
        // latent bug found via `examples/parallel_for.hotc`'s own `Vec<Counter> { data: [], len:
        // 0 }`: taking the LAST ident unconditionally read "Counter" (the type ARGUMENT) as the
        // struct name instead of "Vec" itself, the moment a one-type-argument literal had no
        // `::` at all. Same `isQualified` distinction `HCTypeInference.structLitType` already
        // makes correctly, right above -- this check just never mirrored it.
        val isQualified = directChildren(lit).any { it.node?.elementType == HCTokenTypes.COLONCOLON }
        val typeNameToken = (if (isQualified) idents.lastOrNull() else idents.firstOrNull()) ?: continue
        val declaredFields = fieldSets[typeNameToken.text] ?: continue

        val fieldInits = directChildren(lit).filter { it.node?.elementType == HCElementTypes.FIELD_INIT }
        val seen = mutableSetOf<String>()
        for (fieldInit in fieldInits) {
            val fieldName = declaredName(fieldInit) ?: continue
            if (fieldName.text !in declaredFields) {
                error(holder, fieldName, "no such field '${fieldName.text}' on '${typeNameToken.text}'")
            } else if (!seen.add(fieldName.text)) {
                error(holder, fieldName, "duplicate field '${fieldName.text}' in '${typeNameToken.text}' literal")
            }
        }
        val missing = declaredFields - seen
        if (missing.isNotEmpty()) {
            error(holder, typeNameToken, "missing field(s) in '${typeNameToken.text}' literal: ${missing.sorted().joinToString(", ")}")
        }
    }
}

// A bare `return;` inside a `fn` that DECLARES a non-`Unit` return type is purely structural
// (presence/absence of a value expression, no type inference involved). The VALUE half now uses
// real inference (`inferExprType`) instead of only a literal's own unambiguous type, so `return a
// + b;`/`return self.total();` are checked too, not only `return 5;`.
private fun checkReturnValuesIn(fnDecl: PsiElement, localTypes: Map<String, String>, ctx: HCTypeContext, holder: AnnotationHolder) {
    val retTypeRef = directChildren(fnDecl).firstOrNull { it.node?.elementType == HCElementTypes.TYPE_REF }
    // No `-> T` at all is a real, common, and unambiguous "Unit" -- only an array/generic
    // EXPLICIT return type (`simpleTypeName` returning `null`) is the "unknown, skip" case.
    val retType = if (retTypeRef == null) "Unit" else (simpleTypeName(retTypeRef) ?: return)
    for (returnStmt in elementsOfType(fnDecl, HCElementTypes.RETURN_STMT)) {
        val valueChild = directChildren(returnStmt).firstOrNull {
            it.node?.elementType != HCTokenTypes.KEYWORD && it.node?.elementType != HCTokenTypes.SEMI && it !is PsiWhiteSpace
        }
        if (valueChild == null) {
            // A bare `return;` is only wrong when a real value is required.
            if (retType != "Unit") error(holder, returnStmt, "expected a return value of type '$retType'")
            continue
        }
        // Fires in BOTH directions: a wrong-typed value in a non-`Unit` fn, and any real value at
        // all in a `Unit` fn (nothing should be returned).
        val actual = inferExprType(valueChild, localTypes, ctx) ?: continue
        if (retType == "Unit") {
            error(holder, valueChild, "unexpected return value: this function returns 'Unit'")
        } else if (retType != actual) {
            error(holder, valueChild, "expected a return value of type '$retType', got '$actual'")
        }
    }
}

// `if 5 { }` / `while cond { }` -- the condition's own type, now via real inference
// (`inferExprType`) rather than only a DIRECT literal, so `if a + b { }`/`if self.flag() { }` are
// checked too, not just `if 5 { }`.
private fun checkConditionTypesIn(fnDecl: PsiElement, localTypes: Map<String, String>, ctx: HCTypeContext, holder: AnnotationHolder) {
    for (stmtType in listOf(HCElementTypes.IF_STMT, HCElementTypes.WHILE_STMT)) {
        for (stmt in elementsOfType(fnDecl, stmtType)) {
            val cond = directChildren(stmt).firstOrNull { it !is PsiWhiteSpace && it.node?.elementType != HCTokenTypes.KEYWORD } ?: continue
            val condType = inferExprType(cond, localTypes, ctx) ?: continue
            if (condType != "Bool") error(holder, cond, "condition must be 'Bool', got '$condType'")
        }
    }
}
