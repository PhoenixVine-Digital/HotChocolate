package hc.gradle

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.JavaExec
import org.gradle.language.jvm.tasks.ProcessResources
import java.io.File

private const val COMPILER_GROUP = "com.github.P-H-O-E-N-I-X-PackForge"
private const val COMPILER_ARTIFACT = "HotChocolate"
private const val JITPACK_URL = "https://jitpack.io"


open class HotChocolateExtension(private val project: Project) {

    var compilerHome: File? = null


    var version: String? = null

    var outputDir: File = project.layout.buildDirectory.dir("hc-classes").get().asFile

    internal val sources = mutableListOf<Any>()
    internal val sourceDirs = mutableListOf<Any>()


    fun source(path: Any) {
        sources.add(path)
    }


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

            val compilerClasspath: FileCollection = if (home != null) {
                project.fileTree(File(home, "lib")) { it.include("*.jar") }
            } else {

                project.repositories.maven { it.setUrl(JITPACK_URL) }
                project.repositories.mavenCentral()
                val dependency = project.dependencies.create("$COMPILER_GROUP:$COMPILER_ARTIFACT:$ver")
                project.configurations.detachedConfiguration(dependency)
            }

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
