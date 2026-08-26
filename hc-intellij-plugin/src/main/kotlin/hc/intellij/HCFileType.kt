package hc.intellij

import com.intellij.openapi.fileTypes.LanguageFileType
import javax.swing.Icon

object HCFileType : LanguageFileType(HCLanguage) {
    override fun getName(): String = "HotChocolate"
    override fun getDescription(): String = "HotChocolate source file"
    override fun getDefaultExtension(): String = "hotc"
    override fun getIcon(): Icon? = null
}
