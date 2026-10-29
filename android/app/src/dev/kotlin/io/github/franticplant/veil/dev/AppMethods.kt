package io.github.franticplant.veil.dev

import android.os.Debug
import android.os.Process
import android.os.SystemClock
import io.github.franticplant.veil.BuildConfig
import io.github.franticplant.veil.CrashRecorder
import io.github.franticplant.veil.VeilApplication
import io.github.franticplant.veil.dev.api.ApiException
import io.github.franticplant.veil.dev.api.ErrorCode
import io.github.franticplant.veil.dev.api.MethodTable
import io.github.franticplant.veil.dev.api.Params
import io.github.franticplant.veil.dev.api.Scope
import io.github.franticplant.veil.dev.api.bool
import io.github.franticplant.veil.dev.api.int
import io.github.franticplant.veil.dev.api.long
import io.github.franticplant.veil.dev.api.method
import io.github.franticplant.veil.dev.api.str
import io.github.franticplant.veil.dev.api.strList
import io.github.franticplant.veil.engine.EngineClosedException
import io.github.franticplant.veil.engine.EngineException
import io.github.franticplant.veil.engine.LogRing
import io.github.franticplant.veil.engine.Prompt
import io.github.franticplant.veil.engine.Session
import io.github.franticplant.veil.engine.Target
import io.github.franticplant.veil.engine.proto.HostKeyDecision
import io.github.franticplant.veil.engine.proto.LogLevel
import io.github.franticplant.veil.engine.proto.SessionPhase
import io.github.franticplant.veil.frontend.KeyEncoder
import io.github.franticplant.veil.frontend.SshKeys
import java.io.File
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/** The methods Veil's dev flavor implements. The dev agent adds its own and forwards these. */
class AppMethods(private val app: VeilApplication) {
    private val registry get() = app.sessions
    private val scenarios = Scenarios(app)
    val table = MethodTable()

    init {
        addRead()
        addContent()
        addOperate()
        addKeys()
        addSettings()
        addMetrics()
        addLifecycle()
        addScenarios()
    }

    // ---- read ---------------------------------------------------------------

    private fun addRead() {
        table.add(
            method(
                "app.info",
                Scope.READ,
                "The Veil build, process (uptime, memory, threads, file descriptors) and engine connection.",
            )
        ) {
            val conn = registry.currentConnection
            JSONObject()
                .put("package", app.packageName)
                .put("versionName", BuildConfig.VERSION_NAME)
                .put("versionCode", BuildConfig.VERSION_CODE)
                .put("flavor", BuildConfig.FLAVOR)
                .put("devTools", BuildConfig.DEV_TOOLS)
                .put("pid", Process.myPid())
                .put("uid", Process.myUid())
                .put("uptimeMs", SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime())
                .put("process", processStats())
                .put(
                    "engine",
                    JSONObject()
                        .put("backend", registry.backendKind)
                        .put("connected", conn != null)
                        .apply {
                            if (conn != null) {
                                put("engineVersion", conn.hello.engineVersion)
                                put("protocolVersion", conn.hello.protocolVersion)
                                put(
                                    "limits",
                                    JSONObject()
                                        .put("maxMessageBytes", conn.hello.limits.maxMessageBytes)
                                        .put("maxInFlight", conn.hello.limits.maxInFlight)
                                        .put(
                                            "maxFramesInFlight",
                                            conn.hello.limits.maxFramesInFlight,
                                        )
                                        .put("maxSessions", conn.hello.limits.maxSessions)
                                        .put("maxInputBytes", conn.hello.limits.maxInputBytes),
                                )
                            }
                        },
                )
                .put("sessions", registry.sessions.value.size)
                .put("liveSessions", registry.sessions.value.count { !it.status.value.closed })
        }

        table.add(
            method(
                "app.exits",
                Scope.READ,
                "Why recent Veil processes ended (Android's exit history: crash, native crash, ANR, " +
                    "low memory, killed, package update, ...) and the stack traces of recorded crashes.",
                false,
                int("max", "At most this many exits (default 10).", min = 1, max = 50),
            )
        ) { p ->
            val am = app.getSystemService(android.app.ActivityManager::class.java)
            val exits = am.getHistoricalProcessExitReasons(null, 0, p.intOr("max", 10))
            val crashes =
                CrashRecorder.dir(app).listFiles()?.sortedByDescending { it.name }.orEmpty()
            JSONObject()
                .put(
                    "exits",
                    JSONArray().apply {
                        for (x in exits) {
                            put(
                                JSONObject()
                                    .put("pid", x.pid)
                                    .put("unixMs", x.timestamp)
                                    .put("reason", exitReason(x.reason))
                                    .put("status", x.status)
                                    .put("description", x.description ?: "")
                                    .put("importance", x.importance)
                                    .put("pssKb", x.pss)
                                    .put("rssKb", x.rss)
                            )
                        }
                    },
                )
                .put(
                    "crashes",
                    JSONArray().apply {
                        for (f in crashes) {
                            put(
                                JSONObject()
                                    .put("file", f.name)
                                    .put("trace", f.readText().take(16_000))
                            )
                        }
                    },
                )
        }

        table.add(
            method(
                "components.health",
                Scope.READ,
                "Checks each component: the app process, the engine (a ping with a 2 s deadline), sessions.",
            )
        ) {
            val engine =
                try {
                    val t0 = SystemClock.elapsedRealtimeNanos()
                    val pong = blocking(2_000) { registry.connection().ping(t0) }
                    JSONObject()
                        .put("ok", true)
                        .put("rttUs", (SystemClock.elapsedRealtimeNanos() - t0) / 1000)
                        .put("engineUptimeMs", pong.engineUptimeMs)
                } catch (e: ApiException) {
                    JSONObject().put("ok", false).put("error", e.message)
                }
            val sessions = registry.sessions.value
            JSONArray()
                .put(JSONObject().put("name", "app").put("ok", true).put("pid", Process.myPid()))
                .put(engine.put("name", "engine"))
                .put(
                    JSONObject()
                        .put("name", "sessions")
                        .put("ok", true)
                        .put("live", sessions.count { !it.status.value.closed })
                        .put("waitingForPrompt", sessions.count { it.prompt.value != null })
                )
        }

        table.add(
            method(
                "app.logs",
                Scope.READ,
                "The app-side log ring (session layer, render checks), oldest first.",
                false,
                long("after", "Return entries with seq greater than this (default 0).", min = 0),
                int("max", "At most this many entries (default 200).", min = 1, max = 1000),
                str("level", "Minimum level.", choices = listOf("debug", "info", "warn", "error")),
                str("component", "Only this component."),
                str("grep", "Only entries containing this text (case-insensitive)."),
            )
        ) { p ->
            val b =
                app.log.read(
                    p.longOr("after", 0),
                    p.intOr("max", 200),
                    LogRing.Level.valueOf(p.stringOr("level", "debug").uppercase()),
                    if (p.has("component")) p.string("component") else null,
                    if (p.has("grep")) p.string("grep") else null,
                )
            JSONObject()
                .put("nextSeq", b.nextSeq)
                .put("dropped", b.dropped)
                .put(
                    "entries",
                    JSONArray().apply {
                        for (e in b.entries) {
                            put(
                                JSONObject()
                                    .put("seq", e.seq)
                                    .put("unixMs", e.unixMs)
                                    .put("level", e.level.name.lowercase())
                                    .put("component", e.component)
                                    .put("session", e.sessionId)
                                    .put("message", e.message)
                            )
                        }
                    },
                )
        }

        table.add(
            method(
                "engine.ping",
                Scope.READ,
                "A protocol round trip to the engine.",
            )
        ) {
            val t0 = SystemClock.elapsedRealtimeNanos()
            val pong = engine { registry.connection().ping(t0) }
            JSONObject()
                .put("rttUs", (SystemClock.elapsedRealtimeNanos() - t0) / 1000)
                .put("engineUptimeMs", pong.engineUptimeMs)
        }

        table.add(
            method(
                "engine.stats",
                Scope.READ,
                "Engine counters: uptime, requests, and per live session its phase, size, bytes and frames.",
            )
        ) {
            Json.stats(engine { registry.engineStats() })
        }

        table.add(
            method(
                "engine.logs",
                Scope.READ,
                "The engine's log ring, oldest first.",
                false,
                long("after", "Return entries with seq greater than this (default 0).", min = 0),
                int("max", "At most this many entries (default 200).", min = 1, max = 1000),
                str("level", "Minimum level.", choices = listOf("debug", "info", "warn", "error")),
                str("grep", "Only entries containing this text (case-insensitive)."),
            )
        ) { p ->
            val level =
                when (p.stringOr("level", "debug")) {
                    "info" -> LogLevel.LOG_LEVEL_INFO
                    "warn" -> LogLevel.LOG_LEVEL_WARN
                    "error" -> LogLevel.LOG_LEVEL_ERROR
                    else -> LogLevel.LOG_LEVEL_DEBUG
                }
            val out =
                Json.logs(
                    engine { registry.engineLogs(p.longOr("after", 0), p.intOr("max", 200), level) }
                )
            if (p.has("grep")) {
                val needle = p.string("grep")
                val all = out.getJSONArray("entries")
                val kept = JSONArray()
                for (i in 0 until all.length()) {
                    val e = all.getJSONObject(i)
                    if (e.optString("message").contains(needle, ignoreCase = true)) kept.put(e)
                }
                out.put("entries", kept)
            }
            out
        }

        table.add(
            method(
                "sessions.list",
                Scope.READ,
                "Every session the app knows, closed ones included until forgotten.",
            )
        ) {
            JSONArray().apply { registry.sessions.value.forEach { put(Json.session(it, false)) } }
        }

        table.add(
            method(
                "sessions.get",
                Scope.READ,
                "One session: state, target, open prompt (labels only), terminal size, modes and title.",
                false,
                long("id", "Session id.", required = true, min = 1),
            )
        ) { p ->
            Json.session(session(p), true)
        }

        table.add(
            method(
                "sessions.waitState",
                Scope.READ,
                "Waits until a session reaches a phase. Fails if it closes first (unless waiting for closed).",
                false,
                long("id", "Session id.", required = true, min = 1),
                str(
                    "phase",
                    "The phase to wait for.",
                    required = true,
                    choices =
                        listOf("connecting", "verifying_host", "authenticating", "open", "closed"),
                ),
                long(
                    "timeoutMs",
                    "Give up after this long (default 30000).",
                    min = 1,
                    max = 120_000,
                ),
            )
        ) { p ->
            val s = session(p)
            val want = SessionPhase.valueOf("SESSION_PHASE_" + p.string("phase").uppercase())
            val st =
                blocking(p.longOr("timeoutMs", 30_000)) {
                    s.status.first { it.phase == want || it.closed }
                }
            if (st.phase != want) {
                throw ApiException(ErrorCode.FAILED, "session closed: ${st.detail}")
            }
            Json.session(s, true)
        }

        table.add(
            method(
                "sessions.waitPrompt",
                Scope.READ,
                "Waits until a session asks for something (host key or credentials) and describes it.",
                false,
                long("id", "Session id.", required = true, min = 1),
                str("kind", "Only this kind.", choices = listOf("hostKey", "auth")),
                long(
                    "timeoutMs",
                    "Give up after this long (default 30000).",
                    min = 1,
                    max = 120_000,
                ),
            )
        ) { p ->
            val s = session(p)
            val kind = if (p.has("kind")) p.string("kind") else null
            val found =
                blocking(p.longOr("timeoutMs", 30_000)) {
                    combine(s.prompt, s.status) { pr, st -> pr to st }
                        .first { (pr, st) ->
                            st.closed ||
                                (pr != null && (kind == null || Json.promptKind(pr) == kind))
                        }
                }
            val pr = found.first
            if (pr == null || (kind != null && Json.promptKind(pr) != kind)) {
                throw ApiException(ErrorCode.FAILED, "session closed: ${found.second.detail}")
            }
            Json.prompt(pr)
        }

        table.add(
            method(
                "hostkeys.list",
                Scope.READ,
                "Trusted host keys: the engine's known_hosts and the frontend's VM pins.",
            )
        ) {
            JSONArray().apply {
                for (k in engine { registry.hostKeys() }) {
                    put(
                        JSONObject()
                            .put("host", k.host)
                            .put("port", k.port)
                            .put("algorithm", k.algorithm)
                            .put("fingerprint", k.fingerprint)
                    )
                }
            }
        }

        table.add(
            method("profiles.list", Scope.READ, "Saved hosts (no credentials are ever stored).")
        ) {
            JSONArray().apply { app.profiles.all.value.forEach { put(Json.target(it)) } }
        }
    }

    // ---- read.content -------------------------------------------------------

    private fun addContent() {
        table.add(
            method(
                "sessions.screen",
                Scope.READ_CONTENT,
                "The screen as text lines, with cursor, title and scroll position.",
                false,
                long("id", "Session id.", required = true, min = 1),
            )
        ) { p ->
            Json.screen(session(p))
        }

        table.add(
            method(
                "sessions.waitFor",
                Scope.READ_CONTENT,
                "Waits until a screen line matches a regular expression; returns the line.",
                false,
                long("id", "Session id.", required = true, min = 1),
                str(
                    "regex",
                    "A Java regular expression, matched against each line.",
                    required = true,
                ),
                long(
                    "timeoutMs",
                    "Give up after this long (default 15000).",
                    min = 1,
                    max = 120_000,
                ),
            )
        ) { p ->
            val s = session(p)
            val regex =
                try {
                    Regex(p.string("regex"))
                } catch (e: IllegalArgumentException) {
                    throw ApiException(ErrorCode.INVALID_PARAMS, "bad regex: ${e.message}")
                }
            val t0 = SystemClock.elapsedRealtime()
            var hit: Pair<Int, String>? = null
            val st =
                blocking(p.longOr("timeoutMs", 15_000)) {
                    combine(s.screenVersion, s.status) { _, st -> st }
                        .first { st ->
                            hit = match(s, regex)
                            hit != null || st.closed
                        }
                }
            val (row, line) =
                hit ?: throw ApiException(ErrorCode.FAILED, "session closed: ${st.detail}")
            JSONObject()
                .put("row", row)
                .put("line", line)
                .put("waitedMs", SystemClock.elapsedRealtime() - t0)
        }
    }

    // ---- operate / secrets --------------------------------------------------

    private fun addOperate() {
        table.add(
            method(
                "sessions.open",
                Scope.OPERATE,
                "Opens a session, from a saved profile (by label) or a host. Returns its id at once; " +
                    "follow with sessions.waitPrompt / waitState.",
                true,
                str("profile", "A saved profile's label."),
                str("host", "Host name or address."),
                int("port", "Port (default 22).", min = 1, max = 65535),
                str("user", "User name."),
                int("cols", "Terminal columns (default 80).", min = 2, max = 500),
                int("rows", "Terminal rows (default 24).", min = 2, max = 300),
                str(
                    "key",
                    "A key name (keys.list) to try before passwords; overrides the profile's.",
                ),
                str("startup", "Typed once the shell is open (e.g. tmux new -A -s main)."),
                bool("reconnect", "Reconnect by itself when the connection drops."),
                bool("allowClassicalKex", "Accept servers without post-quantum key exchange."),
            )
        ) { p ->
            val target =
                if (p.has("profile")) {
                        val label = p.string("profile")
                        app.profiles.all.value.firstOrNull {
                            it.label == label || it.displayName == label
                        } ?: throw ApiException(ErrorCode.NOT_FOUND, "no profile '$label'")
                    } else {
                        if (!p.has("host") || !p.has("user")) {
                            throw ApiException(
                                ErrorCode.INVALID_PARAMS,
                                "give either profile, or host and user",
                            )
                        }
                        Target(p.string("host"), p.intOr("port", 22), p.string("user"))
                    }
                    .let { if (p.has("key")) it.copy(keyId = p.string("key")) else it }
                    .let { if (p.has("startup")) it.copy(startup = p.string("startup")) else it }
                    .let {
                        if (p.has("reconnect"))
                            it.copy(autoReconnect = p.boolOr("reconnect", false))
                        else it
                    }
                    .let {
                        if (p.has("allowClassicalKex"))
                            it.copy(allowClassicalKex = p.boolOr("allowClassicalKex", false))
                        else it
                    }
            val s = engine { registry.open(target, p.intOr("cols", 80), p.intOr("rows", 24)) }
            app.log.info("dev", "session opened through the dev API", s.id)
            JSONObject().put("id", s.id)
        }

        table.add(
            method(
                "sessions.close",
                Scope.OPERATE,
                "Closes a session (it stays listed, closed, until forgotten).",
                true,
                long("id", "Session id.", required = true, min = 1),
            )
        ) { p ->
            session(p).close()
            null
        }

        table.add(
            method(
                "sessions.forget",
                Scope.OPERATE,
                "Removes a closed session from the list.",
                true,
                long("id", "Session id.", required = true, min = 1),
            )
        ) { p ->
            val s = session(p)
            if (!s.status.value.closed)
                throw ApiException(ErrorCode.FAILED, "session ${s.id} is still open")
            registry.forget(s)
            null
        }

        table.add(
            method(
                "sessions.input",
                Scope.OPERATE,
                "Types into a session: text, then keys, then Enter if asked. Keys are names like " +
                    "enter, tab, esc, up, f5, page_up, ctrl+c, alt+x, shift+tab.",
                true,
                long("id", "Session id.", required = true, min = 1),
                str("text", "Text typed as is."),
                strList("keys", "Key names, typed in order after the text."),
                bool("enter", "Press Enter at the end."),
            )
        ) { p ->
            val s = session(p)
            val appCursor = synchronized(s.screen) { s.screen.modes.appCursor }
            var bytes = ByteArray(0)
            if (p.has("text")) bytes += p.string("text").toByteArray()
            for (k in p.stringListOr("keys").orEmpty()) bytes += Keys.encode(k, appCursor)
            if (p.boolOr("enter", false)) bytes += KeyEncoder.key(KeyEncoder.Key.ENTER)
            if (bytes.isEmpty()) throw ApiException(ErrorCode.INVALID_PARAMS, "nothing to type")
            s.input(bytes)
            JSONObject().put("bytes", bytes.size)
        }

        table.add(
            method(
                "sessions.paste",
                Scope.OPERATE,
                "Pastes text, bracketed if the remote asked for bracketed paste.",
                true,
                long("id", "Session id.", required = true, min = 1),
                str("text", "The text.", required = true),
            )
        ) { p ->
            val s = session(p)
            val bracketed = synchronized(s.screen) { s.screen.modes.bracketedPaste }
            s.input(KeyEncoder.paste(p.string("text"), bracketed))
            JSONObject().put("bracketed", bracketed)
        }

        table.add(
            method(
                "sessions.resize",
                Scope.OPERATE,
                "Resizes a session's terminal.",
                true,
                long("id", "Session id.", required = true, min = 1),
                int("cols", "Columns.", required = true, min = 2, max = 500),
                int("rows", "Rows.", required = true, min = 2, max = 300),
            )
        ) { p ->
            session(p).resize(p.int("cols"), p.int("rows"))
            null
        }

        table.add(
            method(
                "sessions.scroll",
                Scope.OPERATE,
                "Scrolls the view into history (positive lines: older), or back to the live screen.",
                true,
                long("id", "Session id.", required = true, min = 1),
                int("lines", "Lines to scroll.", min = -100_000, max = 100_000),
                bool("bottom", "Return to the live screen."),
            )
        ) { p ->
            val s = session(p)
            if (p.boolOr("bottom", false)) s.scrollToBottom() else s.scroll(p.intOr("lines", 0))
            null
        }

        table.add(
            method(
                "sessions.snapshot",
                Scope.OPERATE,
                "Asks the engine for a full frame (exercises the resync path).",
                true,
                long("id", "Session id.", required = true, min = 1),
            )
        ) { p ->
            session(p).requestSnapshot()
            null
        }

        table.add(
            method(
                "sessions.answerHostKey",
                Scope.OPERATE,
                "Answers the open host-key prompt.",
                true,
                long("id", "Session id.", required = true, min = 1),
                str(
                    "decision",
                    "What to do.",
                    required = true,
                    choices = listOf("reject", "once", "remember"),
                ),
                str("fingerprint", "If given, the prompt's fingerprint must equal it."),
            )
        ) { p ->
            val s = session(p)
            val pr =
                s.prompt.value as? Prompt.HostKey
                    ?: throw ApiException(
                        ErrorCode.NOT_FOUND,
                        "session ${s.id} has no host-key prompt open",
                    )
            if (p.has("fingerprint") && p.string("fingerprint") != pr.prompt.fingerprint) {
                throw ApiException(
                    ErrorCode.FAILED,
                    "fingerprint is ${pr.prompt.fingerprint}, not ${p.string("fingerprint")}; not answered",
                )
            }
            val decision =
                when (p.string("decision")) {
                    "once" -> HostKeyDecision.HOST_KEY_DECISION_ACCEPT_ONCE
                    "remember" -> HostKeyDecision.HOST_KEY_DECISION_ACCEPT_AND_REMEMBER
                    else -> HostKeyDecision.HOST_KEY_DECISION_REJECT
                }
            s.answerHostKey(pr.prompt, decision)
            app.log.info(
                "dev",
                "host key answered through the dev API: ${p.string("decision")}",
                s.id,
            )
            null
        }

        table.add(
            method(
                "sessions.answerAuth",
                Scope.SECRETS,
                "Answers the open credentials prompt: one response per field, or cancel.",
                true,
                long("id", "Session id.", required = true, min = 1),
                strList("responses", "One answer per prompt field.", secret = true),
                bool("cancel", "Give up instead (the session closes)."),
            )
        ) { p ->
            val s = session(p)
            val pr =
                s.prompt.value as? Prompt.Auth
                    ?: throw ApiException(
                        ErrorCode.NOT_FOUND,
                        "session ${s.id} has no credentials prompt open",
                    )
            if (p.boolOr("cancel", false)) {
                s.answerAuth(pr.prompt, null)
            } else {
                val responses =
                    p.stringListOr("responses")
                        ?: throw ApiException(ErrorCode.INVALID_PARAMS, "give responses or cancel")
                if (responses.size != pr.prompt.fieldsCount) {
                    throw ApiException(
                        ErrorCode.INVALID_PARAMS,
                        "the prompt has ${pr.prompt.fieldsCount} field(s), got ${responses.size} response(s)",
                    )
                }
                s.answerAuth(pr.prompt, responses)
            }
            app.log.info("dev", "credentials prompt answered through the dev API", s.id)
            null
        }

        table.add(
            method(
                "hostkeys.forget",
                Scope.OPERATE,
                "Removes the recorded key of a host, so the next connection asks again.",
                true,
                str("host", "Host as recorded.", required = true),
                int("port", "Port (default 22).", min = 1, max = 65535),
            )
        ) { p ->
            engine { registry.forgetHostKey(p.string("host"), p.intOr("port", 22)) }
            null
        }

        table.add(
            method(
                "profiles.save",
                Scope.OPERATE,
                "Creates a profile, or replaces the one with the same label.",
                true,
                str("label", "Unique label.", required = true),
                str("host", "Host name or address.", required = true),
                int("port", "Port (default 22).", min = 1, max = 65535),
                str("user", "User name.", required = true),
                str("key", "A key name (keys.list) to try before passwords."),
            )
        ) { p ->
            val label = p.string("label")
            val t =
                Target(
                    p.string("host"),
                    p.intOr("port", 22),
                    p.string("user"),
                    label,
                    p.stringOr("key", ""),
                )
            app.profiles.save(app.profiles.all.value.firstOrNull { it.label == label }, t)
            Json.target(t)
        }

        table.add(
            method(
                "profiles.delete",
                Scope.OPERATE,
                "Deletes a profile by label.",
                true,
                str("label", "The label.", required = true),
            )
        ) { p ->
            val t =
                app.profiles.all.value.firstOrNull { it.label == p.string("label") }
                    ?: throw ApiException(ErrorCode.NOT_FOUND, "no profile '${p.string("label")}'")
            app.profiles.delete(t)
            null
        }
    }

    // ---- keys -----------------------------------------------------------------

    private fun addKeys() {
        fun json(k: io.github.franticplant.veil.frontend.SshKeys.Key) =
            JSONObject()
                .put("name", k.name)
                .put("publicKey", k.publicKey)
                .put("fingerprint", k.fingerprint)
                .put("security", k.security.name.lowercase())

        table.add(
            method(
                "keys.list",
                Scope.READ,
                "SSH keys in Android Keystore: name, public key, fingerprint, where it lives " +
                    "(strongbox, tee). Private keys are never exported.",
            )
        ) {
            JSONArray().apply { app.sshKeys.keys.value.forEach { put(json(it)) } }
        }
        table.add(
            method(
                "keys.create",
                Scope.OPERATE,
                "Creates an ECDSA P-256 key in Android Keystore.",
                true,
                str("name", "1-40 letters, digits, '.', '_' or '-'.", required = true),
                str(
                    "where",
                    "auto: StrongBox if available; strongbox: require it; tee: TEE only.",
                    choices = listOf("auto", "strongbox", "tee"),
                ),
                bool("strongBox", "Legacy alias: false means tee."),
                bool(
                    "whileUnlocked",
                    "Keystore refuses to use it while the phone is locked (default true).",
                ),
            )
        ) { p ->
            try {
                val where =
                    when (p.stringOr("where", "").lowercase()) {
                        "strongbox" -> SshKeys.Where.STRONGBOX
                        "tee" -> SshKeys.Where.TEE
                        "auto" -> SshKeys.Where.AUTO
                        else ->
                            if (p.boolOr("strongBox", true)) SshKeys.Where.AUTO
                            else SshKeys.Where.TEE
                    }
                json(
                    app.sshKeys.create(
                        p.string("name"),
                        where,
                        p.boolOr("whileUnlocked", true),
                    )
                )
            } catch (e: IllegalArgumentException) {
                throw ApiException(ErrorCode.INVALID_PARAMS, e.message ?: "invalid")
            } catch (e: Exception) {
                throw ApiException(ErrorCode.FAILED, e.message ?: "key generation failed")
            }
        }
        table.add(
            method(
                "keys.delete",
                Scope.OPERATE,
                "Destroys a key. Irreversible: servers that trust only it will refuse logins.",
                true,
                str("name", "The key.", required = true),
            )
        ) { p ->
            if (app.sshKeys.keys.value.none { it.name == p.string("name") }) {
                throw ApiException(ErrorCode.NOT_FOUND, "no key '${p.string("name")}'")
            }
            app.sshKeys.delete(p.string("name"))
            null
        }
    }

    // ---- metrics ------------------------------------------------------------

    private fun addMetrics() {
        table.add(
            method(
                "metrics.sample",
                Scope.READ,
                "One sample of every Veil process (app, engine) and every session's connection: " +
                    "RAM, CPU time, threads, fds; peer address, wire and payload bytes. devd samples " +
                    "this into time series.",
            )
        ) {
            val processes =
                org.json
                    .JSONArray()
                    .put(io.github.franticplant.veil.dev.api.ProcSelf.sample("veil-app"))
            val host = runCatching { blocking(3_000) { registry.backendStats() } }.getOrNull()
            if (host != null) {
                processes.put(
                    JSONObject()
                        .put("name", "veil-vm")
                        .put("pid", host.optLong("pid"))
                        .put("uptimeMs", host.optLong("uptimeMs"))
                        .put("cpuMs", host.optLong("cpuMs"))
                        .put("rssKb", host.optLong("rssKb"))
                        .put("pssKb", host.optLong("pssKb"))
                        .put("threads", host.optInt("threads"))
                        .put("fds", host.optInt("fds"))
                )
            }
            val sessions = org.json.JSONArray()
            val conn = registry.currentConnection
            if (conn != null) {
                val st = engine(3_000) { registry.engineStats() }
                val p = st.process
                if (st.hasProcess() && p.pid != android.os.Process.myPid()) {
                    processes.put(
                        JSONObject()
                            .put("name", "veil-engine")
                            .put("pid", p.pid)
                            .put("uptimeMs", p.uptimeMs)
                            .put("cpuMs", p.cpuUserMs + p.cpuSystemMs)
                            .put("rssKb", p.rssKb)
                            .put("pssKb", if (p.pssKb > 0) p.pssKb else -1)
                            .put("threads", p.threads)
                            .put("fds", p.fds)
                    )
                }
                for (s in st.sessionsList) {
                    val session = registry.get(s.sessionId)
                    sessions.put(
                        JSONObject()
                            .put("id", s.sessionId)
                            .put("host", s.host)
                            .put("port", s.port)
                            .put("peer", s.peerAddress)
                            .put("phase", Json.phase(s.phaseValue))
                            .put("ageMs", s.ageMs)
                            .put("wireIn", s.wireBytesFromRemote)
                            .put("wireOut", s.wireBytesToRemote)
                            .put("payloadIn", s.bytesFromRemote)
                            .put("payloadOut", s.bytesToRemote)
                            .put("framesSent", s.framesSent)
                            .put(
                                "postQuantum",
                                session?.status?.value?.security?.postQuantum ?: JSONObject.NULL,
                            )
                    )
                }
            }
            val uid = android.os.Process.myUid()
            JSONObject()
                .put("unixMs", System.currentTimeMillis())
                .put("engineBackend", registry.backendKind)
                .put("processes", processes)
                .put("sessions", sessions)
                .put("vm", host ?: JSONObject.NULL)
                .put(
                    "uidTraffic",
                    JSONObject()
                        .put("rxBytes", android.net.TrafficStats.getUidRxBytes(uid))
                        .put("txBytes", android.net.TrafficStats.getUidTxBytes(uid)),
                )
        }
    }

    // ---- settings -----------------------------------------------------------

    private fun addSettings() {
        fun json(v: io.github.franticplant.veil.frontend.UiPrefs.Values) =
            JSONObject()
                .put("theme", v.theme)
                .put("extraKeys", v.extraKeys)
                .put("tabsOnTop", v.tabsOnTop)
                .put("keysRows", v.keysRows)
                .put("fullscreen", v.fullscreen)
                .put("coverCutout", v.coverCutout)
                .put("fontSizeSp", v.fontSizeSp.toDouble())
                .put("clipboard", v.clipboard.name.lowercase())
                .put("requirePostQuantum", v.requirePostQuantum)
                .put("reconnectAttempts", v.reconnectAttempts)
                .put("bellVibrate", v.bellVibrate)
                .put("bellFlash", v.bellFlash)
                .put("keysWhileUnlocked", v.keysWhileUnlocked)
                .put("engine", v.engine.name.lowercase())
                .put("vmMemoryMiB", v.vmMemoryMiB)

        table.add(method("settings.get", Scope.READ, "The app's settings.")) {
            json(app.uiPrefs.values.value)
        }
        table.add(
            method(
                "settings.set",
                Scope.OPERATE,
                "Changes settings; only the parameters given. engine applies at the next start " +
                    "(app.restart).",
                true,
                str(
                    "theme",
                    "A theme id.",
                    choices = io.github.franticplant.veil.frontend.Palette.ALL.map { it.id },
                ),
                bool("extraKeys", "Show the keys bar."),
                bool("tabsOnTop", "Keep a tab strip above the terminal."),
                int("keysRows", "Rows in the keys bar (1 or 2).", min = 1, max = 2),
                bool("fullscreen", "Fullscreen terminal."),
                bool("coverCutout", "Fullscreen covers the notch."),
                str(
                    "clipboard",
                    "Remote clipboard policy.",
                    choices = listOf("ask", "allow", "deny"),
                ),
                bool("requirePostQuantum", "Refuse servers without post-quantum key exchange."),
                int("reconnectAttempts", "Reconnect attempts.", min = 1, max = 12),
                bool("bellVibrate", "Vibrate on bell."),
                bool("bellFlash", "Flash on bell."),
                bool("keysWhileUnlocked", "New keys usable only while unlocked."),
                str(
                    "engine",
                    "Where the engine runs (next start): a native process, a Java service " +
                        "process, inside the app, or the Microdroid VM.",
                    choices = listOf("native", "service", "local", "vm"),
                ),
                int(
                    "vmMemoryMiB",
                    "Microdroid guest RAM in MiB (next start; default 128).",
                    min = 32,
                    max = 1024,
                ),
            )
        ) { p ->
            app.uiPrefs.update { v ->
                v.copy(
                    theme = p.stringOr("theme", v.theme),
                    extraKeys = p.boolOr("extraKeys", v.extraKeys),
                    tabsOnTop = p.boolOr("tabsOnTop", v.tabsOnTop),
                    keysRows = p.intOr("keysRows", v.keysRows).coerceIn(1, 2),
                    fullscreen = p.boolOr("fullscreen", v.fullscreen),
                    coverCutout = p.boolOr("coverCutout", v.coverCutout),
                    clipboard =
                        if (p.has("clipboard"))
                            io.github.franticplant.veil.frontend.UiPrefs.ClipboardPolicy.valueOf(
                                p.string("clipboard").uppercase()
                            )
                        else v.clipboard,
                    requirePostQuantum = p.boolOr("requirePostQuantum", v.requirePostQuantum),
                    reconnectAttempts = p.intOr("reconnectAttempts", v.reconnectAttempts),
                    bellVibrate = p.boolOr("bellVibrate", v.bellVibrate),
                    bellFlash = p.boolOr("bellFlash", v.bellFlash),
                    keysWhileUnlocked = p.boolOr("keysWhileUnlocked", v.keysWhileUnlocked),
                    engine =
                        if (p.has("engine"))
                            io.github.franticplant.veil.frontend.UiPrefs.EnginePlace.valueOf(
                                p.string("engine").uppercase()
                            )
                        else v.engine,
                    vmMemoryMiB = p.intOr("vmMemoryMiB", v.vmMemoryMiB).coerceIn(32, 1024),
                )
            }
            json(app.uiPrefs.values.value)
        }
    }

    // ---- lifecycle ----------------------------------------------------------

    private fun addLifecycle() {
        table.add(
            method(
                "engine.restart",
                Scope.LIFECYCLE,
                "Closes the engine connection (every session ends) and opens a fresh engine.",
                true,
            )
        ) {
            blocking(10_000) { registry.restartEngine("requested through the dev API") }
            val t0 = SystemClock.elapsedRealtimeNanos()
            engine { registry.connection().ping(t0) }
            JSONObject().put("engineVersion", registry.currentConnection?.hello?.engineVersion)
        }

        table.add(
            method(
                "engine.useNative",
                Scope.LIFECYCLE,
                "Explicitly switches this process to the native engine. Refuses if a live session " +
                    "would be lost; persists native for the next process start.",
                true,
            )
        ) {
            if (!app.useNativeEngine()) {
                throw ApiException(ErrorCode.FAILED, "close live sessions before switching")
            }
            JSONObject().put("engine", app.backendKind)
        }

        table.add(
            method(
                "app.exit",
                Scope.LIFECYCLE,
                "Ends the Veil process shortly after replying. Sessions end; the dev agent starts it " +
                    "again by rebinding.",
                true,
            )
        ) {
            app.log.warn("dev", "process exit requested through the dev API")
            Thread {
                Thread.sleep(300)
                Process.killProcess(Process.myPid())
            }
                .start()
            JSONObject().put("pid", Process.myPid())
        }
    }

    // ---- scenarios ----------------------------------------------------------

    private fun addScenarios() {
        table.add(method("scenario.list", Scope.SCENARIO, "The built-in test scenarios.")) {
            JSONArray().apply {
                for ((name, about) in Scenarios.ALL) put(
                    JSONObject().put("name", name).put("description", about)
                )
            }
        }
        table.add(
            method(
                "scenario.run",
                Scope.SCENARIO,
                "Runs a built-in scenario against a test host and returns a step-by-step report. " +
                    "Passing a password needs the secrets scope.",
                true,
                str("name", "Scenario.", required = true, choices = Scenarios.ALL.keys.toList()),
                str("profile", "A saved profile's label (or give host and user)."),
                str("host", "Test host."),
                int("port", "Port (default 22).", min = 1, max = 65535),
                str("user", "User name."),
                str(
                    "password",
                    "Password for password or keyboard-interactive auth.",
                    secret = true,
                ),
                str(
                    "hostKey",
                    "How to answer a host-key prompt (default once; reject makes it fail).",
                    choices = listOf("once", "remember", "reject"),
                ),
                int(
                    "count",
                    "Repetitions, where the scenario has any (resize-storm, reconnect).",
                    min = 1,
                    max = 1000,
                ),
                long(
                    "bytes",
                    "Output size for throughput (default 1000000).",
                    min = 1,
                    max = 100_000_000,
                ),
                long(
                    "timeoutMs",
                    "Deadline for the whole run (default 60000).",
                    min = 1_000,
                    max = 600_000,
                ),
                str("key", "A key name (keys.list) to log in with before any password."),
            )
        ) { p ->
            scenarios.run(p)
        }
    }

    // ---- helpers ------------------------------------------------------------

    private fun session(p: Params): Session {
        val id = p.long("id")
        return registry.get(id) ?: throw ApiException(ErrorCode.NOT_FOUND, "no session $id")
    }

    private fun match(s: Session, regex: Regex): Pair<Int, String>? {
        val lines = synchronized(s.screen) { s.screen.text() }
        for ((i, l) in lines.withIndex()) if (regex.containsMatchIn(l)) return i to l
        return null
    }

    private fun exitReason(r: Int): String =
        when (r) {
            android.app.ApplicationExitInfo.REASON_ANR -> "anr"
            android.app.ApplicationExitInfo.REASON_CRASH -> "crash"
            android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash-native"
            android.app.ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency-died"
            android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE ->
                "excessive-resource-usage"
            android.app.ApplicationExitInfo.REASON_EXIT_SELF -> "exit-self"
            android.app.ApplicationExitInfo.REASON_FREEZER -> "freezer"
            android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE ->
                "initialization-failure"
            android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "low-memory"
            android.app.ApplicationExitInfo.REASON_OTHER -> "other"
            android.app.ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "package-state-change"
            android.app.ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "package-updated"
            android.app.ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission-change"
            android.app.ApplicationExitInfo.REASON_SIGNALED -> "signaled"
            android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> "user-requested"
            android.app.ApplicationExitInfo.REASON_USER_STOPPED -> "user-stopped"
            else -> "unknown($r)"
        }

    private fun processStats(): JSONObject {
        val rt = Runtime.getRuntime()
        val status = readStatus()
        val fds = File("/proc/self/fd").list()?.size ?: -1
        return JSONObject()
            .put("javaHeapUsedKb", (rt.totalMemory() - rt.freeMemory()) / 1024)
            .put("javaHeapMaxKb", rt.maxMemory() / 1024)
            .put("nativeHeapKb", Debug.getNativeHeapAllocatedSize() / 1024)
            .put("pssKb", Debug.getPss())
            .put("rssKb", status["VmRSS"]?.substringBefore(' ')?.toLongOrNull() ?: -1)
            .put("threads", status["Threads"]?.toIntOrNull() ?: -1)
            .put("fds", fds)
    }

    private fun readStatus(): Map<String, String> =
        try {
            File("/proc/self/status").readLines().associate {
                it.substringBefore(':') to it.substringAfter(':').trim()
            }
        } catch (e: java.io.IOException) {
            emptyMap()
        }
}

/** Runs [block] with a deadline, turning a timeout into an [ApiException]. */
internal fun <T> blocking(timeoutMs: Long, block: suspend () -> T): T =
    try {
        runBlocking { withTimeout(timeoutMs) { block() } }
    } catch (e: TimeoutCancellationException) {
        throw ApiException(ErrorCode.TIMEOUT, "nothing after ${timeoutMs} ms")
    }

/** Runs an engine request, mapping engine failures to API errors. */
internal fun <T> engine(timeoutMs: Long = 10_000, block: suspend () -> T): T =
    try {
        blocking(timeoutMs, block)
    } catch (e: EngineClosedException) {
        throw ApiException(ErrorCode.UNAVAILABLE, "engine connection closed: ${e.message}")
    } catch (e: EngineException) {
        throw ApiException(ErrorCode.FAILED, e.message ?: "engine error")
    }
