package hc.intellij

import com.intellij.lang.surroundWith.SurroundDescriptor
import com.intellij.lang.surroundWith.Surrounder
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.tree.TokenSet

// Surround With (Ctrl+Alt+T) -- finds the real STATEMENT siblings the selection spans (or the
// single statement at the caret with no selection), same "direct Document text edit, then
// reformat" pattern the rest of this plugin's own refactorings use rather than PSI-tree
// construction.
class HCSurroundDescriptor : SurroundDescriptor {
    override fun getElementsToSurround(file: PsiFile, startOffset: Int, endOffset: Int): Array<PsiElement> {
        val start = file.findElementAt(startOffset) ?: return PsiElement.EMPTY_ARRAY
        val end = file.findElementAt((endOffset - 1).coerceAtLeast(startOffset)) ?: return PsiElement.EMPTY_ARRAY
        val block = commonBlockAncestor(start, end) ?: return PsiElement.EMPTY_ARRAY
        val statements = directChildren(block).filter { STATEMENT_KINDS.contains(it.node?.elementType) }
        val selected = statements.filter { it.textRange.endOffset > startOffset && it.textRange.startOffset < endOffset.coerceAtLeast(startOffset + 1) }
        if (selected.isNotEmpty()) return selected.toTypedArray()
        // No real selection (a plain caret) -- fall back to whichever single statement the caret sits in.
        val atCaret = statements.firstOrNull { it.textRange.containsOffset(startOffset) || it.textRange.startOffset >= startOffset }
        return if (atCaret != null) arrayOf(atCaret) else PsiElement.EMPTY_ARRAY
    }

    override fun getSurrounders(): Array<Surrounder> = arrayOf(HCIfSurrounder(), HCWhileSurrounder(), HCBlockSurrounder(), HCTrySurrounder())

    override fun isExclusive(): Boolean = false
}

private val STATEMENT_KINDS = TokenSet.create(
    HCElementTypes.LET_STMT, HCElementTypes.IF_STMT, HCElementTypes.IF_LET_STMT, HCElementTypes.DEV_IF_STMT,
    HCElementTypes.WHILE_STMT, HCElementTypes.FOR_STMT, HCElementTypes.MATCH_STMT, HCElementTypes.RETURN_STMT,
    HCElementTypes.BREAK_STMT, HCElementTypes.CONTINUE_STMT, HCElementTypes.TRY_STMT, HCElementTypes.THROW_STMT,
    HCElementTypes.EXPR_STMT, HCElementTypes.BLOCK,
)

private fun commonBlockAncestor(a: PsiElement, b: PsiElement): PsiElement? {
    val blockA = generateSequence(a) { it.parent }.firstOrNull { it.node?.elementType == HCElementTypes.BLOCK } ?: return null
    if (a === b) return blockA
    return generateSequence(b) { it.parent }.firstOrNull { it === blockA || it.node?.elementType == HCElementTypes.BLOCK }
        ?.takeIf { it === blockA }
        ?: blockA
}

// Wraps `[start, end)` (the span of every surrounded statement's own text) with `prefix`/`suffix`,
// reformats the touched range afterward (indentation from a flat text splice is never trustworthy
// on its own), and returns the offset range the caret should land on/select next -- computed in
// PRE-reformat coordinates relative to `prefix`, then carried forward across the reformat via a
// `RangeMarker` so it still points at the right text even if reformatting shifted offsets.
private fun surroundWith(
    project: Project, editor: Editor, elements: Array<PsiElement>, prefix: String, suffix: String, caretRangeInPrefix: IntRange,
): TextRange {
    val file = elements.first().containingFile
    val document = editor.document
    val start = elements.first().textRange.startOffset
    val end = elements.last().textRange.endOffset
    val body = document.getText(TextRange(start, end))

    lateinit var result: TextRange
    WriteCommandAction.runWriteCommandAction(project, "Surround With", null, {
        val replacement = prefix + body + suffix
        val marker = document.createRangeMarker(
            start + caretRangeInPrefix.first,
            start + caretRangeInPrefix.last + 1,
        )
        document.replaceString(start, end, replacement)
        PsiDocumentManager.getInstance(project).commitDocument(document)
        CodeStyleManager.getInstance(project).reformatText(file, start, start + replacement.length)
        PsiDocumentManager.getInstance(project).commitDocument(document)
        result = TextRange(marker.startOffset, marker.endOffset)
        marker.dispose()
    })
    return result
}

internal class HCIfSurrounder : Surrounder {
    override fun getTemplateDescription(): String = "if"
    override fun isApplicable(elements: Array<PsiElement>): Boolean = elements.isNotEmpty()
    override fun surroundElements(project: Project, editor: Editor, elements: Array<PsiElement>): TextRange {
        val prefix = "if true {\n"
        return surroundWith(project, editor, elements, prefix, "\n}", prefix.indexOf("true").let { it..(it + 3) })
    }
}

internal class HCWhileSurrounder : Surrounder {
    override fun getTemplateDescription(): String = "while"
    override fun isApplicable(elements: Array<PsiElement>): Boolean = elements.isNotEmpty()
    override fun surroundElements(project: Project, editor: Editor, elements: Array<PsiElement>): TextRange {
        val prefix = "while true {\n"
        return surroundWith(project, editor, elements, prefix, "\n}", prefix.indexOf("true").let { it..(it + 3) })
    }
}

internal class HCBlockSurrounder : Surrounder {
    override fun getTemplateDescription(): String = "{ }"
    override fun isApplicable(elements: Array<PsiElement>): Boolean = elements.isNotEmpty()
    override fun surroundElements(project: Project, editor: Editor, elements: Array<PsiElement>): TextRange {
        val prefix = "{\n"
        val range = surroundWith(project, editor, elements, prefix, "\n}", prefix.length..(prefix.length - 1))
        return range
    }
}

internal class HCTrySurrounder : Surrounder {
    override fun getTemplateDescription(): String = "try / catch"
    override fun isApplicable(elements: Array<PsiElement>): Boolean = elements.isNotEmpty()
    override fun surroundElements(project: Project, editor: Editor, elements: Array<PsiElement>): TextRange {
        val prefix = "try {\n"
        val suffix = "\n} catch (e: Exception) {\n\n}"
        return surroundWith(project, editor, elements, prefix, suffix, prefix.length..(prefix.length - 1))
    }
}
