package hc.sema

// NOTE: each data class below declares its own `override fun toString()` directly in its body
// -- a data class always auto-generates toString(), which silently shadows one declared only
// on the sealed parent, so putting it here is the only way it actually takes effect. (Learned
// the hard way: error messages were printing "Struct(name=Player)" instead of "Player" for a
// while before this was caught.)
sealed class Ty {
    object Int_ : Ty() { override fun toString() = "Int" }
    object Long_ : Ty() { override fun toString() = "Long" }
    object Float_ : Ty() { override fun toString() = "Float" }
    object Double_ : Ty() { override fun toString() = "Double" }
    object Bool_ : Ty() { override fun toString() = "Bool" }
    object Str_ : Ty() { override fun toString() = "String" }
    object Unit_ : Ty() { override fun toString() = "Unit" }
    data class Struct(val name: String) : Ty() { override fun toString() = name }
    data class Array(val elem: Ty) : Ty() { override fun toString() = "[$elem]" }
    // A contiguous off-heap buffer of `structName` (an `arena struct`), backed by a real
    // MemorySegment -- no boxing, no per-element JVM object, cache-friendly layout.
    data class Arena(val structName: String) : Ty() { override fun toString() = "Arena<$structName>" }
    // `&dyn InterfaceName` / `&mut dyn InterfaceName` -- a reference that can point to any
    // struct implementing the interface, dispatched dynamically (INVOKEINTERFACE). Always
    // used behind a borrow; there's no owned `dyn X` in this language.
    data class Dyn(val interfaceName: String) : Ty() { override fun toString() = "dyn $interfaceName" }
    // A flat tagged union (no inheritance): one JVM class holding every variant's fields
    // side by side (namespaced `variant$field`), discriminated by an int tag. See EnumInfo.
    data class Enum(val name: String) : Ty() { override fun toString() = name }
    // A declared, trusted shape for an existing external JVM class (`extern class`). Move-only,
    // treated as an opaque object reference -- the compiler never looks inside it beyond the
    // method table it was declared with. `nullable`: this value came from a JVM call declared
    // `-> Type?` and hasn't been null-checked yet -- calling a method on it is a checker error
    // until it's narrowed (see Checker's `nullCheckedIdentName`/guard-clause narrowing). A
    // `JavaExtern` with `nullable=true` and one with `nullable=false` for the *same* binary name
    // are deliberately unequal as far as data-class `==` is concerned: that's what makes passing
    // a still-nullable value anywhere a non-nullable one is expected (an argument, a field, a
    // `let`'s declared type) fail through the ordinary "expected X, got Y" type-mismatch path,
    // with no extra checking code needed at any of those call sites.
    data class JavaExtern(val binaryName: String, val nullable: Boolean = false) : Ty() {
        override fun toString() = binaryName.substringAfterLast('/') + (if (nullable) "?" else "")
    }
}

// Value types: assigning/passing them never "moves" the source (Copy semantics).
// Everything else (structs, arrays, arena buffers, dyn refs) has move semantics like Rust.
fun Ty.isCopy(): Boolean = this is Ty.Int_ || this is Ty.Long_ || this is Ty.Float_ || this is Ty.Double_ || this is Ty.Bool_ || this is Ty.Str_

// `Double` (like JVM `long`) takes *two* consecutive local-variable-table/operand-stack slots,
// not one -- every slot-allocating/stack-duplicating call site (declareLocal/declareParam/
// allocTemp, DUP vs DUP2) needs to check this, not just assume every value is 1 slot wide.
// `Float` is a normal 1-slot value, same as Int/Bool -- only Double and Long are wide here.
fun Ty.isWide(): Boolean = this is Ty.Double_ || this is Ty.Long_

fun Ty.descriptor(): String = when (this) {
    Ty.Int_ -> "I"
    Ty.Long_ -> "J"
    Ty.Float_ -> "F"
    Ty.Double_ -> "D"
    Ty.Bool_ -> "Z"
    Ty.Str_ -> "Ljava/lang/String;"
    Ty.Unit_ -> "V"
    is Ty.Struct -> "L${this.name};"
    is Ty.Array -> "[${elem.descriptor()}"
    is Ty.Arena -> "Ljava/lang/foreign/MemorySegment;"
    is Ty.Dyn -> "L${this.interfaceName};"
    is Ty.Enum -> "L${this.name};"
    is Ty.JavaExtern -> "L${this.binaryName};"
}

fun Ty.isObjectRef(): Boolean = this is Ty.Str_ || this is Ty.Struct || this is Ty.Array || this is Ty.Arena || this is Ty.Dyn || this is Ty.Enum || this is Ty.JavaExtern

// `extern class` method table: `params`/`retType` are already resolved `Ty`s (from the exact
// erased-JVM-signature the extern decl gave). `isStatic == false && name != "new"` -> instance
// method (INVOKEVIRTUAL, first param is NOT `self` -- self is the receiver, not in this list).
class ExternMethodInfo(val name: String, val params: List<Ty>, val retType: Ty, val isStatic: Boolean, val isCtor: Boolean)
// `lazy`: this class's members were declared with no signatures at all (bare `extern class X =
// "binary.Name";`, option 3) -- `methods` starts empty and the checker fills entries in one
// name at a time, on demand, the first time a call site actually asks for that name (see
// Checker.resolveExternMethods). Not set for the `use { name, ... }` form (option 2): those
// resolve every requested name up front at registration time, same as hand-written signatures,
// so `methods` is already complete by the time any call site is checked.
class ExternClassInfo(val name: String, val binaryName: String, val methods: List<ExternMethodInfo>, val lazy: Boolean = false) {
    fun method(name: String): List<ExternMethodInfo> = methods.filter { it.name == name }
}

class StructInfo(val name: String, val fields: List<Pair<String, Ty>>) {
    fun fieldType(name: String): Ty? = fields.firstOrNull { it.first == name }?.second
    fun fieldIndex(name: String): Int = fields.indexOfFirst { it.first == name }
}

// One variant of an enum: `tag` is its 0-based declaration-order index (the discriminant
// stored in every instance's `tag` field), `fields` are namespaced `variant$field` on the
// class (see EnumInfo) so two variants can reuse a field name without colliding.
class EnumVariantInfo(val name: String, val tag: Int, val fields: List<Pair<String, Ty>>) {
    fun fieldType(fieldName: String): Ty? = fields.firstOrNull { it.first == fieldName }?.second
}
class EnumInfo(val name: String, val variants: List<EnumVariantInfo>, val moduleName: String? = null, val visible: Boolean = false) {
    fun variant(name: String): EnumVariantInfo? = variants.firstOrNull { it.name == name }
    val variantNames: List<String> get() = variants.map { it.name }
}

// Layout for an `arena struct`: every field is 4 or 8 bytes (Int, Long, or Bool), laid out in
// declaration order. Simple over byte-packed -- a phase-3 concern.
class ArenaLayout(val structName: String, val fields: List<Pair<String, Ty>>) {
    val elemSize: Int = fields.sumOf { if (it.second.isWide()) 8 else (4 as Int) }
    fun fieldType(name: String): Ty? = fields.firstOrNull { it.first == name }?.second
    fun fieldOffset(name: String): Int {
        var offset = 0
        for ((fname, fty) in fields) {
            if (fname == name) return offset
            offset += if (fty.isWide()) 8 else 4
        }
        return -1
    }
}
