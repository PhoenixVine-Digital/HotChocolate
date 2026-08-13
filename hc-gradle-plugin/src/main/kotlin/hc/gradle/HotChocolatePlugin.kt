package hc.gradle

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.JavaExec
import org.gradle.language.jvm.tasks.ProcessResources
import java.io.File

// `hotChocolate { compilerHome = file(...); source(file("hc/foo.hc")); source(file("hc/bar.hc")) }`
// -- a real Gradle DSL extension, not a description of one. Replaces the hand-copied
// `tasks.register('hcCompile', JavaExec) { ... }` block that used to get pasted into every
// consuming project's build.gradle (see kubejs-aisle-tool's build history): same underlying
// mechanism (shell out to the compiler's installed distribution via JavaExec, exactly what
// `hc build <file> <outDir>` already does on the command line), just declared once here and
// reused, instead of copy-pasted per project.
open class HotChocolateExtension(private val project: Project) {
    // Root of the Hot Chocolate compiler's installed distribution (contains `lib/*.jar` with
    // `hc.MainKt` as the application's main class) -- e.g. `./gradlew installDist` run against
    // a sibling HotChocolate checkout. Required.
    var compilerHome: File? = null

    // Where every declared source's compiled `.class` files land, and what gets added to the
    // consuming project's `compileJava` classpath / bundled as a resource. Defaults to
    // `build/hc-classes`, matching the manual convention this plugin replaces.
    var outputDir: File = project.layout.buildDirectory.dir("hc-classes").get().asFile

    internal val sources = mutableListOf<Any>()

    // `source(file("hc/foo.hc"))` -- accepts anything `Project.file(...)` accepts (a `File`, a
    // `String` path, ...). Declaration order matters: each source is compiled after the one
    // before it, so a later file's `extern class` can reach a real class an earlier one
    // produced (the same ordering the manual `hcCompileCopyTool.dependsOn(hcCompile)` chain
    // enforced by hand).
    fun source(path: Any) {
        sources.add(path)
    }
}

class HotChocolatePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val ext = project.extensions.create("hotChocolate", HotChocolateExtension::class.java, project)

        project.afterEvaluate {
            if (ext.sources.isEmpty()) return@afterEvaluate
            val home = ext.compilerHome
                ?: throw GradleException("hotChocolate { compilerHome = file(...) } is required (the Hot Chocolate compiler's installed distribution -- see HotChocolate's README, 'Try it')")

            var previousTaskName: String? = null
            val compileTaskNames = ext.sources.map { src ->
                val srcFile = project.file(src)
                val taskName = "hcCompile" + srcFile.nameWithoutExtension.replaceFirstChar { it.uppercase() }
                val dependsOnName = previousTaskName
                project.tasks.register(taskName, JavaExec::class.java) { t ->
                    t.group = "hot chocolate"
                    t.description = "Compiles ${srcFile.name} with the Hot Chocolate compiler."
                    t.onlyIf { home.isDirectory }
                    if (dependsOnName != null) t.dependsOn(dependsOnName)
                    t.inputs.file(srcFile)
                    t.outputs.dir(ext.outputDir)
                    t.classpath = project.fileTree(File(home, "lib")) { it.include("*.jar") }
                    t.mainClass.set("hc.MainKt")
                    t.args = listOf("build", srcFile.path, ext.outputDir.path)
                    t.doFirst { ext.outputDir.mkdirs() }
                }
                previousTaskName = taskName
                taskName
            }
            val lastCompileTask = compileTaskNames.last()

            project.plugins.withId("java") {
                project.tasks.named("compileJava") { it.dependsOn(lastCompileTask) }
                val javaExt = project.extensions.getByType(JavaPluginExtension::class.java)
                val mainSourceSet = javaExt.sourceSets.getByName("main")
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
