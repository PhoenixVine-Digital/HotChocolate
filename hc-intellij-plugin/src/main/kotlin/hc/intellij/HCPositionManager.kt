package hc.intellij

import com.intellij.debugger.NoDataException
import com.intellij.debugger.PositionManager
import com.intellij.debugger.PositionManagerFactory
import com.intellij.debugger.SourcePosition
import com.intellij.debugger.engine.DebugProcess
import com.intellij.debugger.requests.ClassPrepareRequestor
import com.intellij.openapi.fileTypes.FileType
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.sun.jdi.AbsentInformationException
import com.sun.jdi.Location
import com.sun.jdi.ReferenceType
import com.sun.jdi.request.ClassPrepareRequest

// The piece that makes Debug real rather than a bytecode-only stub: maps a JVM `Location`
// (class + line, from a stack frame or a hit breakpoint) back to a `.hotc` `PsiFile` + line, and
// the reverse (a breakpoint set on a `.hotc` line -> the JDI locations to actually break at).
// Both directions key off `ReferenceType.sourceName()`/`Location.sourceName()` -- exactly the
// string `CodeGen.kt`'s own `cw.visitSource(...)` wrote into the class file (see that file's own
// header on why that attribute is trustworthy here, not just decorative) -- rather than any
// separate bookkeeping this plugin would otherwise have to invent and keep in sync itself.
class HCPositionManager(private val process: DebugProcess) : PositionManager {
    private val project = process.project

    override fun getAcceptedFileTypes(): Set<FileType> = setOf(HCFileType)

    override fun getSourcePosition(location: Location?): SourcePosition? {
        if (location == null) throw NoDataException.INSTANCE
        val sourceName = try {
            location.sourceName()
        } catch (e: AbsentInformationException) {
            throw NoDataException.INSTANCE
        }
        val psiFile = findHcFile(sourceName) ?: throw NoDataException.INSTANCE
        // JDI line numbers are 1-based (matching `Main.kt`'s own lexer/parser line counting, the
        // same numbers `CodeGen.kt`'s `visitLineNumber` calls were seeded with); `SourcePosition`
        // is 0-based (matching `Document`'s own line numbering).
        return SourcePosition.createFromLine(psiFile, location.lineNumber() - 1)
    }

    override fun getAllClasses(position: SourcePosition): List<ReferenceType> {
        val fileName = position.file.name
        if (!isHcFileName(fileName)) throw NoDataException.INSTANCE
        return process.virtualMachineProxy.allClasses().filter { safeSourceName(it) == fileName }
    }

    override fun locationsOfLine(type: ReferenceType, position: SourcePosition): List<Location> {
        if (!isHcFileName(position.file.name)) throw NoDataException.INSTANCE
        return try {
            type.locationsOfLine(position.line + 1)
        } catch (e: AbsentInformationException) {
            emptyList()
        }
    }

    // There's no way to know ahead of time which JVM class name(s) a given `.hotc` file's
    // declarations end up compiled into (one file can spread across several classes -- a struct's
    // own class, a free-function holder class, ...; see `CodeGen.generate()`'s own per-unit
    // class-splitting), so this can't narrow the underlying JDI prepare request by name the way a
    // language with a fixed file-to-class naming convention could. A wildcard request plus
    // filtering by `sourceName` in `getAllClasses`/`getSourcePosition` above is the same tradeoff
    // real dynamic-source-mapping debugger integrations make elsewhere: some extra
    // `classPrepare` event traffic for classes that turn out not to match, never a missed one.
    override fun createPrepareRequest(requestor: ClassPrepareRequestor, position: SourcePosition): ClassPrepareRequest {
        if (!isHcFileName(position.file.name)) throw NoDataException.INSTANCE
        return process.requestsManager.createClassPrepareRequest(requestor, "*") ?: throw NoDataException.INSTANCE
    }

    private fun findHcFile(name: String): PsiFile? {
        val vFile = FilenameIndex.getVirtualFilesByName(project, name, GlobalSearchScope.allScope(project)).firstOrNull() ?: return null
        return PsiManager.getInstance(project).findFile(vFile)
    }
}

private fun isHcFileName(name: String): Boolean = name.endsWith(".hotc") || name.endsWith(".hc")

private fun safeSourceName(type: ReferenceType): String? = try {
    type.sourceName()
} catch (e: AbsentInformationException) {
    null
}

class HCPositionManagerFactory : PositionManagerFactory() {
    override fun createPositionManager(process: DebugProcess): PositionManager = HCPositionManager(process)
}
