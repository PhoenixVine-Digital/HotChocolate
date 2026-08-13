package hc.sema

import java.io.File
import java.lang.reflect.Modifier
import java.net.URLClassLoader

// Reads real signatures for `extern class` members straight off a JVM classpath, via ordinary
// `java.lang.reflect` -- no bytecode-parsing library needed. Backs the `use { ... }` (eager) and
// bare, no-body (lazy) forms of `extern class`, so hand-written `fn getX(&self) -> Int;`-style
// signatures become optional: give the compiler a classpath and it reads the real ones instead.
// The explicit-signature form still works with zero JVM dependency (no classpath needed) --
// this class is only ever consulted for the other two forms.
//
// A member name can be overloaded in real Java (`String.valueOf` has ~9 forms). Every public
// overload matching the requested name is returned as its own ExternMethodInfo, exactly as if a
// hand-written extern block had listed each overload separately. This class itself doesn't pick
// among them -- Checker.pickCandidate does that (by argument count, same as it always could have
// for hand-written overloads, just rarely needed to since those were written one at a time).
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
            // ClassNotFoundException (not on the classpath) or LinkageError (present but its
            // own dependencies aren't on the classpath) -- either way, nothing to reflect.
            null
        }
    }

    // True as soon as the class loads at all, regardless of whether `memberName` (below) turns
    // out to exist on it -- lets the caller tell "no such class" apart from "class exists, no
    // such member" for a clearer error message.
    fun classExists(binaryName: String): Boolean = loadClass(binaryName) != null

    // Every public overload of `memberName` on `binaryName` -- "new" means constructors,
    // anything else means public instance/static methods (inherited ones included, same as
    // Java's own overload resolution would see via `Class.getMethods()`). Empty if the class
    // can't be loaded, has no public member with that name, or every overload found uses a
    // parameter/return type this compiler's type system doesn't represent (see
    // javaClassToTyOrNull) -- such overloads are silently dropped rather than surfaced as
    // corrupt signatures; if the member you need is dropped for that reason, fall back to a
    // hand-written signature for it (see ExternMethodDecl).
    fun resolveMembers(binaryName: String, memberName: String): List<ExternMethodInfo> {
        val cls = loadClass(binaryName) ?: return emptyList()
        return if (memberName == "new") {
            cls.constructors.filter { Modifier.isPublic(it.modifiers) }.mapNotNull { ctor ->
                val params = ctor.parameterTypes.map { javaClassToTyOrNull(it) }
                if (params.any { it == null }) return@mapNotNull null
                ExternMethodInfo(
                    name = "new",
                    params = params.map { it!! },
                    // Ignored for ctors -- see ExternMethodDecl's own comment; checkStaticCall
                    // always substitutes the extern class's own Ty.JavaExtern instead.
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

    // Every public member name declared or inherited on `binaryName` -- used only to build a
    // "did you mean" style error for `use { ... }` when a requested name isn't actually there.
    fun publicMemberNames(binaryName: String): Set<String> {
        val cls = loadClass(binaryName) ?: return emptySet()
        return cls.methods.filter { Modifier.isPublic(it.modifiers) }.map { it.name }.toSet() + "new"
    }

    // Null for anything this type system has no representation for (byte/short/char -- there's
    // no Ty for them) rather than guessing, since a wrong guess here would silently produce a
    // corrupt JVM descriptor in codegen instead of a clear compile-time skip.
    private fun javaClassToTyOrNull(c: Class<*>): Ty? = when {
        c == java.lang.Integer.TYPE -> Ty.Int_
        c == java.lang.Long.TYPE -> Ty.Long_
        c == java.lang.Float.TYPE -> Ty.Float_
        c == java.lang.Double.TYPE -> Ty.Double_
        c == java.lang.Boolean.TYPE -> Ty.Bool_
        c == java.lang.Void.TYPE -> Ty.Unit_
        c == java.lang.String::class.java -> Ty.Str_
        c.isArray -> javaClassToTyOrNull(c.componentType)?.let { Ty.Array(it) }
        c.isPrimitive -> null // byte, short, char -- unrepresentable here
        else -> Ty.JavaExtern(c.name.replace('.', '/'))
    }
}
