package hc.intellij

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.refactoring.RefactoringActionHandler

// Extract/Introduce Variable -- a plain DOCUMENT text edit (same established pattern as
// `HCQuickFixes.kt`), not a real PSI-tree construction: replace the selected/caret expression's
// own occurrence with a new variable name, and insert `let <name> = <expr>;` right before the
// expression's own enclosing statement. Deliberately no in-place rename template for the new
// name (unlike a "real" IDE's usual immediate-inline-edit UX) -- the inserted name is a plain,
// fixed `extracted`, editable afterward via Rename (already real and working); this keeps the
// whole operation deterministic and directly testable, matching this plugin's own established
// preference for a document edit over anything requiring live UI interaction to verify.
class HCIntroduceVariableHandler : RefactoringActionHandler {
    override fun invoke(project: Project, editor: Editor, file: PsiFile, dataContext: DataContext) {
        val expr = findTargetExpression(editor, file) ?: return
        val stmt = findEnclosingStatement(expr) ?: return
        val document = editor.document
        val varName = "extracted"
        val exprText = expr.text
        val indent = leadingWhitespaceOf(document, stmt.textRange.startOffset)
        val stmtStart = stmt.textRange.startOffset
        val exprRange = expr.textRange

        WriteCommandAction.runWriteCommandAction(project, "Introduce Variable", null, {
            // Replace the (higher-offset) expression occurrence FIRST, then insert the
            // (lower-offset) declaration -- inserting first would shift `exprRange` out from
            // under itself.
            document.replaceString(exprRange.startOffset, exprRange.endOffset, varName)
            document.insertString(stmtStart, "let $varName = $exprText;\n$indent")
            PsiDocumentManager.getInstance(project).commitDocument(document)
        })

        editor.caretModel.moveToOffset(stmtStart + "let $varName = $exprText;\n$indent".length)
    }

    // Invoked from a non-editor context (e.g. a project-view selection) -- not meaningfully
    // supported here, since introducing a variable always needs an in-source expression/caret.
    override fun invoke(project: Project, elements: Array<out PsiElement>, dataContext: DataContext?) {}
}

private val EXPRESSION_KINDS = setOf(
    HCElementTypes.LITERAL_EXPR, HCElementTypes.BINARY_EXPR, HCElementTypes.UNARY_EXPR, HCElementTypes.CALL_EXPR,
    HCElementTypes.METHOD_CALL_EXPR, HCElementTypes.STATIC_CALL_EXPR, HCElementTypes.FIELD_ACCESS_EXPR,
    HCElementTypes.REF_EXPR, HCElementTypes.STRUCT_LIT_EXPR, HCElementTypes.PAREN_EXPR, HCElementTypes.INDEX_EXPR,
    HCElementTypes.SLICE_EXPR,
    HCElementTypes.ARRAY_LIT_EXPR, HCElementTypes.IF_EXPR, HCElementTypes.MATCH_EXPR, HCElementTypes.STRING_INTERP_EXPR,
    HCElementTypes.INSTANCE_OF_EXPR, HCElementTypes.CAST_EXPR, HCElementTypes.BORROW_EXPR, HCElementTypes.CLASS_LIT_EXPR,
)

private fun isExpressionKind(element: PsiElement): Boolean = EXPRESSION_KINDS.contains(element.node?.elementType)

// A real editor selection is honored EXACTLY (extended up to the smallest real expression node
// whose own range matches it, same as a real IDE's own "Introduce Variable" selection handling);
// with no selection, the expression directly at the caret is used instead.
private fun findTargetExpression(editor: Editor, file: PsiFile): PsiElement? {
    if (editor.selectionModel.hasSelection()) {
        val start = editor.selectionModel.selectionStart
        val end = editor.selectionModel.selectionEnd
        var el: PsiElement = file.findElementAt(start) ?: return null
        while (el.textRange.startOffset > start || el.textRange.endOffset < end) {
            el = el.parent ?: return null
        }
        var cur: PsiElement? = el
        while (cur != null) {
            if (isExpressionKind(cur) && cur.textRange.startOffset == start && cur.textRange.endOffset == end) return cur
            cur = cur.parent
        }
        cur = el
        while (cur != null) {
            if (isExpressionKind(cur)) return cur
            cur = cur.parent
        }
        return null
    }
    var el: PsiElement = file.findElementAt(editor.caretModel.offset)
        ?: file.findElementAt(editor.caretModel.offset - 1)
        ?: return null
    while (!isExpressionKind(el)) {
        el = el.parent ?: return null
    }
    // A bare `REF_EXPR` serving as a `CALL_EXPR`'s own callee (the caret landed inside a function
    // NAME, e.g. `fo|o()`) is almost never the useful thing to extract on its own -- extracting
    // just `foo` and replacing the callee with it turns a real call into `extracted()`, calling a
    // plain variable as if it were a function, which is nonsensical. Prefer the whole call instead.
    if (el.node?.elementType == HCElementTypes.REF_EXPR && el.parent?.node?.elementType == HCElementTypes.CALL_EXPR) {
        return el.parent
    }
    return el
}

// The nearest ancestor whose own direct PARENT is a `BLOCK` -- the real statement-level node to
// insert the new `let` declaration right before.
private fun findEnclosingStatement(expr: PsiElement): PsiElement? {
    var p: PsiElement = expr
    while (true) {
        val parent = p.parent ?: return null
        if (parent.node?.elementType == HCElementTypes.BLOCK) return p
        p = parent
    }
}

private fun leadingWhitespaceOf(document: Document, offset: Int): String {
    val lineStart = document.getLineStartOffset(document.getLineNumber(offset))
    return document.charsSequence.subSequence(lineStart, offset).takeWhile { it == ' ' || it == '\t' }.toString()
}
