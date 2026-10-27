package io.github.franticplant.veil.engine

import com.google.protobuf.ByteString
import io.github.franticplant.veil.engine.proto.AuthAnswer
import io.github.franticplant.veil.engine.proto.AuthPrompt
import io.github.franticplant.veil.engine.proto.ClientMsg
import io.github.franticplant.veil.engine.proto.CloseSession
import io.github.franticplant.veil.engine.proto.EngineMsg
import io.github.franticplant.veil.engine.proto.EngineStats
import io.github.franticplant.veil.engine.proto.ForgetHostKey
import io.github.franticplant.veil.engine.proto.FrameAck
import io.github.franticplant.veil.engine.proto.GetLogs
import io.github.franticplant.veil.engine.proto.GetStats
import io.github.franticplant.veil.engine.proto.HostKeyAnswer
import io.github.franticplant.veil.engine.proto.HostKeyDecision
import io.github.franticplant.veil.engine.proto.HostKeyEntry
import io.github.franticplant.veil.engine.proto.HostKeyPrompt
import io.github.franticplant.veil.engine.proto.Input
import io.github.franticplant.veil.engine.proto.ListHostKeys
import io.github.franticplant.veil.engine.proto.LogBatch
import io.github.franticplant.veil.engine.proto.LogLevel
import io.github.franticplant.veil.engine.proto.OpenSession
import io.github.franticplant.veil.engine.proto.RequestSnapshot
import io.github.franticplant.veil.engine.proto.Resize
import io.github.franticplant.veil.engine.proto.Scroll
import io.github.franticplant.veil.engine.proto.SessionPhase
import io.github.franticplant.veil.engine.proto.TermSize
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Where a session connects. Holds no secrets. */
/** Where a session connects, and with which key ([keyId], empty for none). Holds no secrets. */
data class Target(
    val host: String,
    val port: Int,
    val username: String,
    val label: String = "",
    val keyId: String = "",
) {
    val displayName: String
        get() = label.ifEmpty { if (port == 22) "$username@$host" else "$username@$host:$port" }
}

/**
 * The open sessions of this app, over one engine connection. It outlives any screen: the UI
 * observes it and can come and go.
 *
 * The registry is the frontend side of the session layer and holds no UI concepts. It owns the
 * engine connection's event stream, routes events to their [Session], and acknowledges frames once
 * they are in the session's [ScreenModel].
 */
class SessionRegistry(
    private val backend: EngineBackend,
    private val scope: CoroutineScope,
    internal val log: LogRing = LogRing(),
    /** Signs logins for sessions with a key; null: keys are not offered. */
    private val signer: LoginSigner? = null,
) {
    private val connectLock = Mutex()
    @Volatile private var conn: EngineConnection? = null
    private val byId = ConcurrentHashMap<Long, Session>()
    private val _sessions = MutableStateFlow<List<Session>>(emptyList())

    /** Sessions in the order they were opened, closed ones included until [forget]. */
    val sessions: StateFlow<List<Session>> = _sessions

    /** The engine connection, opened on first use (and again if the engine went away). */
    suspend fun connection(): EngineConnection = connectLock.withLock {
        conn?.takeIf { it.state.value == EngineConnection.State.Open }
            ?: backend.connect("veil-frontend", scope).also { c ->
                conn = c
                log.info(
                    "engine",
                    "connected to the ${backend.kind} engine ${c.hello.engineVersion}, " +
                        "protocol ${c.hello.protocolVersion}",
                )
                scope.launch { c.events.collect { dispatch(it) } }
                scope.launch {
                    c.state.collect { s ->
                        if (s is EngineConnection.State.Closed) {
                            log.warn("engine", "connection closed: ${s.reason}")
                            byId.values.forEach {
                                it.engineGone("engine connection lost: ${s.reason}")
                            }
                        }
                    }
                }
            }
    }

    /** The current connection if one is open; never opens one. */
    val currentConnection: EngineConnection?
        get() = conn?.takeIf { it.state.value == EngineConnection.State.Open }

    /** Where the engine runs ("local", later "vm"). */
    val backendKind: String
        get() = backend.kind

    /**
     * Closes the engine connection. Every session on it ends; the next use opens a new one. With
     * the local backend this stops the engine and starts a fresh one.
     */
    suspend fun restartEngine(reason: String) {
        connectLock.withLock {
            val c = conn ?: return
            log.warn("engine", "restarting the engine: $reason")
            conn = null
            c.close()
        }
    }

    suspend fun engineStats(): EngineStats =
        connection()
            .request(ClientMsg.newBuilder().setGetStats(GetStats.getDefaultInstance()))
            .expect(EngineMsg.BodyCase.ENGINE_STATS)
            .engineStats

    suspend fun engineLogs(afterSeq: Long, max: Int, minLevel: LogLevel): LogBatch =
        connection()
            .request(
                ClientMsg.newBuilder()
                    .setGetLogs(
                        GetLogs.newBuilder().setAfterSeq(afterSeq).setMax(max).setMinLevel(minLevel)
                    )
            )
            .expect(EngineMsg.BodyCase.LOG_BATCH)
            .logBatch

    suspend fun hostKeys(): List<HostKeyEntry> =
        connection()
            .request(ClientMsg.newBuilder().setListHostKeys(ListHostKeys.getDefaultInstance()))
            .expect(EngineMsg.BodyCase.HOST_KEY_LIST)
            .hostKeyList
            .entriesList

    suspend fun forgetHostKey(host: String, port: Int) {
        connection()
            .request(
                ClientMsg.newBuilder()
                    .setForgetHostKey(ForgetHostKey.newBuilder().setHost(host).setPort(port))
            )
            .expect(EngineMsg.BodyCase.ACK)
        log.info("hostkeys", "forgot the key of $host:$port")
    }

    /** The session with [id], if this registry knows it. */
    fun get(id: Long): Session? = byId[id]

    /** Opens a session to [target] with a [cols] x [rows] terminal. */
    suspend fun open(target: Target, cols: Int, rows: Int): Session {
        val c = connection()
        val reply =
            c.request(
                ClientMsg.newBuilder()
                    .setOpenSession(
                        OpenSession.newBuilder()
                            .setHost(target.host)
                            .setPort(target.port)
                            .setUsername(target.username)
                            .setSize(TermSize.newBuilder().setCols(cols).setRows(rows))
                            .addAllPublicKeys(publicKeysFor(target))
                    )
            )
        val id = reply.expect(EngineMsg.BodyCase.SESSION_OPENED).sessionOpened.sessionId
        log.info("session", "opened ${target.displayName} (${cols}x$rows)", id)
        val session = Session(id, target, c, this)
        byId[id] = session
        _sessions.value = _sessions.value + session
        return session
    }

    private fun publicKeysFor(target: Target): List<String> {
        if (target.keyId.isEmpty()) return emptyList()
        val key = signer?.publicKey(target.keyId)
        if (key == null)
            log.warn("session", "key '${target.keyId}' is not available; using passwords")
        return listOfNotNull(key)
    }

    /** Answers a sign request with the session's key, off the caller's thread. */
    private fun sign(session: Session, req: io.github.franticplant.veil.engine.proto.SignRequest) {
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val sig =
                try {
                    signer?.sign(session.target.keyId, session.target.username, req)
                } catch (e: Exception) {
                    log.warn(
                        "session",
                        "signing failed: ${e.javaClass.simpleName}: ${e.message}",
                        session.id,
                    )
                    null
                }
            log.info(
                "session",
                if (sig != null) "signed the login with key '${session.target.keyId}'"
                else "refused to sign",
                session.id,
            )
            session.answerSign(req, sig)
        }
    }

    /** Removes a closed session from [sessions]. */
    fun forget(session: Session) {
        byId.remove(session.id)
        _sessions.value = _sessions.value - session
    }

    private fun dispatch(msg: EngineMsg) {
        when (msg.bodyCase) {
            EngineMsg.BodyCase.SESSION_STATE ->
                byId[msg.sessionState.sessionId]?.onState(
                    msg.sessionState.phase,
                    msg.sessionState.detail,
                )
            EngineMsg.BodyCase.HOST_KEY_PROMPT ->
                byId[msg.hostKeyPrompt.sessionId]?.onPrompt(Prompt.HostKey(msg.hostKeyPrompt))
            EngineMsg.BodyCase.AUTH_PROMPT ->
                byId[msg.authPrompt.sessionId]?.onPrompt(Prompt.Auth(msg.authPrompt))
            EngineMsg.BodyCase.FRAME -> byId[msg.frame.sessionId]?.onFrame(msg.frame)
            EngineMsg.BodyCase.BELL -> byId[msg.bell.sessionId]?.onBell()
            EngineMsg.BodyCase.SIGN_REQUEST ->
                byId[msg.signRequest.sessionId]?.let { sign(it, msg.signRequest) }
            EngineMsg.BodyCase.CLIPBOARD_WRITE ->
                byId[msg.clipboardWrite.sessionId]?.onClipboard(
                    ClipboardOffer(msg.clipboardWrite.text, msg.clipboardWrite.primary)
                )
            else -> {}
        }
    }

    internal fun launch(block: suspend CoroutineScope.() -> Unit) {
        scope.launch(block = block)
    }
}

/**
 * Text a remote offered for the clipboard (OSC 52). Remote-controlled: whether it reaches the
 * clipboard is the frontend's decision, and it must be shown as plain text.
 */
data class ClipboardOffer(val text: String, val primary: Boolean)

/** Something the engine needs a human to decide. */
sealed interface Prompt {
    data class HostKey(val prompt: HostKeyPrompt) : Prompt

    data class Auth(val prompt: AuthPrompt) : Prompt
}

/** Where a session is in its life; [detail] explains a CLOSED one. */
data class SessionStatus(val phase: SessionPhase, val detail: String = "") {
    val closed: Boolean
        get() = phase == SessionPhase.SESSION_PHASE_CLOSED
}

/** One SSH session, as the frontend sees it. */
class Session
internal constructor(
    val id: Long,
    val target: Target,
    private val conn: EngineConnection,
    private val registry: SessionRegistry,
) {
    private val _status = MutableStateFlow(SessionStatus(SessionPhase.SESSION_PHASE_CONNECTING))
    private val _prompt = MutableStateFlow<Prompt?>(null)
    private val _screenVersion = MutableStateFlow(0L)
    private val _bell = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val _clipboard = MutableSharedFlow<ClipboardOffer>(extraBufferCapacity = 4)

    val status: StateFlow<SessionStatus> = _status

    /** The open prompt, if the engine is waiting for one. */
    val prompt: StateFlow<Prompt?> = _prompt

    /** The screen. Read it under `synchronized(screen)`. */
    val screen = ScreenModel()

    /** Changes whenever [screen] does: observe it to redraw. */
    val screenVersion: StateFlow<Long> = _screenVersion

    val bell: SharedFlow<Unit> = _bell

    /** Clipboard offers from the remote; extra ones are dropped while the UI is not collecting. */
    val clipboard: SharedFlow<ClipboardOffer> = _clipboard

    fun input(bytes: ByteArray) {
        if (bytes.isEmpty() || _status.value.closed) return
        send(
            ClientMsg.newBuilder()
                .setInput(Input.newBuilder().setSessionId(id).setData(ByteString.copyFrom(bytes)))
        )
    }

    fun resize(cols: Int, rows: Int) {
        if (_status.value.closed) return
        send(
            ClientMsg.newBuilder()
                .setResize(
                    Resize.newBuilder()
                        .setSessionId(id)
                        .setSize(TermSize.newBuilder().setCols(cols).setRows(rows))
                )
        )
    }

    /** Scrolls [lines] into history (negative: towards the live screen). */
    fun scroll(lines: Int) {
        if (_status.value.closed || lines == 0) return
        send(ClientMsg.newBuilder().setScroll(Scroll.newBuilder().setSessionId(id).setLines(lines)))
    }

    fun scrollToBottom() {
        if (_status.value.closed) return
        send(
            ClientMsg.newBuilder().setScroll(Scroll.newBuilder().setSessionId(id).setToBottom(true))
        )
    }

    /** Asks for a full frame (e.g. a new screen started showing this session). */
    fun requestSnapshot() {
        if (_status.value.closed) return
        send(
            ClientMsg.newBuilder().setRequestSnapshot(RequestSnapshot.newBuilder().setSessionId(id))
        )
    }

    fun answerHostKey(prompt: HostKeyPrompt, decision: HostKeyDecision) {
        _prompt.compareAndSet(Prompt.HostKey(prompt), null)
        send(
            ClientMsg.newBuilder()
                .setHostKeyAnswer(
                    HostKeyAnswer.newBuilder()
                        .setSessionId(id)
                        .setPromptId(prompt.promptId)
                        .setDecision(decision)
                )
        )
    }

    /** Answers a sign request with a signature blob, or refuses it (null). */
    internal fun answerSign(
        req: io.github.franticplant.veil.engine.proto.SignRequest,
        signature: ByteArray?,
    ) {
        val answer =
            io.github.franticplant.veil.engine.proto.SignAnswer.newBuilder()
                .setSessionId(id)
                .setPromptId(req.promptId)
        if (signature == null) answer.setCancel(true)
        else answer.setSignature(ByteString.copyFrom(signature))
        send(ClientMsg.newBuilder().setSignAnswer(answer))
    }

    /** Answers an auth prompt with one response per field, or cancels it. */
    fun answerAuth(prompt: AuthPrompt, responses: List<String>?) {
        _prompt.compareAndSet(Prompt.Auth(prompt), null)
        val answer = AuthAnswer.newBuilder().setSessionId(id).setPromptId(prompt.promptId)
        if (responses == null) answer.setCancel(true) else answer.addAllResponses(responses)
        send(ClientMsg.newBuilder().setAuthAnswer(answer))
    }

    fun close() {
        if (_status.value.closed) return
        send(ClientMsg.newBuilder().setCloseSession(CloseSession.newBuilder().setSessionId(id)))
    }

    /**
     * Everything this session sends, in order: one coroutine drains it, so input, resizes and acks
     * reach the engine in the order they were made. (A coroutine per message would let a late
     * resize overtake the final one.)
     */
    private val outbox = Channel<ClientMsg.Builder>(OUTBOX_CAPACITY)

    init {
        registry.launch {
            for (msg in outbox) {
                val reply =
                    try {
                        conn.post(msg)
                    } catch (e: EngineClosedException) {
                        engineGone(e.message ?: "engine closed")
                        continue
                    }
                registry.launch {
                    try {
                        reply.await().expect(EngineMsg.BodyCase.ACK)
                    } catch (e: EngineClosedException) {
                        engineGone(e.message ?: "engine closed")
                    } catch (e: EngineException) {
                        // e.g. input refused while the session is busy; the session goes on.
                        registry.log.warn("session", "request refused: ${e.message}", id)
                    }
                }
            }
        }
    }

    /** Queues a request; a refusal is logged and surfaced as the session's detail, not thrown. */
    private fun send(msg: ClientMsg.Builder) {
        val r = outbox.trySend(msg)
        if (r.isFailure && !r.isClosed) {
            registry.log.warn("session", "outbox full; a request was dropped", id)
        }
    }

    internal fun onState(phase: SessionPhase, detail: String) {
        val name = phase.name.removePrefix("SESSION_PHASE_")
        registry.log.info("session", if (detail.isEmpty()) name else "$name: $detail", id)
        _status.value = SessionStatus(phase, detail)
        // Requests already queued still go out; nothing new is accepted.
        if (phase == SessionPhase.SESSION_PHASE_CLOSED) outbox.close()
        if (phase == SessionPhase.SESSION_PHASE_CLOSED) _prompt.value = null
    }

    internal fun onPrompt(p: Prompt) {
        val what =
            when (p) {
                is Prompt.HostKey ->
                    "host key prompt (${p.prompt.algorithm} ${p.prompt.fingerprint})"
                is Prompt.Auth -> "auth prompt (${p.prompt.kind}, ${p.prompt.fieldsCount} field(s))"
            }
        registry.log.info("session", what, id)
        _prompt.value = p
    }

    internal fun onFrame(frame: io.github.franticplant.veil.engine.proto.Frame) {
        try {
            synchronized(screen) { screen.apply(frame) }
        } catch (e: InvalidFrameException) {
            // The engine broke the render contract. Treat it as hostile: stop
            // listening to this session.
            registry.log.log(LogRing.Level.ERROR, "render", "invalid frame: ${e.message}", id)
            send(ClientMsg.newBuilder().setCloseSession(CloseSession.newBuilder().setSessionId(id)))
            onState(SessionPhase.SESSION_PHASE_CLOSED, "engine sent an invalid frame: ${e.message}")
            return
        }
        _screenVersion.value = frame.seq
        send(
            ClientMsg.newBuilder()
                .setFrameAck(FrameAck.newBuilder().setSessionId(id).setSeq(frame.seq))
        )
    }

    internal fun onClipboard(offer: ClipboardOffer) {
        registry.log.info("session", "clipboard offer of ${offer.text.length} chars", id)
        _clipboard.tryEmit(offer)
    }

    internal fun onBell() {
        _bell.tryEmit(Unit)
    }

    internal fun engineGone(reason: String) {
        if (!_status.value.closed) onState(SessionPhase.SESSION_PHASE_CLOSED, reason)
    }
}

/** Requests a session may queue before new ones are dropped (and logged). */
private const val OUTBOX_CAPACITY = 1024
