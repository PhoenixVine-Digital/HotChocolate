package hc.intellij

import com.intellij.ide.actions.CreateFileFromTemplateAction
import com.intellij.ide.actions.CreateFileFromTemplateDialog
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile

// "New > HotChocolate File" in the project view -- deliberately creates a plain EMPTY file rather
// than wiring up the platform's `FileTemplateManager`/internal-template machinery: this language
// has no fixed "every file starts with X" convention (a `module` declaration is optional, and
// real multi-file examples in this repo don't all open with one), so a template with guessed
// boilerplate would be more presumptuous than useful. `PsiDirectory.createFile` is the same
// underlying real, standard PSI API every richer template-based action ultimately calls too.
class HCCreateFileAction :
    CreateFileFromTemplateAction("HotChocolate File", "Creates a new HotChocolate source file", HCFileType.icon),
    DumbAware {
    override fun buildDialog(project: Project, directory: PsiDirectory, builder: CreateFileFromTemplateDialog.Builder) {
        builder.setTitle("New HotChocolate File").addKind("Empty file", HCFileType.icon, "HotChocolate File")
    }

    override fun getActionName(directory: PsiDirectory, newName: String, templateName: String): String =
        "Create HotChocolate File '$newName'"

    public override fun createFile(name: String?, templateName: String?, dir: PsiDirectory?): PsiFile? {
        if (name == null || dir == null) return null
        val fileName = if (name.endsWith(".hotc") || name.endsWith(".hc")) name else "$name.hotc"
        return dir.createFile(fileName)
    }
}
