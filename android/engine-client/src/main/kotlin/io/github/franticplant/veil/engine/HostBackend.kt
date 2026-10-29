package io.github.franticplant.veil.engine

import android.content.Context
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The engine as a native process of its own: the `veil-engine-host` binary, spoken to over its
 * stdin and stdout with veil-wire framing ([StreamPipe]).
 *
 * The binary ships as `libveil_engine_host.so` so Android installs it in the app's native library
 * directory, the one place an app may execute files from. No JVM runs in that process: on
 * GrapheneOS, where apps are not forked from the zygote, a Java process carries a private copy of
 * the framework boot image (~24 MB); this one carries only the engine. It is also the same binary
 * the Microdroid payload runs, so moving the engine into a VM changes the transport, not the
 * engine.
 *
 * Its stderr goes to `host.stderr` in [dataDir], replaced at each start, for crash diagnostics.
 */
class HostBackend(
    context: Context,
    private val engineVersion: String,
    private val dataDir: File,
) : EngineBackend {
    override val kind = "native"

    private val binary = File(context.applicationInfo.nativeLibraryDir, BINARY)

    override suspend fun connect(clientName: String, scope: CoroutineScope): EngineConnection {
        val process =
            withContext(Dispatchers.IO) {
                dataDir.mkdirs()
                check(binary.canExecute()) { "the engine binary is missing: $binary" }
                ProcessBuilder(
                        binary.path,
                        "--version",
                        engineVersion,
                        "--data-dir",
                        dataDir.path,
                        "--memory-limit-mb",
                        "0",
                        "--nofile",
                        "256",
                        "--stdio",
                    )
                    .redirectError(File(dataDir, "host.stderr"))
                    .start()
            }
        val pipe = StreamPipe(process.inputStream, process.outputStream) { process.destroy() }
        return EngineConnection.open(pipe, clientName, scope)
    }

    companion object {
        const val BINARY = "libveil_engine_host.so"
    }
}
