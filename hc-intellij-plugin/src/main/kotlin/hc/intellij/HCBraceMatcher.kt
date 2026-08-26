package hc.intellij

import com.intellij.lang.BracePair
import com.intellij.lang.PairedBraceMatcher
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType

class HCBraceMatcher : PairedBraceMatcher {
    private val pairs = arrayOf(
        BracePair(HCTokenTypes.LBRACE, HCTokenTypes.RBRACE, true),
        BracePair(HCTokenTypes.LPAREN, HCTokenTypes.RPAREN, false),
        BracePair(HCTokenTypes.LBRACKET, HCTokenTypes.RBRACKET, false),
    )

    override fun getPairs(): Array<BracePair> = pairs

    override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, contextType: IElementType?): Boolean = true

    override fun getCodeConstructStart(file: PsiFile?, openingBraceOffset: Int): Int = openingBraceOffset
}
