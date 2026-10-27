package io.github.franticplant.veil.engine

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A [MessagePipe] over a byte stream, framed as core/wire frames them: a 4-byte big-endian length,
 * then the message. This is how a frontend reaches an engine outside its process: a separate
 * process (a pipe or socket), or the Microdroid payload (the vsock file descriptor Android's VM API
 * hands out). The length is checked against [MAX_MESSAGE_BYTES] before reading the body.
 *
 * A reader thread fills a bounded queue; when it is full the thread stops reading, so a slow
 * frontend slows the engine down instead of buffering without bound.
 */
class StreamPipe(input: InputStream, output: OutputStream, private val onClose: () -> Unit = {}) :
    MessagePipe {
    private val input = DataInputStream(input.buffered())
    private val output = DataOutputStream(output.buffered())
    private val inbox = LinkedBlockingQueue<ByteArray>(QUEUE_DEPTH)
    @Volatile private var closedReason: String? = null

    private val reader =
        Thread({ readLoop() }, "veil-stream-pipe").apply {
            isDaemon = true
            start()
        }

    private fun readLoop() {
        try {
            while (closedReason == null) {
                val len =
                    try {
                        this.input.readInt()
                    } catch (e: EOFException) {
                        closedReason = "the engine closed the stream"
                        return
                    }
                if (len < 0 || len > MAX_MESSAGE_BYTES) {
                    closedReason =
                        "the engine sent a frame of $len bytes (limit $MAX_MESSAGE_BYTES)"
                    return
                }
                val buf = ByteArray(len)
                this.input.readFully(buf)
                inbox.put(buf)
            }
        } catch (e: IOException) {
            if (closedReason == null) closedReason = "stream failed: ${e.message}"
        } catch (e: InterruptedException) {
            if (closedReason == null) closedReason = "closed"
        }
    }

    override fun send(msg: ByteArray) {
        closedReason?.let { throw PipeClosedException(it) }
        require(msg.size <= MAX_MESSAGE_BYTES) { "message over $MAX_MESSAGE_BYTES bytes" }
        try {
            synchronized(output) {
                output.writeInt(msg.size)
                output.write(msg)
                output.flush()
            }
        } catch (e: IOException) {
            closedReason = "stream failed: ${e.message}"
            throw PipeClosedException(closedReason!!)
        }
    }

    override fun recv(timeoutMs: Int): ByteArray? {
        inbox.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS)?.let {
            return it
        }
        closedReason?.let { if (inbox.isEmpty()) throw PipeClosedException(it) }
        return null
    }

    override fun close() {
        if (closedReason == null) closedReason = "closed"
        reader.interrupt()
        try {
            output.close()
        } catch (e: IOException) {}
        try {
            input.close()
        } catch (e: IOException) {}
        onClose()
    }

    companion object {
        private const val QUEUE_DEPTH = 64
    }
}
