package hc.codegen

import hc.ast.*
import hc.sema.ArenaLayout
import hc.sema.EnumInfo
import hc.sema.ExternClassInfo
import hc.sema.InterfaceInfo
import hc.sema.StaticInfo
import hc.sema.StructInfo
import hc.sema.Ty
import hc.sema.descriptor
import hc.sema.isWide
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.*

class CodeGenError(message: String) : RuntimeException(message)

// Name of the mainClass's private static BufferedReader field backing `read_line()`. `$` is a
// valid (if unusual) JVM identifier character and can't collide with any user-declared name,
// since the lexer's `identifier()` never accepts `$` as part of a source-level name.
private const val STDIN_FIELD = "\$in"

// Every class this compiler itself generates (structs, enums, non-extern interfaces, the main
// class) needs its own declaring `module a.b.c;`'s prefix (see Program's doc comment) baked
// into its JVM binary name wherever that name is emitted into bytecode -- looked up per-name
// rather than one flat prefix, since different declarations in the same compile can now belong
// to different modules. `extern class`/`extern interface` binary names are real *existing*
// classes elsewhere and must never go through this.
private fun qualify(name: String, moduleOf: Map<String, String?>): String {
    val mod = moduleOf[name] ?: return name
    return mod.replace('.', '/') + "/" + name
}

// `Ty.Dyn.descriptor()` always builds `"L${interfaceName};"` from the *declared alias*, which
// is correct for a normal `interface` (the alias IS the generated class's name, modulo the
// module prefix above) but wrong for an `extern interface` (the alias is just a source-level
// name for some *other*, real JVM class -- e.g. "Run" standing in for "java/lang/Runnable").
// Every method-descriptor / ANEWARRAY / INVOKEINTERFACE-owner call site that touches a
// possibly-Dyn type needs to go through this instead of the bare `.descriptor()`/
// `.interfaceName`, translating the alias to its real binary name when there is one, or
// qualifying it with its module otherwise.
private fun jvmIfaceName(interfaceName: String, moduleOf: Map<String, String?>, externInterfaceBinaryNames: Map<String, String>): String =
    externInterfaceBinaryNames[interfaceName] ?: qualify(interfaceName, moduleOf)

// Like `Ty.descriptor()`, but routes every class-shaped Ty through the alias/module
// translation above instead of baking the bare declared name straight into the descriptor --
// `Ty.descriptor()` itself stays a simple, context-free function (used everywhere a name is
// already known to need no translation, e.g. an extern class's own absolute binary name).
private fun descOf(t: Ty, moduleOf: Map<String, String?>, externInterfaceBinaryNames: Map<String, String>): String = when (t) {
    is Ty.Dyn -> "L${jvmIfaceName(t.interfaceName, moduleOf, externInterfaceBinaryNames)};"
    is Ty.Struct -> "L${qualify(t.name, moduleOf)};"
    is Ty.Enum -> "L${qualify(t.name, moduleOf)};"
    is Ty.Array -> "[${descOf(t.elem, moduleOf, externInterfaceBinaryNames)}"
    else -> t.descriptor()
}

// Shared mutable flag so callers can tell, after generate(), whether the emitted bytecode
// references java.lang.foreign.* anywhere -- i.e. whether it needs a JDK 22+ `java` to run.
// A program that never touches `arena struct` gets plain JDK-17-compatible bytecode with
// zero such references, which matters for e.g. embedding as a Minecraft 1.20.1 (Java 17) mod.
class CodeGenUsage { var usesArena: Boolean = false }

/**
 * Emits one .class per struct (plain fields + all-args constructor) and a single
 * `mainClassName` class holding every top-level fn as a static method, plus a
 * standard `public static void main(String[])` entry point that forwards to the
 * user's `fn main()` if one exists.
 */
class CodeGen(
    private val program: Program,
    private val structs: Map<String, StructInfo>,
    private val fnRetTypes: Map<String, Ty>,
    private val fnParamTypes: Map<String, List<Ty>>,
    private val mainClassName: String,
    private val arenaLayouts: Map<String, ArenaLayout> = emptyMap(),
    private val interfaceDecls: List<InterfaceDecl> = emptyList(),
    private val interfaceSigs: Map<String, InterfaceInfo> = emptyMap(),
    private val structInterfaces: Map<String, Set<String>> = emptyMap(),
    private val interfaceImplFns: Map<String, List<FnDecl>> = emptyMap(),
    private val enums: Map<String, EnumInfo> = emptyMap(),
    private val statics: Map<String, StaticInfo> = emptyMap(),
    // `struct S extends C { }` -- `structSuperclass` maps S's name to C's declared alias;
    // `externClasses` (the full trusted-shape table, alias -> info) is what resolves that alias
    // to C's real binary name and declared constructor, both for S's own generated `<init>`
    // (genStruct) and for `S::new(args)` call sites (genStaticCall).
    private val structSuperclass: Map<String, String> = emptyMap(),
    private val externClasses: Map<String, ExternClassInfo> = emptyMap(),
    private val superclassOverrideFns: Map<String, List<FnDecl>> = emptyMap(),
) {
    private val usage = CodeGenUsage()
    val usesArena: Boolean get() = usage.usesArena

    // Every type-shaped declaration (struct/enum/non-extern interface) carries its own
    // declaring module, since different files/declarations in one compile can now belong to
    // different modules -- see the top-of-file `qualify` doc.
    private val moduleOf: Map<String, String?> = buildMap {
        for (s in program.structs) put(s.name, s.moduleName)
        for (i in interfaceDecls) if (i.externBinaryName == null) put(i.name, i.moduleName)
        for (e in enums.values) put(e.name, e.moduleName)
    }

    // `pub`-or-not per declaration, mapped straight onto `ACC_PUBLIC` vs package-private on the
    // generated class -- real JVM enforcement of cross-module visibility, not just a checker
    // opinion.
    private val visibleOf: Map<String, Boolean> = buildMap {
        for (s in program.structs) put(s.name, s.visible)
        for (i in interfaceDecls) if (i.externBinaryName == null) put(i.name, i.visible)
        for (e in enums.values) put(e.name, e.visible)
    }

    // Which module each top-level fn (including desugared impl/extend methods) was declared
    // in -- drives which per-module holder class it compiles onto (see genHolderClass). Unlike
    // types, a fn's *own* `pub`/private-ness is still only checker-tracked (every fn is emitted
    // as a real public or package-private JVM method either way -- see genFn -- but which
    // holder class it lands on doesn't yet vary per fn the way a struct's own class does).
    private val fnModuleOf: Map<String, String?> = program.fns.associate { it.name to it.moduleName }

    // The module `fn main()` lives in -- including the default/unnamed module (`null`) if
    // `main()` itself never declared one, which is the common case and must NOT fall through
    // to guessing a different module just because *some* other fn happens to have one. Only
    // when there's no `main()` at all (a "library" compile, e.g. examples/interop-style .hc
    // meant to be embedded rather than run) does this fall back to the first module any fn
    // declared. That module's holder class keeps the CLI-supplied `mainClassName` and gets the
    // real JVM `public static void main(String[])` entry point and the `read_line()` backing
    // reader; every other module's fns land on their own `$Fns` holder class in their own
    // package. `$` can't collide with a user identifier -- the lexer never accepts it in one.
    private val entryModuleName: String? = run {
        val mainFn = program.fns.firstOrNull { it.name == "main" }
        if (mainFn != null) mainFn.moduleName else fnModuleOf.values.firstOrNull { it != null }
    }

    private fun holderClassName(mod: String?): String {
        val simple = if (mod == entryModuleName) mainClassName else "\$Fns"
        val prefix = mod?.let { it.replace('.', '/') + "/" } ?: ""
        return prefix + simple
    }
    // Public so Main.kt knows the exact (already-qualified) binary name to launch for `hc run`.
    val entryHolderClassName: String = holderClassName(entryModuleName)
    private val fnOwnerClass: Map<String, String> = fnModuleOf.mapValues { (_, mod) -> holderClassName(mod) }

    // `pub static NAME: Type = init;` compiles onto its own declaring module's holder class
    // too, right alongside that module's fns -- a real static field, `pub`/private mapped onto
    // ACC_PUBLIC vs package-private same as everything else, initialized once in the holder's
    // own `<clinit>` (see genHolderClass).
    private val staticOwnerClass: Map<String, String> = statics.mapValues { (_, info) -> holderClassName(info.moduleName) }

    // `extern interface Alias = "some.Interface" { }` declares a shape for an existing JVM
    // interface -- there's no new class to emit for it (genInterface would try to define a
    // class literally named e.g. "java/lang/Runnable", clobbering the real one). A struct
    // implementing it needs the real binary name in its own `implements` clause instead of the
    // alias -- see genStruct.
    private val externInterfaceBinaryNames: Map<String, String> =
        interfaceDecls.mapNotNull { d -> d.externBinaryName?.let { d.name to it } }.toMap()

    private fun qualify(name: String) = qualify(name, moduleOf)
    private fun classAccess(name: String): Int = if (visibleOf[name] == true) ACC_PUBLIC else 0

    fun generate(): Map<String, ByteArray> {
        val out = mutableMapOf<String, ByteArray>()
        for (s in program.structs) out[qualify(s.name)] = genStruct(structs.getValue(s.name))
        for (i in interfaceDecls.filter { it.externBinaryName == null }) out[qualify(i.name)] = genInterface(i)
        for (e in enums.values) out[qualify(e.name)] = genEnum(e)
        // Always includes the entry module, even if it has zero fns/statics of its own (a
        // program with no `fn main()` at all, say) -- `hc run`/`hc build` still need a class to
        // exist there. Also includes any module that only ever declares a `static` and no fns.
        for (mod in fnModuleOf.values.toSet() + statics.values.map { it.moduleName } + entryModuleName) {
            out[holderClassName(mod)] = genHolderClass(
                mod,
                program.fns.filter { fnModuleOf[it.name] == mod },
                statics.values.filter { it.moduleName == mod },
            )
        }
        return out
    }

    // A flat tagged union: one class, `tag: Int` plus every variant's fields side by side,
    // namespaced `variant$field` so two variants can reuse a field name without colliding.
    // No inheritance -- deliberately, to match this language's no-struct-subtyping stance.
    private fun genEnum(info: EnumInfo): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        cw.visit(V17, classAccess(info.name) or ACC_FINAL, qualify(info.name), null, "java/lang/Object", null)
        cw.visitField(ACC_PUBLIC, "tag", "I", null, null).visitEnd()
        for (v in info.variants) {
            for ((fname, fty) in v.fields) {
                cw.visitField(ACC_PUBLIC, "${v.name}\$${fname}", descOf(fty, moduleOf, externInterfaceBinaryNames), null, null).visitEnd()
            }
        }
        val mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null)
        mv.visitCode()
        mv.visitVarInsn(ALOAD, 0)
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        mv.visitInsn(RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun newFnCodeGen(mv: MethodVisitor) = FnCodeGen(
        mv, structs, fnRetTypes, fnParamTypes, fnOwnerClass, entryHolderClassName, arenaLayouts, usage,
        interfaceSigs, structInterfaces, enums, moduleOf, externInterfaceBinaryNames,
        statics.mapValues { it.value.ty }, staticOwnerClass,
        structSuperclass, externClasses,
    )

    private fun emitReturnOrTrap(mv: MethodVisitor, retTy: Ty) {
        if (retTy == Ty.Unit_) {
            mv.visitInsn(RETURN)
        } else {
            // If control falls off the end without a return, that's a source bug (not yet
            // caught by the checker); trap at runtime instead of failing bytecode verification.
            mv.visitTypeInsn(NEW, "java/lang/IllegalStateException")
            mv.visitInsn(DUP)
            mv.visitLdcInsn("missing return")
            mv.visitMethodInsn(INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "(Ljava/lang/String;)V", false)
            mv.visitInsn(ATHROW)
        }
    }

    private fun genStruct(info: StructInfo): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        val ifaceNames = structInterfaces[info.name]?.map { jvmIfaceName(it, moduleOf, externInterfaceBinaryNames) }?.toTypedArray()
        // `struct S extends C { }` -- S's real JVM superclass is C's binary name, not
        // `java/lang/Object` (checker already guaranteed S has zero fields for this case, so
        // there's no all-fields constructor to reconcile with a superclass forwarding one).
        val superAlias = structSuperclass[info.name]
        val superExt = superAlias?.let { externClasses.getValue(it) }
        val superName = superExt?.binaryName ?: "java/lang/Object"
        cw.visit(V17, classAccess(info.name) or ACC_FINAL, qualify(info.name), null, superName, if (ifaceNames.isNullOrEmpty()) null else ifaceNames)

        for ((fname, fty) in info.fields) {
            cw.visitField(ACC_PUBLIC, fname, descOf(fty, moduleOf, externInterfaceBinaryNames), null, null).visitEnd()
        }

        if (superExt != null) {
            // One forwarding constructor per constructor `C` actually declares -- whichever
            // arg-count a given `S::new(args)` call site resolved against (see checkStaticCall)
            // needs a matching `<init>` to already exist on this class.
            for (ctor in superExt.methods.filter { it.isCtor }) {
                val ctorDesc = "(" + ctor.params.joinToString("") { descOf(it, moduleOf, externInterfaceBinaryNames) } + ")V"
                val cmv = cw.visitMethod(ACC_PUBLIC, "<init>", ctorDesc, null, null)
                cmv.visitCode()
                cmv.visitVarInsn(ALOAD, 0)
                var slot = 1
                for (pty in ctor.params) {
                    cmv.visitVarInsn(loadOpcode(pty), slot)
                    slot += if (pty.isWide()) 2 else 1
                }
                cmv.visitMethodInsn(INVOKESPECIAL, superName, "<init>", ctorDesc, false)
                cmv.visitInsn(RETURN)
                cmv.visitMaxs(0, 0)
                cmv.visitEnd()
            }
        } else {
            val ctorDesc = "(" + info.fields.joinToString("") { descOf(it.second, moduleOf, externInterfaceBinaryNames) } + ")V"
            val mv = cw.visitMethod(ACC_PUBLIC, "<init>", ctorDesc, null, null)
            mv.visitCode()
            mv.visitVarInsn(ALOAD, 0)
            mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            var slot = 1
            for ((fname, fty) in info.fields) {
                mv.visitVarInsn(ALOAD, 0)
                mv.visitVarInsn(loadOpcode(fty), slot)
                mv.visitFieldInsn(PUTFIELD, qualify(info.name), fname, descOf(fty, moduleOf, externInterfaceBinaryNames))
                slot += if (fty.isWide()) 2 else 1
            }
            mv.visitInsn(RETURN)
            mv.visitMaxs(0, 0)
            mv.visitEnd()
        }

        // Instance methods overriding an implemented interface's methods (dynamic dispatch
        // needs these to be real JVM instance methods on the struct's own class, unlike every
        // other method in this language, which is a static call with an explicit self param).
        for (m in interfaceImplFns[info.name].orEmpty()) {
            val ifaceName = structInterfaces[info.name]?.firstOrNull { interfaceSigs[it]?.methods?.containsKey(m.name) == true }
            val msig = ifaceName?.let { interfaceSigs.getValue(it).methods[m.name] } ?: continue
            genInstanceMethod(cw, m, Ty.Struct(info.name), msig.paramTys, msig.ret)
        }

        // `override fn` methods matching `struct S extends C { }`'s superclass `C` -- same
        // "real instance method on S's own class" shape as an interface override above, just
        // matched against C's declared method table (checker already validated the exact
        // match; re-resolving here just needs the same signature back for genInstanceMethod).
        if (superExt != null) {
            for (m in superclassOverrideFns[info.name].orEmpty()) {
                val em = superExt.methods.firstOrNull { it.name == m.name && !it.isCtor && !it.isStatic && it.params.size == m.params.size - 1 }
                    ?: continue
                genInstanceMethod(cw, m, Ty.Struct(info.name), em.params, em.retType)
            }
        }

        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun genInterface(decl: InterfaceDecl): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        // `interface Sub: Super` -> Sub's JVM interface extends Super's (Java interfaces
        // "extend" other interfaces via this same `interfaces` array, not `superName`). Every
        // supertrait here is necessarily a normal (non-extern) HC interface -- `extern
        // interface` never has `extends` -- so each one gets qualified the same way.
        val extends = decl.extends.map { qualify(it) }.toTypedArray()
        cw.visit(V17, classAccess(decl.name) or ACC_INTERFACE or ACC_ABSTRACT, qualify(decl.name), null, "java/lang/Object", if (extends.isEmpty()) null else extends)
        val sig = interfaceSigs.getValue(decl.name)
        for (m in decl.methods) {
            val msig = sig.methods.getValue(m.name)
            val desc = "(" + msig.paramTys.joinToString("") { descOf(it, moduleOf, externInterfaceBinaryNames) } + ")" + descOf(msig.ret, moduleOf, externInterfaceBinaryNames)
            if (m.body == null) {
                cw.visitMethod(ACC_PUBLIC or ACC_ABSTRACT, m.name, desc, null, null).visitEnd()
            } else {
                genInstanceMethod(cw, FnDecl(m.name, m.params, m.retType, m.body, m.line), Ty.Dyn(decl.name), msig.paramTys, msig.ret, ACC_PUBLIC)
            }
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    // Compiles `m` as a real (non-static) JVM instance method: `self` binds to the implicit
    // `this` at slot 0 rather than an explicit first descriptor param.
    private fun genInstanceMethod(cw: ClassWriter, m: FnDecl, selfTy: Ty, paramTys: List<Ty>, retTy: Ty, access: Int = ACC_PUBLIC) {
        val desc = "(" + paramTys.joinToString("") { descOf(it, moduleOf, externInterfaceBinaryNames) } + ")" + descOf(retTy, moduleOf, externInterfaceBinaryNames)
        val mv = cw.visitMethod(access, m.name, desc, null, null)
        mv.visitCode()
        val fnGen = newFnCodeGen(mv)
        fnGen.pushScope()
        fnGen.declareParam("self", selfTy)
        for ((p, ty) in m.params.drop(1).zip(paramTys)) fnGen.declareParam(p.name, ty)
        fnGen.genBlock(m.body!!)
        fnGen.popScope()
        emitReturnOrTrap(mv, retTy)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    // One class per module that any top-level fn (including a desugared impl/extend method)
    // declared in -- `mod == entryModuleName` gets the CLI-supplied `mainClassName`, the real
    // JVM entry point, and the `read_line()` backing reader; every other module just gets its
    // own fns as static methods on a `$Fns` holder in its own package. This is what makes a
    // `pub fn` in one module a genuinely separate, independently-linkable JVM method from a
    // same-named private one in another -- they're not even in the same class anymore.
    private fun genHolderClass(mod: String?, fnsForModule: List<FnDecl>, staticsForModule: List<StaticInfo>): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        val qname = holderClassName(mod)
        cw.visit(V17, ACC_PUBLIC or ACC_FINAL, qname, null, "java/lang/Object", null)
        val isEntry = mod == entryModuleName

        for (s in staticsForModule) {
            cw.visitField((if (s.visible) ACC_PUBLIC else 0) or ACC_STATIC, s.name, descOf(s.ty, moduleOf, externInterfaceBinaryNames), null, null).visitEnd()
        }

        // A single `<clinit>` covers both the `read_line()` backing reader (entry holder only)
        // and every `static` this module declares -- a class can only have one.
        if (isEntry || staticsForModule.isNotEmpty()) {
            val clinit = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null)
            clinit.visitCode()
            if (isEntry) {
                // Backing reader for the `read_line()` builtin -- one persistent BufferedReader
                // over System.in for the whole program's lifetime, not a fresh one per call:
                // BufferedReader reads ahead into its own internal buffer, so throwing the
                // instance away between calls would silently drop any already-buffered-but-
                // unread input past the current line. Lives only on the entry holder -- every
                // read_line() call site, regardless of which module's holder it's compiled
                // into, reaches this same one via `entryHolderClassName`.
                clinit.visitTypeInsn(NEW, "java/io/BufferedReader")
                clinit.visitInsn(DUP)
                clinit.visitTypeInsn(NEW, "java/io/InputStreamReader")
                clinit.visitInsn(DUP)
                clinit.visitFieldInsn(GETSTATIC, "java/lang/System", "in", "Ljava/io/InputStream;")
                clinit.visitMethodInsn(INVOKESPECIAL, "java/io/InputStreamReader", "<init>", "(Ljava/io/InputStream;)V", false)
                clinit.visitMethodInsn(INVOKESPECIAL, "java/io/BufferedReader", "<init>", "(Ljava/io/Reader;)V", false)
                clinit.visitFieldInsn(PUTSTATIC, qname, STDIN_FIELD, "Ljava/io/BufferedReader;")
            }
            if (staticsForModule.isNotEmpty()) {
                val fnGen = newFnCodeGen(clinit)
                for (s in staticsForModule) {
                    fnGen.pushScope()
                    fnGen.genStandaloneExpr(s.init)
                    fnGen.popScope()
                    clinit.visitFieldInsn(PUTSTATIC, qname, s.name, descOf(s.ty, moduleOf, externInterfaceBinaryNames))
                }
            }
            clinit.visitInsn(RETURN)
            clinit.visitMaxs(0, 0)
            clinit.visitEnd()
        }
        if (isEntry) {
            // `read_line()`'s field declaration -- kept here, right after its `<clinit>` init
            // above, rather than folded into the `staticsForModule` loop: it's compiler-owned,
            // not a user `static`.
            cw.visitField(ACC_PRIVATE or ACC_STATIC, STDIN_FIELD, "Ljava/io/BufferedReader;", null, null).visitEnd()
        }

        for (f in fnsForModule) genFn(cw, f)

        if (isEntry) {
            // public static void main(String[] args) -> forwards to user main() if present.
            val mv = cw.visitMethod(ACC_PUBLIC or ACC_STATIC, "main", "([Ljava/lang/String;)V", null, null)
            mv.visitCode()
            if (fnsForModule.any { it.name == "main" }) {
                mv.visitMethodInsn(INVOKESTATIC, qname, "main_", "()V", false)
            }
            mv.visitInsn(RETURN)
            mv.visitMaxs(0, 0)
            mv.visitEnd()
        }

        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun genFn(cw: ClassWriter, f: FnDecl) {
        val retTy = fnRetTypes.getValue(f.name)
        // Uses the checker's own fully-resolved param types (already correct for every Ty
        // variant, including an `extern class`'s real binary name) rather than recomputing
        // from the raw TypeRef -- recomputing here used to fall back to `Ty.Struct(t.name)`
        // for any name it didn't specifically recognize, which silently mismatched an extern
        // class's declaration-site descriptor ("LRandom;") against its call-site one
        // ("Ljava/util/Random;"), surfacing as a runtime NoClassDefFoundError for the bare
        // alias name instead of a compile error.
        val paramTys = fnParamTypes.getValue(f.name)
        val desc = "(" + paramTys.joinToString("") { descOf(it, moduleOf, externInterfaceBinaryNames) } + ")" + descOf(retTy, moduleOf, externInterfaceBinaryNames)
        // "main" is reserved for the JVM entry point wrapper above; rename the user fn.
        val jvmName = if (f.name == "main") "main_" else f.name
        // `f.visible` ("pub") maps straight onto ACC_PUBLIC vs package-private, same as a
        // struct/interface/enum's own class access -- real JVM enforcement. Impl/extend-
        // desugared methods are always `visible = true` (see Checker): they follow their
        // owning struct/trait's own visibility, not an independent per-method one.
        val access = (if (f.visible) ACC_PUBLIC else 0) or ACC_STATIC
        val mv = cw.visitMethod(access, jvmName, desc, null, null)
        mv.visitCode()

        val fnGen = newFnCodeGen(mv)
        fnGen.pushScope()
        for ((p, ty) in f.params.zip(paramTys)) {
            fnGen.declareParam(p.name, ty)
        }
        fnGen.genBlock(f.body)
        fnGen.popScope()
        emitReturnOrTrap(mv, retTy)

        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    private fun loadOpcode(ty: Ty): Int = when (ty) {
        Ty.Int_, Ty.Bool_ -> ILOAD
        Ty.Long_ -> LLOAD
        Ty.Float_ -> FLOAD
        Ty.Double_ -> DLOAD
        else -> ALOAD
    }
}

private class FnCodeGen(
    val mv: MethodVisitor,
    val structs: Map<String, StructInfo>,
    val fnRetTypes: Map<String, Ty>,
    val fnParamTypes: Map<String, List<Ty>>,
    // Which already-qualified holder class a given top-level fn name (drop fns included) was
    // compiled onto -- see CodeGen.fnOwnerClass. `entryHolderClassName` is the one exception
    // that's always the same class regardless of which fn is calling: the `read_line()`
    // backing reader only exists there.
    val fnOwnerClass: Map<String, String>,
    val entryHolderClassName: String,
    val arenaLayouts: Map<String, ArenaLayout>,
    val usage: CodeGenUsage,
    val interfaceSigs: Map<String, InterfaceInfo>,
    val structInterfaces: Map<String, Set<String>>,
    val enums: Map<String, EnumInfo>,
    val moduleOf: Map<String, String?>,
    val externInterfaceBinaryNames: Map<String, String>,
    // Every `static`'s type and already-qualified owning holder class -- checked (via `resolve`
    // in the checker) as ordinary Idents, but codegen has to intercept them before local-slot
    // resolution: there's no slot, they're GETSTATIC/PUTSTATIC against a fixed field instead.
    val staticTypes: Map<String, Ty>,
    val staticOwnerClass: Map<String, String>,
    val structSuperclass: Map<String, String>,
    val externClasses: Map<String, ExternClassInfo>,
) {
    private fun qualify(name: String) = qualify(name, moduleOf)
    private fun staticDescOf(name: String) = descOf(staticTypes.getValue(name), moduleOf, externInterfaceBinaryNames)
    private val scopes = ArrayDeque<MutableMap<String, Pair<Int, Ty>>>()
    private var nextSlot = 0

    fun pushScope() = scopes.addLast(mutableMapOf())
    fun popScope() { scopes.removeLast() }

    // Entry point for a `static`'s own initializer, evaluated directly into a holder class's
    // `<clinit>` -- see CodeGen.genHolderClass. `genExpr` itself stays `private`; nothing else
    // outside this class should be generating expressions mid-body.
    fun genStandaloneExpr(e: Expr) = genExpr(e)

    fun declareParam(name: String, ty: Ty) {
        scopes.last()[name] = nextSlot to ty
        nextSlot += if (ty.isWide()) 2 else 1
    }

    private fun declareLocal(name: String, ty: Ty): Int {
        val slot = nextSlot
        scopes.last()[name] = slot to ty
        nextSlot += if (ty.isWide()) 2 else 1
        return slot
    }

    // A local slot with no source-level name, e.g. for the loop counter in `[value; count]`.
    // Never itself wide -- every caller uses it for an Int (array indices/lengths, etc.).
    private fun allocTemp(): Int {
        val slot = nextSlot
        nextSlot += 1
        return slot
    }

    private fun resolve(name: String): Pair<Int, Ty> {
        for (i in scopes.indices.reversed()) scopes[i][name]?.let { return it }
        throw CodeGenError("codegen: unresolved variable '$name'")
    }

    fun genBlock(block: Block) {
        pushScope()
        for (s in block.stmts) genStmt(s)
        for (name in block.dropsAtEnd) genDropCall(name)
        popScope()
    }

    private fun genDropCall(varName: String) {
        val (slot, ty) = resolve(varName)
        val structName = (ty as Ty.Struct).name
        val dropFn = "$structName\$drop"
        mv.visitVarInsn(ALOAD, slot)
        mv.visitMethodInsn(INVOKESTATIC, fnOwnerClass.getValue(dropFn), dropFn, "(L${qualify(structName)};)V", false)
    }

    private fun genStmt(stmt: Stmt) {
        when (stmt) {
            is Stmt.Let -> {
                val ty = stmt.init.ty!!
                genExpr(stmt.init)
                val slot = declareLocal(stmt.name, ty)
                mv.visitVarInsn(storeOpcode(ty), slot)
            }
            is Stmt.ExprStmt -> {
                genExpr(stmt.expr)
                if (stmt.expr.ty != Ty.Unit_) mv.visitInsn(if (isWide(stmt.expr.ty!!)) POP2 else POP)
            }
            is Stmt.If -> {
                genExpr(stmt.cond)
                val elseLabel = Label()
                val endLabel = Label()
                mv.visitJumpInsn(IFEQ, elseLabel)
                genBlock(stmt.thenB)
                mv.visitJumpInsn(GOTO, endLabel)
                mv.visitLabel(elseLabel)
                stmt.elseB?.let { genBlock(it) }
                mv.visitLabel(endLabel)
            }
            is Stmt.While -> {
                val start = Label()
                val end = Label()
                mv.visitLabel(start)
                genExpr(stmt.cond)
                mv.visitJumpInsn(IFEQ, end)
                genBlock(stmt.body)
                mv.visitJumpInsn(GOTO, start)
                mv.visitLabel(end)
            }
            is Stmt.For -> genFor(stmt)
            is Stmt.Match -> genMatch(stmt)
            is Stmt.Return -> {
                if (stmt.expr == null) {
                    for (name in stmt.varsToDropBeforeReturn) genDropCall(name)
                    mv.visitInsn(RETURN)
                } else {
                    genExpr(stmt.expr)
                    for (name in stmt.varsToDropBeforeReturn) genDropCall(name)
                    mv.visitInsn(returnOpcode(stmt.expr.ty!!))
                }
            }
            is Stmt.Nested -> genBlock(stmt.block)
            is Stmt.Try -> genTry(stmt)
            is Stmt.Throw -> {
                genExpr(stmt.expr)
                mv.visitInsn(ATHROW)
            }
        }
    }

    // Real JVM exception handling via ASM `visitTryCatchBlock` -- registered up front (the
    // `javac`-style convention) against the try body's start/end labels, one entry per catch
    // clause sharing that same range but each with its own handler label and exception binary
    // name. No `finally` support yet (see README's Error handling section).
    private fun genTry(stmt: Stmt.Try) {
        val tryStart = Label()
        val tryEnd = Label()
        val end = Label()
        val handlers = stmt.catches.map { Label() }

        for ((i, c) in stmt.catches.withIndex()) {
            val excTy = c.resolvedTy as Ty.JavaExtern
            mv.visitTryCatchBlock(tryStart, tryEnd, handlers[i], excTy.binaryName)
        }

        mv.visitLabel(tryStart)
        genBlock(stmt.tryBlock)
        mv.visitLabel(tryEnd)
        mv.visitJumpInsn(GOTO, end)

        for ((i, c) in stmt.catches.withIndex()) {
            mv.visitLabel(handlers[i])
            pushScope()
            val slot = declareLocal(c.varName, c.resolvedTy!!)
            mv.visitVarInsn(ASTORE, slot)
            genBlock(c.body)
            popScope()
            if (i != stmt.catches.lastIndex) mv.visitJumpInsn(GOTO, end)
        }
        mv.visitLabel(end)
    }

    private fun genExpr(expr: Expr) {
        when (expr) {
            is Expr.IntLit -> mv.visitLdcInsn(expr.value)
            is Expr.LongLit -> mv.visitLdcInsn(expr.value)
            is Expr.FloatLit -> mv.visitLdcInsn(expr.value)
            is Expr.DoubleLit -> mv.visitLdcInsn(expr.value)
            is Expr.BoolLit -> mv.visitInsn(if (expr.value) ICONST_1 else ICONST_0)
            // Never actually reached in a valid program -- genBinary intercepts `x == null`/
            // `x != null` before either side is generated generically. ACONST_NULL as an inert
            // fallback rather than throwing, consistent with how other codegen paths trust the
            // checker to have already ruled out the cases they don't specifically handle.
            is Expr.NullLit -> mv.visitInsn(ACONST_NULL)
            is Expr.StringLit -> mv.visitLdcInsn(expr.value)
            is Expr.Ident -> {
                val enumName = expr.resolvedName
                if (enumName != null) {
                    genEnumConstruct(enumName, expr.name, emptyMap()) // bare unit-variant construction, e.g. `Point`
                } else if (staticTypes.containsKey(expr.name)) {
                    mv.visitFieldInsn(GETSTATIC, staticOwnerClass.getValue(expr.name), expr.name, staticDescOf(expr.name))
                } else {
                    val (slot, ty) = resolve(expr.name)
                    mv.visitVarInsn(loadOpcode(ty), slot)
                }
            }
            is Expr.Borrow -> genExpr(expr.inner)
            is Expr.Unary -> genUnary(expr)
            is Expr.Cast -> genCast(expr)
            is Expr.Binary -> genBinary(expr)
            is Expr.StringInterp -> genStringInterp(expr)
            is Expr.Assign -> {
                genExpr(expr.value)
                if (staticTypes.containsKey(expr.name)) {
                    val ty = staticTypes.getValue(expr.name)
                    mv.visitInsn(if (isWide(ty)) DUP2 else DUP)
                    mv.visitFieldInsn(PUTSTATIC, staticOwnerClass.getValue(expr.name), expr.name, staticDescOf(expr.name))
                } else {
                    val (slot, ty) = resolve(expr.name)
                    mv.visitInsn(if (isWide(ty)) DUP2 else DUP)
                    mv.visitVarInsn(storeOpcode(ty), slot)
                }
            }
            is Expr.FieldAccess -> {
                val idxObj = expr.obj as? Expr.Index
                if (idxObj != null && idxObj.ty is Ty.Arena) {
                    genArenaFieldGet(idxObj, expr.field)
                } else {
                    genExpr(expr.obj)
                    if (expr.obj.ty is Ty.Array) {
                        mv.visitInsn(ARRAYLENGTH)
                    } else {
                        val structName = (expr.obj.ty as Ty.Struct).name
                        val fty = structs.getValue(structName).fieldType(expr.field)!!
                        mv.visitFieldInsn(GETFIELD, qualify(structName), expr.field, descOf(fty, moduleOf, externInterfaceBinaryNames))
                    }
                }
            }
            is Expr.StructLit -> genStructLit(expr)
            is Expr.Call -> genCall(expr)
            is Expr.MethodCall -> genMethodCall(expr)
            is Expr.StaticCall -> genStaticCall(expr)
            is Expr.FieldAssign -> {
                val idxObj = expr.obj as? Expr.Index
                if (idxObj != null && idxObj.ty is Ty.Arena) {
                    genArenaFieldSet(idxObj, expr.field, expr.value)
                } else {
                    genExpr(expr.obj)
                    genExpr(expr.value)
                    val structName = (expr.obj.ty as Ty.Struct).name
                    val fty = structs.getValue(structName).fieldType(expr.field)!!
                    mv.visitFieldInsn(PUTFIELD, qualify(structName), expr.field, descOf(fty, moduleOf, externInterfaceBinaryNames))
                }
            }
            is Expr.ArrayLit -> genArrayLit(expr)
            is Expr.ArrayRepeat -> genArrayRepeat(expr)
            is Expr.Index -> {
                genExpr(expr.arr)
                genExpr(expr.index)
                val elemTy = (expr.arr.ty as Ty.Array).elem
                mv.visitInsn(arrayLoadOpcode(elemTy))
            }
            is Expr.IndexAssign -> {
                genExpr(expr.arr)
                genExpr(expr.index)
                genExpr(expr.value)
                val elemTy = (expr.arr.ty as Ty.Array).elem
                mv.visitInsn(arrayStoreOpcode(elemTy))
            }
            is Expr.ArenaNew -> genArenaNew(expr)
            is Expr.Range -> throw CodeGenError("codegen: bare range (checker should have rejected this)")
        }
    }

    // `for x in a..b { }` -- counts an Int local from `start` (inclusive) to `end` (exclusive).
    private fun genForRange(stmt: Stmt.For, range: Expr.Range) {
        genExpr(range.start)
        pushScope()
        val varSlot = declareLocal(stmt.varName, Ty.Int_)
        mv.visitVarInsn(ISTORE, varSlot)
        genExpr(range.end)
        val endSlot = allocTemp()
        mv.visitVarInsn(ISTORE, endSlot)

        val loopStart = Label()
        val loopEnd = Label()
        mv.visitLabel(loopStart)
        mv.visitVarInsn(ILOAD, varSlot)
        mv.visitVarInsn(ILOAD, endSlot)
        mv.visitJumpInsn(IF_ICMPGE, loopEnd)
        genBlock(stmt.body)
        mv.visitIincInsn(varSlot, 1)
        mv.visitJumpInsn(GOTO, loopStart)
        mv.visitLabel(loopEnd)
        popScope()
    }

    // `for x in arr { }` -- indexes through the array, loading each element into a fresh local.
    private fun genForArray(stmt: Stmt.For, arrExpr: Expr) {
        val elemTy = (arrExpr.ty as Ty.Array).elem
        genExpr(arrExpr)
        val arrSlot = allocTemp()
        mv.visitVarInsn(ASTORE, arrSlot)
        val idxSlot = allocTemp()
        mv.visitInsn(ICONST_0)
        mv.visitVarInsn(ISTORE, idxSlot)
        val lenSlot = allocTemp()
        mv.visitVarInsn(ALOAD, arrSlot)
        mv.visitInsn(ARRAYLENGTH)
        mv.visitVarInsn(ISTORE, lenSlot)

        pushScope()
        val varSlot = declareLocal(stmt.varName, elemTy)

        val loopStart = Label()
        val loopEnd = Label()
        mv.visitLabel(loopStart)
        mv.visitVarInsn(ILOAD, idxSlot)
        mv.visitVarInsn(ILOAD, lenSlot)
        mv.visitJumpInsn(IF_ICMPGE, loopEnd)
        mv.visitVarInsn(ALOAD, arrSlot)
        mv.visitVarInsn(ILOAD, idxSlot)
        mv.visitInsn(arrayLoadOpcode(elemTy))
        mv.visitVarInsn(storeOpcode(elemTy), varSlot)
        genBlock(stmt.body)
        mv.visitIincInsn(idxSlot, 1)
        mv.visitJumpInsn(GOTO, loopStart)
        mv.visitLabel(loopEnd)
        popScope()
    }

    private fun genFor(stmt: Stmt.For) {
        val iterable = stmt.iterable
        if (iterable is Expr.Range) genForRange(stmt, iterable) else genForArray(stmt, iterable)
    }

    private fun genArrayLit(expr: Expr.ArrayLit) {
        val elemTy = (expr.ty as Ty.Array).elem
        mv.visitLdcInsn(expr.elements.size)
        emitNewArray(elemTy)
        for ((i, el) in expr.elements.withIndex()) {
            mv.visitInsn(DUP)
            mv.visitLdcInsn(i)
            genExpr(el)
            mv.visitInsn(arrayStoreOpcode(elemTy))
        }
    }

    // `[value; count]`: since `count` is only known at runtime, this fills the array with
    // a small bytecode loop rather than unrolling.
    private fun genArrayRepeat(expr: Expr.ArrayRepeat) {
        val elemTy = (expr.ty as Ty.Array).elem
        genExpr(expr.count)
        mv.visitInsn(DUP)
        val countSlot = allocTemp()
        mv.visitVarInsn(ISTORE, countSlot)
        emitNewArray(elemTy)
        val arrSlot = allocTemp()
        mv.visitVarInsn(ASTORE, arrSlot)
        val idxSlot = allocTemp()
        mv.visitInsn(ICONST_0)
        mv.visitVarInsn(ISTORE, idxSlot)

        val loopStart = Label()
        val loopEnd = Label()
        mv.visitLabel(loopStart)
        mv.visitVarInsn(ILOAD, idxSlot)
        mv.visitVarInsn(ILOAD, countSlot)
        mv.visitJumpInsn(IF_ICMPGE, loopEnd)
        mv.visitVarInsn(ALOAD, arrSlot)
        mv.visitVarInsn(ILOAD, idxSlot)
        genExpr(expr.value)
        mv.visitInsn(arrayStoreOpcode(elemTy))
        mv.visitIincInsn(idxSlot, 1)
        mv.visitJumpInsn(GOTO, loopStart)
        mv.visitLabel(loopEnd)
        mv.visitVarInsn(ALOAD, arrSlot)
    }

    private fun emitNewArray(elemTy: Ty) {
        when (elemTy) {
            Ty.Int_ -> mv.visitIntInsn(NEWARRAY, T_INT)
            Ty.Long_ -> mv.visitIntInsn(NEWARRAY, T_LONG)
            Ty.Float_ -> mv.visitIntInsn(NEWARRAY, T_FLOAT)
            Ty.Double_ -> mv.visitIntInsn(NEWARRAY, T_DOUBLE)
            Ty.Bool_ -> mv.visitIntInsn(NEWARRAY, T_BOOLEAN)
            Ty.Str_ -> mv.visitTypeInsn(ANEWARRAY, "java/lang/String")
            is Ty.Struct -> mv.visitTypeInsn(ANEWARRAY, qualify(elemTy.name))
            is Ty.Array -> mv.visitTypeInsn(ANEWARRAY, descOf(elemTy, moduleOf, externInterfaceBinaryNames))
            is Ty.Arena -> mv.visitTypeInsn(ANEWARRAY, "java/lang/foreign/MemorySegment")
            is Ty.Dyn -> mv.visitTypeInsn(ANEWARRAY, jvmIfaceName(elemTy.interfaceName, moduleOf, externInterfaceBinaryNames))
            is Ty.Enum -> mv.visitTypeInsn(ANEWARRAY, qualify(elemTy.name))
            is Ty.JavaExtern -> mv.visitTypeInsn(ANEWARRAY, elemTy.binaryName)
            Ty.Unit_ -> throw CodeGenError("codegen: array of Unit")
        }
    }

    private fun arrayLoadOpcode(elemTy: Ty): Int = when (elemTy) {
        Ty.Int_ -> IALOAD
        Ty.Long_ -> LALOAD
        Ty.Float_ -> FALOAD
        Ty.Double_ -> DALOAD
        Ty.Bool_ -> BALOAD
        else -> AALOAD
    }
    private fun arrayStoreOpcode(elemTy: Ty): Int = when (elemTy) {
        Ty.Int_ -> IASTORE
        Ty.Long_ -> LASTORE
        Ty.Float_ -> FASTORE
        Ty.Double_ -> DASTORE
        Ty.Bool_ -> BASTORE
        else -> AASTORE
    }

    // `arena Particle[count]` -> Arena.ofAuto().allocate(count * elemSize, 4L)
    private fun genArenaNew(expr: Expr.ArenaNew) {
        usage.usesArena = true
        val layout = arenaLayouts.getValue(expr.structName)
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/foreign/Arena", "ofAuto", "()Ljava/lang/foreign/Arena;", true)
        genExpr(expr.count)
        mv.visitLdcInsn(layout.elemSize)
        mv.visitInsn(IMUL)
        mv.visitInsn(I2L)
        mv.visitLdcInsn(4L) // byte alignment -- every field is 4 bytes, so 4-byte alignment suffices
        mv.visitMethodInsn(INVOKEINTERFACE, "java/lang/foreign/Arena", "allocate", "(JJ)Ljava/lang/foreign/MemorySegment;", true)
    }

    // stack effect: pushes the offset (long) of `buf[index]`'s `field` within the segment
    private fun pushArenaOffset(idxObj: Expr.Index, field: String, layout: ArenaLayout) {
        genExpr(idxObj.index)
        mv.visitLdcInsn(layout.elemSize)
        mv.visitInsn(IMUL)
        mv.visitLdcInsn(layout.fieldOffset(field))
        mv.visitInsn(IADD)
        mv.visitInsn(I2L)
    }

    private fun valueLayoutField(fieldTy: Ty): Triple<String, String, String> = when (fieldTy) {
        Ty.Bool_ -> Triple("JAVA_BOOLEAN", "Ljava/lang/foreign/ValueLayout\$OfBoolean;", "Z")
        Ty.Long_ -> Triple("JAVA_LONG", "Ljava/lang/foreign/ValueLayout\$OfLong;", "J")
        else -> Triple("JAVA_INT", "Ljava/lang/foreign/ValueLayout\$OfInt;", "I")
    }

    private fun genArenaFieldGet(idxObj: Expr.Index, field: String) {
        usage.usesArena = true
        val structName = (idxObj.ty as Ty.Arena).structName
        val layout = arenaLayouts.getValue(structName)
        val fieldTy = layout.fieldType(field)!!
        val (layoutField, layoutDesc, primDesc) = valueLayoutField(fieldTy)
        genExpr(idxObj.arr)
        mv.visitFieldInsn(GETSTATIC, "java/lang/foreign/ValueLayout", layoutField, layoutDesc)
        pushArenaOffset(idxObj, field, layout)
        mv.visitMethodInsn(INVOKEINTERFACE, "java/lang/foreign/MemorySegment", "get", "($layoutDesc J)$primDesc".replace(" ", ""), true)
    }

    private fun genArenaFieldSet(idxObj: Expr.Index, field: String, value: Expr) {
        usage.usesArena = true
        val structName = (idxObj.ty as Ty.Arena).structName
        val layout = arenaLayouts.getValue(structName)
        val fieldTy = layout.fieldType(field)!!
        val (layoutField, layoutDesc, primDesc) = valueLayoutField(fieldTy)
        genExpr(idxObj.arr)
        mv.visitFieldInsn(GETSTATIC, "java/lang/foreign/ValueLayout", layoutField, layoutDesc)
        pushArenaOffset(idxObj, field, layout)
        genExpr(value)
        mv.visitMethodInsn(INVOKEINTERFACE, "java/lang/foreign/MemorySegment", "set", "($layoutDesc J$primDesc)V".replace(" ", ""), true)
    }

    private fun genStaticCall(expr: Expr.StaticCall) {
        if (expr.method == "new" && structSuperclass.containsKey(expr.typeName)) {
            // `S::new(args)` for `struct S extends C { }` -- NEW+DUP+args+INVOKESPECIAL against
            // S's own generated class, not C's binary name: S's `<init>` (see genStruct) is the
            // one that internally forwards these exact args straight to C's real constructor.
            val qname = qualify(expr.typeName)
            mv.visitTypeInsn(NEW, qname)
            mv.visitInsn(DUP)
            for (a in expr.args) genExpr(a)
            val paramTys = expr.externParamTys!!
            val desc = "(" + paramTys.joinToString("") { descOf(it, moduleOf, externInterfaceBinaryNames) } + ")V"
            mv.visitMethodInsn(INVOKESPECIAL, qname, "<init>", desc, false)
            return
        }
        val binaryName = expr.resolvedName!!
        val paramTys = expr.externParamTys
        if (paramTys != null) {
            // Extern class call
            if (expr.method == "new") {
                mv.visitTypeInsn(NEW, binaryName)
                mv.visitInsn(DUP)
                for (a in expr.args) genExpr(a)
                val desc = "(" + paramTys.joinToString("") { descOf(it, moduleOf, externInterfaceBinaryNames) } + ")V"
                mv.visitMethodInsn(INVOKESPECIAL, binaryName, "<init>", desc, false)
            } else {
                for (a in expr.args) genExpr(a)
                val desc = "(" + paramTys.joinToString("") { descOf(it, moduleOf, externInterfaceBinaryNames) } + ")" + descOf(expr.ty!!, moduleOf, externInterfaceBinaryNames)
                mv.visitMethodInsn(INVOKESTATIC, binaryName, expr.method, desc, false)
            }
        } else {
            // Native Hot Chocolate static call (mangled name)
            for (a in expr.args) genExpr(a)
            val retTy = expr.ty!!
            val paramTys_ = fnParamTypes.getValue(binaryName)
            val desc = "(" + paramTys_.joinToString("") { descOf(it, moduleOf, externInterfaceBinaryNames) } + ")" + descOf(retTy, moduleOf, externInterfaceBinaryNames)
            mv.visitMethodInsn(INVOKESTATIC, fnOwnerClass.getValue(binaryName), binaryName, desc, false)
        }
    }

    private fun genMethodCall(expr: Expr.MethodCall) {
        genExpr(expr.recv)
        for (a in expr.args) genExpr(a)
        val target = expr.resolvedName!!
        val dynOwner = expr.dynamicOwner
        val instOwner = expr.instanceOwner
        val externOwner = expr.externOwner
        if (externOwner != null) {
            val paramTys = expr.externParamTys!!
            // If the checker identified this as static (via isStatic flag in ExternMethodInfo),
            // use INVOKESTATIC. Otherwise, it's an instance method (INVOKEVIRTUAL).
            // Args were already pushed by the unconditional prologue above -- do not push again.
            val desc = "(" + paramTys.joinToString("") { descOf(it, moduleOf, externInterfaceBinaryNames) } + ")" + descOf(expr.ty!!, moduleOf, externInterfaceBinaryNames)
            val opcode = if (expr.isStaticExtern) INVOKESTATIC else INVOKEVIRTUAL
            mv.visitMethodInsn(opcode, externOwner, target, desc, false)
            return
        }
        when {
            dynOwner != null -> {
                // `x: &dyn Interface` -- concrete implementer unknown until runtime. `dynOwner`
                // is the declared alias (e.g. "Run"); the real INVOKEINTERFACE owner is its
                // binary name when it's an `extern interface` (e.g. "java/lang/Runnable"), or
                // the package-qualified generated class name otherwise.
                val msig = interfaceSigs.getValue(dynOwner).methods.getValue(expr.method)
                val desc = "(" + msig.paramTys.joinToString("") { descOf(it, moduleOf, externInterfaceBinaryNames) } + ")" + descOf(msig.ret, moduleOf, externInterfaceBinaryNames)
                mv.visitMethodInsn(INVOKEINTERFACE, jvmIfaceName(dynOwner, moduleOf, externInterfaceBinaryNames), target, desc, true)
            }
            instOwner != null -> {
                // Concretely-typed struct calling one of its own interface-method overrides:
                // known class at compile time, but it's a real instance method, not our usual
                // static-desugared one, so INVOKEVIRTUAL rather than INVOKESTATIC. Mirrors the
                // checker's own dispatch preference: an explicit override (registered under
                // "Struct@method") is unambiguous even if several implemented interfaces share
                // the method name; only fall back to an interface's signature for an inherited,
                // non-overridden default (which the checker already guaranteed is unambiguous).
                // `overrideKey`/lookups below stay keyed by the *unqualified* `instOwner` --
                // only the actual bytecode owner argument needs qualifying.
                val overrideKey = "$instOwner@$target"
                val desc = if (fnRetTypes.containsKey(overrideKey)) {
                    val retTy = fnRetTypes.getValue(overrideKey)
                    val paramTys = fnParamTypes.getValue(overrideKey).drop(1) // drop self: instance methods exclude it from the descriptor
                    "(" + paramTys.joinToString("") { descOf(it, moduleOf, externInterfaceBinaryNames) } + ")" + descOf(retTy, moduleOf, externInterfaceBinaryNames)
                } else {
                    val ifaceName = structInterfaces[instOwner]?.firstOrNull { interfaceSigs[it]?.methods?.containsKey(expr.method) == true }
                    val msig = ifaceName?.let { interfaceSigs.getValue(it).methods.getValue(expr.method) }
                        ?: throw CodeGenError("codegen: no interface signature for $instOwner.${expr.method}")
                    "(" + msig.paramTys.joinToString("") { descOf(it, moduleOf, externInterfaceBinaryNames) } + ")" + descOf(msig.ret, moduleOf, externInterfaceBinaryNames)
                }
                mv.visitMethodInsn(INVOKEVIRTUAL, qualify(instOwner), target, desc, false)
            }
            else -> {
                val retTy = fnRetTypes.getValue(target)
                val paramTys = fnParamTypes.getValue(target) // includes self at index 0
                val desc = "(" + paramTys.joinToString("") { descOf(it, moduleOf, externInterfaceBinaryNames) } + ")" + descOf(retTy, moduleOf, externInterfaceBinaryNames)
                mv.visitMethodInsn(INVOKESTATIC, fnOwnerClass.getValue(target), target, desc, false)
            }
        }
    }

    private fun genUnary(expr: Expr.Unary) {
        genExpr(expr.expr)
        when (expr.op) {
            "-" -> when (expr.expr.ty) {
                Ty.Long_ -> mv.visitInsn(LNEG)
                Ty.Float_ -> mv.visitInsn(FNEG)
                Ty.Double_ -> mv.visitInsn(DNEG)
                else -> mv.visitInsn(INEG)
            }
            "!" -> {
                mv.visitInsn(ICONST_1)
                mv.visitInsn(IXOR)
            }
        }
    }

    // `expr as Type` -- a genuine primitive JVM conversion instruction, never a method call.
    // Same-type casts (checker allows `x as Int` where `x` is already Int) are a no-op.
    private fun genCast(expr: Expr.Cast) {
        genExpr(expr.inner)
        val from = expr.inner.ty!!
        val to = expr.ty!!
        if (from == to) return
        val op = when (from) {
            Ty.Int_ -> when (to) {
                Ty.Long_ -> I2L
                Ty.Float_ -> I2F
                else -> I2D
            }
            Ty.Long_ -> when (to) {
                Ty.Int_ -> L2I
                Ty.Float_ -> L2F
                else -> L2D
            }
            Ty.Float_ -> when (to) {
                Ty.Int_ -> F2I
                Ty.Long_ -> F2L
                else -> F2D
            }
            Ty.Double_ -> when (to) {
                Ty.Int_ -> D2I
                Ty.Long_ -> D2L
                else -> D2F
            }
            else -> throw CodeGenError("codegen: invalid cast from $from to $to")
        }
        mv.visitInsn(op)
    }

    // Emits a `Label` push-0-or-1 pattern common to every comparison below: jump to `trueL` on
    // `branchOp`, else push 0 and fall through to `endL`; `trueL` pushes 1.
    private fun genBoolFromBranch(branchOp: Int) {
        val trueL = Label(); val endL = Label()
        mv.visitJumpInsn(branchOp, trueL)
        mv.visitInsn(ICONST_0)
        mv.visitJumpInsn(GOTO, endL)
        mv.visitLabel(trueL)
        mv.visitInsn(ICONST_1)
        mv.visitLabel(endL)
    }

    // Desugars to a chain of StringBuilder.append calls, one per literal segment (skipping
    // empty ones -- a leading/trailing/adjacent `{}` produces an empty literal segment that
    // contributes nothing) and one per embedded expression, picking the `append` overload that
    // matches its checked type directly (no boxing) -- the same StringBuilder-chain shape
    // `genBinary`'s String `+` case already uses, just generalized to N parts instead of 2.
    private fun genStringInterp(expr: Expr.StringInterp) {
        mv.visitTypeInsn(NEW, "java/lang/StringBuilder")
        mv.visitInsn(DUP)
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false)
        fun appendLit(s: String) {
            if (s.isEmpty()) return
            mv.visitLdcInsn(s)
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false)
        }
        appendLit(expr.literals[0])
        for (i in expr.exprs.indices) {
            val e = expr.exprs[i]
            genExpr(e)
            val desc = when (e.ty) {
                Ty.Int_ -> "(I)Ljava/lang/StringBuilder;"
                Ty.Long_ -> "(J)Ljava/lang/StringBuilder;"
                Ty.Float_ -> "(F)Ljava/lang/StringBuilder;"
                Ty.Double_ -> "(D)Ljava/lang/StringBuilder;"
                Ty.Bool_ -> "(Z)Ljava/lang/StringBuilder;"
                else -> "(Ljava/lang/String;)Ljava/lang/StringBuilder;"
            }
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append", desc, false)
            appendLit(expr.literals[i + 1])
        }
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false)
    }

    // Real short-circuit branching, not eager-evaluate-both-sides-then-AND/OR: the right side
    // must never execute once the left side alone already determines the result (both for the
    // usual perf reason, and for correctness -- code like `i < len && !arr[i].isEmpty()` relies
    // on `arr[i]` never being read once `i < len` is false).
    private fun genLogical(expr: Expr.Binary) {
        val shortCircuitOn = if (expr.op == "&&") IFEQ else IFNE
        val shortCircuitResult = if (expr.op == "&&") ICONST_0 else ICONST_1
        val shortCircuitLabel = Label()
        val endLabel = Label()
        genExpr(expr.left)
        mv.visitJumpInsn(shortCircuitOn, shortCircuitLabel)
        genExpr(expr.right)
        mv.visitJumpInsn(GOTO, endLabel)
        mv.visitLabel(shortCircuitLabel)
        mv.visitInsn(shortCircuitResult)
        mv.visitLabel(endLabel)
    }

    private fun genBinary(expr: Expr.Binary) {
        if (expr.left is Expr.NullLit || expr.right is Expr.NullLit) {
            // `x == null`/`x != null` -- the JVM's own null-check branch instructions
            // (IFNULL/IFNONNULL) test the single reference on the stack directly, no need to
            // push an actual null constant and compare against it.
            val other = if (expr.left is Expr.NullLit) expr.right else expr.left
            genExpr(other)
            genBoolFromBranch(if (expr.op == "==") IFNULL else IFNONNULL)
            return
        }
        if (expr.op == "&&" || expr.op == "||") {
            genLogical(expr)
            return
        }
        val lty = expr.left.ty!!
        if (expr.op == "+" && lty == Ty.Str_) {
            mv.visitTypeInsn(NEW, "java/lang/StringBuilder")
            mv.visitInsn(DUP)
            mv.visitMethodInsn(INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false)
            genExpr(expr.left)
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false)
            genExpr(expr.right)
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false)
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false)
            return
        }
        if (expr.op == "==" || expr.op == "!=") {
            if (lty == Ty.Str_) {
                genExpr(expr.left)
                genExpr(expr.right)
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false)
                if (expr.op == "!=") {
                    mv.visitInsn(ICONST_1)
                    mv.visitInsn(IXOR)
                }
                return
            }
            genExpr(expr.left)
            genExpr(expr.right)
            if (lty == Ty.Long_) {
                mv.visitInsn(LCMP)
                genBoolFromBranch(if (expr.op == "==") IFEQ else IFNE)
                return
            }
            if (lty == Ty.Float_ || lty == Ty.Double_) {
                // NaN is never equal to anything, including itself -- CMPL/CMPG agree on the
                // sign for equality either way (any NaN operand yields a nonzero result), so
                // which one is used here doesn't matter for correctness, unlike </<=/>/>= below.
                mv.visitInsn(if (lty == Ty.Float_) FCMPL else DCMPL)
                genBoolFromBranch(if (expr.op == "==") IFEQ else IFNE)
                return
            }
            genBoolFromBranch(if (expr.op == "==") IF_ICMPEQ else IF_ICMPNE)
            return
        }
        genExpr(expr.left)
        genExpr(expr.right)
        if (lty == Ty.Long_) {
            val branchOp = when (expr.op) {
                "+" -> { mv.visitInsn(LADD); return }
                "-" -> { mv.visitInsn(LSUB); return }
                "*" -> { mv.visitInsn(LMUL); return }
                "/" -> { mv.visitInsn(LDIV); return }
                "%" -> { mv.visitInsn(LREM); return }
                "<" -> IFLT
                "<=" -> IFLE
                ">" -> IFGT
                else -> IFGE
            }
            mv.visitInsn(LCMP)
            genBoolFromBranch(branchOp)
            return
        }
        if (lty == Ty.Float_ || lty == Ty.Double_) {
            val isFloat = lty == Ty.Float_
            when (expr.op) {
                "+" -> mv.visitInsn(if (isFloat) FADD else DADD)
                "-" -> mv.visitInsn(if (isFloat) FSUB else DSUB)
                "*" -> mv.visitInsn(if (isFloat) FMUL else DMUL)
                "/" -> mv.visitInsn(if (isFloat) FDIV else DDIV)
                "%" -> mv.visitInsn(if (isFloat) FREM else DREM)
                "<", "<=", ">", ">=" -> {
                    // Matches javac's own NaN-safe convention: CMPG (NaN -> +1) for </<=, CMPL
                    // (NaN -> -1) for >/>=, so a comparison against NaN always comes out false,
                    // matching IEEE 754 -- rather than an arbitrary, NaN-inconsistent choice.
                    val useG = expr.op == "<" || expr.op == "<="
                    mv.visitInsn(if (isFloat) (if (useG) FCMPG else FCMPL) else (if (useG) DCMPG else DCMPL))
                    val branchOp = when (expr.op) {
                        "<" -> IFLT
                        "<=" -> IFLE
                        ">" -> IFGT
                        else -> IFGE
                    }
                    genBoolFromBranch(branchOp)
                }
            }
            return
        }
        when (expr.op) {
            "+" -> mv.visitInsn(IADD)
            "-" -> mv.visitInsn(ISUB)
            "*" -> mv.visitInsn(IMUL)
            "/" -> mv.visitInsn(IDIV)
            "%" -> mv.visitInsn(IREM)
            "<", "<=", ">", ">=" -> {
                val op = when (expr.op) {
                    "<" -> IF_ICMPLT
                    "<=" -> IF_ICMPLE
                    ">" -> IF_ICMPGT
                    else -> IF_ICMPGE
                }
                genBoolFromBranch(op)
            }
        }
    }

    private fun genStructLit(expr: Expr.StructLit) {
        val variantName = expr.enumVariant
        if (variantName != null) {
            genEnumConstruct(expr.resolvedName!!, variantName, expr.fields.toMap())
            return
        }
        val info = structs.getValue(expr.resolvedName ?: expr.typeName)
        mv.visitTypeInsn(NEW, qualify(info.name))
        mv.visitInsn(DUP)
        val given = expr.fields.toMap()
        for ((fname, fty) in info.fields) {
            genExpr(given.getValue(fname))
        }
        val ctorDesc = "(" + info.fields.joinToString("") { descOf(it.second, moduleOf, externInterfaceBinaryNames) } + ")V"
        mv.visitMethodInsn(INVOKESPECIAL, qualify(info.name), "<init>", ctorDesc, false)
    }

    // `EnumName::Variant { fields }` (or bare `Variant` for a unit variant, via genExpr's
    // Ident case): NEW + no-arg <init>, then set `tag` and each provided field by PUTFIELD.
    // Namespaced `variant$field` names mean the constructor never needs to know about *other*
    // variants' fields -- they're simply never touched, left at their JVM default (0/null).
    private fun genEnumConstruct(enumName: String, variantName: String, fieldValues: Map<String, Expr>) {
        val info = enums.getValue(enumName)
        val variant = info.variant(variantName)!!
        val qEnumName = qualify(enumName)
        mv.visitTypeInsn(NEW, qEnumName)
        mv.visitInsn(DUP)
        mv.visitMethodInsn(INVOKESPECIAL, qEnumName, "<init>", "()V", false)
        mv.visitInsn(DUP)
        mv.visitLdcInsn(variant.tag)
        mv.visitFieldInsn(PUTFIELD, qEnumName, "tag", "I")
        for ((fname, fty) in variant.fields) {
            mv.visitInsn(DUP)
            genExpr(fieldValues.getValue(fname))
            mv.visitFieldInsn(PUTFIELD, qEnumName, "$variantName\$$fname", descOf(fty, moduleOf, externInterfaceBinaryNames))
        }
    }

    // `match scrutinee { Variant { a, b } => { }, _ => { } }` -- an if/else chain comparing
    // `tag`, destructuring the matched variant's fields into fresh locals before its arm body.
    private fun genMatch(stmt: Stmt.Match) {
        if (stmt.scrutinee.ty is Ty.Dyn) { genSealedMatch(stmt); return }
        val enumName = (stmt.scrutinee.ty as Ty.Enum).name
        val qEnumName = qualify(enumName)
        val info = enums.getValue(enumName)
        genExpr(stmt.scrutinee)
        val scrutSlot = allocTemp()
        mv.visitVarInsn(ASTORE, scrutSlot)

        val endLabel = Label()
        var wildcardArm: MatchArm? = null
        for (arm in stmt.arms) {
            if (arm.variantName == null) { wildcardArm = arm; continue }
            val variant = info.variant(arm.variantName) ?: continue
            val nextLabel = Label()
            mv.visitVarInsn(ALOAD, scrutSlot)
            mv.visitFieldInsn(GETFIELD, qEnumName, "tag", "I")
            mv.visitLdcInsn(variant.tag)
            mv.visitJumpInsn(IF_ICMPNE, nextLabel)

            pushScope()
            for ((bindName, field) in arm.bindings.zip(variant.fields)) {
                val (fname, fty) = field
                mv.visitVarInsn(ALOAD, scrutSlot)
                mv.visitFieldInsn(GETFIELD, qEnumName, "${variant.name}\$${fname}", descOf(fty, moduleOf, externInterfaceBinaryNames))
                val slot = declareLocal(bindName, fty)
                mv.visitVarInsn(storeOpcode(fty), slot)
            }
            genBlock(arm.body)
            popScope()
            mv.visitJumpInsn(GOTO, endLabel)
            mv.visitLabel(nextLabel)
        }
        wildcardArm?.let { genBlock(it.body) }
        mv.visitLabel(endLabel)
    }

    // `match d { Player { hp } => { }, _ => { } }` where `d: &dyn SealedInterface` -- an
    // INSTANCEOF/CHECKCAST chain by concrete struct, not a tag compare (there's no single
    // shared class to hold a tag across independent struct classes the way an enum has).
    // Compiling the matched case's body this way, rather than an INVOKEINTERFACE call through
    // `d`, is the actual "cheaper dispatch" payoff of a sealed interface: once matched, any
    // method called on the destructured value is a direct zero-cost static call, not virtual.
    private fun genSealedMatch(stmt: Stmt.Match) {
        genExpr(stmt.scrutinee)
        val scrutSlot = allocTemp()
        mv.visitVarInsn(ASTORE, scrutSlot)

        val endLabel = Label()
        var wildcardArm: MatchArm? = null
        for (arm in stmt.arms) {
            if (arm.variantName == null) { wildcardArm = arm; continue }
            val structName = arm.variantName
            val qStructName = qualify(structName)
            val fields = structs[structName]?.fields ?: continue
            val nextLabel = Label()
            mv.visitVarInsn(ALOAD, scrutSlot)
            mv.visitTypeInsn(INSTANCEOF, qStructName)
            mv.visitJumpInsn(IFEQ, nextLabel)

            pushScope()
            mv.visitVarInsn(ALOAD, scrutSlot)
            mv.visitTypeInsn(CHECKCAST, qStructName)
            val castSlot = allocTemp()
            mv.visitVarInsn(ASTORE, castSlot)
            for ((bindName, field) in arm.bindings.zip(fields)) {
                val (fname, fty) = field
                mv.visitVarInsn(ALOAD, castSlot)
                mv.visitFieldInsn(GETFIELD, qStructName, fname, descOf(fty, moduleOf, externInterfaceBinaryNames))
                val slot = declareLocal(bindName, fty)
                mv.visitVarInsn(storeOpcode(fty), slot)
            }
            genBlock(arm.body)
            popScope()
            mv.visitJumpInsn(GOTO, endLabel)
            mv.visitLabel(nextLabel)
        }
        wildcardArm?.let { genBlock(it.body) }
        mv.visitLabel(endLabel)
    }

    private fun genCall(expr: Expr.Call) {
        if (expr.callee == "print") {
            mv.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;")
            val arg = expr.args[0]
            genExpr(arg)
            val desc = when (arg.ty) {
                Ty.Int_ -> "(I)V"
                Ty.Long_ -> "(J)V"
                Ty.Float_ -> "(F)V"
                Ty.Double_ -> "(D)V"
                Ty.Bool_ -> "(Z)V"
                else -> "(Ljava/lang/String;)V"
            }
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", desc, false)
            return
        }
        if (expr.callee == "read_line") {
            mv.visitFieldInsn(GETSTATIC, entryHolderClassName, STDIN_FIELD, "Ljava/io/BufferedReader;")
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/io/BufferedReader", "readLine", "()Ljava/lang/String;", false)
            return
        }
        for (a in expr.args) genExpr(a)
        val target = expr.resolvedName ?: expr.callee
        val retTy = fnRetTypes.getValue(target)
        // Built from the callee's own declared param types, not the caller's argument
        // expression types: those can legitimately differ now that a concrete struct is
        // compatible with a `&dyn Interface` parameter (subtyping) -- using the arg's own
        // type there would build a descriptor that doesn't match the compiled method at all.
        val paramTys = fnParamTypes.getValue(target)
        val desc = "(" + paramTys.joinToString("") { descOf(it, moduleOf, externInterfaceBinaryNames) } + ")" + descOf(retTy, moduleOf, externInterfaceBinaryNames)
        val jvmName = if (target == "main") "main_" else target
        mv.visitMethodInsn(INVOKESTATIC, fnOwnerClass.getValue(target), jvmName, desc, false)
    }

    private fun loadOpcode(ty: Ty): Int = when (ty) {
        Ty.Int_, Ty.Bool_ -> ILOAD
        Ty.Long_ -> LLOAD
        Ty.Float_ -> FLOAD
        Ty.Double_ -> DLOAD
        else -> ALOAD
    }
    private fun storeOpcode(ty: Ty): Int = when (ty) {
        Ty.Int_, Ty.Bool_ -> ISTORE
        Ty.Long_ -> LSTORE
        Ty.Float_ -> FSTORE
        Ty.Double_ -> DSTORE
        else -> ASTORE
    }
    private fun returnOpcode(ty: Ty): Int = when (ty) {
        Ty.Int_, Ty.Bool_ -> IRETURN
        Ty.Long_ -> LRETURN
        Ty.Float_ -> FRETURN
        Ty.Double_ -> DRETURN
        Ty.Unit_ -> RETURN
        else -> ARETURN
    }
    private fun isWide(ty: Ty): Boolean = ty.isWide()
}
