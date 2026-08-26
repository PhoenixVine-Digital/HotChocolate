package hc.intellij

import com.intellij.lang.refactoring.InlineHandler
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.usageView.UsageInfo
import com.intellij.util.containers.MultiMap

// Inline Variable (Ctrl+Alt+N) -- the reverse of `HCIntroduceVariableHandler`: replaces every
// usage of a `let`-bound local with its own init expression's text, then removes the `let`
// itself. `InlineHandler` (unlike `SafeDeleteProcessorDelegate`) already handles usage discovery
// FOR you via the platform's own real `ReferencesSearch` machinery (backed by
// `HCReferencesSearcher`, already real) -- `createInliner`'s `Inliner.inlineUsage` is called once
// per usage found that way, so no separate `ReferencesSearch.search` call is needed here at all.
//
// Scoped to `let`-bound locals only for v1 (mirrors Extract Variable's own scope) -- inlining a
// function or a struct field is a substantially different, riskier operation (multi-statement
// bodies, `self`, generics) not attempted here.
class HCInlineHandler : InlineHandler {
    override fun canInlineElement(element: PsiElement): Boolean =
        element.language == HCLanguage && element.node?.elementType == HCTokenTypes.IDENT &&
            element.parent?.node?.elementType == HCElementTypes.LET_STMT

    override fun prepareInlineElement(element: PsiElement, editor: Editor?, invokedOnReference: Boolean): InlineHandler.Settings? {
        val letStmt = element.parent ?: return null
        letInitExprOf(letStmt) ?: return null
        return object : InlineHandler.Settings {
            override fun isOnlyOneReferenceToInline(): Boolean = false
        }
    }

    override fun removeDefinition(element: PsiElement, settings: InlineHandler.Settings) {
        element.parent?.delete()
    }

    override fun createInliner(element: PsiElement, settings: InlineHandler.Settings): InlineHandler.Inliner? {
        val letStmt = element.parent ?: return null
        val initExpr = letInitExprOf(letStmt) ?: return null
        val replacementText = if (needsParensWhenInlined(initExpr)) "(${initExpr.text})" else initExpr.text
        return HCInliner(replacementText)
    }
}

// A precedence-safety rule, not a style preference: inlining `let x = a + b;` into `x * c` MUST
// become `(a + b) * c`, not the silently-wrong `a + b * c`. Every OTHER expression kind used here
// already binds as tightly as (or tighter than) any surrounding context could need (a bare
// literal/ident, any call/access/index chain, a parenthesized/struct-literal/array-literal
// expression, ...), so wrapping them too would just be visual noise, not a correctness need.
private val NEEDS_PARENS_KINDS = setOf(
    HCElementTypes.BINARY_EXPR, HCElementTypes.UNARY_EXPR, HCElementTypes.CAST_EXPR,
    HCElementTypes.INSTANCE_OF_EXPR, HCElementTypes.IF_EXPR, HCElementTypes.MATCH_EXPR, HCElementTypes.LAMBDA_EXPR,
)

private fun needsParensWhenInlined(expr: PsiElement): Boolean = NEEDS_PARENS_KINDS.contains(expr.node?.elementType)

private class HCInliner(private val replacementText: String) : InlineHandler.Inliner {
    override fun getConflicts(reference: PsiReference, referenced: PsiElement): MultiMap<PsiElement, String> = MultiMap.empty()

    override fun inlineUsage(usage: UsageInfo, referenced: PsiElement) {
        val refElement = usage.element ?: return
        val project = refElement.project
        val file = refElement.containingFile ?: return
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return
        val range = refElement.textRange
        document.replaceString(range.startOffset, range.endOffset, replacementText)
        PsiDocumentManager.getInstance(project).commitDocument(document)
    }
}
