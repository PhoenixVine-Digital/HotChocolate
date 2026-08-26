package hc.intellij

import com.intellij.find.findUsages.FindUsagesHandler
import com.intellij.find.findUsages.FindUsagesHandlerFactory
import com.intellij.find.findUsages.FindUsagesOptions
import com.intellij.psi.PsiElement
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.usageView.UsageInfo
import com.intellij.util.Processor

// "Search for text occurrences" -- the checkbox in the Find Usages dialog that also matches a
// symbol's NAME as plain text inside string literals and comments, not just real references
// (`HCReferencesSearcher`'s own domain). The DEFAULT `FindUsagesHandler` already calls
// `ReferencesSearch` for the real-reference half; this factory/handler exist to add the text half
// on top when the user actually checks that box, scanning the exact same scope
// (`HCReferencesSearcher`'s own `filesInScope` -- current file + same-directory siblings) rather
// than the whole project, for the same reason that scope is correct there: nothing outside it
// could be a REAL usage, and a text-occurrence match outside a symbol's own real namespace would
// be pure noise (an unrelated file's own unrelated `"foo"` string matching a `foo` function by
// coincidence).
class HCFindUsagesHandlerFactory : FindUsagesHandlerFactory() {
    override fun canFindUsages(element: PsiElement): Boolean =
        element.language == HCLanguage && element.node?.elementType == HCTokenTypes.IDENT

    override fun createFindUsagesHandler(element: PsiElement, forHighlightUsages: Boolean): FindUsagesHandler =
        HCFindUsagesHandler(element)
}

private class HCFindUsagesHandler(element: PsiElement) : FindUsagesHandler(element) {
    override fun processElementUsages(element: PsiElement, processor: Processor<in UsageInfo>, options: FindUsagesOptions): Boolean {
        if (options.isUsages) {
            for (ref in ReferencesSearch.search(element, options.searchScope).findAll()) {
                if (!processor.process(UsageInfo(ref))) return false
            }
        }
        if (options.isSearchForTextOccurrences) {
            for (usage in textOccurrencesOf(element)) {
                if (!processor.process(usage)) return false
            }
        }
        return true
    }
}

private fun textOccurrencesOf(target: PsiElement): List<UsageInfo> {
    val name = target.text
    if (name.isBlank()) return emptyList()
    val file = target.containingFile ?: return emptyList()
    val out = mutableListOf<UsageInfo>()
    for (f in filesInScope(file)) {
        for (token in com.intellij.psi.util.PsiTreeUtil.collectElements(f) { el ->
            when (el.node?.elementType) {
                HCTokenTypes.STRING, HCTokenTypes.ISTRING_PART, HCTokenTypes.LINE_COMMENT, HCTokenTypes.DOC_COMMENT -> el.text.contains(name)
                else -> false
            }
        }) {
            // The REAL declaration/reference occurrences are handled entirely by `isUsages`
            // above -- this only ever fires on STRING/comment token text, so it can never
            // double-count a real code reference to begin with.
            out += UsageInfo(token)
        }
    }
    return out
}
