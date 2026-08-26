package hc.intellij

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.openapi.util.Ref
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement

// Powers the editor gutter icon / right-click "Run" on a `.hotc`/`.hc` file -- checked by
// EXTENSION directly rather than `HCFileType`/`HCLanguage`, since real example files in this repo
// use both `.hotc` and `.hc` (only `.hotc` is registered as this plugin's own language file type;
// see `HCFileType.kt`'s own `extensions="hotc"` in `plugin.xml`) and the compiler CLI itself
// (`hc.MainKt`) treats both identically.
class HCRunConfigurationProducer : LazyRunConfigurationProducer<HCRunConfiguration>() {
    override fun getConfigurationFactory(): ConfigurationFactory = HCRunConfigurationType().factory

    override fun setupConfigurationFromContext(
        configuration: HCRunConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>,
    ): Boolean {
        val file = hotChocolateFileFrom(context) ?: return false
        configuration.filePath = file.path
        configuration.name = file.name
        return true
    }

    override fun isConfigurationFromContext(configuration: HCRunConfiguration, context: ConfigurationContext): Boolean {
        val file = hotChocolateFileFrom(context) ?: return false
        return configuration.filePath == file.path
    }
}

private fun hotChocolateFileFrom(context: ConfigurationContext): VirtualFile? {
    val file = context.psiLocation?.containingFile?.virtualFile ?: return null
    return file.takeIf { it.extension == "hotc" || it.extension == "hc" }
}
