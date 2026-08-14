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
data class StaticDecl(val name: String, val type: TypeRef, val init: Expr, val line: Int, val moduleName: String? = null, val visible: Boolean = false, val sourceUnit: String? = null)

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
// `isInterface`: the real JVM type this alias trusts is an `interface`, not a `class` --
// `extern class Alias = "some.Interface" interface { ... }`. Matters purely for codegen's choice
// of invoke instruction/constant-pool-entry kind: an interface's *instance* methods need
// INVOKEINTERFACE (not INVOKEVIRTUAL), and even its *static* methods (legal since Java 8) still
// need an InterfaceMethodref constant, not a plain Methodref, despite still using the INVOKESTATIC
// opcode. Getting this wrong compiles clean and `javap`-verifies fine (ASM emits a structurally
// valid classfile either way) but throws `IncompatibleClassChangeError: ... must be
// InterfaceMethodref constant` the moment the JVM actually links the call -- verified the hard
// way against a real Forge mod calling `Component.literal(String)` (`Component` is a real
// interface with a static factory method). Distinct from `extern interface` (see InterfaceDecl's
// doc): that's for an HC struct to *implement* a real interface; this is for *calling* one's own
// methods (static or instance) the same way `extern class` already does for a real class.
data class ExternClassDecl(
    val name: String,
    val binaryName: String,
    val methods: List<ExternMethodDecl>,
    val useNames: List<String>? = null,
    val lazyAll: Boolean = false,
    val fields: List<ExternFieldDecl> = emptyList(),
    val isInterface: Boolean = false,
)
// `NAME: Type;` (instance field, read via `recv.NAME` -- ordinary Expr.FieldAccess, same syntax
// a struct field uses) or `static NAME: Type;` (static field, read via `Alias::NAME` -- see
// Expr.StaticFieldGet) inside an `extern class` body -- a real JVM field (e.g. `Minecraft
// .player`, `ForgeRegistries.ITEMS`). Explicit-signature form only, same scope cut as
// ExternMethodDecl.
data class ExternFieldDecl(val name: String, val type: TypeRef, val line: Int, val isStatic: Boolean = true)
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
// `sourceUnit`: which `.hc` FILE this was declared in, stamped on only for a directory-mode
// multi-file compile (see Main.kt's `compileEntry`) -- `null` for a single-file compile, where
// there's only one file so the distinction is meaningless. Exists specifically so CodeGen's
// "named holder" merge (see IDEAS.md's now-shipped "Named binary target for module-level pub
// fn/pub static") can group by *file*, not just by `moduleName`: multiple files legitimately
// share one module today (`client/CopyToolHudOverlay.hc`, `CopyToolSelectionRender.hc`,
// `RenderUtil.hc` all declare `module ...client;`), each with its own struct meant to be its
// own separately-named class -- grouping by module alone would silently merge all three files'
// top-level fns onto whichever struct happened to be first, breaking every other file's
// `OtherStruct::its_fn(...)` call sites that assumed their own file's struct was still the
// target. See CodeGen's `moduleFirstStructName`.
// `@serializable`: a bare compiler *directive*, not a real Java annotation -- distinguished from
// `@"binary.Name"(...)` purely by the absence of a quoted string right after `@` (see the
// parser's `leadingMarkers`). Triggers real code generation (two synthesized top-level `pub fn`s,
// `encode`/`decode`, fed through the exact same checking/codegen pipeline any hand-written fn
// goes through -- see Checker's `genSerializationFns`), not a classfile attribute; nothing about
// it is emitted as bytecode metadata the way `annotations` above is. Scoped to exactly one
// target for this first pass -- a real JVM `FriendlyByteBuf` (Forge's network-packet buffer
// wrapper) the struct's own fields get written to/read from, field-by-field, in declaration
// order -- see the README's "`@serializable`" section for why this target and not a general
// pluggable one.
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

// `@"binary.Name"(argName: value, ...)` immediately before a top-level `struct`/`fn` -- a real
// Java annotation, emitted as a genuine classfile `RuntimeVisibleAnnotations` attribute (see
// CodeGen's `emitAnnotations`), not just a compiler-internal marker. Exists specifically to
// reach reflection-driven Java frameworks (Forge's `@SubscribeEvent`/`@Mod.EventBusSubscriber`
// event-bus scanning being the motivating case -- see the README's "Java annotations" section)
// where there's no programmatic registration API to call instead. Annotation argument *values*
// are compile-time constants baked directly into the classfile attribute, not executable code --
// deliberately a much smaller grammar than a real expression (`AnnotationValue`, below), not the
// general `Expr` this language uses everywhere else.
data class AnnotationUse(val binaryName: String, val args: List<Pair<String, AnnotationValue>>, val line: Int)

// `@entry("forge.mod", modid: "yourmodid")` immediately before a zero-arg, `Unit`-returning
// top-level `fn` -- a compiler directive (not a real annotation, same "no quoted binary name at
// all" distinction `@serializable` already uses), scoped narrowly to exactly one `target` for
// this first pass: `"forge.mod"`. Generates a real `@Mod("yourmodid")`-annotated class whose
// constructor calls this fn -- Forge's own `@Mod` class is instantiated via a real no-arg
// constructor with an actual imperative body (registering the mod event bus, ...), unlike
// `@SubscribeEvent`'s pure method-scanning, so this is the one piece of "a whole Forge mod
// written in HC" that genuinely needs new codegen (a struct's constructor synthesizing a real
// extra call), not just reuse of the existing annotation-emission machinery. The target struct
// this attaches to is found the same way `@serializable`'s generated fns find their landing
// class: the *first* struct declared in the same file (module + sourceUnit) as this fn -- see
// Checker's `@entry` handling. `args` reuses `AnnotationValue`'s existing small grammar (a
// string literal is all `"forge.mod"` needs right now) rather than inventing a separate one.
data class EntryDirective(val target: String, val args: List<Pair<String, AnnotationValue>>, val line: Int)
sealed class AnnotationValue {
    data class Str(val value: String) : AnnotationValue()
    // A real Java `enum` constant (e.g. `Dist.CLIENT`), stored as (the enum's own binary name,
    // the constant's name) -- exactly what `AnnotationVisitor.visitEnum` needs, and exactly what
    // the classfile format itself stores for an enum-typed annotation argument (there's no
    // "executable reference" to a JVM enum constant the way `GETSTATIC` reads one at runtime;
    // annotation metadata is inert data read back via reflection, never executed).
    data class EnumConst(val enumBinaryName: String, val constName: String) : AnnotationValue()
    // `[value, value, ...]` -- an array-typed annotation attribute (e.g. Forge's
    // `Mod.EventBusSubscriber.value()`, which is `Dist[]`, not a single `Dist`). Classfile-level
    // array annotation values are a genuinely different encoding from a scalar one
    // (`AnnotationVisitor.visitArray` wrapping N nested element writes, vs one direct `visit`/
    // `visitEnum` call) -- there's no reflection against a real `@interface` to infer this from
    // (see AnnotationUse's doc), so the source has to say which shape it means: bare
    // `enum("...", "CLIENT")` for a scalar-typed attribute, `[enum("...", "CLIENT")]` (even with
    // one element) for an array-typed one. Writing a bare value where the real attribute is
    // actually array-typed compiles fine but produces a classfile a real annotation-array
    // consumer chokes on at class-load/scan time (verified: Forge's own `@Mod.EventBusSubscriber`
    // handling throws a `ClassCastException` casting its scanned `EnumHolder` to `List` when
    // `value` was written scalar) -- exactly the kind of "trust the declaration" failure mode
    // every other `extern`-adjacent feature in this language already has, not a new one.
    data class Arr(val values: List<AnnotationValue>) : AnnotationValue()
}
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
    val annotations: List<AnnotationUse> = emptyList(),
    // See StructDecl's `sourceUnit` doc -- same purpose, same "which file, for a directory-mode
    // compile only" meaning.
    val sourceUnit: String? = null,
    val entry: EntryDirective? = null,
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
    // `expr is Type` -- a real runtime type check (JVM `INSTANCEOF`), the boolean-returning
    // counterpart to `as`'s (potentially-throwing) cast. Same reference-type-only scope as `as`.
    // `expr.ty` (inherited from the base class) ends up `Bool_` (this node's own result type),
    // so the checker's resolved *target* type is cached separately here for codegen to read back
    // (`checkcastOperand` needs the real Ty, not the raw `target: TypeRef`).
    data class InstanceOf(val inner: Expr, val target: TypeRef, val line: Int) : Expr() {
        var resolvedTargetTy: Ty? = null
    }
    data class Borrow(val inner: Expr, val isMut: Boolean = false) : Expr()
    data class Assign(val name: String, val value: Expr, val line: Int) : Expr()
    data class Call(val callee: String, val args: List<Expr>, val line: Int) : Expr()
    data class FieldAccess(val obj: Expr, val field: String, val line: Int) : Expr() {
        // Set by the checker (checkFieldAccess) when `field` doesn't name a declared
        // `extern class` field but DOES match a zero-arg `getField`/`isField` instance method --
        // property-style sugar (`enemy.health` reading as `enemy.getHealth()`) so interop-heavy
        // code doesn't have to spell out Java's bean-getter ceremony. Holds the real method name
        // to call (`"getHealth"`/`"isHealth"`, not `"health"`); `resolvedName` (inherited) is
        // still the owning extern class's binary name, same field both plain field reads and
        // this sugar populate. Null for every other `FieldAccess` (struct fields, array
        // `.length`, arena fields, a real declared extern field) -- codegen's default GETFIELD
        // path is unaffected.
        var externGetterMethod: String? = null
    }
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
        // See ExternClassDecl's `isInterface` doc -- codegen needs this to pick INVOKEINTERFACE
        // over INVOKEVIRTUAL for an instance call (and the right constant-pool-entry kind for a
        // static one) when the real target is a Java interface, not a class.
        var isExternInterface: Boolean = false
    }
    // `TypeName::method(args)` -- a constructor (`method == "new"`, NEW + INVOKESPECIAL) or a
    // static method (INVOKESTATIC) on an `extern class`. `resolvedName` (inherited) is set to
    // the extern class's binary name. `externParamTys` -- see MethodCall.externParamTys.
    // `Alias::FIELD` -- reads a declared `extern class` static field (GETSTATIC). Distinguished
    // from StaticCall at parse time by the absence of a following `(...)`.
    data class StaticFieldGet(val typeName: String, val field: String, val line: Int) : Expr()
    data class StaticCall(val typeName: String, val method: String, val args: List<Expr>, val line: Int) : Expr() {
        var externParamTys: List<Ty>? = null
        // See ExternClassDecl's `isInterface` doc.
        var isExternInterface: Boolean = false
    }
    data class ArrayLit(val elements: List<Expr>, val line: Int) : Expr()
    data class ArrayRepeat(val value: Expr, val count: Expr, val line: Int) : Expr()
    data class Index(val arr: Expr, val index: Expr, val line: Int) : Expr()
    data class IndexAssign(val arr: Expr, val index: Expr, val value: Expr, val line: Int) : Expr()
    // `arena Particle[count]`: allocates an auto-managed off-heap buffer of `count` elements.
    data class ArenaNew(val structName: String, val count: Expr, val line: Int) : Expr()
    // Only valid directly as a `for x in a..b` iterable -- not a general-purpose expression.
    data class Range(val start: Expr, val end: Expr, val line: Int) : Expr()
    // `if cond { thenExpr } else { elseExpr }` used as a value (a `let` RHS, a call arg, ...) --
    // distinct from `Stmt.If` (parsed at statement position, no value needed, `elseB` optional).
    // Deliberately narrow first pass: `else` is required (no value without one), and each branch
    // block must contain *exactly one* statement, itself a bare expression (`Stmt.ExprStmt`) --
    // this is a ternary-shaped if-expression (every branch a single expression), not Rust's full
    // "last statement in any block, sans semicolon, is the block's value" model, which would need
    // a real grammar change (this needs none: `{ expr; }` already parses today, just previously
    // only ever discarded as a statement). The checker enforces the one-ExprStmt-per-branch shape
    // and that both branches produce the same type; codegen reads each branch's sole statement's
    // expr directly (no `genBlock`, no scope/drop machinery -- a single bare expression can't
    // introduce a `let` binding that would need either).
    data class If(val cond: Expr, val thenB: Block, val elseB: Block, val line: Int) : Expr()
    // `match scrutinee { Variant { a, b } => expr, _ => expr }` used as a value -- same
    // "exactly one expression per arm" restriction as `If` above, same reason. Otherwise
    // shares every dispatch rule (enum tag compare vs `&dyn sealed interface` instanceof chain,
    // exhaustiveness) with the statement form (`Stmt.Match`).
    data class Match(val scrutinee: Expr, val arms: List<MatchArm>, val line: Int) : Expr()
    // `|params| body` -- a lambda literal, e.g. `|| p as JObject` or `|x| ClipboardPacket
    // ::encode(x, buf)`. `body` is a single expression (no statements, no locals of its own,
    // no explicit `return`) -- same "ternary-shaped" scope cut as `Expr.If`/`Expr.Match`'s
    // value forms, chosen because every real motivating case (a Forge `Supplier`/`Function`/
    // custom functional-interface argument) is a one-expression forwarding call anyway; a
    // richer body just factors the real logic into an ordinary top-level `fn` and forwards to
    // it from here. Only ever legal where the surrounding context already pins down a target
    // type (a call argument whose declared param type is a single-method `extern interface`) --
    // there is no way to spell a lambda's type out explicitly the way a `let`'s does, so it's
    // always inferred, never declared. Compiles to a genuine tiny implementer class (this
    // compiler has no `invokedynamic`/`LambdaMetafactory` support), not a bootstrap-method call
    // site -- see CodeGen's `genLambdaClass`.
    data class Lambda(val params: List<String>, val body: Expr, val line: Int) : Expr() {
        // Everything below is filled in by the checker (Checker.checkLambda) once the call
        // argument's declared type resolves this lambda's target -- null on every Lambda the
        // checker rejected (already reported as an error; codegen never reaches those).
        var targetInterfaceName: String? = null // the HC-side 'extern interface' name
        var targetBinaryName: String? = null // the real JVM interface this implements
        var targetMethodName: String? = null // the single abstract method being implemented
        var paramTys: List<Ty> = emptyList() // that method's own resolved param types
        var retTy: Ty = hc.sema.Ty.Unit_ // that method's own resolved return type
        // Enclosing-scope locals this lambda's body reads, in first-use order -- become fields
        // on the synthesized implementer class, populated from a real constructor call at the
        // lambda's own use site (an ordinary move-capture, exactly like passing the same local
        // to any other call: the outer function can't read it again afterward).
        var captures: List<LambdaCapture> = emptyList()
        // This lambda's synthesized implementer class name -- a bare "Lambda$N" right after the
        // checker assigns it, then rewritten in place to the fully-module-qualified JVM name by
        // CodeGen.generate() before any class bodies are emitted (see CodeGen's lambda-collection
        // pass) -- every codegen call site (both the class's own generation and every NEW/
        // INVOKESPECIAL construction at a use site) reads this same field, so the two can never
        // disagree on the name.
        var syntheticName: String? = null
    }
    // `Type.class` -- a real Java class-literal value (`Ljava/lang/Class;`), needed wherever a
    // Java API asks for a `Class<T>` directly rather than an instance (Forge's
    // `SimpleChannel.registerMessage(int, Class<MSG>, ...)` being the motivating case). Parsed
    // directly off the `IDENT '.' 'class'` token sequence (see Parser's callOrPrimary) rather
    // than going through ordinary FieldAccess/MethodCall machinery -- "class" is deliberately
    // not a reserved keyword (see the lexer's own note on "use"), so this is recognized purely
    // by the literal text "class" appearing right after a dot, same as any other dot-suffix
    // token the parser peeks at.
    data class ClassLit(val typeName: String, val line: Int) : Expr() {
        // `resolvedName` (inherited): the JVM binary name to embed in the LDC operand. For an
        // `extern class`/`extern interface` alias, that's the real trusted binary name
        // (`isExtern = true`, no further translation needed). For a name this compiler itself
        // declared (a struct/enum), `resolvedName` is left as the bare declared name and
        // CodeGen has to run it through its own `qualify()` at codegen time (module-prefixed) --
        // the checker has no `moduleOf` map to do that here, same split responsibility every
        // other AST node with a possibly-HC-owned target name already has.
        var isExtern: Boolean = false
    }
}
data class LambdaCapture(val name: String, val ty: Ty)
