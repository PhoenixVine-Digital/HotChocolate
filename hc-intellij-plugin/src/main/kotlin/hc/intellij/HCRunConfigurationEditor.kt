package hc.intellij

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JLabel

class HCRunConfigurationEditor : SettingsEditor<HCRunConfiguration>() {
    private val fileField = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener("Select HotChocolate File", null, null, FileChooserDescriptorFactory.createSingleFileDescriptor())
    }

    override fun resetEditorFrom(configuration: HCRunConfiguration) {
        fileField.text = configuration.filePath
    }

    override fun applyEditorTo(configuration: HCRunConfiguration) {
        configuration.filePath = fileField.text
    }

    override fun createEditor(): JComponent =
        FormBuilder.createFormBuilder()
            .addLabeledComponent(JLabel("HotChocolate file:"), fileField)
            .panel
}
