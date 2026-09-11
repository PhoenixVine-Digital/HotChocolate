package hc.intellij

import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.io.FileUtil
import com.intellij.util.ui.FormBuilder
import java.io.File
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JTextField

// "New HotChocolate Project" -- scaffolds a real, standalone Gradle project wired up with
// `hc-gradle-plugin`'s own documented "zero-clone" setup (README's own "Zero-clone setup:
// resolving the plugin itself from JitPack, no `includeBuild`" section), so the result works for
// anyone, not just a machine that already has a HotChocolate checkout to `includeBuild` against.
//
// Deliberately a plain `AnAction` under the File menu, NOT a `GeneratorNewProjectWizard`/
// `ModuleBuilder`-based Welcome Screen entry -- this plugin's own `build.gradle.kts` already
// states the reasoning that applies here too: internal wizard-framework APIs have gone through
// several incompatible rewrites across recent platform versions, and nothing about scaffolding a
// few text files and opening the result needs that machinery at all. Also does not generate a
// Gradle wrapper (a binary jar this plugin has no clean way to embed/verify) -- `ProjectUtil.
// openOrImport` on a wrapper-less Gradle project still works via the IDE's own managed Gradle
// distribution, and the user can run `gradle wrapper` themselves once opened if they want one
// committed.
class HCNewProjectAction : AnAction("New HotChocolate Project...", "Scaffold a new HotChocolate Gradle project", null), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val dialog = HCNewProjectDialog()
        if (!dialog.showAndGet()) return

        val root = File(dialog.location, dialog.projectName)
        if (root.exists() && (root.listFiles()?.isNotEmpty() == true)) {
            Messages.showErrorDialog("'${root.path}' already exists and is not empty.", "Cannot Create Project")
            return
        }

        val hcDir = File(root, "src/main/hc")
        hcDir.mkdirs()
        File(root, "settings.gradle.kts").writeText(settingsGradleContent(dialog.projectName))
        File(root, "build.gradle.kts").writeText(buildGradleContent(dialog.compilerVersion))
        File(hcDir, "Main.hotc").writeText(starterHotcContent())

        ProjectUtil.openOrImport(root.toPath())
    }
}

internal fun settingsGradleContent(projectName: String): String = """
    pluginManagement {
        repositories {
            maven { url = uri("https://jitpack.io") }
            gradlePluginPortal()
        }
        resolutionStrategy {
            eachPlugin {
                if (requested.id.id == "hc") {
                    useModule("com.github.PhoenixVine-Digital.HotChocolate:hc-gradle-plugin:${'$'}{requested.version}")
                }
            }
        }
    }

    rootProject.name = "$projectName"
""".trimIndent() + "\n"

internal fun buildGradleContent(version: String): String = """
    plugins {
        id("hc") version "$version"
    }

    hotChocolate {
        version = "$version"
        sourceDir("src/main/hc")
    }
""".trimIndent() + "\n"

internal fun starterHotcContent(): String = "fn main() {\n    print(\"Hello from HotChocolate!\");\n}\n"

private class HCNewProjectDialog : DialogWrapper(true) {
    private val nameField = JTextField("untitled-hc-project")
    private val locationField = TextFieldWithBrowseButton().apply {
        text = File(FileUtil.expandUserHome("~"), "IdeaProjects").path
        addBrowseFolderListener("Project Location", null, null, FileChooserDescriptorFactory.createSingleFolderDescriptor())
    }
    private val versionField = JTextField("v0.1.7")

    val projectName: String get() = nameField.text.trim()
    val location: String get() = locationField.text.trim()
    val compilerVersion: String get() = versionField.text.trim()

    init {
        title = "New HotChocolate Project"
        init()
    }

    override fun createCenterPanel(): JComponent =
        FormBuilder.createFormBuilder()
            .addLabeledComponent(JLabel("Name:"), nameField)
            .addLabeledComponent(JLabel("Location:"), locationField)
            .addLabeledComponent(JLabel("Compiler version:"), versionField)
            .addComponentToRightColumn(JLabel("A published HotChocolate tag, e.g. v0.1.7 -- see the README's 'Published on JitPack' section."))
            .panel
}
