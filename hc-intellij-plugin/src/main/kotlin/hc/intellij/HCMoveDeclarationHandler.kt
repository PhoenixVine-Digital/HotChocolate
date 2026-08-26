package hc.intellij

import com.intellij.lang.Language
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.openapi.util.TextRange
import com.intellij.psi.tree.TokenSet
import com.intellij.refactoring.move.MoveCallback
import com.intellij.refactoring.move.MoveHandlerDelegate
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JTextField

// Move (F6) -- relocates a WHOLE top-level declaration (function, struct, enum, interface,
// extern class, static, or an `impl`/`extend` block) from its current file into a different one.
//
// Deliberately restricted to a destination in the SAME DIRECTORY as the source, enforced by
// `chooseDestinationFile` only ever listing/creating sibling files: `filesInScope`
// (`HCReferences.kt`) is this language's own real name-resolution scope -- there is no import
// system, every file in a directory shares one flat, implicit namespace with every OTHER file in
// that SAME directory, and nothing outside it. A same-directory move is therefore always
// reference-safe with NO further text changes needed anywhere else (nothing had to "import" the
// moved declaration before, and nothing needs to stop importing it now) -- exactly the same
// "global namespace" property that made this language's Rename/Find Usages/Inline able to just
// search `filesInScope` directly rather than tracking real imports. Moving to a DIFFERENT
// directory would silently break every reference to (or from) the moved declaration, which is
// worse than not offering the refactoring there at all -- so v1 doesn't offer it.
class HCMoveDeclarationHandler : MoveHandlerDelegate() {
    override fun supportsLanguage(language: Language): Boolean = language == HCLanguage

    override fun canMove(elements: Array<out PsiElement>, targetContainer: PsiElement?): Boolean {
        val element = elements.singleOrNull() ?: return false
        return element.language == HCLanguage && enclosingMovableDecl(element) != null
    }

    override fun doMove(project: Project, elements: Array<out PsiElement>, targetContainer: PsiElement?, callback: MoveCallback?) {
        val decl = elements.firstOrNull()?.let { enclosingMovableDecl(it) } ?: return
        val sourceFile = decl.containingFile ?: return
        val destFile = (targetContainer as? PsiFile)?.takeIf { it.language == HCLanguage && it != sourceFile }
            ?: chooseDestinationFile(project, sourceFile)
            ?: return
        moveTopLevelDeclaration(project, decl, destFile)
        FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, destFile.virtualFile), true)
        callback?.refactoringCompleted()
    }

    override fun getActionName(elements: Array<out PsiElement>): String = "Move Declaration"
}

private val MOVABLE_KINDS = TokenSet.create(
    HCElementTypes.FN_DECL, HCElementTypes.STRUCT_DECL, HCElementTypes.ENUM_DECL, HCElementTypes.INTERFACE_DECL,
    HCElementTypes.EXTERN_CLASS_DECL, HCElementTypes.STATIC_DECL, HCElementTypes.IMPL_DECL, HCElementTypes.EXTEND_DECL,
)

// Only a genuinely TOP-LEVEL declaration qualifies (`el.parent is PsiFile`) -- a method nested
// inside an `impl`/`extend` block has the same `FN_DECL` element type as a free function, but it's
// not independently movable (it isn't a `topLevelItem` at all per `HCPsiParser`'s own grammar; it
// only exists as a child of its enclosing `IMPL_DECL`/`EXTEND_DECL`, which IS what's movable).
private fun enclosingMovableDecl(element: PsiElement): PsiElement? {
    var el: PsiElement? = element
    while (el != null && el !is PsiFile) {
        if (MOVABLE_KINDS.contains(el.node?.elementType) && el.parent is PsiFile) return el
        el = el.parent
    }
    return null
}

// The one entry point tests drive directly -- no dialog involved, same pattern as every other
// refactoring's own real-logic function in this plugin (`applyChangeSignature`,
// `HCIntroduceVariableHandler.invoke`, ...).
fun moveTopLevelDeclaration(project: Project, decl: PsiElement, destFile: PsiFile) {
    val sourceFile = decl.containingFile ?: return
    val pdm = PsiDocumentManager.getInstance(project)
    val sourceDocument = pdm.getDocument(sourceFile) ?: return
    val destDocument = pdm.getDocument(destFile) ?: return

    val span = declSpan(decl)
    // `span.contentRange` never includes the surrounding blank-line SEPARATOR (only the decl's own
    // modifiers/comment through its own closing token) -- unlike `span.cutRange`, which may reach
    // further to glue that gap closed on the SOURCE side. Re-adding exactly one trailing newline
    // here (never present in `contentRange` itself, since no top-level decl node's own textRange
    // extends past its closing token) is what gives the destination file a clean line ending.
    val movedText = sourceDocument.getText(span.contentRange) + "\n"

    com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project, "Move Declaration", null, {
        val destText = destDocument.text
        val separator = when {
            destText.isEmpty() -> ""
            destText.endsWith("\n\n") -> ""
            destText.endsWith("\n") -> "\n"
            else -> "\n\n"
        }
        destDocument.insertString(destText.length, separator + movedText)
        pdm.commitDocument(destDocument)

        // Done in this order (insert into destination, THEN delete from source) specifically so a
        // failure partway through never loses the declaration entirely (worst case: it's been
        // copied but not yet removed from the source, never the reverse).
        sourceDocument.deleteString(span.cutRange.startOffset, span.cutRange.endOffset)
        pdm.commitDocument(sourceDocument)
    })
}

// A leading `@annotation`, `pub`/`open` modifier keyword, and doc comment are all parsed as bare
// SIBLINGS of the declaration itself, not its children (see `HCPsiParser.topLevelItem`'s own
// header: `leadingAnnotations`/`pub`/`open` are consumed at file level BEFORE the declaration's
// own node even starts) -- so `decl.textRange` alone would silently leave `pub`/`@annotation`
// behind, corrupting the source file (a dangling modifier applied to whatever declaration happens
// to follow next, or a lone modifier with nothing after it if this was the last declaration).
private fun isTrivia(sib: PsiElement): Boolean =
    sib is PsiComment || sib.node?.elementType == HCElementTypes.ANNOTATION ||
        sib.node?.elementType == HCTokenTypes.DOC_COMMENT ||
        (sib.node?.elementType == HCTokenTypes.KEYWORD && (sib.text == "pub" || sib.text == "open"))

// `contentRange` is exactly what gets PASTED into the destination file: the declaration's own
// modifiers/doc-comment through its own closing token, and nothing else -- deliberately never
// includes the blank-line separator on either side, so the destination never inherits stray
// blank lines regardless of where the declaration sat in its source file.
//
// `cutRange` is what gets REMOVED from the SOURCE file, which is a strictly larger range: which
// side of the declaration absorbs the blank-line separator depends on whether anything follows
// it. With a following top-level item, the gap AFTER the declaration is removed too (so the
// remaining file reads as if the declaration was never there, with no leading blank line left
// before what used to be its neighbor); as the LAST item, there's no "after" to glue to, so the
// gap BEFORE it is removed instead (walking back through whitespace/trivia, past the blank line
// this time) to avoid leaving a dangling trailing blank line at EOF.
private data class DeclSpan(val cutRange: TextRange, val contentRange: TextRange)

private fun declSpan(decl: PsiElement): DeclSpan {
    val hasFollowingSibling = generateSequence(decl.nextSibling) { it.nextSibling }.any { it !is PsiWhiteSpace }

    var modifiersStart = decl.textRange.startOffset
    var sib = decl.prevSibling
    while (sib != null) {
        when {
            sib is PsiWhiteSpace -> {
                if (sib.text.count { it == '\n' } >= 2) break
                sib = sib.prevSibling
            }
            isTrivia(sib) -> {
                modifiersStart = sib.textRange.startOffset
                sib = sib.prevSibling
            }
            else -> { sib = null }
        }
    }

    val cutStart = if (hasFollowingSibling) {
        modifiersStart
    } else {
        var s = modifiersStart
        var cur = decl.prevSibling
        while (cur != null && (cur is PsiWhiteSpace || isTrivia(cur))) {
            s = cur.textRange.startOffset
            cur = cur.prevSibling
        }
        s
    }

    val next = decl.nextSibling
    val cutEnd = if (hasFollowingSibling && next is PsiWhiteSpace) next.textRange.endOffset else decl.textRange.endOffset

    return DeclSpan(TextRange(cutStart, cutEnd), TextRange(modifiersStart, decl.textRange.endOffset))
}

private fun chooseDestinationFile(project: Project, sourceFile: PsiFile): PsiFile? {
    val directory = sourceFile.containingDirectory ?: return null
    val siblings = directory.files.filter { it !== sourceFile && it.language == HCLanguage }
    val dialog = HCChooseDestinationFileDialog(project, siblings.map { it.name })
    if (!dialog.showAndGet()) return null
    val newName = dialog.newFileName()
    if (newName != null) {
        val fileName = if (newName.contains('.')) newName else "$newName.hotc"
        return directory.createFile(fileName)
    }
    val chosenName = dialog.chosenExistingName() ?: return null
    return siblings.firstOrNull { it.name == chosenName }
}

private class HCChooseDestinationFileDialog(project: Project, private val existingNames: List<String>) : DialogWrapper(project) {
    private val combo = JComboBox(existingNames.toTypedArray())
    private val newFileField = JTextField()

    init {
        title = "Move Declaration"
        init()
    }

    override fun createCenterPanel(): JComponent =
        FormBuilder.createFormBuilder()
            .addLabeledComponent(JLabel("Destination file:"), combo)
            .addLabeledComponent(JLabel("Or new file name:"), newFileField)
            .panel.apply { border = JBUI.Borders.empty(8) }

    fun newFileName(): String? = newFileField.text.trim().ifEmpty { null }
    fun chosenExistingName(): String? = combo.selectedItem as? String
}
