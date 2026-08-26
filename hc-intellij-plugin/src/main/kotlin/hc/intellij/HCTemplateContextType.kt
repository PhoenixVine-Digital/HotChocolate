package hc.intellij

import com.intellij.codeInsight.template.TemplateActionContext
import com.intellij.codeInsight.template.TemplateContextType

// Live templates -- `HOTCHOCOLATE` is the context id every `<option name="HOTCHOCOLATE" .../>` in
// `liveTemplates/HotChocolate.xml`'s own `<context>` blocks refers back to; without a registered
// `TemplateContextType` for that id, the platform has no way to know those templates should ever
// be offered while editing a `.hotc` file at all.
class HCTemplateContextType : TemplateContextType("HOTCHOCOLATE", "HotChocolate") {
    override fun isInContext(templateActionContext: TemplateActionContext): Boolean =
        templateActionContext.file.language == HCLanguage
}
