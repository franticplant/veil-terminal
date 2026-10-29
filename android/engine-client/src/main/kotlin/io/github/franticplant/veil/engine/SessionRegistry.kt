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
import io.github.franticplant.veil.engine.proto.HostKeyStatus
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
import kotlinx.coroutines.flow.first
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
    /** Typed once the shell is open, e.g. "tmux new -A -s main"; empty for none. */
    val startup: String = "",
    /** Reconnect when the connection drops (not when closed by the user or the shell exits). */
    val autoReconnect: Boolean = false,
    /** Accept a classical key exchange from servers without a post-quantum one. */
    val allowClassicalKex: Boolean = false,
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
    /**
     * Host-key pins held by the frontend. The VM payload cannot read Android storage, so it asks
     * for every host key; this store answers known pins and records new ones on "remember".
     */
    private val knownHosts: KnownHostsStore? = null,
    /** The user's defaults, read at each use (they can change in settings). */
    private val policy: () -> SessionPolicy = { SessionPolicy() },
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

    /** Counters from a supervising backend (the VM host), if it has any. */
    suspend fun backendStats(): org.json.JSONObject? = backend.hostStats()

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

    /**
     * Keys recorded by the engine plus pins remembered by the frontend. The two lists can overlap
     * when native mode's known_hosts and the VM's frontend pins both hold a key.
     */
    suspend fun hostKeys(): List<HostKeyEntry> {
        val engineEntries =
            try {
                // Do not boot a VM just to open the known-hosts screen. Native backends have no
                // such cost, and listing their file is useful before the first session.
                val connection = currentConnection ?: if (backend.kind == "vm") null else connection()
                connection
                    ?.request(
                        ClientMsg.newBuilder().setListHostKeys(ListHostKeys.getDefaultInstance())
                    )
                    ?.expect(EngineMsg.BodyCase.HOST_KEY_LIST)
                    ?.hostKeyList
                    ?.entriesList
                    .orEmpty()
            } catch (e: Exception) {
                log.warn("hostkeys", "could not list engine host keys: ${e.message}")
                emptyList()
            }
        val frontend =
            knownHosts?.list().orEmpty().map {
                HostKeyEntry.newBuilder()
                    .setHost(it.host)
                    .setPort(it.port)
                    .setAlgorithm(it.algorithm)
                    .setFingerprint(it.fingerprint)
                    .build()
            }
        return (engineEntries + frontend).distinctBy {
            listOf(it.host, it.port, it.algorithm, it.fingerprint)
        }
    }

    suspend fun forgetHostKey(host: String, port: Int) {
        knownHosts?.forget(host, port)
        try {
            val connection = currentConnection ?: if (backend.kind == "vm") null else connection()
            connection
                ?.request(
                    ClientMsg.newBuilder()
                        .setForgetHostKey(ForgetHostKey.newBuilder().setHost(host).setPort(port))
                )
                ?.expect(EngineMsg.BodyCase.ACK)
        } catch (e: Exception) {
            log.warn("hostkeys", "forgot the frontend pin but not the engine key: ${e.message}")
        }
        log.info("hostkeys", "forgot the key of $host:$port")
    }

    /**
     * A host-key prompt from the engine. A pin the frontend already holds is answered without a
     * dialog; a different key for the same host is refused, exactly as OpenSSH refuses a changed
     * key. Only an unknown host reaches the UI.
     */
    private fun answerFromPins(session: Session, prompt: HostKeyPrompt) {
        val store = knownHosts
        if (store == null || prompt.status != HostKeyStatus.HOST_KEY_STATUS_UNKNOWN) {
            session.onPrompt(Prompt.HostKey(prompt))
            return
        }
        when (val check = store.check(prompt.host, prompt.port, prompt.algorithm, prompt.fingerprint)) {
            is KnownHostsStore.Check.Known -> {
                log.info(
                    "hostkeys",
                    "known ${prompt.algorithm} ${prompt.fingerprint} for ${prompt.host}:${prompt.port}",
                    session.id,
                )
                session.sendHostKeyAnswer(prompt, HostKeyDecision.HOST_KEY_DECISION_ACCEPT_ONCE)
            }
            is KnownHostsStore.Check.Changed -> {
                log.warn(
                    "hostkeys",
                    "HOST KEY CHANGED for ${prompt.host}:${prompt.port}: recorded ${check.pin.algorithm} " +
                        "${check.pin.fingerprint}, server presented ${prompt.algorithm} " +
                        "${prompt.fingerprint}; refusing. Forget it in Known hosts only if you know " +
                        "why it changed.",
                    session.id,
                )
                session.sendHostKeyAnswer(prompt, HostKeyDecision.HOST_KEY_DECISION_REJECT)
            }
            KnownHostsStore.Check.Unknown -> session.onPrompt(Prompt.HostKey(prompt))
        }
    }

    /** Applies "remember" locally before forwarding the decision to the engine. */
    internal fun answerHostKey(
        session: Session,
        prompt: HostKeyPrompt,
        decision: HostKeyDecision,
    ) {
        if (decision == HostKeyDecision.HOST_KEY_DECISION_ACCEPT_AND_REMEMBER) {
            knownHosts?.let {
                try {
                    it.remember(
                        prompt.host,
                        prompt.port,
                        prompt.algorithm,
                        prompt.fingerprint,
                    )
                } catch (e: Exception) {
                    log.warn(
                        "hostkeys",
                        "could not remember ${prompt.host}:${prompt.port}: ${e.message}",
                        session.id,
                    )
                }
            }
        }
        session.sendHostKeyAnswer(prompt, decision)
    }

    /** The session with [id], if this registry knows it. */
    fun get(id: Long): Session? = byId[id]

    private val _replaced = MutableSharedFlow<Pair<Long, Long>>(extraBufferCapacity = 8)

    /** (old, new) session ids: a dropped session that reconnected by itself. */
    val replaced: SharedFlow<Pair<Long, Long>> = _replaced

    /**
     * Opens a session to [target] with a [cols] x [rows] terminal. If the target asks for it, a
     * session that drops is replaced by a new one, with growing pauses between attempts.
     */
    suspend fun open(target: Target, cols: Int, rows: Int): Session {
        val s = openOnce(target, cols, rows)
        if (target.autoReconnect) watch(s)
        return s
    }

    private fun watch(s: Session) {
        scope.launch {
            val st = s.status.first { it.closed }
            if (!st.dropped) return@launch
            val (cols, rows) = synchronized(s.screen) { s.screen.cols to s.screen.rows }
            val pauses = RECONNECT_PAUSES_MS.take(policy().reconnectAttempts.coerceIn(1, 12))
            for ((attempt, pause) in pauses.withIndex()) {
                log.info(
                    "session",
                    "connection dropped; reconnecting in ${pause / 1000} s (attempt ${attempt + 1})",
                    s.id,
                )
                kotlinx.coroutines.delay(pause)
                val next =
                    try {
                        openOnce(s.target, cols.coerceAtLeast(2), rows.coerceAtLeast(2))
                    } catch (e: Exception) {
                        log.warn("session", "reconnect failed: ${e.message}", s.id)
                        continue
                    }
                forget(s)
                _replaced.tryEmit(s.id to next.id)
                val r =
                    next.status.first { it.phase == SessionPhase.SESSION_PHASE_OPEN || it.closed }
                if (!r.closed) {
                    log.info("session", "reconnected as session ${next.id}", s.id)
                    watch(next)
                    return@launch
                }
                if (!r.dropped) return@launch
                forget(next)
            }
            log.warn("session", "gave up reconnecting", s.id)
        }
    }

    private suspend fun openOnce(target: Target, cols: Int, rows: Int): Session {
        val c = connection()
        val connectionId = backend.prepare(target.host, target.port)
        val reply =
            try {
                c.request(
                    ClientMsg.newBuilder()
                        .setOpenSession(
                            OpenSession.newBuilder()
                                .setHost(target.host)
                                .setPort(target.port)
                                .setUsername(target.username)
                                .setSize(TermSize.newBuilder().setCols(cols).setRows(rows))
                                .addAllPublicKeys(publicKeysFor(target))
                                .setAllowClassicalKex(
                                    target.allowClassicalKex || !policy().requirePostQuantum
                                )
                        )
                )
            } catch (e: Exception) {
                releaseConnection(connectionId)
                throw e
            }
        val id = reply.expect(EngineMsg.BodyCase.SESSION_OPENED).sessionOpened.sessionId
        log.info("session", "opened ${target.displayName} (${cols}x$rows)", id)
        val session = Session(id, target, c, this, connectionId)
        if (target.startup.isNotBlank()) {
            scope.launch {
                val st =
                    session.status.first {
                        it.phase == SessionPhase.SESSION_PHASE_OPEN || it.closed
                    }
                if (!st.closed) {
                    session.input((target.startup.trim() + "\r").toByteArray())
                    log.info("session", "ran the startup command", id)
                }
            }
        }
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
                    if (msg.sessionState.hasSecurity()) msg.sessionState.security else null,
                )
            EngineMsg.BodyCase.HOST_KEY_PROMPT -> {
                val prompt = msg.hostKeyPrompt
                val session = byId[prompt.sessionId]
                if (session != null) {
                    answerFromPins(session, prompt)
                }
            }
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

    /** Releases a backend connection after its session has closed. */
    internal fun releaseConnection(connectionId: Long?) {
        if (connectionId == null) return
        scope.launch { runCatching { backend.closeConnection(connectionId) } }
    }
}

/**
 * Text a remote offered for the clipboard (OSC 52). Remote-controlled: whether it reaches the
 * clipboard is the frontend's decision, and it must be shown as plain text.
 */
data class ClipboardOffer(val text: String, val primary: Boolean)

/** Pauses before successive reconnect attempts (the first [SessionPolicy.reconnectAttempts]). */
private val RECONNECT_PAUSES_MS =
    listOf(
        2_000L,
        5_000L,
        15_000L,
        30_000L,
        30_000L,
        60_000L,
        60_000L,
        120_000L,
        120_000L,
        300_000L,
        300_000L,
        300_000L,
    )

/** Session behavior the user can change in settings; hosts can override some of it. */
data class SessionPolicy(
    /** Refuse servers without post-quantum key exchange, unless the host allows classical. */
    val requirePostQuantum: Boolean = true,
    /** Reconnect attempts after a drop, for hosts with reconnect on. */
    val reconnectAttempts: Int = 6,
)

/** Something the engine needs a human to decide. */
sealed interface Prompt {
    data class HostKey(val prompt: HostKeyPrompt) : Prompt

    data class Auth(val prompt: AuthPrompt) : Prompt
}

/** Where a session is in its life; [detail] explains a CLOSED one. */
data class SessionStatus(
    val phase: SessionPhase,
    val detail: String = "",
    /** What the connection negotiated; set once it is open. */
    val security: io.github.franticplant.veil.engine.proto.ConnectionSecurity? = null,
) {
    val closed: Boolean
        get() = phase == SessionPhase.SESSION_PHASE_CLOSED

    /**
     * Closed because the connection went away (network, timeout, engine), not by the user, the
     * shell exiting, or a refusal (host key, authentication) that retrying would not fix.
     */
    val dropped: Boolean
        get() =
            closed &&
                detail != "closed" &&
                !detail.startsWith("the remote shell exited") &&
                !detail.startsWith("HOST KEY") &&
                !detail.contains("host key", ignoreCase = true) &&
                !detail.contains("authentication", ignoreCase = true) &&
                !detail.contains("invalid frame")
}

/** One SSH session, as the frontend sees it. */
class Session
internal constructor(
    val id: Long,
    val target: Target,
    private val conn: EngineConnection,
    private val registry: SessionRegistry,
    private val connectionId: Long? = null,
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
        registry.answerHostKey(this, prompt, decision)
    }

    /** Sends the final decision; [answerHostKey] applies the frontend pin first. */
    internal fun sendHostKeyAnswer(prompt: HostKeyPrompt, decision: HostKeyDecision) {
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

    internal fun onState(
        phase: SessionPhase,
        detail: String,
        security: io.github.franticplant.veil.engine.proto.ConnectionSecurity? = null,
    ) {
        val name = phase.name.removePrefix("SESSION_PHASE_")
        registry.log.info("session", if (detail.isEmpty()) name else "$name: $detail", id)
        if (security != null) {
            registry.log.info(
                "session",
                "key exchange ${security.kex} (${if (security.postQuantum) "post-quantum" else "classical"})",
                id,
            )
        }
        _status.value = SessionStatus(phase, detail, security ?: _status.value.security)
        // Requests already queued still go out; nothing new is accepted.
        if (phase == SessionPhase.SESSION_PHASE_CLOSED) {
            outbox.close()
            _prompt.value = null
            // Relay close is disabled until the host/guest stream teardown is
            // fixed: closing the relay currently tears down the engine stream.
        }
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
