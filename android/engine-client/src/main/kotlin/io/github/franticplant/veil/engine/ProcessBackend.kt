package io.github.franticplant.veil.engine

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Runs engines in the `:engine` process. Each [IEngineProcess.connect] creates a socket pair,
 * serves an engine on one end (on its own thread) and returns the other. Same UID as the app for
 * now; the boundary is the point: the engine crashes alone, and the next step (an isolated or
 * VM-hosted engine) changes only who answers [connect].
 */
class EngineProcessService : Service() {
    override fun onBind(intent: Intent?): IBinder =
        object : IEngineProcess.Stub() {
            override fun connect(engineVersion: String, dataDir: String): ParcelFileDescriptor {
                if (getCallingUid() != Process.myUid()) throw SecurityException("not this app")
                val (ours, theirs) = ParcelFileDescriptor.createSocketPair()
                val fd = ours.detachFd()
                Thread(
                        { NativeEngine.serveFd(fd, engineVersion, dataDir) },
                        "veil-engine-serve",
                    )
                    .start()
                return theirs
            }
        }
}

/**
 * An [EngineBackend] whose engine runs in the `:engine` process, reached over a socket with
 * veil-wire framing ([StreamPipe]). The same protocol bytes as [LocalBackend]; only the pipe
 * differs.
 */
class ProcessBackend(
    private val context: Context,
    private val engineVersion: String,
    private val dataDir: java.io.File,
) : EngineBackend {
    override val kind = "process"

    override suspend fun connect(clientName: String, scope: CoroutineScope): EngineConnection {
        val bound = CompletableDeferred<IEngineProcess>()
        val conn =
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    bound.complete(IEngineProcess.Stub.asInterface(service))
                }

                override fun onServiceDisconnected(name: ComponentName?) {}
            }
        val intent = Intent(context, EngineProcessService::class.java)
        check(context.bindService(intent, conn, Context.BIND_AUTO_CREATE)) {
            "cannot bind the engine process"
        }
        val service = withTimeout(10_000) { bound.await() }
        val pfd =
            withContext(Dispatchers.IO) {
                dataDir.mkdirs()
                service.connect(engineVersion, dataDir.path)
            }
        val pipe =
            StreamPipe(
                FileInputStream(pfd.fileDescriptor),
                FileOutputStream(pfd.fileDescriptor),
            ) {
                pfd.close()
                context.unbindService(conn)
            }
        return EngineConnection.open(pipe, clientName, scope)
    }
}
