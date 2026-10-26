package io.github.franticplant.veil.engine

import io.github.franticplant.veil.engine.proto.Ack
import io.github.franticplant.veil.engine.proto.ClientMsg
import io.github.franticplant.veil.engine.proto.EngineMsg
import io.github.franticplant.veil.engine.proto.HelloAck
import io.github.franticplant.veil.engine.proto.SessionOpened
import java.util.Collections
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** An engine that answers everything and records the sizes it was asked for, in arrival order. */
private class RecordingPipe : MessagePipe {
    private val out = LinkedBlockingQueue<ByteArray>()
    val sizes: MutableList<Int> = Collections.synchronizedList(ArrayList())

    override fun send(msg: ByteArray) {
        val m = ClientMsg.parseFrom(msg)
        // Uneven write latency: the condition under which unordered senders overtake each other.
        Thread.sleep(Random.nextLong(0, 3))
        val reply = EngineMsg.newBuilder().setRequestId(m.requestId)
        when (m.bodyCase) {
            ClientMsg.BodyCase.HELLO ->
                reply.setHelloAck(HelloAck.newBuilder().setProtocolVersion(1))
            ClientMsg.BodyCase.OPEN_SESSION ->
                reply.setSessionOpened(SessionOpened.newBuilder().setSessionId(1))
            ClientMsg.BodyCase.RESIZE -> {
                sizes.add(m.resize.size.cols)
                reply.setAck(Ack.getDefaultInstance())
            }
            else -> reply.setAck(Ack.getDefaultInstance())
        }
        out.put(reply.build().toByteArray())
    }

    override fun recv(timeoutMs: Int): ByteArray? =
        out.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS)

    override fun close() {}
}

class SessionOrderTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After fun tearDown() = scope.cancel()

    @Test
    fun aBurstOfResizesArrivesInOrder() = runBlocking {
        val pipe = RecordingPipe()
        val backend =
            object : EngineBackend {
                override val kind = "fake"

                override suspend fun connect(clientName: String, scope: CoroutineScope) =
                    EngineConnection.open(pipe, clientName, scope)
            }
        val registry = SessionRegistry(backend, scope, LogRing(logcat = false))
        val s = registry.open(Target("h", 22, "u"), 80, 24)
        val sent = (2..201).toList()
        for (c in sent) s.resize(c, 24)
        withTimeout(20_000) { while (pipe.sizes.size < sent.size) delay(10) }
        assertEquals(sent, pipe.sizes.toList())
    }
}
