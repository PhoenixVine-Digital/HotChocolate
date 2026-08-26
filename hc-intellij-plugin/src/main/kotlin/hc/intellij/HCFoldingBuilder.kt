package hc.intellij

import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil

// Code folding -- two independent kinds of region, both purely structural (no semantic checking
// needed, matching this feature's usual scope in other language plugins):
//   1. Any composite node with a direct `{`/`}` child pair that actually spans multiple lines
//      (`BLOCK`, but also `STRUCT_LIT_EXPR`/`ENUM_DECL`/`IMPL_DECL`/... -- deliberately NOT
//      special-cased per element kind, since "has a brace-delimited body" is the one property
//      that actually matters for folding, and checking it structurally covers every current AND
//      future brace-bodied node the same way) collapses to `{...}`.
//   2. A run of two or more consecutive `//`/`///` comment lines (no blank line or other content
//      between them) collapses together, mirroring how comment-block folding works in most
//      language plugins (Java's own import-block folding is the closest analogue for "several
//      adjacent trivial lines become one line").
class HCFoldingBuilder : FoldingBuilderEx(), DumbAware {
    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        if (root.language != HCLanguage) return FoldingDescriptor.EMPTY_ARRAY
        val descriptors = mutableListOf<FoldingDescriptor>()
        collectBraceFolds(root, document, descriptors)
        collectCommentRunFolds(root, descriptors)
        return descriptors.toTypedArray()
    }

    override fun getPlaceholderText(node: ASTNode): String = "{...}"

    override fun isCollapsedByDefault(node: ASTNode): Boolean = false
}

private fun collectBraceFolds(element: PsiElement, document: Document, out: MutableList<FoldingDescriptor>) {
    val lbrace = directChildren(element).firstOrNull { it.node?.elementType == HCTokenTypes.LBRACE }
    val rbrace = directChildren(element).lastOrNull { it.node?.elementType == HCTokenTypes.RBRACE }
    if (lbrace != null && rbrace != null && rbrace.textRange.startOffset > lbrace.textRange.endOffset) {
        val startLine = document.getLineNumber(lbrace.textRange.endOffset)
        val endLine = document.getLineNumber(rbrace.textRange.startOffset)
        if (endLine > startLine) {
            out.add(FoldingDescriptor(element.node!!, com.intellij.openapi.util.TextRange(lbrace.textRange.startOffset, rbrace.textRange.endOffset)))
        }
    }
    for (child in directChildren(element)) collectBraceFolds(child, document, out)
}

// Walks the tree once collecting every comment LEAF (`LINE_COMMENT`/`DOC_COMMENT` tokens, which
// -- like any comment -- are automatically bound into the tree as `PsiComment` siblings rather
// than needing to be found via any grammar rule) and groups adjacent runs by sibling chain,
// skipping over whitespace that contains no blank line.
private fun collectCommentRunFolds(root: PsiElement, out: MutableList<FoldingDescriptor>) {
    val allComments = PsiTreeUtil.collectElementsOfType(root, PsiComment::class.java)
    val visited = mutableSetOf<PsiElement>()
    for (comment in allComments) {
        if (comment in visited) continue
        val run = mutableListOf(comment)
        visited.add(comment)
        var next = nextCommentSibling(comment)
        while (next != null) {
            run.add(next)
            visited.add(next)
            next = nextCommentSibling(next)
        }
        if (run.size >= 2) {
            val range = com.intellij.openapi.util.TextRange(run.first().textRange.startOffset, run.last().textRange.endOffset)
            out.add(FoldingDescriptor(run.first().node!!, range, null, run.first().text.trim() + " ..."))
        }
    }
}

private fun nextCommentSibling(comment: PsiElement): PsiComment? {
    var sib = comment.nextSibling
    while (sib is PsiWhiteSpace) {
        if (sib.text.count { it == '\n' } >= 2) return null
        sib = sib.nextSibling
    }
    return sib as? PsiComment
}
