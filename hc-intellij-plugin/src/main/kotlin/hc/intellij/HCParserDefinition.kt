package hc.intellij

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet

val HC_FILE = IFileElementType(HCLanguage)

// `ASTWrapperPsiElement`'s own `getReferences()` does NOT route through
// `ReferenceProvidersRegistry` by default (an IntelliJ Platform default, not specific to this
// SDK version) -- `HCReferences.kt`'s `HCReferenceContributor` registers providers for several
// composite node types (`REF_EXPR`/`TYPE_REF`/...), but without this override,
// `PsiFile.findReferenceAt`/`getReferenceAtCaretPosition`/Ctrl+B all come back empty even though
// `ReferenceProvidersRegistry.getReferencesFromProviders(element)` finds the registered reference
// just fine when called directly -- found via a throwaway debug test (see `HCReferences.kt`'s own
// header for the full story).
private class HCCompositeElement(node: ASTNode) : com.intellij.extapi.psi.ASTWrapperPsiElement(node) {
    override fun getReferences(): Array<com.intellij.psi.PsiReference> =
        com.intellij.psi.impl.source.resolve.reference.ReferenceProvidersRegistry.getReferencesFromProviders(this)
}

// The REAL, root-level fix for a whole class of problems `HCTargetElementEvaluator`/
// `HCRenameHandler`/`HCSafeDeleteProcessorDelegate` each had to work around individually: a
// declaration composite (`FN_DECL`, `STRUCT_DECL`, `PARAM`, ...) that implements
// `PsiNameIdentifierOwner` is what `TargetElementUtil`'s OWN built-in logic (not anything specific
// to this plugin) already knows how to walk an `IDENT` leaf up to -- found the hard way, via a
// throwaway debug test, that Safe Delete stayed permanently grayed out on a `fn` name because the
// element it resolved to (the bare `IDENT`, via `HCTargetElementEvaluator.getNamedElement`) never
// matched `HCSafeDeleteProcessorDelegate.handlesElement`'s own composite-only check, and that
// trying to patch around it by deleting the composite from inside `prepareForDeletion` produced
// silently WRONG results (`fn (a: Int, b: Int) { }` -- the name gone, the rest of the broken
// declaration left behind) rather than a clean deletion or a clean failure. `PsiNameIdentifierOwner`
// is what real language plugins use for exactly this reason: implement it once per declaration
// kind, and Rename/Safe Delete/Quick Definition/`TargetElementUtil` all resolve correctly through
// the platform's own built-in mechanism, no bespoke per-feature glue needed.
private class HCNamedElement(node: ASTNode) : com.intellij.extapi.psi.ASTWrapperPsiElement(node), com.intellij.psi.PsiNameIdentifierOwner {
    override fun getNameIdentifier(): PsiElement? = declaredName(this)
    override fun getName(): String? = nameIdentifier?.text
    override fun setName(name: String): PsiElement {
        val current = nameIdentifier ?: return this
        replaceIdentLeaf(current, name)
        return this
    }
}

private val NAMED_ELEMENT_KINDS = TokenSet.create(
    HCElementTypes.FN_DECL, HCElementTypes.STRUCT_DECL, HCElementTypes.ENUM_DECL, HCElementTypes.INTERFACE_DECL,
    HCElementTypes.EXTERN_CLASS_DECL, HCElementTypes.STATIC_DECL, HCElementTypes.PARAM, HCElementTypes.LET_STMT,
    HCElementTypes.FIELD_DECL, HCElementTypes.ENUM_VARIANT, HCElementTypes.FOR_STMT, HCElementTypes.CATCH_CLAUSE,
)

class HCFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, HCLanguage) {
    override fun getFileType(): FileType = HCFileType
    override fun toString(): String = "HotChocolate File"
}

class HCParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?): Lexer = HCLexer()
    override fun createParser(project: Project?): PsiParser = HCPsiParser
    override fun getFileNodeType() = HC_FILE
    override fun getWhitespaceTokens(): TokenSet = TokenSet.create(com.intellij.psi.TokenType.WHITE_SPACE)
    override fun getCommentTokens(): TokenSet = TokenSet.create(HCTokenTypes.LINE_COMMENT, HCTokenTypes.DOC_COMMENT)
    override fun getStringLiteralElements(): TokenSet = TokenSet.create(
        HCTokenTypes.STRING, HCTokenTypes.ISTRING_BEGIN, HCTokenTypes.ISTRING_PART, HCTokenTypes.ISTRING_END,
    )
    override fun createElement(node: ASTNode): PsiElement =
        if (NAMED_ELEMENT_KINDS.contains(node.elementType)) HCNamedElement(node) else HCCompositeElement(node)
    override fun createFile(viewProvider: FileViewProvider): com.intellij.psi.PsiFile = HCFile(viewProvider)
}
