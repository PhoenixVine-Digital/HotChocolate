package hc.sema

import java.io.File
import java.lang.reflect.Modifier
import java.net.URLClassLoader

class ClasspathReflector(classpathEntries: List<String>) {
    private val loader: ClassLoader = if (classpathEntries.isEmpty()) {
        Thread.currentThread().contextClassLoader ?: ClasspathReflector::class.java.classLoader
    } else {
        URLClassLoader(
            classpathEntries.map { File(it).toURI().toURL() }.toTypedArray(),
            Thread.currentThread().contextClassLoader ?: ClasspathReflector::class.java.classLoader,
        )
    }

    private val classCache = mutableMapOf<String, Class<*>?>()

    private fun loadClass(binaryName: String): Class<*>? = classCache.getOrPut(binaryName) {
        try {
            Class.forName(binaryName.replace('/', '.'), false, loader)
        } catch (e: Throwable) {

            null
        }
    }

    fun classExists(binaryName: String): Boolean = loadClass(binaryName) != null

    fun resolveMembers(binaryName: String, memberName: String): List<ExternMethodInfo> {
        val cls = loadClass(binaryName) ?: return emptyList()
        return if (memberName == "new") {
            cls.constructors.filter { Modifier.isPublic(it.modifiers) }.mapNotNull { ctor ->
                val params = ctor.parameterTypes.map { javaClassToTyOrNull(it) }
                if (params.any { it == null }) return@mapNotNull null
                ExternMethodInfo(
                    name = "new",
                    params = params.map { it!! },

                    retType = Ty.Unit_,
                    isStatic = false,
                    isCtor = true,
                )
            }
        } else {
            cls.methods.filter { it.name == memberName && Modifier.isPublic(it.modifiers) }.mapNotNull { m ->
                val params = m.parameterTypes.map { javaClassToTyOrNull(it) }
                val ret = javaClassToTyOrNull(m.returnType)
                if (ret == null || params.any { it == null }) return@mapNotNull null
                ExternMethodInfo(
                    name = memberName,
                    params = params.map { it!! },
                    retType = ret,
                    isStatic = Modifier.isStatic(m.modifiers),
                    isCtor = false,
                )
            }
        }
    }

    fun publicMemberNames(binaryName: String): Set<String> {
        val cls = loadClass(binaryName) ?: return emptySet()
        return cls.methods.filter { Modifier.isPublic(it.modifiers) }.map { it.name }.toSet() + "new"
    }

    private fun javaClassToTyOrNull(c: Class<*>): Ty? = when {
        c == java.lang.Integer.TYPE -> Ty.Int_
        c == java.lang.Long.TYPE -> Ty.Long_
        c == java.lang.Float.TYPE -> Ty.Float_
        c == java.lang.Double.TYPE -> Ty.Double_
        c == java.lang.Boolean.TYPE -> Ty.Bool_
        c == java.lang.Void.TYPE -> Ty.Unit_
        c == java.lang.String::class.java -> Ty.Str_()
        c.isArray -> javaClassToTyOrNull(c.componentType)?.let { Ty.Array(it) }
        c.isPrimitive -> null 
        else -> Ty.JavaExtern(c.name.replace('.', '/'))
    }
}
