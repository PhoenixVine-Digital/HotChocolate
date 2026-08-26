package hc

import hc.ast.Block
import hc.ast.DocComment
import hc.ast.Program
import hc.ast.Stmt
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

private fun parseEntry(path: File): Program {
    if (path.isDirectory) {
        val files = path.walkTopDown().filter { it.isFile && it.extension == "hotc" }.toList()
            .sortedBy { it.relativeTo(path).path }
        if (files.isEmpty()) throw CodegenEntryError("no .hotc files found in directory '${path.path}'")
        var merged = Program(structs = emptyList(), fns = emptyList())
        for (f in files) {
            val parsed = Parser(Lexer(f.readText()).tokenize()).parseProgram()
            merged = mergeProgram(merged, stampSourceUnit(parsed, f.nameWithoutExtension))
        }
        return merged
    }
    return Parser(Lexer(path.readText()).tokenize()).parseProgram()
}

private fun stripDevCode(program: Program, devMode: Boolean): Program = program.copy(
    fns = program.fns.filter { devMode || !it.dev }.map { it.copy(body = stripDevBlock(it.body, devMode)) },
    impls = program.impls.map { it.copy(methods = it.methods.map { m -> m.copy(body = stripDevBlock(m.body, devMode)) }) },
    extends = program.extends.map { it.copy(methods = it.methods.map { m -> m.copy(body = stripDevBlock(m.body, devMode)) }) },
)

private fun stripDevBlock(b: Block, devMode: Boolean): Block = Block(b.stmts.mapNotNull { stripDevStmt(it, devMode) })

private fun stripDevStmt(s: Stmt, devMode: Boolean): Stmt? = when (s) {
    is Stmt.DevIf -> when {
        devMode -> Stmt.Nested(stripDevBlock(s.thenB, devMode))
        s.elseB != null -> Stmt.Nested(stripDevBlock(s.elseB, devMode))
        else -> null
    }
    is Stmt.If -> s.copy(thenB = stripDevBlock(s.thenB, devMode), elseB = s.elseB?.let { stripDevBlock(it, devMode) })
    is Stmt.While -> s.copy(body = stripDevBlock(s.body, devMode))
    is Stmt.For -> s.copy(body = stripDevBlock(s.body, devMode))
    is Stmt.Nested -> s.copy(block = stripDevBlock(s.block, devMode))
    is Stmt.Match -> s.copy(arms = s.arms.map { it.copy(body = stripDevBlock(it.body, devMode)) })
    is Stmt.Try -> s.copy(
        tryBlock = stripDevBlock(s.tryBlock, devMode),
        catches = s.catches.map { it.copy(body = stripDevBlock(it.body, devMode)) },
    )
    is Stmt.Let, is Stmt.ExprStmt, is Stmt.Return, is Stmt.Throw, is Stmt.Break, is Stmt.Continue -> s
}

fun compileEntry(path: File, mainClassName: String, classpath: List<String> = emptyList(), devMode: Boolean = true): CompileResult =
    compileProgram(parseEntry(path), mainClassName, classpath, devMode)

class CodegenEntryError(message: String) : RuntimeException(message)

fun compileProgram(userProgram: Program, mainClassName: String, classpath: List<String> = emptyList(), devMode: Boolean = true): CompileResult {
    val preludeProgram = Parser(Lexer(PRELUDE_SOURCE).tokenize()).parseProgram()

    val program = mergeProgram(preludeProgram, stripDevCode(userProgram, devMode))

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

fun generateDocs(path: File, classpath: List<String> = emptyList(), devMode: Boolean = true): String {
    val userProgram = stripDevCode(parseEntry(path), devMode)
    val preludeProgram = Parser(Lexer(PRELUDE_SOURCE).tokenize()).parseProgram()
    val checker = Checker(mergeProgram(preludeProgram, userProgram), ClasspathReflector(classpath))
    checker.check()

    val sb = StringBuilder("# API Reference\n\n")
    val docStructs = userProgram.structs.filter { it.docComment != null }.sortedBy { it.name }
    val docFns = userProgram.fns.filter { it.docComment != null }.sortedBy { it.name }
    if (docStructs.isNotEmpty()) {
        sb.append("## Structs\n\n")
        for (s in docStructs) renderDoc(sb, "struct ${s.name}", s.docComment!!)
    }
    if (docFns.isNotEmpty()) {
        sb.append("## Functions\n\n")
        for (f in docFns) {
            val params = f.params.joinToString(", ") { "${it.name}: ${it.type.name}" }
            val ret = f.retType?.let { " -> ${it.name}" } ?: ""
            renderDoc(sb, "fn ${f.name}($params)$ret", f.docComment!!)
        }
    }
    return sb.toString()
}

private fun renderDoc(sb: StringBuilder, heading: String, doc: DocComment) {
    sb.append("### `$heading`\n\n")
    if (doc.summary.isNotEmpty()) sb.append("${doc.summary}\n\n")
    if (doc.params.isNotEmpty()) {
        sb.append("**Parameters:**\n\n")
        for ((name, text) in doc.params) sb.append("- `$name`" + (if (text.isNotEmpty()) " -- $text" else "") + "\n")
        sb.append("\n")
    }
    doc.returns?.let { sb.append("**Returns:** $it\n\n") }
    for (ex in doc.examples) sb.append("**Example:**\n\n```\n$ex\n```\n\n")
    for (w in doc.warnings) sb.append("> \u26a0\ufe0f $w\n\n")
    doc.deprecated?.let { sb.append("**Deprecated.**" + (if (it.isNotEmpty()) " $it" else "") + "\n\n") }
    if (doc.sees.isNotEmpty()) {
        sb.append("**See also:** ")
        sb.append(doc.sees.joinToString(", ") { see -> "`${see.target}`" + (if (!see.resolved) " (unresolved)" else "") })
        sb.append("\n\n")
    }
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

private fun extractReleaseFlag(args: List<String>): Pair<List<String>, Boolean> {
    val positional = mutableListOf<String>()
    var release = false
    for (a in args) {
        if (a == "--release") release = true else positional += a
    }
    return positional to release
}

// `--debug` (default port 5005, matching the conventional Java default so a plain "Remote JVM
// Debug" configuration's own default port needs no adjustment) / `--debug=PORT` -- only meaningful
// for `run`, never `build`/`doc`. Adds a suspended JDWP listener to the SAME inner `java`
// invocation `run` already spawns via `ProcessBuilder` (see `main`'s own `"run" ->` branch below),
// rather than duplicating that invocation's classpath/JDK-version-check logic anywhere else (an
// IDE plugin's own Debug configuration is the intended caller of this flag, wired up in
// `hc-intellij-plugin`'s own `HCCommandLineState`/`HCDebugState`).
private fun extractDebugFlag(args: List<String>): Pair<List<String>, Int?> {
    val positional = mutableListOf<String>()
    var port: Int? = null
    for (a in args) {
        if (a == "--debug") {
            port = 5005
        } else if (a.startsWith("--debug=")) {
            port = a.removePrefix("--debug=").toIntOrNull()
        } else {
            positional += a
        }
    }
    return positional to port
}

fun main(rawArgs: Array<String>) {
    val (argsWithProfile, classpath) = extractClasspath(rawArgs)
    val (argsWithDebug, profilePath) = extractProfileFlag(argsWithProfile)
    val (argsWithRelease, debugPort) = extractDebugFlag(argsWithDebug)
    val (args, release) = extractReleaseFlag(argsWithRelease)
    if (args.isEmpty()) {
        System.err.println("usage: hc <run|build|doc> <file.hotc | project-dir> [outDir] [--classpath a.jar:b.jar] [--profile[=out.jfr]] [--release] [--debug[=port]]")
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
    if (cmd == "doc") {

        val docs = try {
            generateDocs(file, classpath, devMode = !release)
        } catch (e: Exception) {
            e.printStackTrace()
            System.err.println("compile error: ${e.message}")
            return
        }
        val outPath = File(args.getOrNull(2) ?: "api.md")
        outPath.parentFile?.mkdirs()
        outPath.writeText(docs)
        println("wrote docs to ${outPath.path}")
        return
    }
    
    val mainClassName = (if (file.isDirectory) file.name else file.nameWithoutExtension)
        .replaceFirstChar { it.uppercase() }

    val result = try {
        compileEntry(file, mainClassName, classpath, devMode = !release)
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
            // `suspend=y` -- the debuggee blocks until a debugger actually attaches, so whatever
            // launched this (an IDE's Debug configuration) never races the JVM past a breakpoint
            // that hasn't been registered yet. `address=*:$port` (not `localhost:`) so an IDE
            // running the SAME process tree can still attach even if it resolves "localhost"
            // differently than this JVM did -- the standard JDWP form for "listen on all
            // interfaces." The JVM's own jdwp agent already prints "Listening for transport
            // dt_socket at address: $port" to stderr once it's actually ready to accept a
            // connection -- the IDE side watches for exactly that line rather than guessing at a
            // fixed startup delay.
            val debugArgs = debugPort?.let { port ->
                listOf("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:$port")
            } ?: emptyList()
            val proc = ProcessBuilder(listOf(javaExe) + profileArgs + debugArgs + listOf("-cp", runCp, result.mainClassBinaryName.replace('/', '.')))
                .inheritIO()
                .start()
            val exitCode = proc.waitFor()
            outDir.deleteRecursively()
            if (exitCode != 0) System.err.println("process exited with code $exitCode")
            if (profilePath != null) println("wrote JFR recording to ${File(profilePath).absolutePath}")
        }
        else -> System.err.println("unknown command '$cmd' (expected 'run', 'build', or 'doc')")
    }
}
