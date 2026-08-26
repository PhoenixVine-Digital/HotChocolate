package hc.intellij

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.util.SystemInfo
import java.io.File

class HCCommandLineState(environment: ExecutionEnvironment, private val filePath: String) : CommandLineState(environment) {
    override fun startProcess(): ProcessHandler {
        val file = File(filePath)
        if (!file.isFile) throw ExecutionException("HotChocolate file not found: $filePath")
        val gradleRoot = findGradleRoot(file.parentFile)
            ?: throw ExecutionException(
                "Could not find a Gradle project (gradlew/gradlew.bat) above '$filePath' -- " +
                    "running a .hotc file needs a HotChocolate compiler checkout whose 'run' task is wired to hc.MainKt.",
            )
        val gradlew = File(gradleRoot, if (SystemInfo.isWindows) "gradlew.bat" else "gradlew")
        val commandLine = GeneralCommandLine(gradlew.path, "run", "--args=run ${file.absolutePath}", "--console=plain")
            .withWorkDirectory(gradleRoot)
        val handler = KillableColoredProcessHandler(commandLine)
        ProcessTerminatedListener.attach(handler)
        return handler
    }
}

// Walks up from the file's own directory looking for the nearest Gradle wrapper -- the natural
// "which project does this file belong to" answer, same convention any Gradle-aware tool uses
// (no separate HotChocolate-specific project marker exists, or is needed: `gradlew`/`gradlew.bat`
// IS the marker).
internal fun findGradleRoot(startDir: File?): File? {
    var dir = startDir
    while (dir != null) {
        if (File(dir, "gradlew.bat").isFile || File(dir, "gradlew").isFile) return dir
        dir = dir.parentFile
    }
    return null
}
