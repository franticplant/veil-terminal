package io.github.franticplant.veil.frontend

import android.content.Context
import io.github.franticplant.veil.engine.Target
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Recent connections, kept separately from saved hosts. A burst of short connects to the same
 * endpoint collapses into one entry with a count, so history stays usable without hiding the fact
 * that it happened many times.
 */
class History(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Entry(val target: Target, val lastUsed: Long, val count: Int) {
        val key: String
            get() = key(target)
    }

    private val prefs = context.getSharedPreferences("history", Context.MODE_PRIVATE)
    private val _all = MutableStateFlow(load())
    val all: StateFlow<List<Entry>> = _all

    fun record(target: Target) = store(recorded(_all.value, target, clock()))

    fun clear() {
        prefs.edit().remove("list").apply()
        _all.value = emptyList()
    }

    private fun load(): List<Entry> {
        val arr = JSONArray(prefs.getString("list", "[]"))
        return (0 until arr.length())
            .mapNotNull {
                val o = arr.optJSONObject(it) ?: return@mapNotNull null
                val t = o.optJSONObject("target") ?: return@mapNotNull null
                Entry(
                    target = targetFrom(t),
                    lastUsed = o.optLong("at"),
                    count = o.optInt("count", 1).coerceAtLeast(1),
                )
            }
            .sortedByDescending { it.lastUsed }
            .take(MAX_ENTRIES)
    }

    private fun store(list: List<Entry>) {
        val arr = JSONArray()
        for (e in list) {
            arr.put(
                JSONObject()
                    .put("target", targetJson(e.target))
                    .put("at", e.lastUsed)
                    .put("count", e.count)
            )
        }
        prefs.edit().putString("list", arr.toString()).apply()
        _all.value = list
    }

    companion object {
        /** Repeats of one endpoint inside this window share an entry. */
        const val GROUP_WINDOW_MS = 10 * 60 * 1000L

        const val MAX_ENTRIES = 200

        internal fun key(t: Target) = "${t.username}@${t.host}:${t.port}"

        /**
         * Adds [target] to [list] at [now]. If the endpoint was used within [GROUP_WINDOW_MS], its
         * newest entry is updated instead of adding another row.
         */
        internal fun recorded(list: List<Entry>, target: Target, now: Long): List<Entry> {
            val out = list.toMutableList()
            val key = key(target)
            val i = out.indexOfFirst { it.key == key && now - it.lastUsed <= GROUP_WINDOW_MS }
            if (i >= 0) {
                out[i] = out[i].copy(target = target, lastUsed = now, count = out[i].count + 1)
            } else {
                out.add(Entry(target, now, 1))
            }
            return out.sortedByDescending { it.lastUsed }.take(MAX_ENTRIES)
        }

        private fun targetJson(t: Target) =
            JSONObject()
                .put("label", t.label)
                .put("host", t.host)
                .put("port", t.port)
                .put("username", t.username)
                .put("key", t.keyId)
                .put("startup", t.startup)
                .put("reconnect", t.autoReconnect)
                .put("classicalKex", t.allowClassicalKex)

        private fun targetFrom(o: JSONObject) =
            Target(
                host = o.getString("host"),
                port = o.optInt("port", 22),
                username = o.getString("username"),
                label = o.optString("label"),
                keyId = o.optString("key"),
                startup = o.optString("startup"),
                autoReconnect = o.optBoolean("reconnect"),
                allowClassicalKex = o.optBoolean("classicalKex"),
            )
    }
}
