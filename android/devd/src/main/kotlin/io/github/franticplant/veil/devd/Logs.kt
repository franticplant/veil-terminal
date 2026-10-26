package io.github.franticplant.veil.devd

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * devd's log (listener, pairing, updates, hooks). Entries go to the [Journal], so they persist and
 * are hash-chained, and to logcat.
 */
class DevdLog(private val journal: Journal) {
    fun log(level: String, component: String, message: String) {
        journal.append(
            JSONObject()
                .put("kind", "log")
                .put("level", level)
                .put("component", component)
                .put("message", message)
        )
        val tag = "devd/$component"
        when (level) {
            "warn" -> Log.w(tag, message)
            "error" -> Log.e(tag, message)
            else -> Log.i(tag, message)
        }
    }

    fun info(component: String, message: String) = log("info", component, message)

    fun warn(component: String, message: String) = log("warn", component, message)
}

/** One API call, as the access log records it: parameter names only, never values. */
data class Access(
    val client: String,
    val method: String,
    val params: List<String>,
    val outcome: String,
    val ms: Long,
    /** The listener address the call arrived on, and the peer's address. */
    val local: String,
    val peer: String,
)

/**
 * The access log: one journal entry per call. The API can read it; nothing but the server writes
 * it, and nothing can clear it.
 */
class AccessLog(private val journal: Journal) {
    fun add(a: Access) {
        journal.append(
            JSONObject()
                .put("kind", "access")
                .put("level", if (a.outcome == "ok") "info" else "warn")
                .put("component", "api")
                .put("client", a.client)
                .put("method", a.method)
                .put("params", JSONArray(a.params))
                .put("outcome", a.outcome)
                .put("ms", a.ms)
                .put("local", a.local)
                .put("peer", a.peer)
        )
    }
}

/** Journal filters shared by the API and the log viewer. */
object JournalFilter {
    private val LEVELS = listOf("debug", "info", "warn", "error")

    fun of(
        kind: String = "all",
        minLevel: String? = null,
        component: String? = null,
        grep: String? = null,
    ): (JSONObject) -> Boolean {
        val min = minLevel?.let { LEVELS.indexOf(it) } ?: 0
        return { e ->
            (kind == "all" || e.optString("kind") == kind) &&
                LEVELS.indexOf(e.optString("level", "info")) >= min &&
                (component == null || e.optString("component") == component) &&
                (grep == null || searchText(e).contains(grep, ignoreCase = true))
        }
    }

    /** The fields a search looks at (not hashes). */
    fun searchText(e: JSONObject): String =
        listOf("component", "message", "client", "method", "outcome", "peer", "local").joinToString(
            " "
        ) {
            e.optString(it)
        }
}
