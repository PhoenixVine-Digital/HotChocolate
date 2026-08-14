package hc.sema

sealed class Ty {
    object Int_ : Ty() { override fun toString() = "Int" }
    object Long_ : Ty() { override fun toString() = "Long" }
    object Float_ : Ty() { override fun toString() = "Float" }
    object Double_ : Ty() { override fun toString() = "Double" }
    object Bool_ : Ty() { override fun toString() = "Bool" }

    data class Str_(val nullable: Boolean = false) : Ty() { override fun toString() = "String" + (if (nullable) "?" else "") }
    object Unit_ : Ty() { override fun toString() = "Unit" }
    data class Struct(val name: String) : Ty() { override fun toString() = name }
    data class Array(val elem: Ty) : Ty() { override fun toString() = "[$elem]" }

    data class Arena(val structName: String) : Ty() { override fun toString() = "Arena<$structName>" }

    data class Dyn(val interfaceName: String) : Ty() {
        override fun toString() = if (interfaceName.startsWith("\$Bound\$")) {
            "T: " + interfaceName.removePrefix("\$Bound\$").split("\$").joinToString(" + ")
        } else "dyn $interfaceName"
    }

    data class Enum(val name: String) : Ty() { override fun toString() = name }

    data class JavaExtern(val binaryName: String, val nullable: Boolean = false) : Ty() {
        override fun toString() = binaryName.substringAfterLast('/') + (if (nullable) "?" else "")
    }
}

fun Ty.isCopy(): Boolean = this is Ty.Int_ || this is Ty.Long_ || this is Ty.Float_ || this is Ty.Double_ || this is Ty.Bool_ || this is Ty.Str_

fun Ty.isWide(): Boolean = this is Ty.Double_ || this is Ty.Long_

fun Ty.descriptor(): String = when (this) {
    Ty.Int_ -> "I"
    Ty.Long_ -> "J"
    Ty.Float_ -> "F"
    Ty.Double_ -> "D"
    Ty.Bool_ -> "Z"
    is Ty.Str_ -> "Ljava/lang/String;"
    Ty.Unit_ -> "V"
    is Ty.Struct -> "L${this.name};"
    is Ty.Array -> "[${elem.descriptor()}"
    is Ty.Arena -> "Ljava/lang/foreign/MemorySegment;"
    is Ty.Dyn -> "L${this.interfaceName};"
    is Ty.Enum -> "L${this.name};"
    is Ty.JavaExtern -> "L${this.binaryName};"
}

fun Ty.isObjectRef(): Boolean = this is Ty.Str_ || this is Ty.Struct || this is Ty.Array || this is Ty.Arena || this is Ty.Dyn || this is Ty.Enum || this is Ty.JavaExtern

class ExternMethodInfo(
    val name: String,
    val params: List<Ty>,
    val retType: Ty,
    val isStatic: Boolean,
    val isCtor: Boolean,
    val paramIsRef: List<Boolean> = params.map { false },
    val paramIsMut: List<Boolean> = params.map { false },
)

class ExternFieldInfo(val name: String, val type: Ty, val isStatic: Boolean = true)

class ExternClassInfo(val name: String, val binaryName: String, val methods: List<ExternMethodInfo>, val lazy: Boolean = false, val fields: List<ExternFieldInfo> = emptyList(), val isInterface: Boolean = false) {
    fun method(name: String): List<ExternMethodInfo> = methods.filter { it.name == name }
    fun field(name: String): ExternFieldInfo? = fields.firstOrNull { it.name == name }
}

class StructInfo(val name: String, val fields: List<Pair<String, Ty>>) {
    fun fieldType(name: String): Ty? = fields.firstOrNull { it.first == name }?.second
    fun fieldIndex(name: String): Int = fields.indexOfFirst { it.first == name }
}

class EnumVariantInfo(val name: String, val tag: Int, val fields: List<Pair<String, Ty>>) {
    fun fieldType(fieldName: String): Ty? = fields.firstOrNull { it.first == fieldName }?.second
}
class EnumInfo(val name: String, val variants: List<EnumVariantInfo>, val moduleName: String? = null, val visible: Boolean = false) {
    fun variant(name: String): EnumVariantInfo? = variants.firstOrNull { it.name == name }
    val variantNames: List<String> get() = variants.map { it.name }
}

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
