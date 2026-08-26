package hc.intellij

import com.intellij.lang.cacheBuilder.DefaultWordsScanner
import com.intellij.lang.cacheBuilder.WordsScanner
import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.TokenSet

// Find Usages (Alt+F7) -- needs no search logic of its own: IntelliJ's default `ReferencesSearch`
// does a WORD-INDEXED text scan for the target's own name (using `getWordsScanner`'s tokenization
// below to build that index), then, for each textual hit, resolves that hit's own `PsiReference`
// (via `HCReferenceContributor`, already built for go-to-declaration) and keeps only the ones that
// resolve back to the searched declaration -- so every resolution rule already established there
// (receiver-type-aware field/method access, same-directory multi-file scope, generics, "give up
// rather than guess when ambiguous," ...) applies here for free, with no separate scoping code
// needed. A candidate usage from an unrelated file can only survive the filter if ITS OWN
// `resolve()` call (which re-runs `filesInScope` from ITS OWN containing file) actually lands on
// our exact target element -- which is only possible if that file is a real same-directory sibling
// of the declaration, exactly the scope this plugin already treats as one shared namespace.
//
// Without SOME `FindUsagesProvider` registered for a language, IntelliJ disables "Find Usages" for
// that language's elements entirely (`canFindUsagesFor` has no other source to consult) -- this
// class exists as much for that registration as for its own (mostly cosmetic) methods.
class HCFindUsagesProvider : FindUsagesProvider {
    override fun getWordsScanner(): WordsScanner = DefaultWordsScanner(
        HCLexer(),
        TokenSet.create(HCTokenTypes.IDENT),
        TokenSet.create(HCTokenTypes.LINE_COMMENT, HCTokenTypes.DOC_COMMENT),
        TokenSet.create(HCTokenTypes.STRING, HCTokenTypes.ISTRING_PART),
    )

    override fun canFindUsagesFor(psiElement: PsiElement): Boolean = psiElement.node?.elementType == HCTokenTypes.IDENT

    override fun getHelpId(psiElement: PsiElement): String? = null

    // Cosmetic only -- the "Struct 'Point'"/"parameter 'x'"-style grouping header text in the Find
    // Usages tool window. Determined by the declaration ident's own immediate parent node shape,
    // same dispatch style `HCReferences.kt` already uses for the opposite direction.
    override fun getType(element: PsiElement): String = when (element.parent?.node?.elementType) {
        HCElementTypes.FN_DECL -> "function"
        HCElementTypes.STRUCT_DECL -> "struct"
        HCElementTypes.ENUM_DECL -> "enum"
        HCElementTypes.ENUM_VARIANT -> "enum variant"
        HCElementTypes.INTERFACE_DECL -> "interface"
        HCElementTypes.EXTERN_CLASS_DECL -> "extern class"
        HCElementTypes.FIELD_DECL -> "field"
        HCElementTypes.STATIC_DECL -> "static"
        HCElementTypes.PARAM -> "parameter"
        HCElementTypes.LET_STMT -> "variable"
        HCElementTypes.FOR_STMT -> "loop variable"
        HCElementTypes.CATCH_CLAUSE -> "caught exception"
        HCElementTypes.VARIANT_PATTERN -> "pattern binding"
        HCElementTypes.LAMBDA_EXPR -> "parameter"
        else -> ""
    }

    override fun getDescriptiveName(element: PsiElement): String = element.text

    override fun getNodeText(element: PsiElement, useFullName: Boolean): String = element.text
}
