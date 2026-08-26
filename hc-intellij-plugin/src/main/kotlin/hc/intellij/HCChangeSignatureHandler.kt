package hc.intellij

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.refactoring.changeSignature.ChangeSignatureHandler
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JTextField
import javax.swing.table.AbstractTableModel

// Change Signature -- unlike Safe Delete/Extract/Inline (all direct `Document` text edits), this
// one legitimately needs a modal dialog: the set of changes (rename, reorder/add/remove params,
// change the return type) has to be collected as a single user-reviewed batch before any text is
// touched, since e.g. a reorder is only meaningful relative to the FULL new param list, not
// expressible as a single incremental edit the way Inline/Extract's one-shot operations are.
//
// Deliberately does NOT hand this off to the platform's own generic multi-file "Change Signature"
// UI/engine (`ChangeSignatureUsageProcessor` and friends) -- that machinery is built around a
// language's own `PsiMethod`-like SDK model (JVM languages, mainly) with no meaningful hook for a
// hand-rolled PSI tree like this plugin's. Instead: a small custom dialog collects the target
// signature into a plain `HCChangeSignatureModel`, and `applyChangeSignature` (the real, directly
// testable logic -- deliberately factored out of the dialog so tests can drive it without ever
// popping real UI, mirroring this whole session's "hand off to `RenameProcessor`/`WriteCommandAction`
// directly" pattern) does the actual work:
//   1. a name change is delegated ENTIRELY to `RenameProcessor` -- the already-fully-tested rename
//      engine already updates the declaration and every call site correctly, so there's no reason
//      to hand-roll that same text-replacement logic a second time here.
//   2. the declaration's own param-list/return-type text is rewritten in place.
//   3. every call site (found via `ReferencesSearch` on the function's name ident, same pattern
//      `HCSafeDeleteProcessorDelegate`/`HCInlineHandler` already use) has its `ARG_LIST` rewritten:
//      each new param position pulls the OLD argument expression text from `originalIndex` (a
//      removed param just drops its old argument; a genuinely new param -- `originalIndex == null`
//      -- gets a `/* TODO */` placeholder, since there's no way to conjure a real value for a
//      parameter call sites never supplied before).
class HCChangeSignatureHandler : ChangeSignatureHandler {
    override fun findTargetMember(file: PsiFile, editor: Editor): PsiElement? {
        val offset = editor.caretModel.offset
        val element = file.findElementAt(offset) ?: file.findElementAt(offset - 1) ?: return null
        return findTargetMember(element)
    }

    override fun findTargetMember(element: PsiElement): PsiElement? {
        enclosingFnDecl(element)?.let { return it }
        val host = element.parent ?: return null
        for (ref in host.references) {
            val target = ref.resolve() ?: continue
            if (target.parent?.node?.elementType == HCElementTypes.FN_DECL) return target.parent
            enclosingFnDecl(target)?.let { return it }
        }
        return null
    }

    override fun getTargetNotFoundMessage(): String =
        "Cannot perform Change Signature here: the caret must be on a function declaration or a call to one."

    override fun invoke(project: Project, editor: Editor, file: PsiFile, dataContext: DataContext?) {
        val fnDecl = findTargetMember(file, editor) ?: return
        invoke(project, arrayOf(fnDecl), dataContext)
    }

    override fun invoke(project: Project, elements: Array<out PsiElement>, dataContext: DataContext?) {
        val fnDecl = elements.firstOrNull { it.node?.elementType == HCElementTypes.FN_DECL }
            ?: elements.firstOrNull()?.let { enclosingFnDecl(it) }
            ?: return
        val initial = buildInitialModel(fnDecl)
        val dialog = HCChangeSignatureDialog(project, initial)
        if (!dialog.showAndGet()) return
        applyChangeSignature(project, fnDecl, dialog.result())
    }
}

data class HCParamSpec(val name: String, val type: String, val originalIndex: Int?)
data class HCChangeSignatureModel(val name: String, val returnType: String?, val params: List<HCParamSpec>)

private fun enclosingFnDecl(element: PsiElement): PsiElement? {
    var el: PsiElement? = element
    while (el != null && el !is PsiFile) {
        if (el.node?.elementType == HCElementTypes.FN_DECL) return el
        el = el.parent
    }
    return null
}

// `self`/`&self`/`&mut self` (see `HCParameterInfoHandler.paramTextsOf`'s own identical exclusion)
// is never shown/editable here -- a call site never supplies it, and it's preserved verbatim by
// `applyChangeSignature` regardless of anything the user does to the rest of the param list.
internal fun buildInitialModel(fnDecl: PsiElement): HCChangeSignatureModel {
    val name = declaredName(fnDecl)?.text ?: ""
    val paramList = directChildren(fnDecl).firstOrNull { it.node?.elementType == HCElementTypes.PARAM_LIST }
    val nonSelfParams = paramList?.let { directChildren(it) }
        ?.filter { it.node?.elementType == HCElementTypes.PARAM }
        ?.filter { declaredName(it)?.text != "self" }
        .orEmpty()
    val params = nonSelfParams.mapIndexed { idx, p ->
        val pname = declaredName(p)?.text ?: ""
        val typeRef = directChildren(p).firstOrNull { it.node?.elementType == HCElementTypes.TYPE_REF }
        HCParamSpec(pname, typeRef?.text ?: "", idx)
    }
    val returnTypeRef = returnTypeRefOf(fnDecl)
    return HCChangeSignatureModel(name, returnTypeRef?.text, params)
}

private fun returnTypeRefOf(fnDecl: PsiElement): PsiElement? {
    val arrow = directChildren(fnDecl).firstOrNull { it.node?.elementType == HCTokenTypes.ARROW } ?: return null
    return directChildren(fnDecl).firstOrNull {
        it.node?.elementType == HCElementTypes.TYPE_REF && it.textRange.startOffset > arrow.textRange.startOffset
    }
}

private data class TextEdit(val file: PsiFile, val range: com.intellij.openapi.util.TextRange, val replacement: String)

// The one entry point tests drive directly -- no dialog involved, exactly like every other
// refactoring's own "real logic" function in this plugin (`HCIntroduceVariableHandler.invoke`,
// `HCInliner.inlineUsage`, ...).
fun applyChangeSignature(project: Project, fnDecl: PsiElement, model: HCChangeSignatureModel) {
    val originalNameIdent = declaredName(fnDecl) ?: return
    val originalName = originalNameIdent.text

    var currentFnDecl = fnDecl
    if (model.name.isNotBlank() && model.name != originalName) {
        val pointer = SmartPointerManager.createPointer(fnDecl)
        RenameProcessor(project, originalNameIdent, model.name, false, false).run()
        currentFnDecl = pointer.element ?: return
    }

    // A renamed EXISTING param (matched by `originalIndex`, so this only fires for params kept
    // from before, never a genuinely new one) is delegated to `RenameProcessor` too, for the exact
    // same reason the function's own name is: it's the only way every reference to that param
    // INSIDE the function body gets updated along with it, not just its declaration site. Done
    // before the signature-list/return-type text rewrite below (which only handles reordering/
    // adding/removing/retyping) so each param's own PSI identity is still whatever `RenameProcessor`
    // itself resolves against, one rename at a time (each one reparses the file, invalidating the
    // rest of the tree, hence re-fetching the param list fresh from a pointer every iteration).
    val pointerAfterNameChange = SmartPointerManager.createPointer(currentFnDecl)
    for (paramSpec in model.params) {
        val idx = paramSpec.originalIndex ?: continue
        if (paramSpec.name.isBlank()) continue
        val fresh = pointerAfterNameChange.element ?: return
        val freshParamList = directChildren(fresh).firstOrNull { it.node?.elementType == HCElementTypes.PARAM_LIST } ?: continue
        val nonSelfParams = directChildren(freshParamList)
            .filter { it.node?.elementType == HCElementTypes.PARAM }
            .filter { declaredName(it)?.text != "self" }
        val paramIdent = nonSelfParams.getOrNull(idx)?.let { declaredName(it) } ?: continue
        if (paramIdent.text != paramSpec.name) {
            RenameProcessor(project, paramIdent, paramSpec.name, false, false).run()
        }
    }
    currentFnDecl = pointerAfterNameChange.element ?: return

    val nameIdent = declaredName(currentFnDecl) ?: return
    val paramList = directChildren(currentFnDecl).firstOrNull { it.node?.elementType == HCElementTypes.PARAM_LIST } ?: return
    val selfParam = directChildren(paramList).firstOrNull {
        it.node?.elementType == HCElementTypes.PARAM && declaredName(it)?.text == "self"
    }

    val newParamsText = buildString {
        append('(')
        val parts = mutableListOf<String>()
        selfParam?.let { parts.add(it.text) }
        parts.addAll(model.params.map { "${it.name}: ${it.type}" })
        append(parts.joinToString(", "))
        append(')')
    }
    val returnTypeRef = returnTypeRefOf(currentFnDecl)
    val sigStart = paramList.textRange.startOffset
    val sigEnd = returnTypeRef?.textRange?.endOffset ?: paramList.textRange.endOffset
    val sigReplacement = newParamsText + if (model.returnType != null && model.returnType.isNotBlank()) " -> ${model.returnType}" else ""

    val edits = mutableListOf(TextEdit(currentFnDecl.containingFile, com.intellij.openapi.util.TextRange(sigStart, sigEnd), sigReplacement))

    for (ref in ReferencesSearch.search(nameIdent).findAll()) {
        val argList = argListForReference(ref.element) ?: continue
        val oldArgs = directChildren(argList).filter {
            it !is PsiWhiteSpace && it.node?.elementType != HCTokenTypes.COMMA &&
                it.node?.elementType != HCTokenTypes.LPAREN && it.node?.elementType != HCTokenTypes.RPAREN
        }
        val newArgsText = model.params.joinToString(", ") { p -> p.originalIndex?.let { oldArgs.getOrNull(it)?.text } ?: "/* TODO */" }
        val innerRange = com.intellij.openapi.util.TextRange(argList.textRange.startOffset + 1, argList.textRange.endOffset - 1)
        edits.add(TextEdit(argList.containingFile, innerRange, newArgsText))
    }

    applyTextEdits(project, edits)
}

// A reference to a plain function's name is registered on its `REF_EXPR` (a direct child of its
// enclosing `CALL_EXPR` -- see `HCReferences.kt`'s own header on why references live on composite
// hosts, not bare idents); a method/static call's name reference is registered on the
// `METHOD_CALL_EXPR`/`STATIC_CALL_EXPR` composite itself directly, which already IS the node
// carrying the `ARG_LIST` child.
private fun argListForReference(refElement: PsiElement): PsiElement? {
    val host = when (refElement.node?.elementType) {
        HCElementTypes.REF_EXPR -> refElement.parent?.takeIf { it.node?.elementType == HCElementTypes.CALL_EXPR }
        HCElementTypes.METHOD_CALL_EXPR, HCElementTypes.STATIC_CALL_EXPR -> refElement
        else -> null
    } ?: return null
    return directChildren(host).firstOrNull { it.node?.elementType == HCElementTypes.ARG_LIST }
}

// Grouped by file and applied highest-offset-first (per file) so earlier edits in the same
// document never get invalidated by a later one shifting offsets underneath it -- the same
// bottom-up-within-a-document concern `HCIntroduceVariableHandler`/`HCInliner` already have to
// account for, just needing an explicit sort here since edits come from an unordered
// `ReferencesSearch` result instead of a single known usage.
private fun applyTextEdits(project: Project, edits: List<TextEdit>) {
    if (edits.isEmpty()) return
    com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project, "Change Signature", null, {
        val pdm = PsiDocumentManager.getInstance(project)
        for ((file, fileEdits) in edits.groupBy { it.file }) {
            val document = pdm.getDocument(file) ?: continue
            for (edit in fileEdits.sortedByDescending { it.range.startOffset }) {
                document.replaceString(edit.range.startOffset, edit.range.endOffset, edit.replacement)
            }
            pdm.commitDocument(document)
        }
    })
}

// A small, self-contained dialog: a name field, a return-type field (blank = no return type), and
// an editable table of (name, type) rows backed by `HCParamsTableModel` -- reordering table rows
// IS reordering the signature (each row keeps its own `originalIndex` as it moves), add/remove
// buttons insert a fresh `HCParamSpec(originalIndex = null)`/delete a row outright.
private class HCChangeSignatureDialog(project: Project, initial: HCChangeSignatureModel) : DialogWrapper(project) {
    private val nameField = JTextField(initial.name)
    private val returnTypeField = JTextField(initial.returnType ?: "")
    private val tableModel = HCParamsTableModel(initial.params.toMutableList())
    private val table = JBTable(tableModel)

    init {
        title = "Change Signature"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val tablePanel = ToolbarDecorator.createDecorator(table)
            .setAddAction { tableModel.addRow() }
            .setRemoveAction { table.selectedRow.takeIf { it >= 0 }?.let { tableModel.removeRow(it) } }
            .setMoveUpAction { table.selectedRow.takeIf { it > 0 }?.let { tableModel.moveRow(it, it - 1); table.setRowSelectionInterval(it - 1, it - 1) } }
            .setMoveDownAction { table.selectedRow.takeIf { it in 0 until tableModel.rowCount - 1 }?.let { tableModel.moveRow(it, it + 1); table.setRowSelectionInterval(it + 1, it + 1) } }
            .createPanel()
        return FormBuilder.createFormBuilder()
            .addLabeledComponent(JLabel("Name:"), nameField)
            .addLabeledComponent(JLabel("Return type:"), returnTypeField)
            .addLabeledComponent(JLabel("Parameters:"), tablePanel, true)
            .panel.apply { border = JBUI.Borders.empty(8) }
    }

    fun result(): HCChangeSignatureModel = HCChangeSignatureModel(
        nameField.text.trim(),
        returnTypeField.text.trim().ifEmpty { null },
        tableModel.params.toList(),
    )
}

private class HCParamsTableModel(val params: MutableList<HCParamSpec>) : AbstractTableModel() {
    override fun getRowCount(): Int = params.size
    override fun getColumnCount(): Int = 2
    override fun getColumnName(column: Int): String = if (column == 0) "Name" else "Type"
    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = true
    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any =
        if (columnIndex == 0) params[rowIndex].name else params[rowIndex].type

    override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
        val current = params[rowIndex]
        val text = (aValue as? String) ?: return
        params[rowIndex] = if (columnIndex == 0) current.copy(name = text) else current.copy(type = text)
        fireTableCellUpdated(rowIndex, columnIndex)
    }

    fun addRow() {
        params.add(HCParamSpec("param", "Int", null))
        fireTableRowsInserted(params.size - 1, params.size - 1)
    }

    fun removeRow(index: Int) {
        params.removeAt(index)
        fireTableRowsDeleted(index, index)
    }

    fun moveRow(from: Int, to: Int) {
        val item = params.removeAt(from)
        params.add(to, item)
        fireTableDataChanged()
    }
}
