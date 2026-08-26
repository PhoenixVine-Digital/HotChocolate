package hc.intellij

import com.intellij.lang.refactoring.RefactoringSupportProvider
import com.intellij.psi.PsiElement
import com.intellij.refactoring.RefactoringActionHandler
import com.intellij.refactoring.changeSignature.ChangeSignatureHandler

// The missing piece Safe Delete needed even after `HCSafeDeleteProcessorDelegate` correctly
// handled deletion: `SafeDeleteAction.isEnabledOnElements` (the check controlling whether the
// action is grayed out) ALSO consults this provider's `isSafeDeleteAvailable`, independent of
// whether a `SafeDeleteProcessorDelegate` handles the element -- found empirically (a throwaway
// debug test showed `isEnabledOnElements` staying `false` even once the delegate's own
// `handlesElement` returned `true` and the full deletion flow already worked correctly end-to-end
// when driven directly). Reuses the exact same "is this a real deletable declaration" check the
// delegate itself uses, so the two can never disagree.
//
// Also this language's per-feature dispatch point for Extract/Introduce Variable
// (`getIntroduceVariableHandler`) -- the platform's own Introduce Variable action consults this
// provider directly, no separate handler-registry indirection the way Rename needed.
class HCRefactoringSupportProvider : RefactoringSupportProvider() {
    override fun isSafeDeleteAvailable(element: PsiElement): Boolean =
        element.language == HCLanguage && (isDeclarationAnchor(element) || isDeletableDeclarationKind(element))

    override fun getIntroduceVariableHandler(): RefactoringActionHandler = HCIntroduceVariableHandler()

    override fun getChangeSignatureHandler(): ChangeSignatureHandler = HCChangeSignatureHandler()
}

private fun isDeletableDeclarationKind(element: PsiElement): Boolean = when (element.node?.elementType) {
    HCElementTypes.FN_DECL, HCElementTypes.STRUCT_DECL, HCElementTypes.ENUM_DECL, HCElementTypes.INTERFACE_DECL,
    HCElementTypes.EXTERN_CLASS_DECL, HCElementTypes.STATIC_DECL, HCElementTypes.PARAM, HCElementTypes.LET_STMT,
    HCElementTypes.FIELD_DECL, HCElementTypes.ENUM_VARIANT,
    -> true
    else -> false
}
