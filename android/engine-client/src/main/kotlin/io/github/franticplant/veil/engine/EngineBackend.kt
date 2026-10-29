package io.github.franticplant.veil.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Where the engine runs. The UI and session layers hold an [EngineBackend] and never learn whether
 * the engine is in this process, another process, or a Microdroid VM: every backend hands back the
 * same [EngineConnection], speaking the same protocol over some [MessagePipe].
 */
interface EngineBackend {
    /** A short name for what this backend is, e.g. "local", for diagnostics. */
    val kind: String

    /** Opens a connection and completes the Hello handshake. */
    suspend fun connect(clientName: String, scope: CoroutineScope): EngineConnection

    /**
     * Prepares the network path for a session before OpenSession. Native and local backends dial
     * directly and do nothing; the VM backend asks its host broker to open the guest's vsock side.
     */
    suspend fun prepare(host: String, port: Int) {}

    /** Optional host-side counters, for backends that have a separate supervising process. */
    suspend fun hostStats(): org.json.JSONObject? = null
}

/**
 * The engine in this app's own process, through JNI. [dataDir] is the engine's private state
 * (known_hosts); nothing else on the filesystem is handed to it.
 */
class LocalBackend(private val engineVersion: String, private val dataDir: java.io.File) :
    EngineBackend {
    override val kind = "local"

    override suspend fun connect(clientName: String, scope: CoroutineScope): EngineConnection {
        val pipe =
            withContext(Dispatchers.IO) {
                dataDir.mkdirs()
                NativePipe.open(engineVersion, dataDir.path)
            }
        return EngineConnection.open(pipe, clientName, scope)
    }
}
