package hc.intellij

import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.refactoring.rename.PsiElementRenameHandler
import com.intellij.refactoring.rename.RenameHandler
import com.intellij.refactoring.rename.RenameProcessor

// Shift+F6 invoked with the caret ALREADY on a reference (a call site, a field access, a type
// name, ...) already works via the platform's own default path -- `TargetElementUtil` resolves
// the reference at the caret the same way go-to-declaration does, then hands the resolved target
// to `HCRenamePsiElementProcessor`, which already knows how to process it. Found NOT to work,
// though (a throwaway `renameElementAtCaretUsingHandler` test came back "No handler for this
// context"), with the caret on the DECLARATION site itself (the `add` in `fn add(...)`): that
// path relies on `TargetElementUtil`'s own `ELEMENT_NAME_ACCEPTED` check, which only recognizes
// `PsiNamedElement`s -- and every PSI element in this plugin is a plain generic
// `LeafPsiElement`/`ASTWrapperPsiElement` (see `HCRenamePsiElementProcessor.kt`'s own header),
// so a declaration's own name is invisible to that generic mechanism entirely.
//
// This handler is this language's own replacement for that discovery step ONLY -- it doesn't
// duplicate any actual renaming logic, just finds the right target `PsiElement` (a declaration
// anchor directly, or a reference's own resolved target otherwise) and hands off to the platform's
// normal interactive rename flow (`PsiElementRenameHandler.invoke`), which is what actually shows
// the dialog/does the work via `HCRenamePsiElementProcessor`.
class HCRenameHandler : RenameHandler {
    override fun isAvailableOnDataContext(dataContext: DataContext): Boolean {
        val editor = CommonDataKeys.EDITOR.getData(dataContext) ?: return false
        val file = CommonDataKeys.PSI_FILE.getData(dataContext) ?: return false
        if (file.language != HCLanguage) return false
        return findRenameTarget(editor, file) != null
    }

    override fun invoke(project: Project, editor: Editor, file: PsiFile, dataContext: DataContext) {
        val target = findRenameTarget(editor, file) ?: return
        // A caller (the test fixture's own `renameElementAtCaretUsingHandler`, or any other
        // programmatic caller) can preset the new name via this `DataContext` key -- when
        // present, the real platform convention is to skip the interactive dialog entirely and
        // rename straight through `RenameProcessor` (which is what `PsiElementRenameHandler.
        // invoke` itself does internally too, once ITS OWN dialog produces a name) rather than
        // popping a real UI dialog no test (or scripted caller) can answer.
        val presetName = PsiElementRenameHandler.DEFAULT_NAME.getData(dataContext)
        if (presetName != null) {
            RenameProcessor(project, target, presetName, false, false).run()
        } else {
            PsiElementRenameHandler.invoke(target, project, file, editor)
        }
    }

    // Invoked from a non-editor context (e.g. a project-view selection) -- not meaningfully
    // supported here, since renaming always needs an in-source identifier target to find.
    override fun invoke(project: Project, elements: Array<out PsiElement>, dataContext: DataContext) {}
}

// A declaration's own name-anchor IDENT (`declaredName(parent) === ident` for every real
// declaration kind this plugin tracks -- see `HCReferences.kt`'s own catalogue of node shapes),
// plus the two binding-site shapes that AREN'T their composite's first `IDENT` child: a
// `VARIANT_PATTERN`'s bind names (which come after its own `{`; the pattern's LEADING ident is a
// REFERENCE to the enum variant itself, not a declaration -- already handled by
// `HCReferences.resolveStructLitBase`-adjacent machinery, deliberately excluded here) and a
// `LAMBDA_EXPR`'s own params (every direct `IDENT` child).
internal fun isDeclarationAnchor(ident: PsiElement): Boolean {
    val parent = ident.parent ?: return false
    return when (parent.node?.elementType) {
        HCElementTypes.PARAM, HCElementTypes.LET_STMT, HCElementTypes.FOR_STMT, HCElementTypes.CATCH_CLAUSE,
        HCElementTypes.FN_DECL, HCElementTypes.STRUCT_DECL, HCElementTypes.ENUM_DECL, HCElementTypes.ENUM_VARIANT,
        HCElementTypes.INTERFACE_DECL, HCElementTypes.EXTERN_CLASS_DECL, HCElementTypes.FIELD_DECL,
        HCElementTypes.STATIC_DECL,
        -> declaredName(parent) === ident
        HCElementTypes.VARIANT_PATTERN -> {
            val kids = directChildren(parent)
            val braceIdx = kids.indexOfFirst { it.node?.elementType == HCTokenTypes.LBRACE }
            braceIdx >= 0 && kids.indexOf(ident) > braceIdx
        }
        HCElementTypes.LAMBDA_EXPR -> true
        else -> false
    }
}

private fun findRenameTarget(editor: Editor, file: PsiFile): PsiElement? {
    val offset = editor.caretModel.offset
    // `offset - 1` fallback: a caret resting right AFTER an identifier (the common real-world
    // case -- double-click a word, the caret lands at its end) sees the FOLLOWING token/whitespace
    // at `offset` itself, not the identifier just typed/selected.
    val element = file.findElementAt(offset)?.takeIf { it.node?.elementType == HCTokenTypes.IDENT }
        ?: file.findElementAt(offset - 1)?.takeIf { it.node?.elementType == HCTokenTypes.IDENT }
        ?: return null
    if (isDeclarationAnchor(element)) return element
    val host = element.parent ?: return null
    for (ref in host.references) {
        ref.resolve()?.let { return it }
    }
    return null
}
