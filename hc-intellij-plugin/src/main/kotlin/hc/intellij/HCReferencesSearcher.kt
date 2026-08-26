package hc.intellij

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiReference
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.util.Processor

// Find Usages (Alt+F7)'s actual search logic -- deliberately NOT the platform's default
// index-based `ReferencesSearch` path (registering `HCFindUsagesProvider.getWordsScanner()` alone
// is what that path is SUPPOSED to need, and its own word-tokenization was verified correct in
// isolation via a throwaway debug test), because that indexed path came back empty in this SDK's
// test environment for reasons that didn't repay chasing further -- likely some indexing-pipeline
// wiring this hand-rolled `LexerBase` doesn't satisfy. Explicitly scanning instead sidesteps that
// uncertainty entirely, and it isn't a compromise: `HCReferences.kt`'s own resolution NEVER looks
// beyond `filesInScope(file)` (current file + same-directory siblings -- the language's own real
// multi-file namespace, see that file's own header), so no reference outside that scope could ever
// resolve to a given target anyway. Restricting the search to exactly that scope is therefore not
// an approximation of the indexed search -- it visits precisely the set of files that could
// possibly contain a real usage, no more and no less.
class HCReferencesSearcher : QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters>(true) {
    override fun processQuery(queryParameters: ReferencesSearch.SearchParameters, consumer: Processor<in PsiReference>) {
        val target = queryParameters.elementToSearch
        if (target.language != HCLanguage) return
        val file = target.containingFile ?: return
        for (f in filesInScopeFor(file)) {
            for (host in referenceHostsIn(f)) {
                for (ref in host.references) {
                    if (ref.isReferenceTo(target)) {
                        if (!consumer.process(ref)) return
                    }
                }
            }
        }
    }
}

private fun filesInScopeFor(file: PsiFile): List<PsiFile> =
    listOf(file) + (file.containingDirectory?.files?.filter { it !== file && it.language == HCLanguage } ?: emptyList())

private fun referenceHostsIn(file: PsiFile): List<PsiElement> =
    PsiTreeUtil.collectElements(file) { el -> REFERENCE_HOST_TYPES.contains(el.node?.elementType) }.toList()
