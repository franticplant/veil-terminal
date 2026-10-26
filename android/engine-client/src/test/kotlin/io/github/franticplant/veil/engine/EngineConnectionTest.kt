package io.github.franticplant.veil.engine

import io.github.franticplant.veil.engine.proto.ClientMsg
import io.github.franticplant.veil.engine.proto.EngineMsg
import io.github.franticplant.veil.engine.proto.Error as ProtoError
import io.github.franticplant.veil.engine.proto.ErrorCode
import io.github.franticplant.veil.engine.proto.HelloAck
import io.github.franticplant.veil.engine.proto.Pong
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** A pipe to a scripted engine: [reply] decides what the engine sends back for each message. */
private class FakePipe(private val reply: (ClientMsg) -> List<ByteArray>) : MessagePipe {
  private val out = LinkedBlockingQueue<ByteArray>()
  @Volatile var closed = false

  override fun send(msg: ByteArray) {
    if (closed) throw PipeClosedException("closed")
    reply(ClientMsg.parseFrom(msg)).forEach(out::put)
  }

  override fun recv(timeoutMs: Int): ByteArray? {
    if (closed) throw PipeClosedException("closed")
    return out.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
  }

  override fun close() {
    closed = true
  }
}

private fun helloAck(id: Long): ByteArray =
    EngineMsg.newBuilder()
        .setRequestId(id)
        .setHelloAck(HelloAck.newBuilder().setProtocolVersion(1).setEngineVersion("fake"))
        .build()
        .toByteArray()

class EngineConnectionTest {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  @After fun tearDown() = scope.cancel()

  @Test
  fun handshakeThenPing() = runBlocking {
    val pipe = FakePipe { msg ->
      when (msg.bodyCase) {
        ClientMsg.BodyCase.HELLO -> listOf(helloAck(msg.requestId))
        ClientMsg.BodyCase.PING ->
            listOf(
                EngineMsg.newBuilder()
                    .setRequestId(msg.requestId)
                    .setPong(Pong.newBuilder().setNonce(msg.ping.nonce))
                    .build()
                    .toByteArray())
        else -> emptyList()
      }
    }
    val conn = EngineConnection.open(pipe, "test", scope)
    assertEquals("fake", conn.hello.engineVersion)
    assertEquals(42L, withTimeout(5_000) { conn.ping(42) }.nonce)
    conn.close()
    assertTrue(pipe.closed)
  }

  @Test
  fun malformedEngineMessageClosesTheConnectionInsteadOfCrashing() = runBlocking {
    val pipe = FakePipe { msg ->
      when (msg.bodyCase) {
        ClientMsg.BodyCase.HELLO -> listOf(helloAck(msg.requestId))
        else -> listOf(byteArrayOf(0x52, 0xff.toByte(), 0xff.toByte(), 0x03))
      }
    }
    val conn = EngineConnection.open(pipe, "test", scope)
    try {
      withTimeout(5_000) { conn.ping(1) }
      fail("ping should fail")
    } catch (e: EngineClosedException) {
      assertTrue(e.message!!, e.message!!.contains("malformed"))
    }
    assertTrue(conn.state.value is EngineConnection.State.Closed)
  }

  @Test
  fun engineErrorBecomesEngineException() = runBlocking {
    val pipe = FakePipe { msg ->
      listOf(
          veilError(msg.requestId, ErrorCode.ERROR_CODE_PROTOCOL))
    }
    try {
      EngineConnection.open(pipe, "test", scope)
      fail("open should fail")
    } catch (e: EngineException) {
      assertEquals(ErrorCode.ERROR_CODE_PROTOCOL, e.code)
    }
    assertTrue("a failed handshake closes the pipe", pipe.closed)
  }
}

private fun veilError(id: Long, code: ErrorCode): ByteArray =
    EngineMsg.newBuilder()
        .setRequestId(id)
        .setError(ProtoError.newBuilder().setCode(code))
        .build()
        .toByteArray()
