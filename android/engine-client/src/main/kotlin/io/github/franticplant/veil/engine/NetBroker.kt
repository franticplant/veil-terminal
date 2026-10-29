package io.github.franticplant.veil.engine

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File
import java.io.InputStream

/**
 * The native host network broker. The app sends a target over a Unix socket;
 * the broker (a separate no-JVM process) dials TCP and relays bytes. Keeping
 * the dial out of the UI process is what makes VM sessions behave like the
 * native engine process.
 */
class NetBroker(context: Context, private val dataDir: File) : Closeable {
    private val binary = File(context.applicationInfo.nativeLibraryDir, BINARY)
    private val path = File(dataDir, "net-broker.sock")
    private var process: Process? = null

    @Synchronized
    private fun start() {
        process?.takeIf { it.isAlive }?.let { return }
        dataDir.mkdirs()
        path.delete()
        val stderr = File(dataDir, "net-broker.stderr")
        process =
            ProcessBuilder(binary.path, "--net-broker", path.path)
                .redirectError(stderr)
                .start()
        for (i in 0 until 100) {
            if (path.exists()) return
            if (process?.isAlive != true) break
            Thread.sleep(20)
        }
        val detail =
            runCatching { stderr.readText().takeLast(2_000) }
                .getOrDefault("no stderr")
        val exit = runCatching { process?.exitValue() }.getOrNull()
        throw IllegalStateException(
            "native network broker did not start (exit=$exit): $detail"
        )
    }

    /** Opens one relayed TCP connection and returns the app-facing pipe end. */
    fun dial(host: String, port: Int): ParcelFileDescriptor {
        start()
        val socket = LocalSocket()
        socket.connect(LocalSocketAddress(path.path, LocalSocketAddress.Namespace.FILESYSTEM))
        try {
            socket.outputStream.write("$host\n$port\n".toByteArray())
            socket.outputStream.flush()
            val ack = readLine(socket.inputStream)
            if (ack != "OK") throw IllegalStateException("network broker: $ack")
            val pfd = ParcelFileDescriptor.dup(socket.fileDescriptor)
            // The fd is duplicated for the relay and for the VM service's
            // Binder transaction. Keeping this LocalSocket open would also
            // keep the native broker's upload thread (and its TCP socket)
            // alive after the session ends.
            socket.close()
            return pfd
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw e
        }
    }

    override fun close() {
        process?.destroy()
        process = null
    }

    /** Reads the broker ack without buffering bytes that belong to SSH. */
    private fun readLine(input: InputStream): String {
        val out = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) throw IllegalStateException("network broker closed the stream")
            if (b == '\n'.code) return out.toString()
            if (out.length >= 256) throw IllegalStateException("broker ack too long")
            out.append(b.toChar())
        }
    }

    companion object {
        private const val BINARY = "libveil_engine_host.so"
    }
}
