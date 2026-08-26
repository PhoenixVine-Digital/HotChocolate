package hc.intellij

import com.intellij.navigation.ChooseByNameContributor
import com.intellij.navigation.ItemPresentation
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope

// Go to Symbol (Ctrl+Alt+Shift+N / part of Search Everywhere) -- the one navigation feature in
// this plugin that's deliberately PROJECT-WIDE rather than scoped to `filesInScope` (current file
// + same-directory siblings): unlike reference resolution, Find Usages, or rename (where a result
// outside that scope could never be a REAL usage under this language's own directory-based
// namespace model), "jump to a symbol by name" is a pure navigation aid -- a user typing a name
// wants every declaration by that name across the whole project, including ones in an unrelated
// directory, so they can pick the right one themselves.
//
// `FileTypeIndex` (keyed on the registered `HCFileType`, a simple VFS-level file-type tag) is used
// instead of anything driven by this plugin's own hand-rolled lexer/word-scanner -- see
// `HCReferencesSearcher.kt`'s own header for why leaning on THAT kind of index came back unreliable
// in this SDK's test environment; `FileTypeIndex` is a different, simpler mechanism with no such
// history here.
class HCGotoSymbolContributor : ChooseByNameContributor {
    override fun getNames(project: Project, includeNonProjectItems: Boolean): Array<String> {
        val names = mutableSetOf<String>()
        for (file in hcFilesIn(project)) {
            for (decl in symbolDeclarationsIn(file)) declaredName(decl)?.text?.let { names += it }
        }
        return names.toTypedArray()
    }

    override fun getItemsByName(name: String, pattern: String, project: Project, includeNonProjectItems: Boolean): Array<NavigationItem> {
        val items = mutableListOf<NavigationItem>()
        for (file in hcFilesIn(project)) {
            for (decl in symbolDeclarationsIn(file)) {
                val anchor = declaredName(decl) ?: continue
                if (anchor.text == name) items += HCNavigationItem(anchor, decl, symbolKindOf(decl))
            }
        }
        return items.toTypedArray()
    }
}

private fun hcFilesIn(project: Project): List<PsiFile> {
    val psiManager = PsiManager.getInstance(project)
    return FileTypeIndex.getFiles(HCFileType, GlobalSearchScope.allScope(project)).mapNotNull { psiManager.findFile(it) }
}

// Every real, named declaration worth jumping to directly -- top-level declarations AND impl
// methods/enum variants (`elementsOfType` recurses, so both come back from one pass per kind, same
// as `HCStructureViewFactory`'s own member set).
private val SYMBOL_KINDS = listOf(
    HCElementTypes.FN_DECL, HCElementTypes.STRUCT_DECL, HCElementTypes.ENUM_DECL,
    HCElementTypes.INTERFACE_DECL, HCElementTypes.EXTERN_CLASS_DECL, HCElementTypes.STATIC_DECL,
    HCElementTypes.ENUM_VARIANT, HCElementTypes.INTERFACE_METHOD_SIG,
)

private fun symbolDeclarationsIn(file: PsiFile): List<PsiElement> = SYMBOL_KINDS.flatMap { elementsOfType(file, it) }

private fun symbolKindOf(decl: PsiElement): String = when (decl.node?.elementType) {
    HCElementTypes.FN_DECL -> if (decl.parent?.node?.elementType == HCElementTypes.IMPL_DECL) "method" else "function"
    HCElementTypes.STRUCT_DECL -> "struct"
    HCElementTypes.ENUM_DECL -> "enum"
    HCElementTypes.INTERFACE_DECL -> "interface"
    HCElementTypes.EXTERN_CLASS_DECL -> "extern class"
    HCElementTypes.STATIC_DECL -> "static"
    HCElementTypes.ENUM_VARIANT -> "enum variant"
    HCElementTypes.INTERFACE_METHOD_SIG -> "interface method"
    else -> ""
}

private class HCNavigationItem(private val anchor: PsiElement, private val decl: PsiElement, private val kind: String) : NavigationItem {
    override fun getName(): String = anchor.text

    override fun getPresentation(): ItemPresentation = object : ItemPresentation {
        override fun getPresentableText() = anchor.text
        override fun getLocationString() = "$kind, ${decl.containingFile?.name ?: ""}"
        override fun getIcon(unused: Boolean) = null
    }

    // Deliberately navigates via `OpenFileDescriptor` (an offset into the real file) rather than
    // relying on `PsiElement`'s own `navigate()` -- reliable regardless of what concrete PSI class
    // backs `anchor` (every element in this plugin is a plain generic `LeafPsiElement`/
    // `ASTWrapperPsiElement`, see `HCRenamePsiElementProcessor.kt`'s own header for why that keeps
    // mattering across features in this plugin).
    override fun navigate(requestFocus: Boolean) {
        val vFile = anchor.containingFile?.virtualFile ?: return
        OpenFileDescriptor(anchor.project, vFile, anchor.textRange.startOffset).navigate(requestFocus)
    }

    override fun canNavigate(): Boolean = anchor.containingFile?.virtualFile != null
    override fun canNavigateToSource(): Boolean = canNavigate()
}
