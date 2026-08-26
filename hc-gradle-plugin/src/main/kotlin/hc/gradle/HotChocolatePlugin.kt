package hc.gradle

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.JavaExec
import org.gradle.language.jvm.tasks.ProcessResources
import java.io.File

// JitPack's coordinates for this repo's own root module (the compiler itself -- `hc.MainKt`
// lives there, not in this plugin module). See the README's "Published on JitPack" section for
// how a tag turns into a resolvable version here.
private const val COMPILER_GROUP = "com.github.P-H-O-E-N-I-X-PackForge"
private const val COMPILER_ARTIFACT = "HotChocolate"
private const val JITPACK_URL = "https://jitpack.io"

// `hotChocolate { compilerHome = file(...); source(file("hc/foo.hc")); source(file("hc/bar.hc")) }`
// -- a real Gradle DSL extension, not a description of one. Replaces the hand-copied
// `tasks.register('hcCompile', JavaExec) { ... }` block that used to get pasted into every
// consuming project's build.gradle (see kubejs-aisle-tool's build history): same underlying
// mechanism (shell out to the compiler via JavaExec, exactly what `hc build <file> <outDir>`
// already does on the command line), just declared once here and reused, instead of copy-pasted
// per project.
open class HotChocolateExtension(private val project: Project) {
    // Root of the Hot Chocolate compiler's installed distribution (contains `lib/*.jar` with
    // `hc.MainKt` as the application's main class) -- e.g. `./gradlew installDist` run against a
    // sibling HotChocolate checkout. The local-dev escape hatch: set this when you're actually
    // working on the compiler itself and need a change to show up without waiting on a published
    // tag. Most consuming projects should use `version` (below) instead -- if both are set,
    // `compilerHome` wins (an explicit local override always beats a resolved artifact).
    var compilerHome: File? = null

    // `version = "v0.1.5"` -- resolves the compiler as a real, already-published JitPack
    // dependency (`com.github.P-H-O-E-N-I-X-PackForge:HotChocolate:v0.1.5`, see the README's
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
    // plugin side of the CLI's own `hc build <dir> <outDir>` (see the README's "Multi-file
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
                            "published compiler from JitPack -- see HotChocolate's README, " +
                            "'Published on JitPack') or 'compilerHome = file(...)' (a local " +
                            "./gradlew installDist checkout, for working on the compiler itself)"
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
                    t.mainClass.set("hc.MainKt")
                    t.args = listOf("build", srcFile.path, ext.outputDir.path)
                    t.doFirst { ext.outputDir.mkdirs() }
                }
                previousTaskName = taskName
                taskName
            }
            // Directory-mode sources compile after every individual `source(file)` (if any --
            // most projects will use only one form or the other, but nothing stops mixing them):
            // one task per directory, each a single `hc build <dir> <outDir>` invocation covering
            // every `.hc` file inside at once, chained after whatever came before the same way
            // individual sources already are.
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
                    t.mainClass.set("hc.MainKt")
                    t.args = listOf("build", dir.path, ext.outputDir.path)
                    t.doFirst { ext.outputDir.mkdirs() }
                }
                previousTaskName = taskName
                taskName
            }
            val lastCompileTask = (fileCompileTaskNames + dirCompileTaskNames).last()

            project.plugins.withId("java") {
                project.tasks.named("compileJava") { it.dependsOn(lastCompileTask) }
                val javaExt = project.extensions.getByType(JavaPluginExtension::class.java)
                val mainSourceSet = javaExt.sourceSets.getByName("main")
                // Captured before the `compileClasspath` reassignment below appends `ext
                // .outputDir` itself -- passing the compiler its own not-yet-written output
                // directory back as a `--classpath` entry would be circular and pointless (there's
                // nothing in it to reflect against yet, and it invites a stale/self-referential
                // read on a rebuild).
                val realCompileClasspath = mainSourceSet.compileClasspath
                for (taskName in fileCompileTaskNames + dirCompileTaskNames) {
                    project.tasks.named(taskName, JavaExec::class.java) { t ->
                        // `--classpath` is what backs the compiler's `verifyExternSignatures`
                        // (compile-time checking of a hand-written `extern class`/`extern
                        // interface` signature against the real method it names -- catches a
                        // wrong return type or param type as a compile error instead of a runtime
                        // `NoSuchMethodError`) and lazy/`use { }` extern reflection generally --
                        // without this, every extern declaration in the project silently degrades
                        // to "trust the declaration," exactly as it did before this classpath was
                        // ever wired through. Appended in `doFirst` rather than set directly above
                        // on `t.args` at task-registration time, so resolving this Configuration
                        // into a real path list happens at task EXECUTION time -- once every other
                        // project dependency is fully configured -- not eagerly during Gradle's
                        // configuration phase, which risks resolving it before it's actually ready.
                        //
                        // Known limitation, not fixed here: a large Forge project's full
                        // `compileClasspath` can be a lot of jar paths, and this is passed as a
                        // literal OS process argument (`JavaExec`, not a manifest-based
                        // classpath jar) -- workable in practice for a real Forge mod's dependency
                        // count, but a project with an unusually large dependency graph could
                        // theoretically approach Windows' ~32K-character command-line limit. Worth
                        // revisiting (a `Class-Path`-manifest wrapper jar, the standard Java-world
                        // fix for this) only if it actually bites a real project.
                        t.doFirst {
                            val classpathString = realCompileClasspath.filter { it.exists() }.asPath
                            t.args = (t.args ?: emptyList()) + "--classpath" + classpathString
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
