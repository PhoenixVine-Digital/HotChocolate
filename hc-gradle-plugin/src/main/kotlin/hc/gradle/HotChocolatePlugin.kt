package hc.gradle

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.JavaExec
import org.gradle.language.jvm.tasks.ProcessResources
import java.io.File
import java.util.zip.ZipFile

// JitPack's coordinates for this repo's own root module (the compiler itself -- `SelfhostCLI`
// lives there, not in this plugin module). See ARCHITECTURE.md's own "Published on JitPack"
// section for how a tag turns into a resolvable version here.
//
// **Fixed 2026-09-10**: the GitHub org this repo lives under renamed from
// `P-H-O-E-N-I-X-PackForge` to `PhoenixVine-Digital` -- GitHub itself transparently redirects
// `git clone`/`git push` against the OLD org name to the new one, but JitPack does NOT follow
// that redirect when resolving a Maven coordinate: `com.github.P-H-O-E-N-I-X-PackForge:
// HotChocolate:<tag>` simply fails to resolve post-rename, even for a tag that exists and
// builds fine under the new org name. Found scaffolding Marshmallow (the first real consumer to
// resolve this plugin from JitPack after the rename) -- every `hcCompile*`/plugin-application
// step failed with "Plugin ... was not found in any of the following sources" until this
// constant (and the matching `useModule(...)` mapping every consuming project's own
// `settings.gradle.kts` needs -- see README.md/ARCHITECTURE.md's own JitPack setup examples,
// and `hc-intellij-plugin`'s own new-project scaffolding template) were updated to match.
// **Fixed 2026-09-11 -- a third, independent real bug, found the same way as the other two on
// this same pass**: this constant was missing the REPO name segment JitPack's own multi-module
// coordinate convention needs (`hc-gradle-plugin`'s own `useModule(...)` mapping, in
// `settings.gradle.kts`, already had it right -- `com.github.<user>.<repo>:<module>:<version>`,
// the repo name folded into the GROUP, not a separate path segment -- this constant just never
// matched it). `"com.github.PhoenixVine-Digital"` alone resolves to
// `jitpack.io/com/github/PhoenixVine-Digital/hotchocolate/...` -- missing the `/HotChocolate/`
// segment the real artifact is actually published under
// (`jitpack.io/com/github/PhoenixVine-Digital/HotChocolate/hotchocolate/...`) -- confirmed via a
// real Gradle resolution failure ("Read timed out" against the WRONG URL, not a 404 -- JitPack's
// own reverse-proxy has no route registered for a group that never resolves to a real repo at
// all) after the other two fixes on this same pass were already verified working.
private const val COMPILER_GROUP = "com.github.PhoenixVine-Digital.HotChocolate"
// **Fixed 2026-09-11 -- a second, independent real bug** found the same way as the org-rename
// one above (actually resolving this plugin end to end for the first time, scaffolding
// Marshmallow): the repo's OWN root `settings.gradle.kts` sets `rootProject.name = "hotchocolate"`
// (lowercase) -- Gradle's own Maven-publish machinery uses that verbatim as the artifact id, and
// JitPack's own coordinate lookup is case-SENSITIVE, so requesting the capitalized
// "HotChocolate" 404s while the real, lowercase "hotchocolate" resolves (confirmed directly
// against JitPack's own URLs). Whatever CLI resolution "worked" against this constant before
// must have been against `compilerHome` (the local-checkout path, which never goes through this
// constant at all) -- nothing had exercised the `version =` / JitPack path end-to-end since
// before the self-hosted migration, so this had been silently broken for a while.
private const val COMPILER_ARTIFACT = "hotchocolate"
private const val JITPACK_URL = "https://jitpack.io"

// **Fixed 2026-09-10 -- a real, previously-undiscovered break.** This whole file used to invoke
// `hc.MainKt` with `args = listOf("build", path, outputDir)` -- the OLD, hand-written Kotlin
// compiler's own entry point and CLI shape. The compiler has since fully migrated to a
// self-hosted one (`SelfhostCLI`, written in Hot Chocolate itself -- see ARCHITECTURE.md's own
// "Status" section), and `hc.MainKt` no longer exists in the repo at all -- any tag built from
// current/recent HEAD has NO such class, so every `hcCompile*` task would fail immediately with
// `ClassNotFoundException` the instant it ran. Found and fixed only once this plugin was
// actually exercised end-to-end again for the first time since the migration (scaffolding a real
// consuming project, Marshmallow) -- never caught by anything in THIS repo's own test suite,
// since nothing here builds a real project through the published plugin.
//
// Three real, disclosed consequences of the fix, not just a class-name swap:
// 1. **`SelfhostCLI`'s own CLI has no `"build"` keyword at all** (`selfhost/Driver.hotc`'s own
//    `run_cli`) -- its real contract is positional: `[--target N] [--explain-schedule] [run]
//    <path> [outPath]`; the ABSENCE of `"run"` is what "compile only" already means, no separate
//    keyword needed. Sending literal `"build"` as the first arg under the OLD code would have
//    made `SelfhostCLI` try to compile a source file literally named `build`.
// 2. **`SelfhostCLI` writes every class relative to its own PROCESS WORKING DIRECTORY**, not to
//    an argument -- only the single ENTRY class respects an explicit `outPath` (`compile_program`'s
//    own `out_path` parameter); every struct/enum/generic-instantiation/lambda class it also
//    emits (`gen_struct`/`gen_enum`/`gen_interface`/`gen_lambda`) writes to a bare, cwd-relative
//    filename unconditionally. There is no "pass an output directory" argument to pass at all --
//    the ONLY way to control where compiled classes land is the process's own working directory,
//    which is why every `hcCompile*` task below now sets `workingDir` explicitly instead of
//    passing `outputDir` as a positional arg (which used to silently do nothing beyond naming
//    where the SINGLE entry class landed, mismatched against every other file the compile
//    produced).
// 3. **`--classpath` was not a recognized flag on the CLI at the time of this fix** -- it backs
//    real classpath-based `extern class`/`extern interface` SIGNATURE VERIFICATION (`Checker
//    .hotc`'s own `verify_extern_signature`, catching a wrong declared param/return type as a
//    compile error instead of a runtime `NoSuchMethodError`), proven to work via the compiler's
//    own internal `--classpath` self-test (`run_classpath_verify_probe` in `Driver.hotc`) but not
//    yet wired to `run_cli`'s own real argv parsing. Removed HERE at the time, rather than left in
//    place doing nothing. **Restored 2026-09-11**, now that `run_cli` genuinely parses `--classpath
//    <paths>` and threads it into `verify_extern_signature` for every declared extern method --
//    see this file's own later `--classpath`-prepending `doFirst` block for the real wiring.

// `hotChocolate { compilerHome = file(...); source(file("hc/foo.hc")); source(file("hc/bar.hc")) }`
// -- a real Gradle DSL extension, not a description of one. Replaces the hand-copied
// `tasks.register('hcCompile', JavaExec) { ... }` block that used to get pasted into every
// consuming project's build.gradle (see kubejs-aisle-tool's build history): same underlying
// mechanism (shell out to the compiler via JavaExec, exactly what `hc build <file> <outDir>`
// already does on the command line), just declared once here and reused, instead of copy-pasted
// per project.
open class HotChocolateExtension(private val project: Project) {
    // Root of the Hot Chocolate compiler's installed distribution (contains `lib/*.jar` with
    // `SelfhostCLI` as the application's main class) -- e.g. `./gradlew installDist` run against a
    // sibling HotChocolate checkout. The local-dev escape hatch: set this when you're actually
    // working on the compiler itself and need a change to show up without waiting on a published
    // tag. Most consuming projects should use `version` (below) instead -- if both are set,
    // `compilerHome` wins (an explicit local override always beats a resolved artifact).
    var compilerHome: File? = null

    // `version = "v0.1.5"` -- resolves the compiler as a real, already-published JitPack
    // dependency (`com.github.PhoenixVine-Digital.HotChocolate:hotchocolate:v0.1.5`, see the README's
    // "Published on JitPack") instead of requiring a local `./gradlew installDist` against a
    // sibling checkout. This is the normal path for a consuming project: no local HotChocolate
    // checkout, no manual build step, just a pinned version like any other dependency --
    // reproducible across machines/CI the way `compilerHome` (pointing at whatever a developer's
    // machine happens to have installed) never was. Exactly the string that follows the last
    // `:` in the Maven coordinate -- passed through as-is, including the leading `v`, no magic
    // normalization.
    var version: String? = null

    // Where every declared source's compiled `.class` files land, and what gets added to the
    // consuming project's `compileJava` classpath / bundled as a resource. Defaults to
    // `build/hc-classes`, matching the manual convention this plugin replaces.
    var outputDir: File = project.layout.buildDirectory.dir("hc-classes").get().asFile

    internal val sources = mutableListOf<Any>()
    internal val sourceDirs = mutableListOf<Any>()

    // `source(file("hc/foo.hc"))` -- accepts anything `Project.file(...)` accepts (a `File`, a
    // `String` path, ...). Declaration order matters: each source is compiled after the one
    // before it, so a later file's `extern class` can reach a real class an earlier one
    // produced (the same ordering the manual `hcCompileCopyTool.dependsOn(hcCompile)` chain
    // enforced by hand). Prefer `sourceDir` (below) whenever a project's own `.hc` files
    // reference *each other* -- this per-file form only really earns its keep for a single
    // standalone file, or when deliberately keeping certain sources compiled independently.
    fun source(path: Any) {
        sources.add(path)
    }

    // `sourceDir(file("src/main/hc"))` -- compiles every `.hc` file directly inside the
    // directory (non-recursive) as ONE shared, `extern`-free-between-them compile, the Gradle-
    // plugin side of the CLI's own directory-mode compile (see ARCHITECTURE.md's "Multi-file
    // projects"). Unlike `source(file)`, declaration order stops mattering entirely between
    // files in the same `sourceDir` -- every top-level name in the directory shares one flat
    // namespace, so a struct in one file references a struct in another directly, no
    // `extern class Foo = "already.compiled.Foo" { ... }` bridging needed. This is what
    // eliminates the exact hand-copied-signature duplication `IDEAS.md`'s "Directory-mode
    // multi-file compilation" and "Macros" entries were written against.
    fun sourceDir(path: Any) {
        sourceDirs.add(path)
    }
}

// **Fixed 2026-09-11 -- the fourth real break found scaffolding Marshmallow.** `stdlib_topic_
// path` (`Driver.hotc`) resolves every stdlib topic via a path relative to `SelfhostCLI`'s own
// PROCESS WORKING DIRECTORY (`"stdlib/" + topic + ".hotc"`), with no other fallback -- and this
// fires even for a program with ZERO `use` lines, since the "no `use` at all" default still
// injects the original five topics. The compiler jar now bundles those `.hotc` SOURCE files
// (see the root `build.gradle.kts`'s own `jar` task fix), but bundled-in-a-jar and "present as a
// real file next to the compile's own `workingDir`" are two different things -- this extracts
// every `stdlib/*.hotc` entry out of the resolved compiler classpath into `destDir` (== `ext.
// outputDir`, the same directory every `hcCompile*` task's own `workingDir` is already set to)
// before the compiler ever runs. Idempotent and cheap enough to just always do, rather than try
// to detect whether it's already been done for this particular `destDir`.
private fun extractStdlibResources(classpath: FileCollection, destDir: File) {
    for (file in classpath.files) {
        if (!file.isFile || !file.name.endsWith(".jar")) continue
        ZipFile(file).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory || !entry.name.startsWith("stdlib/")) continue
                val outFile = File(destDir, entry.name)
                outFile.parentFile.mkdirs()
                zip.getInputStream(entry).use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }
}

class HotChocolatePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val ext = project.extensions.create("hotChocolate", HotChocolateExtension::class.java, project)

        project.afterEvaluate {
            if (ext.sources.isEmpty() && ext.sourceDirs.isEmpty()) return@afterEvaluate
            val home = ext.compilerHome
            val ver = ext.version
            if (home == null && ver == null) {
                throw GradleException(
                    "hotChocolate { } needs either 'version = \"v0.1.5\"' (resolves the " +
                            "published compiler from JitPack -- see HotChocolate's " +
                            "ARCHITECTURE.md, 'Published on JitPack') or 'compilerHome = " +
                            "file(...)' (a local ./gradlew installDist checkout, for working " +
                            "on the compiler itself)"
                )
            }
            // `compilerHome` (a local installDist) always wins when both are set -- an explicit
            // local override beats a resolved artifact, the same "local dev takes priority"
            // reasoning `compilerHome` alone already had. Otherwise resolve the compiler as a
            // real dependency (transitively pulling in whatever it itself depends on, e.g. ASM --
            // no need to hand-enumerate jars the way the `compilerHome`/`lib/*.jar` path does)
            // instead of requiring a local `./gradlew installDist` against a sibling checkout --
            // this is what lets a consuming project pin a specific compiler version and build
            // reproducibly on a machine (or CI runner) that's never touched the HotChocolate
            // repo at all.
            val compilerClasspath: FileCollection = if (home != null) {
                project.fileTree(File(home, "lib")) { it.include("*.jar") }
            } else {
                // The compiler jar itself resolves from JitPack, but its own transitive
                // dependencies (Kotlin stdlib, ASM) are ordinary Maven Central artifacts --
                // JitPack doesn't mirror those, so resolution fails on them specifically (not on
                // the compiler jar itself) for any project that doesn't already declare
                // `mavenCentral()` in its own `repositories { }`. Virtually every real project
                // already has it (Forge's own tooling adds it by default), but a genuinely bare
                // project shouldn't need to know that -- add both here rather than depend on it.
                project.repositories.maven { it.setUrl(JITPACK_URL) }
                project.repositories.mavenCentral()
                val dependency = project.dependencies.create("$COMPILER_GROUP:$COMPILER_ARTIFACT:$ver")
                project.configurations.detachedConfiguration(dependency)
            }
            // Only meaningful in `compilerHome` mode: silently skip rather than fail when the
            // local checkout hasn't been built yet. `version` mode has no equivalent skip --
            // Gradle's own dependency resolution already fails clearly (bad coordinate, no
            // network, ...) without one, and pretending a resolution failure is "nothing to do
            // here" would just turn a real error into a confusing silent no-op.
            val shouldRun: () -> Boolean = if (home != null) { { home.isDirectory } } else { { true } }

            var previousTaskName: String? = null
            val fileCompileTaskNames = ext.sources.map { src ->
                val srcFile = project.file(src)
                val taskName = "hcCompile" + srcFile.nameWithoutExtension.replaceFirstChar { it.uppercase() }
                val dependsOnName = previousTaskName
                project.tasks.register(taskName, JavaExec::class.java) { t ->
                    t.group = "hot chocolate"
                    t.description = "Compiles ${srcFile.name} with the Hot Chocolate compiler."
                    t.onlyIf { shouldRun() }
                    if (dependsOnName != null) t.dependsOn(dependsOnName)
                    t.inputs.file(srcFile)
                    t.outputs.dir(ext.outputDir)
                    t.classpath = compilerClasspath
                    t.mainClass.set("SelfhostCLI")
                    // `workingDir` (not an "output directory" argument -- there isn't one, see
                    // this file's own header) is what makes every class this compile emits land
                    // under `ext.outputDir`; the source path has to be ABSOLUTE since the process
                    // no longer runs from the project's own directory.
                    t.args = listOf(srcFile.absolutePath)
                    t.workingDir = ext.outputDir
                    t.doFirst { ext.outputDir.mkdirs(); extractStdlibResources(compilerClasspath, ext.outputDir) }
                }
                previousTaskName = taskName
                taskName
            }
            // Directory-mode sources compile after every individual `source(file)` (if any --
            // most projects will use only one form or the other, but nothing stops mixing them):
            // one task per directory, each a single `SelfhostCLI <dir>` invocation (this file's
            // own `workingDir` set to `ext.outputDir`) covering every `.hc` file inside at once,
            // chained after whatever came before the same way individual sources already are.
            val dirCompileTaskNames = ext.sourceDirs.map { src ->
                val dir = project.file(src)
                val taskName = "hcCompile" + dir.name.replaceFirstChar { it.uppercase() }
                val dependsOnName = previousTaskName
                project.tasks.register(taskName, JavaExec::class.java) { t ->
                    t.group = "hot chocolate"
                    t.description = "Compiles every .hc file in ${dir.name}/ with the Hot Chocolate compiler."
                    t.onlyIf { shouldRun() }
                    if (dependsOnName != null) t.dependsOn(dependsOnName)
                    t.inputs.dir(dir)
                    t.outputs.dir(ext.outputDir)
                    t.classpath = compilerClasspath
                    t.mainClass.set("SelfhostCLI")
                    t.args = listOf(dir.absolutePath)
                    t.workingDir = ext.outputDir
                    t.doFirst { ext.outputDir.mkdirs(); extractStdlibResources(compilerClasspath, ext.outputDir) }
                }
                previousTaskName = taskName
                taskName
            }
            val lastCompileTask = (fileCompileTaskNames + dirCompileTaskNames).last()

            project.plugins.withId("java") {
                project.tasks.named("compileJava") { it.dependsOn(lastCompileTask) }
                val javaExt = project.extensions.getByType(JavaPluginExtension::class.java)
                val mainSourceSet = javaExt.sourceSets.getByName("main")
                // **Fixed 2026-09-11 -- restored, now that it's real.** `run_cli` (`selfhost/
                // Driver.hotc`) grew a genuine `--classpath <paths>` flag, wired to `Checker.hotc`'s
                // own `verify_extern_signature` -- compile-time checking of a hand-written `extern
                // class`/`extern interface` signature against the real method it names, catching a
                // wrong return/param type as a compile error instead of a runtime `NoSuchMethodError`.
                // Captured before the `compileClasspath` reassignment right below appends `ext.
                // outputDir` itself -- passing the compiler its own not-yet-written output directory
                // back as a `--classpath` entry would be circular and pointless. `--classpath` is a
                // LEADING flag on the current CLI (stacks with `--target`/`--explain-schedule` the
                // same way, before the source path) -- resolved into a real path list in `doFirst`
                // (once every other project dependency is fully configured, not eagerly during
                // Gradle's configuration phase) and PREPENDED, not appended, to each task's own args.
                val realCompileClasspath = mainSourceSet.compileClasspath
                for (taskName in fileCompileTaskNames + dirCompileTaskNames) {
                    project.tasks.named(taskName, JavaExec::class.java) { t ->
                        t.doFirst {
                            val classpathString = realCompileClasspath.filter { it.exists() }.asPath
                            if (classpathString.isNotEmpty()) {
                                t.args = listOf("--classpath", classpathString) + (t.args ?: emptyList())
                            }
                        }
                    }
                }
                mainSourceSet.compileClasspath = mainSourceSet.compileClasspath
                    .plus(project.files(ext.outputDir).builtBy(lastCompileTask))
                project.tasks.named("processResources", ProcessResources::class.java) { t ->
                    t.dependsOn(lastCompileTask)
                    t.from(ext.outputDir)
                }
            }
        }
    }
}
