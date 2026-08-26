package hc.intellij

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.refactoring.safeDelete.NonCodeUsageSearchInfo
import com.intellij.refactoring.safeDelete.SafeDeleteProcessorDelegate
import com.intellij.refactoring.safeDelete.usageInfo.SafeDeleteReferenceSimpleDeleteUsageInfo
import com.intellij.usageView.UsageInfo

// Safe Delete -- `element` can be EITHER a real declaration composite (`FN_DECL`, `PARAM`, ...,
// when invoked programmatically with the composite directly) OR the bare declaration-name `IDENT`
// leaf (when invoked from the editor caret): confirmed empirically (via a throwaway debug test)
// that `BaseRefactoringAction.getElementAtCaret` -- what Safe Delete's own action actually resolves
// the caret to -- goes through the EXACT SAME `TargetElementUtil.findTargetElement` call as Rename
// and Quick Definition, landing on the same bare ident `HCTargetElementEvaluator.getNamedElement`
// returns for all three. There is no way to tell these three consumers apart at that shared layer,
// so unlike Rename (which has its own pluggable `RenameHandler` to intercept ahead of it -- see
// `HCRenameHandler.kt`), Safe Delete has to accept the ident as `element` and only remap AFTER
// the fact, via `getAdditionalElementsToDelete`.
//
// A first attempt at that remap -- deleting the ident's PARENT from inside `prepareForDeletion`,
// hoping the framework would gracefully skip its own later delete of the (now invalid) ident --
// produced a silently WRONG result instead (`fn (a: Int, b: Int) { }`: the name gone, the rest of
// the declaration left behind). What DOES work, confirmed empirically: returning the ident's
// parent from `getAdditionalElementsToDelete` and letting `SafeDeleteProcessor` itself handle the
// resulting "one element is INSIDE another element also being deleted" case -- it already has
// exactly that logic built in (`SafeDeleteProcessor.isInside`), and passing `[ident, ident.parent]`
// together produces a clean, complete deletion of the whole declaration.
class HCSafeDeleteProcessorDelegate : SafeDeleteProcessorDelegate {
    override fun handlesElement(element: PsiElement): Boolean =
        element.language == HCLanguage && (isDeletableDeclaration(element) || isDeclarationAnchor(element))

    override fun findUsages(
        element: PsiElement, allElementsToDelete: Array<out PsiElement>, result: MutableList<in UsageInfo>,
    ): NonCodeUsageSearchInfo? {
        val nameAnchor = if (element.node?.elementType == HCTokenTypes.IDENT) element else declaredName(element) ?: return null
        for (ref in ReferencesSearch.search(nameAnchor).findAll()) {
            result.add(SafeDeleteReferenceSimpleDeleteUsageInfo(ref.element, element, false))
        }
        return null
    }

    override fun getElementsToSearch(element: PsiElement, allElementsToDelete: Collection<PsiElement>): Collection<PsiElement> =
        listOf(element)

    // `PARAM`/`FIELD_DECL`/`ENUM_VARIANT` all sit in a plain comma-separated list
    // (`PARAM_LIST`/`fieldList`, see `HCPsiParser`'s own shape for both) -- generic PSI deletion
    // only excises the target ITSELF, leaving a dangling comma behind (found via a throwaway debug
    // test: deleting the FIRST param of `fn add(a: Int, b: Int)` left `fn add(, b: Int)`, a real
    // syntax error). Handles both shapes `element` can arrive in: the bare declaration-anchor
    // IDENT (editor caret invocation -- remap to its parent, PLUS that parent's own adjacent comma
    // if the parent itself is a comma-list member) and the composite comma-list member directly
    // (programmatic invocation -- just the adjacent comma). Prefers the comma AFTER the node
    // (correct for the first/middle position, where the list continues to its right); falls back
    // to the one BEFORE it (correct for the last position). A lone single-item list has no comma
    // at all either way, so this is a safe no-op there.
    override fun getAdditionalElementsToDelete(
        element: PsiElement, allElementsToDelete: Collection<PsiElement>, askUser: Boolean,
    ): Collection<PsiElement>? {
        if (element.node?.elementType == HCTokenTypes.IDENT && isDeclarationAnchor(element)) {
            val parent = element.parent ?: return null
            val extra = if (isCommaListMember(parent)) adjacentComma(parent) else null
            return listOfNotNull(parent, extra)
        }
        if (isCommaListMember(element)) return listOfNotNull(adjacentComma(element))
        return null
    }

    override fun findConflicts(element: PsiElement, allElementsToDelete: Array<out PsiElement>): Collection<String>? = null

    override fun preprocessUsages(project: Project, usages: Array<UsageInfo>): Array<UsageInfo> = usages

    override fun prepareForDeletion(element: PsiElement) {}

    override fun isToSearchInComments(element: PsiElement): Boolean = false
    override fun setToSearchInComments(element: PsiElement, enabled: Boolean) {}
    override fun isToSearchForTextOccurrences(element: PsiElement): Boolean = false
    override fun setToSearchForTextOccurrences(element: PsiElement, enabled: Boolean) {}
}

private fun adjacentComma(element: PsiElement): PsiElement? {
    val nextComma = generateSequence(element.nextSibling) { it.nextSibling }
        .firstOrNull { it !is PsiWhiteSpace }
        ?.takeIf { it.node?.elementType == HCTokenTypes.COMMA }
    val prevComma = generateSequence(element.prevSibling) { it.prevSibling }
        .firstOrNull { it !is PsiWhiteSpace }
        ?.takeIf { it.node?.elementType == HCTokenTypes.COMMA }
    return nextComma ?: prevComma
}

private fun isCommaListMember(element: PsiElement): Boolean = when (element.node?.elementType) {
    HCElementTypes.PARAM, HCElementTypes.FIELD_DECL, HCElementTypes.ENUM_VARIANT -> true
    else -> false
}

private fun isDeletableDeclaration(element: PsiElement): Boolean = when (element.node?.elementType) {
    HCElementTypes.FN_DECL, HCElementTypes.STRUCT_DECL, HCElementTypes.ENUM_DECL, HCElementTypes.INTERFACE_DECL,
    HCElementTypes.EXTERN_CLASS_DECL, HCElementTypes.STATIC_DECL, HCElementTypes.PARAM, HCElementTypes.LET_STMT,
    HCElementTypes.FIELD_DECL, HCElementTypes.ENUM_VARIANT,
    -> true
    else -> false
}
