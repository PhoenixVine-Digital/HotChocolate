package hc.intellij

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as Colors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.tree.IElementType

object HCHighlightKeys {
    val KEYWORD = createTextAttributesKey("HC_KEYWORD", Colors.KEYWORD)
    val STRING = createTextAttributesKey("HC_STRING", Colors.STRING)
    val NUMBER = createTextAttributesKey("HC_NUMBER", Colors.NUMBER)
    val LINE_COMMENT = createTextAttributesKey("HC_LINE_COMMENT", Colors.LINE_COMMENT)
    val DOC_COMMENT = createTextAttributesKey("HC_DOC_COMMENT", Colors.DOC_COMMENT)
    val IDENTIFIER = createTextAttributesKey("HC_IDENTIFIER", Colors.IDENTIFIER)
    val OPERATOR = createTextAttributesKey("HC_OPERATOR", Colors.OPERATION_SIGN)
    val PARENTHESES = createTextAttributesKey("HC_PARENTHESES", Colors.PARENTHESES)
    val BRACES = createTextAttributesKey("HC_BRACES", Colors.BRACES)
    val BRACKETS = createTextAttributesKey("HC_BRACKETS", Colors.BRACKETS)
    val COMMA = createTextAttributesKey("HC_COMMA", Colors.COMMA)
    val SEMICOLON = createTextAttributesKey("HC_SEMICOLON", Colors.SEMICOLON)
    val DOT = createTextAttributesKey("HC_DOT", Colors.DOT)
    val KEYWORD_LITERAL = createTextAttributesKey("HC_KEYWORD_LITERAL", Colors.KEYWORD)
    val BAD_CHARACTER = createTextAttributesKey("HC_BAD_CHARACTER", com.intellij.openapi.editor.colors.CodeInsightColors.ERRORS_ATTRIBUTES)
}

class HCSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = HCLexer()

    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> {
        val key = when (tokenType) {
            HCTokenTypes.KEYWORD -> HCHighlightKeys.KEYWORD
            HCTokenTypes.TRUE, HCTokenTypes.FALSE, HCTokenTypes.NULL_KW -> HCHighlightKeys.KEYWORD_LITERAL
            HCTokenTypes.STRING, HCTokenTypes.ISTRING_BEGIN, HCTokenTypes.ISTRING_PART, HCTokenTypes.ISTRING_END, HCTokenTypes.CHAR -> HCHighlightKeys.STRING
            HCTokenTypes.INT, HCTokenTypes.LONG, HCTokenTypes.FLOAT, HCTokenTypes.DOUBLE -> HCHighlightKeys.NUMBER
            HCTokenTypes.LINE_COMMENT -> HCHighlightKeys.LINE_COMMENT
            HCTokenTypes.DOC_COMMENT -> HCHighlightKeys.DOC_COMMENT
            HCTokenTypes.IDENT -> HCHighlightKeys.IDENTIFIER
            HCTokenTypes.OPERATOR -> HCHighlightKeys.OPERATOR
            HCTokenTypes.LPAREN, HCTokenTypes.RPAREN -> HCHighlightKeys.PARENTHESES
            HCTokenTypes.LBRACE, HCTokenTypes.RBRACE -> HCHighlightKeys.BRACES
            HCTokenTypes.LBRACKET, HCTokenTypes.RBRACKET -> HCHighlightKeys.BRACKETS
            HCTokenTypes.COMMA -> HCHighlightKeys.COMMA
            HCTokenTypes.SEMI -> HCHighlightKeys.SEMICOLON
            HCTokenTypes.DOT -> HCHighlightKeys.DOT
            HCTokenTypes.BAD_CHARACTER -> HCHighlightKeys.BAD_CHARACTER
            else -> null
        }
        return if (key == null) emptyArray() else arrayOf(key)
    }
}

class HCSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?) = HCSyntaxHighlighter()
}
