package hc.intellij

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.ProcessingContext

// Basic completion (Ctrl+Space) -- dispatches on the shape of the COMPLETION POSITION's own
// immediate parent, reusing exactly the same node-shape knowledge `HCReferences.kt` already has
// for the opposite direction (declaration -> reference vs. here, partial text -> declaration
// candidates). During completion, the platform inserts a dummy identifier at the caret before
// re-parsing (`CompletionUtilCoreImpl.DUMMY_IDENTIFIER`, an ordinary alphanumeric string our
// lexer treats as a plain `IDENT` like any other), so the SAME real recursive-descent parser used
// everywhere else in this plugin parses the in-progress code around the caret into a real (if
// locally throwaway) PSI shape -- no separate "completion grammar" needed.
//
// Three real contexts, matching exactly the shapes `identLed`/`callOrPrimary`/`typeRef`/
// `fieldList` produce (see those functions' own headers in `HCPsiParser.kt`):
// - Dummy ident's parent is `TYPE_REF` (`let x: <caret>`, `fn f(p: <caret>)`, a field's own
//   declared type, ...) -> type names (structs/enums/interfaces/extern classes + the primitive
//   type names, which are ordinary `IDENT`s in this grammar, not reserved keywords -- see
//   `HCTokenTypes.KEYWORDS`, which doesn't include them).
// - Dummy ident's parent is `FIELD_ACCESS_EXPR`/`METHOD_CALL_EXPR` AND immediately preceded by a
//   real `.` (as opposed to that same node shape's OTHER, `::`-qualified static-access reading --
//   see `HCReferences.fieldAccessTargets`'s own header) -> field/method names, receiver-type-aware
//   exactly like go-to-declaration (falls back to every field/method in scope when the receiver's
//   own type isn't confidently known, since an imprecise completion suggestion is harmless in a
//   way an imprecise annotator error never is).
// - Anything else (a fresh statement/expression, a struct-literal field's VALUE position, a bare
//   call's callee, ...) -> general completion: keywords that can start a statement/expression,
//   every local binding in the enclosing function, and every top-level name (functions, statics,
//   enum variants, plus struct/enum/interface/extern-class names themselves, since `Point { ...
//   }` and `Status::Active` both start as a plain bare identifier before the rest is typed).
class HCCompletionContributor : CompletionContributor() {
    init {
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), HCCompletionProvider)
    }
}

private val STATEMENT_KEYWORDS = listOf(
    "fn", "struct", "enum", "interface", "extern", "impl", "let", "var", "if", "else", "while",
    "for", "in", "match", "return", "break", "continue", "true", "false", "null", "self", "arena",
    "static", "module", "pub", "try", "catch", "throw", "sealed", "dev", "mut",
    // **Fixed 2026-09-23** -- these were all real reserved keywords already (some since
    // 2026-09-11, the rest added the same day array slicing landed) but never made it into THIS
    // list, a separate hardcoded set from `HCTokenTypes.KEYWORDS` -- so completion never
    // suggested them even though the parser/lexer/highlighter already knew them.
    "use", "component", "system", "resource", "unit", "parallel", "sequence", "typestate",
    "state", "event", "handle",
)

private val PRIMITIVE_TYPE_NAMES = listOf("Int", "Long", "Float", "Double", "Bool", "String", "Char")

private object HCCompletionProvider : CompletionProvider<CompletionParameters>() {
    override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
        val position = parameters.position
        if (position.language != HCLanguage) return
        // `position`/its parse tree live in a throwaway, NON-PHYSICAL copy of the file (with a
        // dummy identifier substituted at the caret) that completion always runs against --
        // `containingFile` on it works fine, but `containingDirectory` comes back `null` (no real
        // `VirtualFile` backs the copy), silently breaking every same-directory sibling-file
        // lookup (`filesInScope`) if used for that. `parameters.originalFile` is the real,
        // physical file instead -- use it for anything file/directory-scoped; `position` itself
        // is still the right tree to read PARSE CONTEXT from (parent shape, enclosing fn, ...)
        // since that's exactly the in-progress-typing structure completion needs.
        val file = parameters.originalFile
        val parent = position.parent ?: return

        when (parent.node?.elementType) {
            HCElementTypes.TYPE_REF -> addTypeCompletions(file, result)
            HCElementTypes.FIELD_ACCESS_EXPR, HCElementTypes.METHOD_CALL_EXPR -> {
                val kids = directChildren(parent)
                if (kids.any { it.node?.elementType == HCTokenTypes.DOT }) {
                    addMemberCompletions(parent, file, result)
                } else {
                    addGeneralCompletions(position, file, result)
                }
            }
            else -> addGeneralCompletions(position, file, result)
        }
    }
}

private fun addTypeCompletions(file: PsiFile, result: CompletionResultSet) {
    for (name in PRIMITIVE_TYPE_NAMES) result.addElement(LookupElementBuilder.create(name).withTypeText("primitive", true))
    for (f in filesInScope(file)) {
        for (decl in directChildren(f)) {
            val kind = declKind(decl) ?: continue
            if (kind !in setOf("struct", "enum", "interface", "extern class")) continue
            val name = declaredName(decl)?.text ?: continue
            result.addElement(LookupElementBuilder.create(name).withTypeText(kind, true))
        }
    }
}

private fun addMemberCompletions(host: PsiElement, file: PsiFile, result: CompletionResultSet) {
    val receiver = host.firstChild ?: return
    val fnDecl = findEnclosingFnDecl(host)
    val enclosingStruct = findEnclosingImplTargetStruct(host)
    val structName = fnDecl?.let { receiverBaseType(receiver, it, enclosingStruct) }

    val structNames = if (structName != null) listOf(structName) else allStructNames(file)
    for (sName in structNames) {
        for (fieldDecl in fieldsOf(sName, file)) {
            declaredName(fieldDecl)?.text?.let { result.addElement(LookupElementBuilder.create(it).withTypeText(sName, true)) }
        }
        for (fnDeclInImpl in methodsOf(sName, file)) {
            declaredName(fnDeclInImpl)?.text?.let {
                result.addElement(LookupElementBuilder.create(it).withTailText("()", true).withTypeText(sName, true))
            }
        }
    }
}

private fun addGeneralCompletions(position: PsiElement, file: PsiFile, result: CompletionResultSet) {
    for (kw in STATEMENT_KEYWORDS) result.addElement(LookupElementBuilder.create(kw).bold())

    findEnclosingFnDecl(position)?.let { fnDecl ->
        for (name in localBindingNames(fnDecl)) result.addElement(LookupElementBuilder.create(name).withTypeText("local", true))
    }

    for (f in filesInScope(file)) {
        for (decl in directChildren(f)) {
            val kind = declKind(decl) ?: continue
            when (kind) {
                "function" -> declaredName(decl)?.text?.let { result.addElement(LookupElementBuilder.create(it).withTailText("()", true).withTypeText(kind, true)) }
                "static", "struct", "enum", "interface", "extern class" ->
                    declaredName(decl)?.text?.let { result.addElement(LookupElementBuilder.create(it).withTypeText(kind, true)) }
                else -> {}
            }
            if (decl.node?.elementType == HCElementTypes.ENUM_DECL) {
                for (variant in directChildren(decl).filter { it.node?.elementType == HCElementTypes.ENUM_VARIANT }) {
                    declaredName(variant)?.text?.let { result.addElement(LookupElementBuilder.create(it).withTypeText("variant", true)) }
                }
            }
        }
    }
}

private fun declKind(decl: PsiElement): String? = when (decl.node?.elementType) {
    HCElementTypes.FN_DECL -> "function"
    HCElementTypes.STRUCT_DECL -> "struct"
    HCElementTypes.ENUM_DECL -> "enum"
    HCElementTypes.INTERFACE_DECL -> "interface"
    HCElementTypes.EXTERN_CLASS_DECL -> "extern class"
    HCElementTypes.STATIC_DECL -> "static"
    else -> null
}

private fun allStructNames(file: PsiFile): List<String> {
    val out = mutableListOf<String>()
    for (f in filesInScope(file)) {
        for (decl in directChildren(f).filter { it.node?.elementType == HCElementTypes.STRUCT_DECL }) {
            declaredName(decl)?.text?.let { out += it }
        }
    }
    return out
}

private fun fieldsOf(structName: String, file: PsiFile): List<PsiElement> {
    for (f in filesInScope(file)) {
        for (decl in directChildren(f)) {
            if (decl.node?.elementType == HCElementTypes.STRUCT_DECL && declaredName(decl)?.text == structName) {
                return directChildren(decl).filter { it.node?.elementType == HCElementTypes.FIELD_DECL }
            }
        }
    }
    return emptyList()
}

private fun methodsOf(structName: String, file: PsiFile): List<PsiElement> {
    val out = mutableListOf<PsiElement>()
    for (f in filesInScope(file)) {
        for (implDecl in directChildren(f).filter { it.node?.elementType == HCElementTypes.IMPL_DECL }) {
            if (implTargetStructName(implDecl) != structName) continue
            out += directChildren(implDecl).filter { it.node?.elementType == HCElementTypes.FN_DECL }
        }
    }
    return out
}

// Same binding-site coverage as `HCReferences.findLocalBinding`, enumerating every NAME rather
// than searching for one -- params (incl. `self`), `let`, `for`, `catch`, `match`/`if let`
// pattern binds, lambda params.
private fun localBindingNames(fnDecl: PsiElement): List<String> {
    val out = mutableListOf<String>()
    for (param in elementsOfType(fnDecl, HCElementTypes.PARAM)) declaredName(param)?.text?.let { out += it }
    for (letStmt in elementsOfType(fnDecl, HCElementTypes.LET_STMT)) declaredName(letStmt)?.text?.let { out += it }
    for (forStmt in elementsOfType(fnDecl, HCElementTypes.FOR_STMT)) declaredName(forStmt)?.text?.let { out += it }
    for (catchClause in elementsOfType(fnDecl, HCElementTypes.CATCH_CLAUSE)) declaredName(catchClause)?.text?.let { out += it }
    for (pattern in elementsOfType(fnDecl, HCElementTypes.VARIANT_PATTERN)) {
        val kids = directChildren(pattern)
        val braceIdx = kids.indexOfFirst { it.node?.elementType == HCTokenTypes.LBRACE }
        if (braceIdx < 0) continue
        for (kid in kids.drop(braceIdx + 1)) {
            if (kid.node?.elementType == HCTokenTypes.IDENT) out += kid.text
        }
    }
    for (lambda in elementsOfType(fnDecl, HCElementTypes.LAMBDA_EXPR)) {
        for (kid in directChildren(lambda)) {
            if (kid.node?.elementType == HCTokenTypes.IDENT) out += kid.text
        }
    }
    return out
}
