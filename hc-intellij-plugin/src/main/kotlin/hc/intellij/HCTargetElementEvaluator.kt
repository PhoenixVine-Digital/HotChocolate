package hc.intellij

import com.intellij.codeInsight.TargetElementEvaluatorEx2
import com.intellij.psi.PsiElement

// Fixes the SAME root gap `HCRenameHandler.kt` found and fixed for Shift+F6 (see that file's own
// header), but for `TargetElementUtil`'s `ELEMENT_NAME_ACCEPTED` check directly, which is what
// Quick Definition (Ctrl+Shift+I, action id `QuickImplementations`) uses -- and, unlike rename,
// has no per-action customization point of its own to intercept ahead of it. Confirmed via a
// throwaway debug test: `TargetElementUtil.findTargetElement` at a USAGE site (the caret on
// `add` inside `add(1, 2)`) correctly resolves to the declaration already (that path goes through
// `REFERENCED_ELEMENT_ACCEPTED`, backed by `HCReferenceContributor`, already real); at the
// DECLARATION site itself (the caret on `add` inside `fn add(...)`) it came back `null`, because
// `ELEMENT_NAME_ACCEPTED` only recognizes `PsiNamedElement`s, and every PSI element in this plugin
// is a plain generic `LeafPsiElement`/`ASTWrapperPsiElement` (see `HCRenamePsiElementProcessor.
// kt`'s own header).
//
// `getNamedElement` is exactly the platform's OWN extension point for "what does 'the named thing
// at this leaf' mean for your language" -- overriding it fixes `ELEMENT_NAME_ACCEPTED` for EVERY
// consumer of `TargetElementUtil`, not just Quick Definition, unlike `HCRenameHandler`'s own
// narrower, action-specific fix (kept as-is since it already works and touching it isn't needed).
class HCTargetElementEvaluator : TargetElementEvaluatorEx2() {
    override fun getNamedElement(element: PsiElement): PsiElement? =
        if (element.node?.elementType == HCTokenTypes.IDENT && isDeclarationAnchor(element)) element else null
}
