package io.github.franticplant.veil.engine

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import io.github.franticplant.veil.vm.api.IVmHost
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The engine inside the separate VM host package. The host boots the fixed Microdroid payload and
 * returns the connected vsock stream; this backend wraps it in the same [StreamPipe] the native
 * process uses.
 *
 * If the VM package is missing, not granted, or fails to boot, the native process backend is used
 * instead. VM mode is an optimization/hardening mode, not a new engine.
 */
class VmBackend(
    private val context: Context,
    private val engineVersion: String,
    private val dataDir: File,
    private val protectedVm: Boolean = true,
    private val log: LogRing? = null,
) : EngineBackend {
    override val kind = "vm"

    private val fallback = HostBackend(context, engineVersion, dataDir)
    private val netBroker by lazy { NetBroker(context, dataDir) }

    @Volatile private var active: IVmHost? = null

    override suspend fun prepare(host: String, port: Int) =
        withContext(Dispatchers.IO) {
            val vmHost = active ?: return@withContext
            log?.info("vm", "broker prepare $host:$port")
            try {
                val pfd = netBroker.dial(host, port)
                try {
                    // Binder duplicates the fd for the VM service. The app's
                    // copy is done as soon as the call returns; keeping it
                    // would leak one descriptor per session.
                    vmHost.dial(host, port, pfd)
                } finally {
                    runCatching { pfd.close() }
                }
                log?.info("vm", "broker ready $host:$port")
            } catch (e: Exception) {
                log?.warn("vm", "broker failed $host:$port: ${e.message}")
                throw IllegalStateException(
                    "VM network broker could not reach $host:$port: ${e.message}",
                    e,
                )
            }
        }

    override suspend fun connect(clientName: String, scope: CoroutineScope): EngineConnection =
        withContext(Dispatchers.IO) {
            val handle =
                try {
                    bindAndStart()
                } catch (e: Exception) {
                    log?.warn(
                        "vm",
                        "VM unavailable (${e.javaClass.name}: ${e.message}); falling back to the native engine",
                    )
                    return@withContext fallback.connect(clientName, scope)
                }
            try {
                val input = ParcelFileDescriptor.AutoCloseInputStream(handle.pfds[1])
                val output = ParcelFileDescriptor.AutoCloseOutputStream(handle.pfds[0])
                val pipe = StreamPipe(input, output) { handle.close() }
                EngineConnection.open(pipe, clientName, scope)
            } catch (e: Exception) {
                handle.close()
                log?.warn(
                    "vm",
                    "VM pipe failed (${e.javaClass.name}: ${e.message}); falling back to the native engine",
                )
                fallback.connect(clientName, scope)
            }
        }

    private fun bindAndStart(): Handle {
        val connected = CountDownLatch(1)
        var remote: IVmHost? = null
        var failure: String? = null
        val connection =
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    remote = binder?.let { IVmHost.Stub.asInterface(it) }
                    if (remote == null) failure = "the VM host returned no binder"
                    connected.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    // The stream close is the real signal; the call reports failure.
                }
            }
        val intent = Intent().setClassName(VM_PACKAGE, VM_HOST_SERVICE)
        // The VM host is a foreground service while a VM runs; background
        // services are a prime LMK target during boot.
        runCatching { context.startForegroundService(intent) }
        if (!context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
            throw IllegalStateException("cannot bind $VM_PACKAGE")
        }
        var keep = false
        try {
            if (!connected.await(10, TimeUnit.SECONDS)) {
                throw IllegalStateException("timed out binding $VM_PACKAGE")
            }
            failure?.let { throw IllegalStateException(it) }
            val host = remote ?: throw IllegalStateException("no VM host binder")
            val pfds = host.startEngine(protectedVm)
            active = host
            keep = true
            return Handle(context, connection, host, pfds) {
                active = null
                runCatching { netBroker.close() }
            }
        } finally {
            if (!keep) runCatching { context.unbindService(connection) }
        }
    }

    private class Handle(
        private val context: Context,
        private val connection: ServiceConnection,
        private val host: IVmHost,
        val pfds: Array<ParcelFileDescriptor>,
        private val onClose: () -> Unit,
    ) : AutoCloseable {
        private var closed = false

        override fun close() {
            if (closed) return
            closed = true
            pfds.forEach { runCatching { it.close() } }
            runCatching { host.stopEngine() }
            runCatching { context.unbindService(connection) }
            onClose()
        }
    }

    companion object {
        private const val VM_PACKAGE = "io.github.franticplant.veil.vm"
        private const val VM_HOST_SERVICE = "io.github.franticplant.veil.vm.VmHostService"
    }
}
