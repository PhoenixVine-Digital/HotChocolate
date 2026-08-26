package hc.intellij

import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.util.Ref
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile

// Continues a `///` doc comment on Enter (`/// summary<Enter>` -> the next line starts with
// `/// ` too, matching indentation) -- `HCCommenter` only declares `//` as the plain line-comment
// prefix (correctly; `///` is a DIFFERENT token, `DOC_COMMENT`, that the grammar parses as its own
// real construct, not just a stylistic `//`), so the platform's generic "continue the line comment
// on Enter" behavior never recognizes `///` as something to continue at all without this.
class HCEnterHandlerDelegate : EnterHandlerDelegate {
    override fun preprocessEnter(
        file: PsiFile,
        editor: Editor,
        caretOffsetRef: Ref<Int>,
        caretAdvanceRef: Ref<Int>,
        dataContext: DataContext,
        originalHandler: EditorActionHandler?,
    ): EnterHandlerDelegate.Result {
        if (file.language != HCLanguage) return EnterHandlerDelegate.Result.Continue
        val document = editor.document
        val offset = caretOffsetRef.get()
        val lineNumber = document.getLineNumber(offset)
        val lineStart = document.getLineStartOffset(lineNumber)
        val beforeCaret = document.getText(TextRange(lineStart, offset))
        if (!beforeCaret.trimStart().startsWith("///")) return EnterHandlerDelegate.Result.Continue

        val indent = beforeCaret.takeWhile { it == ' ' || it == '\t' }
        val insertion = "\n$indent/// "
        document.insertString(offset, insertion)
        caretOffsetRef.set(offset + insertion.length)
        caretAdvanceRef.set(0)
        return EnterHandlerDelegate.Result.Stop
    }

    override fun postProcessEnter(file: PsiFile, editor: Editor, dataContext: DataContext): EnterHandlerDelegate.Result =
        EnterHandlerDelegate.Result.Continue
}
