package hc.intellij

import com.intellij.lang.parameterInfo.CreateParameterInfoContext
import com.intellij.lang.parameterInfo.ParameterInfoHandler
import com.intellij.lang.parameterInfo.ParameterInfoUIContext
import com.intellij.lang.parameterInfo.UpdateParameterInfoContext
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

// Parameter info popup (Ctrl+P) -- deliberately reuses go-to-declaration's OWN reference
// resolution (`callNode.references`/a `CALL_EXPR`'s own callee `REF_EXPR`'s references) to find
// the callee's real `FN_DECL`, rather than re-deriving signature resolution here: whatever
// `HCReferences.kt` already resolves a call to IS the right callee for this popup too, including
// its receiver-type-aware precision for `METHOD_CALL_EXPR`/`STATIC_CALL_EXPR`.
data class HCParamInfo(val paramTexts: List<String>)

class HCParameterInfoHandler : ParameterInfoHandler<PsiElement, HCParamInfo> {
    override fun findElementForParameterInfo(context: CreateParameterInfoContext): PsiElement? {
        val argList = findArgListAt(context.file, context.offset) ?: return null
        val fnDecl = resolveCalleeFnDecl(argList.parent ?: return null) ?: return null
        context.itemsToShow = arrayOf(HCParamInfo(paramTextsOf(fnDecl)))
        return argList
    }

    override fun showParameterInfo(element: PsiElement, context: CreateParameterInfoContext) {
        context.showHint(element, element.textRange.startOffset, this)
    }

    override fun findElementForUpdatingParameterInfo(context: UpdateParameterInfoContext): PsiElement? =
        findArgListAt(context.file, context.offset)

    override fun updateParameterInfo(parameterOwner: PsiElement, context: UpdateParameterInfoContext) {
        context.setCurrentParameter(currentArgIndex(parameterOwner, context.offset))
    }

    override fun updateUI(p: HCParamInfo, context: ParameterInfoUIContext) {
        if (p.paramTexts.isEmpty()) {
            context.setupUIComponentPresentation("<no parameters>", -1, -1, false, false, false, context.defaultParameterColor)
            return
        }
        val text = p.paramTexts.joinToString(", ")
        val index = context.currentParameterIndex
        var start = -1
        var end = -1
        if (index in p.paramTexts.indices) {
            start = p.paramTexts.subList(0, index).sumOf { it.length + 2 }
            end = start + p.paramTexts[index].length
        }
        context.setupUIComponentPresentation(text, start, end, false, false, false, context.defaultParameterColor)
    }
}

private fun findArgListAt(file: PsiFile, offset: Int): PsiElement? {
    var el: PsiElement? = file.findElementAt(offset) ?: file.findElementAt(offset - 1) ?: return null
    while (el != null && el !is PsiFile) {
        if (el.node?.elementType == HCElementTypes.ARG_LIST) return el
        el = el.parent
    }
    return null
}

// A `CALL_EXPR`'s own reference is registered on its callee `REF_EXPR` child, not the `CALL_EXPR`
// node itself (see `HCReferences.kt`'s own `REFERENCE_HOST_TYPES` -- `CALL_EXPR` only carries a
// reference directly for the unrelated `arena Name[count]` shape); `METHOD_CALL_EXPR`/
// `STATIC_CALL_EXPR` carry their own method-name reference directly on the composite itself.
private fun resolveCalleeFnDecl(callNode: PsiElement): PsiElement? {
    val refHost = if (callNode.node?.elementType == HCElementTypes.CALL_EXPR) {
        directChildren(callNode).firstOrNull { it.node?.elementType == HCElementTypes.REF_EXPR } ?: callNode
    } else {
        callNode
    }
    for (ref in refHost.references) {
        val target = ref.resolve() ?: continue
        if (target.parent?.node?.elementType == HCElementTypes.FN_DECL) return target.parent
    }
    return null
}

// `self` excluded (a call site never supplies it) -- the raw `TYPE_REF` text (not `simpleTypeName`)
// is shown, since a popup benefits from the FULL declared type (`&Foo`, `[Int]`, `Vec<T>`, ...)
// where the type-checking passes elsewhere in this plugin deliberately stay conservative instead.
private fun paramTextsOf(fnDecl: PsiElement): List<String> {
    val paramList = directChildren(fnDecl).firstOrNull { it.node?.elementType == HCElementTypes.PARAM_LIST } ?: return emptyList()
    return directChildren(paramList).filter { it.node?.elementType == HCElementTypes.PARAM }.mapNotNull { param ->
        val name = declaredName(param)?.text ?: return@mapNotNull null
        if (name == "self") return@mapNotNull null
        val typeRef = directChildren(param).firstOrNull { it.node?.elementType == HCElementTypes.TYPE_REF }
        if (typeRef != null) "$name: ${typeRef.text}" else name
    }
}

private fun currentArgIndex(argList: PsiElement, offset: Int): Int {
    var index = 0
    for (child in directChildren(argList)) {
        if (child.textRange.startOffset >= offset) break
        if (child.node?.elementType == HCTokenTypes.COMMA) index++
    }
    return index
}
