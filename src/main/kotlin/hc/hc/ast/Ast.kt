package hc.ast

import hc.sema.Ty

data class TypeRef(val name: String, val isRef: Boolean, val typeArgs: List<TypeRef> = emptyList(), val isMut: Boolean = false, val isDyn: Boolean = false, val isNullable: Boolean = false)

data class Program(
    val structs: List<StructDecl>,
    val fns: List<FnDecl>,
    val impls: List<ImplBlock> = emptyList(),
    val interfaces: List<InterfaceDecl> = emptyList(),
    val enums: List<EnumDecl> = emptyList(),
    val externs: List<ExternClassDecl> = emptyList(),
    val extends: List<ExtendBlock> = emptyList(),
    val statics: List<StaticDecl> = emptyList(),
)

data class StaticDecl(val name: String, val type: TypeRef, val init: Expr, val line: Int, val moduleName: String? = null, val visible: Boolean = false, val sourceUnit: String? = null)

data class ExtendBlock(val targetName: String, val methods: List<FnDecl>, val moduleName: String?, val line: Int)

data class ExternClassDecl(
    val name: String,
    val binaryName: String,
    val methods: List<ExternMethodDecl>,
    val useNames: List<String>? = null,
    val lazyAll: Boolean = false,
    val fields: List<ExternFieldDecl> = emptyList(),
    val isInterface: Boolean = false,
)

data class ExternFieldDecl(val name: String, val type: TypeRef, val line: Int, val isStatic: Boolean = true)

data class ExternMethodDecl(val name: String, val params: List<Param>, val retType: TypeRef?, val line: Int, val isStatic: Boolean = false)

data class ImplBlock(
    val structName: String,
    val typeParams: List<String>,
    val methods: List<FnDecl>,
    val interfaceName: String? = null,
    val delegateField: String? = null,
    val typeParamBounds: Map<String, List<String>> = emptyMap(),
)

data class InterfaceDecl(
    val name: String,
    val methods: List<InterfaceMethodDecl>,
    val extends: List<String> = emptyList(),
    val sealed: Boolean = false,
    val externBinaryName: String? = null,
    val moduleName: String? = null,
    val visible: Boolean = false,
    val open: Boolean = false,
)

data class InterfaceMethodDecl(val name: String, val params: List<Param>, val retType: TypeRef?, val body: Block?, val line: Int)

data class StructDecl(
    val name: String,
    val fields: List<FieldDecl>,
    val typeParams: List<String> = emptyList(),
    val isArena: Boolean = false,
    val typeParamBounds: Map<String, List<String>> = emptyMap(),
    val moduleName: String? = null,
    val visible: Boolean = false,
    val superclass: String? = null,
    val annotations: List<AnnotationUse> = emptyList(),
    val sourceUnit: String? = null,
    val serializable: Boolean = false,
)

data class AnnotationUse(val binaryName: String, val args: List<Pair<String, AnnotationValue>>, val line: Int)

data class EntryDirective(val target: String, val args: List<Pair<String, AnnotationValue>>, val line: Int)
sealed class AnnotationValue {
    data class Str(val value: String) : AnnotationValue()

    data class EnumConst(val enumBinaryName: String, val constName: String) : AnnotationValue()

    data class Arr(val values: List<AnnotationValue>) : AnnotationValue()
}
data class FieldDecl(val name: String, val type: TypeRef)

data class FnDecl(
    val name: String,
    val params: List<Param>,
    val retType: TypeRef?,
    val body: Block,
    val line: Int,
    val typeParams: List<String> = emptyList(),
    val typeParamBounds: Map<String, List<String>> = emptyMap(),
    val moduleName: String? = null,
    val visible: Boolean = false,
    val isOverride: Boolean = false,
    val annotations: List<AnnotationUse> = emptyList(),

    val sourceUnit: String? = null,
    val entry: EntryDirective? = null,
)
data class Param(val name: String, val type: TypeRef)

data class EnumDecl(
    val name: String,
    val variants: List<EnumVariant>,
    val typeParams: List<String> = emptyList(),
    val typeParamBounds: Map<String, List<String>> = emptyMap(),
    val moduleName: String? = null,
    val visible: Boolean = false,
)
data class EnumVariant(val name: String, val fields: List<FieldDecl>)

data class Block(val stmts: List<Stmt>) {

    var dropsAtEnd: List<String> = emptyList()
}

sealed class Stmt {
    data class Let(val name: String, val mutable: Boolean, val declType: TypeRef?, val init: Expr, val line: Int) : Stmt()
    data class ExprStmt(val expr: Expr) : Stmt()
    data class If(val cond: Expr, val thenB: Block, val elseB: Block?) : Stmt()
    data class While(val cond: Expr, val body: Block) : Stmt()

    data class For(val varName: String, val iterable: Expr, val body: Block, val line: Int) : Stmt()
    data class Return(val expr: Expr?, val line: Int) : Stmt() {

        var varsToDropBeforeReturn: List<String> = emptyList()
    }
    data class Nested(val block: Block) : Stmt()

    data class Match(val scrutinee: Expr, val arms: List<MatchArm>, val line: Int) : Stmt()

    data class Try(val tryBlock: Block, val catches: List<CatchClause>, val line: Int) : Stmt()

    data class Throw(val expr: Expr, val line: Int) : Stmt()
}
data class MatchArm(val variantName: String?, val bindings: List<String>, val body: Block, val line: Int)

data class CatchClause(val varName: String, val exceptionType: TypeRef, val body: Block, val line: Int) {
    var resolvedTy: Ty? = null
}

sealed class Expr {
    var ty: Ty? = null 

    var resolvedName: String? = null

    data class IntLit(val value: Int) : Expr()
    data class LongLit(val value: Long) : Expr()
    data class FloatLit(val value: Float) : Expr()
    data class DoubleLit(val value: Double) : Expr()
    data class StringLit(val value: String) : Expr()

    data class StringInterp(val literals: List<String>, val exprs: List<Expr>, val line: Int) : Expr()
    data class BoolLit(val value: Boolean) : Expr()

    class NullLit : Expr()
    data class Ident(val name: String, val line: Int) : Expr()
    data class Binary(val op: String, val left: Expr, val right: Expr, val line: Int) : Expr()
    data class Unary(val op: String, val expr: Expr, val line: Int) : Expr()

    data class Cast(val inner: Expr, val target: TypeRef, val line: Int) : Expr()

    data class InstanceOf(val inner: Expr, val target: TypeRef, val line: Int) : Expr() {
        var resolvedTargetTy: Ty? = null
    }
    data class Borrow(val inner: Expr, val isMut: Boolean = false) : Expr()
    data class Assign(val name: String, val value: Expr, val line: Int) : Expr()
    data class Call(val callee: String, val args: List<Expr>, val line: Int) : Expr()
    data class FieldAccess(val obj: Expr, val field: String, val line: Int) : Expr() {

        var externGetterMethod: String? = null
    }
    data class FieldAssign(val obj: Expr, val field: String, val value: Expr, val line: Int) : Expr()

    data class StructLit(val typeName: String, val fields: List<Pair<String, Expr>>, val line: Int) : Expr() {
        var enumVariant: String? = null
    }
    data class MethodCall(val recv: Expr, val method: String, val args: List<Expr>, val line: Int) : Expr() {

        var dynamicOwner: String? = null
        var instanceOwner: String? = null
        var externOwner: String? = null

        var externParamTys: List<Ty>? = null
        var isStaticExtern: Boolean = false

        var isExternInterface: Boolean = false
    }

    data class StaticFieldGet(val typeName: String, val field: String, val line: Int) : Expr()
    data class StaticCall(val typeName: String, val method: String, val args: List<Expr>, val line: Int) : Expr() {
        var externParamTys: List<Ty>? = null
        
        var isExternInterface: Boolean = false
    }
    data class ArrayLit(val elements: List<Expr>, val line: Int) : Expr()
    data class ArrayRepeat(val value: Expr, val count: Expr, val line: Int) : Expr()
    data class Index(val arr: Expr, val index: Expr, val line: Int) : Expr()
    data class IndexAssign(val arr: Expr, val index: Expr, val value: Expr, val line: Int) : Expr()
    
    data class ArenaNew(val structName: String, val count: Expr, val line: Int) : Expr()
    
    data class Range(val start: Expr, val end: Expr, val line: Int) : Expr()

    data class If(val cond: Expr, val thenB: Block, val elseB: Block, val line: Int) : Expr()

    data class Match(val scrutinee: Expr, val arms: List<MatchArm>, val line: Int) : Expr()

    data class Lambda(val params: List<String>, val body: Expr, val line: Int) : Expr() {

        var targetInterfaceName: String? = null 
        var targetBinaryName: String? = null 
        var targetMethodName: String? = null 
        var paramTys: List<Ty> = emptyList() 
        var retTy: Ty = hc.sema.Ty.Unit_ 

        var captures: List<LambdaCapture> = emptyList()

        var syntheticName: String? = null
    }

    data class ClassLit(val typeName: String, val line: Int) : Expr() {

        var isExtern: Boolean = false
    }
}
data class LambdaCapture(val name: String, val ty: Ty)
