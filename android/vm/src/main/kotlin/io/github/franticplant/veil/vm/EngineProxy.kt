package io.github.franticplant.veil.vm

import android.content.Context
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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
    private val relays = CopyOnWriteArrayList<Relay>()

    /** Returns [write-to-VM, read-from-VM]. */
    fun start(protectedVm: Boolean, memoryMiB: Int): Array<ParcelFileDescriptor> {
        stop()
        val up = ParcelFileDescriptor.createPipe() // [read by proxy, write by app]
        val down = ParcelFileDescriptor.createPipe() // [read by app, write by proxy]
        val appWrite = up[1]
        val proxyRead = up[0]
        val appRead = down[0]
        val proxyWrite = down[1]
        val h =
            try {
                VmEngine.start(context, protectedVm, memoryMiB)
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
        val tcpIn = ParcelFileDescriptor.AutoCloseInputStream(tcp)
        val tcpOut = ParcelFileDescriptor.AutoCloseOutputStream(tcp.dup())
        val relay =
            Relay(toGuest, fromGuest, tcpIn, tcpOut) {
                relays.remove(it)
            }
        relays.add(relay)
        try {
            toGuest.write("$host\n$port\n".toByteArray())
            toGuest.flush()
            VmEngine.note("broker header sent")
            Thread {
                    try {
                        tcpIn.copyTo(toGuest)
                    } finally {
                        runCatching { toGuest.close() }
                        runCatching { tcpIn.close() }
                        relay.finished()
                    }
                }
                .apply { isDaemon = true }
                .also { it.start() }
            Thread {
                    try {
                        fromGuest.copyTo(tcpOut)
                    } finally {
                        runCatching { fromGuest.close() }
                        runCatching { tcpOut.close() }
                        relay.finished()
                    }
                }
                .apply { isDaemon = true }
                .also { it.start() }
        } catch (e: Exception) {
            relay.close()
            throw e
        }
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

    /** One broker relay: two directions, removed once both have finished or it is stopped. */
    private class Relay(
        private val toGuest: Closeable,
        private val fromGuest: Closeable,
        private val tcpIn: Closeable,
        private val tcpOut: Closeable,
        private val onDone: (Relay) -> Unit,
    ) : Closeable {
        private val remaining = AtomicInteger(2)
        private val closed = AtomicBoolean(false)
        private val removed = AtomicBoolean(false)

        fun finished() {
            if (remaining.decrementAndGet() == 0) remove()
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            listOf(toGuest, fromGuest, tcpIn, tcpOut).forEach { runCatching { it.close() } }
            remove()
        }

        private fun remove() {
            if (removed.compareAndSet(false, true)) onDone(this)
        }
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
