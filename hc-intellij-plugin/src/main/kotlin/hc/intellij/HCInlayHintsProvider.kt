package hc.intellij

import com.intellij.codeInsight.hints.ChangeListener
import com.intellij.codeInsight.hints.FactoryInlayHintsCollector
import com.intellij.codeInsight.hints.ImmediateConfigurable
import com.intellij.codeInsight.hints.InlayHintsCollector
import com.intellij.codeInsight.hints.InlayHintsProvider
import com.intellij.codeInsight.hints.InlayHintsSink
import com.intellij.codeInsight.hints.NoSettings
import com.intellij.codeInsight.hints.SettingsKey
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import javax.swing.JComponent
import javax.swing.JPanel

// Argument-name inlay hints at call sites (`greet(/* name: */ "world")`) -- now driven by
// `HCPsiParser`'s real `CALL_EXPR`/`ARG_LIST` nodes (each direct, non-punctuation child of an
// `ARG_LIST` is exactly one argument's own root node, since `HCPsiParser.argList` parses each one
// via a single `expression()` call), not a token-by-token depth scan over a flat tree. Declared
// signatures still come from a whole-file regex scan (`collectFnParams`) rather than real
// symbol resolution -- a real, disclosed limitation: this fires for ANY `fn` in the file by this
// exact name, with no receiver-aware overload resolution (a struct's own `impl` method called via
// `recv.method(...)` isn't even a `CALL_EXPR` at all -- see `METHOD_CALL_EXPR` -- so it's
// naturally excluded, but two top-level `fn`s that happened to share a name, if the checker even
// allowed that, would collide here).
@Suppress("UnstableApiUsage")
class HCInlayHintsProvider : InlayHintsProvider<NoSettings> {
    override val key: SettingsKey<NoSettings> = SettingsKey("hc.parameter.hints")
    override val name: String = "Parameter hints"
    override val previewText: String = "fn greet(name: String) {\n}\n\ngreet(\"world\")"

    override fun createSettings(): NoSettings = NoSettings()

    override fun getCollectorFor(file: PsiFile, editor: Editor, settings: NoSettings, sink: InlayHintsSink): InlayHintsCollector? {
        if (file.language != HCLanguage) return null
        val paramsByFn = collectFnParams(file.text)
        if (paramsByFn.isEmpty()) return null
        return Collector(editor, paramsByFn)
    }

    override fun createConfigurable(settings: NoSettings): ImmediateConfigurable = object : ImmediateConfigurable {
        override fun createComponent(listener: ChangeListener): JComponent = JPanel()
    }

    override val isVisibleInSettings: Boolean = true

    private class Collector(editor: Editor, private val paramsByFn: Map<String, List<String>>) : FactoryInlayHintsCollector(editor) {
        private val punctuation = setOf(HCTokenTypes.LPAREN, HCTokenTypes.RPAREN, HCTokenTypes.COMMA)

        override fun collect(element: PsiElement, editor: Editor, sink: InlayHintsSink): Boolean {
            if (element.node?.elementType != HCElementTypes.CALL_EXPR) return true
            val callee = element.firstChild ?: return true
            if (callee.node?.elementType != HCElementTypes.REF_EXPR) return true
            val params = paramsByFn[callee.text] ?: return true
            var argList = callee.nextSibling
            while (argList is PsiWhiteSpace) argList = argList.nextSibling
            if (argList?.node?.elementType != HCElementTypes.ARG_LIST) return true

            var argIndex = 0
            var child = argList.firstChild
            while (child != null && argIndex < params.size) {
                val t = child.node?.elementType
                if (child !is PsiWhiteSpace && t != HCTokenTypes.LINE_COMMENT && t != HCTokenTypes.DOC_COMMENT && t !in punctuation) {
                    sink.addInlineElement(child.textRange.startOffset, false, factory.smallText(params[argIndex] + ":"), false)
                    argIndex++
                }
                child = child.nextSibling
            }
            return true
        }
    }
}

private val FN_DECL = Regex("""fn\s+(\w+)\s*(?:<\s*\w+\s*>)?\s*\(([^)]*)\)""")

private fun collectFnParams(fileText: String): Map<String, List<String>> {
    val out = mutableMapOf<String, List<String>>()
    for (m in FN_DECL.findAll(fileText)) {
        val name = m.groupValues[1]
        val params = splitTopLevel(m.groupValues[2])
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { paramNameOf(it) }
            .filter { it != "self" }
        if (params.isNotEmpty()) out[name] = params
    }
    return out
}

// Splits a raw param-list substring on top-level commas only -- depth-tracked over `<>`/`[]`/`()`
// so a param typed `Vec<Registry<T>>` (a nested generic argument) doesn't get split mid-type.
private fun splitTopLevel(s: String): List<String> {
    val parts = mutableListOf<String>()
    var depth = 0
    var start = 0
    for (i in s.indices) {
        when (s[i]) {
            '<', '[', '(' -> depth++
            '>', ']', ')' -> depth--
            ',' -> if (depth == 0) {
                parts.add(s.substring(start, i))
                start = i + 1
            }
        }
    }
    parts.add(s.substring(start))
    return parts
}

// A raw param entry is `[&][mut ]name: Type` (or bare `self`/`&self`/`&mut self`) -- only the
// name is needed for a hint label.
private fun paramNameOf(raw: String): String? {
    var s = raw.trim()
    if (s == "self" || s == "&self" || s == "&mut self") return "self"
    s = s.removePrefix("&").trim().removePrefix("mut").trim()
    val colon = s.indexOf(':')
    if (colon <= 0) return null
    return s.substring(0, colon).trim().takeIf { it.isNotEmpty() && it[0].isJavaIdentifierStart() }
}
