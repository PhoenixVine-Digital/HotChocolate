package hc.intellij

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import javax.swing.Icon

// Settings > Editor > Color Scheme > HotChocolate -- without this, every syntax color
// `HCSyntaxHighlighter` assigns (`HCHighlightKeys`) is permanently fixed to whatever its default
// `DefaultLanguageHighlighterColors` mapping happens to be; a user can't customize any of it, or
// even preview what each token category maps to. Purely mechanical wiring over already-real
// pieces -- every `AttributesDescriptor` below reuses an EXISTING `HCHighlightKeys` entry
// (`HCSyntaxHighlighter.kt`'s own real token-to-key mapping), and the demo text is real,
// syntactically valid HotChocolate exercising every one of them at least once.
private val DESCRIPTORS = arrayOf(
    AttributesDescriptor("Keyword", HCHighlightKeys.KEYWORD),
    AttributesDescriptor("Keyword literal (true/false/null)", HCHighlightKeys.KEYWORD_LITERAL),
    AttributesDescriptor("String", HCHighlightKeys.STRING),
    AttributesDescriptor("Number", HCHighlightKeys.NUMBER),
    AttributesDescriptor("Line comment", HCHighlightKeys.LINE_COMMENT),
    AttributesDescriptor("Doc comment", HCHighlightKeys.DOC_COMMENT),
    AttributesDescriptor("Identifier", HCHighlightKeys.IDENTIFIER),
    AttributesDescriptor("Operator", HCHighlightKeys.OPERATOR),
    AttributesDescriptor("Parentheses", HCHighlightKeys.PARENTHESES),
    AttributesDescriptor("Braces", HCHighlightKeys.BRACES),
    AttributesDescriptor("Brackets", HCHighlightKeys.BRACKETS),
    AttributesDescriptor("Comma", HCHighlightKeys.COMMA),
    AttributesDescriptor("Semicolon", HCHighlightKeys.SEMICOLON),
    AttributesDescriptor("Dot", HCHighlightKeys.DOT),
)

private val DEMO_TEXT = """
    // a line comment
    /// a doc comment for `add`
    module demo;

    struct Point {
        x: Int,
        y: Int,
    }

    fn add(a: Int, b: Int) -> Int {
        let sum = a + b + 1;
        let flag = true;
        let name = "hello";
        let nums = [1, 2, 3];
        return sum + nums[0] + name.length();
    }
""".trimIndent()

class HCColorSettingsPage : ColorSettingsPage {
    override fun getIcon(): Icon? = HCFileType.icon
    override fun getHighlighter(): SyntaxHighlighter = HCSyntaxHighlighter()
    override fun getDemoText(): String = DEMO_TEXT
    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey>? = null
    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = DESCRIPTORS
    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY
    override fun getDisplayName(): String = "HotChocolate"
}
