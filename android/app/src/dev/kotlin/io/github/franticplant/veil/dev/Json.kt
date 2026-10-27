package io.github.franticplant.veil.dev

import io.github.franticplant.veil.dev.api.ApiException
import io.github.franticplant.veil.dev.api.ErrorCode
import io.github.franticplant.veil.engine.Prompt
import io.github.franticplant.veil.engine.Session
import io.github.franticplant.veil.engine.Target
import io.github.franticplant.veil.engine.proto.AuthKind
import io.github.franticplant.veil.engine.proto.EngineStats
import io.github.franticplant.veil.engine.proto.LogBatch
import io.github.franticplant.veil.engine.proto.MouseMode
import io.github.franticplant.veil.engine.proto.SessionPhase
import io.github.franticplant.veil.frontend.KeyEncoder
import org.json.JSONArray
import org.json.JSONObject

/** Protocol and session-layer values as dev API JSON. */
internal object Json {
    fun phase(p: SessionPhase): String = p.name.removePrefix("SESSION_PHASE_").lowercase()

    fun phase(n: Int): String = SessionPhase.forNumber(n)?.let { phase(it) } ?: "unknown($n)"

    fun target(t: Target): JSONObject =
        JSONObject()
            .put("label", t.label)
            .put("host", t.host)
            .put("port", t.port)
            .put("user", t.username)
            .put("display", t.displayName)

    fun promptKind(p: Prompt): String =
        when (p) {
            is Prompt.HostKey -> "hostKey"
            is Prompt.Auth -> "auth"
        }

    /** A prompt's description. Labels are server-supplied text; no answers are ever included. */
    fun prompt(p: Prompt): JSONObject =
        when (p) {
            is Prompt.HostKey ->
                JSONObject()
                    .put("kind", "hostKey")
                    .put("promptId", p.prompt.promptId)
                    .put("host", p.prompt.host)
                    .put("port", p.prompt.port)
                    .put("algorithm", p.prompt.algorithm)
                    .put("fingerprint", p.prompt.fingerprint)
                    .put(
                        "status",
                        p.prompt.status.name.removePrefix("HOST_KEY_STATUS_").lowercase(),
                    )
            is Prompt.Auth ->
                JSONObject()
                    .put("kind", "auth")
                    .put("promptId", p.prompt.promptId)
                    .put(
                        "authKind",
                        when (p.prompt.kind) {
                            AuthKind.AUTH_KIND_PASSWORD -> "password"
                            AuthKind.AUTH_KIND_KEYBOARD_INTERACTIVE -> "keyboard-interactive"
                            else -> "unknown"
                        },
                    )
                    .put("username", p.prompt.username)
                    .put("name", p.prompt.name)
                    .put("instruction", p.prompt.instruction)
                    .put("retry", p.prompt.retry)
                    .put(
                        "fields",
                        JSONArray().apply {
                            for (f in p.prompt.fieldsList) {
                                put(JSONObject().put("label", f.label).put("echo", f.echo))
                            }
                        },
                    )
        }

    fun session(s: Session, detailed: Boolean): JSONObject {
        val st = s.status.value
        val o =
            JSONObject()
                .put("id", s.id)
                .put("target", target(s.target))
                .put("phase", phase(st.phase))
                .put("detail", st.detail)
                .put(
                    "security",
                    st.security?.let {
                        JSONObject()
                            .put("kex", it.kex)
                            .put("postQuantum", it.postQuantum)
                            .put("cipher", it.cipher)
                            .put("mac", it.mac)
                            .put("hostKeyAlgorithm", it.hostKeyAlgorithm)
                    } ?: JSONObject.NULL,
                )
                .put(
                    "prompt",
                    s.prompt.value?.let { if (detailed) prompt(it) else promptKind(it) }
                        ?: JSONObject.NULL,
                )
        synchronized(s.screen) {
            val m = s.screen
            o.put("cols", m.cols).put("rows", m.rows).put("title", m.title).put("frameSeq", m.seq)
            if (detailed) {
                o.put(
                    "modes",
                    JSONObject()
                        .put("appCursor", m.modes.appCursor)
                        .put("appKeypad", m.modes.appKeypad)
                        .put("bracketedPaste", m.modes.bracketedPaste)
                        .put(
                            "mouse",
                            when (m.modes.mouse) {
                                MouseMode.MOUSE_MODE_CLICK -> "click"
                                MouseMode.MOUSE_MODE_DRAG -> "drag"
                                MouseMode.MOUSE_MODE_MOTION -> "motion"
                                else -> "none"
                            },
                        )
                        .put("mouseSgr", m.modes.mouseSgr)
                        .put("altScreen", m.modes.altScreen),
                )
                o.put("scrollOffset", m.scrollOffset).put("historyLines", m.historyLines)
            }
        }
        return o
    }

    fun screen(s: Session): JSONObject =
        synchronized(s.screen) {
            val m = s.screen
            JSONObject()
                .put("id", s.id)
                .put("cols", m.cols)
                .put("rows", m.rows)
                .put("frameSeq", m.seq)
                .put("title", m.title)
                .put(
                    "cursor",
                    JSONObject()
                        .put("row", m.cursor.row)
                        .put("col", m.cursor.col)
                        .put("visible", m.cursor.visible),
                )
                .put("scrollOffset", m.scrollOffset)
                .put("historyLines", m.historyLines)
                .put("lines", JSONArray(m.text()))
        }

    fun stats(st: EngineStats): JSONObject =
        JSONObject()
            .put("uptimeMs", st.uptimeMs)
            .put("sessionsOpened", st.sessionsOpened)
            .put("requests", st.requests)
            .put("malformedRequests", st.malformedRequests)
            .put("lastLogSeq", st.lastLogSeq)
            .put(
                "sessions",
                JSONArray().apply {
                    for (s in st.sessionsList) {
                        put(
                            JSONObject()
                                .put("id", s.sessionId)
                                .put("host", s.host)
                                .put("port", s.port)
                                .put("user", s.username)
                                .put("phase", phase(s.phaseValue))
                                .put("cols", s.size.cols)
                                .put("rows", s.size.rows)
                                .put("ageMs", s.ageMs)
                                .put("bytesFromRemote", s.bytesFromRemote)
                                .put("bytesToRemote", s.bytesToRemote)
                                .put("framesSent", s.framesSent)
                                .put("framesAcked", s.framesAcked)
                        )
                    }
                },
            )

    fun logs(b: LogBatch): JSONObject =
        JSONObject()
            .put("nextSeq", b.nextSeq)
            .put("dropped", b.dropped)
            .put(
                "entries",
                JSONArray().apply {
                    for (e in b.entriesList) {
                        put(
                            JSONObject()
                                .put("seq", e.seq)
                                .put("unixMs", e.unixMs)
                                .put("level", e.level.name.removePrefix("LOG_LEVEL_").lowercase())
                                .put("component", e.component)
                                .put("session", e.sessionId)
                                .put("message", e.message)
                        )
                    }
                },
            )
}

/** Key names for sessions.input. */
internal object Keys {
    private val named =
        mapOf(
            "enter" to KeyEncoder.Key.ENTER,
            "return" to KeyEncoder.Key.ENTER,
            "tab" to KeyEncoder.Key.TAB,
            "backspace" to KeyEncoder.Key.BACKSPACE,
            "esc" to KeyEncoder.Key.ESCAPE,
            "escape" to KeyEncoder.Key.ESCAPE,
            "up" to KeyEncoder.Key.UP,
            "down" to KeyEncoder.Key.DOWN,
            "left" to KeyEncoder.Key.LEFT,
            "right" to KeyEncoder.Key.RIGHT,
            "home" to KeyEncoder.Key.HOME,
            "end" to KeyEncoder.Key.END,
            "page_up" to KeyEncoder.Key.PAGE_UP,
            "pgup" to KeyEncoder.Key.PAGE_UP,
            "page_down" to KeyEncoder.Key.PAGE_DOWN,
            "pgdn" to KeyEncoder.Key.PAGE_DOWN,
            "insert" to KeyEncoder.Key.INSERT,
            "delete" to KeyEncoder.Key.DELETE,
            "del" to KeyEncoder.Key.DELETE,
        ) + (1..12).associate { "f$it" to KeyEncoder.Key.valueOf("F$it") }

    /** Encodes one key name such as "ctrl+c", "alt+x", "shift+tab", "f5" or "space". */
    fun encode(name: String, appCursor: Boolean): ByteArray {
        val parts = name.lowercase().split('+')
        var mods = KeyEncoder.Mods()
        for (m in parts.dropLast(1)) {
            mods =
                when (m) {
                    "ctrl",
                    "control" -> mods.copy(ctrl = true)
                    "alt",
                    "meta" -> mods.copy(alt = true)
                    "shift" -> mods.copy(shift = true)
                    else ->
                        throw ApiException(
                            ErrorCode.INVALID_PARAMS,
                            "unknown modifier '$m' in '$name'",
                        )
                }
        }
        val key = parts.last()
        named[key]?.let {
            return KeyEncoder.key(it, mods, appCursor)
        }
        val ch =
            when {
                key == "space" -> " "
                key.codePointCount(0, key.length) == 1 -> key
                else -> throw ApiException(ErrorCode.INVALID_PARAMS, "unknown key '$name'")
            }
        return KeyEncoder.text(ch, mods)
    }
}
