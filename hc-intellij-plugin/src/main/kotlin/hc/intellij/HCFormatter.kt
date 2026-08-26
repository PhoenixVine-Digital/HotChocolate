package hc.intellij

import com.intellij.formatting.Alignment
import com.intellij.formatting.Block
import com.intellij.formatting.ChildAttributes
import com.intellij.formatting.FormattingContext
import com.intellij.formatting.FormattingModel
import com.intellij.formatting.FormattingModelBuilder
import com.intellij.formatting.FormattingModelProvider
import com.intellij.formatting.Indent
import com.intellij.formatting.Spacing
import com.intellij.formatting.SpacingBuilder
import com.intellij.formatting.Wrap
import com.intellij.lang.ASTNode
import com.intellij.openapi.util.TextRange
import com.intellij.psi.codeStyle.CodeStyleSettings

// Reformat support built on the same "no real grammar" premise `HCParserDefinition` discloses --
// rather than reading indent from a real PSI expression/statement tree (which doesn't exist
// here), this reconstructs a synthetic block hierarchy directly from `{`/`}` nesting in the flat
// leaf-token stream. One indent level per brace depth is exactly what a C-like language's
// "Reformat Code" needs 95% of the time; `(`/`[` nesting deliberately does NOT add its own indent
// level (matching how most formatters only indent on `{`, not every paren/bracket, to avoid
// runaway indentation on a long wrapped call/array literal).
class HCFormattingModelBuilder : FormattingModelBuilder {
    override fun createModel(formattingContext: FormattingContext): FormattingModel {
        val file = formattingContext.containingFile
        val settings = formattingContext.codeStyleSettings
        val spacingBuilder = buildSpacingBuilder(settings)
        val leaves = mutableListOf<ASTNode>()
        collectLeaves(file.node, leaves)
        val rootBlock = HCGroupBlock(leaves, spacingBuilder, Indent.getNoneIndent())
        return FormattingModelProvider.createFormattingModelForPsiFile(file, rootBlock, settings)
    }

    private fun collectLeaves(node: ASTNode, out: MutableList<ASTNode>) {
        var child = node.firstChildNode
        while (child != null) {
            if (child.firstChildNode == null) out.add(child) else collectLeaves(child, out)
            child = child.treeNext
        }
    }
}

private fun buildSpacingBuilder(settings: CodeStyleSettings): SpacingBuilder =
    SpacingBuilder(settings, HCLanguage)
        .after(HCTokenTypes.COMMA).spaceIf(true)
        .before(HCTokenTypes.COMMA).spaceIf(false)
        .before(HCTokenTypes.SEMI).spaceIf(false)
        .before(HCTokenTypes.COLON).spaceIf(false)
        .after(HCTokenTypes.COLON).spaceIf(true)
        .around(HCTokenTypes.ARROW).spaceIf(true)
        .around(HCTokenTypes.FATARROW).spaceIf(true)
        .around(HCTokenTypes.OPERATOR).spaceIf(true)
        .before(HCTokenTypes.LPAREN).spaceIf(false)
        .before(HCTokenTypes.LBRACKET).spaceIf(false)

// A leaf-only region of the flat token stream, re-grouped by `{`/`}` nesting into leaf blocks and
// nested `HCGroupBlock`s -- see this file's own header. `indent` is this block's OWN indent,
// interpreted by whichever block places it as a child (one extra level per `{` it sits inside).
private class HCGroupBlock(
    private val nodes: List<ASTNode>,
    private val spacingBuilder: SpacingBuilder,
    private val indent: Indent,
) : Block {
    private val computedSubBlocks: List<Block> by lazy { buildSubBlocks() }

    private fun buildSubBlocks(): List<Block> {
        val result = mutableListOf<Block>()
        var i = 0
        while (i < nodes.size) {
            val n = nodes[i]
            if (n.elementType == HCTokenTypes.LBRACE) {
                var depth = 1
                var j = i + 1
                while (j < nodes.size && depth > 0) {
                    when (nodes[j].elementType) {
                        HCTokenTypes.LBRACE -> depth++
                        HCTokenTypes.RBRACE -> depth--
                        else -> {}
                    }
                    if (depth == 0) break
                    j++
                }
                result.add(HCLeafBlock(n, spacingBuilder, Indent.getNoneIndent()))
                if (j > i + 1 && j <= nodes.size) {
                    val inner = nodes.subList(i + 1, minOf(j, nodes.size))
                    if (inner.isNotEmpty()) {
                        result.add(HCGroupBlock(inner, spacingBuilder, Indent.getNormalIndent()))
                    }
                }
                if (j < nodes.size) {
                    result.add(HCLeafBlock(nodes[j], spacingBuilder, Indent.getNoneIndent()))
                    i = j + 1
                } else {
                    i = nodes.size
                }
            } else {
                result.add(HCLeafBlock(n, spacingBuilder, Indent.getNoneIndent()))
                i++
            }
        }
        return result
    }

    override fun getTextRange(): TextRange =
        if (nodes.isEmpty()) TextRange.EMPTY_RANGE
        else TextRange(nodes.first().startOffset, nodes.last().let { it.startOffset + it.textLength })

    override fun getSubBlocks(): List<Block> = computedSubBlocks
    override fun getWrap(): Wrap? = null
    override fun getIndent(): Indent = indent
    override fun getAlignment(): Alignment? = null

    override fun getSpacing(child1: Block?, child2: Block): Spacing? {
        if (child1 !is HCLeafBlock || child2 !is HCLeafBlock) return null
        return spacingBuilder.getSpacing(this, child1, child2)
    }

    override fun getChildAttributes(newChildIndex: Int): ChildAttributes = ChildAttributes(Indent.getNormalIndent(), null)
    override fun isIncomplete(): Boolean = false
    override fun isLeaf(): Boolean = false
}

private class HCLeafBlock(
    private val node: ASTNode,
    private val spacingBuilder: SpacingBuilder,
    private val indent: Indent,
) : Block {
    override fun getTextRange(): TextRange = node.textRange
    override fun getSubBlocks(): List<Block> = emptyList()
    override fun getWrap(): Wrap? = null
    override fun getIndent(): Indent = indent
    override fun getAlignment(): Alignment? = null
    override fun getSpacing(child1: Block?, child2: Block): Spacing? = null
    override fun getChildAttributes(newChildIndex: Int): ChildAttributes = ChildAttributes(Indent.getNoneIndent(), null)
    override fun isIncomplete(): Boolean = false
    override fun isLeaf(): Boolean = true
}
