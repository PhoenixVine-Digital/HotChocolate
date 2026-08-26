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
import hc.sema.isObjectRef
import hc.sema.isWide
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.Type as AsmType

class CodeGenError(message: String) : RuntimeException(message)

private const val STDIN_FIELD = "\$in"

private fun qualify(name: String, moduleOf: Map<String, String?>): String {
    val mod = moduleOf[name] ?: return name
    return mod.replace('.', '/') + "/" + name
}

private fun jvmIfaceName(interfaceName: String, moduleOf: Map<String, String?>, externInterfaceBinaryNames: Map<String, String>): String =
    externInterfaceBinaryNames[interfaceName] ?: qualify(interfaceName, moduleOf)

private fun descOf(t: Ty, moduleOf: Map<String, String?>, externInterfaceBinaryNames: Map<String, String>): String = when (t) {
    is Ty.Dyn -> "L${jvmIfaceName(t.interfaceName, moduleOf, externInterfaceBinaryNames)};"
    is Ty.Struct -> "L${qualify(t.name, moduleOf)};"
    is Ty.Enum -> "L${qualify(t.name, moduleOf)};"
    is Ty.Array -> "[${descOf(t.elem, moduleOf, externInterfaceBinaryNames)}"
    else -> t.descriptor()
}

private fun collectLambdasInBlock(block: Block, out: MutableList<Expr.Lambda>) {
    for (s in block.stmts) collectLambdasInStmt(s, out)
}
private fun collectLambdasInStmt(s: Stmt, out: MutableList<Expr.Lambda>) {
    when (s) {
        is Stmt.Let -> collectLambdasInExpr(s.init, out)
        is Stmt.ExprStmt -> collectLambdasInExpr(s.expr, out)
        is Stmt.If -> { collectLambdasInExpr(s.cond, out); collectLambdasInBlock(s.thenB, out); s.elseB?.let { collectLambdasInBlock(it, out) } }
        is Stmt.While -> { collectLambdasInExpr(s.cond, out); collectLambdasInBlock(s.body, out) }
        is Stmt.For -> { collectLambdasInExpr(s.iterable, out); collectLambdasInBlock(s.body, out) }
        is Stmt.Return -> s.expr?.let { collectLambdasInExpr(it, out) }
        is Stmt.Nested -> collectLambdasInBlock(s.block, out)
        is Stmt.Match -> { collectLambdasInExpr(s.scrutinee, out); for (arm in s.arms) collectLambdasInBlock(arm.body, out) }
        is Stmt.Try -> { collectLambdasInBlock(s.tryBlock, out); for (c in s.catches) collectLambdasInBlock(c.body, out) }
        is Stmt.Throw -> collectLambdasInExpr(s.expr, out)
        is Stmt.Break, is Stmt.Continue -> {} 
        is Stmt.DevIf -> throw IllegalStateException("Stmt.DevIf reached collectLambdasInBlock -- Main.kt's stripDevCode should have already resolved every 'if dev { }' before codegen ever ran")
    }
}
private fun collectLambdasInExpr(e: Expr, out: MutableList<Expr.Lambda>) {
    if (e is Expr.Lambda) { out += e; collectLambdasInExpr(e.body, out); return }
    when (e) {
        is Expr.Binary -> { collectLambdasInExpr(e.left, out); collectLambdasInExpr(e.right, out) }
        is Expr.Unary -> collectLambdasInExpr(e.expr, out)
        is Expr.Cast -> collectLambdasInExpr(e.inner, out)
        is Expr.InstanceOf -> collectLambdasInExpr(e.inner, out)
        is Expr.Borrow -> collectLambdasInExpr(e.inner, out)
        is Expr.Assign -> collectLambdasInExpr(e.value, out)
        is Expr.Call -> e.args.forEach { collectLambdasInExpr(it, out) }
        is Expr.FieldAccess -> collectLambdasInExpr(e.obj, out)
        is Expr.FieldAssign -> { collectLambdasInExpr(e.obj, out); collectLambdasInExpr(e.value, out) }
        is Expr.StructLit -> e.fields.forEach { collectLambdasInExpr(it.second, out) }
        is Expr.MethodCall -> { collectLambdasInExpr(e.recv, out); e.args.forEach { collectLambdasInExpr(it, out) } }
        is Expr.StaticCall -> e.args.forEach { collectLambdasInExpr(it, out) }
        is Expr.ArrayLit -> e.elements.forEach { collectLambdasInExpr(it, out) }
        is Expr.ArrayRepeat -> { collectLambdasInExpr(e.value, out); collectLambdasInExpr(e.count, out) }
        is Expr.Index -> { collectLambdasInExpr(e.arr, out); collectLambdasInExpr(e.index, out) }
        is Expr.IndexAssign -> { collectLambdasInExpr(e.arr, out); collectLambdasInExpr(e.index, out); collectLambdasInExpr(e.value, out) }
        is Expr.ArenaNew -> collectLambdasInExpr(e.count, out)
        is Expr.StringInterp -> e.exprs.forEach { collectLambdasInExpr(it, out) }
        is Expr.If -> { collectLambdasInExpr(e.cond, out); collectLambdasInBlock(e.thenB, out); collectLambdasInBlock(e.elseB, out) }
        is Expr.Match -> { collectLambdasInExpr(e.scrutinee, out); for (arm in e.arms) collectLambdasInBlock(arm.body, out) }
        is Expr.Range -> { collectLambdasInExpr(e.start, out); collectLambdasInExpr(e.end, out) }
        else -> {} 
    }
}

private fun emitAnnotations(annotations: List<AnnotationUse>, visitAnn: (String, Boolean) -> org.objectweb.asm.AnnotationVisitor) {
    for (ann in annotations) {
        val av = visitAnn("L${ann.binaryName};", true)
        for ((argName, value) in ann.args) emitAnnotationValue(av, argName, value)
        av.visitEnd()
    }
}

private fun emitAnnotationValue(av: org.objectweb.asm.AnnotationVisitor, argName: String?, value: AnnotationValue) {
    when (value) {
        is AnnotationValue.Str -> av.visit(argName, value.value)
        is AnnotationValue.EnumConst -> av.visitEnum(argName, "L${value.enumBinaryName};", value.constName)
        is AnnotationValue.Arr -> {
            val arrAv = av.visitArray(argName)
            for (elem in value.values) emitAnnotationValue(arrAv, null, elem)
            arrAv.visitEnd()
        }
    }
}

class CodeGenUsage { var usesArena: Boolean = false }

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

    private val structSuperclass: Map<String, String> = emptyMap(),
    private val externClasses: Map<String, ExternClassInfo> = emptyMap(),
    private val superclassOverrideFns: Map<String, List<FnDecl>> = emptyMap(),

    private val entryClassName: String? = null,
    private val entryModid: String? = null,
    private val entryInitFn: String? = null,
) {
    private val usage = CodeGenUsage()
    val usesArena: Boolean get() = usage.usesArena

    private val moduleOf: Map<String, String?> = buildMap {
        for (s in program.structs) put(s.name, s.moduleName)
        for (i in interfaceDecls) if (i.externBinaryName == null) put(i.name, i.moduleName)
        for (e in enums.values) put(e.name, e.moduleName)
    }

    private val visibleOf: Map<String, Boolean> = buildMap {
        for (s in program.structs) put(s.name, s.visible)
        for (i in interfaceDecls) if (i.externBinaryName == null) put(i.name, i.visible)
        for (e in enums.values) put(e.name, e.visible)
    }

    private data class Unit(val module: String?, val sourceUnit: String?)

    private val fnUnitOf: Map<String, Unit> = program.fns.associate { it.name to Unit(it.moduleName, it.sourceUnit) }

    private val entryUnit: Unit = run {
        val mainFn = program.fns.firstOrNull { it.name == "main" }
        if (mainFn != null) Unit(mainFn.moduleName, mainFn.sourceUnit)
        else fnUnitOf.values.firstOrNull() ?: Unit(null, null)
    }

    private val hasRealMain: Boolean = program.fns.any { it.name == "main" }

    private val unitFirstStructName: Map<Unit, String> = buildMap {
        for (s in program.structs) {
            val u = Unit(s.moduleName, s.sourceUnit)

            val sharesMainClassName = u == entryUnit && s.name == mainClassName
            if (u != entryUnit || !hasRealMain || sharesMainClassName) putIfAbsent(u, s.name)
        }
    }

    private fun holderClassName(unit: Unit): String {
        val prefix = unit.module?.let { it.replace('.', '/') + "/" } ?: ""
        unitFirstStructName[unit]?.let { return qualify(it, moduleOf) }

        if (unit == entryUnit && (hasRealMain || unit.sourceUnit == null)) return prefix + mainClassName
        val simple = unit.sourceUnit?.replaceFirstChar { it.uppercase() } ?: "\$Fns"
        return prefix + simple
    }
    
    val entryHolderClassName: String = holderClassName(entryUnit)
    private val fnOwnerClass: Map<String, String> = fnUnitOf.mapValues { (_, unit) -> holderClassName(unit) }

    private val entryUnitFirstStructIsHolder: Boolean = unitFirstStructName.containsKey(entryUnit)

    private val staticOwnerClass: Map<String, String> = statics.mapValues { (_, info) -> holderClassName(Unit(info.moduleName, info.sourceUnit)) }

    private val externInterfaceBinaryNames: Map<String, String> =
        interfaceDecls.mapNotNull { d -> d.externBinaryName?.let { d.name to it } }.toMap()

    private fun qualify(name: String) = qualify(name, moduleOf)
    private fun classAccess(name: String): Int = if (visibleOf[name] == true) ACC_PUBLIC else 0

    private fun collectAllLambdas(): List<Pair<Expr.Lambda, String?>> {
        val out = mutableListOf<Pair<Expr.Lambda, String?>>()
        for (f in program.fns) {
            val found = mutableListOf<Expr.Lambda>()
            collectLambdasInBlock(f.body, found)
            for (l in found) out += l to f.moduleName
        }
        for (impl in program.impls) {
            val mod = program.structs.firstOrNull { it.name == impl.structName }?.moduleName
            for (m in impl.methods) {
                val found = mutableListOf<Expr.Lambda>()
                collectLambdasInBlock(m.body, found)
                for (l in found) out += l to mod
            }
        }
        for (ext in program.extends) {
            for (m in ext.methods) {
                val found = mutableListOf<Expr.Lambda>()
                collectLambdasInBlock(m.body, found)
                for (l in found) out += l to ext.moduleName
            }
        }

        for (s in statics.values) {
            val found = mutableListOf<Expr.Lambda>()
            collectLambdasInExpr(s.init, found)
            for (l in found) out += l to s.moduleName
        }
        return out
    }

    private fun genLambdaClasses(out: MutableMap<String, ByteArray>) {
        val all = collectAllLambdas()

        for ((lambda, mod) in all) {
            val base = lambda.syntheticName ?: continue
            lambda.syntheticName = if (mod != null) mod.replace('.', '/') + "/" + base else base
        }
        for ((lambda, _) in all) {
            val qname = lambda.syntheticName ?: continue
            out[qname] = genLambdaClass(lambda, qname)
        }
    }

    private fun genLambdaClass(lambda: Expr.Lambda, qname: String): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        cw.visit(V17, ACC_FINAL, qname, null, "java/lang/Object", arrayOf(lambda.targetBinaryName!!))
        cw.visitSource(sourceFileName(null), null)
        for (c in lambda.captures) {
            cw.visitField(ACC_PRIVATE or ACC_FINAL, "cap\$${c.name}", descOf(c.ty, moduleOf, externInterfaceBinaryNames), null, null).visitEnd()
        }
        val ctorDesc = "(" + lambda.captures.joinToString("") { descOf(it.ty, moduleOf, externInterfaceBinaryNames) } + ")V"
        val ctor = cw.visitMethod(ACC_PUBLIC, "<init>", ctorDesc, null, null)
        ctor.visitCode()
        ctor.visitVarInsn(ALOAD, 0)
        ctor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        var slot = 1
        for (c in lambda.captures) {
            ctor.visitVarInsn(ALOAD, 0)
            ctor.visitVarInsn(loadOpcode(c.ty), slot)
            ctor.visitFieldInsn(PUTFIELD, qname, "cap\$${c.name}", descOf(c.ty, moduleOf, externInterfaceBinaryNames))
            slot += if (c.ty.isWide()) 2 else 1
        }
        ctor.visitInsn(RETURN)
        ctor.visitMaxs(0, 0)
        ctor.visitEnd()

        val desc = "(" + lambda.paramTys.joinToString("") { descOf(it, moduleOf, externInterfaceBinaryNames) } + ")" + descOf(lambda.retTy, moduleOf, externInterfaceBinaryNames)
        val mv = cw.visitMethod(ACC_PUBLIC, lambda.targetMethodName!!, desc, null, null)
        mv.visitCode()
        val fnGen = newFnCodeGen(mv)
        fnGen.pushScope()

        fnGen.declareParam("\$self", Ty.Unit_)
        for (i in lambda.params.indices) fnGen.declareParam(lambda.params[i], lambda.paramTys[i])
        for (c in lambda.captures) fnGen.declareCaptureField(c.name, c.ty, qname)
        fnGen.genExprAndReturn(lambda.body)
        fnGen.popScope()
        mv.visitMaxs(0, 0)
        mv.visitEnd()

        cw.visitEnd()
        return cw.toByteArray()
    }

    fun generate(): Map<String, ByteArray> {
        val out = mutableMapOf<String, ByteArray>()
        genLambdaClasses(out)
        for (s in program.structs) {
            val unit = Unit(s.moduleName, s.sourceUnit)

            val isEntryHolder = unit == entryUnit && unitFirstStructName[unit] == s.name
            val isHolder = unitFirstStructName[unit] == s.name || isEntryHolder

            val structAnnotations = if (s.name == entryClassName && entryModid != null) {
                s.annotations + AnnotationUse("net/minecraftforge/fml/common/Mod", listOf("value" to AnnotationValue.Str(entryModid)), 0)
            } else {
                s.annotations
            }
            out[qualify(s.name)] = genStruct(
                structs.getValue(s.name),
                if (isHolder) program.fns.filter { fnUnitOf[it.name] == unit } else emptyList(),
                if (isHolder) statics.values.filter { Unit(it.moduleName, it.sourceUnit) == unit } else emptyList(),
                structAnnotations,
                isEntryHolder,
                s.sourceUnit,
            )
        }
        for (i in interfaceDecls.filter { it.externBinaryName == null }) out[qualify(i.name)] = genInterface(i)
        for (e in enums.values) out[qualify(e.name)] = genEnum(e)

        val allUnits = program.fns.map { Unit(it.moduleName, it.sourceUnit) }.toSet() +
            statics.values.map { Unit(it.moduleName, it.sourceUnit) }.toSet() + entryUnit
        for (unit in allUnits) {
            if (unit != entryUnit && unitFirstStructName[unit] != null) continue
            if (unit == entryUnit && entryUnitFirstStructIsHolder) continue
            out[holderClassName(unit)] = genHolderClass(
                unit,
                program.fns.filter { Unit(it.moduleName, it.sourceUnit) == unit },
                statics.values.filter { Unit(it.moduleName, it.sourceUnit) == unit },
            )
        }
        return out
    }

    private fun genEnum(info: EnumInfo): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        cw.visit(V17, classAccess(info.name) or ACC_FINAL, qualify(info.name), null, "java/lang/Object", null)
        cw.visitSource(sourceFileName(null), null)
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

            mv.visitTypeInsn(NEW, "java/lang/IllegalStateException")
            mv.visitInsn(DUP)
            mv.visitLdcInsn("missing return")
            mv.visitMethodInsn(INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "(Ljava/lang/String;)V", false)
            mv.visitInsn(ATHROW)
        }
    }

    private fun genStruct(
        info: StructInfo, fnsForModule: List<FnDecl> = emptyList(), staticsForModule: List<StaticInfo> = emptyList(),
        annotations: List<AnnotationUse> = emptyList(), isEntry: Boolean = false, sourceUnit: String? = null,
    ): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        val ifaceNames = structInterfaces[info.name]?.map { jvmIfaceName(it, moduleOf, externInterfaceBinaryNames) }?.toTypedArray()

        val superAlias = structSuperclass[info.name]
        val superExt = superAlias?.let { externClasses.getValue(it) }
        val superName = superExt?.binaryName ?: "java/lang/Object"
        cw.visit(V17, classAccess(info.name) or ACC_FINAL, qualify(info.name), null, superName, if (ifaceNames.isNullOrEmpty()) null else ifaceNames)
        cw.visitSource(sourceFileName(sourceUnit), null)
        emitAnnotations(annotations) { desc, visible -> cw.visitAnnotation(desc, visible) }

        for ((fname, fty) in info.fields) {
            cw.visitField(ACC_PUBLIC, fname, descOf(fty, moduleOf, externInterfaceBinaryNames), null, null).visitEnd()
        }

        if (superExt != null) {

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

            if (info.name == entryClassName && entryInitFn != null) {
                mv.visitMethodInsn(INVOKESTATIC, qualify(info.name), entryInitFn, "()V", false)
            }
            mv.visitInsn(RETURN)
            mv.visitMaxs(0, 0)
            mv.visitEnd()
        }

        for (m in interfaceImplFns[info.name].orEmpty()) {
            val ifaceName = structInterfaces[info.name]?.firstOrNull { interfaceSigs[it]?.methods?.containsKey(m.name) == true }
            val msig = ifaceName?.let { interfaceSigs.getValue(it).methods[m.name] } ?: continue
            genInstanceMethod(cw, m, Ty.Struct(info.name), msig.paramTys, msig.ret)
        }

        if (superExt != null) {
            for (m in superclassOverrideFns[info.name].orEmpty()) {
                val em = superExt.methods.firstOrNull { it.name == m.name && !it.isCtor && !it.isStatic && it.params.size == m.params.size - 1 }
                    ?: continue
                genInstanceMethod(cw, m, Ty.Struct(info.name), em.params, em.retType)
            }
        }

        for (s in staticsForModule) {
            cw.visitField((if (s.visible) ACC_PUBLIC else 0) or ACC_STATIC, s.name, descOf(s.ty, moduleOf, externInterfaceBinaryNames), null, null).visitEnd()
        }
        if (isEntry || staticsForModule.isNotEmpty()) {
            val clinit = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null)
            clinit.visitCode()
            if (isEntry) {

                clinit.visitTypeInsn(NEW, "java/io/BufferedReader")
                clinit.visitInsn(DUP)
                clinit.visitTypeInsn(NEW, "java/io/InputStreamReader")
                clinit.visitInsn(DUP)
                clinit.visitFieldInsn(GETSTATIC, "java/lang/System", "in", "Ljava/io/InputStream;")
                clinit.visitMethodInsn(INVOKESPECIAL, "java/io/InputStreamReader", "<init>", "(Ljava/io/InputStream;)V", false)
                clinit.visitMethodInsn(INVOKESPECIAL, "java/io/BufferedReader", "<init>", "(Ljava/io/Reader;)V", false)
                clinit.visitFieldInsn(PUTSTATIC, qualify(info.name), STDIN_FIELD, "Ljava/io/BufferedReader;")
            }
            val fnGen = newFnCodeGen(clinit)
            for (s in staticsForModule) {
                fnGen.pushScope()
                fnGen.genStandaloneExpr(s.init)
                fnGen.popScope()
                clinit.visitFieldInsn(PUTSTATIC, qualify(info.name), s.name, descOf(s.ty, moduleOf, externInterfaceBinaryNames))
            }
            clinit.visitInsn(RETURN)
            clinit.visitMaxs(0, 0)
            clinit.visitEnd()
        }
        if (isEntry) {
            cw.visitField(ACC_PRIVATE or ACC_STATIC, STDIN_FIELD, "Ljava/io/BufferedReader;", null, null).visitEnd()
        }

        for (f in fnsForModule) genFn(cw, f)

        if (isEntry) {
            
            val mv = cw.visitMethod(ACC_PUBLIC or ACC_STATIC, "main", "([Ljava/lang/String;)V", null, null)
            mv.visitCode()
            val userMain = fnsForModule.find { it.name == "main" }
            if (userMain != null) {
                if (userMain.params.isNotEmpty()) {
                    mv.visitVarInsn(ALOAD, 0)
                    mv.visitMethodInsn(INVOKESTATIC, qualify(info.name), "main_", "([Ljava/lang/String;)V", false)
                } else {
                    mv.visitMethodInsn(INVOKESTATIC, qualify(info.name), "main_", "()V", false)
                }
            }
            mv.visitInsn(RETURN)
            mv.visitMaxs(0, 0)
            mv.visitEnd()
        }

        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun genInterface(decl: InterfaceDecl): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)

        val extends = decl.extends.map { qualify(it) }.toTypedArray()
        cw.visit(V17, classAccess(decl.name) or ACC_INTERFACE or ACC_ABSTRACT, qualify(decl.name), null, "java/lang/Object", if (extends.isEmpty()) null else extends)
        cw.visitSource(sourceFileName(null), null)
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

    private fun genHolderClass(unit: Unit, fnsForModule: List<FnDecl>, staticsForModule: List<StaticInfo>): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        val qname = holderClassName(unit)
        cw.visit(V17, ACC_PUBLIC or ACC_FINAL, qname, null, "java/lang/Object", null)
        cw.visitSource(sourceFileName(unit.sourceUnit), null)
        val isEntry = unit == entryUnit

        for (s in staticsForModule) {
            cw.visitField((if (s.visible) ACC_PUBLIC else 0) or ACC_STATIC, s.name, descOf(s.ty, moduleOf, externInterfaceBinaryNames), null, null).visitEnd()
        }

        if (isEntry || staticsForModule.isNotEmpty()) {
            val clinit = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null)
            clinit.visitCode()
            if (isEntry) {

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

            cw.visitField(ACC_PRIVATE or ACC_STATIC, STDIN_FIELD, "Ljava/io/BufferedReader;", null, null).visitEnd()
        }

        for (f in fnsForModule) genFn(cw, f)

        if (isEntry) {
            
            val mv = cw.visitMethod(ACC_PUBLIC or ACC_STATIC, "main", "([Ljava/lang/String;)V", null, null)
            mv.visitCode()
            val userMain = fnsForModule.find { it.name == "main" }
            if (userMain != null) {
                if (userMain.params.isNotEmpty()) {
                    mv.visitVarInsn(ALOAD, 0)
                    mv.visitMethodInsn(INVOKESTATIC, qname, "main_", "([Ljava/lang/String;)V", false)
                } else {
                    mv.visitMethodInsn(INVOKESTATIC, qname, "main_", "()V", false)
                }
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

        val paramTys = fnParamTypes.getValue(f.name)
        val desc = "(" + paramTys.joinToString("") { descOf(it, moduleOf, externInterfaceBinaryNames) } + ")" + descOf(retTy, moduleOf, externInterfaceBinaryNames)
        
        val jvmName = if (f.name == "main") "main_" else f.name

        val access = (if (f.visible) ACC_PUBLIC else 0) or ACC_STATIC
        val mv = cw.visitMethod(access, jvmName, desc, null, null)
        emitAnnotations(f.annotations) { d, visible -> mv.visitAnnotation(d, visible) }
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

    // `visitSource` gives a compiled class's stack traces (and a JVM debugger, once it's attached
    // via a source-position mapping on the IDE side) a real `.hotc` file name instead of none at
    // all. `sourceUnit` (stamped per-declaration only in the multi-file/directory compile path --
    // see `Main.kt`'s own `stampSourceUnit`) is the best available answer when present; a
    // single-file compile never sets it, so `mainClassName` (already derived from that one file's
    // own name) is the correct fallback, not a guess.
    private fun sourceFileName(sourceUnit: String?): String = "${sourceUnit ?: mainClassName}.hotc"
}

private class FnCodeGen(
    val mv: MethodVisitor,
    val structs: Map<String, StructInfo>,
    val fnRetTypes: Map<String, Ty>,
    val fnParamTypes: Map<String, List<Ty>>,

    val fnOwnerClass: Map<String, String>,
    val entryHolderClassName: String,
    val arenaLayouts: Map<String, ArenaLayout>,
    val usage: CodeGenUsage,
    val interfaceSigs: Map<String, InterfaceInfo>,
    val structInterfaces: Map<String, Set<String>>,
    val enums: Map<String, EnumInfo>,
    val moduleOf: Map<String, String?>,
    val externInterfaceBinaryNames: Map<String, String>,

    val staticTypes: Map<String, Ty>,
    val staticOwnerClass: Map<String, String>,
    val structSuperclass: Map<String, String>,
    val externClasses: Map<String, ExternClassInfo>,
) {
    private fun qualify(name: String) = qualify(name, moduleOf)
    private fun staticDescOf(name: String) = descOf(staticTypes.getValue(name), moduleOf, externInterfaceBinaryNames)
    private val scopes = ArrayDeque<MutableMap<String, Pair<Int, Ty>>>()
    private var nextSlot = 0

    private val loopLabels = ArrayDeque<Pair<Label, Label>>()

    fun pushScope() = scopes.addLast(mutableMapOf())
    fun popScope() { scopes.removeLast() }

    fun genStandaloneExpr(e: Expr) = genExpr(e)

    fun declareCaptureField(name: String, ty: Ty, ownerClass: String) {
        mv.visitVarInsn(ALOAD, 0)
        mv.visitFieldInsn(GETFIELD, ownerClass, "cap\$$name", descOf(ty, moduleOf, externInterfaceBinaryNames))
        val slot = declareLocal(name, ty)
        mv.visitVarInsn(storeOpcode(ty), slot)
    }

    fun genExprAndReturn(e: Expr) {
        genExpr(e)
        mv.visitInsn(returnOpcode(e.ty!!))
    }

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

    // Debug-info emission (`LineNumberTable`), the piece a JVM debugger needs to map a bytecode
    // offset back to a real `.hotc` source line -- without it, breakpoints/stepping have nothing
    // to attach to at all, regardless of how correct the rest of codegen is. Marked once per
    // DISTINCT line right before that statement's own bytecode (skipping a repeat of the same
    // line, since ASM's `LineNumberTable` is keyed by bytecode offset, not by statement -- two
    // statements sharing a line, e.g. after formatting collapses them, would otherwise emit two
    // redundant entries for the identical line).
    private var lastEmittedLine = -1

    private fun markLine(line: Int?) {
        if (line == null || line == lastEmittedLine) return
        lastEmittedLine = line
        val label = Label()
        mv.visitLabel(label)
        mv.visitLineNumber(line, label)
    }

    // Most `Stmt` variants carry their own `line` directly; the handful that don't (`ExprStmt`,
    // `If`, `While`, `Nested`) either wrap an `Expr` that has one (delegated to `lineOf(Expr)`
    // below) or, for `Nested`, have no single meaningful line of their own at all (its own
    // sub-statements each mark their own line individually once `genBlock` reaches them).
    private fun lineOf(stmt: Stmt): Int? = when (stmt) {
        is Stmt.Let -> stmt.line
        is Stmt.ExprStmt -> lineOf(stmt.expr)
        is Stmt.If -> lineOf(stmt.cond)
        is Stmt.DevIf -> stmt.line
        is Stmt.While -> lineOf(stmt.cond)
        is Stmt.For -> stmt.line
        is Stmt.Return -> stmt.line
        is Stmt.Break -> stmt.line
        is Stmt.Continue -> stmt.line
        is Stmt.Nested -> null
        is Stmt.Match -> stmt.line
        is Stmt.Try -> stmt.line
        is Stmt.Throw -> stmt.line
    }

    // Every `Expr` variant carries its own `line` EXCEPT the bare literals (no meaningful
    // sub-position to report) and `Borrow` (no `line` field of its own -- delegates to whatever
    // it wraps, which does).
    private fun lineOf(expr: Expr): Int? = when (expr) {
        is Expr.IntLit, is Expr.LongLit, is Expr.FloatLit, is Expr.DoubleLit, is Expr.StringLit,
        is Expr.BoolLit, is Expr.NullLit,
        -> null
        is Expr.Borrow -> lineOf(expr.inner)
        is Expr.StringInterp -> expr.line
        is Expr.Ident -> expr.line
        is Expr.Binary -> expr.line
        is Expr.Unary -> expr.line
        is Expr.Cast -> expr.line
        is Expr.InstanceOf -> expr.line
        is Expr.Assign -> expr.line
        is Expr.Call -> expr.line
        is Expr.FieldAccess -> expr.line
        is Expr.FieldAssign -> expr.line
        is Expr.StructLit -> expr.line
        is Expr.MethodCall -> expr.line
        is Expr.StaticFieldGet -> expr.line
        is Expr.StaticCall -> expr.line
        is Expr.ArrayLit -> expr.line
        is Expr.ArrayRepeat -> expr.line
        is Expr.Index -> expr.line
        is Expr.IndexAssign -> expr.line
        is Expr.ArenaNew -> expr.line
        is Expr.Range -> expr.line
        is Expr.If -> expr.line
        is Expr.Match -> expr.line
        is Expr.Lambda -> expr.line
        is Expr.ClassLit -> expr.line
    }

    private fun genStmt(stmt: Stmt) {
        markLine(lineOf(stmt))
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
                loopLabels.addLast(start to end)
                genBlock(stmt.body)
                loopLabels.removeLast()
                mv.visitJumpInsn(GOTO, start)
                mv.visitLabel(end)
            }
            is Stmt.For -> genFor(stmt)
            is Stmt.Match -> genMatch(stmt.scrutinee, stmt.arms) { genBlock(it) }
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

            is Stmt.Break -> {
                for (name in stmt.varsToDropBeforeBreak) genDropCall(name)
                mv.visitJumpInsn(GOTO, loopLabels.last().second)
            }
            is Stmt.Continue -> {
                for (name in stmt.varsToDropBeforeContinue) genDropCall(name)
                mv.visitJumpInsn(GOTO, loopLabels.last().first)
            }
            is Stmt.DevIf -> throw IllegalStateException("Stmt.DevIf reached genStmt -- Main.kt's stripDevCode should have already resolved every 'if dev { }' before codegen ever ran")
        }
    }

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

            is Expr.NullLit -> mv.visitInsn(ACONST_NULL)
            is Expr.StringLit -> mv.visitLdcInsn(expr.value)
            is Expr.Ident -> {
                val enumName = expr.resolvedName
                if (enumName != null) {
                    genEnumConstruct(enumName, expr.name, emptyMap()) 
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
            is Expr.InstanceOf -> {
                genExpr(expr.inner)
                mv.visitTypeInsn(INSTANCEOF, checkcastOperand(expr.resolvedTargetTy!!))
            }
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
                    } else if (expr.obj.ty is Ty.JavaExtern) {

                        val getter = expr.externGetterMethod
                        if (getter != null) {

                            mv.visitMethodInsn(INVOKEVIRTUAL, expr.resolvedName!!, getter, "()" + descOf(expr.ty!!, moduleOf, externInterfaceBinaryNames), false)
                        } else {
                            mv.visitFieldInsn(GETFIELD, expr.resolvedName!!, expr.field, descOf(expr.ty!!, moduleOf, externInterfaceBinaryNames))
                        }
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

            is Expr.StaticFieldGet -> mv.visitFieldInsn(GETSTATIC, expr.resolvedName!!, expr.field, descOf(expr.ty!!, moduleOf, externInterfaceBinaryNames))
            is Expr.FieldAssign -> {
                val idxObj = expr.obj as? Expr.Index
                if (idxObj != null && idxObj.ty is Ty.Arena) {
                    genArenaFieldSet(idxObj, expr.field, expr.value)
                } else if (expr.obj.ty is Ty.JavaExtern) {
                    genExpr(expr.obj)
                    genExpr(expr.value)
                    val valueDesc = descOf(expr.value.ty!!, moduleOf, externInterfaceBinaryNames)
                    val setter = expr.externSetterMethod
                    if (setter != null) {

                        val retTy = expr.externSetterRetTy!!
                        mv.visitMethodInsn(INVOKEVIRTUAL, expr.resolvedName!!, setter, "($valueDesc)" + descOf(retTy, moduleOf, externInterfaceBinaryNames), false)
                        if (retTy != Ty.Unit_) mv.visitInsn(if (isWide(retTy)) POP2 else POP)
                    } else {
                        mv.visitFieldInsn(PUTFIELD, expr.resolvedName!!, expr.field, valueDesc)
                    }
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
            is Expr.If -> {

                genExpr(expr.cond)
                val elseLabel = Label()
                val endLabel = Label()
                mv.visitJumpInsn(IFEQ, elseLabel)
                genExpr((expr.thenB.stmts[0] as Stmt.ExprStmt).expr)
                mv.visitJumpInsn(GOTO, endLabel)
                mv.visitLabel(elseLabel)
                genExpr((expr.elseB.stmts[0] as Stmt.ExprStmt).expr)
                mv.visitLabel(endLabel)
            }
            is Expr.Match -> genMatch(expr.scrutinee, expr.arms, producesValue = true) { b -> genExpr((b.stmts[0] as Stmt.ExprStmt).expr) }
            is Expr.Lambda -> {

                val qname = expr.syntheticName!!
                mv.visitTypeInsn(NEW, qname)
                mv.visitInsn(DUP)
                for (c in expr.captures) {
                    val (slot, ty) = resolve(c.name)
                    mv.visitVarInsn(loadOpcode(ty), slot)
                }
                val ctorDesc = "(" + expr.captures.joinToString("") { descOf(it.ty, moduleOf, externInterfaceBinaryNames) } + ")V"
                mv.visitMethodInsn(INVOKESPECIAL, qname, "<init>", ctorDesc, false)
            }
            is Expr.ClassLit -> {
                val binaryName = if (expr.isExtern) expr.resolvedName!! else qualify(expr.resolvedName!!)
                mv.visitLdcInsn(AsmType.getObjectType(binaryName))
            }
        }
    }

    private fun genForRange(stmt: Stmt.For, range: Expr.Range) {
        genExpr(range.start)
        pushScope()
        val varSlot = declareLocal(stmt.varName, Ty.Int_)
        mv.visitVarInsn(ISTORE, varSlot)
        genExpr(range.end)
        val endSlot = allocTemp()
        mv.visitVarInsn(ISTORE, endSlot)

        val loopStart = Label()
        val loopContinue = Label()
        val loopEnd = Label()
        mv.visitLabel(loopStart)
        mv.visitVarInsn(ILOAD, varSlot)
        mv.visitVarInsn(ILOAD, endSlot)

        mv.visitJumpInsn(if (range.inclusive) IF_ICMPGT else IF_ICMPGE, loopEnd)
        loopLabels.addLast(loopContinue to loopEnd)
        genBlock(stmt.body)
        loopLabels.removeLast()

        mv.visitLabel(loopContinue)
        mv.visitIincInsn(varSlot, 1)
        mv.visitJumpInsn(GOTO, loopStart)
        mv.visitLabel(loopEnd)
        popScope()
    }

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
        val loopContinue = Label()
        val loopEnd = Label()
        mv.visitLabel(loopStart)
        mv.visitVarInsn(ILOAD, idxSlot)
        mv.visitVarInsn(ILOAD, lenSlot)
        mv.visitJumpInsn(IF_ICMPGE, loopEnd)
        mv.visitVarInsn(ALOAD, arrSlot)
        mv.visitVarInsn(ILOAD, idxSlot)
        mv.visitInsn(arrayLoadOpcode(elemTy))
        mv.visitVarInsn(storeOpcode(elemTy), varSlot)
        loopLabels.addLast(loopContinue to loopEnd)
        genBlock(stmt.body)
        loopLabels.removeLast()
        mv.visitLabel(loopContinue)
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
            is Ty.Str_ -> mv.visitTypeInsn(ANEWARRAY, "java/lang/String")
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

    private fun genArenaNew(expr: Expr.ArenaNew) {
        usage.usesArena = true
        val layout = arenaLayouts.getValue(expr.structName)
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/foreign/Arena", "ofAuto", "()Ljava/lang/foreign/Arena;", true)
        genExpr(expr.count)
        mv.visitLdcInsn(layout.elemSize)
        mv.visitInsn(IMUL)
        mv.visitInsn(I2L)
        mv.visitLdcInsn(4L) 
        mv.visitMethodInsn(INVOKEINTERFACE, "java/lang/foreign/Arena", "allocate", "(JJ)Ljava/lang/foreign/MemorySegment;", true)
    }

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
            
            if (expr.method == "new") {
                mv.visitTypeInsn(NEW, binaryName)
                mv.visitInsn(DUP)
                for (a in expr.args) genExpr(a)
                val desc = "(" + paramTys.joinToString("") { descOf(it, moduleOf, externInterfaceBinaryNames) } + ")V"
                mv.visitMethodInsn(INVOKESPECIAL, binaryName, "<init>", desc, false)
            } else {
                for (a in expr.args) genExpr(a)
                val desc = "(" + paramTys.joinToString("") { descOf(it, moduleOf, externInterfaceBinaryNames) } + ")" + descOf(expr.ty!!, moduleOf, externInterfaceBinaryNames)

                mv.visitMethodInsn(INVOKESTATIC, binaryName, expr.method, desc, expr.isExternInterface)
            }
        } else {
            
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

        if (expr.isEnumOrdinal) {
            val qEnumName = qualify((expr.recv.ty as Ty.Enum).name)
            mv.visitFieldInsn(GETFIELD, qEnumName, "tag", "I")
            return
        }
        val target = expr.resolvedName!!
        val dynOwner = expr.dynamicOwner
        val instOwner = expr.instanceOwner
        val externOwner = expr.externOwner
        if (externOwner != null) {
            val paramTys = expr.externParamTys!!

            val desc = "(" + paramTys.joinToString("") { descOf(it, moduleOf, externInterfaceBinaryNames) } + ")" + descOf(expr.ty!!, moduleOf, externInterfaceBinaryNames)

            val opcode = when {
                expr.isStaticExtern -> INVOKESTATIC
                expr.isExternInterface -> INVOKEINTERFACE
                else -> INVOKEVIRTUAL
            }
            mv.visitMethodInsn(opcode, externOwner, target, desc, expr.isExternInterface)
            return
        }
        when {
            dynOwner != null -> {

                val msig = interfaceSigs.getValue(dynOwner).methods.getValue(expr.method)
                val desc = "(" + msig.paramTys.joinToString("") { descOf(it, moduleOf, externInterfaceBinaryNames) } + ")" + descOf(msig.ret, moduleOf, externInterfaceBinaryNames)
                mv.visitMethodInsn(INVOKEINTERFACE, jvmIfaceName(dynOwner, moduleOf, externInterfaceBinaryNames), target, desc, true)
            }
            instOwner != null -> {

                val overrideKey = "$instOwner@$target"
                val desc = if (fnRetTypes.containsKey(overrideKey)) {
                    val retTy = fnRetTypes.getValue(overrideKey)
                    val paramTys = fnParamTypes.getValue(overrideKey).drop(1) 
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
                val paramTys = fnParamTypes.getValue(target) 
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

    private fun checkcastOperand(ty: Ty): String = when (ty) {
        is Ty.Str_ -> "java/lang/String"
        is Ty.Struct -> qualify(ty.name)
        is Ty.Enum -> qualify(ty.name)
        is Ty.Dyn -> jvmIfaceName(ty.interfaceName, moduleOf, externInterfaceBinaryNames)
        is Ty.JavaExtern -> ty.binaryName
        is Ty.Array -> descOf(ty, moduleOf, externInterfaceBinaryNames)
        else -> throw CodeGenError("codegen: '$ty' isn't a valid reference-cast target")
    }

    private fun genCast(expr: Expr.Cast) {
        genExpr(expr.inner)
        val from = expr.inner.ty!!
        val to = expr.ty!!
        if (from == to) return
        if (from.isObjectRef() && to.isObjectRef()) {
            mv.visitTypeInsn(CHECKCAST, checkcastOperand(to))
            return
        }
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

    private fun genBoolFromBranch(branchOp: Int) {
        val trueL = Label(); val endL = Label()
        mv.visitJumpInsn(branchOp, trueL)
        mv.visitInsn(ICONST_0)
        mv.visitJumpInsn(GOTO, endL)
        mv.visitLabel(trueL)
        mv.visitInsn(ICONST_1)
        mv.visitLabel(endL)
    }

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
        if (expr.op == "+" && lty == Ty.Str_()) {
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
            if (lty is Ty.Enum) {

                genExpr(expr.left)
                mv.visitFieldInsn(GETFIELD, qualify(lty.name), "tag", "I")
                genExpr(expr.right)
                mv.visitFieldInsn(GETFIELD, qualify(lty.name), "tag", "I")
                genBoolFromBranch(if (expr.op == "==") IF_ICMPEQ else IF_ICMPNE)
                return
            }
            if (lty is Ty.Str_) {

                val rty = expr.right.ty!!
                val bothNonNull = !lty.nullable && rty is Ty.Str_ && !rty.nullable
                genExpr(expr.left)
                genExpr(expr.right)
                if (bothNonNull) {
                    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false)
                } else {
                    mv.visitMethodInsn(INVOKESTATIC, "java/util/Objects", "equals", "(Ljava/lang/Object;Ljava/lang/Object;)Z", false)
                }
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

                mv.visitInsn(if (lty == Ty.Float_) FCMPL else DCMPL)
                genBoolFromBranch(if (expr.op == "==") IFEQ else IFNE)
                return
            }

            if (lty == Ty.Int_ || lty == Ty.Bool_) {
                genBoolFromBranch(if (expr.op == "==") IF_ICMPEQ else IF_ICMPNE)
            } else {
                genBoolFromBranch(if (expr.op == "==") IF_ACMPEQ else IF_ACMPNE)
            }
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

    private fun genMatch(scrutinee: Expr, arms: List<MatchArm>, producesValue: Boolean = false, emitArm: (Block) -> Unit) {
        if (scrutinee.ty is Ty.Dyn) { genSealedMatch(scrutinee, arms, producesValue, emitArm); return }
        if (scrutinee.ty !is Ty.Enum) { genLiteralMatch(scrutinee, arms, producesValue, emitArm); return }
        val enumName = (scrutinee.ty as Ty.Enum).name
        val qEnumName = qualify(enumName)
        val info = enums.getValue(enumName)
        genExpr(scrutinee)
        val scrutSlot = allocTemp()
        mv.visitVarInsn(ASTORE, scrutSlot)

        val endLabel = Label()
        var wildcardArm: MatchArm? = null
        for (arm in arms) {
            if (arm.variantName == null) { wildcardArm = arm; continue }

            val bareVariantName = arm.variantName.substringAfterLast("::")
            val variant = info.variant(bareVariantName) ?: continue
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
            emitArm(arm.body)
            popScope()
            mv.visitJumpInsn(GOTO, endLabel)
            mv.visitLabel(nextLabel)
        }
        if (wildcardArm != null) {
            emitArm(wildcardArm.body)
        } else if (producesValue) {
            genTrap("non-exhaustive match")
        }
        mv.visitLabel(endLabel)
    }

    private fun genLiteralMatch(scrutinee: Expr, arms: List<MatchArm>, producesValue: Boolean, emitArm: (Block) -> Unit) {
        val ty = scrutinee.ty!!
        genExpr(scrutinee)
        val scrutSlot = allocTemp()
        mv.visitVarInsn(storeOpcode(ty), scrutSlot)

        val endLabel = Label()
        var wildcardArm: MatchArm? = null
        for (arm in arms) {
            val lit = arm.literal
            if (lit == null) { wildcardArm = arm; continue }
            val nextLabel = Label()
            when (ty) {
                Ty.Int_, Ty.Bool_ -> {
                    mv.visitVarInsn(loadOpcode(ty), scrutSlot)
                    genExpr(lit)
                    mv.visitJumpInsn(IF_ICMPNE, nextLabel)
                }
                Ty.Long_ -> {
                    mv.visitVarInsn(loadOpcode(ty), scrutSlot)
                    genExpr(lit)
                    mv.visitInsn(LCMP)
                    mv.visitJumpInsn(IFNE, nextLabel)
                }
                else -> { 
                    genExpr(lit)
                    mv.visitVarInsn(loadOpcode(ty), scrutSlot)
                    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false)
                    mv.visitJumpInsn(IFEQ, nextLabel)
                }
            }
            emitArm(arm.body)
            mv.visitJumpInsn(GOTO, endLabel)
            mv.visitLabel(nextLabel)
        }
        if (wildcardArm != null) {
            emitArm(wildcardArm.body)
        } else if (producesValue) {
            genTrap("non-exhaustive match")
        }
        mv.visitLabel(endLabel)
    }

    private fun genTrap(message: String) {
        mv.visitTypeInsn(NEW, "java/lang/IllegalStateException")
        mv.visitInsn(DUP)
        mv.visitLdcInsn(message)
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "(Ljava/lang/String;)V", false)
        mv.visitInsn(ATHROW)
    }

    private fun genSealedMatch(scrutinee: Expr, arms: List<MatchArm>, producesValue: Boolean = false, emitArm: (Block) -> Unit) {
        genExpr(scrutinee)
        val scrutSlot = allocTemp()
        mv.visitVarInsn(ASTORE, scrutSlot)

        val endLabel = Label()
        var wildcardArm: MatchArm? = null
        for (arm in arms) {
            if (arm.variantName == null) { wildcardArm = arm; continue }
            
            val structName = arm.variantName.substringAfterLast("::")
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
            emitArm(arm.body)
            popScope()
            mv.visitJumpInsn(GOTO, endLabel)
            mv.visitLabel(nextLabel)
        }
        if (wildcardArm != null) {
            emitArm(wildcardArm.body)
        } else if (producesValue) {
            genTrap("non-exhaustive match")
        }
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
