package hc.intellij

import com.intellij.lang.refactoring.NamesValidator
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileFactory
import com.intellij.refactoring.listeners.RefactoringElementListener
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.usageView.UsageInfo

// Rename (Shift+F6) -- builds directly on Find Usages (`HCReferencesSearcher`) and go-to-
// declaration (`HCReferences.kt`): the platform's own `RenameProcessor` already knows how to
// collect every usage of a target (via `ReferencesSearch`, already wired up) and drive a rename
// dialog/preview; all a language needs to supply is HOW to actually rewrite one declaration and
// one reference once a new name is chosen -- that's this class plus `HCReference.
// handleElementRename` (`HCReferences.kt`).
//
// Every rename TARGET and every reference-bearing IDENT in this plugin is a plain generic
// `LeafPsiElement`/`ASTWrapperPsiElement` (no per-node PSI classes at all -- see
// `HCParserDefinition.createElement`'s own header), so neither implements `PsiNamedElement`
// (`getName()`/`setName()`), which is what the platform's DEFAULT rename handling relies on.
// `canProcessElement`/`renameElement` below are this language's own opt-in replacement for that
// default path.
class HCRenamePsiElementProcessor : RenamePsiElementProcessor() {
    override fun canProcessElement(element: PsiElement): Boolean =
        element.language == HCLanguage && element.node?.elementType == HCTokenTypes.IDENT

    override fun renameElement(element: PsiElement, newName: String, usages: Array<out UsageInfo>, listener: RefactoringElementListener?) {
        for (usage in usages) usage.reference?.handleElementRename(newName)
        val renamed = replaceIdentLeaf(element, newName)
        listener?.elementRenamed(renamed)
    }
}

// Blocks the rename dialog from accepting a new name that isn't even a syntactically valid
// identifier in this grammar, or that collides with a real reserved keyword (`HCTokenTypes.
// KEYWORDS`, plus `true`/`false`/`null`, which lex as their own dedicated token types rather than
// through the generic keyword set -- see that set's own header).
class HCNamesValidator : NamesValidator {
    override fun isIdentifier(name: String, project: Project?): Boolean {
        if (name.isEmpty()) return false
        if (!(name[0].isLetter() || name[0] == '_')) return false
        return name.drop(1).all { it.isLetterOrDigit() || it == '_' }
    }

    override fun isKeyword(name: String, project: Project?): Boolean =
        name in HCTokenTypes.KEYWORDS || name == "true" || name == "false" || name == "null"
}

// The one real mechanism both the declaration-rename and reference-rename paths share: since
// nothing in this plugin's PSI has a `setName()`/text-manipulator to hook into, a brand new
// `IDENT` LEAF is synthesized by parsing a tiny throwaway file containing the new name (`"fn
// $newName() {}"`'s own `fn`-name anchor is itself a plain, ordinary `IDENT` -- any position would
// do, this one's simplest), then swapped in via `PsiElement.replace()`, which handles the AST-level
// substitution generically for any leaf, regardless of what kind of node it originally sat under
// (a declaration's own name, a `FIELD_ACCESS_EXPR`'s trailing ident, a `TYPE_REF`'s bare name, ...).
// Safe against `newName` itself being a reserved word -- `HCNamesValidator.isKeyword` above is
// what's actually responsible for the rename dialog never letting that name through in the first
// place; this fn trusts that check rather than re-verifying it.
internal fun replaceIdentLeaf(oldIdent: PsiElement, newName: String): PsiElement {
    val dummyFile = PsiFileFactory.getInstance(oldIdent.project).createFileFromText("dummy.hotc", HCLanguage, "fn $newName() {}")
    val newIdent = elementsOfType(dummyFile, HCElementTypes.FN_DECL)
        .firstOrNull()
        ?.let { fnDecl -> directChildren(fnDecl).firstOrNull { it.node?.elementType == HCTokenTypes.IDENT } }
        ?: error("failed to synthesize an identifier for '$newName'")
    return oldIdent.replace(newIdent)
}
