package hc.sema

import hc.ast.*

class SemaError(message: String) : RuntimeException(message)

data class FnSig(val params: List<Param>, val paramTys: List<Ty>, val ret: Ty)

// `paramTys`/`params` exclude `self`. `hasDefault` = the interface method has a body (JVM
// default method); if not, every implementer must override it. `retType` (unresolved TypeRef,
// alongside the already-resolved `ret`) is kept around only so a `by field` delegation can
// synthesize a forwarding method's own AST without needing a Ty-to-TypeRef reverse conversion.
data class InterfaceMethodSig(val params: List<Param>, val paramTys: List<Ty>, val ret: Ty, val retType: TypeRef?, val hasDefault: Boolean, val selfIsMut: Boolean)
// `methods` is the *flattened* set (this interface's own methods plus every supertrait's,
// transitively -- own methods win on a name collision). Used for impl validation and for
// dyn/instance dispatch lookups. Codegen still needs each interface's *own* declared methods
// separately (to know what to actually emit on that interface's JVM class) -- see `program.interfaces`.
class InterfaceInfo(val name: String, val sealed: Boolean = false) {
    val methods = mutableMapOf<String, InterfaceMethodSig>()
    // Struct names implementing this interface, in `impl` declaration order -- only populated
    // (and only meaningful) for a `sealed interface`. Drives `match`-by-concrete-type over a
    // `&dyn SealedInterface` value: an instanceof chain in this order, exhaustiveness checked
    // against this exact list.
    val implementers = mutableListOf<String>()
}
private class RawInterface(val extends: List<String>, val ownMethods: Map<String, InterfaceMethodSig>)

// A `pub static NAME: Type = initExpr;` -- see StaticDecl's doc. `init` is the already
// type-checked initializer expression, evaluated once at class-load time (codegen emits it
// into its owning module's holder-class `<clinit>`).
class StaticInfo(val name: String, val ty: Ty, val init: Expr, val moduleName: String?, val visible: Boolean, val sourceUnit: String? = null)

// `mutable` governs reassigning the whole variable (`x = ...`, needs `var`).
// `canMutateFields` governs mutating through it (`x.field = ...`): true for `var` owned
// locals and `&mut`-borrowed params/self, false for `let` locals and `&`-borrowed ones.
private data class VarInfo(val ty: Ty, val mutable: Boolean, val canMutateFields: Boolean = false)

private class Env {
    private val scopes = ArrayDeque<MutableMap<String, VarInfo>>()
    fun push() = scopes.addLast(mutableMapOf())
    fun pop() = scopes.removeLast()
    fun declare(name: String, info: VarInfo) { scopes.last()[name] = info }
    fun lookup(name: String): VarInfo? {
        for (i in scopes.indices.reversed()) scopes[i][name]?.let { return it }
        return null
    }
    // How many scopes are currently pushed -- lambda-capture detection (see Checker's
    // `captureStack`) needs to tell "declared inside this lambda's own param scope" apart from
    // "declared somewhere further out," which a plain `lookup` can't distinguish.
    fun depth(): Int = scopes.size
    fun lookupWithDepth(name: String): Pair<VarInfo, Int>? {
        for (i in scopes.indices.reversed()) scopes[i][name]?.let { return it to i }
        return null
    }
}

/**
 * Type checker + a flow-sensitive move checker (structs are move-only, like Rust;
 * Int/Bool/String are Copy). Borrowing (&x) reads without moving. Branch merges are
 * conservative: a variable moved on *either* side of an if/else, or anywhere inside a
 * while body, counts as moved afterward.
 *
 * Generics are handled by monomorphization: `struct`/`fn` declarations with type params
 * are never checked or codegen'd directly. Each concrete use (a call, a struct literal)
 * infers its type arguments from the already-checked argument/field expression types,
 * and gets a specialized copy of the template substituted in with those concrete types
 * and a mangled name (e.g. `identity_Int`) generated and cached on first use. Every
 * instantiation is fully concrete afterward, so a generic `Box<Int>` gets a real `int`
 * field (zero-cost, no boxing) exactly like `Box<Player>` gets a real `Player` field.
 */
class Checker(private val program: Program, private val classpathReflector: ClasspathReflector? = null) {
    companion object {
        // Max chars `@serializable`'s synthesized `writeUtf`/`readUtf` calls pass explicitly
        // (see the `@serializable` codegen loop below) instead of relying on vanilla
        // `FriendlyByteBuf.writeUtf(String)`'s silent 1-arg default of `Short.MAX_VALUE`
        // (32767). Generous enough for real payload-carrying strings (e.g. a copied
        // structure's NBT/SNBT blob); still finite so a matching `readUtf(N)` on the receiving
        // end can't be tricked into an unbounded allocation by a corrupt/hostile length prefix.
        const val SERIALIZABLE_STRING_MAX_LEN = 2_097_151
    }
    val structs = mutableMapOf<String, StructInfo>()
    val fns = mutableMapOf<String, FnSig>()
    val arenaLayouts = mutableMapOf<String, ArenaLayout>()
    val enums = mutableMapOf<String, EnumInfo>()
    private val enumVariantOwner = mutableMapOf<String, String>() // variant name -> owning enum/template name (variant names are global, like top-level fns)
    private val genericEnumTemplates = mutableMapOf<String, EnumDecl>()
    private val enumInstanceTemplate = mutableMapOf<String, String>() // mangled enum name -> owning template name
    private val enumInstanceArgs = mutableMapOf<String, List<Ty>>() // mangled enum name -> its concrete type args
    val externClasses = mutableMapOf<String, ExternClassInfo>() // declared alias name -> its trusted shape
    val interfaces = mutableMapOf<String, InterfaceInfo>()
    val structInterfaces = mutableMapOf<String, MutableSet<String>>() // struct name -> interfaces (+ transitive supertraits) it implements
    val interfaceImplFns = mutableMapOf<String, MutableList<FnDecl>>() // struct name -> instance methods to compile onto its own class
    val structSuperclass = mutableMapOf<String, String>() // struct name -> extern class alias it `extends` (real JVM superclass, not just `implements`)
    val superclassOverrideFns = mutableMapOf<String, MutableList<FnDecl>>() // struct name -> `override fn` instance methods matching its superclass's method table
    private val rawInterfaces = mutableMapOf<String, RawInterface>()
    private val genericInterfaceImpls = mutableMapOf<String, MutableList<ImplBlock>>() // struct *template* name -> deferred `impl Interface for Generic<T>` blocks
    private val genericStructTemplates = mutableMapOf<String, StructDecl>()
    private val genericFnTemplates = mutableMapOf<String, FnDecl>()
    private val structInstances = mutableMapOf<String, StructDecl>()
    private val fnInstances = mutableMapOf<String, FnDecl>()
    private val structInstanceArgs = mutableMapOf<String, List<Ty>>() // mangled struct name -> its concrete type args
    private val structInstanceTemplate = mutableMapOf<String, String>() // mangled struct name -> owning template name
    private val implFns = mutableListOf<FnDecl>() // methods, desugared to top-level fns named "Owner$method"
    private val extensionFns = mutableMapOf<Pair<String, String>, String>() // (targetName, methodName) -> mangled fn name, see the `extend` processing loop
    val statics = mutableMapOf<String, StaticInfo>()
    // Set by the `@entry("forge.mod", ...)` handling below when present -- CodeGen reads these
    // three directly (no AST mutation) to add the `@Mod("...")` annotation and the extra
    // constructor call onto exactly one struct's generated class. Null/null/null when no
    // `@entry` fn exists in this compile (the overwhelmingly common case).
    var entryClassName: String? = null
    var entryModid: String? = null
    var entryInitFn: String? = null
    private val errors = mutableListOf<String>()
    // One entry per lambda literal currently being checked (a stack since lambdas could in
    // principle nest, though checkLambda rejects that for v1 -- see its own doc): `first` is
    // the Env depth right after that lambda's own param scope was pushed (see Env.depth), so
    // any Ident resolved at a shallower depth is a genuine capture, not one of the lambda's own
    // params; `second` accumulates each captured name's type, in first-use order (a
    // LinkedHashMap so codegen's field/constructor-param order is deterministic and matches
    // what the capturing use site loads in).
    private val captureStack = ArrayDeque<Pair<Int, LinkedHashMap<String, Ty>>>()
    private var lambdaCounter = 0
    // Mirrors the Env scope stack while checking a function body: one frame per open block,
    // holding (in declaration order) the names of that block's own `let`/`var` locals whose
    // type has a `drop` method. Used to compute Block.dropsAtEnd and Stmt.Return's
    // cross-scope drop list. Concrete structs only -- see hasDrop().
    private val dropScopeStack = ArrayDeque<MutableList<String>>()

    private fun hasDrop(structName: String): Boolean = fns.containsKey("$structName\$drop")

    /** The fully concrete program (originals minus generic/arena templates, plus every instantiation). */
    fun resolvedProgram(): Program = Program(
        structs = program.structs.filter { it.typeParams.isEmpty() && !it.isArena } + structInstances.values,
        fns = program.fns.filter { it.typeParams.isEmpty() } +
                implFns.filter { it.typeParams.isEmpty() } +
                fnInstances.values,
    )

    fun check() {
        // Extern class *names* must be registered before anything else calls `resolveType` --
        // struct fields, interface method signatures (including `extern interface`'s own, which
        // may reference an `extern class` type, e.g. `fn get(&self) -> JObject;`), enum variant
        // fields, all resolve types before the extern-registration block below used to run. Only
        // the name needs to exist this early (an empty method table is fine here -- real
        // signatures are resolved further down, after interfaces, in the same place this used to
        // live); `resolveType` only needs to know a `JavaExtern` name is valid at all.
        for (ext in program.externs) {
            if (externClasses.containsKey(ext.name)) { errors += "Duplicate extern class '${ext.name}'"; continue }
            externClasses[ext.name] = ExternClassInfo(ext.name, ext.binaryName, emptyList(), lazy = ext.lazyAll, isInterface = ext.isInterface)
        }
        for (s in program.structs) {
            if (s.typeParams.isNotEmpty()) {
                if (genericStructTemplates.containsKey(s.name)) errors += "Duplicate struct '${s.name}'"
                genericStructTemplates[s.name] = s
            }
        }
        for (s in program.structs) {
            for (f in s.fields) {
                if (f.type.isRef) errors += "struct '${s.name}' field '${f.name}': fields can't be references (no lifetime tracking)"
            }
        }
        for (s in program.structs.filter { it.isArena }) {
            if (structs.containsKey(s.name) || genericStructTemplates.containsKey(s.name) || arenaLayouts.containsKey(s.name)) {
                errors += "Duplicate struct '${s.name}'"; continue
            }
            val fields = s.fields.map { f ->
                val ty = resolveType(f.type)
                if (ty != Ty.Int_ && ty != Ty.Long_ && ty != Ty.Bool_) {
                    errors += "arena struct '${s.name}' field '${f.name}': only Int/Long/Bool fields are supported off-heap, got ${ty}"
                }
                f.name to ty
            }
            arenaLayouts[s.name] = ArenaLayout(s.name, fields)
        }
        for (s in program.structs.filter { it.typeParams.isEmpty() && !it.isArena }) {
            if (structs.containsKey(s.name) || genericStructTemplates.containsKey(s.name) || arenaLayouts.containsKey(s.name)) {
                errors += "Duplicate struct '${s.name}'"; continue
            }
            structs[s.name] = StructInfo(s.name, s.fields.map { it.name to resolveType(it.type) })
        }

        for (e in program.enums) {
            if (enums.containsKey(e.name) || genericEnumTemplates.containsKey(e.name)) { errors += "Duplicate enum '${e.name}'"; continue }
            for (v in e.variants) {
                if (structs.containsKey(v.name) || genericStructTemplates.containsKey(v.name) || enumVariantOwner.containsKey(v.name)) {
                    errors += "Duplicate variant/struct name '${v.name}' (enum variant names share a namespace with structs and must be globally unique)"
                    continue
                }
                for (f in v.fields) {
                    if (f.type.isRef) errors += "enum '${e.name}' variant '${v.name}' field '${f.name}': fields can't be references"
                }
                enumVariantOwner[v.name] = e.name
            }
            if (e.typeParams.isNotEmpty()) {
                genericEnumTemplates[e.name] = e
                continue
            }
            val variants = e.variants.mapIndexed { tag, v -> EnumVariantInfo(v.name, tag, v.fields.map { it.name to resolveType(it.type) }) }
            enums[e.name] = EnumInfo(e.name, variants, e.moduleName, e.visible)
        }

        // Pass 1: each interface's *own* declared methods (`interface Sub: Super` doesn't
        // affect this -- just records the supertrait list for pass 2 to resolve).
        for (i in program.interfaces) {
            if (rawInterfaces.containsKey(i.name)) { errors += "Duplicate interface '${i.name}'"; continue }
            val own = mutableMapOf<String, InterfaceMethodSig>()
            for (m in i.methods) {
                val selfParam = m.params.getOrNull(0)
                if (selfParam?.name != "self" || !selfParam.type.isRef) {
                    errors += "Line ${m.line}: interface method '${m.name}' must take &self or &mut self"
                }
                if (own.containsKey(m.name)) {
                    errors += "Line ${m.line}: duplicate method '${m.name}' in interface '${i.name}'"; continue
                }
                val restParams = m.params.drop(1)
                own[m.name] = InterfaceMethodSig(
                    params = restParams,
                    paramTys = restParams.map { resolveType(it.type) },
                    ret = m.retType?.let { resolveType(it) } ?: Ty.Unit_,
                    retType = m.retType,
                    hasDefault = m.body != null,
                    selfIsMut = selfParam?.type?.isMut ?: false,
                )
            }
            rawInterfaces[i.name] = RawInterface(i.extends, own)
        }
        // Pass 2: flatten each interface's method set to include every supertrait's,
        // transitively (own methods win on a name collision with an inherited one).
        for (i in program.interfaces) {
            if (!rawInterfaces.containsKey(i.name)) continue // duplicate, already reported
            val info = InterfaceInfo(i.name, i.sealed)
            info.methods.putAll(flattenInterfaceMethods(i.name, mutableSetOf()))
            interfaces[i.name] = info
        }

        // Extern class method/field resolution -- names are already registered (see the top of
        // `check()`); this fills in the real signatures, from the hand-written signature list
        // (explicit form), from reflection against `--classpath` (`use { ... }`, eager), or left
        // empty pending on-demand reflection later (bare/lazy form) -- see ExternClassDecl.
        for (ext in program.externs) {
            if (ext.lazyAll) continue // nothing to resolve up front -- resolveExternMethods handles it per call site
            val methods = if (ext.useNames != null) {
                resolveUseNames(ext.name, ext.binaryName, ext.useNames)
            } else {
                ext.methods.map { m ->
                    val selfParam = m.params.firstOrNull()
                    val isInstance = selfParam?.name == "self"
                    val isCtor = !isInstance && m.name == "new"
                    val restParams = if (isInstance) m.params.drop(1) else m.params
                    ExternMethodInfo(
                        name = m.name,
                        params = restParams.map { resolveType(it.type) },
                        // A ctor's declared return type is conventionally written `Self` -- not a
                        // real resolvable type name, so it's ignored here; checkStaticCall always
                        // uses the extern class's own Ty.JavaExtern for a ctor's result type instead.
                        retType = if (isCtor) Ty.Unit_ else m.retType?.let { resolveType(it) } ?: Ty.Unit_,
                        isStatic = m.isStatic || (!isInstance && m.name != "new"),
                        isCtor = isCtor,
                        paramIsRef = restParams.map { it.type.isRef },
                        paramIsMut = restParams.map { it.type.isMut },
                    )
                }
            }
            val fields = ext.fields.map { f -> ExternFieldInfo(f.name, resolveType(f.type), f.isStatic) }
            // Replace the empty method list with the resolved one.
            externClasses[ext.name] = ExternClassInfo(ext.name, ext.binaryName, methods, fields = fields, isInterface = ext.isInterface)
        }

        // `@serializable struct S { ... }` -- synthesizes `pub fn encode(packet: &S, buf: &mut
        // FriendlyByteBuf)` / `pub fn decode(buf: &mut FriendlyByteBuf) -> S` as real AST, fed
        // through the same `implFns` pipeline as every other synthesized fn (see the `by field`
        // delegation block above for the same pattern). Deliberately scoped to exactly one
        // target extern class (an alias literally named "FriendlyByteBuf", matching this
        // project's Forge networking use case) and to Int/Long/Float/Double/Bool/String fields
        // only -- each maps to a real FriendlyByteBuf.writeX/readX call, in field-declaration
        // order, so encode/decode round-trip each other by construction. Whether the user's own
        // `extern class FriendlyByteBuf = ...` declaration actually has the needed methods is
        // left to the normal checker pass over these synthesized bodies to catch (same "no
        // special-cased validation duplicated here" reasoning as `by field` delegation).
        //
        // String fields always emit the explicit-max-length overload (`writeUtf(s, N)` /
        // `readUtf(N)`), never the bare 1-arg `writeUtf(s)` -- that overload silently defaults
        // to `Short.MAX_VALUE` (32767 chars) in vanilla `FriendlyByteBuf`, which throws mid-write
        // on anything holding real payload data (a copied structure's NBT/SNBT blob, etc.) and,
        // because that throw happens after the packet's length/id header is already on the wire,
        // desyncs every packet after it on the same connection rather than failing cleanly. The
        // bound below (`SERIALIZABLE_STRING_MAX_LEN`) is deliberately generous but still finite
        // -- unlike passing `Int.MAX_VALUE`, a hostile/corrupt incoming packet can't use the
        // matching `readUtf(N)` to claim an unbounded string length and force a huge allocation.
        // The `extern class FriendlyByteBuf` declaration this compile provides needs a matching
        // 2-arg `writeUtf`/`readUtf` overload for this to link -- `pickCandidate` (see
        // `checkMethodCall`'s `Ty.JavaExtern` branch) resolves extern overloads by arg count, so
        // adding a 2-arg pair alongside an existing 1-arg one is additive, not a breaking change
        // for any other caller of the 1-arg form.
        for (s in program.structs.filter { it.serializable && it.typeParams.isEmpty() }) {
            if (!externClasses.containsKey("FriendlyByteBuf")) {
                errors += "struct '${s.name}': '@serializable' needs an 'extern class FriendlyByteBuf = ...' declared in this compile"
                continue
            }
            val fields = structs[s.name]?.fields ?: continue
            val ops = fields.map { (fname, fty) ->
                val pair = when (fty) {
                    Ty.Int_ -> "writeInt" to "readInt"
                    Ty.Long_ -> "writeLong" to "readLong"
                    Ty.Float_ -> "writeFloat" to "readFloat"
                    Ty.Double_ -> "writeDouble" to "readDouble"
                    Ty.Bool_ -> "writeBoolean" to "readBoolean"
                    is Ty.Str_ -> if (!fty.nullable) "writeUtf" to "readUtf" else null
                    else -> null
                }
                if (pair == null) {
                    errors += "struct '${s.name}': '@serializable' field '$fname' has unsupported type $fty (only Int/Long/Float/Double/Bool/String are supported)"
                }
                // Only String fields get the extra max-length arg -- writeInt/writeLong/etc.
                // take no such param, and passing one would just be a bogus extra arg.
                val extraArg: Expr? = if (fty is Ty.Str_) Expr.IntLit(SERIALIZABLE_STRING_MAX_LEN) else null
                Triple(fname, pair, extraArg)
            }
            if (ops.any { it.second == null }) continue
            val encodeParams = listOf(
                Param("packet", TypeRef(s.name, isRef = true)),
                Param("buf", TypeRef("FriendlyByteBuf", isRef = true, isMut = true)),
            )
            val encodeBody = Block(ops.map { (fname, methods, extraArg) ->
                val args = listOfNotNull(Expr.FieldAccess(Expr.Ident("packet", 0), fname, 0), extraArg)
                Stmt.ExprStmt(Expr.MethodCall(Expr.Ident("buf", 0), methods!!.first, args, 0))
            })
            val encodeFn = FnDecl(
                name = "encode", params = encodeParams, retType = null, body = encodeBody, line = 0,
                moduleName = s.moduleName, visible = true, sourceUnit = s.sourceUnit,
            )
            val decodeParams = listOf(Param("buf", TypeRef("FriendlyByteBuf", isRef = true, isMut = true)))
            val decodeBody = Block(listOf(
                Stmt.Return(
                    Expr.StructLit(s.name, ops.map { (fname, methods, extraArg) ->
                        fname to Expr.MethodCall(Expr.Ident("buf", 0), methods!!.second, listOfNotNull(extraArg), 0)
                    }, 0),
                    0,
                )
            ))
            val decodeFn = FnDecl(
                name = "decode", params = decodeParams, retType = TypeRef(s.name, isRef = false), body = decodeBody, line = 0,
                moduleName = s.moduleName, visible = true, sourceUnit = s.sourceUnit,
            )
            implFns += encodeFn
            implFns += decodeFn
        }

        // `@entry("forge.mod", modid: "...")` -- see EntryDirective's doc. Scoped to exactly one
        // target ("forge.mod") and at most one `@entry` fn per compile for this first pass.
        // Validates the fn's own shape and finds the struct CodeGen should attach the generated
        // `@Mod`-annotated class + constructor call to -- the *first* struct declared in the same
        // file (module + sourceUnit) as the `@entry` fn, the same "named holder" convention every
        // other per-file feature here already uses (see StructDecl's `sourceUnit` doc). Nothing
        // here mutates `program.structs` -- `entryClassName`/`entryModid`/`entryInitFn` are read
        // directly by CodeGen's `genStruct` instead, the same "no AST mutation, a side table
        // CodeGen consults" shape `structSuperclass` etc. already use.
        val entryFns = program.fns.filter { it.entry != null }
        if (entryFns.size > 1) {
            errors += "only one '@entry(...)' fn is allowed per compile, found ${entryFns.size}: ${entryFns.joinToString(", ") { it.name }}"
        }
        for (fn in entryFns) {
            val directive = fn.entry!!
            if (directive.target != "forge.mod") {
                errors += "Line ${directive.line}: unknown '@entry' target '${directive.target}' (only \"forge.mod\" is supported)"
                continue
            }
            if (fn.params.isNotEmpty()) {
                errors += "Line ${directive.line}: '@entry(\"forge.mod\", ...)' fn '${fn.name}' must take zero parameters"
            }
            if (fn.retType != null) {
                errors += "Line ${directive.line}: '@entry(\"forge.mod\", ...)' fn '${fn.name}' must return nothing"
            }
            val modid = (directive.args.firstOrNull { it.first == "modid" }?.second as? AnnotationValue.Str)?.value
            if (modid == null) {
                errors += "Line ${directive.line}: '@entry(\"forge.mod\", ...)' needs a 'modid: \"...\"' argument"
                continue
            }
            val targetStruct = program.structs.firstOrNull { it.moduleName == fn.moduleName && it.sourceUnit == fn.sourceUnit }
            if (targetStruct == null) {
                errors += "Line ${directive.line}: '@entry(\"forge.mod\", ...)' needs a struct declared in the same file to attach the generated '@Mod' class to (e.g. 'pub struct YourModName {}')"
                continue
            }
            // Forge instantiates the `@Mod` class via a genuine no-arg constructor -- the target
            // struct needs the same "plain zero-field, no `extends`" shape `genStruct` already
            // requires to emit that exact constructor shape (a superclass forwards a *different*
            // ctor signature; any field would need a *non*-zero-arg one).
            if (targetStruct.fields.isNotEmpty() || targetStruct.superclass != null) {
                errors += "Line ${directive.line}: '@entry(\"forge.mod\", ...)' target struct '${targetStruct.name}' must have no fields and no 'extends' -- Forge needs a real no-arg constructor to instantiate it"
                continue
            }
            entryClassName = targetStruct.name
            entryModid = modid
            entryInitFn = fn.name
        }

        // `struct S extends C { }` -- real JVM `extends` (S's generated class has C's binary
        // name as its `superName`), not just interface conformance. Must reference an
        // already-declared `extern class`. Scoped to zero-field structs for now: the generated
        // constructor just forwards every arg straight to C's own declared `new(...)` (see
        // genStruct), and there's nowhere for extra per-instance state to live in that scheme.
        for (s in program.structs.filter { it.typeParams.isEmpty() && !it.isArena && it.superclass != null }) {
            val superAlias = s.superclass!!
            val ext = externClasses[superAlias]
            if (ext == null) {
                errors += "struct '${s.name}' extends '${superAlias}': unknown extern class '${superAlias}'"
                continue
            }
            if (s.fields.isNotEmpty()) {
                errors += "struct '${s.name}' extends '${superAlias}': extending an extern class doesn't support additional fields yet -- '${s.name}' must have none"
                continue
            }
            structSuperclass[s.name] = superAlias
        }

        // `impl Interface for Struct { }` (concrete structs only here -- a generic struct's
        // interface impls are deferred to instantiation time, see getOrInstantiateStruct).
        for (impl in program.impls.filter { it.interfaceName != null }) {
            val template = genericStructTemplates[impl.structName]
            if (template != null) {
                if (impl.typeParams.size != template.typeParams.size) {
                    errors += "'impl${if (impl.typeParams.isEmpty()) "" else "<...>"} ${impl.interfaceName} for ${impl.structName}': " +
                            "expected ${template.typeParams.size} type param(s) to match '${impl.structName}', got ${impl.typeParams.size}"
                }
                genericInterfaceImpls.getOrPut(impl.structName) { mutableListOf() } += impl
                continue
            }
            if (impl.typeParams.isNotEmpty()) {
                errors += "'impl<...> ${impl.interfaceName} for ${impl.structName}': '${impl.structName}' isn't a generic struct"
                continue
            }
            if (!structs.containsKey(impl.structName)) {
                errors += "'impl ${impl.interfaceName} for ${impl.structName}': unknown struct '${impl.structName}'"
                continue
            }
            processInterfaceImpl(impl, impl.structName, emptyMap())
        }
        for (structName in program.impls.filter { it.interfaceName != null }.map { it.structName }.distinct()) {
            if (structs.containsKey(structName)) checkInterfaceCompleteness(structName)
        }

        // Desugar `impl StructName { fn method(self, ...) { ... } }` into a top-level fn
        // "StructName$method" with `self`/`&self` rewritten to the concrete owner type
        // (or, for a generic impl, `Owner<T>` using the impl's own type params).
        for (impl in program.impls.filter { it.interfaceName == null }) {
            val ownerType = TypeRef(impl.structName, isRef = false, typeArgs = impl.typeParams.map { TypeRef(it, false) })
            fun fixSelf(t: TypeRef): TypeRef = if (t.name == "Self") ownerType.copy(isRef = t.isRef, isMut = t.isMut) else t
            // Struct methods aren't independently `pub`-marked -- they follow the owning
            // struct's own visibility, so they're always compiled as public methods (on
            // whichever per-module holder class they end up on); a private *struct* still
            // blocks outside access at the class level regardless.
            val structModule = program.structs.firstOrNull { it.name == impl.structName }?.moduleName
                ?: genericStructTemplates[impl.structName]?.moduleName
            for (m in impl.methods) {
                if (m.isOverride) {
                    checkSuperclassOverride(impl.structName, m, ::fixSelf)
                    continue
                }
                if (m.name == "drop") {
                    val selfParam = m.params.getOrNull(0)
                    val validSelf = selfParam?.name == "self" && selfParam.type.isRef && selfParam.type.isMut
                    if (m.params.size != 1 || !validSelf || m.retType != null) {
                        errors += "Line ${m.line}: 'drop' must be declared exactly as `fn drop(&mut self)`"
                    }
                }
                val mangledName = if (m.params.firstOrNull()?.name == "self") "${impl.structName}\$${m.name}" else "${impl.structName}@${m.name}"
                val newParams = m.params.map { Param(it.name, fixSelf(it.type)) }
                val newRet = m.retType?.let { fixSelf(it) }
                implFns += FnDecl(mangledName, newParams, newRet, m.body, m.line, impl.typeParams, moduleName = structModule, visible = true)
            }
        }

        // `extend Target { fn newMethod(&self, ...) -> T { body } }` -- desugars into an
        // ordinary top-level fn (mangled `$ext$Target$method`, self rewritten from `Self` to
        // the target type, same as an inherent impl above), fed into the same `implFns` list
        // so the registration/body-check passes just below pick it up like anything else --
        // the only genuinely new bit is `extensionFns`, consulted by checkMethodCall's
        // last-resort fallback once every real resolution path has already failed.
        for (ext in program.extends) {
            val isStruct = structs.containsKey(ext.targetName) || genericStructTemplates.containsKey(ext.targetName)
            val iface = interfaces[ext.targetName]
            if (!isStruct && iface == null) {
                errors += "Line ${ext.line}: 'extend ${ext.targetName}': unknown struct or interface '${ext.targetName}'"
                continue
            }
            if (!isStruct) {
                val declaredOpen = program.interfaces.firstOrNull { it.name == ext.targetName }?.open == true
                if (!declaredOpen) {
                    errors += "Line ${ext.line}: 'extend ${ext.targetName}': '${ext.targetName}' isn't 'open' -- only an 'open interface' can receive extension methods from elsewhere"
                    continue
                }
            }
            val targetSelfType = if (isStruct) TypeRef(ext.targetName, isRef = true) else TypeRef(ext.targetName, isRef = true, isDyn = true)
            fun fixSelf(t: TypeRef): TypeRef = if (t.name == "Self") targetSelfType.copy(isRef = t.isRef, isMut = t.isMut) else t
            for (m in ext.methods) {
                val selfParam = m.params.getOrNull(0)
                if (selfParam?.name != "self") {
                    errors += "Line ${m.line}: 'extend' method '${m.name}' must take 'self', '&self', or '&mut self'"
                    continue
                }
                if (!isStruct && !selfParam.type.isRef) {
                    errors += "Line ${m.line}: 'extend ${ext.targetName}' method '${m.name}': dyn values are always used by reference -- use '&self' or '&mut self'"
                    continue
                }
                val alreadyReal = iface?.methods?.containsKey(m.name) == true ||
                        (isStruct && (fns.containsKey("${ext.targetName}\$${m.name}") || genericFnTemplates.containsKey("${ext.targetName}\$${m.name}")))
                if (alreadyReal) {
                    errors += "Line ${m.line}: 'extend ${ext.targetName}': '${m.name}' already exists as a real method -- extensions can add new methods, not override"
                    continue
                }
                val key = ext.targetName to m.name
                if (extensionFns.containsKey(key)) {
                    errors += "Line ${m.line}: '${ext.targetName}' already has an extension method '${m.name}' -- ambiguous (two 'extend' blocks target the same method name)"
                    continue
                }
                val mangled = "\$ext\$${ext.targetName}\$${m.name}"
                val newParams = m.params.map { Param(it.name, fixSelf(it.type)) }
                val newRet = m.retType?.let { fixSelf(it) }
                extensionFns[key] = mangled
                implFns += FnDecl(mangled, newParams, newRet, m.body, m.line, moduleName = ext.moduleName, visible = true)
            }
        }

        for (f in program.fns + implFns) {
            if (f.typeParams.isNotEmpty()) {
                if (genericFnTemplates.containsKey(f.name)) errors += "Duplicate function '${f.name}'"
                genericFnTemplates[f.name] = f
                continue
            }
            if (fns.containsKey(f.name) || genericFnTemplates.containsKey(f.name)) {
                errors += "Duplicate function '${f.name}'"; continue
            }
            val paramTys = f.params.map { resolveType(it.type) }
            val ret = f.retType?.let { resolveType(it) } ?: Ty.Unit_
            fns[f.name] = FnSig(f.params, paramTys, ret)
        }
        if (fns.containsKey("main")) {
            val m = fns.getValue("main")
            if (m.params.isNotEmpty()) errors += "fn main must take no parameters"
        }

        // `pub static NAME: Type = initExpr;` -- checked with every *already-registered* static
        // in scope (same mechanism checkFn uses to inject the full `statics` map, just seeded
        // incrementally here since `statics` is still being built) but no locals otherwise.
        // Backward references only, by construction: a static's init runs this loop body before
        // any later static gets added to `statics` at all, so forward references are simply
        // unresolvable, not specially rejected -- no cross-static circularity is possible.
        // Sound at codegen time too: same-module statics initialize in this same declared order
        // within one shared `<clinit>` (see CodeGen.genHolderClass/genStruct), and a read of a
        // different module's static triggers that module's own class-init first, ordinary JVM
        // `<clinit>`-on-first-use semantics. Registered after fn signatures (an initializer may
        // call an ordinary fn) but before fn bodies are checked, since every fn body needs every
        // static already in `statics` to inject them into its own scope.
        for (s in program.statics) {
            if (statics.containsKey(s.name)) { errors += "Line ${s.line}: duplicate static '${s.name}'"; continue }
            val ty = resolveType(s.type)
            val emptyEnv = Env()
            emptyEnv.push()
            for ((name, info) in statics) {
                emptyEnv.declare(name, VarInfo(info.ty, mutable = true, canMutateFields = true))
            }
            val (initTy, _) = checkExpr(s.init, emptyEnv, emptyMap(), consume = true, expectedTy = ty)
            if (!tyCompatible(initTy, ty)) {
                errors += "Line ${s.line}: cannot assign ${initTy} to static '${s.name}' of declared type ${ty}"
            }
            statics[s.name] = StaticInfo(s.name, ty, s.init, s.moduleName, s.visible, s.sourceUnit)
        }

        for (f in (program.fns + implFns).filter { it.typeParams.isEmpty() }) checkFn(f)

        // See checkGenericFnBoundValidity's own doc comment: this is the missing half of what a
        // declared `<T: Trait>` bound is supposed to guarantee, checked once per template here,
        // independent of whether/how the generic ever actually gets instantiated for real.
        for (f in genericFnTemplates.values) checkGenericFnBoundValidity(f)

        // Type-check interface default method bodies. `self` is typed as `&dyn Interface`
        // (not the concrete struct) -- this is what correctly restricts a default body to
        // only calling other interface methods through self, never touching concrete struct
        // fields, entirely for free by reusing the existing Ty.Dyn machinery (FieldAccess on
        // a non-Ty.Struct receiver is already a checker error).
        for (i in program.interfaces) {
            val iface = interfaces[i.name] ?: continue
            for (m in i.methods) {
                if (m.body == null) continue
                val msig = iface.methods[m.name] ?: continue
                val selfType = TypeRef(i.name, isRef = true, isMut = msig.selfIsMut, isDyn = true)
                val checkKey = "${i.name}.${m.name}"
                val newParams = listOf(Param("self", selfType)) + msig.params
                fns[checkKey] = FnSig(newParams, listOf(Ty.Dyn(i.name)) + msig.paramTys, msig.ret)
                checkFn(FnDecl(checkKey, newParams, m.retType, m.body, m.line))
            }
        }

        // Type-check each struct's interface-method overrides, self typed as the concrete
        // struct (unlike the interface's own default bodies above). Uses a throwaway renamed
        // copy purely so checkFn's `fns.getValue` lookup has a collision-free key across
        // different structs implementing a same-named interface method -- the body Block is
        // the same object either way, so annotations land on what codegen actually reads.
        // (Signatures were already registered in `fns` back in processInterfaceImpl -- needed
        // there so call sites checked earlier in the same pass, e.g. inside `fn main()`, can
        // already resolve them; this loop only needs to check the bodies.)
        for ((structName, methods) in interfaceImplFns) {
            for (m in methods) {
                checkFn(m.copy(name = "$structName@${m.name}"))
            }
        }
        // Same idea, for `struct S extends C { }` + `impl S { override fn m(...) { ... } }` --
        // signatures were already registered in `checkSuperclassOverride`, this just checks
        // each override's body, with `self` typed as the concrete struct `S` (set up by
        // checkSuperclassOverride's `fixSelf`, same as every other inherent `impl S` method).
        for ((structName, methods) in superclassOverrideFns) {
            for (m in methods) {
                checkFn(m.copy(name = "$structName@${m.name}"))
            }
        }
        if (errors.isNotEmpty()) throw SemaError(errors.joinToString("\n"))
    }

    // Eager (`use { ... }`, option 2) resolution: reflect every requested name up front, at
    // registration time, so a typo'd or nonexistent member is a compile error right away
    // instead of surfacing later at whatever call site happens to use it.
    private fun resolveUseNames(aliasName: String, binaryName: String, useNames: List<String>): List<ExternMethodInfo> {
        val reflector = classpathReflector
        if (reflector == null) {
            errors += "extern class '$aliasName' uses 'use { ... }', which needs a classpath -- pass --classpath (or set HC_CLASSPATH)"
            return emptyList()
        }
        if (!reflector.classExists(binaryName)) {
            errors += "extern class '$aliasName': could not load '${binaryName.replace('/', '.')}' from the configured classpath"
            return emptyList()
        }
        val out = mutableListOf<ExternMethodInfo>()
        for (memberName in useNames) {
            val found = reflector.resolveMembers(binaryName, memberName)
            if (found.isEmpty()) {
                val near = reflector.publicMemberNames(binaryName).filter { it.contains(memberName, ignoreCase = true) }
                val hint = if (near.isNotEmpty()) " (did you mean: ${near.take(5).joinToString(", ")}?)" else ""
                errors += "extern class '$aliasName': no public member named '$memberName' on '${binaryName.replace('/', '.')}'$hint"
                continue
            }
            out += found
        }
        return out
    }

    // Lazy (bare `extern class X = "...";`, option 3) resolution: called from checkMethodCall /
    // checkStaticCall whenever `ext.lazy` and this exact name hasn't been looked up yet.
    // Reflects once, caches the result back into `externClasses` under the same alias (so a
    // second call to the same member reuses it instead of reflecting again), and reports a
    // normal compile error if reflection can't find anything -- same failure mode as the eager
    // form, just deferred to first use instead of caught for every declared member at once.
    private fun resolveExternMethods(ext: ExternClassInfo, memberName: String, line: Int): List<ExternMethodInfo> {
        val existing = ext.method(memberName)
        if (existing.isNotEmpty() || !ext.lazy) return existing
        val reflector = classpathReflector
        if (reflector == null) {
            errors += "Line $line: extern class '${ext.name}' resolves members lazily, which needs a classpath -- pass --classpath (or set HC_CLASSPATH)"
            return emptyList()
        }
        val found = reflector.resolveMembers(ext.binaryName, memberName)
        if (found.isEmpty()) {
            errors += "Line $line: '${ext.name}' has no public member '$memberName' on '${ext.binaryName.replace('/', '.')}'"
            return emptyList()
        }
        val aliasKey = externClasses.entries.first { it.value === ext }.key
        externClasses[aliasKey] = ExternClassInfo(ext.name, ext.binaryName, ext.methods + found, lazy = true, isInterface = ext.isInterface)
        return found
    }

    // Picks which overload of a name-matched candidate list a call site meant, by argument
    // count -- the same rule hand-written extern blocks always implicitly relied on (one
    // signature per name, so "the first/only candidate" was always the right one), made
    // explicit now that reflection can hand back several real overloads for one name (e.g.
    // `String.valueOf` has ~9). Not full Java overload resolution (no argument-type scoring) --
    // an ambiguous call (two overloads, same arg count) still just takes the first found; a
    // call site that needs a specific one of those can drop to an explicit hand-written
    // signature for that member instead (see ExternClassDecl).
    private fun pickCandidate(candidates: List<ExternMethodInfo>, argCount: Int): ExternMethodInfo =
        candidates.firstOrNull { it.params.size == argCount } ?: candidates[0]

    private fun resolveType(t: TypeRef): Ty {
        if (t.isNullable && t.name != "String" && !externClasses.containsKey(t.name)) {
            errors += "'${t.name}?': nullable types are only supported for 'extern class' types and 'String'"
        }
        if (t.isDyn) {
            if (!t.isRef) errors += "'dyn ${t.name}' must be used behind & or &mut, e.g. &dyn ${t.name}"
            if (!interfaces.containsKey(t.name)) { errors += "Unknown interface '${t.name}'"; return Ty.Unit_ }
            return Ty.Dyn(t.name)
        }
        return when (t.name) {
            "Int" -> Ty.Int_
            "Long" -> Ty.Long_
            "Float" -> Ty.Float_
            "Double" -> Ty.Double_
            "Bool" -> Ty.Bool_
            "String" -> Ty.Str_(nullable = t.isNullable)
            "Unit" -> Ty.Unit_
            "Array" -> Ty.Array(resolveType(t.typeArgs.getOrNull(0) ?: TypeRef("Unit", false)))
            "Arena" -> {
                val elemName = t.typeArgs.getOrNull(0)?.name
                if (elemName != null && arenaLayouts.containsKey(elemName)) Ty.Arena(elemName)
                else run { errors += "Arena<${elemName}>: '${elemName}' is not a known 'arena struct'"; Ty.Unit_ }
            }
            else -> {
                externClasses[t.name]?.let { return Ty.JavaExtern(it.binaryName, t.isNullable) }
                // A directly-written generic type name with concrete type args, e.g. a `let`
                // declared type `Option<Int>` -- as opposed to substituteTypeRef's similar
                // logic, which additionally has to substitute type *params* (like `T` inside
                // `Option<T>`) before instantiating, for a template still being instantiated.
                genericStructTemplates[t.name]?.let { tpl ->
                    return Ty.Struct(getOrInstantiateStruct(tpl, t.typeArgs.map { resolveType(it) }).name)
                }
                genericEnumTemplates[t.name]?.let { tpl ->
                    return Ty.Enum(getOrInstantiateEnum(tpl, t.typeArgs.map { resolveType(it) }, 0).name)
                }
                structs[t.name]?.let { Ty.Struct(t.name) }
                    ?: enums[t.name]?.let { Ty.Enum(t.name) }
                    ?: run { errors += "Unknown type '${t.name}'"; Ty.Unit_ }
            }
        }
    }

    // ---- monomorphization ----

    private fun tyName(t: Ty): String = when (t) {
        Ty.Int_ -> "Int"
        Ty.Long_ -> "Long"
        Ty.Float_ -> "Float"
        Ty.Double_ -> "Double"
        Ty.Bool_ -> "Bool"
        is Ty.Str_ -> "String"
        Ty.Unit_ -> "Unit"
        is Ty.Struct -> t.name
        is Ty.Array -> "Array_${tyName(t.elem)}"
        is Ty.Arena -> "Arena_${t.structName}"
        is Ty.Dyn -> "Dyn_${t.interfaceName}"
        is Ty.Enum -> t.name
        is Ty.JavaExtern -> t.binaryName.replace('/', '_')
    }

    private fun mangle(base: String, typeArgs: List<Ty>): String =
        base + typeArgs.joinToString("") { "_" + tyName(it) }

    // Converts a resolved Ty back into a TypeRef that `resolveType` can turn right back into
    // the same Ty -- needed wherever a type param gets substituted with something whose
    // *name* alone isn't a valid type reference (a `dyn Trait` needs isRef/isDyn set, an array
    // needs its element wrapped, etc.). Using bare `tyName(ty)` as the name for those (as this
    // function used to) silently produced an unresolvable type name like "Dyn_Block" --
    // real gap, only ever exercised once a generic got instantiated with `&dyn X` or `[X]`.
    private fun tyToTypeRef(ty: Ty, isRef: Boolean, isMut: Boolean): TypeRef = when (ty) {
        is Ty.Dyn -> TypeRef(ty.interfaceName, isRef = true, isMut = isMut, isDyn = true)
        is Ty.Array -> TypeRef("Array", isRef, listOf(tyToTypeRef(ty.elem, isRef = false, isMut = false)))
        is Ty.Arena -> TypeRef("Arena", isRef, listOf(TypeRef(ty.structName, isRef = false)))
        is Ty.JavaExtern -> {
            // Find the original alias name for this binary name.
            val alias = externClasses.entries.firstOrNull { it.value.binaryName == ty.binaryName }?.key
                ?: ty.binaryName // fallback to binary name if not found
            TypeRef(alias, isRef, isMut = isMut)
        }
        else -> TypeRef(tyName(ty), isRef, isMut = isMut)
    }

    // Substitutes a bare type-param name (e.g. `T`), and also resolves nested generic type
    // refs (e.g. `Box<T>` or `[T]` used as a field/param type) into their concrete form.
    private fun substituteTypeRef(t: TypeRef, subst: Map<String, Ty>): TypeRef {
        subst[t.name]?.let { return tyToTypeRef(it, t.isRef, t.isMut) }
        if (t.name == "Array" && t.typeArgs.size == 1) {
            return TypeRef("Array", t.isRef, listOf(substituteTypeRef(t.typeArgs[0], subst)), t.isMut)
        }
        if (t.typeArgs.isNotEmpty()) {
            val structTemplate = genericStructTemplates[t.name]
            if (structTemplate != null) {
                val substitutedArgs = t.typeArgs.map { substituteTypeRef(it, subst) }
                val info = getOrInstantiateStruct(structTemplate, substitutedArgs.map { resolveType(it) })
                return TypeRef(info.name, t.isRef, isMut = t.isMut)
            }
            val enumTemplate = genericEnumTemplates[t.name]
            if (enumTemplate != null) {
                val substitutedArgs = t.typeArgs.map { substituteTypeRef(it, subst) }
                val info = getOrInstantiateEnum(enumTemplate, substitutedArgs.map { resolveType(it) }, 0)
                return TypeRef(info.name, t.isRef, isMut = t.isMut)
            }
        }
        return t
    }

    private fun substituteBlock(b: Block, subst: Map<String, Ty>): Block = Block(b.stmts.map { substituteStmt(it, subst) })

    private fun substituteStmt(s: Stmt, subst: Map<String, Ty>): Stmt = when (s) {
        is Stmt.Let -> s.copy(declType = s.declType?.let { substituteTypeRef(it, subst) }, init = substituteExpr(s.init, subst))
        is Stmt.ExprStmt -> s.copy(expr = substituteExpr(s.expr, subst))
        is Stmt.If -> s.copy(
            cond = substituteExpr(s.cond, subst),
            thenB = substituteBlock(s.thenB, subst),
            elseB = s.elseB?.let { substituteBlock(it, subst) },
        )
        is Stmt.While -> s.copy(cond = substituteExpr(s.cond, subst), body = substituteBlock(s.body, subst))
        is Stmt.For -> s.copy(iterable = substituteExpr(s.iterable, subst), body = substituteBlock(s.body, subst))
        is Stmt.Return -> s.copy(expr = s.expr?.let { substituteExpr(it, subst) })
        is Stmt.Nested -> s.copy(block = substituteBlock(s.block, subst))
        is Stmt.Match -> s.copy(
            scrutinee = substituteExpr(s.scrutinee, subst),
            arms = s.arms.map { it.copy(body = substituteBlock(it.body, subst)) },
        )
        is Stmt.Try -> s.copy(
            tryBlock = substituteBlock(s.tryBlock, subst),
            catches = s.catches.map { it.copy(body = substituteBlock(it.body, subst)) },
        )
        is Stmt.Throw -> s.copy(expr = substituteExpr(s.expr, subst))
    }

    private fun substituteExpr(e: Expr, subst: Map<String, Ty>): Expr = when (e) {
        is Expr.Binary -> Expr.Binary(e.op, substituteExpr(e.left, subst), substituteExpr(e.right, subst), e.line)
        is Expr.Unary -> Expr.Unary(e.op, substituteExpr(e.expr, subst), e.line)
        is Expr.Cast -> Expr.Cast(substituteExpr(e.inner, subst), e.target, e.line)
        is Expr.InstanceOf -> Expr.InstanceOf(substituteExpr(e.inner, subst), e.target, e.line)
        is Expr.Borrow -> Expr.Borrow(substituteExpr(e.inner, subst), e.isMut)
        is Expr.Assign -> Expr.Assign(e.name, substituteExpr(e.value, subst), e.line)
        is Expr.Call -> Expr.Call(e.callee, e.args.map { substituteExpr(it, subst) }, e.line)
        is Expr.FieldAccess -> Expr.FieldAccess(substituteExpr(e.obj, subst), e.field, e.line)
        is Expr.FieldAssign -> Expr.FieldAssign(substituteExpr(e.obj, subst), e.field, substituteExpr(e.value, subst), e.line)
        is Expr.StructLit -> Expr.StructLit(e.typeName, e.fields.map { it.first to substituteExpr(it.second, subst) }, e.line)
        is Expr.MethodCall -> Expr.MethodCall(substituteExpr(e.recv, subst), e.method, e.args.map { substituteExpr(it, subst) }, e.line)
        is Expr.StaticCall -> Expr.StaticCall(e.typeName, e.method, e.args.map { substituteExpr(it, subst) }, e.line)
        is Expr.StaticFieldGet -> Expr.StaticFieldGet(e.typeName, e.field, e.line)
        is Expr.ArrayLit -> Expr.ArrayLit(e.elements.map { substituteExpr(it, subst) }, e.line)
        is Expr.ArrayRepeat -> Expr.ArrayRepeat(substituteExpr(e.value, subst), substituteExpr(e.count, subst), e.line)
        is Expr.Index -> Expr.Index(substituteExpr(e.arr, subst), substituteExpr(e.index, subst), e.line)
        is Expr.IndexAssign -> Expr.IndexAssign(substituteExpr(e.arr, subst), substituteExpr(e.index, subst), substituteExpr(e.value, subst), e.line)
        is Expr.ArenaNew -> Expr.ArenaNew(e.structName, substituteExpr(e.count, subst), e.line)
        is Expr.Range -> Expr.Range(substituteExpr(e.start, subst), substituteExpr(e.end, subst), e.line)
        is Expr.StringInterp -> Expr.StringInterp(e.literals, e.exprs.map { substituteExpr(it, subst) }, e.line)
        // Every instantiation needs its own expr nodes: `ty`/`resolvedName` are mutable and
        // set per-check, so sharing leaf nodes across instantiations would let the last-checked
        // one clobber the others' annotations.
        is Expr.IntLit -> Expr.IntLit(e.value)
        is Expr.LongLit -> Expr.LongLit(e.value)
        is Expr.FloatLit -> Expr.FloatLit(e.value)
        is Expr.DoubleLit -> Expr.DoubleLit(e.value)
        is Expr.StringLit -> Expr.StringLit(e.value)
        is Expr.BoolLit -> Expr.BoolLit(e.value)
        is Expr.NullLit -> Expr.NullLit()
        is Expr.Ident -> Expr.Ident(e.name, e.line)
        is Expr.If -> Expr.If(substituteExpr(e.cond, subst), substituteBlock(e.thenB, subst), substituteBlock(e.elseB, subst), e.line)
        is Expr.Match -> Expr.Match(
            substituteExpr(e.scrutinee, subst),
            e.arms.map { it.copy(body = substituteBlock(it.body, subst)) },
            e.line,
        )
        is Expr.Lambda -> Expr.Lambda(e.params, substituteExpr(e.body, subst), e.line)
        is Expr.ClassLit -> Expr.ClassLit(e.typeName, e.line)
    }

    // `T: Trait1 + Trait2`, checked at every real instantiation site: does the concrete `Ty`
    // substituted for `T` actually implement every required trait? This alone was the *only*
    // enforcement a bound gave for a long time -- see `checkGenericFnBoundValidity` below for
    // the other, more important half (does the *body* only ever rely on what the bound
    // promises?) that used to be completely missing. A `Ty.Dyn` argument -- the synthetic
    // merged-bound interface `checkGenericFnBoundValidity` instantiates with, or a real `&dyn
    // Trait`-typed value inferred as a type argument at a genuine call site -- satisfies a
    // required trait if its own interface name matches, or (transitively) if that interface was
    // built by merging a bound list that included it (see `boundInterfaceComponents`).
    private fun checkTypeParamBounds(typeParams: List<String>, bounds: Map<String, List<String>>, typeArgs: List<Ty>, line: Int, what: String) {
        if (bounds.isEmpty()) return
        for ((name, ty) in typeParams.zip(typeArgs)) {
            val required = bounds[name] ?: continue
            val implemented: Set<String> = when (ty) {
                is Ty.Struct -> {
                    val ownerName = structInstanceTemplate[ty.name] ?: ty.name
                    structInterfaces[ownerName] ?: emptySet()
                }
                is Ty.Dyn -> setOf(ty.interfaceName) + (boundInterfaceComponents[ty.interfaceName] ?: emptySet())
                else -> emptySet()
            }
            val missing = required.filter { it !in implemented }
            if (missing.isNotEmpty()) {
                errors += "Line $line: $what: '$ty' for type param '$name' doesn't implement ${missing} (required by 'T: ${required.joinToString(" + ")}')"
            }
        }
    }

    // synthetic merged-bound-interface name -> the real trait names it was merged from (so
    // `checkTypeParamBounds` can recognize a `Ty.Dyn` pointing at one of these as satisfying
    // each individual bound it was built to cover). Keyed by the *interface name*, not the
    // bound list, since that's all `checkTypeParamBounds` has to work with once the Dyn value
    // already exists.
    private val boundInterfaceComponents = mutableMapOf<String, Set<String>>()
    // bound list (sorted, for a stable cache key) -> the merged interface's name, or null if
    // synthesizing it failed (already reported). See `checkGenericFnBoundValidity`.
    private val boundInterfaceCache = mutableMapOf<List<String>, String?>()

    // Synthesizes a real (checker-internal only -- registered in `interfaces`, never in
    // `program.interfaces`, so codegen never sees it and no classfile is ever emitted for it) merged
    // interface combining every method every trait in `bounds` declares, so a bounded type param
    // can be checked *abstractly*, before any real instantiation exists, by typing it as `Ty.Dyn`
    // against this interface -- reusing the exact same method-resolution path a real `&dyn Trait`
    // receiver already goes through in `checkMethodCall`, which is exactly the restriction a
    // bound is supposed to provide (only members the bound trait(s) actually declare are visible).
    private fun syntheticBoundInterface(bounds: List<String>, line: Int): String? {
        val key = bounds.sorted()
        if (boundInterfaceCache.containsKey(key)) return boundInterfaceCache[key]
        val merged = InterfaceInfo("\$Bound\$" + key.joinToString("\$"))
        for (b in bounds) {
            val info = interfaces[b]
            if (info == null) {
                errors += "Line $line: unknown bound trait '$b'"
                boundInterfaceCache[key] = null
                return null
            }
            for ((mname, msig) in info.methods) {
                val existing = merged.methods[mname]
                if (existing != null && existing.paramTys != msig.paramTys) {
                    errors += "Line $line: bound combination '${bounds.joinToString(" + ")}' declares conflicting signatures for method '$mname' -- no real type could implement both compatibly"
                    boundInterfaceCache[key] = null
                    return null
                }
                merged.methods[mname] = msig
            }
        }
        interfaces[merged.name] = merged
        boundInterfaceComponents[merged.name] = key.toSet()
        boundInterfaceCache[key] = merged.name
        return merged.name
    }

    // The other half of what a declared bound is supposed to guarantee, previously entirely
    // missing: not just "does the concrete type at each instantiation site implement the bound"
    // (checkTypeParamBounds, above) but "does the body itself only ever rely on what the bound
    // actually promises." Without this, a bounded generic's body was checked exactly like an
    // *unbounded* one -- fully duck-typed against whatever concrete type happened to get
    // substituted in -- so a body could compile fine against the first instantiation tried
    // (accidentally relying on some method that merely happens to exist on that one concrete
    // type, not on anything the bound declares) and only fail, confusingly, at a *different*,
    // later call site instantiating the same generic with a type that satisfies the identical
    // declared bound but lacks that extra method. That defeats the entire point of writing a
    // bound in the first place: the promise that "compiles once" means "works for every type
    // satisfying the bound," not merely "works for the type someone happened to test first."
    //
    // Fixed by checking the template's body exactly once, abstractly: every bounded type param
    // is substituted with `Ty.Dyn(mergedBoundInterface)` instead of any real concrete type (see
    // syntheticBoundInterface), so every method call the body makes on a bound-typed value goes
    // through the same restricted `&dyn Trait` resolution a real interface value already does --
    // only methods the bound(s) actually declare resolve at all. This is genuinely a *stronger*
    // check than any single real instantiation could give, since it holds for every future
    // instantiation simultaneously, checked exactly once, cached, and entirely independent of
    // whether the generic is ever actually called anywhere in the program (matching how a real
    // bounded generic's correctness shouldn't depend on which instantiations someone happened to
    // exercise) -- run once per template right after registration, not lazily at first use.
    //
    // Scoped to fns where *every* type param has a declared bound (a mixed `<T: Trait, U>` has
    // no way to abstractly type the unbounded `U` at all -- falls back to today's
    // per-instantiation-only checking for such fns, unchanged, not a regression) and to
    // top-level fns only (a `self` param on a generic struct's own impl method would also need
    // `Self`/`Owner<T>` itself abstracted, real design work not attempted in this pass).
    private val boundValidityChecked = mutableSetOf<String>()
    private fun checkGenericFnBoundValidity(template: FnDecl) {
        if (template.typeParams.isEmpty()) return
        if (template.params.firstOrNull()?.name == "self") return
        if (!template.typeParams.all { template.typeParamBounds.containsKey(it) }) return
        if (!boundValidityChecked.add(template.name)) return
        val typeArgs = template.typeParams.map { tp ->
            val bounds = template.typeParamBounds.getValue(tp)
            val ifaceName = syntheticBoundInterface(bounds, template.line) ?: return
            Ty.Dyn(ifaceName)
        }
        val mangled = mangle(template.name, typeArgs)
        if (fns.containsKey(mangled)) return
        val subst = template.typeParams.zip(typeArgs).toMap()
        val newParams = template.params.map { Param(it.name, substituteTypeRef(it.type, subst)) }
        val newRet = template.retType?.let { substituteTypeRef(it, subst) }
        val sig = FnSig(newParams, newParams.map { resolveType(it.type) }, newRet?.let { resolveType(it) } ?: Ty.Unit_)
        fns[mangled] = sig // register before checking: supports a self-recursive bounded generic
        val newDecl = FnDecl(mangled, newParams, newRet, substituteBlock(template.body, subst), template.line, moduleName = template.moduleName, visible = template.visible)
        checkFn(newDecl)
        // Deliberately not registered in `fnInstances` -- this synthetic instantiation is purely
        // a checker-time validation pass and is never emitted by codegen (nothing in
        // `program.fns`/`genericFnTemplates` ever references this mangled name).
    }

    private fun getOrInstantiateFn(template: FnDecl, typeArgs: List<Ty>, line: Int): FnSig {
        val mangled = mangle(template.name, typeArgs)
        fns[mangled]?.let { return it }
        checkTypeParamBounds(template.typeParams, template.typeParamBounds, typeArgs, line, "'${template.name}'")
        val subst = template.typeParams.zip(typeArgs).toMap()
        val newParams = template.params.map { Param(it.name, substituteTypeRef(it.type, subst)) }
        val newRet = template.retType?.let { substituteTypeRef(it, subst) }
        val sig = FnSig(newParams, newParams.map { resolveType(it.type) }, newRet?.let { resolveType(it) } ?: Ty.Unit_)
        fns[mangled] = sig // register before checking the body: supports self-recursive generics
        val newDecl = FnDecl(mangled, newParams, newRet, substituteBlock(template.body, subst), line, moduleName = template.moduleName, visible = template.visible)
        fnInstances[mangled] = newDecl
        checkFn(newDecl)
        return sig
    }

    private fun getOrInstantiateStruct(template: StructDecl, typeArgs: List<Ty>): StructInfo {
        val mangled = mangle(template.name, typeArgs)
        structs[mangled]?.let { return it }
        checkTypeParamBounds(template.typeParams, template.typeParamBounds, typeArgs, 0, "struct '${template.name}'")
        val subst = template.typeParams.zip(typeArgs).toMap()
        val newFields = template.fields.map { FieldDecl(it.name, substituteTypeRef(it.type, subst)) }
        val info = StructInfo(mangled, newFields.map { it.name to resolveType(it.type) })
        structs[mangled] = info
        structInstances[mangled] = StructDecl(mangled, newFields, moduleName = template.moduleName, visible = template.visible)
        structInstanceArgs[mangled] = typeArgs
        structInstanceTemplate[mangled] = template.name

        // Any `impl<T> Interface for Struct<T> { }` blocks deferred from the main pass: only
        // meaningful once we have a concrete instantiation to attach the interface to.
        for (impl in genericInterfaceImpls[template.name].orEmpty()) {
            if (impl.typeParams.size != typeArgs.size) continue // already reported at registration
            processInterfaceImpl(impl, mangled, impl.typeParams.zip(typeArgs).toMap())
        }
        if (genericInterfaceImpls.containsKey(template.name)) checkInterfaceCompleteness(mangled)
        return info
    }

    private fun getOrInstantiateEnum(template: EnumDecl, typeArgs: List<Ty>, line: Int): EnumInfo {
        val mangled = mangle(template.name, typeArgs)
        enums[mangled]?.let { return it }
        checkTypeParamBounds(template.typeParams, template.typeParamBounds, typeArgs, line, "enum '${template.name}'")
        val subst = template.typeParams.zip(typeArgs).toMap()
        val variants = template.variants.mapIndexed { tag, v ->
            EnumVariantInfo(v.name, tag, v.fields.map { it.name to resolveType(substituteTypeRef(it.type, subst)) })
        }
        val info = EnumInfo(mangled, variants, template.moduleName, template.visible)
        enums[mangled] = info
        enumInstanceTemplate[mangled] = template.name
        enumInstanceArgs[mangled] = typeArgs
        return info
    }

    // Resolves `interface Sub: Super, Super2` transitively, with a cycle guard. Own methods
    // win over inherited ones with the same name (last-declared supertrait wins between
    // supertraits themselves, which is unspecified-but-deterministic and rarely matters).
    private fun flattenInterfaceMethods(name: String, visiting: MutableSet<String>): Map<String, InterfaceMethodSig> {
        val raw = rawInterfaces[name] ?: return emptyMap()
        if (!visiting.add(name)) {
            errors += "Interface inheritance cycle involving '${name}'"
            return emptyMap()
        }
        val result = mutableMapOf<String, InterfaceMethodSig>()
        for (sup in raw.extends) {
            if (!rawInterfaces.containsKey(sup)) errors += "Interface '${name}' extends unknown interface '${sup}'"
            result.putAll(flattenInterfaceMethods(sup, visiting))
        }
        result.putAll(raw.ownMethods)
        visiting.remove(name)
        return result
    }

    private fun transitiveSupertraits(name: String, acc: MutableSet<String> = mutableSetOf()): Set<String> {
        for (sup in rawInterfaces[name]?.extends.orEmpty()) {
            if (acc.add(sup)) transitiveSupertraits(sup, acc)
        }
        return acc
    }

    // Validates `impl S { override fn m(...) { ... } }`: `S` must `extend` an `extern class`,
    // and `m` must match one of that class's declared instance methods by name and exact
    // signature (self + params + return) -- same shape as `processInterfaceImpl` below, just
    // sourced from the extended extern class's method table instead of an interface's. Records
    // a match into `superclassOverrideFns`, compiled as a real instance method (see genStruct)
    // so external Java code reaches it via ordinary JVM virtual dispatch, same reasoning as
    // interface overrides needing real instance methods instead of this language's usual
    // static-desugared ones.
    private fun checkSuperclassOverride(structName: String, m: FnDecl, fixSelf: (TypeRef) -> TypeRef) {
        val superAlias = structSuperclass[structName]
        if (superAlias == null) {
            errors += "Line ${m.line}: 'override fn ${m.name}': '${structName}' doesn't 'extends' anything"
            return
        }
        val ext = externClasses.getValue(superAlias)
        val candidates = resolveExternMethods(ext, m.name, m.line).filter { !it.isCtor && !it.isStatic }
        if (candidates.isEmpty()) {
            errors += "Line ${m.line}: 'override fn ${m.name}': '${superAlias}' has no instance method '${m.name}' to override"
            return
        }
        val selfParam = m.params.getOrNull(0)
        if (selfParam?.name != "self" || !selfParam.type.isRef) {
            errors += "Line ${m.line}: 'override fn ${m.name}' must take '&self' or '&mut self'"
            return
        }
        val em = pickCandidate(candidates, m.params.size - 1)
        val restTys = m.params.drop(1).map { resolveType(fixSelf(it.type)) }
        val retTy = m.retType?.let { resolveType(fixSelf(it)) } ?: Ty.Unit_
        if (restTys != em.params || retTy != em.retType) {
            errors += "Line ${m.line}: 'override fn ${m.name}' doesn't match '${superAlias}'s signature for this method " +
                    "(expected (${em.params.joinToString(", ")}) -> ${em.retType})"
        }
        val newDecl = FnDecl(m.name, m.params.map { Param(it.name, fixSelf(it.type)) }, m.retType?.let { fixSelf(it) }, m.body, m.line)
        superclassOverrideFns.getOrPut(structName) { mutableListOf() } += newDecl
        // Same "$structName@$method" key convention `processInterfaceImpl` already uses for
        // interface overrides -- `checkMethodCall`'s `Ty.Struct` branch and codegen's
        // `instanceOwner` path both key off exactly this, so a superclass override is callable
        // from HC code (`self.method(...)`) through the identical real-instance-method/
        // INVOKEVIRTUAL machinery, with no separate resolution path needed. (A struct that both
        // `extends` a class and `impl`s an interface with a same-named method would collide
        // here -- rare enough, and not attempted by anything real yet, to leave unhandled.)
        // Registered here (not just where the body gets checked below) so a call site reached
        // earlier in the same pass can already resolve it, same reasoning as interface overrides.
        fns["$structName@${m.name}"] = FnSig(newDecl.params, newDecl.params.map { resolveType(it.type) }, retTy)
    }

    // Validates an `impl Interface for Struct { }` block (every required method overridden,
    // signatures match) and records its provided overrides as real instance methods to
    // compile onto the struct's own class. `concreteStructName` is the mangled instantiation
    // name for a generic struct, or just the struct's own name for a concrete one; `subst`
    // substitutes the impl's own type params (empty for a concrete struct).
    private fun processInterfaceImpl(impl: ImplBlock, concreteStructName: String, subst: Map<String, Ty>) {
        val ifaceName = impl.interfaceName!!
        val iface = interfaces[ifaceName]
        if (iface == null) { errors += "'impl ${ifaceName} for ${impl.structName}': unknown interface '${ifaceName}'"; return }
        val selfType = TypeRef(concreteStructName, isRef = false)
        fun fixSelf(t: TypeRef): TypeRef {
            val withSelf = if (t.name == "Self") selfType.copy(isRef = t.isRef, isMut = t.isMut) else t
            return if (subst.isEmpty()) withSelf else substituteTypeRef(withSelf, subst)
        }
        val provided = mutableSetOf<String>()
        for (m in impl.methods) {
            val msig = iface.methods[m.name]
            if (msig == null) { errors += "Line ${m.line}: '${ifaceName}' has no method '${m.name}'"; continue }
            provided += m.name
            val selfParam = m.params.getOrNull(0)
            val restTys = m.params.drop(1).map { resolveType(fixSelf(it.type)) }
            val retTy = m.retType?.let { resolveType(fixSelf(it)) } ?: Ty.Unit_
            val selfOk = selfParam?.name == "self" && selfParam.type.isRef && selfParam.type.isMut == msig.selfIsMut
            if (!selfOk || restTys != msig.paramTys || retTy != msig.ret) {
                errors += "Line ${m.line}: '${m.name}' doesn't match '${ifaceName}''s signature for this method"
            }
            // Body always deep-copied (even when subst is empty): a generic struct's interface
            // impl gets processed once per distinct instantiation (Box_Int, Box_Person, ...),
            // and reusing the same Block/Expr objects across them would let the last-checked
            // instantiation's type annotations clobber the others' -- the same hazard fixed
            // for generic fn/struct instantiation elsewhere in this file.
            val newDecl = FnDecl(m.name, m.params.map { Param(it.name, fixSelf(it.type)) }, m.retType?.let { fixSelf(it) }, substituteBlock(m.body, subst), m.line)
            interfaceImplFns.getOrPut(concreteStructName) { mutableListOf() } += newDecl
            // Registered here (not just where the body gets checked later) so a call site
            // reached *before* that later pass -- e.g. inside `fn main()`, checked in the very
            // same top-level pass these overrides are collected during -- can already resolve it.
            fns["$concreteStructName@${m.name}"] = FnSig(newDecl.params, newDecl.params.map { resolveType(it.type) }, newDecl.retType?.let { resolveType(it) } ?: Ty.Unit_)
        }
        // `by field`: every interface method *not* explicitly overridden above gets a
        // forwarding method synthesized -- `fn m(&self, args) { return self.field.m(args); }`
        // -- rather than requiring the struct to hand-write boilerplate delegation. Whether
        // the field's type actually has the method is left to the normal checker pass over
        // this synthesized body (in the unified interfaceImplFns loop) to catch, same as any
        // other method call -- no special-cased validation duplicated here.
        if (impl.delegateField != null) {
            for ((mname, msig) in iface.methods) {
                if (mname in provided) continue
                val selfParam = Param("self", fixSelf(TypeRef("Self", isRef = true, isMut = msig.selfIsMut)))
                val newParams = listOf(selfParam) + msig.params.map { Param(it.name, fixSelf(it.type)) }
                val receiver = Expr.FieldAccess(Expr.Ident("self", 0), impl.delegateField, 0)
                val args = msig.params.map { p ->
                    if (p.type.isRef) Expr.Borrow(Expr.Ident(p.name, 0), p.type.isMut) else Expr.Ident(p.name, 0)
                }
                val body = Block(listOf(Stmt.Return(Expr.MethodCall(receiver, mname, args, 0), 0)))
                val newDecl = FnDecl(mname, newParams, msig.retType?.let { fixSelf(it) }, body, 0)
                interfaceImplFns.getOrPut(concreteStructName) { mutableListOf() } += newDecl
                fns["$concreteStructName@${mname}"] = FnSig(newDecl.params, newDecl.params.map { resolveType(it.type) }, newDecl.retType?.let { resolveType(it) } ?: Ty.Unit_)
            }
        }
        // Deliberately *not* checked here: on the JVM, one instance method named e.g. `info`
        // satisfies any number of interfaces' abstract `info` requirement simultaneously, so
        // "is everything required actually provided" can't be decided per impl block -- an
        // override written under `impl A for Struct` can satisfy `impl B for Struct`'s
        // requirement for the same method too. See checkInterfaceCompleteness, run once all
        // of a struct's impl blocks (across every interface) have been processed.
        structInterfaces.getOrPut(concreteStructName) { mutableSetOf() } += ifaceName
        structInterfaces.getOrPut(concreteStructName) { mutableSetOf() } += transitiveSupertraits(ifaceName)
        if (iface.sealed && concreteStructName !in iface.implementers) iface.implementers += concreteStructName
    }

    // For every interface a struct implements, checks every required (non-default) method is
    // present *somewhere* among that struct's provided overrides (by name -- one override can
    // satisfy several interfaces' requirements for the same method, matching real JVM semantics).
    private fun checkInterfaceCompleteness(structName: String) {
        val provided = interfaceImplFns[structName].orEmpty().map { it.name }.toSet()
        for (ifaceName in structInterfaces[structName].orEmpty()) {
            val iface = interfaces[ifaceName] ?: continue
            val missing = iface.methods.filterValues { !it.hasDefault }.keys - provided
            if (missing.isNotEmpty()) {
                errors += "'${structName}' is missing required method(s) ${missing} for interface '${ifaceName}'"
            }
        }
    }

    // Recursively binds type-param names found in `paramType` (e.g. bare `T`, or nested
    // inside a generic struct's type args like `Box<T>`) against the concrete `argTy` of
    // the already-checked argument expression.
    private fun bindTypeParams(paramType: TypeRef, argTy: Ty, typeParams: List<String>, bindings: MutableMap<String, Ty>) {
        if (paramType.name in typeParams) {
            bindings.putIfAbsent(paramType.name, argTy)
            return
        }
        if (paramType.name == "Array" && paramType.typeArgs.size == 1 && argTy is Ty.Array) {
            bindTypeParams(paramType.typeArgs[0], argTy.elem, typeParams, bindings)
            return
        }
        if (paramType.typeArgs.isNotEmpty() && argTy is Ty.Struct) {
            val concreteArgs = structInstanceArgs[argTy.name] ?: return
            for ((sub, concrete) in paramType.typeArgs.zip(concreteArgs)) {
                bindTypeParams(sub, concrete, typeParams, bindings)
            }
        }
    }

    // Infers each type param by matching `paramTypes` (which may reference type params
    // directly or nested inside another generic type, e.g. `Box<T>`) against the concrete
    // types of the corresponding already-checked argument expressions.
    // `expectedTypeArgs`: a fallback for whichever type params can't be solved from the
    // constructor's own field values -- e.g. `Result::Ok { value: n }` only has a field to infer
    // `T` from, never `E` (there's no `error`-typed value anywhere in an `Ok`), so `E` can only
    // ever come from context (a declared `let`/return type). Exactly the same idea `None`
    // already needed (a zero-field variant can't infer *any* param from its fields), generalized
    // to "whichever params are still missing after fields," not just "all of them."
    private fun inferTypeArgs(typeParams: List<String>, paramTypes: List<TypeRef>, argTys: List<Ty>, line: Int, what: String, expectedTypeArgs: List<Ty>? = null): List<Ty>? {
        val bindings = mutableMapOf<String, Ty>()
        for ((paramType, argTy) in paramTypes.zip(argTys)) {
            bindTypeParams(paramType, argTy, typeParams, bindings)
        }
        if (expectedTypeArgs != null && expectedTypeArgs.size == typeParams.size) {
            for ((i, tp) in typeParams.withIndex()) {
                if (tp !in bindings) bindings[tp] = expectedTypeArgs[i]
            }
        }
        val missing = typeParams.filter { it !in bindings }
        if (missing.isNotEmpty()) {
            errors += "Line $line: cannot infer type argument(s) ${missing} for $what (not enough context)"
            return null
        }
        return typeParams.map { bindings.getValue(it) }
    }

    // ---- function bodies ----

    private fun checkFn(f: FnDecl) {
        val sig = fns.getValue(f.name)
        val env = Env()
        env.push()
        // Every `static` is in scope everywhere, always mutable-through -- deliberately never
        // added to `moved` (see checkExpr's Ident case): there's no scope for a global to be
        // "used up" by, so a read here must never poison a later read anywhere else.
        for ((name, info) in statics) {
            env.declare(name, VarInfo(info.ty, mutable = true, canMutateFields = true))
        }
        val moved = mutableMapOf<String, Boolean>()
        for ((p, ty) in f.params.zip(sig.paramTys)) {
            val canMutFields = p.type.isRef && p.type.isMut
            env.declare(p.name, VarInfo(ty, mutable = false, canMutateFields = canMutFields))
            moved[p.name] = false
        }
        checkBlock(f.body, env, moved, sig.ret)
        env.pop()
    }

    // `beforeCheck`: runs right after `env.push()`, before any statement in `block` is checked --
    // its only real use is narrowing a nullable extern binding for exactly this block's own
    // scope (see the `Stmt.If` narrowing below), since a declare here lands in the frame this
    // call's own `env.pop()` discards, never leaking into a sibling branch or code after the
    // block.
    private fun checkBlock(
        block: Block,
        env: Env,
        movedIn: Map<String, Boolean>,
        retTy: Ty,
        beforeCheck: (() -> Unit)? = null,
    ): Map<String, Boolean> {
        env.push()
        beforeCheck?.invoke()
        dropScopeStack.addLast(mutableListOf())
        var moved: Map<String, Boolean> = HashMap(movedIn)
        for (stmt in block.stmts) {
            moved = checkStmt(stmt, env, moved, retTy)
        }
        val frame = dropScopeStack.removeLast()
        block.dropsAtEnd = frame.asReversed().filter { moved[it] != true }
        env.pop()
        return moved.filterKeys { movedIn.containsKey(it) }
    }

    // `if x == null { ... }`/`if null == x { ... }` -- returns `x`'s name, or null if `cond`
    // isn't exactly this shape (any other condition, `!=` instead of `==`, comparing something
    // other than a bare identifier). Deliberately narrow pattern matching, not general
    // expression analysis -- this only needs to recognize the one guard-clause idiom the
    // narrowing above cares about.
    // `if x == null { ... }` / `if x != null { ... }` (either literal order) -- returns the
    // checked ident's name paired with which branch is the one where `x` is proven non-null:
    // `true` for `!=` (the *then* branch is non-null), `false` for `==` (the *else* branch is,
    // and -- see the `Stmt.If` handling below -- so is everything *after* the whole statement,
    // if there's no `else` and the `then` branch always exits). Any other condition shape (not
    // `==`/`!=`, not exactly `ident <op> null`) returns null -- deliberately narrow pattern
    // matching, not general expression analysis.
    private fun nullCheckedIdentName(cond: Expr): Pair<String, Boolean>? {
        if (cond !is Expr.Binary || (cond.op != "==" && cond.op != "!=")) return null
        val name = when {
            cond.left is Expr.Ident && cond.right is Expr.NullLit -> cond.left.name
            cond.right is Expr.Ident && cond.left is Expr.NullLit -> cond.right.name
            else -> null
        } ?: return null
        return name to (cond.op == "!=")
    }

    // Re-declares `name` in `env`'s *current* top frame as non-nullable, if it's currently a
    // nullable `Ty.JavaExtern` or `Ty.Str_` -- shared by every narrowing call site below. A
    // no-op for anything else (already non-nullable, not a nullable-capable type, unknown name),
    // so it's safe to call unconditionally wherever a narrowing candidate might apply.
    private fun narrowNonNull(env: Env, name: String) {
        val info = env.lookup(name) ?: return
        when (val ty = info.ty) {
            is Ty.JavaExtern -> if (ty.nullable) env.declare(name, info.copy(ty = ty.copy(nullable = false)))
            is Ty.Str_ -> if (ty.nullable) env.declare(name, info.copy(ty = Ty.Str_(nullable = false)))
            else -> {}
        }
    }

    // Whether a block unconditionally exits its enclosing function (or throws) rather than
    // falling through -- checked structurally (last statement is `return`/`throw`), not a full
    // reachability analysis. Enough for real guard clauses (`if x == null { return ...; }`),
    // which always end directly in the exit, not buried inside a further nested branch.
    private fun blockAlwaysExits(block: Block): Boolean {
        val last = block.stmts.lastOrNull() ?: return false
        return last is Stmt.Return || last is Stmt.Throw
    }

    private fun checkStmt(
        stmt: Stmt,
        env: Env,
        moved: Map<String, Boolean>,
        retTy: Ty,
    ): Map<String, Boolean> = when (stmt) {
        is Stmt.Let -> {
            val declared = stmt.declType?.let { resolveType(it) }
            val (rhsTy, m) = checkExpr(stmt.init, env, moved, consume = true, expectedTy = declared)
            if (declared != null && !tyCompatible(rhsTy, declared)) {
                errors += "Line ${stmt.line}: cannot assign ${rhsTy} to '${stmt.name}' of declared type ${declared}"
            }
            // If the RHS is a concrete struct but the declared type is a wider `&dyn Trait` it
            // implements, `stmt.name` is stored as the *declared* type from here on -- without
            // this, a `let x: &dyn Block = &StoneBlock{};` would silently keep `x` typed as the
            // concrete `StoneBlock` internally, so passing `x` on to anything generic (e.g.
            // inferring a type param from it) would infer the concrete type instead of the
            // trait, defeating the entire point of writing the annotation.
            val ty = if (declared != null && tyCompatible(rhsTy, declared)) declared else rhsTy
            env.declare(stmt.name, VarInfo(ty, stmt.mutable, canMutateFields = stmt.mutable))
            if (ty is Ty.Struct && hasDrop(ty.name)) dropScopeStack.lastOrNull()?.add(stmt.name)
            val out = HashMap(m)
            out[stmt.name] = false
            out
        }
        is Stmt.ExprStmt -> checkExpr(stmt.expr, env, moved, consume = true).second
        is Stmt.If -> {
            val (condTy, m0) = checkExpr(stmt.cond, env, moved, consume = true)
            if (condTy != Ty.Bool_) errors += "if condition must be Bool, got ${condTy}"
            // `if x != null { ... }` narrows `x` for the *then* branch's own scope only;
            // `if x == null { ... } else { ... }` narrows it for the *else* branch's own scope
            // only (its `then` branch is where `x` is null, not the else). Each narrowing is
            // scoped to exactly that one block via `checkBlock`'s `beforeCheck` hook -- it can
            // never leak into the sibling branch or outlive the `if`, since it's declared into a
            // frame that block's own `env.pop()` discards.
            val narrow = nullCheckedIdentName(stmt.cond)
            val thenState = checkBlock(stmt.thenB, env, m0, retTy, beforeCheck = {
                if (narrow != null && narrow.second) narrowNonNull(env, narrow.first)
            })
            val elseState = stmt.elseB?.let {
                checkBlock(it, env, m0, retTy, beforeCheck = {
                    if (narrow != null && !narrow.second) narrowNonNull(env, narrow.first)
                })
            } ?: m0
            val merged = HashMap<String, Boolean>()
            for (k in m0.keys) merged[k] = (thenState[k] ?: false) || (elseState[k] ?: false)
            // `if x == null { <always exits> }`, no `else` -- the only way execution reaches
            // past this statement is `x` being non-null, so narrow it in the enclosing scope
            // for every statement after this one (re-`declare`ing into the same `Env` frame the
            // original `let`/`var`/param lives in). Deliberately narrow, not general: only this
            // exact guard-clause shape is recognized, not arbitrary reachability analysis.
            if (stmt.elseB == null && narrow != null && !narrow.second && blockAlwaysExits(stmt.thenB)) {
                narrowNonNull(env, narrow.first)
            }
            merged
        }
        is Stmt.While -> {
            val (condTy, m0) = checkExpr(stmt.cond, env, moved, consume = true)
            if (condTy != Ty.Bool_) errors += "while condition must be Bool, got ${condTy}"
            val afterBody = checkBlock(stmt.body, env, m0, retTy)
            val merged = HashMap<String, Boolean>()
            for (k in m0.keys) merged[k] = (m0[k] ?: false) || (afterBody[k] ?: false)
            merged
        }
        is Stmt.For -> checkFor(stmt, env, moved, retTy)
        is Stmt.Match -> checkMatch(stmt.scrutinee, stmt.arms, stmt.line, env, moved, retTy)
        is Stmt.Return -> {
            if (stmt.expr == null) {
                if (retTy != Ty.Unit_) errors += "Line ${stmt.line}: expected return value of type ${retTy}"
                stmt.varsToDropBeforeReturn = dropScopeStack.asReversed().flatMap { it.asReversed().filter { n -> moved[n] != true } }
                moved
            } else {
                val (ty, m) = checkExpr(stmt.expr, env, moved, consume = true, expectedTy = retTy)
                if (!tyCompatible(ty, retTy)) errors += "Line ${stmt.line}: return type mismatch, expected ${retTy} got ${ty}"
                stmt.varsToDropBeforeReturn = dropScopeStack.asReversed().flatMap { it.asReversed().filter { n -> m[n] != true } }
                m
            }
        }
        is Stmt.Nested -> checkBlock(stmt.block, env, moved, retTy)
        is Stmt.Try -> checkTry(stmt, env, moved, retTy)
        is Stmt.Throw -> {
            val (ty, m) = checkExpr(stmt.expr, env, moved, consume = true)
            if (ty !is Ty.JavaExtern) {
                errors += "Line ${stmt.line}: 'throw' needs a real JVM exception value (an 'extern class' type), got ${ty}"
            }
            m
        }
    }

    // A thrown exception can interrupt the try block at any point, so each catch body is
    // checked from the *pre-try* moved-state, not the state after the try block ran to
    // completion -- assuming the latter would let a catch see something as still-unmoved (or
    // moved) based on code that might never have actually executed before the throw happened.
    // Final moved-state merges conservatively across the try block and every catch, same
    // OR-merge `checkStmt`'s `If` case already uses for its branches.
    private fun checkTry(stmt: Stmt.Try, env: Env, moved: Map<String, Boolean>, retTy: Ty): Map<String, Boolean> {
        val tryState = checkBlock(stmt.tryBlock, env, moved, retTy)
        val merged = HashMap(tryState)
        for (c in stmt.catches) {
            val excTy = resolveType(c.exceptionType)
            if (excTy !is Ty.JavaExtern) {
                errors += "Line ${c.line}: 'catch' needs a real JVM exception type (an 'extern class' type), got ${excTy}"
            }
            c.resolvedTy = excTy
            env.push()
            env.declare(c.varName, VarInfo(excTy, mutable = false, canMutateFields = false))
            val bodyMovedIn = HashMap(moved)
            bodyMovedIn[c.varName] = false
            val afterCatch = checkBlock(c.body, env, bodyMovedIn, retTy)
            env.pop()
            for (k in moved.keys) merged[k] = (merged[k] ?: false) || (afterCatch[k] ?: false)
        }
        return merged
    }

    // `for x in a..b { }` (Int, exclusive end) or `for x in arr { }` (element by element,
    // borrowing the array -- doesn't move it). Same conservative "body may run 0+ times"
    // merge as while: anything moved anywhere in the body counts as moved afterward.
    private fun checkFor(stmt: Stmt.For, env: Env, moved: Map<String, Boolean>, retTy: Ty): Map<String, Boolean> {
        val elemTy: Ty
        val m0: Map<String, Boolean>
        val iterable = stmt.iterable
        if (iterable is Expr.Range) {
            val (sTy, ms) = checkExpr(iterable.start, env, moved, consume = true)
            val (eTy, me) = checkExpr(iterable.end, env, ms, consume = true)
            if (sTy != Ty.Int_) errors += "Line ${stmt.line}: for-range start must be Int, got ${sTy}"
            if (eTy != Ty.Int_) errors += "Line ${stmt.line}: for-range end must be Int, got ${eTy}"
            elemTy = Ty.Int_
            m0 = me
        } else {
            val (arrTy, ma) = checkExpr(iterable, env, moved, consume = false)
            if (arrTy !is Ty.Array) {
                errors += "Line ${stmt.line}: 'for x in ...' needs a range (a..b) or an array, got ${arrTy}"
                elemTy = Ty.Unit_
            } else {
                elemTy = arrTy.elem
            }
            m0 = ma
        }
        env.push()
        env.declare(stmt.varName, VarInfo(elemTy, mutable = false, canMutateFields = false))
        val bodyMovedIn = HashMap(m0)
        bodyMovedIn[stmt.varName] = false
        val afterBody = checkBlock(stmt.body, env, bodyMovedIn, retTy)
        env.pop()
        val merged = HashMap<String, Boolean>()
        for (k in m0.keys) merged[k] = (m0[k] ?: false) || (afterBody[k] ?: false)
        return merged
    }

    // `match scrutinee { Variant { a, b } => { }, Other => { }, _ => { } }`. The scrutinee is
    // consumed (moved) -- fields get destructured into fresh bindings, mirroring Rust's default
    // `match x { }` semantics for a non-Copy `x`. Exhaustive unless a `_` wildcard is present;
    // moved-state merges conservatively across arms, same as if/else with N branches.
    private fun checkMatch(scrutinee: Expr, arms: List<MatchArm>, line: Int, env: Env, moved: Map<String, Boolean>, retTy: Ty): Map<String, Boolean> {
        val (scrutTy, m0) = checkExpr(scrutinee, env, moved, consume = true)
        if (scrutTy is Ty.Dyn) return checkSealedMatch(arms, line, scrutTy, env, m0, retTy)
        if (scrutTy !is Ty.Enum) {
            errors += "Line ${line}: match requires an enum value or a &dyn sealed interface, got ${scrutTy}"
            for (arm in arms) checkBlock(arm.body, env, m0, retTy)
            return m0
        }
        val enumInfo = enums.getValue(scrutTy.name)
        val covered = mutableSetOf<String>()
        var hasWildcard = false
        val armStates = mutableListOf<Map<String, Boolean>>()
        for (arm in arms) {
            if (hasWildcard) errors += "Line ${arm.line}: unreachable arm after wildcard '_'"
            if (arm.variantName == null) {
                hasWildcard = true
                if (arm.bindings.isNotEmpty()) errors += "Line ${arm.line}: wildcard arm '_' can't bind fields"
                armStates += checkBlock(arm.body, env, m0, retTy)
                continue
            }
            val qualified = arm.variantName.split("::")
            val baseName = qualified[0]
            val variantName = if (qualified.size > 1) qualified[1] else arm.variantName

            val variant = enumInfo.variant(variantName)
            if (variant == null) {
                errors += "Line ${arm.line}: '${variantName}' is not a variant of '${scrutTy.name}'"
                continue
            }
            if (!covered.add(variantName)) errors += "Line ${arm.line}: duplicate arm for variant '${variantName}'"
            if (arm.bindings.size != variant.fields.size) {
                errors += "Line ${arm.line}: '${variantName}' has ${variant.fields.size} field(s), pattern binds ${arm.bindings.size}"
            }
            env.push()
            for ((bindName, field) in arm.bindings.zip(variant.fields)) {
                env.declare(bindName, VarInfo(field.second, mutable = false, canMutateFields = false))
            }
            val armMoved = HashMap(m0)
            for (b in arm.bindings) armMoved[b] = false
            val afterArm = checkBlock(arm.body, env, armMoved, retTy)
            env.pop()
            armStates += afterArm
        }
        if (!hasWildcard) {
            val missing = enumInfo.variantNames.toSet() - covered
            if (missing.isNotEmpty()) {
                errors += "Line ${line}: match on '${scrutTy.name}' isn't exhaustive, missing ${missing} (add arms or a '_' wildcard)"
            }
        }
        val merged = HashMap<String, Boolean>()
        for (k in m0.keys) merged[k] = armStates.any { it[k] == true }
        return merged
    }

    // `match d { Player { hp } => { }, Item { ... } => { }, _ => { } }` where `d: &dyn X` and
    // `X` is `sealed`. Arm patterns name concrete implementer structs (not enum variants);
    // dispatch is an `instanceof` chain in `impl` declaration order, not a tag compare -- see
    // genMatch. Bindings destructure the struct's own fields, same shape as enum matching.
    private fun checkSealedMatch(arms: List<MatchArm>, line: Int, scrutTy: Ty.Dyn, env: Env, m0: Map<String, Boolean>, retTy: Ty): Map<String, Boolean> {
        val iface = interfaces[scrutTy.interfaceName]
        if (iface == null || !iface.sealed) {
            errors += "Line ${line}: match over '&dyn ${scrutTy.interfaceName}' requires '${scrutTy.interfaceName}' to be a 'sealed interface'"
            for (arm in arms) checkBlock(arm.body, env, m0, retTy)
            return m0
        }
        val covered = mutableSetOf<String>()
        var hasWildcard = false
        val armStates = mutableListOf<Map<String, Boolean>>()
        for (arm in arms) {
            if (hasWildcard) errors += "Line ${arm.line}: unreachable arm after wildcard '_'"
            if (arm.variantName == null) {
                hasWildcard = true
                if (arm.bindings.isNotEmpty()) errors += "Line ${arm.line}: wildcard arm '_' can't bind fields"
                armStates += checkBlock(arm.body, env, m0, retTy)
                continue
            }
            val qualified = arm.variantName.split("::")
            val structName = if (qualified.size > 1) qualified[1] else arm.variantName

            if (structName !in iface.implementers) {
                errors += "Line ${arm.line}: '${structName}' doesn't implement sealed interface '${scrutTy.interfaceName}'"
                continue
            }
            if (!covered.add(structName)) errors += "Line ${arm.line}: duplicate arm for '${structName}'"
            val fields = structs[structName]?.fields.orEmpty()
            if (arm.bindings.size != fields.size) {
                errors += "Line ${arm.line}: '${structName}' has ${fields.size} field(s), pattern binds ${arm.bindings.size}"
            }
            env.push()
            for ((bindName, field) in arm.bindings.zip(fields)) {
                env.declare(bindName, VarInfo(field.second, mutable = false, canMutateFields = false))
            }
            val afterArm = checkBlock(arm.body, env, m0, retTy)
            env.pop()
            armStates += afterArm
        }
        if (!hasWildcard) {
            val missing = iface.implementers.toSet() - covered
            if (missing.isNotEmpty()) {
                errors += "Line ${line}: match on '&dyn ${scrutTy.interfaceName}' isn't exhaustive, missing ${missing} (add arms or a '_' wildcard)"
            }
        }
        val merged = HashMap<String, Boolean>()
        for (k in m0.keys) merged[k] = armStates.any { it[k] == true }
        return merged
    }

    // A branch/arm body used as a value must be exactly one bare-expression statement -- see
    // `Expr.If`'s doc for why (no full Rust-style block-tail-value grammar, deliberately). Runs
    // the normal `checkBlock` over it regardless (for move/drop bookkeeping) before validating
    // the shape, so a badly-shaped branch still gets its contents checked instead of skipped.
    // `Ty.Unit_` stands in for `retTy`: safe because a single `Stmt.ExprStmt` can never be a
    // `Stmt.Return`, so `checkBlock`'s `retTy` is provably never consulted for this call.
    private fun checkValueBlock(block: Block, env: Env, moved: Map<String, Boolean>, line: Int, label: String): Triple<Ty, Expr?, Map<String, Boolean>> {
        val after = checkBlock(block, env, moved, Ty.Unit_)
        val sole = block.stmts.singleOrNull()
        if (sole !is Stmt.ExprStmt) {
            errors += "Line ${line}: ${label} must be exactly one expression (got ${block.stmts.size} statement(s))"
            return Triple(Ty.Unit_, null, after)
        }
        return Triple(sole.expr.ty ?: Ty.Unit_, sole.expr, after)
    }

    private fun checkIfExpr(expr: Expr.If, env: Env, moved: Map<String, Boolean>): Pair<Ty, Map<String, Boolean>> {
        val (condTy, m0) = checkExpr(expr.cond, env, moved, consume = true)
        if (condTy != Ty.Bool_) errors += "Line ${expr.line}: if-expression condition must be Bool, got ${condTy}"
        val (thenTy, _, thenState) = checkValueBlock(expr.thenB, env, m0, expr.line, "if-expression's 'then' branch")
        val (elseTy, _, elseState) = checkValueBlock(expr.elseB, env, m0, expr.line, "if-expression's 'else' branch")
        if (thenTy != elseTy) {
            errors += "Line ${expr.line}: if-expression branches have incompatible types (${thenTy} vs ${elseTy})"
        }
        val merged = HashMap<String, Boolean>()
        for (k in m0.keys) merged[k] = (thenState[k] ?: false) || (elseState[k] ?: false)
        return thenTy to merged
    }

    private fun checkMatchExpr(expr: Expr.Match, env: Env, moved: Map<String, Boolean>): Pair<Ty, Map<String, Boolean>> {
        val merged = checkMatch(expr.scrutinee, expr.arms, expr.line, env, moved, Ty.Unit_)
        val armTypes = expr.arms.map { arm ->
            val sole = arm.body.stmts.singleOrNull()
            if (sole !is Stmt.ExprStmt) {
                errors += "Line ${arm.line}: match-expression arm must be exactly one expression (got ${arm.body.stmts.size} statement(s))"
                Ty.Unit_
            } else {
                sole.expr.ty ?: Ty.Unit_
            }
        }
        val ty = armTypes.firstOrNull() ?: Ty.Unit_
        if (armTypes.any { it != ty }) {
            errors += "Line ${expr.line}: match-expression arms have incompatible types (${armTypes.distinct()})"
        }
        return ty to merged
    }

    // Returns (type-of-expr, moved-state-after-evaluating-expr). `consume` marks any
    // struct-typed Ident read here as moved; pass false when reading under a Borrow.
    // `expectedTy`: an optional hint, used *only* to resolve a bare generic unit variant
    // (e.g. `None`) that has no fields to infer its type argument(s) from -- threaded in from
    // the few call sites that know a target type ahead of checking (a `return` against the
    // function's declared return type, a `let` against its declared type). Every other
    // expression kind ignores it; recursive checkExpr calls default it to null.
    private fun checkExpr(
        expr: Expr,
        env: Env,
        moved: Map<String, Boolean>,
        consume: Boolean,
        expectedTy: Ty? = null,
    ): Pair<Ty, Map<String, Boolean>> {
        val result: Pair<Ty, Map<String, Boolean>> = when (expr) {
            is Expr.IntLit -> Ty.Int_ to moved
            is Expr.LongLit -> Ty.Long_ to moved
            is Expr.FloatLit -> Ty.Float_ to moved
            is Expr.DoubleLit -> Ty.Double_ to moved
            is Expr.StringLit -> Ty.Str_() to moved
            is Expr.BoolLit -> Ty.Bool_ to moved
            // Two legal shapes: a direct operand of `==`/`!=` (handled entirely inside the
            // `Expr.Binary` case below, which checks for this before recursing normally), or
            // *constructing* a nullable value in a position whose expected type is already
            // known to be a nullable extern type -- `return null;` against a `Type?`-returning
            // function, `let x: Type? = null;` -- mirroring the same `expectedTy`-hint
            // mechanism bare `None` already needed for generic unit-variant construction.
            // Anywhere else, `Ty.Unit_` turns a bare `null` into an ordinary type-mismatch
            // error at whatever expected the real type instead.
            is Expr.NullLit -> {
                if (expectedTy is Ty.JavaExtern && expectedTy.nullable) expectedTy to moved
                else if (expectedTy is Ty.Str_ && expectedTy.nullable) expectedTy to moved
                else Ty.Unit_ to moved
            }
            is Expr.Ident -> {
                val info = env.lookup(expr.name)
                // If this read resolves outside the innermost open lambda's own param scope,
                // it's a capture -- record its type so checkLambda/codegen know to synthesize a
                // field for it. `statics` are never captured this way: codegen reaches them via
                // a fixed GETSTATIC regardless of lexical position, same as any other read.
                if (info != null && captureStack.isNotEmpty() && !statics.containsKey(expr.name)) {
                    val (_, depth) = env.lookupWithDepth(expr.name)!!
                    // Record on EVERY currently-open lambda frame this read is declared outside
                    // of, not just the innermost -- a nested lambda (see checkLambda's own doc:
                    // supported, e.g. a Supplier lambda whose body itself passes an icon/
                    // displayItems lambda to a builder call) capturing something declared
                    // outside an OUTER lambda too needs that value threaded through the outer
                    // lambda's own capture (and constructor call) to ever reach the inner one's
                    // -- exactly like passing a value down through an ordinary nested function
                    // call chain. Each frame's own `first` (its param-scope depth) is
                    // independent, so no early-exit is needed or correct here.
                    for (frame in captureStack) {
                        if (depth < frame.first) frame.second[expr.name] = info.ty
                    }
                }
                if (info == null) {
                    val enumName = enumVariantOwner[expr.name]
                    if (enumName != null && enums.containsKey(enumName)) {
                        val variant = enums.getValue(enumName).variant(expr.name)!!
                        if (variant.fields.isEmpty()) {
                            // A unit variant used bare, e.g. `Point` (no `{ }`) -- construction, not a variable read.
                            expr.resolvedName = enumName
                            Ty.Enum(enumName) to moved
                        } else {
                            errors += "Line ${expr.line}: variant '${expr.name}' has fields -- construct it with `${expr.name} { ... }`"
                            Ty.Unit_ to moved
                        }
                    } else if (enumName != null && genericEnumTemplates.containsKey(enumName)) {
                        val template = genericEnumTemplates.getValue(enumName)
                        val templateVariant = template.variants.first { it.name == expr.name }
                        if (templateVariant.fields.isNotEmpty()) {
                            errors += "Line ${expr.line}: variant '${expr.name}' has fields -- construct it with `${expr.name} { ... }`"
                            Ty.Unit_ to moved
                        } else if (expectedTy is Ty.Enum && enumInstanceTemplate[expectedTy.name] == enumName) {
                            // No fields to infer T from -- only resolvable because the caller
                            // (return/let) already told us the concrete instantiation it wants.
                            val typeArgs = enumInstanceArgs.getValue(expectedTy.name)
                            val info = getOrInstantiateEnum(template, typeArgs, expr.line)
                            expr.resolvedName = info.name
                            Ty.Enum(info.name) to moved
                        } else {
                            errors += "Line ${expr.line}: cannot infer type argument(s) for generic unit variant '${expr.name}' " +
                                    "-- use it somewhere the target type is already known (a typed 'let', or a function's declared return type)"
                            Ty.Unit_ to moved
                        }
                    } else {
                        errors += "Line ${expr.line}: unknown variable '${expr.name}'"
                        Ty.Unit_ to moved
                    }
                } else if (statics.containsKey(expr.name)) {
                    // A `static` is never "moved" -- there's no scope for the value to be used
                    // up by, so reading it here must never poison a later read elsewhere.
                    info.ty to moved
                } else {
                    if (moved[expr.name] == true) {
                        errors += "Line ${expr.line}: use of moved value '${expr.name}'"
                    }
                    val out = if (consume && !info.ty.isCopy()) {
                        val m = HashMap(moved); m[expr.name] = true; m
                    } else moved
                    info.ty to out
                }
            }
            is Expr.Borrow -> checkExpr(expr.inner, env, moved, consume = false)
            is Expr.Unary -> {
                val (ty, m) = checkExpr(expr.expr, env, moved, consume = true)
                when (expr.op) {
                    "!" -> { if (ty != Ty.Bool_) errors += "Line ${expr.line}: '!' requires Bool"; Ty.Bool_ to m }
                    "-" -> {
                        if (!isNumeric(ty)) errors += "Line ${expr.line}: unary '-' requires Int, Long, Float, or Double"
                        ty to m
                    }
                    else -> Ty.Unit_ to m
                }
            }
            is Expr.Cast -> {
                val (innerTy, m) = checkExpr(expr.inner, env, moved, consume = true)
                val targetTy = resolveType(expr.target)
                // Two, and only two, kinds of `as`: numeric widening/narrowing between
                // Int/Long/Float/Double (existing), or a reference-type cast between two
                // object-reference types (Str_/Struct/Enum/Dyn/JavaExtern/Array), compiling to
                // a real `CHECKCAST` -- the actual bridge from a Java generic API's erased
                // `Object` (a `List<Component>.add(Object)`, a `Supplier<T>.get(): Object`,
                // anything erasure touches) back to a concrete, usable type. Trusted like every
                // other `extern` interaction in this language: a wrong cast type-checks fine and
                // throws a real `ClassCastException` at runtime, not a compile error -- the
                // compiler has no classfile-level hierarchy info to verify it against, same
                // "honesty" tradeoff already documented for `extern class` itself.
                val bothNumeric = isNumeric(innerTy) && isNumeric(targetTy)
                val bothObjectRef = innerTy.isObjectRef() && targetTy.isObjectRef()
                if (!bothNumeric && !bothObjectRef) {
                    errors += "Line ${expr.line}: 'as' only supports numeric conversions (Int/Long/Float/Double) or reference-type casts, got ${innerTy} as ${targetTy}"
                }
                targetTy to m
            }
            is Expr.InstanceOf -> {
                // Deliberately `consume = false` -- a runtime type check is a peek at the value,
                // not a move (same reasoning as `x == null`'s non-consuming borrow): the checked
                // expression needs to stay usable right after, e.g. `if (x is Foo) { x.bar(); }`.
                val (innerTy, m) = checkExpr(expr.inner, env, moved, consume = false)
                val targetTy = resolveType(expr.target)
                if (!innerTy.isObjectRef() || !targetTy.isObjectRef()) {
                    errors += "Line ${expr.line}: 'is' only supports reference types (Struct/Enum/Dyn/JavaExtern/Array/String), got ${innerTy} is ${targetTy}"
                }
                expr.resolvedTargetTy = targetTy
                Ty.Bool_ to m
            }
            is Expr.Binary -> if (expr.left is Expr.NullLit || expr.right is Expr.NullLit) {
                // `x == null` / `null == x` -- deliberately NOT `consume = true`: comparing
                // against null is a peek, not a move (same as reading through a `&` borrow), so
                // `x` stays usable afterward exactly the way the guard-clause idiom needs it to
                // (`if x == null { return; } ... use x ...`). Also deliberately not routed
                // through the ordinary `lty == rty` equality check below: `null` itself resolves
                // to `Ty.Unit_`, which would never equal a real `Ty.JavaExtern` anyway.
                if (expr.op != "==" && expr.op != "!=") {
                    errors += "Line ${expr.line}: 'null' can only be used with '==' or '!='"
                }
                val other = if (expr.left is Expr.NullLit) expr.right else expr.left
                val (otherTy, m1) = checkExpr(other, env, moved, consume = false)
                if (otherTy !is Ty.JavaExtern && otherTy !is Ty.Str_) {
                    errors += "Line ${expr.line}: 'null' can only be compared against an extern class value or a String, got ${otherTy}"
                }
                Ty.Bool_ to m1
            } else {
                val (lty, m1) = checkExpr(expr.left, env, moved, consume = true)
                val (rty, m2) = checkExpr(expr.right, env, m1, consume = true)
                val ty = when (expr.op) {
                    "+" -> when {
                        isNumeric(lty) && lty == rty -> lty
                        lty == Ty.Str_() && rty == Ty.Str_() -> Ty.Str_()
                        else -> { errors += "Line ${expr.line}: '+' needs matching Int/Long/Float/Double operands or String+String, got ${lty} + ${rty}"; Ty.Int_ }
                    }
                    "-", "*", "/", "%" -> {
                        if (!isNumeric(lty) || lty != rty) {
                            errors += "Line ${expr.line}: '${expr.op}' requires matching Int, Long, Float, or Double operands, got ${lty} ${expr.op} ${rty}"
                        }
                        if (isNumeric(lty)) lty else Ty.Int_
                    }
                    "==", "!=" -> {
                        if (lty != rty) errors += "Line ${expr.line}: cannot compare ${lty} with ${rty}"
                        Ty.Bool_
                    }
                    "&&", "||" -> {
                        if (lty != Ty.Bool_ || rty != Ty.Bool_) errors += "Line ${expr.line}: '${expr.op}' requires Bool operands, got ${lty} ${expr.op} ${rty}"
                        Ty.Bool_
                    }
                    "<", "<=", ">", ">=" -> {
                        if (!isNumeric(lty) || lty != rty) {
                            errors += "Line ${expr.line}: '${expr.op}' requires matching Int, Long, Float, or Double operands, got ${lty} ${expr.op} ${rty}"
                        }
                        Ty.Bool_
                    }
                    else -> Ty.Unit_
                }
                ty to m2
            }
            is Expr.Assign -> {
                val info = env.lookup(expr.name)
                val (vty, m) = checkExpr(expr.value, env, moved, consume = true)
                if (info == null) {
                    errors += "Line ${expr.line}: unknown variable '${expr.name}'"
                } else {
                    if (!info.mutable) errors += "Line ${expr.line}: cannot assign to immutable variable '${expr.name}' (use 'var')"
                    if (info.ty != vty) errors += "Line ${expr.line}: cannot assign ${vty} to '${expr.name}' of type ${info.ty}"
                }
                val out = HashMap(m); out[expr.name] = false
                (info?.ty ?: Ty.Unit_) to out
            }
            is Expr.FieldAccess -> checkFieldAccess(expr, env, moved)
            is Expr.Call -> checkCall(expr, env, moved)
            is Expr.StructLit -> checkStructLit(expr, env, moved, expectedTy)
            is Expr.MethodCall -> checkMethodCall(expr, env, moved)
            is Expr.StaticCall -> checkStaticCall(expr, env, moved)
            is Expr.StaticFieldGet -> checkStaticFieldGet(expr, moved)
            is Expr.FieldAssign -> checkFieldAssign(expr, env, moved)
            is Expr.ArrayLit -> checkArrayLit(expr, env, moved)
            is Expr.ArrayRepeat -> checkArrayRepeat(expr, env, moved)
            is Expr.Index -> checkIndex(expr, env, moved)
            is Expr.IndexAssign -> checkIndexAssign(expr, env, moved)
            is Expr.ArenaNew -> checkArenaNew(expr, env, moved)
            is Expr.StringInterp -> checkStringInterp(expr, env, moved)
            is Expr.Range -> {
                errors += "Line ${expr.line}: a range (a..b) can only appear directly in 'for x in a..b { }'"
                Ty.Unit_ to moved
            }
            is Expr.If -> checkIfExpr(expr, env, moved)
            is Expr.Match -> checkMatchExpr(expr, env, moved)
            is Expr.Lambda -> checkLambda(expr, env, moved, expectedTy)
            is Expr.ClassLit -> checkClassLit(expr, moved)
        }
        expr.ty = result.first
        return result
    }

    // `|params| body` -- only ever resolvable when `expectedTy` (threaded in from the call
    // argument position it appeared in -- see checkMethodCall/checkStaticCall's Lambda
    // special-casing) is a `Ty.Dyn` naming a declared interface with exactly one method AND a
    // real `extern interface` binary name to implement (see the class-level doc on Expr.Lambda
    // for why "single-method extern interface" is this language's whole functional-interface
    // story). Anywhere else -- no expected type, an expected type that isn't `Ty.Dyn`, a
    // multi-method interface, a native (non-extern) HC interface -- is a clear compile error,
    // not silent inference failure: there is no fallback target-type source (no declared lambda
    // type syntax exists) for this to fall back to.
    private fun checkLambda(expr: Expr.Lambda, env: Env, moved: Map<String, Boolean>, expectedTy: Ty?): Pair<Ty, Map<String, Boolean>> {
        if (expectedTy !is Ty.Dyn) {
            errors += "Line ${expr.line}: cannot infer a target type for this lambda -- use it directly as an argument whose declared type is a single-method 'extern interface' (&dyn SomeInterface)"
            return Ty.Unit_ to moved
        }
        val iface = interfaces[expectedTy.interfaceName]
        if (iface == null) {
            errors += "Line ${expr.line}: unknown interface '${expectedTy.interfaceName}'"
            return Ty.Unit_ to moved
        }
        if (iface.methods.size != 1) {
            errors += "Line ${expr.line}: '${expectedTy.interfaceName}' has ${iface.methods.size} methods -- a lambda can only target a single-method (functional) interface"
            return Ty.Unit_ to moved
        }
        val binaryName = program.interfaces.firstOrNull { it.name == expectedTy.interfaceName }?.externBinaryName
        if (binaryName == null) {
            errors += "Line ${expr.line}: a lambda can only target an 'extern interface' (a real JVM interface) -- '${expectedTy.interfaceName}' is a native Hot Chocolate interface, implement it with a real 'impl' block instead"
            return Ty.Unit_ to moved
        }
        val (methodName, msig) = iface.methods.entries.first()
        if (msig.params.size != expr.params.size) {
            errors += "Line ${expr.line}: lambda has ${expr.params.size} param(s), '${expectedTy.interfaceName}::${methodName}' expects ${msig.params.size}"
            return Ty.Unit_ to moved
        }
        env.push()
        for (i in expr.params.indices) {
            env.declare(expr.params[i], VarInfo(msig.paramTys[i], mutable = false, canMutateFields = false))
        }
        // The scope INDEX the lambda's own params live in (env.depth() - 1, not env.depth()
        // itself -- push() already created that scope, so depth() counts it; a param declared
        // into it resolves at exactly that index via lookupWithDepth, not one past it). Getting
        // this off by one would make every lambda incorrectly capture its own params as fields.
        val lambdaDepth = env.depth() - 1
        captureStack.addLast(lambdaDepth to LinkedHashMap())
        val (bodyTy, m2raw) = checkExpr(expr.body, env, moved, consume = true, expectedTy = msig.ret)
        val captured = captureStack.removeLast().second
        env.pop()
        // Same unwind `checkBlock` already does for an ordinary nested scope: strip out any
        // entry the lambda's OWN params (or anything else scoped to just this lambda body)
        // added, keeping only keys the CALLER's own `moved` map already had. Without this, a
        // sibling lambda literal reusing the same param name (`|s| ...` used twice in a row, a
        // Predicate arg being a real, common shape) sees a stale "moved" entry left over from
        // the FIRST lambda's own now-finished param scope and wrongly rejects its own fresh
        // same-named param as already used.
        val m2 = m2raw.filterKeys { moved.containsKey(it) }
        if (!tyCompatible(bodyTy, msig.ret)) {
            errors += "Line ${expr.line}: lambda body has type ${bodyTy}, '${expectedTy.interfaceName}::${methodName}' expects ${msig.ret}"
        }
        expr.targetInterfaceName = expectedTy.interfaceName
        expr.targetBinaryName = binaryName
        expr.targetMethodName = methodName
        expr.paramTys = msig.paramTys
        expr.retTy = msig.ret
        expr.captures = captured.map { (n, t) -> LambdaCapture(n, t) }
        expr.syntheticName = "Lambda\$${lambdaCounter++}"
        return expectedTy to m2
    }

    // `Type.class` -- `Type` names either a real `extern class`/`extern interface` alias (the
    // real binary name goes straight into `resolvedName`, `isExtern = true`) or one of this
    // compiler's own struct/enum declarations (bare name into `resolvedName`, `isExtern =
    // false`, letting CodeGen run it through its own module-qualifying `qualify()` -- see
    // Expr.ClassLit's own doc). Never a `Ty.Struct`/`Ty.Enum` *value*'s runtime class (there's
    // no `x.getClass()`-equivalent here) -- only ever a compile-time-known type name.
    private fun checkClassLit(expr: Expr.ClassLit, moved: Map<String, Boolean>): Pair<Ty, Map<String, Boolean>> {
        externClasses[expr.typeName]?.let {
            expr.resolvedName = it.binaryName
            expr.isExtern = true
            return Ty.JavaExtern("java/lang/Class") to moved
        }
        val externIfaceBinary = program.interfaces.firstOrNull { it.name == expr.typeName }?.externBinaryName
        if (externIfaceBinary != null) {
            expr.resolvedName = externIfaceBinary
            expr.isExtern = true
            return Ty.JavaExtern("java/lang/Class") to moved
        }
        if (structs.containsKey(expr.typeName) || enums.containsKey(expr.typeName)) {
            expr.resolvedName = expr.typeName
            expr.isExtern = false
            return Ty.JavaExtern("java/lang/Class") to moved
        }
        errors += "Line ${expr.line}: '${expr.typeName}.class' -- unknown type '${expr.typeName}'"
        return Ty.Unit_ to moved
    }

    private fun checkMethodCall(expr: Expr.MethodCall, env: Env, moved: Map<String, Boolean>): Pair<Ty, Map<String, Boolean>> {
        if (expr.method == "drop") {
            errors += "Line ${expr.line}: call the built-in drop(x) instead of x.drop() -- .drop() doesn't consume x, so calling it directly risks a double-drop"
            var m = checkExpr(expr.recv, env, moved, consume = false).second
            for (a in expr.args) m = checkExpr(a, env, m, consume = true).second
            return Ty.Unit_ to m
        }
        val (recvTy, m0) = checkExpr(expr.recv, env, moved, consume = false)
        var m = m0
        // A lambda literal's own type-checking needs the resolved call target's declared param
        // type (see checkLambda) -- not known yet at this point, before `sig` below exists --
        // so it's deliberately skipped here and checked later, once `sig` is available (see the
        // loop right before the final `sig.params.size != ...` validation below).
        for (a in expr.args) if (a !is Expr.Lambda) m = checkExpr(a, env, m, consume = true).second

        val sig: FnSig
        if (recvTy is Ty.Dyn) {
            // `x: &dyn Interface`: always dynamic dispatch (INVOKEINTERFACE) -- the concrete
            // implementer isn't known until runtime.
            val iface = interfaces[recvTy.interfaceName]
            val msig = iface?.methods?.get(expr.method)
            if (iface == null) {
                errors += "Line ${expr.line}: unknown interface '${recvTy}'"
                return Ty.Unit_ to m
            }
            if (msig == null) {
                // Last resort: an `extend ${recvTy.interfaceName} { }` block elsewhere may have
                // added this method (only possible if the interface is `open`).
                val extMangled = extensionFns[recvTy.interfaceName to expr.method]
                if (extMangled == null) {
                    errors += "Line ${expr.line}: '${recvTy}' has no method '${expr.method}'"
                    return Ty.Unit_ to m
                }
                sig = fns.getValue(extMangled)
                expr.resolvedName = extMangled
            } else {
                sig = FnSig(listOf(Param("self", TypeRef("Self", isRef = true, isMut = msig.selfIsMut))) + msig.params, listOf(recvTy) + msig.paramTys, msig.ret)
                expr.resolvedName = expr.method
                expr.dynamicOwner = recvTy.interfaceName
            }
        } else if (recvTy is Ty.Struct) {
            val ownerTemplateName = structInstanceTemplate[recvTy.name] ?: recvTy.name
            // Interface membership/overrides are tracked per concrete instantiation (recvTy.name
            // -- e.g. "Box_Int"), NOT per generic template ("Box"): each instantiation gets its
            // own monomorphized instance methods, same as everything else in this language.
            // `ownerTemplateName` is only for the plain-static-method fallback path below, which
            // (like all other generic methods) IS keyed by the template name.
            val concreteName = recvTy.name
            // A concretely-typed struct calling a method that happens to be part of an
            // interface it implements: still resolved at compile time (we know the exact
            // class), but the method lives as a real instance method on the struct's own
            // class (not our usual static-desugared one), so it's INVOKEVIRTUAL, not
            // INVOKESTATIC.
            val overrideKey = "$concreteName@${expr.method}"
            val hasExplicitOverride = fns.containsKey(overrideKey) && (
                    interfaceImplFns[concreteName]?.any { it.name == expr.method } == true ||
                            superclassOverrideFns[concreteName]?.any { it.name == expr.method } == true
                    )
            val candidates = structInterfaces[concreteName].orEmpty()
                .mapNotNull { ifaceName -> interfaces[ifaceName]?.methods?.get(expr.method)?.let { ifaceName to it } }
            if (hasExplicitOverride) {
                // An explicit override is unambiguous (one real instance method on this
                // struct's class) regardless of how many interfaces happen to share the name.
                sig = fns.getValue(overrideKey)
                expr.resolvedName = expr.method
                expr.instanceOwner = concreteName
            } else if (candidates.size > 1) {
                // Two *unrelated* interfaces both defaulting the same method name, with no
                // override to resolve it, isn't just ambiguous for type-checking purposes --
                // even with identical signatures, the struct's class has no single method to
                // link an INVOKEVIRTUAL against (real javac rejects this too: "inherits
                // unrelated defaults"). Same declaration reached via two supertrait paths never
                // reaches here as 2 candidates: `structInterfaces` is a set, so it only appears
                // once regardless of how many paths reach it.
                errors += "Line ${expr.line}: call to '${expr.method}' on '${ownerTemplateName}' is ambiguous between interfaces " +
                        "${candidates.map { it.first }} -- add an explicit override in an impl block to resolve it"
                return Ty.Unit_ to m
            } else if (candidates.isNotEmpty()) {
                val msig = candidates[0].second
                sig = FnSig(listOf(Param("self", TypeRef("Self", isRef = true, isMut = msig.selfIsMut))) + msig.params, listOf(recvTy) + msig.paramTys, msig.ret)
                expr.resolvedName = expr.method
                expr.instanceOwner = concreteName
            } else {
                val methodKey = "$ownerTemplateName\$${expr.method}"
                val argTys = listOf<Ty>(recvTy) + expr.args.map { it.ty ?: Ty.Unit_ }
                val genericTemplate = genericFnTemplates[methodKey]
                if (genericTemplate != null) {
                    val paramTypes = genericTemplate.params.map { it.type }
                    val typeArgs = inferTypeArgs(genericTemplate.typeParams, paramTypes, argTys, expr.line, "method '${expr.method}'")
                        ?: return Ty.Unit_ to m
                    sig = getOrInstantiateFn(genericTemplate, typeArgs, expr.line)
                    expr.resolvedName = mangle(methodKey, typeArgs)
                } else {
                    val found = fns[methodKey]
                    if (found != null) {
                        sig = found
                        expr.resolvedName = methodKey
                    } else {
                        // Last resort: an `extend ${ownerTemplateName} { }` (struct-direct)
                        // block, or an `extend SomeOpenInterface { }` block reached through one
                        // of this struct's own implemented interfaces, may have added this
                        // method elsewhere. (Codegen needs no special handling for the
                        // interface case: the desugared extension's `self` param is typed `&dyn
                        // Interface`, and passing this concretely-typed receiver there is
                        // ordinary reference-widening -- ­valid on the JVM exactly like passing
                        // a String where an Object param is expected.)
                        val ifaceExtension = structInterfaces[concreteName].orEmpty()
                            .firstNotNullOfOrNull { ifaceName -> extensionFns[ifaceName to expr.method] }
                        val extMangled = extensionFns[ownerTemplateName to expr.method] ?: ifaceExtension
                        if (extMangled == null) {
                            errors += "Line ${expr.line}: struct '${ownerTemplateName}' has no method '${expr.method}'"
                            return Ty.Unit_ to m
                        }
                        sig = fns.getValue(extMangled)
                        expr.resolvedName = extMangled
                    }
                }
            }
        } else if (recvTy is Ty.JavaExtern) {
            if (recvTy.nullable) {
                errors += "Line ${expr.line}: cannot call '${expr.method}' on a possibly-null value -- check `== null`/`!= null` first"
            }
            val ext = externClasses.values.firstOrNull { it.binaryName == recvTy.binaryName }
            val candidates = ext?.let { resolveExternMethods(it, expr.method, expr.line) }?.filter { !it.isCtor }.orEmpty()
            if (ext == null || candidates.isEmpty()) {
                // A lazy extern already reports its own precise error (missing classpath, or no
                // such member) from resolveExternMethods above -- don't pile a generic one on top.
                if (ext == null || !ext.lazy) errors += "Line ${expr.line}: '${recvTy}' has no method '${expr.method}'"
                return Ty.Unit_ to m
            }
            val em = pickCandidate(candidates, expr.args.size)
            sig = FnSig(listOf(Param("self", TypeRef(ext.name, isRef = true))) + em.params.indices.map { i -> Param("_", TypeRef("Unit", isRef = em.paramIsRef.getOrElse(i) { false }, isMut = em.paramIsMut.getOrElse(i) { false })) }, listOf(recvTy) + em.params, em.retType)
            expr.resolvedName = expr.method
            expr.externOwner = recvTy.binaryName
            expr.externParamTys = em.params
            expr.isStaticExtern = em.isStatic
            expr.isExternInterface = ext.isInterface
        } else if (recvTy is Ty.Str_) {
            // A native HC `String` (`Ty.Str_`) and an `extern class Foo = "java.lang.String"
            // { ... }` declaration are the exact same JVM type underneath -- both compile to
            // descriptor `Ljava/lang/String;` -- so a value the checker calls `Str_` is free to
            // call any method some `extern class` in this program happened to declare against
            // that binary name, with zero codegen changes (genMethodCall's `externOwner != null`
            // path doesn't care what static HC type the checker called the receiver; it only
            // ever emits the receiver's real bytecode value). This is the actual bridge from
            // native strings to things like `.split()`/`.charAt()`/`.trim()` that a bare `Ty.Str_`
            // has no methods of its own for -- see the README's "String instance methods" note.
            if (recvTy.nullable) {
                errors += "Line ${expr.line}: cannot call '${expr.method}' on a possibly-null value -- check `== null`/`!= null` first"
            }
            val ext = externClasses.values.firstOrNull { it.binaryName == "java/lang/String" }
            val candidates = ext?.let { resolveExternMethods(it, expr.method, expr.line) }?.filter { !it.isCtor }.orEmpty()
            if (ext == null || candidates.isEmpty()) {
                errors += "Line ${expr.line}: 'String' has no method '${expr.method}' -- declare it on an 'extern class ... = \"java.lang.String\"' first"
                return Ty.Unit_ to m
            }
            val em = pickCandidate(candidates, expr.args.size)
            sig = FnSig(listOf(Param("self", TypeRef(ext.name, isRef = true))) + em.params.map { Param("_", TypeRef("Unit", false)) }, listOf(recvTy) + em.params, em.retType)
            expr.resolvedName = expr.method
            expr.externOwner = "java/lang/String"
            expr.externParamTys = em.params
            expr.isStaticExtern = em.isStatic
            expr.isExternInterface = ext.isInterface
        } else {
            errors += "Line ${expr.line}: method call on non-struct type ${recvTy}"
            return Ty.Unit_ to m
        }

        if (sig.params.size != expr.args.size + 1) {
            errors += "Line ${expr.line}: method '${expr.method}' expects ${sig.params.size - 1} args, got ${expr.args.size}"
        }
        val selfParam = sig.params.getOrNull(0)
        val recvIdent = expr.recv as? Expr.Ident
        if (selfParam?.type?.isRef == false) {
            // Owned self: moves the receiver, if it's a plain named variable -- unless it's a
            // `static`, which is never moved (see checkExpr's Ident case).
            if (recvIdent != null && !statics.containsKey(recvIdent.name)) {
                val mm = HashMap(m); mm[recvIdent.name] = true; m = mm
            }
        } else if (selfParam?.type?.isMut == true && recvIdent != null) {
            // canMutateFields covers both a `var` owned local and an already-&mut-borrowed
            // param/self -- either is a valid receiver for a &mut self method.
            val info = env.lookup(recvIdent.name)
            if (info != null && !info.canMutateFields) {
                errors += "Line ${expr.line}: cannot call '&mut self' method '${expr.method}' on '${recvIdent.name}': not declared 'var' and not received as '&mut'"
            }
        }
        val borrows = mutableListOf<Pair<String, Boolean>>()
        if (selfParam?.type?.isRef == true && recvIdent != null) borrows += recvIdent.name to (selfParam.type.isMut)
        for (i in expr.args.indices) {
            val a = expr.args[i]
            val param = sig.params.getOrNull(i + 1)
            val expectRef = param?.type?.isRef ?: false
            if (a is Expr.Lambda) {
                // A lambda needs no explicit '&' -- it always constructs a brand-new object
                // reference at its use site, never an existing local that borrow-vs-own rules
                // would need to arbitrate (see Expr.Lambda's own doc).
                val (_, m2) = checkExpr(a, env, m, consume = true, expectedTy = sig.paramTys.getOrNull(i + 1))
                m = m2
            } else {
                if (expectRef && a !is Expr.Borrow) {
                    errors += "Line ${expr.line}: argument ${i + 1} to '${expr.method}' must be borrowed (use &${describeArg(a)})"
                }
                if (!expectRef && a is Expr.Borrow) {
                    errors += "Line ${expr.line}: argument ${i + 1} to '${expr.method}' takes ownership, don't borrow it"
                }
                if (a is Expr.Borrow) {
                    if (expectRef && a.isMut != param?.type?.isMut) {
                        errors += "Line ${expr.line}: argument ${i + 1} to '${expr.method}' expects ${if (param?.type?.isMut == true) "&mut" else "&"}, got ${if (a.isMut) "&mut" else "&"}"
                    }
                    if (a.isMut) checkMutBorrowTarget(a.inner, env, expr.line, "call to '${expr.method}'")
                    (a.inner as? Expr.Ident)?.let { borrows += it.name to a.isMut }
                }
            }
            val expectedTy = sig.paramTys.getOrNull(i + 1)
            if (expectedTy != null && !tyCompatible(a.ty!!, expectedTy)) {
                errors += "Line ${expr.line}: argument ${i + 1} to '${expr.method}' expects ${expectedTy}, got ${a.ty}"
            }
        }
        checkBorrowExclusivity(borrows, expr.line, "call to '${expr.method}'")
        return sig.ret to m
    }

    // `Alias::FIELD` -- a declared `extern class` static field read (GETSTATIC). Never a move
    // source: like a `static`, the same field can be read again by anyone else later, so `moved`
    // passes through unchanged.
    private fun checkStaticFieldGet(expr: Expr.StaticFieldGet, moved: Map<String, Boolean>): Pair<Ty, Map<String, Boolean>> {
        val ext = externClasses[expr.typeName]
        if (ext == null) {
            errors += "Line ${expr.line}: unknown extern class '${expr.typeName}'"
            return Ty.Unit_ to moved
        }
        val f = ext.field(expr.field)
        if (f == null) {
            errors += "Line ${expr.line}: '${expr.typeName}' has no declared static field '${expr.field}'"
            return Ty.Unit_ to moved
        }
        if (!f.isStatic) {
            errors += "Line ${expr.line}: '${expr.typeName}::${expr.field}' is declared as an instance field, not static -- read it via '<value>.${expr.field}' instead"
            return Ty.Unit_ to moved
        }
        expr.resolvedName = ext.binaryName
        return f.type to moved
    }

    private fun checkStaticCall(expr: Expr.StaticCall, env: Env, moved: Map<String, Boolean>): Pair<Ty, Map<String, Boolean>> {
        var m = moved
        // See checkMethodCall's identical comment -- a Lambda arg needs the resolved callee's
        // declared param type first, so it's checked later in each branch below instead of here.
        for (a in expr.args) if (a !is Expr.Lambda) m = checkExpr(a, env, m, consume = true).second

        val ext = externClasses[expr.typeName]
        if (ext != null) {
            val candidates = resolveExternMethods(ext, expr.method, expr.line).filter { it.isStatic || it.isCtor }
            if (candidates.isEmpty()) {
                if (!ext.lazy) errors += "Line ${expr.line}: '${expr.typeName}' has no static method '${expr.method}'"
                return Ty.Unit_ to m
            }
            val em = pickCandidate(candidates, expr.args.size)
            if (em.params.size != expr.args.size) {
                errors += "Line ${expr.line}: '${expr.typeName}::${expr.method}' expects ${em.params.size} args, got ${expr.args.size}"
            }
            for (i in expr.args.indices) {
                val a = expr.args[i]
                val expectedTy = em.params.getOrNull(i) ?: continue
                if (a is Expr.Lambda) { val (_, m2) = checkExpr(a, env, m, consume = true, expectedTy = expectedTy); m = m2 }
                val argTy = a.ty ?: continue
                if (!tyCompatible(argTy, expectedTy)) {
                    errors += "Line ${expr.line}: argument ${i + 1} to '${expr.typeName}::${expr.method}' expects ${expectedTy}, got ${argTy}"
                }
            }
            expr.resolvedName = ext.binaryName
            expr.externParamTys = em.params
            expr.isExternInterface = ext.isInterface
            val retTy = if (em.isCtor) Ty.JavaExtern(ext.binaryName) else em.retType
            return retTy to m
        }

        // `S::new(args)` for a `struct S extends C { }` -- not a user-written static method
        // (there's no `impl S { fn new(...) }` anywhere), auto-derived from `C`'s own declared
        // extern constructor: args get forwarded straight through to it (see genStruct's ctor
        // codegen), so the args this call needs to type-check are exactly `C::new`'s.
        val superAlias = structSuperclass[expr.typeName]
        if (superAlias != null && expr.method == "new") {
            val superExt = externClasses.getValue(superAlias)
            val candidates = resolveExternMethods(superExt, "new", expr.line).filter { it.isCtor }
            if (candidates.isEmpty()) {
                errors += "Line ${expr.line}: '${superAlias}' (extended by '${expr.typeName}') has no declared constructor"
                return Ty.Unit_ to m
            }
            val em = pickCandidate(candidates, expr.args.size)
            if (em.params.size != expr.args.size) {
                errors += "Line ${expr.line}: '${expr.typeName}::new' expects ${em.params.size} args, got ${expr.args.size}"
            }
            for (i in expr.args.indices) {
                val a = expr.args[i]
                val expectedTy = em.params.getOrNull(i) ?: continue
                if (a is Expr.Lambda) { val (_, m2) = checkExpr(a, env, m, consume = true, expectedTy = expectedTy); m = m2 }
                val argTy = a.ty ?: continue
                if (!tyCompatible(argTy, expectedTy)) {
                    errors += "Line ${expr.line}: argument ${i + 1} to '${expr.typeName}::new' expects ${expectedTy}, got ${argTy}"
                }
            }
            expr.resolvedName = expr.typeName
            expr.externParamTys = em.params
            return Ty.Struct(expr.typeName) to m
        }

        // Native Hot Chocolate static call (on a struct)
        if (structs.containsKey(expr.typeName) || genericStructTemplates.containsKey(expr.typeName)) {
            // Static methods on native structs are just desugared to top-level fns with mangled names: Struct@method
            val mangled = "${expr.typeName}@${expr.method}"
            val sig = fns[mangled]
            if (sig == null) {
                // Try it as an inherent method call instead of a static call (it might have been declared as a normal method)
                val inherentMangled = "${expr.typeName}@${expr.method}"
                val inherentSig = fns[inherentMangled]
                if (inherentSig != null) {
                    errors += "Line ${expr.line}: method '${expr.method}' on struct '${expr.typeName}' is an instance method, call it on an instance (obj.${expr.method}) or declare it without 'self'"
                    return Ty.Unit_ to m
                }
                errors += "Line ${expr.line}: struct '${expr.typeName}' has no static method '${expr.method}'"
                return Ty.Unit_ to m
            }
            if (sig.params.size != expr.args.size) {
                errors += "Line ${expr.line}: '${expr.typeName}::${expr.method}' expects ${sig.params.size} args, got ${expr.args.size}"
            }
            for (i in expr.args.indices) {
                val a = expr.args[i]
                val expectedTy = sig.paramTys.getOrNull(i) ?: continue
                if (a is Expr.Lambda) { val (_, m2) = checkExpr(a, env, m, consume = true, expectedTy = expectedTy); m = m2 }
                val argTy = a.ty ?: continue
                if (!tyCompatible(argTy, expectedTy)) {
                    errors += "Line ${expr.line}: argument ${i + 1} to '${expr.typeName}::${expr.method}' expects ${expectedTy}, got ${argTy}"
                }
            }
            expr.resolvedName = mangled
            return sig.ret to m
        }

        errors += "Line ${expr.line}: unknown extern class or struct '${expr.typeName}'"
        return Ty.Unit_ to m
    }

    private fun checkStructLit(expr: Expr.StructLit, env: Env, moved: Map<String, Boolean>, expectedTy: Ty? = null): Pair<Ty, Map<String, Boolean>> {
        var m = moved
        for ((_, fexpr) in expr.fields) {
            val (_, m2) = checkExpr(fexpr, env, m, consume = true)
            m = m2
        }
        val fieldMap = expr.fields.toMap()

        val qualified = expr.typeName.split("::")
        val baseName = qualified[0]
        val variantName = if (qualified.size > 1) qualified[1] else expr.typeName

        val genericTemplate = genericStructTemplates[baseName]
        if (genericTemplate != null && qualified.size == 1) {
            val paramTypes = genericTemplate.fields.map { it.type }
            val fieldTys = genericTemplate.fields.map { fieldMap[it.name]?.ty ?: Ty.Unit_ }
            val typeArgs = inferTypeArgs(genericTemplate.typeParams, paramTypes, fieldTys, expr.line, "struct '${expr.typeName}'")
                ?: return Ty.Unit_ to m
            val given = expr.fields.map { it.first }.toSet()
            val expected = genericTemplate.fields.map { it.name }.toSet()
            if (given != expected) errors += "Line ${expr.line}: struct '${expr.typeName}' field mismatch, expected ${expected}"
            val info = getOrInstantiateStruct(genericTemplate, typeArgs)
            expr.resolvedName = info.name
            for ((fname, fexpr) in expr.fields) {
                val expectedTy_ = info.fieldType(fname)
                if (expectedTy_ != null && expectedTy_ != fexpr.ty) {
                    errors += "Line ${expr.line}: field '${fname}' expects ${expectedTy_}, got ${fexpr.ty}"
                }
            }
            return Ty.Struct(info.name) to m
        }

        val enumName = if (qualified.size > 1) baseName else enumVariantOwner[variantName]
        if (enumName != null) {
            val genericTemplate_ = genericEnumTemplates[enumName]
            if (genericTemplate_ != null) {
                val templateVariant = genericTemplate_.variants.firstOrNull { it.name == variantName }
                if (templateVariant == null) {
                    errors += "Line ${expr.line}: enum '${enumName}' has no variant '${variantName}'"
                    return Ty.Unit_ to m
                }
                val fieldMap_ = expr.fields.toMap()
                val paramTypes = templateVariant.fields.map { it.type }
                val argTys = templateVariant.fields.map { fieldMap_[it.name]?.ty ?: Ty.Unit_ }

                // `enumInstanceArgs`, not `structInstanceArgs` -- a separate map keyed by
                // mangled *enum* instantiation names (structs and enums are monomorphized
                // through parallel but distinct machinery). Using the wrong one here was a
                // real, pre-existing dormant bug: it happened to never matter before, because
                // bare zero-field variant construction (`None`) goes through `Expr.Ident`, a
                // completely different checker path that doesn't touch this fallback at all --
                // it only surfaced once a *non-zero-field* variant needed a still-unsolved type
                // param filled from context (`Result::Ok { value: n }` can infer `T` from
                // `value` but never `E`, since no `error`-typed value exists in an `Ok` at all).
                val expectedTypeArgs = if (expectedTy is Ty.Enum && expectedTy.name.startsWith(enumName + "_")) {
                    enumInstanceArgs[expectedTy.name]
                } else null
                val typeArgs = if (argTys.isEmpty() && expectedTy is Ty.Enum && expectedTy.name == enumName) {
                    emptyList() // not really possible for a generic enum, but for completeness
                } else {
                    inferTypeArgs(genericTemplate_.typeParams, paramTypes, argTys, expr.line, "enum '${enumName}'", expectedTypeArgs)
                }

                if (typeArgs == null) return Ty.Unit_ to m

                val given = expr.fields.map { it.first }.toSet()
                val expected = templateVariant.fields.map { it.name }.toSet()
                if (given != expected) errors += "Line ${expr.line}: variant '${variantName}' field mismatch, expected ${expected}"

                val info = getOrInstantiateEnum(genericTemplate_, typeArgs, expr.line)
                val variant = info.variant(variantName)!!
                for ((fname, fexpr) in expr.fields) {
                    val expectedTy_ = variant.fieldType(fname)
                    if (expectedTy_ != null && expectedTy_ != fexpr.ty) {
                        errors += "Line ${expr.line}: field '${fname}' expects ${expectedTy_}, got ${fexpr.ty}"
                    }
                }
                expr.resolvedName = info.name
                expr.enumVariant = variantName
                return Ty.Enum(info.name) to m
            }
            val variant = enums.getValue(enumName).variant(variantName)!!
            val given = expr.fields.map { it.first }.toSet()
            val expected = variant.fields.map { it.first }.toSet()
            if (given != expected) errors += "Line ${expr.line}: variant '${expr.typeName}' field mismatch, expected ${expected}"
            for ((fname, fexpr) in expr.fields) {
                val expectedTy_ = variant.fieldType(fname)
                if (expectedTy_ != null && expectedTy_ != fexpr.ty) {
                    errors += "Line ${expr.line}: field '${fname}' expects ${expectedTy_}, got ${fexpr.ty}"
                }
            }
            expr.resolvedName = enumName
            expr.enumVariant = variantName
            return Ty.Enum(enumName) to m
        }

        val info = structs[expr.typeName]
        if (info == null) {
            if (arenaLayouts.containsKey(expr.typeName)) {
                errors += "Line ${expr.line}: '${expr.typeName}' is an 'arena struct' -- allocate a buffer with `arena ${expr.typeName}[count]`, not a struct literal"
            } else {
                errors += "Line ${expr.line}: unknown struct '${expr.typeName}'"
            }
            return Ty.Unit_ to m
        }
        expr.resolvedName = info.name
        val given = expr.fields.map { it.first }.toSet()
        val expected = info.fields.map { it.first }.toSet()
        if (given != expected) errors += "Line ${expr.line}: struct '${expr.typeName}' field mismatch, expected ${expected}"
        for ((fname, fexpr) in expr.fields) {
            val expectedTy = info.fieldType(fname)
            if (expectedTy != null && expectedTy != fexpr.ty) {
                errors += "Line ${expr.line}: field '${fname}' expects ${expectedTy}, got ${fexpr.ty}"
            }
        }
        return Ty.Struct(info.name) to m
    }

    private fun checkCall(expr: Expr.Call, env: Env, moved: Map<String, Boolean>): Pair<Ty, Map<String, Boolean>> {
        if (expr.callee == "print") {
            if (expr.args.size != 1) errors += "Line ${expr.line}: print takes exactly 1 argument"
            var m = moved
            for (a in expr.args) {
                val (ty, m2) = checkExpr(a, env, m, consume = true)
                m = m2
                if (ty !is Ty.Int_ && ty !is Ty.Long_ && ty !is Ty.Float_ && ty !is Ty.Double_ && ty !is Ty.Bool_ && ty !is Ty.Str_) {
                    errors += "Line ${expr.line}: print only supports Int, Long, Float, Double, Bool, String (got ${ty})"
                } else if (ty is Ty.Str_ && ty.nullable) {
                    errors += "Line ${expr.line}: cannot print a possibly-null String -- check `== null`/`!= null` first"
                }
            }
            expr.resolvedName = "print"
            return Ty.Unit_ to m
        }
        if (expr.callee == "read_line") {
            if (expr.args.isNotEmpty()) errors += "Line ${expr.line}: read_line takes no arguments"
            expr.resolvedName = "read_line"
            // Honestly, `BufferedReader.readLine()` really can return null (at EOF) -- this
            // stays non-nullable for now to avoid a breaking change to every existing program
            // that already calls `read_line()` without narrowing (see IDEAS.md for the real
            // follow-up: making this `String?` once there's an appetite for fixing every
            // existing call site, not silently here).
            return Ty.Str_() to moved
        }
        if (expr.callee == "drop") {
            if (expr.args.size != 1) {
                errors += "Line ${expr.line}: drop takes exactly 1 argument"
                var m = moved
                for (a in expr.args) m = checkExpr(a, env, m, consume = true).second
                return Ty.Unit_ to m
            }
            val arg = expr.args[0]
            val (argTy, m) = checkExpr(arg, env, moved, consume = true) // consumes x: skips its own end-of-scope drop
            if (argTy !is Ty.Struct) {
                errors += "Line ${expr.line}: drop() requires a struct with a 'drop' method, got ${argTy}"
                return Ty.Unit_ to m
            }
            val ownerTemplateName = structInstanceTemplate[argTy.name] ?: argTy.name
            val methodKey = "$ownerTemplateName\$drop"
            val genericTemplate = genericFnTemplates[methodKey]
            if (genericTemplate != null) {
                val typeArgs = structInstanceArgs[argTy.name] ?: emptyList()
                getOrInstantiateFn(genericTemplate, typeArgs, expr.line)
                expr.resolvedName = mangle(methodKey, typeArgs)
            } else if (fns.containsKey(methodKey)) {
                expr.resolvedName = methodKey
            } else {
                errors += "Line ${expr.line}: '${argTy}' has no 'drop' method"
            }
            return Ty.Unit_ to m
        }

        // Evaluate all args first so their types are known for generic inference.
        var m = moved
        for (a in expr.args) m = checkExpr(a, env, m, consume = true).second

        val genericTemplate = genericFnTemplates[expr.callee]
        val sig: FnSig
        if (genericTemplate != null) {
            val paramTypes = genericTemplate.params.map { it.type }
            val argTys = expr.args.map { it.ty ?: Ty.Unit_ }
            val typeArgs = inferTypeArgs(genericTemplate.typeParams, paramTypes, argTys, expr.line, "'${expr.callee}'")
                ?: return Ty.Unit_ to m
            sig = getOrInstantiateFn(genericTemplate, typeArgs, expr.line)
            expr.resolvedName = mangle(expr.callee, typeArgs)
        } else {
            val found = fns[expr.callee]
            if (found == null) {
                errors += "Line ${expr.line}: unknown function '${expr.callee}'"
                return Ty.Unit_ to m
            }
            sig = found
            expr.resolvedName = expr.callee
        }

        if (sig.params.size != expr.args.size) {
            errors += "Line ${expr.line}: '${expr.callee}' expects ${sig.params.size} args, got ${expr.args.size}"
        }
        val borrows = mutableListOf<Pair<String, Boolean>>()
        for (i in expr.args.indices) {
            val a = expr.args[i]
            val param = sig.params.getOrNull(i)
            val expectRef = param?.type?.isRef ?: false
            if (expectRef && a !is Expr.Borrow) {
                errors += "Line ${expr.line}: argument ${i + 1} to '${expr.callee}' must be borrowed (use &${describeArg(a)})"
            }
            if (!expectRef && a is Expr.Borrow) {
                errors += "Line ${expr.line}: argument ${i + 1} to '${expr.callee}' takes ownership, don't borrow it"
            }
            if (a is Expr.Borrow) {
                if (expectRef && a.isMut != param?.type?.isMut) {
                    errors += "Line ${expr.line}: argument ${i + 1} to '${expr.callee}' expects ${if (param?.type?.isMut == true) "&mut" else "&"}, got ${if (a.isMut) "&mut" else "&"}"
                }
                if (a.isMut) checkMutBorrowTarget(a.inner, env, expr.line, "call to '${expr.callee}'")
                (a.inner as? Expr.Ident)?.let { borrows += it.name to a.isMut }
            }
            val expectedTy = sig.paramTys.getOrNull(i)
            if (expectedTy != null && !tyCompatible(a.ty!!, expectedTy)) {
                errors += "Line ${expr.line}: argument ${i + 1} to '${expr.callee}' expects ${expectedTy}, got ${a.ty}"
            }
        }
        checkBorrowExclusivity(borrows, expr.line, "call to '${expr.callee}'")
        return sig.ret to m
    }

    // Each embedded `{expr}` must be one of the stringifiable Copy types -- the same set `print`
    // already knows how to render. No user-defined `toString`/interpolation overloads exist in
    // this language, so anything else (a struct, an extern type) is a clear compile-time error
    // rather than silently printing something useless like a JVM default toString.
    private val STRINGIFIABLE = setOf(Ty.Int_, Ty.Long_, Ty.Float_, Ty.Double_, Ty.Bool_, Ty.Str_())

    private fun checkStringInterp(expr: Expr.StringInterp, env: Env, moved: Map<String, Boolean>): Pair<Ty, Map<String, Boolean>> {
        var m = moved
        for (e in expr.exprs) {
            val (ty, m2) = checkExpr(e, env, m, consume = true)
            if (ty !in STRINGIFIABLE) {
                // `ty is Ty.Str_ && ty.nullable` falls into this branch too (correctly) -- not
                // in STRINGIFIABLE (which only contains the non-nullable form), same "must
                // narrow first" discipline as print()/`+`.
                errors += "Line ${expr.line}: cannot interpolate ${ty} into a string -- only Int/Long/Float/Double/Bool/String are supported" +
                        if (ty is Ty.Str_ && ty.nullable) " (this String is possibly-null -- check `== null`/`!= null` first)" else ""
            }
            m = m2
        }
        return Ty.Str_() to m
    }

    private fun checkArrayLit(expr: Expr.ArrayLit, env: Env, moved: Map<String, Boolean>): Pair<Ty, Map<String, Boolean>> {
        if (expr.elements.isEmpty()) {
            errors += "Line ${expr.line}: empty array literal needs a type -- use `[value; 0]` instead"
            return Ty.Array(Ty.Unit_) to moved
        }
        var m = moved
        val tys = mutableListOf<Ty>()
        for (e in expr.elements) {
            val (ty, m2) = checkExpr(e, env, m, consume = true)
            tys += ty
            m = m2
        }
        val elemTy = tys[0]
        for ((i, ty) in tys.withIndex()) {
            if (ty != elemTy) errors += "Line ${expr.line}: array element ${i + 1} has type ${ty}, expected ${elemTy}"
        }
        return Ty.Array(elemTy) to m
    }

    // `[value; count]`: every slot starts out *aliasing the same reference* for a non-Copy
    // `value` (not moving it -- consume = false), rather than being disallowed outright. On
    // the JVM this is memory-safe regardless of element type (storing one reference into N
    // array slots is exactly what any array-fill does under the hood); the only real
    // difference from a Copy fill is that mutating through one slot's reference is visible
    // through the others too, until individual slots get overwritten. This matters in
    // practice for growable-array-style code that fills a new backing array with a filler
    // value it immediately overwrites slot-by-slot (see the `Vec<T>` prelude).
    private fun checkArrayRepeat(expr: Expr.ArrayRepeat, env: Env, moved: Map<String, Boolean>): Pair<Ty, Map<String, Boolean>> {
        val (valTy, m0) = checkExpr(expr.value, env, moved, consume = false)
        val (countTy, m1) = checkExpr(expr.count, env, m0, consume = true)
        if (countTy != Ty.Int_) errors += "Line ${expr.line}: array repeat count must be Int"
        return Ty.Array(valTy) to m1
    }

    private fun checkIndex(expr: Expr.Index, env: Env, moved: Map<String, Boolean>): Pair<Ty, Map<String, Boolean>> {
        val (arrTy, m0) = checkExpr(expr.arr, env, moved, consume = false)
        val (idxTy, m1) = checkExpr(expr.index, env, m0, consume = true)
        if (idxTy != Ty.Int_) errors += "Line ${expr.line}: array index must be Int"
        if (arrTy is Ty.Arena) {
            errors += "Line ${expr.line}: cannot use a raw off-heap value; access a field instead, e.g. buf[i].field"
            return Ty.Unit_ to m1
        }
        if (arrTy !is Ty.Array) {
            errors += "Line ${expr.line}: indexing a non-array type ${arrTy}"
            return Ty.Unit_ to m1
        }
        return arrTy.elem to m1
    }

    private fun checkArenaNew(expr: Expr.ArenaNew, env: Env, moved: Map<String, Boolean>): Pair<Ty, Map<String, Boolean>> {
        val (countTy, m) = checkExpr(expr.count, env, moved, consume = true)
        if (countTy != Ty.Int_) errors += "Line ${expr.line}: arena buffer count must be Int"
        if (!arenaLayouts.containsKey(expr.structName)) {
            errors += "Line ${expr.line}: unknown arena struct '${expr.structName}'"
            return Ty.Unit_ to m
        }
        return Ty.Arena(expr.structName) to m
    }

    // `buf[i].field` (read) -- special-cased because a raw off-heap struct value can never
    // be materialized as one JVM value, only accessed field-by-field, so this compound form
    // is handled as a unit rather than as Index-then-FieldAccess.
    private fun checkFieldAccess(expr: Expr.FieldAccess, env: Env, moved: Map<String, Boolean>): Pair<Ty, Map<String, Boolean>> {
        val idxObj = expr.obj as? Expr.Index
        if (idxObj != null) {
            val (arrTy, m0) = checkExpr(idxObj.arr, env, moved, consume = false)
            if (arrTy is Ty.Arena) {
                val (idxTy, m1) = checkExpr(idxObj.index, env, m0, consume = true)
                if (idxTy != Ty.Int_) errors += "Line ${expr.line}: array index must be Int"
                idxObj.ty = arrTy
                val layout = arenaLayouts.getValue(arrTy.structName)
                val fty = layout.fieldType(expr.field)
                if (fty == null) errors += "Line ${expr.line}: arena struct '${arrTy.structName}' has no field '${expr.field}'"
                return (fty ?: Ty.Unit_) to m1
            }
        }
        val (oty, m) = checkExpr(expr.obj, env, moved, consume = false)
        if (oty is Ty.Array) {
            if (expr.field != "length") errors += "Line ${expr.line}: array has no field '${expr.field}' (only 'length')"
            return Ty.Int_ to m
        }
        if (oty is Ty.JavaExtern) {
            // `recv.FIELD` on an `extern class` value -- a real instance field read (GETFIELD),
            // the receiver-borrow counterpart to `Alias::FIELD`'s static one. Same "trust the
            // declared shape" honesty as everything else under `extern`.
            if (oty.nullable) {
                errors += "Line ${expr.line}: cannot access field '${expr.field}' on a possibly-null value -- check `== null`/`!= null` first"
            }
            val ext = externClasses.values.firstOrNull { it.binaryName == oty.binaryName }
            val f = ext?.field(expr.field)
            if (ext == null || f == null) {
                // Property-style sugar: no declared field named `expr.field`, but a zero-arg
                // Java-bean getter (`getField`/`isField`) exists -- resolve to that instead of
                // erroring. Scoped to non-`lazy` extern classes only (explicit signature or
                // `use { }` reflection, both fully resolved up front): probing two candidate
                // names (`getX` then `isX`) through the *lazy* on-demand reflection path would
                // fire a real classpath lookup, and log a real "no such member" error, for every
                // name that doesn't happen to exist -- fine for a single deliberate method call,
                // noisy (up to two spurious errors before this function's own) for a guess.
                if (ext != null && !ext.lazy) {
                    val cap = expr.field.replaceFirstChar { it.uppercase() }
                    val getter = ext.method("get$cap").firstOrNull { !it.isCtor && !it.isStatic && it.params.isEmpty() }
                        ?: ext.method("is$cap").firstOrNull { !it.isCtor && !it.isStatic && it.params.isEmpty() }
                    if (getter != null) {
                        expr.externGetterMethod = getter.name
                        expr.resolvedName = ext.binaryName
                        return getter.retType to m
                    }
                }
                errors += "Line ${expr.line}: '${oty}' has no declared field '${expr.field}' (and no zero-arg getter 'get${expr.field.replaceFirstChar { it.uppercase() }}'/'is${expr.field.replaceFirstChar { it.uppercase() }}' to use as a property)"
                return Ty.Unit_ to m
            }
            if (f.isStatic) {
                errors += "Line ${expr.line}: '${expr.field}' is declared as a static field -- read it via '${ext.name}::${expr.field}' instead"
                return Ty.Unit_ to m
            }
            expr.resolvedName = ext.binaryName
            return f.type to m
        }
        val fty = (oty as? Ty.Struct)?.let { structs[it.name]?.fieldType(expr.field) }
        if (oty !is Ty.Struct) {
            errors += "Line ${expr.line}: field access on non-struct type ${oty}"
        } else if (fty == null) {
            errors += "Line ${expr.line}: struct '${oty.name}' has no field '${expr.field}'"
        }
        return (fty ?: Ty.Unit_) to m
    }

    private fun checkIndexAssign(expr: Expr.IndexAssign, env: Env, moved: Map<String, Boolean>): Pair<Ty, Map<String, Boolean>> {
        val (arrTy, m0) = checkExpr(expr.arr, env, moved, consume = false)
        val (idxTy, m1) = checkExpr(expr.index, env, m0, consume = true)
        val (valTy, m2) = checkExpr(expr.value, env, m1, consume = true)
        if (idxTy != Ty.Int_) errors += "Line ${expr.line}: array index must be Int"
        if (arrTy !is Ty.Array) {
            errors += "Line ${expr.line}: indexing a non-array type ${arrTy}"
            return Ty.Unit_ to m2
        }
        if (arrTy.elem != valTy) errors += "Line ${expr.line}: cannot assign ${valTy} into array of ${arrTy.elem}"
        val root = rootIdent(expr.arr)
        if (root == null) {
            errors += "Line ${expr.line}: left side of index assignment must be a variable or field path"
        } else {
            val info = env.lookup(root.name)
            if (info != null && !info.canMutateFields) {
                errors += "Line ${expr.line}: cannot mutate elements of '${root.name}': not declared 'var' and not received as '&mut'"
            }
        }
        return Ty.Unit_ to m2
    }

    private fun describeArg(e: Expr): String = if (e is Expr.Ident) e.name else "expr"

    // Int/Long/Float/Double all support arithmetic/comparison, but never mixed with each other --
    // no implicit promotion, matching this language's general same-type strictness elsewhere.
    private fun isNumeric(ty: Ty): Boolean = ty == Ty.Int_ || ty == Ty.Long_ || ty == Ty.Float_ || ty == Ty.Double_

    // Exact match, or a concrete struct implementing the interface an expected `&dyn X` wants
    // (the only subtyping relationship in this language -- everything else is exact).
    private fun tyCompatible(argTy: Ty, expectedTy: Ty): Boolean {
        if (argTy == expectedTy) return true
        if (expectedTy is Ty.Dyn && argTy is Ty.Struct) {
            val owner = structInstanceTemplate[argTy.name] ?: argTy.name
            return structInterfaces[owner]?.contains(expectedTy.interfaceName) == true
        }
        // A definitely-non-null value trivially satisfies a "maybe null" expectation --
        // `return get_pos_raw(...)` (never null) against a `-> BlockPos?`-declared function,
        // `let x: Type? = someNonNullValue;`. The reverse direction (a possibly-null value
        // where non-nullable is expected) is deliberately NOT allowed here -- that's exactly
        // what forces the `== null` guard-clause check in the first place.
        if (expectedTy is Ty.JavaExtern && argTy is Ty.JavaExtern && expectedTy.nullable && !argTy.nullable) {
            return argTy.binaryName == expectedTy.binaryName
        }
        // Same widening, for the one nullable-capable type that isn't a `JavaExtern`.
        if (expectedTy is Ty.Str_ && argTy is Ty.Str_ && expectedTy.nullable && !argTy.nullable) {
            return true
        }
        return false
    }

    private fun rootIdent(e: Expr): Expr.Ident? = when (e) {
        is Expr.Ident -> e
        is Expr.FieldAccess -> rootIdent(e.obj)
        is Expr.Index -> rootIdent(e.arr)
        else -> null
    }

    // Checks the same variable isn't borrowed both mutably and (mutably or shared) at once
    // within one call's argument list -- e.g. `foo(&mut p, &p)` or `foo(&mut p, &mut p)`.
    // Multiple shared borrows of the same variable in one call are fine.
    private fun checkBorrowExclusivity(borrows: List<Pair<String, Boolean>>, line: Int, context: String) {
        for ((name, kinds) in borrows.groupBy({ it.first }, { it.second })) {
            if (kinds.size > 1 && kinds.any { it }) {
                errors += "Line $line: conflicting borrows of '$name' in $context (a &mut borrow can't coexist with any other borrow of the same value)"
            }
        }
    }

    // A `&mut` borrow of a plain variable requires that variable to have been declared `var`.
    private fun checkMutBorrowTarget(inner: Expr, env: Env, line: Int, context: String) {
        val ident = inner as? Expr.Ident ?: return
        val info = env.lookup(ident.name) ?: return
        if (!info.mutable) errors += "Line $line: cannot take &mut borrow of immutable variable '${ident.name}' in $context (declared with 'let', use 'var')"
    }

    // `buf[i].field = value` (write) -- same reasoning as checkFieldAccess's arena special-case.
    private fun checkFieldAssign(expr: Expr.FieldAssign, env: Env, moved: Map<String, Boolean>): Pair<Ty, Map<String, Boolean>> {
        val idxObj = expr.obj as? Expr.Index
        if (idxObj != null) {
            val (arrTy, m0) = checkExpr(idxObj.arr, env, moved, consume = false)
            if (arrTy is Ty.Arena) {
                val (idxTy, m1) = checkExpr(idxObj.index, env, m0, consume = true)
                val (valTy, m2) = checkExpr(expr.value, env, m1, consume = true)
                if (idxTy != Ty.Int_) errors += "Line ${expr.line}: array index must be Int"
                idxObj.ty = arrTy
                val layout = arenaLayouts.getValue(arrTy.structName)
                val fty = layout.fieldType(expr.field)
                if (fty == null) {
                    errors += "Line ${expr.line}: arena struct '${arrTy.structName}' has no field '${expr.field}'"
                } else if (fty != valTy) {
                    errors += "Line ${expr.line}: cannot assign ${valTy} to field '${expr.field}' of type ${fty}"
                }
                val root = rootIdent(idxObj.arr)
                if (root == null) {
                    errors += "Line ${expr.line}: left side of field assignment must be a variable or field path"
                } else {
                    val info = env.lookup(root.name)
                    if (info != null && !info.canMutateFields) {
                        errors += "Line ${expr.line}: cannot mutate elements of '${root.name}': not declared 'var' and not received as '&mut'"
                    }
                }
                return Ty.Unit_ to m2
            }
        }
        val (objTy, m0) = checkExpr(expr.obj, env, moved, consume = false)
        val (valTy, m1) = checkExpr(expr.value, env, m0, consume = true)
        if (objTy !is Ty.Struct) {
            errors += "Line ${expr.line}: field assignment on non-struct type ${objTy}"
            return Ty.Unit_ to m1
        }
        val fty = structs[objTy.name]?.fieldType(expr.field)
        if (fty == null) {
            errors += "Line ${expr.line}: struct '${objTy.name}' has no field '${expr.field}'"
        } else if (fty != valTy) {
            errors += "Line ${expr.line}: cannot assign ${valTy} to field '${expr.field}' of type ${fty}"
        }
        val root = rootIdent(expr.obj)
        if (root == null) {
            errors += "Line ${expr.line}: left side of field assignment must be a variable or field path"
        } else {
            val info = env.lookup(root.name)
            if (info != null && !info.canMutateFields) {
                errors += "Line ${expr.line}: cannot mutate field of '${root.name}': not declared 'var' and not received as '&mut'"
            }
        }
        return Ty.Unit_ to m1
    }
}