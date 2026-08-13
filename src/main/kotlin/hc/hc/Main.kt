package hc

import hc.ast.Program
import hc.codegen.CodeGen
import hc.lexer.Lexer
import hc.parser.Parser
import hc.sema.Checker
import hc.sema.ClasspathReflector
import hc.sema.Ty
import java.io.File

// `mainClassBinaryName`: the entry class's JVM internal name (slash-separated, package prefix
// included when one was declared) -- matches a key in `classes`, and is what `hc run` launches.
class CompileResult(val classes: Map<String, ByteArray>, val usesArena: Boolean, val mainClassBinaryName: String)

private fun mergeProgram(a: Program, b: Program): Program = Program(
    structs = a.structs + b.structs,
    fns = a.fns + b.fns,
    impls = a.impls + b.impls,
    interfaces = a.interfaces + b.interfaces,
    enums = a.enums + b.enums,
    externs = a.externs + b.externs,
    extends = a.extends + b.extends,
    statics = a.statics + b.statics,
)

// `classpath`: jar/dir paths used to reflect real signatures for `extern class` members
// declared with `use { ... }` or with no body at all (see ExternClassDecl) -- e.g. Forge's/
// Minecraft's own jars for a mod project. Empty is fine for a program that only uses the
// original, hand-written-signature `extern class` form (or none at all); reflection is never
// attempted unless a declaration actually asks for it.
fun compile(source: String, mainClassName: String, classpath: List<String> = emptyList()): CompileResult =
    compileProgram(Parser(Lexer(source).tokenize()).parseProgram(), mainClassName, classpath)

// `path` a single file compiles just that file (as before); a directory compiles every
// `.hc` file directly inside it (non-recursive) as one flat program -- no import statements,
// every top-level name (struct/fn/interface/enum/extern class) shares one global namespace
// across all of them, exactly as if they'd been pasted into a single file. This is also what
// makes `sealed interface` actually mean something: "every implementer is declared in this
// compilation unit" now spans a real multi-file project, not just one file trivially.
fun compileEntry(path: File, mainClassName: String, classpath: List<String> = emptyList()): CompileResult {
    if (path.isDirectory) {
        val files = path.listFiles { f -> f.isFile && f.extension == "hc" }
            ?.sortedBy { it.name }
            ?: emptyList()
        if (files.isEmpty()) throw CodegenEntryError("no .hc files found in directory '${path.path}'")
        var merged = Program(structs = emptyList(), fns = emptyList())
        for (f in files) {
            val parsed = Parser(Lexer(f.readText()).tokenize()).parseProgram()
            merged = mergeProgram(merged, parsed)
        }
        return compileProgram(merged, mainClassName, classpath)
    }
    return compile(path.readText(), mainClassName, classpath)
}

class CodegenEntryError(message: String) : RuntimeException(message)

fun compileProgram(userProgram: Program, mainClassName: String, classpath: List<String> = emptyList()): CompileResult {
    val preludeProgram = Parser(Lexer(PRELUDE_SOURCE).tokenize()).parseProgram()
    val program = mergeProgram(preludeProgram, userProgram)
    // Cheap to construct even when unused -- a URLClassLoader doesn't load anything until a
    // class is actually requested, so a program with no `use { ... }`/bare `extern class` never
    // touches the classpath at all despite this always being built.
    val reflector = ClasspathReflector(classpath)
    val checker = Checker(program, reflector)
    checker.check()
    val fnRetTypes = checker.fns.mapValues { it.value.ret } + ("print" to Ty.Unit_) + ("read_line" to Ty.Str_)
    val fnParamTypes = checker.fns.mapValues { it.value.paramTys }
    val codegen = CodeGen(
        checker.resolvedProgram(), checker.structs, fnRetTypes, fnParamTypes, mainClassName, checker.arenaLayouts,
        program.interfaces, checker.interfaces, checker.structInterfaces, checker.interfaceImplFns, checker.enums,
        checker.statics, checker.structSuperclass, checker.externClasses, checker.superclassOverrideFns,
    )
    val classes = codegen.generate()
    return CompileResult(classes, codegen.usesArena, codegen.entryHolderClassName)
}

// A program that never touches `arena struct` compiles to plain JDK-17-compatible bytecode
// with zero java.lang.foreign references -- that's what makes it embeddable as e.g. a
// Minecraft 1.20.1 (Java 17) mod. `arena struct` needs java.lang.foreign, stable only from
// JDK 22+; the compiler itself still only needs JDK 17 (it just emits those calls as ASM
// string literals -- no compile-time dependency on the classes), but *running* a program that
// actually uses arenas needs a 22+ `java` to load them, so `hc run` shells out to one instead
// of executing in-process, and checks the version first when arenas are in play.
private fun findJava(): String {
    for (envVar in listOf("HC_JAVA_HOME", "JAVA_HOME")) {
        val home = System.getenv(envVar) ?: continue
        val exe = File(home, "bin/java.exe").takeIf { it.exists() } ?: File(home, "bin/java")
        if (exe.exists()) return exe.path
    }
    return "java" // resolved via PATH
}

private fun javaMajorVersion(javaExe: String): Int? = try {
    val proc = ProcessBuilder(javaExe, "-version").redirectErrorStream(true).start()
    val output = proc.inputStream.bufferedReader().readText()
    proc.waitFor()
    val m = Regex("version \"(\\d+)(?:\\.(\\d+))?").find(output)
    when {
        m == null -> null
        m.groupValues[1] == "1" -> m.groupValues[2].toIntOrNull() // old style "1.8.0_..." -> 8
        else -> m.groupValues[1].toIntOrNull()
    }
} catch (e: Exception) {
    null
}

// Pulls `--classpath <entries>` out of the raw args (accepting either the platform path
// separator -- ':' on Linux/macOS, ';' on Windows -- or ',' since a Java-style classpath string
// is easy to get wrong on the command line and ',' never collides with a real path). Returns the
// remaining positional args plus the resolved classpath list, with HC_CLASSPATH's own entries
// appended after any --classpath flag so both can be used together (e.g. a project keeps its own
// jars in HC_CLASSPATH and passes extra ones ad hoc via the flag).
private fun extractClasspath(args: Array<String>): Pair<List<String>, List<String>> {
    val positional = mutableListOf<String>()
    var flagged: List<String> = emptyList()
    var i = 0
    while (i < args.size) {
        if (args[i] == "--classpath" && i + 1 < args.size) {
            flagged = args[i + 1].split(':', ';', ',').filter { it.isNotBlank() }
            i += 2
        } else {
            positional += args[i]
            i += 1
        }
    }
    val envEntries = System.getenv("HC_CLASSPATH")?.split(':', ';', ',')?.filter { it.isNotBlank() } ?: emptyList()
    return positional to (flagged + envEntries)
}

fun main(rawArgs: Array<String>) {
    val (args, classpath) = extractClasspath(rawArgs)
    if (args.isEmpty()) {
        System.err.println("usage: hc <run|build> <file.hc | project-dir> [outDir] [--classpath a.jar:b.jar]")
        return
    }
    val cmd = args[0]
    val path = args.getOrNull(1)
    if (path == null) {
        System.err.println("missing <file.hc | project-dir>")
        return
    }
    val file = File(path)
    if (!file.exists()) {
        System.err.println("no such file or directory '$path'")
        return
    }
    // A directory's class name has no `.hc` extension to strip -- just its own name.
    val mainClassName = (if (file.isDirectory) file.name else file.nameWithoutExtension)
        .replaceFirstChar { it.uppercase() }

    val result = try {
        compileEntry(file, mainClassName, classpath)
    } catch (e: Exception) {
        e.printStackTrace()
        System.err.println("compile error: ${e.message}")
        return
    }
    val classes = result.classes

    when (cmd) {
        "build" -> {
            val outDir = File(args.getOrNull(2) ?: "out")
            outDir.mkdirs()
            for ((name, bytes) in classes) {
                // `name` is a JVM binary name (slash-separated, package prefix included when
                // one was declared) -- nest into matching subdirectories, same as javac would.
                val classFile = File(outDir, "$name.class")
                classFile.parentFile?.mkdirs()
                classFile.writeBytes(bytes)
            }
            println("wrote ${classes.size} class file(s) to ${outDir.path}")
            if (result.usesArena) {
                println("note: uses 'arena struct' -- these .class files need a JDK 22+ runtime to load (java.lang.foreign)")
            }
        }
        "run" -> {
            val javaExe = findJava()
            if (result.usesArena) {
                val version = javaMajorVersion(javaExe)
                if (version == null || version < 22) {
                    System.err.println(
                        "this program uses 'arena struct', which needs a JDK 22+ 'java' to run " +
                            "(found ${version?.let { "JDK $it" } ?: "an unrecognized/missing java"} at '$javaExe'). " +
                            "Set HC_JAVA_HOME (or JAVA_HOME) to a JDK 22+ install."
                    )
                    return
                }
            }
            val outDir = File.createTempFile("hc-run", "").apply { delete(); mkdirs() }
            for ((name, bytes) in classes) {
                val classFile = File(outDir, "$name.class")
                classFile.parentFile?.mkdirs()
                classFile.writeBytes(bytes)
            }
            val proc = ProcessBuilder(javaExe, "-cp", outDir.path, result.mainClassBinaryName.replace('/', '.'))
                .inheritIO()
                .start()
            val exitCode = proc.waitFor()
            outDir.deleteRecursively()
            if (exitCode != 0) System.err.println("process exited with code $exitCode")
        }
        else -> System.err.println("unknown command '$cmd' (expected 'run' or 'build')")
    }
}
