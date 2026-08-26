package hc.intellij

import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.LocatableConfigurationBase
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationException
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import org.jdom.Element
import java.io.File

// A HotChocolate Run configuration -- "run this .hotc/.hc file the same way `hc run <file>`
// does." Deliberately does NOT bundle or directly invoke the compiler (`hc.MainKt`) from inside
// this plugin process: the compiler is a SEPARATE Gradle module/repo with its own runtime
// classpath (Kotlin stdlib, ASM), which this plugin has no access to and no business duplicating.
// Instead, `HCCommandLineState` shells out to that project's OWN `./gradlew run --args="run
// <file>"` -- the exact same command a developer would type by hand, and the same one this
// repo's own `build.gradle.kts` already wires `standardInput` through for (so an interactive
// `read_line()`-using program still works when run this way). This only works for a `.hotc` file
// that lives inside (or below) a Gradle project whose `run` task is wired to `hc.MainKt` --
// realistically, the HotChocolate compiler's own repo -- not an arbitrary consuming project that
// only uses `hc-gradle-plugin`'s `build`-only tasks (those compile `.hotc` into a larger mod, and
// were never meant to be run standalone). A consuming project without that task will simply see a
// real, honest Gradle error in the Run console rather than a wrong/silent success.
class HCRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    LocatableConfigurationBase<RunConfigurationOptions>(project, factory, name) {

    var filePath: String = ""

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = HCRunConfigurationEditor()

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState =
        if (executor.id == DefaultDebugExecutor.EXECUTOR_ID) {
            HCDebugState(environment, filePath)
        } else {
            HCCommandLineState(environment, filePath)
        }

    override fun checkConfiguration() {
        if (filePath.isBlank()) throw RuntimeConfigurationException("No HotChocolate file specified")
        if (!File(filePath).isFile) throw RuntimeConfigurationException("File not found: $filePath")
    }

    override fun writeExternal(element: Element) {
        super.writeExternal(element)
        element.setAttribute(FILE_PATH_ATTR, filePath)
    }

    override fun readExternal(element: Element) {
        super.readExternal(element)
        element.getAttributeValue(FILE_PATH_ATTR)?.let { filePath = it }
    }
}

private const val FILE_PATH_ATTR = "hcFilePath"
