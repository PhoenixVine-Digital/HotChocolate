package hc.intellij

import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext

// Go-to-declaration (Ctrl+B / Ctrl+Click) -- resolves an `IDENT` token to whatever declaration it
// names, dispatching on the shape of the enclosing COMPOSITE node it's a direct child of (see
// `HCPsiParser`'s own `identLed`/`callOrPrimary`/`typeRef` headers for exactly which shape each
// composite has; this file mirrors those shapes rather than re-deriving them).
//
// References are registered on the COMPOSITE host nodes (`REF_EXPR`, `TYPE_REF`, ...), NOT on the
// bare `IDENT` leaf tokens themselves, with each reference's own `rangeInElement` narrowed down to
// just the relevant ident's own span within that host -- found empirically (a throwaway debug
// test) that this SDK's default `LeafPsiElement.getReferences()` does NOT route through
// `ReferenceProvidersRegistry` the way composite `ASTWrapperPsiElement`s do (calling
// `ReferenceProvidersRegistry.getReferencesFromProviders(leafIdent)` directly DOES find a
// registered-on-IDENT reference; `leafIdent.getReferences()` and `PsiFile.findReferenceAt` both
// come back empty for the exact same element), so a contributor pattern matching `IDENT` directly
// silently never fires in practice. Registering on the composite parent instead sidesteps that --
// `PsiFile.findReferenceAt`/`getReferenceAtCaretPosition` walk UP from the clicked leaf through
// its ancestors anyway, so the host node's own (correctly range-scoped) reference is still found.
//
// Deliberately conservative in the SAME direction as `HCAnnotator`/`HCTypeChecks` -- an ambiguous
// or unresolvable name just means Ctrl+B does nothing, never a wrong jump. Lower stakes than a
// false annotator error (a missing reference is just unhelpful, not misleading), but a WRONG jump
// actively misleads, so ambiguous cases still resolve to `null` rather than guessing.
//
// `directChildren`/`declaredName`/`elementsOfType`/`filesInScope` are small, deliberately
// duplicated copies of the same-named helpers in `HCAnnotator.kt`/`HCTypeChecks.kt` -- this
// project's established precedent (see `HCTypeChecks.kt`'s own header) rather than factoring out
// a shared internal module.
internal val REFERENCE_HOST_TYPES = com.intellij.psi.tree.TokenSet.create(
    HCElementTypes.REF_EXPR, HCElementTypes.TYPE_REF, HCElementTypes.STRUCT_LIT_EXPR,
    HCElementTypes.STATIC_CALL_EXPR, HCElementTypes.FIELD_ACCESS_EXPR, HCElementTypes.METHOD_CALL_EXPR,
    HCElementTypes.FIELD_INIT, HCElementTypes.CALL_EXPR, HCElementTypes.VARIANT_PATTERN,
)

class HCReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement().withElementType(REFERENCE_HOST_TYPES),
            HCReferenceProvider,
        )
    }
}

private object HCReferenceProvider : PsiReferenceProvider() {
    override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
        val file = element.containingFile ?: return PsiReference.EMPTY_ARRAY
        val targets = identTargetsFor(element, file)
        if (targets.isEmpty()) return PsiReference.EMPTY_ARRAY
        return targets.map { (identChild, target) -> HCReference(element, identChild, target) }.toTypedArray()
    }
}

private class HCReference(host: PsiElement, private val identChild: PsiElement, private val target: PsiElement) :
    PsiReferenceBase<PsiElement>(
        host,
        TextRange(identChild.startOffsetInParent, identChild.startOffsetInParent + identChild.textLength),
    ) {
    override fun resolve(): PsiElement = target

    // Rename (Shift+F6) support -- reuses the exact same "synthesize a fresh `IDENT` leaf via a
    // throwaway parse, then `PsiElement.replace()` it into place" mechanism `HCRenamePsiElement
    // Processor.kt` uses for the declaration side, applied here to `identChild` (the specific
    // sub-span of `element` this reference actually points at -- e.g. the trailing name in a
    // `FIELD_ACCESS_EXPR`, not the whole host node) rather than the whole reference's own
    // `element`.
    override fun handleElementRename(newElementName: String): PsiElement {
        replaceIdentLeaf(identChild, newElementName)
        return element
    }

    override fun getVariants(): Array<Any> = emptyArray()
}

internal fun directChildren(element: PsiElement): List<PsiElement> {
    val out = mutableListOf<PsiElement>()
    var c = element.firstChild
    while (c != null) {
        out.add(c)
        c = c.nextSibling
    }
    return out
}

internal fun declaredName(decl: PsiElement): PsiElement? =
    directChildren(decl).firstOrNull { it.node?.elementType == HCTokenTypes.IDENT }

internal fun elementsOfType(root: PsiElement, type: HCElementType): List<PsiElement> =
    PsiTreeUtil.collectElements(root) { it.node?.elementType == type }.toList()

// The nearest ancestor directory named `stdlib`, found by walking up from the file's own
// directory -- a real, disclosed heuristic (not a project-config lookup, since this plugin has no
// notion of "where is this project's stdlib") that happens to match this repo's own layout
// (`examples/**/*.hotc` alongside a root-level `stdlib/` -- see `HCRunConfiguration.kt`'s own
// header for the analogous "shell out to this project's own gradlew" assumption). Capped at 8
// levels so a file opened outside any HotChocolate-shaped project just quietly finds nothing
// rather than climbing to filesystem root. First match wins -- real projects have exactly one.
private fun findStdlibDir(file: PsiFile): PsiDirectory? {
    var dir = file.containingDirectory
    var depth = 0
    while (dir != null && depth < 8) {
        dir.findSubdirectory("stdlib")?.let { return it }
        dir = dir.parentDirectory
        depth++
    }
    return null
}

// Current file, every same-directory `.hc`/`.hotc` sibling, and every `.hc`/`.hotc` file in the
// project's `stdlib/` directory (if found) -- widened 2026-09-23 specifically so go-to-definition
// on a stdlib name (`Vec`, `Option`, `Registry`, an `extern class` binding a real JDK type, ...)
// actually resolves: `stdlib/*.hotc` lives outside every example's own directory, so it was never
// reachable through the plain same-directory heuristic below. Same disclosed heuristic as
// `HCAnnotator.collectTopLevelValueNames`'s own header for the same-directory half (real multi-
// file `hc run <dir>` programs share one flat namespace across every file in a directory).
internal fun filesInScope(file: PsiFile): List<PsiFile> {
    val sameDir = file.containingDirectory?.files?.filter { it !== file && it.language == HCLanguage } ?: emptyList()
    val stdlib = findStdlibDir(file)?.files?.filter { it !== file && it.language == HCLanguage } ?: emptyList()
    return listOf(file) + sameDir + stdlib
}

internal fun baseTypeNameOf(typeRef: PsiElement): String? {
    val kids = directChildren(typeRef)
    if (kids.any { it.node?.elementType == HCTokenTypes.LBRACKET }) return null
    return kids.firstOrNull { it.node?.elementType == HCTokenTypes.IDENT }?.text
}

private fun identTargetsFor(element: PsiElement, file: PsiFile): List<Pair<PsiElement, PsiElement>> {
    fun single(ident: PsiElement?, target: PsiElement?): List<Pair<PsiElement, PsiElement>> =
        if (ident != null && target != null) listOf(ident to target) else emptyList()

    return when (element.node?.elementType) {
        HCElementTypes.REF_EXPR -> {
            val ident = directChildren(element).firstOrNull { it.node?.elementType == HCTokenTypes.IDENT }
            single(ident, ident?.let { resolveValueRef(it, element, file) })
        }
        HCElementTypes.TYPE_REF -> {
            val ident = directChildren(element).firstOrNull { it.node?.elementType == HCTokenTypes.IDENT }
            single(ident, ident?.let { resolveTypeDeclByName(it.text, file) })
        }
        HCElementTypes.STRUCT_LIT_EXPR -> {
            val ident = directChildren(element).firstOrNull { it.node?.elementType == HCTokenTypes.IDENT }
            single(ident, ident?.let { resolveStructLitBase(it.text, file) })
        }
        HCElementTypes.STATIC_CALL_EXPR -> staticCallTargets(element, file)
        HCElementTypes.FIELD_ACCESS_EXPR -> fieldAccessTargets(element, file)
        HCElementTypes.METHOD_CALL_EXPR -> methodCallTargets(element, file)
        HCElementTypes.FIELD_INIT -> fieldInitTargets(element, file)
        HCElementTypes.CALL_EXPR -> arenaTargets(element, file)
        HCElementTypes.VARIANT_PATTERN -> variantPatternTargets(element, file)
        else -> emptyList()
    }
}

internal fun isTypeDecl(decl: PsiElement): Boolean = when (decl.node?.elementType) {
    HCElementTypes.STRUCT_DECL, HCElementTypes.ENUM_DECL, HCElementTypes.INTERFACE_DECL, HCElementTypes.EXTERN_CLASS_DECL -> true
    else -> false
}

internal fun resolveTypeDeclByName(name: String, file: PsiFile): PsiElement? {
    for (f in filesInScope(file)) {
        for (decl in directChildren(f)) {
            if (isTypeDecl(decl) && declaredName(decl)?.text == name) return declaredName(decl)
        }
    }
    return null
}

internal fun findEnclosingFnDecl(element: PsiElement): PsiElement? {
    var p: PsiElement? = element.parent
    while (p != null) {
        if (p.node?.elementType == HCElementTypes.FN_DECL) return p
        p = p.parent
    }
    return null
}

internal fun implTargetStructName(implDecl: PsiElement): String? {
    val kids = directChildren(implDecl)
    val forIdx = kids.indexOfFirst { it.node?.elementType == HCTokenTypes.KEYWORD && it.text == "for" }
    if (forIdx >= 0) return kids.drop(forIdx + 1).firstOrNull { it.node?.elementType == HCTokenTypes.IDENT }?.text
    return kids.firstOrNull { it.node?.elementType == HCTokenTypes.IDENT }?.text
}

internal fun findEnclosingImplTargetStruct(element: PsiElement): String? {
    var p: PsiElement? = element.parent
    while (p != null) {
        if (p.node?.elementType == HCElementTypes.IMPL_DECL) return implTargetStructName(p)
        p = p.parent
    }
    return null
}

// Local binding sites inside one `FN_DECL` -- same coverage as `HCAnnotator.collectLocalNames`
// (params incl. `self`, `let`, `for`, `catch`, `match`/`if let` pattern binds, lambda params) but
// keyed to the WINNING declaration site rather than just name membership -- first match wins,
// which is fine for jump-to-declaration even though a real program could in principle have more
// than one same-named candidate (same flow-insensitivity trade-off `collectLocalNames` accepts).
private fun findLocalBinding(fnDecl: PsiElement, name: String): PsiElement? {
    for (param in elementsOfType(fnDecl, HCElementTypes.PARAM)) {
        val n = declaredName(param); if (n?.text == name) return n
    }
    for (letStmt in elementsOfType(fnDecl, HCElementTypes.LET_STMT)) {
        val n = declaredName(letStmt); if (n?.text == name) return n
    }
    for (forStmt in elementsOfType(fnDecl, HCElementTypes.FOR_STMT)) {
        val n = declaredName(forStmt); if (n?.text == name) return n
    }
    // Comprehension bound variable -- see `HCAnnotator.collectLocalNames`'s own header for why
    // `declaredName` (first direct-child `IDENT`) is safe here too.
    for (comp in elementsOfType(fnDecl, HCElementTypes.COMPREHENSION_EXPR)) {
        val n = declaredName(comp); if (n?.text == name) return n
    }
    for (catchClause in elementsOfType(fnDecl, HCElementTypes.CATCH_CLAUSE)) {
        val n = declaredName(catchClause); if (n?.text == name) return n
    }
    for (pattern in elementsOfType(fnDecl, HCElementTypes.VARIANT_PATTERN)) {
        val kids = directChildren(pattern)
        // `{ field: bind }` or `(bind1, bind2)` (positional sugar) -- see `HCAnnotator.
        // collectLocalNames`'s own header for why both delimiters are checked here.
        val openIdx = kids.indexOfFirst { it.node?.elementType == HCTokenTypes.LBRACE || it.node?.elementType == HCTokenTypes.LPAREN }
        if (openIdx < 0) continue
        for (kid in kids.drop(openIdx + 1)) {
            if (kid.node?.elementType == HCTokenTypes.IDENT && kid.text == name) return kid
        }
    }
    for (lambda in elementsOfType(fnDecl, HCElementTypes.LAMBDA_EXPR)) {
        for (kid in directChildren(lambda)) {
            if (kid.node?.elementType == HCTokenTypes.IDENT && kid.text == name) return kid
        }
    }
    return null
}

internal fun resolveTopLevelValue(name: String, file: PsiFile): PsiElement? {
    for (f in filesInScope(file)) {
        for (decl in directChildren(f)) {
            when (decl.node?.elementType) {
                HCElementTypes.FN_DECL, HCElementTypes.STATIC_DECL -> {
                    val n = declaredName(decl); if (n?.text == name) return n
                }
                HCElementTypes.ENUM_DECL -> for (variant in directChildren(decl).filter { it.node?.elementType == HCElementTypes.ENUM_VARIANT }) {
                    val n = declaredName(variant); if (n?.text == name) return n
                }
                else -> {}
            }
        }
    }
    return null
}

// A bare `REF_EXPR` -- local binding wins over a same-named top-level declaration (real lexical
// shadowing), same order `HCAnnotator.checkUndefinedReferences` implicitly uses by unioning
// `topLevel + collectLocalNames(fnDecl)`. Also the resolution path for a bare call's own callee
// (`adder(total, a)` -- `adder` is itself a plain `REF_EXPR` wrapped in a `CALL_EXPR`, not a
// separate node shape; a lambda bound to a local, per `HCAnnotatorTest`'s own lambda-binding test,
// resolves correctly here without any `CALL_EXPR`-specific handling at all).
private fun resolveValueRef(ident: PsiElement, refExpr: PsiElement, file: PsiFile): PsiElement? {
    val name = ident.text
    findEnclosingFnDecl(refExpr)?.let { fnDecl -> findLocalBinding(fnDecl, name)?.let { return it } }
    return resolveTopLevelValue(name, file)
}

internal fun findFieldDecl(structName: String, fieldName: String, file: PsiFile): PsiElement? {
    for (f in filesInScope(file)) {
        for (decl in directChildren(f)) {
            if (decl.node?.elementType == HCElementTypes.STRUCT_DECL && declaredName(decl)?.text == structName) {
                for (fieldDecl in directChildren(decl).filter { it.node?.elementType == HCElementTypes.FIELD_DECL }) {
                    val n = declaredName(fieldDecl)
                    if (n?.text == fieldName) return n
                }
            }
        }
    }
    return null
}

internal fun findMethodDecl(structName: String, methodName: String, file: PsiFile): PsiElement? {
    for (f in filesInScope(file)) {
        for (implDecl in directChildren(f).filter { it.node?.elementType == HCElementTypes.IMPL_DECL }) {
            if (implTargetStructName(implDecl) != structName) continue
            for (fnDecl in directChildren(implDecl).filter { it.node?.elementType == HCElementTypes.FN_DECL }) {
                val n = declaredName(fnDecl)
                if (n?.text == methodName) return n
            }
        }
    }
    return null
}

// Fallback for a dot-chain receiver whose own type this pass doesn't know (a chained result, an
// untyped `let`, ...) -- same "unambiguous name across every struct in scope, else give up" rule
// `HCTypeChecks.checkFieldAndMethodAccess` uses for the same reason (see that fn's own header).
private fun findUniqueFieldDecl(fieldName: String, file: PsiFile): PsiElement? {
    val matches = mutableListOf<PsiElement>()
    for (f in filesInScope(file)) {
        for (decl in directChildren(f).filter { it.node?.elementType == HCElementTypes.STRUCT_DECL }) {
            for (fieldDecl in directChildren(decl).filter { it.node?.elementType == HCElementTypes.FIELD_DECL }) {
                val n = declaredName(fieldDecl)
                if (n?.text == fieldName) matches += n
            }
        }
    }
    return matches.singleOrNull()
}

private fun findUniqueMethodDecl(methodName: String, file: PsiFile): PsiElement? {
    val matches = mutableListOf<PsiElement>()
    for (f in filesInScope(file)) {
        for (implDecl in directChildren(f).filter { it.node?.elementType == HCElementTypes.IMPL_DECL }) {
            for (fnDecl in directChildren(implDecl).filter { it.node?.elementType == HCElementTypes.FN_DECL }) {
                val n = declaredName(fnDecl)
                if (n?.text == methodName) matches += n
            }
        }
    }
    return matches.singleOrNull()
}

// The base type name (generics stripped, see `HCTypeChecks.baseTypeName`'s own header for why
// only the base name is safe to use for THIS purpose) of a bare `REF_EXPR` receiver, using the
// SAME explicit-annotation-only, `self`-aware lookup `HCTypeChecks.collectLocalVarTypes` performs
// -- duplicated here rather than shared (see this file's own header).
internal fun receiverBaseType(receiver: PsiElement, fnDecl: PsiElement?, enclosingStruct: String?): String? {
    if (receiver.node?.elementType != HCElementTypes.REF_EXPR || fnDecl == null) return null
    val name = directChildren(receiver).firstOrNull { it.node?.elementType == HCTokenTypes.IDENT }?.text ?: return null
    if (name == "self") return enclosingStruct
    val paramList = directChildren(fnDecl).firstOrNull { it.node?.elementType == HCElementTypes.PARAM_LIST }
    for (param in paramList?.let { directChildren(it) }.orEmpty().filter { it.node?.elementType == HCElementTypes.PARAM }) {
        if (declaredName(param)?.text != name) continue
        val typeRef = directChildren(param).firstOrNull { it.node?.elementType == HCElementTypes.TYPE_REF } ?: return null
        return baseTypeNameOf(typeRef)
    }
    for (letStmt in elementsOfType(fnDecl, HCElementTypes.LET_STMT)) {
        if (declaredName(letStmt)?.text != name) continue
        val typeRef = directChildren(letStmt).firstOrNull { it.node?.elementType == HCElementTypes.TYPE_REF } ?: return null
        return baseTypeNameOf(typeRef)
    }
    return null
}

// `recv.field` (dot-chain shape: `[<receiver>, DOT, IDENT]`) vs. `Alias::field` (identLed's own
// `::`-no-call-no-braces branch, shape `[IDENT(alias), COLONCOLON, IDENT(field)]`) -- both parse
// to `FIELD_ACCESS_EXPR`, distinguished by which punctuation token is actually present. The
// static-field-read's OWN field ident is deliberately left unresolved -- it could equally be a
// real extern-class static field (unresolvable from HC source at all) or a bare qualified enum
// variant, and guessing wrong here would actively mislead, unlike simply not jumping.
private fun fieldAccessTargets(fieldAccess: PsiElement, file: PsiFile): List<Pair<PsiElement, PsiElement>> {
    val kids = directChildren(fieldAccess)
    if (kids.any { it.node?.elementType == HCTokenTypes.COLONCOLON }) {
        val aliasIdent = kids.firstOrNull { it.node?.elementType == HCTokenTypes.IDENT } ?: return emptyList()
        val target = resolveTypeDeclByName(aliasIdent.text, file) ?: return emptyList()
        return listOf(aliasIdent to target)
    }
    val dotIdx = kids.indexOfFirst { it.node?.elementType == HCTokenTypes.DOT }
    if (dotIdx < 0) return emptyList()
    val receiver = kids.getOrNull(dotIdx - 1) ?: return emptyList()
    val fieldIdent = kids.getOrNull(dotIdx + 1) ?: return emptyList()
    val fnDecl = findEnclosingFnDecl(fieldAccess)
    val structName = receiverBaseType(receiver, fnDecl, findEnclosingImplTargetStruct(fieldAccess))
    val target = if (structName != null) findFieldDecl(structName, fieldIdent.text, file) else findUniqueFieldDecl(fieldIdent.text, file)
    return if (target != null) listOf(fieldIdent to target) else emptyList()
}

private fun methodCallTargets(methodCall: PsiElement, file: PsiFile): List<Pair<PsiElement, PsiElement>> {
    val kids = directChildren(methodCall)
    val dotIdx = kids.indexOfFirst { it.node?.elementType == HCTokenTypes.DOT }
    if (dotIdx < 0) return emptyList()
    val receiver = kids.getOrNull(dotIdx - 1) ?: return emptyList()
    val methodIdent = kids.getOrNull(dotIdx + 1) ?: return emptyList()
    val fnDecl = findEnclosingFnDecl(methodCall)
    val structName = receiverBaseType(receiver, fnDecl, findEnclosingImplTargetStruct(methodCall))
    val target = if (structName != null) findMethodDecl(structName, methodIdent.text, file) else findUniqueMethodDecl(methodIdent.text, file)
    return if (target != null) listOf(methodIdent to target) else emptyList()
}

// `Alias::method(args)` -- `[IDENT(alias), COLONCOLON, IDENT(method), ARG_LIST]`.
private fun staticCallTargets(staticCall: PsiElement, file: PsiFile): List<Pair<PsiElement, PsiElement>> {
    val idents = directChildren(staticCall).filter { it.node?.elementType == HCTokenTypes.IDENT }
    val aliasIdent = idents.getOrNull(0)
    val methodIdent = idents.getOrNull(1)
    val out = mutableListOf<Pair<PsiElement, PsiElement>>()
    if (aliasIdent != null) resolveTypeDeclByName(aliasIdent.text, file)?.let { out += aliasIdent to it }
    if (aliasIdent != null && methodIdent != null) findMethodDecl(aliasIdent.text, methodIdent.text, file)?.let { out += methodIdent to it }
    return out
}

// `Name { ... }` / `Base::Variant { ... }` -- resolved as either a struct or a bare enum variant
// name (`Some { v: 1 }`-shaped, unqualified).
private fun resolveStructLitBase(name: String, file: PsiFile): PsiElement? {
    resolveTypeDeclByName(name, file)?.let { return it }
    for (f in filesInScope(file)) {
        for (decl in directChildren(f).filter { it.node?.elementType == HCElementTypes.ENUM_DECL }) {
            for (variant in directChildren(decl).filter { it.node?.elementType == HCElementTypes.ENUM_VARIANT }) {
                val n = declaredName(variant)
                if (n?.text == name) return n
            }
        }
    }
    return null
}

private fun fieldInitTargets(fieldInit: PsiElement, file: PsiFile): List<Pair<PsiElement, PsiElement>> {
    val nameIdent = directChildren(fieldInit).firstOrNull { it.node?.elementType == HCTokenTypes.IDENT } ?: return emptyList()
    val structLit = fieldInit.parent?.takeIf { it.node?.elementType == HCElementTypes.STRUCT_LIT_EXPR } ?: return emptyList()
    val litKids = directChildren(structLit)
    if (litKids.any { it.node?.elementType == HCTokenTypes.COLONCOLON }) return emptyList()
    val structNameIdent = litKids.firstOrNull { it.node?.elementType == HCTokenTypes.IDENT } ?: return emptyList()
    val target = findFieldDecl(structNameIdent.text, nameIdent.text, file) ?: return emptyList()
    return listOf(nameIdent to target)
}

private fun findEnumVariant(enumName: String, variantName: String, file: PsiFile): PsiElement? {
    for (f in filesInScope(file)) {
        for (decl in directChildren(f)) {
            if (decl.node?.elementType != HCElementTypes.ENUM_DECL || declaredName(decl)?.text != enumName) continue
            for (variant in directChildren(decl).filter { it.node?.elementType == HCElementTypes.ENUM_VARIANT }) {
                val n = declaredName(variant)
                if (n?.text == variantName) return n
            }
        }
    }
    return null
}

private fun findUniqueEnumVariant(variantName: String, file: PsiFile): PsiElement? {
    val matches = mutableListOf<PsiElement>()
    for (f in filesInScope(file)) {
        for (decl in directChildren(f).filter { it.node?.elementType == HCElementTypes.ENUM_DECL }) {
            for (variant in directChildren(decl).filter { it.node?.elementType == HCElementTypes.ENUM_VARIANT }) {
                val n = declaredName(variant)
                if (n?.text == variantName) matches += n
            }
        }
    }
    return matches.singleOrNull()
}

// A match arm's own pattern -- `Status::Active` (qualified: first ident is the ENUM name, real
// TYPE reference; second is the variant) or a bare `Active`/`Some { v: 1 }` (unqualified: resolved
// only when the variant name is unambiguous across every enum in scope, same "give up rather than
// guess" rule `findUniqueFieldDecl`/`findUniqueMethodDecl` use for the same reason). `_` (the
// wildcard pattern -- a plain `IDENT` in this lexer, see `HCPsiParser.variantPattern`'s own header)
// naturally resolves to nothing here, same as any other name matching no real variant.
private fun variantPatternTargets(pattern: PsiElement, file: PsiFile): List<Pair<PsiElement, PsiElement>> {
    val kids = directChildren(pattern)
    val firstIdent = kids.firstOrNull { it.node?.elementType == HCTokenTypes.IDENT } ?: return emptyList()
    val colonColonIdx = kids.indexOfFirst { it.node?.elementType == HCTokenTypes.COLONCOLON }
    if (colonColonIdx >= 0) {
        val out = mutableListOf<Pair<PsiElement, PsiElement>>()
        resolveTypeDeclByName(firstIdent.text, file)?.let { out += firstIdent to it }
        val variantIdent = kids.drop(colonColonIdx + 1).firstOrNull { it.node?.elementType == HCTokenTypes.IDENT }
        if (variantIdent != null) findEnumVariant(firstIdent.text, variantIdent.text, file)?.let { out += variantIdent to it }
        return out
    }
    val target = findUniqueEnumVariant(firstIdent.text, file) ?: return emptyList()
    return listOf(firstIdent to target)
}

// `arena Name[count]` -- the only `CALL_EXPR` shape whose own callee isn't a `REF_EXPR` (see
// `HCPsiParser.primary`'s own `arena` branch: `[KEYWORD("arena"), IDENT(structName), LBRACKET,
// <expr>, RBRACKET]`, no `ARG_LIST` at all).
private fun arenaTargets(callExpr: PsiElement, file: PsiFile): List<Pair<PsiElement, PsiElement>> {
    val kids = directChildren(callExpr)
    val isArena = kids.firstOrNull()?.let { it.node?.elementType == HCTokenTypes.KEYWORD && it.text == "arena" } == true
    if (!isArena) return emptyList()
    val structIdent = kids.getOrNull(1)?.takeIf { it.node?.elementType == HCTokenTypes.IDENT } ?: return emptyList()
    val target = resolveTypeDeclByName(structIdent.text, file) ?: return emptyList()
    return listOf(structIdent to target)
}
