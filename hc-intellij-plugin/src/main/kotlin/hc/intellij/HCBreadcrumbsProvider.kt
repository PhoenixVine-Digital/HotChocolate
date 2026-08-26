package hc.intellij

import com.intellij.lang.Language
import com.intellij.psi.PsiElement
import com.intellij.ui.breadcrumbs.BreadcrumbsProvider

// The trail at the top of the editor (struct > impl > fn > if > match, ...) -- a plain, pure
// mapping from a PSI node's own shape to a short label, no new resolution logic needed at all
// (reuses `declaredName`/`implTargetStructName` from `HCReferences.kt`). Scoped to the node kinds
// worth showing a crumb for: top-level/impl declarations (real navigational landmarks) plus the
// handful of control-flow constructs a reader might want to jump back out of (`if`/`while`/`for`/
// `match`/`try`) -- everything else (individual statements, expressions, ...) stays out of the
// trail on purpose, matching how e.g. the Java/Kotlin plugins scope their own breadcrumbs to
// declarations and control flow rather than every single statement.
class HCBreadcrumbsProvider : BreadcrumbsProvider {
    override fun getLanguages(): Array<Language> = arrayOf(HCLanguage)

    override fun acceptElement(e: PsiElement): Boolean = when (e.node?.elementType) {
        HCElementTypes.FN_DECL, HCElementTypes.STRUCT_DECL, HCElementTypes.ENUM_DECL,
        HCElementTypes.INTERFACE_DECL, HCElementTypes.IMPL_DECL, HCElementTypes.EXTERN_CLASS_DECL,
        HCElementTypes.IF_STMT, HCElementTypes.WHILE_STMT, HCElementTypes.FOR_STMT,
        HCElementTypes.MATCH_STMT, HCElementTypes.MATCH_ARM, HCElementTypes.TRY_STMT,
        -> true
        else -> false
    }

    override fun getElementInfo(e: PsiElement): String = when (e.node?.elementType) {
        HCElementTypes.FN_DECL -> "fn " + name(e)
        HCElementTypes.STRUCT_DECL -> "struct " + name(e)
        HCElementTypes.ENUM_DECL -> "enum " + name(e)
        HCElementTypes.INTERFACE_DECL -> "interface " + name(e)
        HCElementTypes.EXTERN_CLASS_DECL -> "extern class " + name(e)
        HCElementTypes.IMPL_DECL -> "impl " + (implTargetStructName(e) ?: "")
        HCElementTypes.IF_STMT -> "if"
        HCElementTypes.WHILE_STMT -> "while"
        HCElementTypes.FOR_STMT -> "for"
        HCElementTypes.MATCH_STMT -> "match"
        HCElementTypes.MATCH_ARM -> "=>"
        HCElementTypes.TRY_STMT -> "try"
        else -> e.text
    }

    private fun name(decl: PsiElement): String = declaredName(decl)?.text ?: ""
}
