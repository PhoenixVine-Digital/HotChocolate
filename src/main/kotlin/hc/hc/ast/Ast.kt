package hc.ast

import hc.sema.Ty

// `isNullable`: trailing `?` (`Player?`) -- only meaningful on an `extern class` type (see
// Checker.resolveType); marks that a JVM call declared with this return type can genuinely
// hand back `null`, which HC otherwise has no concept of at all. A nullable value can only be
// compared against the `null` literal (`Expr.NullLit`) until narrowed -- see the README's
// "Nullable extern references" section for the exact guard-clause idiom this recognizes.
data class TypeRef(val name: String, val isRef: Boolean, val typeArgs: List<TypeRef> = emptyList(), val isMut: Boolean = false, val isDyn: Boolean = false, val isNullable: Boolean = false)

// Each file may open with `module a.b.c;` (dotted, JVM-style) -- unlike a bare `package`, this
// is a real ownership/visibility boundary, not just a naming prefix: every top-level struct/
// fn/interface/enum carries the module it was declared in (`moduleName`, below), and is
// `pub`-or-not relative to *that* module, not the whole program. Different files in the same
// compile can freely declare different modules -- there's no whole-program "the package" the
// way `package` used to require. A declaration with no `module` line lands in the null/default
// module, same as today's behavior for a program that never declares one.
//
// Every type-shaped declaration (struct/interface/enum) gets its own JVM class, so its module
// maps directly onto a real JVM package, and its `pub`/private-ness maps directly onto
// `ACC_PUBLIC` vs package-private -- cross-module access the checker missed still gets caught
// for free by the JVM's own linker. Top-level fns (including desugared impl/extend methods)
// are grouped onto one holder class *per module* too (see CodeGen's `fnOwnerClass`), each
// fn's own `pub` mapped onto that method's own JVM access flag -- real enforcement, same as
// types, just sharing a class per module rather than getting one each.
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

// `[pub] static NAME: Type = initExpr;` -- Hot Chocolate's first *global* mutable value: not a
// local (no enclosing scope), not a struct field (no owning instance). Exists for the whole
// program's lifetime, compiled as a real static field on its declaring module's fn-holder
// class, initialized once in that class's `<clinit>` from `initExpr` (checked in an empty
// scope -- no locals, and deliberately no access to *other* statics either, sidestepping
// cross-static init-order entirely for this first pass). Reading/writing it works exactly like
// a `var` local everywhere else in the checker (same Ident/Assign/method-call machinery,
// injected into every fn's scope) with one difference: a read is *never* treated as a move,
// since there's no scope for the value to be "used up" by -- the next reader anywhere in the
// program still needs to see it.
data class StaticDecl(val name: String, val type: TypeRef, val init: Expr, val line: Int, val moduleName: String? = null, val visible: Boolean = false)

// `extend Target { fn newMethod(&self, ...) -> T { body } }` -- adds a new, callable method to
// an existing type *without* touching its own declaration, from any module. `target` is either
// an `open interface`'s name (the method becomes callable on any `&dyn Target` value) or a
// concrete struct's name (callable on that struct directly) -- never a generic struct/sealed
// interface member set, and never able to override something that already exists (a real
// inherent/interface method always wins over an extension of the same name; two modules
// extending the same target with the same name and both in scope is an ambiguity error). Every
// method needs a body -- there's nothing to "require implementers to override," since
// implementers' classes are already compiled. `moduleName`: which module is *providing* the
// extension (not `target`'s own module) -- this is what "extends the ecosystem without
// modifying the original source" actually means structurally.
data class ExtendBlock(val targetName: String, val methods: List<FnDecl>, val moduleName: String?, val line: Int)

// `extern class Alias = "java/util/ArrayList" { ... }` -- a declared shape for an existing,
// externally-compiled JVM class. Three forms, all producing the same `ExternClassDecl`/
// `ExternClassInfo` machinery downstream (Checker/CodeGen don't care which form a given class
// used -- only the parser and the registration loop in Checker.check() do):
//   1. Explicit signatures (`methods` non-empty, `useNames == null`, `lazyAll == false`) --
//      the original form. No classfile parser here to verify this against the real class, so
//      the compiler just trusts it and emits calls against `binaryName` directly -- a wrong
//      declaration surfaces as a JVM link error (NoSuchMethodError etc.) at runtime, not a
//      compile error, same as any hand-written FFI declaration file in any language.
//   2. `extern class X = "..." use { new, getX, getY };` (`useNames` non-null) -- every named
//      member's *real* signature is read via reflection against `--classpath`, up front, at
//      registration time. Still a closed, explicitly-opted-in interface (nothing you didn't
//      name is callable), just without retyping each signature by hand.
//   3. `extern class X = "...";` with no body at all (`lazyAll == true`) -- nothing is resolved
//      up front; each member's signature is read via reflection the first time some call site
//      actually asks for that name (Checker.resolveExternMethods), and cached from then on.
//      Least ceremony, but also the least checkable up front: a typo'd method name isn't an
//      error until/unless something calls it.
data class ExternClassDecl(
    val name: String,
    val binaryName: String,
    val methods: List<ExternMethodDecl>,
    val useNames: List<String>? = null,
    val lazyAll: Boolean = false,
)
// `self` present (params[0].name == "self") -> instance method (INVOKEVIRTUAL). No self and
// name == "new" -> constructor (NEW + INVOKESPECIAL <init>). No self otherwise -> static
// method (INVOKESTATIC). Types in `params`/`retType` must be the exact erased JVM signature --
// generic Java APIs erase to Object at the bytecode level, so wrapping e.g. `List<String>`
// needs the declaration to say `Object`, not `String`, or it link-fails at runtime.
data class ExternMethodDecl(val name: String, val params: List<Param>, val retType: TypeRef?, val line: Int, val isStatic: Boolean = false)

// `interfaceName == null` -> inherent `impl StructName { }`. Otherwise `impl InterfaceName for StructName { }`.
// `delegateField` (the `by field` clause): any interface method not explicitly overridden in
// `methods` gets a forwarding method synthesized that calls `self.field.method(...)`.
data class ImplBlock(
    val structName: String,
    val typeParams: List<String>,
    val methods: List<FnDecl>,
    val interfaceName: String? = null,
    val delegateField: String? = null,
    val typeParamBounds: Map<String, List<String>> = emptyMap(),
)

// `extends`: supertraits, e.g. `interface Sub: Super, Super2 { }`. A struct implementing
// `Sub` automatically satisfies `Super`/`Super2` too (their methods are inherited into Sub's
// method set, and Sub's JVM interface `extends` theirs).
// `sealed`: every implementer must be declared in this same compilation unit -- which, with
// multi-file support (see `compileEntry` in Main.kt), can now be a real multi-file project
// directory, not just one file. It's also what unlocks `match`-by-concrete-type over a
// `&dyn SealedInterface` value, with compile-time exhaustiveness, same as matching an enum's
// variants.
// `externBinaryName != null` -> this isn't a new interface to compile a class for -- it's a
// declared, trusted shape for an *existing* JVM interface (`extern interface Alias =
// "some.Interface" { ... }`), analogous to `extern class`. `impl Alias for Struct { }` still
// goes through the exact same machinery as any other interface impl (real instance methods on
// the struct's own class), but codegen has the struct's class actually `implements` the real
// binary name instead of a class this compiler generated -- so a Java-side caller (a mod
// loader's callback registration, say) can invoke it through the genuine interface type, no
// shim needed. Never `sealed` (arbitrary external Java code could implement it too, which
// would make the closed-set assumption behind `match`-by-concrete-type unsound) and never has
// `extends` or default method bodies -- the parser's `externInterfaceDecl` never produces
// either, rather than trusting every call site to leave them empty/null.
// `open`: allows other modules to add new methods to this interface via `extend Name { }`
// elsewhere -- orthogonal to `sealed` (that's about the closed set of *implementers*, for
// match-by-concrete-type; `open` is about the extensible set of *extension methods*, which
// never changes what implements the interface). Implies `visible` -- an inaccessible trait
// can't usefully be extended from another module anyway.
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
// `body == null` -> required (must be overridden by every implementer). `body != null` -> a
// default implementation, inherited automatically (via the JVM's own interface default-method
// mechanism) unless a struct's `impl Interface for Struct` block overrides it.
data class InterfaceMethodDecl(val name: String, val params: List<Param>, val retType: TypeRef?, val body: Block?, val line: Int)

// `superclass`: `struct S extends C { }` where `C` names a previously-declared `extern class` --
// unlike `impl Interface for Struct` (JVM `implements`), this is real JVM `extends`: S's
// generated class has `C`'s binary name as its `superName`, a constructor that forwards
// straight to `C`'s own declared `new(...)` (S must have zero fields of its own for this --
// there's nowhere else for extra state to live in a "just forward to super" constructor), and
// `impl S { override fn m(...) { ... } }` methods compile to real instance methods matching
// `C`'s declared method table exactly, so external Java code (a mod loader calling `item.use(...)`
// on what it thinks is a plain `Item`) reaches them via ordinary JVM virtual dispatch -- no shim
// class, no reflection. The actual mechanism that lets a whole Forge/Minecraft `Item`/`Block`/
// etc. subclass be written natively in HC instead of needing a thin Java shell.
data class StructDecl(
    val name: String,
    val fields: List<FieldDecl>,
    val typeParams: List<String> = emptyList(),
    val isArena: Boolean = false,
    val typeParamBounds: Map<String, List<String>> = emptyMap(),
    val moduleName: String? = null,
    val visible: Boolean = false,
    val superclass: String? = null,
)
data class FieldDecl(val name: String, val type: TypeRef)

// `moduleName`/`visible` are only meaningful for a *top-level* fn -- methods desugared from an
// `impl`/`extend` block (see Checker) inherit their owning struct/extend-block's module
// instead, and are never independently `pub`-marked (visibility follows the struct/trait).
// `isOverride`: only meaningful inside a plain `impl S { }` block where `S extends SomeExtern`
// -- marks this method as overriding one of `SomeExtern`'s declared methods (matched by name,
// validated against its exact signature), compiled as a real instance method rather than this
// language's usual static-desugared one. Explicit, not inferred from a name match alone, so a
// typo'd override name is a clear "no such method to override" error instead of silently
// becoming an ordinary unrelated static method.
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
)
data class Param(val name: String, val type: TypeRef)

// `enum Shape { Circle { radius: Int }, Square { side: Int }, Point }` -- `fields.isEmpty()`
// means a unit variant (no braces at the use site either: bare `Point`). `enum Option<T> { }`
// is monomorphized exactly like a generic struct.
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
    // Names of this block's own `let`/`var` locals (struct types with a `drop` method) that
    // are still unmoved when the block falls off its end -- set by the checker, in the order
    // they should be dropped (already reversed: most-recently-declared first).
    var dropsAtEnd: List<String> = emptyList()
}

sealed class Stmt {
    data class Let(val name: String, val mutable: Boolean, val declType: TypeRef?, val init: Expr, val line: Int) : Stmt()
    data class ExprStmt(val expr: Expr) : Stmt()
    data class If(val cond: Expr, val thenB: Block, val elseB: Block?) : Stmt()
    data class While(val cond: Expr, val body: Block) : Stmt()
    // `for x in a..b { }` or `for x in arr { }` -- `iterable` is either an Expr.Range or
    // anything else (checked to be an array). Covers both counting and collection loops.
    data class For(val varName: String, val iterable: Expr, val body: Block, val line: Int) : Stmt()
    data class Return(val expr: Expr?, val line: Int) : Stmt() {
        // Like Block.dropsAtEnd, but flattened across *every* enclosing scope this return
        // exits past (an early return skips their normal end-of-block drop point entirely).
        var varsToDropBeforeReturn: List<String> = emptyList()
    }
    data class Nested(val block: Block) : Stmt()
    // `match scrutinee { Variant { a, b } => { }, Other => { }, _ => { } }`. `variantName ==
    // null` is the wildcard arm (must be last if present). `bindings` names the variant's
    // fields, in declaration order, bound fresh in that arm's block -- no renaming/nesting.
    data class Match(val scrutinee: Expr, val arms: List<MatchArm>, val line: Int) : Stmt()
    // `try { } catch (e: SomeExternException) { } catch (e2: OtherExc) { }` -- real JVM
    // exception handling (ASM `visitTryCatchBlock`), not a language-invented mechanism. A catch
    // type must resolve to an `extern class` (a real, trusted JVM Throwable) -- there's no
    // HC-native exception type. No `finally` yet (see README's Error handling section).
    data class Try(val tryBlock: Block, val catches: List<CatchClause>, val line: Int) : Stmt()
    // `throw someExternException;` -- compiles directly to ATHROW. `expr` must check to an
    // extern class type; nothing here verifies it's actually a `Throwable` subtype beyond that
    // (same "trust the declaration" scope cut as every other `extern class` interaction).
    data class Throw(val expr: Expr, val line: Int) : Stmt()
}
data class MatchArm(val variantName: String?, val bindings: List<String>, val body: Block, val line: Int)
// `resolvedTy` is filled in by the checker (mirrors Expr.ty) -- codegen needs the caught
// exception's real `Ty.JavaExtern` to know the local slot's type and the handler's binary name.
data class CatchClause(val varName: String, val exceptionType: TypeRef, val body: Block, val line: Int) {
    var resolvedTy: Ty? = null
}

sealed class Expr {
    var ty: Ty? = null // filled in by the checker
    // For Call/StructLit: the mangled, fully-monomorphized target name (e.g. "identity_Int"),
    // set by the checker when the callee/typeName resolves to a generic template. Codegen
    // must prefer this over callee/typeName whenever it's non-null.
    var resolvedName: String? = null

    data class IntLit(val value: Int) : Expr()
    data class LongLit(val value: Long) : Expr()
    data class FloatLit(val value: Float) : Expr()
    data class DoubleLit(val value: Double) : Expr()
    data class StringLit(val value: String) : Expr()
    // `"a{x}b{y}c"` -- `literals.size == exprs.size + 1` always (literal segments alternate
    // with expressions, starting and ending with a literal, possibly empty, segment).
    data class StringInterp(val literals: List<String>, val exprs: List<Expr>, val line: Int) : Expr()
    data class BoolLit(val value: Boolean) : Expr()
    // `null` -- only ever legal as a direct operand of `==`/`!=` against an extern class value
    // (checker-enforced); never a real value of any type, so codegen never actually reaches
    // genExpr on a bare one in a valid program (genBinary intercepts it first). A plain class,
    // not an `object` singleton: `Expr`'s `ty`/`resolvedName` are mutable per-instance state,
    // so every `null` occurrence in the source needs its own object, not one shared across all
    // of them.
    class NullLit : Expr()
    data class Ident(val name: String, val line: Int) : Expr()
    data class Binary(val op: String, val left: Expr, val right: Expr, val line: Int) : Expr()
    data class Unary(val op: String, val expr: Expr, val line: Int) : Expr()
    // `expr as Type` -- numeric conversion only (Int/Float/Double), compiling directly to a
    // primitive JVM conversion instruction (I2F, D2I, ...), never a method call.
    data class Cast(val inner: Expr, val target: TypeRef, val line: Int) : Expr()
    data class Borrow(val inner: Expr, val isMut: Boolean = false) : Expr()
    data class Assign(val name: String, val value: Expr, val line: Int) : Expr()
    data class Call(val callee: String, val args: List<Expr>, val line: Int) : Expr()
    data class FieldAccess(val obj: Expr, val field: String, val line: Int) : Expr()
    data class FieldAssign(val obj: Expr, val field: String, val value: Expr, val line: Int) : Expr()
    // Doubles as an enum variant construction (`Circle { radius: 5 }`): `resolvedName` is
    // still the NEW target's class name either way (the struct's own, or the owning enum's),
    // but `enumVariant` is additionally set to the variant name so codegen knows to also set
    // the tag field and use `variant$field`-prefixed field names instead of plain ones.
    data class StructLit(val typeName: String, val fields: List<Pair<String, Expr>>, val line: Int) : Expr() {
        var enumVariant: String? = null
    }
    data class MethodCall(val recv: Expr, val method: String, val args: List<Expr>, val line: Int) : Expr() {
        // At most one of these is set by the checker when the call resolves to something other
        // than this language's usual static-desugared struct method (all null means that):
        //  - dynamicOwner: the interface name -- INVOKEINTERFACE (receiver's static type is `&dyn X`).
        //  - instanceOwner: the concrete struct's class name -- INVOKEVIRTUAL (receiver's static
        //    type is a concrete struct that happens to implement an interface).
        //  - externOwner: an `extern class`'s binary name -- INVOKEVIRTUAL against a real,
        //    externally-compiled JVM class.
        var dynamicOwner: String? = null
        var instanceOwner: String? = null
        var externOwner: String? = null
        // Set alongside externOwner: the extern method's own declared param types (not the
        // args' own checked types -- those can legitimately differ, e.g. a concrete struct
        // argument satisfying a `&dyn Trait` param, same reasoning as every other call site in
        // this compiler). Building the JVM descriptor from the wrong side here would link-fail
        // at runtime against the real external method with a mismatched signature.
        var externParamTys: List<Ty>? = null
        var isStaticExtern: Boolean = false
    }
    // `TypeName::method(args)` -- a constructor (`method == "new"`, NEW + INVOKESPECIAL) or a
    // static method (INVOKESTATIC) on an `extern class`. `resolvedName` (inherited) is set to
    // the extern class's binary name. `externParamTys` -- see MethodCall.externParamTys.
    data class StaticCall(val typeName: String, val method: String, val args: List<Expr>, val line: Int) : Expr() {
        var externParamTys: List<Ty>? = null
    }
    data class ArrayLit(val elements: List<Expr>, val line: Int) : Expr()
    data class ArrayRepeat(val value: Expr, val count: Expr, val line: Int) : Expr()
    data class Index(val arr: Expr, val index: Expr, val line: Int) : Expr()
    data class IndexAssign(val arr: Expr, val index: Expr, val value: Expr, val line: Int) : Expr()
    // `arena Particle[count]`: allocates an auto-managed off-heap buffer of `count` elements.
    data class ArenaNew(val structName: String, val count: Expr, val line: Int) : Expr()
    // Only valid directly as a `for x in a..b` iterable -- not a general-purpose expression.
    data class Range(val start: Expr, val end: Expr, val line: Int) : Expr()
}
