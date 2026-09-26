package hc.intellij

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil

// The first real SEMANTIC checks (not just syntax) -- "errors soon," now partially arrived. Four
// checks, each deliberately scoped to what's verifiable off `HCPsiParser`'s real tree alone,
// without needing actual TYPE inference (that's the next, much bigger, follow-up):
// 1. `@` annotation/directive validation -- mirrors the real compiler's own `leadingMarkers()`
//    (see `Parser.kt`'s own error strings, copied verbatim below so a user sees the SAME message
//    in the IDE as they would from a real `hc build`) -- an unrecognized directive, or one used
//    before the wrong declaration kind.
// 2. `break`/`continue` outside any enclosing loop.
// 3. Duplicate top-level declaration names, scoped per-kind (`Duplicate function`/`struct`/
//    `enum`/`interface`/`extern class`, matching the real compiler's own message text) plus the
//    real compiler's own cross-kind rule: an enum variant's name shares a namespace with plain
//    struct names (`StructLit`/`variant` construction sites are otherwise ambiguous).
// 4. Undefined references -- a bare `REF_EXPR` (see `HCPsiParser.identLed`'s own header for
//    exactly which shapes become `REF_EXPR` vs. something else entirely -- `a.b.c`'s `b`/`c`,
//    `Type::method`'s `Type`, and a struct literal's own field names are all PLAIN LEAF tokens
//    under a different node, never `REF_EXPR`, so this check is already naturally scoped to real
//    value references without needing to special-case any of them) that doesn't resolve to
//    anything in scope -- see `collectLocalNames`'s own header for the deliberately conservative,
//    over-inclusive resolution rule this uses to stay FALSE-POSITIVE-safe without real type
//    inference or true block scoping.
//
// Called once per `PsiElement` in the tree (bottom-up, not once per file) -- each check below is
// keyed on the specific node kind(s) it cares about and is a no-op for everything else. Checks
// 3/4 additionally only do real work once, at the file root (`element is PsiFile`), since both
// need whole-file context (every top-level name; every `FN_DECL`'s own local names) rather than
// being meaningfully checkable one node at a time.
class HCAnnotator : Annotator {
    companion object {
        // Bare or parenthesized-args directives, grouped by the ONE declaration kind each is
        // valid before -- see `checkAnnotation`'s own header. Whether a directive takes `(...)`
        // args isn't checked here at all (the generic `leadingAnnotations` parser already
        // consumes an optional `(...)` for ANY directive uniformly, see that fn's own header) --
        // only WHICH declaration kind follows it.
        private val FN_ONLY = setOf(
            "deterministic", "gpu", "startup", "update", "fixed_update", "render",
            "requires", "ensures",
        )
        private val STRUCT_ONLY = setOf("sendable", "derive")
        private val STATIC_ONLY = setOf("tunable")
        private val SYSTEM_ONLY = setOf("after", "before", "profile", "main_thread", "run_if")
    }

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        when (element.node?.elementType) {
            HCElementTypes.ANNOTATION -> checkAnnotation(element, holder)
            HCElementTypes.BREAK_STMT -> checkLoopControl(element, holder, "break")
            HCElementTypes.CONTINUE_STMT -> checkLoopControl(element, holder, "continue")
            else -> {}
        }
        if (element is PsiFile) {
            checkDuplicateTopLevelNames(element, holder)
            checkUndefinedReferences(element, holder)
            // Argument-count/basic-literal-type checks -- see `HCTypeChecks.kt`'s own header.
            checkArgCountsAndBasicTypes(element, holder)
            checkUnreachableCode(element, holder)
            checkUnusedVariables(element, holder)
            checkUnusedFunctions(element, holder)
            checkShadowing(element, holder)
        }
    }

    private fun warn(holder: AnnotationHolder, anchor: PsiElement, message: String) {
        holder.newAnnotation(HighlightSeverity.WARNING, message).range(anchor).create()
    }

    private fun directChildren(element: PsiElement): List<PsiElement> {
        val out = mutableListOf<PsiElement>()
        var c = element.firstChild
        while (c != null) {
            out.add(c)
            c = c.nextSibling
        }
        return out
    }

    // Skips whitespace, a leading `pub`/`open`/`priv` modifier keyword, and any OTHER stacked
    // `@...` annotation -- `leadingAnnotations` (see its own header) creates one SEPARATE sibling
    // `ANNOTATION` node per `@...` (never nesting them), and `pub`/`open` are bare tokens
    // consumed directly under the file/block root, both BEFORE the actual declaration's own node
    // ever opens. So `@profile @after(Other) system S { ... }`'s FIRST annotation's own next
    // sibling is the SECOND annotation node, not `SYSTEM_DECL` -- and `@sendable pub struct Foo {
    // ... }`'s sits before a bare "pub" leaf, not directly before `STRUCT_DECL` either. Real,
    // previously-latent gap in BOTH shapes, pre-existing (not specific to any one directive),
    // just never exercised by a test before stacked-annotation/`pub`-plus-directive coverage was
    // added alongside the new directives below.
    private fun nextSignificantSibling(element: PsiElement): PsiElement? {
        var next = element.nextSibling
        while (
            next is PsiWhiteSpace ||
            next?.node?.elementType == HCElementTypes.ANNOTATION ||
            (next?.node?.elementType == HCTokenTypes.KEYWORD && (next.text == "pub" || next.text == "open" || next.text == "priv"))
        ) {
            next = next.nextSibling
        }
        return next
    }

    private fun error(holder: AnnotationHolder, anchor: PsiElement, message: String) {
        holder.newAnnotation(HighlightSeverity.ERROR, message).range(anchor).create()
    }

    // `@name` / `@"binary.Name"` -- children are `[AT, nameToken, (LPAREN ... RPAREN)?]`, see
    // `HCPsiParser.leadingAnnotations`. `FN_ONLY`/`STRUCT_ONLY`/`STATIC_ONLY`/`SYSTEM_ONLY` --
    // every bare compiler directive `Parser.hotc`'s own top-level `while self.check(AT) { ... }`
    // dispatch recognizes as of this writing, grouped by the ONE declaration kind each is valid
    // before (see that fn's own `directive()`-dispatch chain and each directive's own `Ast.hc`
    // header for why). `must_use`/`dev`/`serializable`/`entry` (pre-existing) aren't in these sets
    // -- they keep their own explicit branches below, unchanged.
    private fun checkAnnotation(element: PsiElement, holder: AnnotationHolder) {
        // A NESTED annotation VALUE (`at: @"...At"(value: "HEAD")` -- see `HCPsiParser.primary`'s
        // own header on why this is a real, recursive `ANNOTATION` node just like a top-level
        // one) isn't subject to the top-level PLACEMENT rule at all -- its own parent is another
        // `ANNOTATION`, never a real declaration, so `nextSignificantSibling` below would find
        // whatever token follows it INSIDE the outer annotation's own arg list (a `,`/`)`, never
        // an `FN_DECL`/`STRUCT_DECL`) and false-positive "annotations can only precede ...".
        if (element.parent?.node?.elementType == HCElementTypes.ANNOTATION) return
        val nameToken = directChildren(element).getOrNull(1) ?: return
        val isString = nameToken.node?.elementType == HCTokenTypes.STRING
        val text = nameToken.text
        val kind = when {
            isString -> "string"
            text == "must_use" -> "must_use"
            text == "serializable" -> "serializable"
            text == "entry" -> "entry"
            text == "dev" -> "dev"
            text in FN_ONLY -> "fn_only:$text"
            text in STRUCT_ONLY -> "struct_only:$text"
            text in STATIC_ONLY -> "static_only:$text"
            text in SYSTEM_ONLY -> "system_only:$text"
            else -> null
        }
        if (kind == null) {
            error(
                holder, element,
                "expected a quoted annotation name (@\"binary.Name\") or a known compiler directive " +
                    "after '@' (@must_use, @dev, @serializable, @entry(\"target\", ...), " +
                    "@deterministic, @gpu, @sendable, @tunable, @derive(...), @startup/@update/" +
                    "@fixed_update/@render, @requires(...)/@ensures(...), @after(...)/@before(...)/" +
                    "@profile/@main_thread/@run_if(...))",
            )
            return
        }

        if (element.parent?.node?.elementType == HCElementTypes.IMPL_DECL) {
            if (kind != "must_use") error(holder, element, "only '@must_use' is supported before an impl method")
            return
        }

        val next = nextSignificantSibling(element)
        val nextType = next?.node?.elementType
        val isFn = nextType == HCElementTypes.FN_DECL
        val isStruct = nextType == HCElementTypes.STRUCT_DECL
        val isStatic = nextType == HCElementTypes.STATIC_DECL
        val isSystem = nextType == HCElementTypes.SYSTEM_DECL
        when {
            kind == "serializable" -> if (!isStruct) error(holder, element, "'@serializable' can only precede a top-level 'struct'")
            kind == "must_use" -> if (!isFn) error(holder, element, "'@must_use' can only precede a top-level 'fn'")
            kind == "dev" -> if (!isFn) error(holder, element, "'@dev' can only precede a top-level 'fn'")
            kind == "entry" -> if (!isFn) error(holder, element, "'@entry(...)' can only precede a top-level 'fn'")
            kind == "string" -> if (!isFn && !isStruct) error(holder, element, "annotations can only precede a top-level 'struct' or 'fn'")
            kind.startsWith("fn_only:") -> if (!isFn) error(holder, element, "'@$text' can only precede a top-level 'fn'")
            kind.startsWith("struct_only:") -> if (!isStruct) error(holder, element, "'@$text' can only precede a top-level 'struct'")
            kind.startsWith("static_only:") -> if (!isStatic) error(holder, element, "'@$text' can only precede a top-level 'static'")
            kind.startsWith("system_only:") -> if (!isSystem) error(holder, element, "'@$text' can only precede a 'system'")
        }
    }

    // Walks up parents looking for an enclosing `WHILE_STMT`/`FOR_STMT` -- stops at the nearest
    // `FN_DECL` (a `break`/`continue` never sees past its own function's boundary) or the file
    // root.
    private fun checkLoopControl(element: PsiElement, holder: AnnotationHolder, keyword: String) {
        var p: PsiElement? = element.parent
        while (p != null) {
            val t = p.node?.elementType
            if (t == HCElementTypes.WHILE_STMT || t == HCElementTypes.FOR_STMT) return
            if (t == HCElementTypes.FN_DECL || p is PsiFile) break
            p = p.parent
        }
        error(holder, element, "'$keyword' can only appear inside a loop")
    }

    private val DECL_KEYWORD_TO_KIND = mapOf(
        HCElementTypes.FN_DECL to "function",
        HCElementTypes.STRUCT_DECL to "struct",
        HCElementTypes.ENUM_DECL to "enum",
        HCElementTypes.INTERFACE_DECL to "interface",
        HCElementTypes.EXTERN_CLASS_DECL to "extern class",
    )

    private fun declaredName(decl: PsiElement): PsiElement? =
        directChildren(decl).firstOrNull { it.node?.elementType == HCTokenTypes.IDENT }

    private fun checkDuplicateTopLevelNames(file: PsiFile, holder: AnnotationHolder) {
        val seenByKind = mutableMapOf<String, MutableMap<String, PsiElement>>()
        // Enum variant names and plain struct names share ONE namespace (real compiler rule --
        // `Duplicate variant/struct name`, see this file's own header) -- tracked separately from
        // `seenByKind["struct"]` since the message differs and a struct/variant collision isn't
        // itself a same-kind duplicate.
        val structAndVariantNames = mutableMapOf<String, PsiElement>()

        for (decl in directChildren(file)) {
            val kind = DECL_KEYWORD_TO_KIND[decl.node?.elementType] ?: continue
            val nameToken = declaredName(decl) ?: continue
            val name = nameToken.text

            val bucket = seenByKind.getOrPut(kind) { mutableMapOf() }
            if (bucket.containsKey(name)) {
                error(holder, nameToken, "Duplicate $kind '$name'")
            } else {
                bucket[name] = nameToken
            }

            if (kind == "struct") {
                if (structAndVariantNames.containsKey(name)) {
                    error(
                        holder, nameToken,
                        "Duplicate variant/struct name '$name' (enum variant names share a namespace with structs and must be globally unique)",
                    )
                } else {
                    structAndVariantNames[name] = nameToken
                }
            }
            if (kind == "enum") {
                for (variant in directChildren(decl).filter { it.node?.elementType == HCElementTypes.ENUM_VARIANT }) {
                    val variantName = declaredName(variant) ?: continue
                    val vText = variantName.text
                    if (structAndVariantNames.containsKey(vText)) {
                        error(
                            holder, variantName,
                            "Duplicate variant/struct name '$vText' (enum variant names share a namespace with structs and must be globally unique)",
                        )
                    } else {
                        structAndVariantNames[vText] = variantName
                    }
                }
            }
        }
    }

    private fun elementsOfType(root: PsiElement, type: HCElementType): List<PsiElement> =
        PsiTreeUtil.collectElements(root) { it.node?.elementType == type }.toList()

    // Names that are real, callable/referenceable values in EVERY `.hc`/`.hotc` file regardless
    // of what that file itself declares -- none of these come from a `FnDecl`/`EnumDecl` this
    // file's own parser would ever see, so `collectTopLevelValueNames` genuinely cannot discover
    // them by walking the current file's tree. Two different reasons a name ends up here:
    // - `print`/`read_line` are real compiler INTRINSICS, special-cased directly in the real
    //   checker's own fn-type map (`Checker.kt`: `fnRetTypes = checker.fns... + ("print" to
    //   Ty.Unit_) + ("read_line" to Ty.Str_())`) rather than resolved from any declared `fn` at
    //   all.
    // - `vec_of`/`registry_new`/`read_int`/`read_string`/`read_ints`/`read_strings` are real
    //   PRELUDE functions (`Prelude.kt`'s `PRELUDE_SOURCE`), auto-merged into every program before
    //   the checker ever runs; `Some`/`None`/`Ok`/`Err` are the prelude's own `Option<T>`/
    //   `Result<T, E>` enum variants, included here too since a bare bit like `return None;` is a
    //   real, common shape and this check has no way to special-case "these enums came from the
    //   prelude" from ordinary enum-variant collection.
    // - `gpu_thread_id` is a `@gpu`-fn-body-only pseudo-builtin (see ARCHITECTURE.md's own
    //   "`@gpu`" entry / `Driver.hotc`'s own `check_gpu_expr` header) -- never a real `FnDecl`
    //   anywhere (a `@gpu` fn's body is entirely rewritten away before the ordinary checker/
    //   codegen pipeline ever runs), so it can't be discovered by walking the file's own tree
    //   either.
    // Missing an entry here is a REAL false-positive risk, not a cosmetic gap -- see
    // `HCAnnotatorTest`'s own real-example sweep, which is exactly what this list was built and
    // verified against.
    private val ALWAYS_KNOWN_VALUE_NAMES = setOf(
        "print", "read_line", "drop",
        "vec_of", "registry_new", "read_int", "read_string", "read_ints", "read_strings",
        "Some", "None", "Ok", "Err",
        "gpu_thread_id",
        // `asset("path")` -- a real compiler INTRINSIC (`Driver.hotc`'s own `check_assets`/
        // `Codegen.hotc`'s own `gen_expr`, both special-casing `callee == "asset"` directly),
        // same shape as `print`/`read_line` above -- never a real `FnDecl` anywhere.
        "asset",
        // `stdlib/collections.hotc`'s own constructors (`use collections;`), `stdlib/random.hotc`'s
        // `random_new` (`use random;`), `stdlib/tuple.hotc`'s `tuple2` (`use tuple;`), `stdlib/
        // math.hotc`'s `clamp_int`/`clamp_float`/`clamp_long` (`use math;`) -- real prelude-shaped
        // stdlib functions, same "not discoverable by walking the current file's own tree" reason
        // `vec_of`/`registry_new` above already are, just from a topic that needs its own explicit
        // `use` rather than the 5-topic legacy default. Added unconditionally (not gated on the
        // file actually writing that `use` line) -- same deliberately-imprecise "avoid a false
        // positive over catching a missing `use`" leniency this whole list already takes for
        // `vec_of` etc.
        "hash_map_new", "int_hash_map_new", "hash_map2_new", "hash_set_new", "int_hash_set_new",
        "random_new", "tuple2", "clamp", "clamp_int", "clamp_float", "clamp_long",
        // `stdlib/sequence.hotc`'s own `wait`/`wait_until` (`use sequence;`, or implicitly via a
        // `sequence { ... }` block) -- real coroutine-suspend helpers, callable from a virtual
        // thread, not scoped to lexically inside the block itself.
        "wait", "wait_until",
    )

    // Every name a value expression could legitimately refer to at the TOP level: a callable
    // `fn`, a bare (non-generic) enum variant (`Some`/`None`-shaped -- a generic enum's variants
    // need the qualified `Base<Arg>::Variant` form the real grammar requires, which parses as
    // `STATIC_CALL_EXPR`-adjacent territory, not a bare `REF_EXPR`, so they're never checked
    // against this set at all), and a top-level `static` constant. Struct/interface/extern-class
    // names themselves are deliberately NOT included -- a bare struct name alone isn't a
    // meaningful value (see `identLed`'s own header: it only ever appears as the lead of a
    // `StructLit`/`StaticCall`, never bare), so a real typo there should still surface as
    // "unresolved reference" rather than being silently accepted.
    //
    // Also collects from every SIBLING `.hc`/`.hotc` file in the SAME DIRECTORY -- real, not
    // hypothetical: the real compiler's own directory-mode compilation (`hc run <project-dir>`)
    // merges every `.hotc` file found into ONE flat program with one shared top-level namespace
    // (see `Main.kt`'s own `parseEntry` doc), which is exactly how `examples/multifile/`,
    // `examples/registries/`, and `examples/battle/` are laid out -- `main.hc` calling a `fn`
    // declared in a sibling file in the same folder is real, valid, already-working code, not a
    // typo. The IDE has no way to know which directory a given file is actually MEANT to be
    // compiled as part of (that's a `hc run`/`hc build` CLI argument, not anything in the file
    // itself), so "immediate containing directory" is a deliberate, disclosed heuristic -- it
    // matches every real multi-file example in this repo, but a project layout that spreads one
    // logical multi-file program across NESTED subdirectories wouldn't be picked up by this.
    // **Widened 2026-09-25** -- several real declarations SYNTHESIZE a callable name that never
    // appears as its own `FN_DECL`/`STATIC_DECL` anywhere in source at all (the real compiler's
    // own parser-level AST synthesis does it invisibly): `unit Name(Base);` generates a
    // lowercased constructor fn (`ticks(...)` for `unit Ticks(Int);`, see `Parser.hotc`'s own
    // `unit_decl` header); `state Machine { ... }` generates `<Machine>_transition` (see
    // `Parser.hotc`'s own `state_machine_decl` header); `@startup`/`@update`/`@fixed_update`/
    // `@render` each generate ONE shared dispatcher (`run_startup`/`run_update`/
    // `run_fixed_update`/`run_render`) once ANY fn in the file carries that directive (see
    // `build_lifecycle_dispatcher_fn`'s own header); `@tunable` on a `static` generates
    // `tunable_get`/`tunable_set`/`tunable_names` (see `build_tunable_get_fn`'s own header).
    // Tracked via a single forward pass accumulating "pending" annotation names exactly the way
    // the real compiler's own parser does (`pending_startup`/`pending_tunable`/... in `Parser.
    // hotc`'s own top-level loop) -- an `ANNOTATION`/`pub`/`open`/`priv` sibling never resets the
    // accumulator, any OTHER node does, same shape `HCAnnotator.nextSignificantSibling` already
    // established for the directive-PLACEMENT check (see that fn's own header).
    private fun collectTopLevelValueNames(file: PsiFile): Set<String> {
        val names = mutableSetOf<String>()
        fun collectFrom(f: PsiFile) {
            var pending = mutableListOf<String>()
            for (decl in directChildren(f)) {
                val et = decl.node?.elementType
                if (decl is PsiWhiteSpace) continue
                if (et == HCElementTypes.ANNOTATION) {
                    directChildren(decl).getOrNull(1)?.text?.let { pending += it }
                    continue
                }
                if (et == HCTokenTypes.KEYWORD && (decl.text == "pub" || decl.text == "open" || decl.text == "priv")) continue
                when (et) {
                    HCElementTypes.FN_DECL, HCElementTypes.STATIC_DECL, HCElementTypes.CONST_FN_DECL, HCElementTypes.CONST_DECL ->
                        declaredName(decl)?.let { names += it.text }
                    HCElementTypes.ENUM_DECL -> for (variant in directChildren(decl).filter { it.node?.elementType == HCElementTypes.ENUM_VARIANT }) {
                        declaredName(variant)?.let { names += it.text }
                    }
                    HCElementTypes.UNIT_DECL -> declaredName(decl)?.let { names += it.text.lowercase() }
                    HCElementTypes.STATE_MACHINE_DECL -> declaredName(decl)?.let { names += "${it.text}_transition" }
                    HCElementTypes.EVENT_DECL -> declaredName(decl)?.let { names += "emit_${it.text}" }
                    // A top-level ITEM-macro invocation (`make_adder!(add5, 5);`) generates a
                    // real top-level fn this plugin's parser can never discover directly (no
                    // macro expansion at all -- see `HCPsiParser.macroDecl`'s own header). Real,
                    // disclosed HEURISTIC, not a precise fix: registers the invocation's own
                    // FIRST bare-identifier argument as a known value, on the assumption it names
                    // the generated fn (the real compiler's own convention every item-macro
                    // example in this repo follows -- `name` is the first declared param) --
                    // wrong for an expression/statement macro whose first arg happens to be a
                    // bare identifier too (a real, accepted false-negative-avoidance tradeoff,
                    // matching this whole file's own "avoid a false positive over precision"
                    // stance elsewhere).
                    HCElementTypes.MACRO_INVOCATION -> {
                        val argList = directChildren(decl).firstOrNull { it.node?.elementType == HCElementTypes.ARG_LIST }
                        val firstArg = argList?.let { directChildren(it).firstOrNull { c -> c.node?.elementType == HCElementTypes.REF_EXPR } }
                        firstArg?.let { declaredName(it) }?.let { names += it.text }
                    }
                    else -> {}
                }
                if (et == HCElementTypes.FN_DECL) {
                    if ("startup" in pending) names += "run_startup"
                    if ("update" in pending) names += "run_update"
                    if ("fixed_update" in pending) names += "run_fixed_update"
                    if ("render" in pending) names += "run_render"
                }
                if (et == HCElementTypes.STATIC_DECL && "tunable" in pending) {
                    names += "tunable_get"; names += "tunable_set"; names += "tunable_names"
                }
                pending = mutableListOf()
            }
        }
        collectFrom(file)
        file.containingDirectory?.files?.forEach { sibling ->
            if (sibling !== file && sibling.language == HCLanguage) collectFrom(sibling)
        }
        return names
    }

    // Every name LOCALLY bound anywhere inside one `FN_DECL` (top-level fn or impl method) --
    // flat and flow-insensitive (a name bound in one `if` branch is treated as visible in a
    // sibling branch too, and for the fn's ENTIRE body, not just after its own binding point).
    // Real, deliberate over-inclusion, not an oversight: getting true block-scoping/flow ordering
    // exactly right needs more structure than this pass tracks, and the failure mode of getting
    // it WRONG in the strict direction is a false "unresolved reference" on perfectly valid code
    // -- much worse for an IDE check than occasionally staying quiet on a real (and rare) same-
    // function shadowing/use-before-definition mistake, which the real compiler's own checker
    // will still catch at build time regardless. Binding sites covered: fn params (incl. `self`,
    // which lexes as a plain `IDENT` -- see `HCPsiParser.paramList`'s own header), `let`/`var`,
    // a `for` loop's own variable, a `try`/`catch`'s own caught-exception variable, and pattern
    // bindings from `match`/`if let` (every `IDENT` after a `VARIANT_PATTERN`'s own `{`, which
    // over-includes a `{ field: bind }` pattern's FIELD name alongside its real bind name -- safe
    // over-inclusion, same reasoning as this fn's own flow-insensitivity).
    private fun collectLocalNames(fnDecl: PsiElement): Set<String> {
        val names = mutableSetOf<String>()
        for (param in elementsOfType(fnDecl, HCElementTypes.PARAM)) {
            declaredName(param)?.let { names += it.text }
        }
        for (letStmt in elementsOfType(fnDecl, HCElementTypes.LET_STMT)) {
            declaredName(letStmt)?.let { names += it.text }
        }
        for (forStmt in elementsOfType(fnDecl, HCElementTypes.FOR_STMT)) {
            declaredName(forStmt)?.let { names += it.text }
        }
        // `parallel for c in items { ... }` -- same "first direct-child IDENT is the loop
        // variable" shape `FOR_STMT` right above already uses (`HCPsiParser.kt`'s own
        // `PARALLEL_STMT` parse fn confirms the identical child order).
        for (parallelStmt in elementsOfType(fnDecl, HCElementTypes.PARALLEL_STMT)) {
            declaredName(parallelStmt)?.let { names += it.text }
        }
        // `[result_expr for var_name in iter_expr if cond]` -- `var_name`'s own binding, same
        // "first direct-child IDENT" extraction `declaredName` already uses for `FOR_STMT`'s loop
        // variable right above (safe here for the identical reason: `result_expr`/`iter_expr`/
        // `cond` are always full expression subtrees, never a bare `IDENT` token directly under
        // `COMPREHENSION_EXPR` itself -- even a bare-name result like `[x for x in xs]`'s `x` is
        // wrapped in its own `REF_EXPR`, see `HCPsiParser.identLed`'s own header). Missing this
        // produced a real, false "unresolved reference" on every comprehension's own bound
        // variable the moment `COMPREHENSION_EXPR` itself started parsing successfully.
        for (comp in elementsOfType(fnDecl, HCElementTypes.COMPREHENSION_EXPR)) {
            declaredName(comp)?.let { names += it.text }
        }
        for (catchClause in elementsOfType(fnDecl, HCElementTypes.CATCH_CLAUSE)) {
            declaredName(catchClause)?.let { names += it.text }
        }
        for (pattern in elementsOfType(fnDecl, HCElementTypes.VARIANT_PATTERN)) {
            val kids = directChildren(pattern)
            // `{ field: bind }` (brace-delimited) or `(bind1, bind2)` (positional sugar -- see
            // `HCPsiParser.variantPattern`'s own header) -- whichever delimiter is present, every
            // `IDENT` after it is a real bind name (over-including a brace pattern's own FIELD
            // name too, same disclosed over-inclusion this fn's own header already covers).
            val openIdx = kids.indexOfFirst { it.node?.elementType == HCTokenTypes.LBRACE || it.node?.elementType == HCTokenTypes.LPAREN }
            if (openIdx < 0) continue
            for (kid in kids.drop(openIdx + 1)) {
                if (kid.node?.elementType == HCTokenTypes.IDENT) names += kid.text
            }
        }
        for (lambda in elementsOfType(fnDecl, HCElementTypes.LAMBDA_EXPR)) {
            for (kid in directChildren(lambda)) {
                if (kid.node?.elementType == HCTokenTypes.IDENT) names += kid.text
            }
        }
        return names
    }

    // A macro's own declared params (`macro make_adder(name, amount) { fn name(x: Int) -> Int {
    // return x + amount; } }`'s `name`/`amount`) are template variables, real-compiler-substituted
    // away entirely at expansion time -- this plugin never expands macros at all (see
    // `HCPsiParser.macroDecl`'s own header), so an ITEM macro's own literal, unexpanded `fn` body
    // genuinely references a name (`amount`) that isn't a param of THAT inner fn, nor any real
    // top-level/local name -- a guaranteed false "unresolved reference" without this. Walks UP
    // from a `FN_DECL` looking for an enclosing `MACRO_DECL` (only true for the item-macro shape;
    // an expression/statement macro's own body has no nested `FN_DECL` at all, so this never
    // fires for those, and their own bare macro-param references are never checked in the first
    // place either -- `checkUndefinedReferences`'s own loop only ever walks `FN_DECL`s).
    private fun enclosingMacroParamNames(fnDecl: PsiElement): Set<String> {
        var p: PsiElement? = fnDecl.parent
        while (p != null) {
            if (p.node?.elementType == HCElementTypes.MACRO_DECL) {
                return directChildren(p).filter { it.node?.elementType == HCTokenTypes.IDENT }.map { it.text }.toSet()
            }
            p = p.parent
        }
        return emptySet()
    }

    private fun checkUndefinedReferences(file: PsiFile, holder: AnnotationHolder) {
        val topLevel = collectTopLevelValueNames(file) + ALWAYS_KNOWN_VALUE_NAMES
        for (fnDecl in elementsOfType(file, HCElementTypes.FN_DECL)) {
            val known = topLevel + collectLocalNames(fnDecl) + enclosingMacroParamNames(fnDecl)
            for (ref in elementsOfType(fnDecl, HCElementTypes.REF_EXPR)) {
                val nameToken = directChildren(ref).firstOrNull { it.node?.elementType == HCTokenTypes.IDENT } ?: continue
                if (nameToken.text !in known) {
                    error(holder, nameToken, "unresolved reference '${nameToken.text}'")
                }
            }
        }
    }

    // Every real STATEMENT kind `HCPsiParser.statement`/`block` can produce as a direct child of a
    // `BLOCK` -- used both to walk a block's own statement list in order (unreachable-code,
    // shadowing) and to recognize a nested statement's OWN inner block(s) to recurse into.
    private val STATEMENT_KINDS = setOf(
        HCElementTypes.LET_STMT, HCElementTypes.IF_STMT, HCElementTypes.IF_LET_STMT, HCElementTypes.DEV_IF_STMT,
        HCElementTypes.WHILE_STMT, HCElementTypes.FOR_STMT, HCElementTypes.MATCH_STMT, HCElementTypes.RETURN_STMT,
        HCElementTypes.BREAK_STMT, HCElementTypes.CONTINUE_STMT, HCElementTypes.TRY_STMT, HCElementTypes.THROW_STMT,
        HCElementTypes.EXPR_STMT, HCElementTypes.BLOCK,
    )

    // `return`/`break`/`continue`/`throw` unconditionally end their own block right there --
    // anything textually AFTER one of these, as a direct sibling in the SAME block, can never run.
    // Deliberately shallow: an `if`/`match` where EVERY branch itself diverges is not detected as
    // diverging overall (that needs recursive branch-completeness analysis this pass doesn't do) --
    // a real, disclosed miss, not a false positive risk, since understating divergence only means
    // this check stays quiet on a rarer, more complex case rather than ever flagging live code.
    private val DIVERGING_KINDS = setOf(
        HCElementTypes.RETURN_STMT, HCElementTypes.BREAK_STMT, HCElementTypes.CONTINUE_STMT, HCElementTypes.THROW_STMT,
    )

    private fun checkUnreachableCode(file: PsiFile, holder: AnnotationHolder) {
        for (block in elementsOfType(file, HCElementTypes.BLOCK)) {
            var diverged = false
            for (stmt in directChildren(block).filter { STATEMENT_KINDS.contains(it.node?.elementType) }) {
                if (diverged) {
                    warn(holder, stmt, "unreachable code")
                    continue
                }
                if (DIVERGING_KINDS.contains(stmt.node?.elementType)) diverged = true
            }
        }
    }

    // A `let`/`var` whose bound name never appears again as a `REF_EXPR` anywhere in its own
    // function -- flat, per-NAME-TEXT counting across the whole function body (not real scope
    // tracking), the same deliberate over-inclusion `collectLocalNames` already established: a
    // second, unrelated `let x` shadowing a USED first `x` won't itself get flagged (the shared
    // name text already looks "used"), a real but safe miss, never a false positive. A name that's
    // `_` or starts with `_` is skipped outright -- the common cross-language "intentionally
    // unused" convention, and real HC code already uses it that way (e.g. this repo's own examples
    // discard a `read_string()` result via `let _dummy1 = read_string();`).
    private fun checkUnusedVariables(file: PsiFile, holder: AnnotationHolder) {
        for (fnDecl in elementsOfType(file, HCElementTypes.FN_DECL)) {
            val refCounts = elementsOfType(fnDecl, HCElementTypes.REF_EXPR)
                .mapNotNull { ref -> directChildren(ref).firstOrNull { it.node?.elementType == HCTokenTypes.IDENT }?.text }
                .groupingBy { it }.eachCount()
            for (letStmt in elementsOfType(fnDecl, HCElementTypes.LET_STMT)) {
                val nameToken = declaredName(letStmt) ?: continue
                val name = nameToken.text
                if (name.startsWith("_")) continue
                if ((refCounts[name] ?: 0) == 0) {
                    warn(holder, nameToken, "variable '$name' is never used")
                }
            }
        }
    }

    // A non-`pub` top-level function (impl/extend methods excluded entirely -- they're reached via
    // method-call syntax with real receiver-type resolution this pass doesn't attempt, and flagging
    // one wrong would be a real false positive on a live interface/override method) with no
    // `REF_EXPR` anywhere in `filesInScope` referring to its name -- covers both a direct call
    // (`foo()`, whose callee is still a real `REF_EXPR` child of the wrapping `CALL_EXPR`, not
    // replaced by it -- see `HCPsiParser.callOrPrimary`'s own header) and passing the function
    // itself as a bare value. `main` (the real entry point, never explicitly called by user code)
    // and anything preceded by `@entry(...)` (the real compiler's own alternate-entry-point
    // directive) are excluded outright; `pub` is excluded because it's the language's own "meant to
    // be used by something outside this compiled unit" signal (a real, external Forge-mod consumer,
    // for instance) that this pass has no way to see usages of at all.
    private fun precedingModifiersAndAnnotations(decl: PsiElement): List<PsiElement> {
        val result = mutableListOf<PsiElement>()
        var sib = decl.prevSibling
        while (sib != null) {
            when {
                sib is PsiWhiteSpace -> {
                    if (sib.text.count { it == '\n' } >= 2) return result
                    sib = sib.prevSibling
                }
                sib.node?.elementType == HCElementTypes.ANNOTATION -> {
                    result.add(sib)
                    sib = sib.prevSibling
                }
                sib.node?.elementType == HCTokenTypes.KEYWORD && (sib.text == "pub" || sib.text == "open") -> {
                    result.add(sib)
                    sib = sib.prevSibling
                }
                else -> sib = null
            }
        }
        return result
    }

    private fun checkUnusedFunctions(file: PsiFile, holder: AnnotationHolder) {
        val usedNames = mutableSetOf<String>()
        for (f in filesInScope(file)) {
            for (ref in elementsOfType(f, HCElementTypes.REF_EXPR)) {
                val ident = directChildren(ref).firstOrNull { it.node?.elementType == HCTokenTypes.IDENT } ?: continue
                usedNames += ident.text
            }
        }
        for (decl in directChildren(file).filter { it.node?.elementType == HCElementTypes.FN_DECL }) {
            val nameToken = declaredName(decl) ?: continue
            val name = nameToken.text
            if (name == "main") continue
            val modifiers = precedingModifiersAndAnnotations(decl)
            if (modifiers.any { it.text == "pub" }) continue
            if (modifiers.any { it.node?.elementType == HCElementTypes.ANNOTATION && directChildren(it).getOrNull(1)?.text == "entry" }) continue
            if (name in usedNames) continue
            warn(holder, nameToken, "function '$name' is never used")
        }
    }

    // Real, PER-SCOPE tracking (unlike `collectLocalNames`'s deliberately flat over-approximation --
    // shadowing is exactly the one check that NEEDS real nesting to mean anything at all: flagging
    // it flatly would make two unrelated sibling `if`/`else` branches each declaring their own
    // `let x` a false positive, which is completely ordinary, valid code). Deliberately shallow in
    // a different way instead: only `LET_STMT`/`FOR_STMT`/`CATCH_CLAUSE` binding sites are tracked
    // (match-arm/if-let pattern binds are walked into for their own nested `let`s but their OWN
    // bind names are never registered) -- under-registering only means a real shadow of a pattern
    // bind goes unflagged, a safe miss, never a false positive, and expression-position blocks
    // (`if`/`match` used as a VALUE) aren't recursed into at all for the same reason. `self` and any
    // `_`-prefixed name are never flagged, matching `checkUnusedVariables`'s own convention.
    private fun checkShadowing(file: PsiFile, holder: AnnotationHolder) {
        for (fnDecl in elementsOfType(file, HCElementTypes.FN_DECL)) {
            val paramScope = mutableMapOf<String, PsiElement>()
            val paramList = directChildren(fnDecl).firstOrNull { it.node?.elementType == HCElementTypes.PARAM_LIST }
            for (param in paramList?.let { directChildren(it) }.orEmpty().filter { it.node?.elementType == HCElementTypes.PARAM }) {
                declaredName(param)?.let { paramScope[it.text] = it }
            }
            val body = directChildren(fnDecl).firstOrNull { it.node?.elementType == HCElementTypes.BLOCK } ?: continue
            walkBlockForShadowing(body, mutableListOf(paramScope), holder)
        }
    }

    private fun bindForShadowCheck(nameToken: PsiElement?, scopes: MutableList<MutableMap<String, PsiElement>>, holder: AnnotationHolder) {
        val name = nameToken?.text ?: return
        if (name == "self" || name.startsWith("_")) return
        if (scopes.any { it.containsKey(name) }) {
            warn(holder, nameToken, "'$name' shadows a declaration from an enclosing scope")
        }
        scopes.last()[name] = nameToken
    }

    private fun childBlockOf(stmt: PsiElement): PsiElement? =
        directChildren(stmt).firstOrNull { it.node?.elementType == HCElementTypes.BLOCK }

    private fun walkBlockForShadowing(block: PsiElement, scopes: MutableList<MutableMap<String, PsiElement>>, holder: AnnotationHolder) {
        scopes.add(mutableMapOf())
        for (stmt in directChildren(block).filter { STATEMENT_KINDS.contains(it.node?.elementType) }) {
            walkStmtForShadowing(stmt, scopes, holder)
        }
        scopes.removeAt(scopes.size - 1)
    }

    private fun walkStmtForShadowing(stmt: PsiElement, scopes: MutableList<MutableMap<String, PsiElement>>, holder: AnnotationHolder) {
        when (stmt.node?.elementType) {
            HCElementTypes.LET_STMT -> bindForShadowCheck(declaredName(stmt), scopes, holder)
            HCElementTypes.IF_STMT, HCElementTypes.IF_LET_STMT, HCElementTypes.DEV_IF_STMT -> {
                for (child in directChildren(stmt)) {
                    when (child.node?.elementType) {
                        HCElementTypes.BLOCK -> walkBlockForShadowing(child, scopes, holder)
                        HCElementTypes.IF_STMT, HCElementTypes.IF_LET_STMT, HCElementTypes.DEV_IF_STMT ->
                            walkStmtForShadowing(child, scopes, holder)
                        else -> {}
                    }
                }
            }
            HCElementTypes.WHILE_STMT -> childBlockOf(stmt)?.let { walkBlockForShadowing(it, scopes, holder) }
            HCElementTypes.FOR_STMT -> {
                scopes.add(mutableMapOf())
                bindForShadowCheck(declaredName(stmt), scopes, holder)
                childBlockOf(stmt)?.let { walkBlockForShadowing(it, scopes, holder) }
                scopes.removeAt(scopes.size - 1)
            }
            HCElementTypes.MATCH_STMT -> {
                for (arm in directChildren(stmt).filter { it.node?.elementType == HCElementTypes.MATCH_ARM }) {
                    childBlockOf(arm)?.let { walkBlockForShadowing(it, scopes, holder) }
                }
            }
            HCElementTypes.TRY_STMT -> {
                childBlockOf(stmt)?.let { walkBlockForShadowing(it, scopes, holder) }
                for (catchClause in directChildren(stmt).filter { it.node?.elementType == HCElementTypes.CATCH_CLAUSE }) {
                    scopes.add(mutableMapOf())
                    bindForShadowCheck(declaredName(catchClause), scopes, holder)
                    childBlockOf(catchClause)?.let { walkBlockForShadowing(it, scopes, holder) }
                    scopes.removeAt(scopes.size - 1)
                }
            }
            HCElementTypes.BLOCK -> walkBlockForShadowing(stmt, scopes, holder)
            else -> {}
        }
    }
}
