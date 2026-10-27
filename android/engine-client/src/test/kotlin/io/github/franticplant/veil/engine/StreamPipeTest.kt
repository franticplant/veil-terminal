package io.github.franticplant.veil.engine

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class StreamPipeTest {
    private fun frame(vararg msgs: ByteArray): ByteArray =
        ByteArrayOutputStream()
            .also { b ->
                val d = DataOutputStream(b)
                for (m in msgs) {
                    d.writeInt(m.size)
                    d.write(m)
                }
            }
            .toByteArray()

    @Test
    fun framesBothWays() {
        val fromEngine = PipedOutputStream()
        val toEngine = ByteArrayOutputStream()
        val pipe = StreamPipe(PipedInputStream(fromEngine, 1 shl 16), toEngine)
        fromEngine.write(frame("one".toByteArray(), ByteArray(0)))
        fromEngine.flush()
        assertArrayEquals("one".toByteArray(), pipe.recv(2000))
        assertArrayEquals(ByteArray(0), pipe.recv(2000))
        assertNull(pipe.recv(50))

        pipe.send("hi".toByteArray())
        assertArrayEquals(frame("hi".toByteArray()), toEngine.toByteArray())

        fromEngine.close()
        try {
            pipe.recv(2000)
            fail("expected the pipe to report the closed stream")
        } catch (e: PipeClosedException) {
            assertEquals("the engine closed the stream", e.message)
        }
    }

    @Test
    fun oversizedFrameClosesThePipe() {
        val fromEngine = PipedOutputStream()
        val pipe = StreamPipe(PipedInputStream(fromEngine), ByteArrayOutputStream())
        DataOutputStream(fromEngine).writeInt(MAX_MESSAGE_BYTES + 1)
        fromEngine.flush()
        try {
            pipe.recv(2000)
            fail("expected the oversized frame to close the pipe")
        } catch (e: PipeClosedException) {
            assertEquals(true, e.message!!.contains("limit"))
        }
    }
}
