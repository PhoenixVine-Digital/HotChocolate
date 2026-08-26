package hc.intellij

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RemoteConnection
import com.intellij.execution.configurations.RemoteState
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.util.SystemInfo
import java.io.File
import java.net.ServerSocket

// The Debug counterpart to `HCCommandLineState`: same "shell out to the project's own `./gradlew
// run --args=...`" launch, plus `--debug=$port` (see `Main.kt`'s own `extractDebugFlag`/
// `debugArgs`, which adds a suspended JDWP listener to the actual `java` process the compiler
// spawns internally) and a `RemoteConnection` describing that same port. Extending
// `CommandLineState` (rather than plain `RunProfileState`) reuses its own `execute()` for the
// process/console half of the job; implementing `RemoteState` on top is what tells
// `GenericDebuggerRunner` to ALSO attach a real JVM debugger to `getRemoteConnection()` once the
// process is running -- the platform's own documented way for a single state object to serve both
// roles for a "launch AND attach in one click" Debug configuration, rather than requiring the
// debuggee to already be running (the usual assumption behind a plain "Remote JVM Debug"
// configuration).
class HCDebugState(environment: ExecutionEnvironment, private val filePath: String) : CommandLineState(environment), RemoteState {
    private val port: Int = ServerSocket(0).use { it.localPort }

    override fun startProcess(): ProcessHandler {
        val file = File(filePath)
        if (!file.isFile) throw ExecutionException("HotChocolate file not found: $filePath")
        val gradleRoot = findGradleRoot(file.parentFile)
            ?: throw ExecutionException(
                "Could not find a Gradle project (gradlew/gradlew.bat) above '$filePath' -- " +
                    "debugging a .hotc file needs a HotChocolate compiler checkout whose 'run' task is wired to hc.MainKt.",
            )
        val gradlew = File(gradleRoot, if (SystemInfo.isWindows) "gradlew.bat" else "gradlew")
        val commandLine = GeneralCommandLine(gradlew.path, "run", "--args=run ${file.absolutePath} --debug=$port", "--console=plain")
            .withWorkDirectory(gradleRoot)
        val handler = KillableColoredProcessHandler(commandLine)
        com.intellij.execution.process.ProcessTerminatedListener.attach(handler)
        return handler
    }

    // `useSockets=true`, `serverMode=false` -- the DEBUGGEE is the one listening (`server=y` in
    // the jdwp agent string `Main.kt` adds), so the IDE's debugger connects OUT to it as a client,
    // not the other way around. `suspend=y` on the debuggee side is what makes this race-free:
    // the target JVM blocks until this very connection succeeds, so there's no window where the
    // program could run past a breakpoint before the debugger has attached.
    override fun getRemoteConnection(): RemoteConnection = RemoteConnection(true, "localhost", port.toString(), false)
}
