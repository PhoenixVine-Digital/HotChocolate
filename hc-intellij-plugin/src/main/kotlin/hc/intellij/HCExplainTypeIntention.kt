package hc.intellij

import com.intellij.codeInsight.hint.HintManager
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.TokenSet

// "Explain inferred type" (Alt+Enter on any expression) -- the user-facing surface for
// `HCTypeProvenance.kt`'s own reasoning-chain builder. Deliberately an intention rather than a
// hover/quick-doc extension: it works uniformly for ANY expression under the caret (a bare local,
// a chained method call, a struct literal, ...), not just a named symbol hover naturally resolves
// to, and its result (a real reasoning CHAIN, not a one-line answer) reads better in a hint popup
// the user explicitly asked for than crammed into quick-documentation's own compact popup.
private val EXPR_KINDS = TokenSet.create(
    HCElementTypes.LITERAL_EXPR, HCElementTypes.STRING_INTERP_EXPR, HCElementTypes.PAREN_EXPR, HCElementTypes.BORROW_EXPR,
    HCElementTypes.UNARY_EXPR, HCElementTypes.INSTANCE_OF_EXPR, HCElementTypes.CAST_EXPR, HCElementTypes.BINARY_EXPR,
    HCElementTypes.REF_EXPR, HCElementTypes.CALL_EXPR, HCElementTypes.STATIC_CALL_EXPR, HCElementTypes.METHOD_CALL_EXPR,
    HCElementTypes.FIELD_ACCESS_EXPR, HCElementTypes.STRUCT_LIT_EXPR,
)

class HCExplainTypeIntention : IntentionAction {
    override fun getText(): String = "Explain inferred type"
    override fun getFamilyName(): String = "HotChocolate"

    override fun isAvailable(project: Project, editor: Editor, file: PsiFile): Boolean =
        file.language == HCLanguage && findExprAtCaret(file, editor.caretModel.offset) != null

    override fun invoke(project: Project, editor: Editor, file: PsiFile) {
        val expr = findExprAtCaret(file, editor.caretModel.offset) ?: return
        val fnDecl = enclosingFnDecl(expr) ?: return
        val text = explainExprType(expr, fnDecl, file)
        val html = "<html>" + text.trimEnd().split("\n").joinToString("<br/>") { line ->
            line.replace("  ", "&nbsp;&nbsp;")
        } + "</html>"
        HintManager.getInstance().showInformationHint(editor, html)
    }

    override fun startInWriteAction(): Boolean = false
}

private fun enclosingFnDecl(element: PsiElement): PsiElement? {
    var el: PsiElement? = element
    while (el != null && el !is PsiFile) {
        if (el.node?.elementType == HCElementTypes.FN_DECL) return el
        el = el.parent
    }
    return null
}

// Walks UP from the leaf at the caret to the SMALLEST enclosing node this pass actually infers a
// type for -- a caret resting on, say, the `+` operator or the callee name inside a call still
// resolves to the whole containing `BINARY_EXPR`/`CALL_EXPR`, matching how "select the whole
// meaningful thing" intentions elsewhere in the IDE behave.
private fun findExprAtCaret(file: PsiFile, offset: Int): PsiElement? {
    var el: PsiElement? = file.findElementAt(offset) ?: file.findElementAt(offset - 1) ?: return null
    while (el != null && el !is PsiFile) {
        if (EXPR_KINDS.contains(el.node?.elementType)) return el
        el = el.parent
    }
    return null
}
