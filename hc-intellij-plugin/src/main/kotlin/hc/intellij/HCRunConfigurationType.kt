package hc.intellij

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import javax.swing.Icon

class HCRunConfigurationType : ConfigurationType {
    val factory: ConfigurationFactory = HCRunConfigurationFactory(this)

    override fun getDisplayName(): String = "HotChocolate"
    override fun getConfigurationTypeDescription(): String = "Compiles and runs a HotChocolate (.hotc/.hc) file"
    override fun getIcon(): Icon = AllIcons.RunConfigurations.Application
    override fun getId(): String = "HCRunConfigurationType"
    override fun getConfigurationFactories(): Array<ConfigurationFactory> = arrayOf(factory)
}

class HCRunConfigurationFactory(type: ConfigurationType) : ConfigurationFactory(type) {
    override fun createTemplateConfiguration(project: Project): RunConfiguration =
        HCRunConfiguration(project, this, "HotChocolate")

    override fun getId(): String = "HCRunConfigurationFactory"
}
