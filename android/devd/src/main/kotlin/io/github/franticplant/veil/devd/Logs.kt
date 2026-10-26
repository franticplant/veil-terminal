package io.github.franticplant.veil.devd

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** devd's own bounded log (listener, pairing, updates), also written to logcat. */
class DevdLog(private val capacity: Int = 2000) {
    private val entries = ArrayDeque<JSONObject>(capacity)
    private var nextSeq = 1L

    fun log(level: String, component: String, message: String) {
        synchronized(this) {
            if (entries.size == capacity) entries.removeFirst()
            entries.addLast(
                JSONObject()
                    .put("seq", nextSeq++)
                    .put("unixMs", System.currentTimeMillis())
                    .put("level", level)
                    .put("component", component)
                    .put("message", message)
            )
        }
        val tag = "devd/$component"
        when (level) {
            "warn" -> Log.w(tag, message)
            "error" -> Log.e(tag, message)
            else -> Log.i(tag, message)
        }
    }

    fun info(component: String, message: String) = log("info", component, message)

    fun warn(component: String, message: String) = log("warn", component, message)

    fun read(after: Long, max: Int, grep: String?): JSONObject =
        synchronized(this) {
            val out = JSONArray()
            var next = after
            for (e in entries) {
                if (e.getLong("seq") <= after) continue
                if (out.length() == max) break
                next = e.getLong("seq")
                if (grep != null && !e.getString("message").contains(grep, ignoreCase = true))
                    continue
                out.put(e)
            }
            JSONObject().put("entries", out).put("nextSeq", next)
        }
}

/** One authenticated (or refused) API call. Parameter names only, never values. */
data class Access(
    val unixMs: Long,
    val client: String,
    val method: String,
    val params: List<String>,
    val outcome: String,
    val ms: Long,
)

/**
 * The access log. The API can read it but nothing can write it except the server, and remote
 * control cannot clear it.
 */
class AccessLog(private val capacity: Int = 500) {
    private val entries = ArrayDeque<Access>(capacity)
    private val _recent = MutableStateFlow<List<Access>>(emptyList())

    /** The newest entries, newest first, for the phone's screen. */
    val recent: StateFlow<List<Access>> = _recent

    fun add(a: Access) {
        synchronized(this) {
            if (entries.size == capacity) entries.removeFirst()
            entries.addLast(a)
            _recent.value = entries.takeLast(30).reversed()
        }
    }

    fun read(max: Int): JSONArray =
        synchronized(this) {
            JSONArray().apply {
                for (a in entries.takeLast(max)) {
                    put(
                        JSONObject()
                            .put("unixMs", a.unixMs)
                            .put("client", a.client)
                            .put("method", a.method)
                            .put("params", JSONArray(a.params))
                            .put("outcome", a.outcome)
                            .put("ms", a.ms)
                    )
                }
            }
        }
}
