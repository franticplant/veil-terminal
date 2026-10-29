package io.github.franticplant.veil.vm

import android.content.Context
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Bridges the VM's vsock stream to two ordinary pipes. Binder carries the app-facing pipe ends; the
 * VM host keeps the other ends and relays bytes. Pipes avoid handing a vsock descriptor across
 * Binder.
 *
 * The same object owns the per-session network broker: the guest engine asks for a host name and
 * port through [dial], the host connects TCP and relays it to the guest's broker listener on vsock
 * port 5001.
 */
class EngineProxy(private val context: Context) {
    private var toVm: ParcelFileDescriptor? = null
    private var fromVm: ParcelFileDescriptor? = null
    private var handle: VmEngine.Handle? = null
    private val relays = CopyOnWriteArrayList<Closeable>()

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

    /** Relays an already-connected TCP socket to the guest broker listener. */
    fun dial(host: String, port: Int, tcp: ParcelFileDescriptor) {
        val h = handle ?: throw IllegalStateException("engine VM is not running")
        VmEngine.note("broker dial $host:$port")
        val vmClass = Class.forName("android.system.virtualmachine.VirtualMachine")
        val pfd =
            vmClass.getMethod("connectVsock", Long::class.javaPrimitiveType).invoke(h.vm, 5001L)
                as ParcelFileDescriptor
        VmEngine.note("broker vsock connected")
        val toGuest = ParcelFileDescriptor.AutoCloseOutputStream(pfd.dup())
        val fromGuest = ParcelFileDescriptor.AutoCloseInputStream(pfd)
        toGuest.write("$host\n$port\n".toByteArray())
        toGuest.flush()
        VmEngine.note("broker header sent")
        val tcpIn = ParcelFileDescriptor.AutoCloseInputStream(tcp)
        val tcpOut = ParcelFileDescriptor.AutoCloseOutputStream(tcp.dup())
        Thread {
            runCatching { tcpIn.copyTo(toGuest) }
            runCatching { toGuest.close() }
        }
            .apply { isDaemon = true }
            .also { it.start() }
        Thread {
            runCatching { fromGuest.copyTo(tcpOut) }
            runCatching { fromGuest.close() }
        }
            .apply { isDaemon = true }
            .also { it.start() }
        relays.add(toGuest)
        relays.add(fromGuest)
        relays.add(tcpIn)
        relays.add(tcpOut)
    }

    fun stop() {
        relays.forEach { runCatching { it.close() } }
        relays.clear()
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
