package hc.intellij

import com.intellij.codeInsight.editorActions.SimpleTokenSetQuoteHandler
import com.intellij.psi.tree.TokenSet

// Smart quote typing (auto-close `"`, and type-through an already-inserted closing `"` instead of
// inserting a duplicate) -- a real, previously missing piece: `HCBraceMatcher` already covers
// `(`/`{`/`[`, but braces and quotes are separate platform mechanisms (`BraceMatcher` vs
// `QuoteHandler`), and nothing registered the latter for this language at all. Covers
// `ISTRING_BEGIN`/`ISTRING_END` alongside plain `STRING` so an interpolated literal's own
// delimiting quotes get the same smart-typing treatment as a non-interpolated one.
class HCQuoteHandler : SimpleTokenSetQuoteHandler(
    TokenSet.create(HCTokenTypes.STRING, HCTokenTypes.ISTRING_BEGIN, HCTokenTypes.ISTRING_END),
)
