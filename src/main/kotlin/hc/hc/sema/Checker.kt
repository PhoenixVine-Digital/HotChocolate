package hc.sema

import hc.ast.*

class SemaError(message: String) : RuntimeException(message)

data class FnSig(val params: List<Param>, val paramTys: List<Ty>, val ret: Ty)

data class InterfaceMethodSig(val params: List<Param>, val paramTys: List<Ty>, val ret: Ty, val retType: TypeRef?, val hasDefault: Boolean, val selfIsMut: Boolean)

class InterfaceInfo(val name: String, val sealed: Boolean = false) {
    val methods = mutableMapOf<String, InterfaceMethodSig>()

    val implementers = mutableListOf<String>()
}
private class RawInterface(val extends: List<String>, val ownMethods: Map<String, InterfaceMethodSig>)

class StaticInfo(val name: String, val ty: Ty, val init: Expr, val moduleName: String?, val visible: Boolean, val sourceUnit: String? = null)

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

    fun depth(): Int = scopes.size
    fun lookupWithDepth(name: String): Pair<VarInfo, Int>? {
        for (i in scopes.indices.reversed()) scopes[i][name]?.let { return it to i }
        return null
    }
}

class Checker(private val program: Program, private val classpathReflector: ClasspathReflector? = null) {
    companion object {

        const val SERIALIZABLE_STRING_MAX_LEN = 2_097_151
    }
    val structs = mutableMapOf<String, StructInfo>()
    val fns = mutableMapOf<String, FnSig>()

    val mustUseFns = mutableSetOf<String>()
    val arenaLayouts = mutableMapOf<String, ArenaLayout>()
    val enums = mutableMapOf<String, EnumInfo>()
    private val enumVariantOwner = mutableMapOf<String, String>() 
    private val genericEnumTemplates = mutableMapOf<String, EnumDecl>()
    private val enumInstanceTemplate = mutableMapOf<String, String>() 
    private val enumInstanceArgs = mutableMapOf<String, List<Ty>>() 
    val externClasses = mutableMapOf<String, ExternClassInfo>() 
    val interfaces = mutableMapOf<String, InterfaceInfo>()
    val structInterfaces = mutableMapOf<String, MutableSet<String>>() 
    val interfaceImplFns = mutableMapOf<String, MutableList<FnDecl>>() 
    val structSuperclass = mutableMapOf<String, String>() 
    val superclassOverrideFns = mutableMapOf<String, MutableList<FnDecl>>() 
    private val rawInterfaces = mutableMapOf<String, RawInterface>()
    private val genericInterfaceImpls = mutableMapOf<String, MutableList<ImplBlock>>() 
    private val genericStructTemplates = mutableMapOf<String, StructDecl>()
    private val genericFnTemplates = mutableMapOf<String, FnDecl>()
    private val structInstances = mutableMapOf<String, StructDecl>()
    private val fnInstances = mutableMapOf<String, FnDecl>()
    private val structInstanceArgs = mutableMapOf<String, List<Ty>>() 
    private val structInstanceTemplate = mutableMapOf<String, String>() 
    private val implFns = mutableListOf<FnDecl>() 
    private val extensionFns = mutableMapOf<Pair<String, String>, String>() 
    val statics = mutableMapOf<String, StaticInfo>()

    var entryClassName: String? = null
    var entryModid: String? = null
    var entryInitFn: String? = null
    private val errors = mutableListOf<String>()

    private val captureStack = ArrayDeque<Pair<Int, LinkedHashMap<String, Ty>>>()
    private var lambdaCounter = 0

    private val dropScopeStack = ArrayDeque<MutableList<String>>()

    private val loopDropFloors = ArrayDeque<Int>()

    private fun hasDrop(structName: String): Boolean = fns.containsKey("$structName\$drop")

    fun resolvedProgram(): Program = Program(
        structs = program.structs.filter { it.typeParams.isEmpty() && !it.isArena } + structInstances.values,
        fns = program.fns.filter { it.typeParams.isEmpty() } +
            implFns.filter { it.typeParams.isEmpty() } +
            fnInstances.values,
    )

    fun check() {
        val duplicateStructNames = mutableSetOf<String>()

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

        for (e in program.enums) {
            if (e.typeParams.isNotEmpty()) {
                if (genericEnumTemplates.containsKey(e.name)) { errors += "Duplicate enum '${e.name}'"; continue }
                genericEnumTemplates[e.name] = e
            } else {
                if (enums.containsKey(e.name)) { errors += "Duplicate enum '${e.name}'"; continue }
                enums[e.name] = EnumInfo(e.name, emptyList(), e.moduleName, e.visible)
            }
        }

        for (s in program.structs) {
            if (s.typeParams.isEmpty() && !s.isArena) {
                if (structs.containsKey(s.name) || genericStructTemplates.containsKey(s.name)) {
                    errors += "Duplicate struct '${s.name}'"
                    duplicateStructNames += s.name
                    continue
                }
                structs[s.name] = StructInfo(s.name, emptyList())
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
        for (s in program.structs.filter { it.typeParams.isEmpty() && !it.isArena && it.name !in duplicateStructNames }) {
            if (arenaLayouts.containsKey(s.name)) { errors += "Duplicate struct '${s.name}'"; continue }
            structs[s.name] = StructInfo(s.name, s.fields.map { it.name to resolveType(it.type) })
        }

        for (e in program.enums) {
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
            if (e.typeParams.isNotEmpty()) continue 
            val variants = e.variants.mapIndexed { tag, v -> EnumVariantInfo(v.name, tag, v.fields.map { it.name to resolveType(it.type) }) }
            enums[e.name] = EnumInfo(e.name, variants, e.moduleName, e.visible)
        }

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

        for (i in program.interfaces) {
            if (!rawInterfaces.containsKey(i.name)) continue 
            val info = InterfaceInfo(i.name, i.sealed)
            info.methods.putAll(flattenInterfaceMethods(i.name, mutableSetOf()))
            interfaces[i.name] = info
        }

        for (ext in program.externs) {
            if (ext.lazyAll) continue 
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

                        retType = if (isCtor) Ty.Unit_ else m.retType?.let { resolveType(it) } ?: Ty.Unit_,
                        isStatic = m.isStatic || (!isInstance && m.name != "new"),
                        isCtor = isCtor,
                        paramIsRef = restParams.map { it.type.isRef },
                        paramIsMut = restParams.map { it.type.isMut },
                    )
                }.also { verifyExternSignatures(ext.name, ext.binaryName, it) }
            }
            val fields = ext.fields.map { f -> ExternFieldInfo(f.name, resolveType(f.type), f.isStatic) }
            
            externClasses[ext.name] = ExternClassInfo(ext.name, ext.binaryName, methods, fields = fields, isInterface = ext.isInterface)
        }

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

            if (targetStruct.fields.isNotEmpty() || targetStruct.superclass != null) {
                errors += "Line ${directive.line}: '@entry(\"forge.mod\", ...)' target struct '${targetStruct.name}' must have no fields and no 'extends' -- Forge needs a real no-arg constructor to instantiate it"
                continue
            }
            entryClassName = targetStruct.name
            entryModid = modid
            entryInitFn = fn.name
        }

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

        for (impl in program.impls.filter { it.interfaceName == null }) {
            val ownerType = TypeRef(impl.structName, isRef = false, typeArgs = impl.typeParams.map { TypeRef(it, false) })
            fun fixSelf(t: TypeRef): TypeRef = if (t.name == "Self") ownerType.copy(isRef = t.isRef, isMut = t.isMut) else t

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
                implFns += FnDecl(mangledName, newParams, newRet, m.body, m.line, impl.typeParams, moduleName = structModule, visible = true, mustUse = m.mustUse)
            }
        }

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

            if (f.mustUse) {
                if (ret == Ty.Unit_) errors += "fn '${f.name}': '@must_use' on a Unit-returning fn has no return value to enforce using"
                mustUseFns += f.name
            }
        }
        if (fns.containsKey("main")) {
            val m = fns.getValue("main")

            val isArgvParam = m.params.size == 1 && m.params[0].type.name == "Array" &&
                m.params[0].type.typeArgs.singleOrNull()?.name == "String"
            if (m.params.isNotEmpty() && !isArgvParam) {
                errors += "fn main must take no parameters, or a single '[String]' parameter for argv"
            }
        }

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

        for (f in genericFnTemplates.values) checkGenericFnBoundValidity(f)

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

        for ((structName, methods) in interfaceImplFns) {
            for (m in methods) {
                checkFn(m.copy(name = "$structName@${m.name}"))
            }
        }

        for ((structName, methods) in superclassOverrideFns) {
            for (m in methods) {
                checkFn(m.copy(name = "$structName@${m.name}"))
            }
        }

        checkDocSees()
        if (errors.isNotEmpty()) throw SemaError(errors.joinToString("\n"))
    }

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

    private fun verifyExternSignatures(aliasName: String, binaryName: String, declared: List<ExternMethodInfo>) {
        val reflector = classpathReflector ?: return
        if (!reflector.classExists(binaryName)) return 
        for (m in declared) {
            val realCandidates = reflector.resolveMembers(binaryName, m.name)
            if (realCandidates.isEmpty()) {
                errors += "extern class '$aliasName': no public member '${m.name}' found on '${binaryName.replace('/', '.')}' on the configured classpath"
                continue
            }
            val declaredParamKeys = m.params.map { realTyKey(it) }
            val declaredRetKey = if (m.isCtor) null else realTyKey(m.retType)

            if (declaredParamKeys.any { it == null } || (!m.isCtor && declaredRetKey == null)) continue
            val matches = realCandidates.any { real ->
                real.isStatic == m.isStatic &&
                    real.params.map { realTyKey(it) } == declaredParamKeys &&
                    (m.isCtor || realTyKey(real.retType) == declaredRetKey)
            }
            if (!matches) {
                fun ExternMethodInfo.describe() = "(${params.joinToString(", ")})" + (if (isCtor) "" else " -> $retType")
                errors += "extern class '$aliasName': declared '${m.name}${m.describe()}' doesn't match any real overload of " +
                    "'${binaryName.replace('/', '.')}.${m.name}' on the configured classpath -- found: " +
                    realCandidates.joinToString("; ") { it.describe() }
            }
        }
    }

    private fun realTyKey(ty: Ty): String? = when (ty) {
        Ty.Int_ -> "I"
        Ty.Long_ -> "J"
        Ty.Float_ -> "F"
        Ty.Double_ -> "D"
        Ty.Bool_ -> "Z"
        Ty.Unit_ -> "V"
        is Ty.Str_ -> "Ljava/lang/String;"
        is Ty.JavaExtern -> "L${ty.binaryName};"
        is Ty.Array -> realTyKey(ty.elem)?.let { "[$it" }
        is Ty.Dyn -> externClasses[ty.interfaceName]?.binaryName?.let { "L$it;" }
        else -> null 
    }

    private fun findExternAlias(binaryName: String, hasIt: (ExternClassInfo) -> Boolean): ExternClassInfo? {
        val aliases = externClasses.values.filter { it.binaryName == binaryName }
        return aliases.firstOrNull(hasIt) ?: aliases.firstOrNull()
    }

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

    private fun tyToTypeRef(ty: Ty, isRef: Boolean, isMut: Boolean): TypeRef = when (ty) {
        is Ty.Dyn -> TypeRef(ty.interfaceName, isRef = true, isMut = isMut, isDyn = true)
        is Ty.Array -> TypeRef("Array", isRef, listOf(tyToTypeRef(ty.elem, isRef = false, isMut = false)))
        is Ty.Arena -> TypeRef("Arena", isRef, listOf(TypeRef(ty.structName, isRef = false)))
        is Ty.JavaExtern -> {
            
            val alias = externClasses.entries.firstOrNull { it.value.binaryName == ty.binaryName }?.key
                ?: ty.binaryName 
            TypeRef(alias, isRef, isMut = isMut)
        }
        else -> TypeRef(tyName(ty), isRef, isMut = isMut)
    }

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
            arms = s.arms.map { it.copy(body = substituteBlock(it.body, subst), literal = it.literal?.let { lit -> substituteExpr(lit, subst) }) },
        )
        is Stmt.Try -> s.copy(
            tryBlock = substituteBlock(s.tryBlock, subst),
            catches = s.catches.map { it.copy(body = substituteBlock(it.body, subst)) },
        )
        is Stmt.Throw -> s.copy(expr = substituteExpr(s.expr, subst))

        is Stmt.Break -> Stmt.Break(s.line)
        is Stmt.Continue -> Stmt.Continue(s.line)
        is Stmt.DevIf -> throw IllegalStateException("Stmt.DevIf reached substituteStmt -- Main.kt's stripDevCode should have already resolved every 'if dev { }' before generics were ever instantiated")
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
        is Expr.Range -> Expr.Range(substituteExpr(e.start, subst), substituteExpr(e.end, subst), e.line, e.inclusive)
        is Expr.StringInterp -> Expr.StringInterp(e.literals, e.exprs.map { substituteExpr(it, subst) }, e.line)

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
            e.arms.map { it.copy(body = substituteBlock(it.body, subst), literal = it.literal?.let { lit -> substituteExpr(lit, subst) }) },
            e.line,
        )
        is Expr.Lambda -> Expr.Lambda(e.params, substituteExpr(e.body, subst), e.line)
        is Expr.ClassLit -> Expr.ClassLit(e.typeName, e.line)
    }

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

    private val boundInterfaceComponents = mutableMapOf<String, Set<String>>()

    private val boundInterfaceCache = mutableMapOf<List<String>, String?>()

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
        fns[mangled] = sig 
        val newDecl = FnDecl(mangled, newParams, newRet, substituteBlock(template.body, subst), template.line, moduleName = template.moduleName, visible = template.visible)
        checkFn(newDecl)

    }

    private fun getOrInstantiateFn(template: FnDecl, typeArgs: List<Ty>, line: Int): FnSig {
        val mangled = mangle(template.name, typeArgs)
        fns[mangled]?.let { return it }
        checkTypeParamBounds(template.typeParams, template.typeParamBounds, typeArgs, line, "'${template.name}'")
        val subst = template.typeParams.zip(typeArgs).toMap()
        val newParams = template.params.map { Param(it.name, substituteTypeRef(it.type, subst)) }
        val newRet = template.retType?.let { substituteTypeRef(it, subst) }
        val sig = FnSig(newParams, newParams.map { resolveType(it.type) }, newRet?.let { resolveType(it) } ?: Ty.Unit_)
        fns[mangled] = sig 
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

        for (impl in genericInterfaceImpls[template.name].orEmpty()) {
            if (impl.typeParams.size != typeArgs.size) continue 
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

        fns["$structName@${m.name}"] = FnSig(newDecl.params, newDecl.params.map { resolveType(it.type) }, retTy)
    }

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

            val newDecl = FnDecl(m.name, m.params.map { Param(it.name, fixSelf(it.type)) }, m.retType?.let { fixSelf(it) }, substituteBlock(m.body, subst), m.line)
            interfaceImplFns.getOrPut(concreteStructName) { mutableListOf() } += newDecl

            fns["$concreteStructName@${m.name}"] = FnSig(newDecl.params, newDecl.params.map { resolveType(it.type) }, newDecl.retType?.let { resolveType(it) } ?: Ty.Unit_)
        }

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

        structInterfaces.getOrPut(concreteStructName) { mutableSetOf() } += ifaceName
        structInterfaces.getOrPut(concreteStructName) { mutableSetOf() } += transitiveSupertraits(ifaceName)
        if (iface.sealed && concreteStructName !in iface.implementers) iface.implementers += concreteStructName
    }

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

    private fun checkFn(f: FnDecl) {
        val sig = fns.getValue(f.name)
        val env = Env()
        env.push()

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

    private fun nullCheckedIdentName(cond: Expr): Pair<String, Boolean>? {
        if (cond !is Expr.Binary || (cond.op != "==" && cond.op != "!=")) return null
        val name = when {
            cond.left is Expr.Ident && cond.right is Expr.NullLit -> cond.left.name
            cond.right is Expr.Ident && cond.left is Expr.NullLit -> cond.right.name
            else -> null
        } ?: return null
        return name to (cond.op == "!=")
    }

    private fun narrowNonNull(env: Env, name: String) {
        val info = env.lookup(name) ?: return
        when (val ty = info.ty) {
            is Ty.JavaExtern -> if (ty.nullable) env.declare(name, info.copy(ty = ty.copy(nullable = false)))
            is Ty.Str_ -> if (ty.nullable) env.declare(name, info.copy(ty = Ty.Str_(nullable = false)))
            else -> {}
        }
    }

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

            val ty = if (declared != null && tyCompatible(rhsTy, declared)) declared else rhsTy
            env.declare(stmt.name, VarInfo(ty, stmt.mutable, canMutateFields = stmt.mutable))
            if (ty is Ty.Struct && hasDrop(ty.name)) dropScopeStack.lastOrNull()?.add(stmt.name)
            val out = HashMap(m)
            out[stmt.name] = false
            out
        }
        is Stmt.ExprStmt -> {
            val e = stmt.expr
            if (e is Expr.Call && e.callee in mustUseFns) {
                errors += "Line ${e.line}: return value of '${e.callee}' must be used ('@must_use')"
            }
            val out = checkExpr(stmt.expr, env, moved, consume = true).second

            if (e is Expr.MethodCall && e.resolvedName in mustUseFns) {
                errors += "Line ${e.line}: return value of '${e.method}' must be used ('@must_use')"
            }
            if (e is Expr.StaticCall && e.resolvedName in mustUseFns) {
                errors += "Line ${e.line}: return value of '${e.typeName}::${e.method}' must be used ('@must_use')"
            }
            out
        }
        is Stmt.If -> {
            val (condTy, m0) = checkExpr(stmt.cond, env, moved, consume = true)
            if (condTy != Ty.Bool_) errors += "if condition must be Bool, got ${condTy}"

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

            if (stmt.elseB == null && narrow != null && !narrow.second && blockAlwaysExits(stmt.thenB)) {
                narrowNonNull(env, narrow.first)
            }
            merged
        }
        is Stmt.While -> {
            val (condTy, m0) = checkExpr(stmt.cond, env, moved, consume = true)
            if (condTy != Ty.Bool_) errors += "while condition must be Bool, got ${condTy}"
            loopDropFloors.addLast(dropScopeStack.size)
            val afterBody = checkBlock(stmt.body, env, m0, retTy)
            loopDropFloors.removeLast()
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
        is Stmt.Break -> {
            if (loopDropFloors.isEmpty()) {
                errors += "Line ${stmt.line}: 'break' outside of a loop"
            } else {
                val skip = dropScopeStack.size - loopDropFloors.last()
                stmt.varsToDropBeforeBreak = dropScopeStack.asReversed().take(skip).flatMap { it.asReversed().filter { n -> moved[n] != true } }
            }
            moved
        }
        is Stmt.Continue -> {
            if (loopDropFloors.isEmpty()) {
                errors += "Line ${stmt.line}: 'continue' outside of a loop"
            } else {
                val skip = dropScopeStack.size - loopDropFloors.last()
                stmt.varsToDropBeforeContinue = dropScopeStack.asReversed().take(skip).flatMap { it.asReversed().filter { n -> moved[n] != true } }
            }
            moved
        }
        is Stmt.DevIf -> throw IllegalStateException("Stmt.DevIf reached checkStmt -- Main.kt's stripDevCode should have already resolved every 'if dev { }' before the checker ever ran")
    }

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
        loopDropFloors.addLast(dropScopeStack.size)
        val afterBody = checkBlock(stmt.body, env, bodyMovedIn, retTy)
        loopDropFloors.removeLast()
        env.pop()
        val merged = HashMap<String, Boolean>()
        for (k in m0.keys) merged[k] = (m0[k] ?: false) || (afterBody[k] ?: false)
        return merged
    }

    private fun checkMatch(scrutinee: Expr, arms: List<MatchArm>, line: Int, env: Env, moved: Map<String, Boolean>, retTy: Ty): Map<String, Boolean> {
        val (scrutTy, m0) = checkExpr(scrutinee, env, moved, consume = true)
        if (scrutTy is Ty.Dyn) return checkSealedMatch(arms, line, scrutTy, env, m0, retTy)
        if (scrutTy == Ty.Int_ || scrutTy == Ty.Long_ || scrutTy == Ty.Bool_ || scrutTy is Ty.Str_) {
            return checkLiteralMatch(arms, line, scrutTy, env, m0, retTy)
        }
        if (scrutTy !is Ty.Enum) {
            errors += "Line ${line}: match requires an enum value, a primitive (Int/Long/Bool/String), or a &dyn sealed interface, got ${scrutTy}"
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

    private fun checkLiteralMatch(arms: List<MatchArm>, line: Int, scrutTy: Ty, env: Env, moved: Map<String, Boolean>, retTy: Ty): Map<String, Boolean> {
        val covered = mutableSetOf<Any>()
        var hasWildcard = false
        var m0 = moved
        val armStates = mutableListOf<Map<String, Boolean>>()
        for (arm in arms) {
            if (hasWildcard) errors += "Line ${arm.line}: unreachable arm after wildcard '_'"
            if (arm.variantName == null && arm.literal == null) {
                hasWildcard = true
                armStates += checkBlock(arm.body, env, m0, retTy)
                continue
            }
            if (arm.variantName != null) {
                errors += "Line ${arm.line}: '${arm.variantName}' is an enum-variant pattern, but this match is on a ${scrutTy} value"
                armStates += checkBlock(arm.body, env, m0, retTy)
                continue
            }
            val lit = arm.literal!!
            val (litTy, m1) = checkExpr(lit, env, m0, consume = true)
            m0 = m1
            if (litTy != scrutTy && !(litTy is Ty.Str_ && scrutTy is Ty.Str_)) {
                errors += "Line ${arm.line}: match arm literal is ${litTy}, but this match is on a ${scrutTy} value"
            }
            val litValue: Any = when (lit) {
                is Expr.IntLit -> lit.value
                is Expr.LongLit -> lit.value
                is Expr.BoolLit -> lit.value
                is Expr.StringLit -> lit.value
                else -> continue 
            }
            if (!covered.add(litValue)) errors += "Line ${arm.line}: duplicate arm for ${litValue}"
            armStates += checkBlock(arm.body, env, m0, retTy)
        }
        val boolExhaustive = scrutTy == Ty.Bool_ && covered.containsAll(setOf(true, false))
        if (!hasWildcard && !boolExhaustive) {
            errors += "Line ${line}: match on ${scrutTy} isn't exhaustive -- add a '_' wildcard arm" +
                (if (scrutTy == Ty.Bool_) " (or cover both 'true' and 'false')" else "")
        }
        val merged = HashMap<String, Boolean>()
        for (k in m0.keys) merged[k] = armStates.any { it[k] == true }
        return merged
    }

    private fun checkDocSees() {
        for (doc in program.fns.map { it.docComment } + program.structs.map { it.docComment }) {
            if (doc == null) continue
            for (see in doc.sees) {
                see.resolved = resolveDocSeeTarget(see.target)
                if (!see.resolved) {
                    errors += "Line ${see.line}: '@see ${see.target}' doesn't resolve to anything in this program"
                }
            }
        }
    }

    private fun resolveDocSeeTarget(target: String): Boolean {
        val dot = target.indexOf('.')
        if (dot < 0) {
            return structs.containsKey(target) || fns.containsKey(target) || enums.containsKey(target) ||
                interfaces.containsKey(target) || externClasses.containsKey(target)
        }
        val owner = target.substring(0, dot)
        val member = target.substring(dot + 1)
        structs[owner]?.let { return it.fields.any { f -> f.first == member } || fns.containsKey("$owner@$member") }
        enums[owner]?.let { return it.variantNames.contains(member) }
        interfaces[owner]?.let { return it.methods.containsKey(member) }
        externClasses[owner]?.let { return it.methods.any { m -> m.name == member } || it.fields.any { f -> f.name == member } }
        return false
    }

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

            is Expr.NullLit -> {
                if (expectedTy is Ty.JavaExtern && expectedTy.nullable) expectedTy to moved
                else if (expectedTy is Ty.Str_ && expectedTy.nullable) expectedTy to moved
                else Ty.Unit_ to moved
            }
            is Expr.Ident -> {
                val info = env.lookup(expr.name)

                if (info != null && captureStack.isNotEmpty() && !statics.containsKey(expr.name)) {
                    val (_, depth) = env.lookupWithDepth(expr.name)!!

                    for (frame in captureStack) {
                        if (depth < frame.first) frame.second[expr.name] = info.ty
                    }
                }
                if (info == null) {
                    val enumName = enumVariantOwner[expr.name]
                    if (enumName != null && enums.containsKey(enumName)) {
                        val variant = enums.getValue(enumName).variant(expr.name)!!
                        if (variant.fields.isEmpty()) {
                            
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

                val bothNumeric = isNumeric(innerTy) && isNumeric(targetTy)
                val bothObjectRef = innerTy.isObjectRef() && targetTy.isObjectRef()
                if (!bothNumeric && !bothObjectRef) {
                    errors += "Line ${expr.line}: 'as' only supports numeric conversions (Int/Long/Float/Double) or reference-type casts, got ${innerTy} as ${targetTy}"
                }
                targetTy to m
            }
            is Expr.InstanceOf -> {

                val (innerTy, m) = checkExpr(expr.inner, env, moved, consume = false)
                val targetTy = resolveType(expr.target)
                if (!innerTy.isObjectRef() || !targetTy.isObjectRef()) {
                    errors += "Line ${expr.line}: 'is' only supports reference types (Struct/Enum/Dyn/JavaExtern/Array/String), got ${innerTy} is ${targetTy}"
                }
                expr.resolvedTargetTy = targetTy
                Ty.Bool_ to m
            }
            is Expr.Binary -> if (expr.left is Expr.NullLit || expr.right is Expr.NullLit) {

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

        val lambdaDepth = env.depth() - 1
        captureStack.addLast(lambdaDepth to LinkedHashMap())
        val (bodyTy, m2raw) = checkExpr(expr.body, env, moved, consume = true, expectedTy = msig.ret)
        val captured = captureStack.removeLast().second
        env.pop()

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

        if (recvTy is Ty.Enum && expr.method == "ordinal") {
            if (expr.args.isNotEmpty()) errors += "Line ${expr.line}: '.ordinal()' takes no arguments"
            expr.isEnumOrdinal = true
            return Ty.Int_ to m
        }

        for (a in expr.args) if (a !is Expr.Lambda) m = checkExpr(a, env, m, consume = true).second

        val sig: FnSig
        if (recvTy is Ty.Dyn) {

            val iface = interfaces[recvTy.interfaceName]
            val msig = iface?.methods?.get(expr.method)
            if (iface == null) {
                errors += "Line ${expr.line}: unknown interface '${recvTy}'"
                return Ty.Unit_ to m
            }
            if (msig == null) {

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

            val concreteName = recvTy.name

            val overrideKey = "$concreteName@${expr.method}"
            val hasExplicitOverride = fns.containsKey(overrideKey) && (
                interfaceImplFns[concreteName]?.any { it.name == expr.method } == true ||
                superclassOverrideFns[concreteName]?.any { it.name == expr.method } == true
            )
            val candidates = structInterfaces[concreteName].orEmpty()
                .mapNotNull { ifaceName -> interfaces[ifaceName]?.methods?.get(expr.method)?.let { ifaceName to it } }
            if (hasExplicitOverride) {

                sig = fns.getValue(overrideKey)
                expr.resolvedName = expr.method
                expr.instanceOwner = concreteName
            } else if (candidates.size > 1) {

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
            val ext = findExternAlias(recvTy.binaryName) { it.method(expr.method).isNotEmpty() }
            val candidates = ext?.let { resolveExternMethods(it, expr.method, expr.line) }?.filter { !it.isCtor }.orEmpty()
            if (ext == null || candidates.isEmpty()) {

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

            if (recvTy.nullable) {
                errors += "Line ${expr.line}: cannot call '${expr.method}' on a possibly-null value -- check `== null`/`!= null` first"
            }
            val ext = findExternAlias("java/lang/String") { it.method(expr.method).isNotEmpty() }
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

            if (recvIdent != null && !statics.containsKey(recvIdent.name)) {
                val mm = HashMap(m); mm[recvIdent.name] = true; m = mm
            }
        } else if (selfParam?.type?.isMut == true && recvIdent != null) {

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

        if (structs.containsKey(expr.typeName) || genericStructTemplates.containsKey(expr.typeName)) {
            
            val mangled = "${expr.typeName}@${expr.method}"
            val sig = fns[mangled]
            if (sig == null) {
                
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

                val expectedTypeArgs = if (expectedTy is Ty.Enum && expectedTy.name.startsWith(enumName + "_")) {
                    enumInstanceArgs[expectedTy.name]
                } else null
                val typeArgs = if (argTys.isEmpty() && expectedTy is Ty.Enum && expectedTy.name == enumName) {
                    emptyList() 
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
            val (argTy, m) = checkExpr(arg, env, moved, consume = true) 
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

    private val STRINGIFIABLE = setOf(Ty.Int_, Ty.Long_, Ty.Float_, Ty.Double_, Ty.Bool_, Ty.Str_())

    private fun checkStringInterp(expr: Expr.StringInterp, env: Env, moved: Map<String, Boolean>): Pair<Ty, Map<String, Boolean>> {
        var m = moved
        for (e in expr.exprs) {
            val (ty, m2) = checkExpr(e, env, m, consume = true)
            if (ty !in STRINGIFIABLE) {

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

            if (oty.nullable) {
                errors += "Line ${expr.line}: cannot access field '${expr.field}' on a possibly-null value -- check `== null`/`!= null` first"
            }
            val ext = findExternAlias(oty.binaryName) { it.field(expr.field) != null }
            val f = ext?.field(expr.field)
            if (ext == null || f == null) {

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

    private fun isNumeric(ty: Ty): Boolean = ty == Ty.Int_ || ty == Ty.Long_ || ty == Ty.Float_ || ty == Ty.Double_

    private fun tyCompatible(argTy: Ty, expectedTy: Ty): Boolean {
        if (argTy == expectedTy) return true
        if (expectedTy is Ty.Dyn && argTy is Ty.Struct) {
            val owner = structInstanceTemplate[argTy.name] ?: argTy.name
            return structInterfaces[owner]?.contains(expectedTy.interfaceName) == true
        }

        if (expectedTy is Ty.JavaExtern && argTy is Ty.JavaExtern && expectedTy.nullable && !argTy.nullable) {
            return argTy.binaryName == expectedTy.binaryName
        }
        
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

    private fun checkBorrowExclusivity(borrows: List<Pair<String, Boolean>>, line: Int, context: String) {
        for ((name, kinds) in borrows.groupBy({ it.first }, { it.second })) {
            if (kinds.size > 1 && kinds.any { it }) {
                errors += "Line $line: conflicting borrows of '$name' in $context (a &mut borrow can't coexist with any other borrow of the same value)"
            }
        }
    }

    private fun checkMutBorrowTarget(inner: Expr, env: Env, line: Int, context: String) {
        val ident = inner as? Expr.Ident ?: return
        val info = env.lookup(ident.name) ?: return
        if (!info.mutable) errors += "Line $line: cannot take &mut borrow of immutable variable '${ident.name}' in $context (declared with 'let', use 'var')"
    }

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

        fun checkMutabilityRoot() {
            val root = rootIdent(expr.obj)
            if (root == null) {
                errors += "Line ${expr.line}: left side of field assignment must be a variable or field path"
            } else {
                val info = env.lookup(root.name)
                if (info != null && !info.canMutateFields) {
                    errors += "Line ${expr.line}: cannot mutate field of '${root.name}': not declared 'var' and not received as '&mut'"
                }
            }
        }

        if (objTy is Ty.JavaExtern) {

            if (objTy.nullable) {
                errors += "Line ${expr.line}: cannot assign field '${expr.field}' on a possibly-null value -- check `== null`/`!= null` first"
                return Ty.Unit_ to m1
            }
            val ext = findExternAlias(objTy.binaryName) { it.field(expr.field) != null }
            val f = ext?.field(expr.field)
            if (ext != null && f != null) {

                if (f.isStatic) {
                    errors += "Line ${expr.line}: '${expr.field}' is declared as a static field -- write it via '${ext.name}::${expr.field} = ...' instead"
                } else if (f.type != valTy) {
                    errors += "Line ${expr.line}: cannot assign ${valTy} to field '${expr.field}' of type ${f.type}"
                }
                expr.resolvedName = ext.binaryName
                checkMutabilityRoot()
                return Ty.Unit_ to m1
            }

            if (ext != null && !ext.lazy) {
                val cap = expr.field.replaceFirstChar { it.uppercase() }
                val setter = ext.method("set$cap").firstOrNull { !it.isCtor && !it.isStatic && it.params.size == 1 && it.params[0] == valTy }
                if (setter != null) {
                    expr.externSetterMethod = setter.name
                    expr.externSetterRetTy = setter.retType
                    expr.resolvedName = ext.binaryName
                    checkMutabilityRoot()
                    return Ty.Unit_ to m1
                }
            }
            val cap = expr.field.replaceFirstChar { it.uppercase() }
            errors += "Line ${expr.line}: '${objTy}' has no declared field '${expr.field}' (and no single-arg setter 'set$cap(${valTy})' to use as a property)"
            return Ty.Unit_ to m1
        }

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
        checkMutabilityRoot()
        return Ty.Unit_ to m1
    }
}
