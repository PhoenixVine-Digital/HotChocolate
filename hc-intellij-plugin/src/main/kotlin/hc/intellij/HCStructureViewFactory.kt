package hc.intellij

import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewModelBase
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.lang.PsiStructureViewFactory
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import javax.swing.Icon

// File outline (Ctrl+F12) -- a plain, pure mapping from the real PSI tree to a two-level-deep
// (occasionally three: `impl` block -> its own methods) member list, reusing `declaredName`/
// `implTargetStructName` from `HCReferences.kt` rather than any new resolution logic. Scoped to
// real "members" worth navigating to directly: top-level declarations, plus a struct's own
// fields, an enum's own variants, an interface's own method signatures, and an `impl` block's own
// methods -- individual statements/expressions inside a fn body are deliberately NOT part of the
// outline, matching how e.g. the Java/Kotlin structure views scope themselves to declarations.
class HCStructureViewFactory : PsiStructureViewFactory {
    override fun getStructureViewBuilder(psiFile: PsiFile): StructureViewBuilder =
        object : TreeBasedStructureViewBuilder() {
            override fun createStructureViewModel(editor: Editor?): StructureViewModel =
                HCStructureViewModel(psiFile)
        }
}

private class HCStructureViewModel(psiFile: PsiFile) :
    StructureViewModelBase(psiFile, HCStructureViewElement(psiFile)), StructureViewModel.ElementInfoProvider {
    override fun isAlwaysShowsPlus(element: StructureViewTreeElement) = false
    override fun isAlwaysLeaf(element: StructureViewTreeElement) = (element as? HCStructureViewElement)?.isLeaf() == true
}

private fun isMemberKind(element: PsiElement): Boolean = when (element.node?.elementType) {
    HCElementTypes.FN_DECL, HCElementTypes.STRUCT_DECL, HCElementTypes.ENUM_DECL, HCElementTypes.INTERFACE_DECL,
    HCElementTypes.EXTERN_CLASS_DECL, HCElementTypes.IMPL_DECL, HCElementTypes.STATIC_DECL,
    HCElementTypes.FIELD_DECL, HCElementTypes.ENUM_VARIANT, HCElementTypes.INTERFACE_METHOD_SIG,
    -> true
    else -> false
}

private fun childMembersOf(element: PsiElement): List<PsiElement> {
    val kids = when (element) {
        is PsiFile -> directChildren(element)
        else -> when (element.node?.elementType) {
            HCElementTypes.STRUCT_DECL, HCElementTypes.EXTERN_CLASS_DECL -> directChildren(element).filter { it.node?.elementType == HCElementTypes.FIELD_DECL }
            HCElementTypes.ENUM_DECL -> directChildren(element).filter { it.node?.elementType == HCElementTypes.ENUM_VARIANT }
            HCElementTypes.INTERFACE_DECL -> directChildren(element).filter { it.node?.elementType == HCElementTypes.INTERFACE_METHOD_SIG }
            HCElementTypes.IMPL_DECL -> directChildren(element).filter { it.node?.elementType == HCElementTypes.FN_DECL }
            else -> emptyList()
        }
    }
    return kids.filter { isMemberKind(it) }
}

private fun implLabel(implDecl: PsiElement): String {
    val kids = directChildren(implDecl)
    val forIdx = kids.indexOfFirst { it.node?.elementType == HCTokenTypes.KEYWORD && it.text == "for" }
    val target = implTargetStructName(implDecl) ?: "?"
    if (forIdx < 0) return "impl $target"
    val interfaceName = kids.firstOrNull { it.node?.elementType == HCTokenTypes.IDENT }?.text ?: "?"
    return "impl $interfaceName for $target"
}

private fun presentableTextFor(element: PsiElement): String = when (element) {
    is PsiFile -> element.name
    else -> when (element.node?.elementType) {
        HCElementTypes.FN_DECL, HCElementTypes.INTERFACE_METHOD_SIG -> "fn ${declaredName(element)?.text ?: "?"}()"
        HCElementTypes.STRUCT_DECL -> declaredName(element)?.text ?: "?"
        HCElementTypes.ENUM_DECL -> declaredName(element)?.text ?: "?"
        HCElementTypes.INTERFACE_DECL -> declaredName(element)?.text ?: "?"
        HCElementTypes.EXTERN_CLASS_DECL -> declaredName(element)?.text ?: "?"
        HCElementTypes.ENUM_VARIANT -> declaredName(element)?.text ?: "?"
        HCElementTypes.IMPL_DECL -> implLabel(element)
        HCElementTypes.FIELD_DECL -> {
            val name = declaredName(element)?.text ?: "?"
            val type = directChildren(element).firstOrNull { it.node?.elementType == HCElementTypes.TYPE_REF }?.text
            if (type != null) "$name: $type" else name
        }
        HCElementTypes.STATIC_DECL -> {
            val name = declaredName(element)?.text ?: "?"
            val type = directChildren(element).firstOrNull { it.node?.elementType == HCElementTypes.TYPE_REF }?.text
            if (type != null) "static $name: $type" else "static $name"
        }
        else -> element.text
    }
}

private class HCStructureViewElement(private val element: PsiElement) : StructureViewTreeElement, ItemPresentation {
    fun isLeaf(): Boolean = element !is PsiFile && childMembersOf(element).isEmpty()

    override fun getValue(): Any = element
    override fun getPresentation(): ItemPresentation = this
    override fun getPresentableText(): String = presentableTextFor(element)
    override fun getLocationString(): String? = null
    override fun getIcon(unused: Boolean): Icon? = null
    override fun getChildren(): Array<TreeElement> = childMembersOf(element).map { HCStructureViewElement(it) }.toTypedArray()
    override fun navigate(requestFocus: Boolean) { (element as? com.intellij.pom.Navigatable)?.navigate(requestFocus) }
    override fun canNavigate(): Boolean = (element as? com.intellij.pom.Navigatable)?.canNavigate() == true
    override fun canNavigateToSource(): Boolean = canNavigate()
}
