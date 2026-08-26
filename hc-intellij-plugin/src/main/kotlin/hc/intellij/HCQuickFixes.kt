package hc.intellij

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer

// Quick-fixes for the checks in `HCTypeChecks.kt` that already know EXACTLY what's missing --
// match exhaustiveness already computes the missing variant names, a no-such-field/-method error
// already knows the receiver's real struct, and a missing-interface-method error already has the
// interface's own real signature (param list, return type) sitting right there in source. Every
// fix here is a plain DOCUMENT text insertion (not a real PSI-tree construction) right before the
// target container's own closing `}` -- simpler and more robust than building composite nodes via
// a throwaway parse, and standard practice for this class of "insert a stub" fix.
//
// `SmartPsiElementPointer` -- not the raw `PsiElement` -- is what each fix actually holds:
// standard practice for any `IntentionAction` since the fix object is constructed once (when the
// annotator runs) but may be INVOKED much later, after the user has kept typing and the original
// PSI has long since been reparsed/invalidated.
private fun closingBraceOffset(container: PsiElement): Int? =
    directChildren(container).lastOrNull { it.node?.elementType == HCTokenTypes.RBRACE }?.textRange?.startOffset

private fun insertBeforeClosingBrace(project: Project, editor: Editor?, file: PsiFile?, container: PsiElement, text: String) {
    val offset = closingBraceOffset(container) ?: return
    val targetFile = file ?: container.containingFile
    val document = editor?.document ?: PsiDocumentManager.getInstance(project).getDocument(targetFile) ?: return
    document.insertString(offset, text)
    PsiDocumentManager.getInstance(project).commitDocument(document)
}

// `isInterface` -- an enum variant's own qualified stub arm is `EnumName::Variant => {}`; a
// `sealed interface` match arm names the IMPLEMENTING STRUCT directly with no qualifier at all
// (`Circle => {}`, matching `examples/sealed.hc`'s own real arm shape, just without the field
// bindings this fix has no confident way to guess) -- `HCPsiParser.variantPattern`'s own `{ ... }`
// suffix is OPTIONAL, so the bare struct name alone is already valid, real syntax on its own.
class HCAddMissingMatchArmsFix(
    matchNode: PsiElement, private val enumName: String, private val missing: List<String>, private val isInterface: Boolean = false,
) : IntentionAction {
    private val pointer: SmartPsiElementPointer<PsiElement> = SmartPointerManager.createPointer(matchNode)
    override fun getText() = "Add missing match arm(s)"
    override fun getFamilyName() = "HotChocolate"
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?) = pointer.element != null
    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val matchNode = pointer.element ?: return
        val stub = missing.joinToString("") { name ->
            if (isInterface) "    $name => {}\n" else "    $enumName::$name => {}\n"
        }
        insertBeforeClosingBrace(project, editor, file, matchNode, stub)
    }
    override fun startInWriteAction() = true
}

// The new field's type is deliberately a placeholder (`Int`) -- this pass has no confident way to
// infer what type a brand-new field SHOULD be from a single read-site (`p.x`'s own usage context
// isn't a type constraint the way an argument position is), so it's left for the user to fix,
// same as any "create member" quick-fix in a language without full inference does when the type
// genuinely isn't determinable.
class HCCreateFieldFix(structDecl: PsiElement, private val structName: String, private val fieldName: String) : IntentionAction {
    private val pointer: SmartPsiElementPointer<PsiElement> = SmartPointerManager.createPointer(structDecl)
    override fun getText() = "Add field '$fieldName' to '$structName'"
    override fun getFamilyName() = "HotChocolate"
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?) = pointer.element != null
    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val structDecl = pointer.element ?: return
        insertBeforeClosingBrace(project, editor, file, structDecl, "    $fieldName: Int,\n")
    }
    override fun startInWriteAction() = true
}

// Unlike the field fix, the new method's OWN parameter types come from real type inference --
// `paramTypes` is built from the actual call site's own arguments (`inferExprType`, see
// `HCTypeChecks.checkFieldAndMethodAccessIn`'s own call site) -- falling back to `Int` per
// argument only when that specific argument's own type couldn't be determined.
class HCCreateMethodFix(
    implDecl: PsiElement, private val structName: String, private val methodName: String, private val paramTypes: List<String?>,
) : IntentionAction {
    private val pointer: SmartPsiElementPointer<PsiElement> = SmartPointerManager.createPointer(implDecl)
    override fun getText() = "Add method '$methodName' to '$structName'"
    override fun getFamilyName() = "HotChocolate"
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?) = pointer.element != null
    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val implDecl = pointer.element ?: return
        val params = (listOf("&self") + paramTypes.mapIndexed { i, t -> "arg$i: ${t ?: "Int"}" }).joinToString(", ")
        insertBeforeClosingBrace(project, editor, file, implDecl, "    fn $methodName($params) {}\n")
    }
    override fun startInWriteAction() = true
}

// `paramListText` is the INTERFACE's own real, already-written `PARAM_LIST` text (parens
// included, e.g. `(&self, n: Int)`) lifted verbatim from source -- exact names and types, not a
// reconstruction, since the interface method signature already has them for real.
class HCImplementInterfaceMethodFix(
    implDecl: PsiElement, private val methodName: String, private val paramListText: String, private val retTypeText: String?,
) : IntentionAction {
    private val pointer: SmartPsiElementPointer<PsiElement> = SmartPointerManager.createPointer(implDecl)
    override fun getText() = "Implement '$methodName'"
    override fun getFamilyName() = "HotChocolate"
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?) = pointer.element != null
    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val implDecl = pointer.element ?: return
        val ret = if (retTypeText != null) " -> $retTypeText" else ""
        insertBeforeClosingBrace(project, editor, file, implDecl, "    fn $methodName$paramListText$ret {}\n")
    }
    override fun startInWriteAction() = true
}
