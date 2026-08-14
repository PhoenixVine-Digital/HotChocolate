package hc

import hc.ast.Program
import hc.codegen.CodeGen
import hc.lexer.Lexer
import hc.parser.Parser
import hc.sema.Checker
import hc.sema.ClasspathReflector
import hc.sema.Ty
import java.io.File

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

fun compile(source: String, mainClassName: String, classpath: List<String> = emptyList()): CompileResult =
    compileProgram(Parser(Lexer(source).tokenize()).parseProgram(), mainClassName, classpath)

private fun stampSourceUnit(program: Program, unit: String): Program = Program(
    structs = program.structs.map { it.copy(sourceUnit = unit) },
    fns = program.fns.map { it.copy(sourceUnit = unit) },
    impls = program.impls,
    interfaces = program.interfaces,
    enums = program.enums,
    externs = program.externs,
    extends = program.extends,
    statics = program.statics.map { it.copy(sourceUnit = unit) },
)

fun compileEntry(path: File, mainClassName: String, classpath: List<String> = emptyList()): CompileResult {
    if (path.isDirectory) {
        val files = path.walkTopDown().filter { it.isFile && it.extension == "hotc" }.toList()
            .sortedBy { it.relativeTo(path).path }
        if (files.isEmpty()) throw CodegenEntryError("no .hotc files found in directory '${path.path}'")
        var merged = Program(structs = emptyList(), fns = emptyList())
        for (f in files) {
            val parsed = Parser(Lexer(f.readText()).tokenize()).parseProgram()
            merged = mergeProgram(merged, stampSourceUnit(parsed, f.nameWithoutExtension))
        }
        return compileProgram(merged, mainClassName, classpath)
    }
    return compile(path.readText(), mainClassName, classpath)
}

class CodegenEntryError(message: String) : RuntimeException(message)

fun compileProgram(userProgram: Program, mainClassName: String, classpath: List<String> = emptyList()): CompileResult {
    val preludeProgram = Parser(Lexer(PRELUDE_SOURCE).tokenize()).parseProgram()
    val program = mergeProgram(preludeProgram, userProgram)

    val reflector = ClasspathReflector(classpath)
    val checker = Checker(program, reflector)
    checker.check()
    val fnRetTypes = checker.fns.mapValues { it.value.ret } + ("print" to Ty.Unit_) + ("read_line" to Ty.Str_())
    val fnParamTypes = checker.fns.mapValues { it.value.paramTys }
    val codegen = CodeGen(
        checker.resolvedProgram(), checker.structs, fnRetTypes, fnParamTypes, mainClassName, checker.arenaLayouts,
        program.interfaces, checker.interfaces, checker.structInterfaces, checker.interfaceImplFns, checker.enums,
        checker.statics, checker.structSuperclass, checker.externClasses, checker.superclassOverrideFns,
        checker.entryClassName, checker.entryModid, checker.entryInitFn,
    )
    val classes = codegen.generate()
    return CompileResult(classes, codegen.usesArena, codegen.entryHolderClassName)
}

private fun findJava(): String {
    for (envVar in listOf("HC_JAVA_HOME", "JAVA_HOME")) {
        val home = System.getenv(envVar) ?: continue
        val exe = File(home, "bin/java.exe").takeIf { it.exists() } ?: File(home, "bin/java")
        if (exe.exists()) return exe.path
    }
    return "java" 
}

private fun javaMajorVersion(javaExe: String): Int? = try {
    val proc = ProcessBuilder(javaExe, "-version").redirectErrorStream(true).start()
    val output = proc.inputStream.bufferedReader().readText()
    proc.waitFor()
    val m = Regex("version \"(\\d+)(?:\\.(\\d+))?").find(output)
    when {
        m == null -> null
        m.groupValues[1] == "1" -> m.groupValues[2].toIntOrNull() 
        else -> m.groupValues[1].toIntOrNull()
    }
} catch (e: Exception) {
    null
}

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

private fun extractProfileFlag(args: List<String>): Pair<List<String>, String?> {
    val positional = mutableListOf<String>()
    var profilePath: String? = null
    for (a in args) {
        if (a == "--profile") {
            profilePath = "profile.jfr"
        } else if (a.startsWith("--profile=")) {
            profilePath = a.removePrefix("--profile=")
        } else {
            positional += a
        }
    }
    return positional to profilePath
}

fun main(rawArgs: Array<String>) {
    val (argsWithProfile, classpath) = extractClasspath(rawArgs)
    val (args, profilePath) = extractProfileFlag(argsWithProfile)
    if (args.isEmpty()) {
        System.err.println("usage: hc <run|build> <file.hotc | project-dir> [outDir] [--classpath a.jar:b.jar] [--profile[=out.jfr]]")
        return
    }
    val cmd = args[0]
    val path = args.getOrNull(1)
    if (path == null) {
        System.err.println("missing <file.hotc | project-dir>")
        return
    }
    val file = File(path)
    if (!file.exists()) {
        System.err.println("no such file or directory '$path'")
        return
    }
    
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

            val runCp = (listOf(outDir.path) + classpath).joinToString(File.pathSeparator)

            val profileArgs = profilePath?.let { p ->
                listOf("-XX:StartFlightRecording=filename=${File(p).absolutePath},settings=profile")
            } ?: emptyList()
            val proc = ProcessBuilder(listOf(javaExe) + profileArgs + listOf("-cp", runCp, result.mainClassBinaryName.replace('/', '.')))
                .inheritIO()
                .start()
            val exitCode = proc.waitFor()
            outDir.deleteRecursively()
            if (exitCode != 0) System.err.println("process exited with code $exitCode")
            if (profilePath != null) println("wrote JFR recording to ${File(profilePath).absolutePath}")
        }
        else -> System.err.println("unknown command '$cmd' (expected 'run' or 'build')")
    }
}