package hc.intellij

import com.intellij.codeInsight.template.postfix.templates.PostfixTemplate
import com.intellij.codeInsight.template.postfix.templates.PostfixTemplateExpressionSelectorBase
import com.intellij.codeInsight.template.postfix.templates.PostfixTemplateProvider
import com.intellij.codeInsight.template.postfix.templates.StringBasedPostfixTemplate
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.Condition
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.TokenSet

// Postfix completion (`expr.if`, `expr.let`, ...) -- each template here is `StringBasedPostfixTemplate`,
// the same "substitute into a template string" mechanism live templates use under the hood, so a
// wrapped expression's own text is reused verbatim rather than reconstructed through PSI surgery.
// `HCExprAncestorsSelector` is the one piece that has to be hand-rolled: it walks UP from the PSI
// element the framework hands it (the leaf immediately before the typed `.`) collecting every
// enclosing expression node that ends at that exact offset, so `a + b.if` offers BOTH `b` and
// `a + b` as candidates, largest first -- `PostfixTemplateExpressionSelectorBase`'s own
// `getBorderOffsetFilter` does the actual offset filtering; this only needs to supply candidates.
class HCPostfixTemplateProvider : PostfixTemplateProvider {
    override fun getTemplates(): Set<PostfixTemplate> = setOf(
        HCStringPostfixTemplate("if", "if (expr) {}", "if (\$${StringBasedPostfixTemplate.EXPR}\$) {\n    \$END\$\n}", this),
        HCStringPostfixTemplate("while", "while (expr) {}", "while (\$${StringBasedPostfixTemplate.EXPR}\$) {\n    \$END\$\n}", this),
        HCStringPostfixTemplate("match", "match (expr) {}", "match (\$${StringBasedPostfixTemplate.EXPR}\$) {\n    \$END\$\n}", this),
        HCStringPostfixTemplate("not", "!expr", "!(\$${StringBasedPostfixTemplate.EXPR}\$)\$END\$", this),
        HCStringPostfixTemplate("let", "let name = expr;", "let \$NAME\$ = \$${StringBasedPostfixTemplate.EXPR}\$;\$END\$", this),
        HCStringPostfixTemplate("return", "return expr;", "return \$${StringBasedPostfixTemplate.EXPR}\$;\$END\$", this),
        HCStringPostfixTemplate("print", "print(expr);", "print(\$${StringBasedPostfixTemplate.EXPR}\$);\$END\$", this),
    )

    override fun isTerminalSymbol(currentChar: Char): Boolean = currentChar == '.'

    override fun preExpand(file: PsiFile, editor: Editor) {}
    override fun afterExpand(file: PsiFile, editor: Editor) {}
    override fun preCheck(copyFile: PsiFile, realEditor: Editor, currentOffset: Int): PsiFile = copyFile
}

private class HCStringPostfixTemplate(name: String, example: String, private val templateString: String, provider: PostfixTemplateProvider) :
    StringBasedPostfixTemplate(name, example, HCExprAncestorsSelector, provider) {
    override fun getTemplateString(element: PsiElement): String = templateString
    override fun getElementToRemove(expr: PsiElement): PsiElement = expr
}

private val EXPR_KINDS = TokenSet.create(
    HCElementTypes.BINARY_EXPR, HCElementTypes.UNARY_EXPR, HCElementTypes.BORROW_EXPR, HCElementTypes.CAST_EXPR,
    HCElementTypes.INSTANCE_OF_EXPR, HCElementTypes.CALL_EXPR, HCElementTypes.STATIC_CALL_EXPR,
    HCElementTypes.FIELD_ACCESS_EXPR, HCElementTypes.METHOD_CALL_EXPR, HCElementTypes.INDEX_EXPR,
    HCElementTypes.SLICE_EXPR,
    HCElementTypes.STRUCT_LIT_EXPR, HCElementTypes.ARRAY_LIT_EXPR, HCElementTypes.IF_EXPR, HCElementTypes.MATCH_EXPR,
    HCElementTypes.PAREN_EXPR, HCElementTypes.REF_EXPR, HCElementTypes.LITERAL_EXPR, HCElementTypes.STRING_INTERP_EXPR,
    HCElementTypes.CLASS_LIT_EXPR,
)

internal object HCExprAncestorsSelector : PostfixTemplateExpressionSelectorBase(Condition<PsiElement> { true }) {
    override fun getNonFilteredExpressions(context: PsiElement, document: Document, offset: Int): List<PsiElement> {
        val result = mutableListOf<PsiElement>()
        var el: PsiElement? = context
        while (el != null) {
            if (EXPR_KINDS.contains(el.node?.elementType)) result.add(el)
            el = el.parent
        }
        return result
    }
}
