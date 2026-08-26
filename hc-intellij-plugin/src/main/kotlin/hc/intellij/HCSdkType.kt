package hc.intellij

import com.intellij.icons.AllIcons
import com.intellij.openapi.projectRoots.AdditionalDataConfigurable
import com.intellij.openapi.projectRoots.SdkModel
import com.intellij.openapi.projectRoots.SdkModificator
import com.intellij.openapi.projectRoots.SdkAdditionalData
import com.intellij.openapi.projectRoots.SdkType
import org.jdom.Element
import java.io.File
import javax.swing.Icon

// Project Structure > SDKs support for a local HotChocolate compiler install -- specifically the
// `./gradlew installDist` layout `HotChocolateExtension.compilerHome` (`hc-gradle-plugin`'s own
// local-dev escape hatch, see that class's own doc comment) already expects: a directory
// containing `lib/hotchocolate.jar` (confirmed against a REAL `installDist` run against this
// repo's own root module -- the jar name has no version suffix at all, unlike its dependency jars
// alongside it, e.g. `asm-9.7.jar`; there's no `Implementation-Version` manifest entry either, so
// `getVersionString` honestly returns null rather than fabricating one). A project resolving the
// compiler via `version = "..."` (the JitPack-published, zero-clone path most consuming projects
// use -- see `HCNewProjectAction`'s own scaffold) has no local directory to point an SDK at at
// all; this type only makes sense for -- and is only useful to -- someone working on the compiler
// itself via a local checkout, the same audience `compilerHome` itself is for.
class HCSdkType : SdkType("HotChocolate Compiler") {
    override fun suggestHomePath(): String? = null

    override fun isValidSdkHome(path: String): Boolean = compilerJar(path) != null

    override fun suggestSdkName(currentSdkName: String?, sdkHome: String): String = "HotChocolate Compiler"

    override fun getVersionString(sdkHome: String): String? = null

    override fun createAdditionalDataConfigurable(sdkModel: SdkModel, sdkModificator: SdkModificator): AdditionalDataConfigurable? = null

    override fun saveAdditionalData(additionalData: SdkAdditionalData, element: Element) {}

    override fun getPresentableName(): String = "HotChocolate Compiler"

    override fun getIcon(): Icon = AllIcons.Nodes.PpLib
}

internal fun compilerJar(sdkHome: String): File? {
    val libDir = File(sdkHome, "lib")
    if (!libDir.isDirectory) return null
    return File(libDir, "hotchocolate.jar").takeIf { it.isFile }
}
