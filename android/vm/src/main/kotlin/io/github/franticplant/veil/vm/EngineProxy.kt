package io.github.franticplant.veil.vm

import android.content.Context
import android.os.ParcelFileDescriptor

/**
 * Bridges the VM's vsock stream to two ordinary pipes. Binder carries the app-facing pipe ends; the
 * VM host keeps the other ends and relays bytes. Pipes avoid handing a vsock descriptor across
 * Binder.
 */
class EngineProxy(private val context: Context) {
    private var toVm: ParcelFileDescriptor? = null
    private var fromVm: ParcelFileDescriptor? = null
    private var handle: VmEngine.Handle? = null

    /** Returns [write-to-VM, read-from-VM]. */
    fun start(protectedVm: Boolean): Array<ParcelFileDescriptor> {
        stop()
        val up = ParcelFileDescriptor.createPipe() // [read by proxy, write by app]
        val down = ParcelFileDescriptor.createPipe() // [read by app, write by proxy]
        val appWrite = up[1]
        val proxyRead = up[0]
        val appRead = down[0]
        val proxyWrite = down[1]
        val h =
            try {
                VmEngine.start(context, protectedVm)
            } catch (e: Exception) {
                listOf(appWrite, proxyRead, appRead, proxyWrite).forEach {
                    runCatching { it.close() }
                }
                throw e
            }
        toVm = proxyRead
        fromVm = proxyWrite
        handle = h
        Thread {
            try {
                bridge(h.pfd, proxyRead, proxyWrite)
            } catch (_: Exception) {
                // The VM or the app end closed.
            }
        }
            .apply { isDaemon = true }
            .start()
        return arrayOf(appWrite, appRead)
    }

    fun stop() {
        runCatching { toVm?.close() }
        runCatching { fromVm?.close() }
        toVm = null
        fromVm = null
        handle?.let { VmEngine.stop(it) }
        handle = null
    }

    private fun bridge(
        pfd: ParcelFileDescriptor,
        proxyRead: ParcelFileDescriptor,
        proxyWrite: ParcelFileDescriptor,
    ) {
        val fromApp = ParcelFileDescriptor.AutoCloseInputStream(proxyRead)
        val toVm = ParcelFileDescriptor.AutoCloseOutputStream(pfd.dup())
        val fromVm = ParcelFileDescriptor.AutoCloseInputStream(pfd)
        val toApp = ParcelFileDescriptor.AutoCloseOutputStream(proxyWrite)
        val upload = Thread {
            runCatching { fromApp.copyTo(toVm) }
        }
            .apply { isDaemon = true }
            .also { it.start() }
        runCatching { fromVm.copyTo(toApp) }
        runCatching { upload.interrupt() }
    }
}
