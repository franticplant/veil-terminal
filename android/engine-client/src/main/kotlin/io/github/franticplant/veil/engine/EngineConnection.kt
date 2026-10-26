package io.github.franticplant.veil.engine

import com.google.protobuf.InvalidProtocolBufferException
import io.github.franticplant.veil.engine.proto.ClientMsg
import io.github.franticplant.veil.engine.proto.EngineMsg
import io.github.franticplant.veil.engine.proto.ErrorCode
import io.github.franticplant.veil.engine.proto.Hello
import io.github.franticplant.veil.engine.proto.HelloAck
import io.github.franticplant.veil.engine.proto.Ping
import io.github.franticplant.veil.engine.proto.Pong
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The engine protocol version this client speaks (`Hello.protocol_version`). */
const val PROTOCOL_VERSION = 1

/** Matches `MAX_MESSAGE_BYTES` in core/protocol: larger engine messages are refused unread. */
const val MAX_MESSAGE_BYTES = 4 * 1024 * 1024

/** The engine answered a request with an `Error`. */
class EngineException(val code: ErrorCode, detail: String) : Exception("$code: $detail")

/** The connection ended: the engine closed, or sent something this client cannot trust. */
class EngineClosedException(message: String) : Exception(message)

/**
 * One frontend connection to an engine, over any [MessagePipe].
 *
 * Requests carry a fresh non-zero `request_id` and suspend until the engine's reply with the same
 * ID. Messages with `request_id` 0 are engine events, delivered on [events].
 *
 * The engine is treated as untrusted input: a reply that is oversized or does not parse closes the
 * connection rather than crashing the app. (Once the engine runs in a VM, that is exactly what a
 * compromised guest would send.)
 */
class EngineConnection private constructor(private val pipe: MessagePipe, scope: CoroutineScope) :
    AutoCloseable {

    sealed interface State {
        data object Open : State

        data class Closed(val reason: String) : State
    }

    private val nextId = AtomicLong(1)
    private val sendLock = Mutex()
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<EngineMsg>>()
    // Events are not dropped: when the buffer is full the reader stops reading,
    // which stops the engine (its queue fills). Something must collect events
    // for the connection to make progress; SessionRegistry does.
    private val _events = MutableSharedFlow<EngineMsg>(extraBufferCapacity = 256)
    private val _state = MutableStateFlow<State>(State.Open)

    /** Engine-initiated messages (`request_id` 0). */
    val events: SharedFlow<EngineMsg> = _events

    val state: StateFlow<State> = _state

    /** The engine's handshake reply: negotiated version, engine build, limits. */
    lateinit var hello: HelloAck
        private set

    private val reader: Job = scope.launch(Dispatchers.IO) { readLoop() }

    /** Sends [msg] with a fresh request ID and waits for the matching reply. */
    suspend fun request(msg: ClientMsg.Builder): EngineMsg = post(msg).await()

    /**
     * Sends [msg] with a fresh request ID and returns its reply, to await separately. Messages
     * posted one after another reach the engine in that order (the pipe is written under a lock),
     * so a caller that must keep order (a session's input and resizes) posts sequentially and
     * awaits the replies elsewhere, without a round trip per message.
     */
    suspend fun post(msg: ClientMsg.Builder): Deferred<EngineMsg> {
        (state.value as? State.Closed)?.let { throw EngineClosedException(it.reason) }
        val reply = CompletableDeferred<EngineMsg>()
        reply.invokeOnCompletion { pending.values.remove(reply) }
        sendLock.withLock {
            val id = nextId.getAndIncrement()
            pending[id] = reply
            val bytes = msg.setRequestId(id).build().toByteArray()
            withContext(Dispatchers.IO) {
                try {
                    pipe.send(bytes)
                } catch (e: PipeClosedException) {
                    shutDown(e.message ?: "engine closed")
                }
            }
        }
        return reply
    }

    /** A round trip to the engine. */
    suspend fun ping(nonce: Long): Pong {
        val reply = request(ClientMsg.newBuilder().setPing(Ping.newBuilder().setNonce(nonce)))
        return reply.expect(EngineMsg.BodyCase.PONG).pong
    }

    override fun close() {
        shutDown("closed by client")
        reader.cancel()
        pipe.close()
    }

    private suspend fun readLoop() {
        while (currentCoroutineContext().isActive && state.value == State.Open) {
            val buf =
                try {
                    pipe.recv(RECV_POLL_MS) ?: continue
                } catch (e: PipeClosedException) {
                    shutDown(e.message ?: "engine closed")
                    return
                }
            if (buf.size > MAX_MESSAGE_BYTES) {
                shutDown(
                    "engine sent a ${buf.size}-byte message, over the $MAX_MESSAGE_BYTES limit"
                )
                return
            }
            val msg =
                try {
                    EngineMsg.parseFrom(buf)
                } catch (e: InvalidProtocolBufferException) {
                    shutDown("engine sent a malformed message: ${e.message}")
                    return
                }
            if (msg.requestId == 0L) {
                _events.emit(msg)
            } else {
                // A reply to nothing we are waiting for (late, or invented) is dropped.
                pending.remove(msg.requestId)?.complete(msg)
            }
        }
    }

    private fun shutDown(reason: String) {
        if (_state.value is State.Closed) return
        _state.value = State.Closed(reason)
        val error = EngineClosedException(reason)
        pending.values.forEach { it.completeExceptionally(error) }
    }

    companion object {
        private const val RECV_POLL_MS = 250

        /** Opens a connection over [pipe] and performs the Hello handshake. */
        suspend fun open(
            pipe: MessagePipe,
            clientName: String,
            scope: CoroutineScope,
        ): EngineConnection {
            val conn = EngineConnection(pipe, scope)
            try {
                val reply =
                    conn.request(
                        ClientMsg.newBuilder()
                            .setHello(
                                Hello.newBuilder()
                                    .setProtocolVersion(PROTOCOL_VERSION)
                                    .setClientName(clientName)
                            )
                    )
                conn.hello = reply.expect(EngineMsg.BodyCase.HELLO_ACK).helloAck
                return conn
            } catch (e: Exception) {
                conn.close()
                throw e
            }
        }
    }
}

/** Returns this message if its body is [case]; throws the engine's error, or a mismatch. */
fun EngineMsg.expect(case: EngineMsg.BodyCase): EngineMsg =
    when (bodyCase) {
        case -> this
        EngineMsg.BodyCase.ERROR -> throw EngineException(error.code, error.detail)
        else ->
            throw EngineException(ErrorCode.ERROR_CODE_PROTOCOL, "expected $case, got $bodyCase")
    }
