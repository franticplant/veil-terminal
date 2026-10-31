package io.github.franticplant.veil.frontend

import android.content.Context
import io.github.franticplant.veil.engine.Target
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Saved hosts. Only where to connect and as whom; credentials are never stored here (passwords are
 * asked per session, and keys will live in the Vault).
 */
class Profiles(context: Context) {
    private val prefs = context.getSharedPreferences("profiles", Context.MODE_PRIVATE)
    /** When each host was last connected to, by [key]; device-local. */
    private val used = context.getSharedPreferences("profiles-used", Context.MODE_PRIVATE)
    private val _all = MutableStateFlow(sorted(load()))

    val all: StateFlow<List<Target>> = _all

    fun save(old: Target?, new: Target) {
        val list = _all.value.toMutableList()
        val i = if (old == null) -1 else list.indexOf(old)
        if (i >= 0) list[i] = new else list.add(new)
        store(list)
    }

    fun delete(t: Target) = store(_all.value - t)

    /**
     * Records a connection to [t] for sorting and returns the saved profile if one matches, else
     * [t] itself. New targets are not saved automatically: they go to [History] and become a host
     * only when the user says so.
     */
    fun touch(t: Target): Target {
        val existing = _all.value.firstOrNull { key(it) == key(t) }
        used.edit().putLong(key(t), System.currentTimeMillis()).apply()
        if (existing != null) _all.value = sorted(_all.value)
        return existing ?: t
    }

    /** True when the same user, host and port are already saved. */
    fun contains(t: Target): Boolean = _all.value.any { key(it) == key(t) }

    /** Last connection time, 0 if never. */
    fun lastUsed(t: Target): Long = used.getLong(key(t), 0)

    private fun key(t: Target) =
        "${t.username}@${t.host}:${t.port}" + (t.jump?.let { "/via-${it.host}:${it.port}" } ?: "")

    private fun sorted(list: List<Target>) = list.sortedByDescending { used.getLong(key(it), 0) }

    private fun store(list: List<Target>) {
        val arr = JSONArray()
        for (t in list) {
            arr.put(
                JSONObject()
                    .put("label", t.label)
                    .put("host", t.host)
                    .put("port", t.port)
                    .put("username", t.username)
                    .put("key", t.keyId)
                    .put("startup", t.startup)
                    .put("reconnect", t.autoReconnect)
                    .put("classicalKex", t.allowClassicalKex)
                    .put("jumpHost", t.jump?.host ?: "")
                    .put("jumpPort", t.jump?.port ?: 22)
            )
        }
        prefs.edit().putString("list", arr.toString()).apply()
        _all.value = sorted(list)
    }

    private fun load(): List<Target> {
        val arr = JSONArray(prefs.getString("list", "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Target(
                host = o.getString("host"),
                port = o.optInt("port", 22),
                username = o.getString("username"),
                label = o.optString("label"),
                keyId = o.optString("key"),
                startup = o.optString("startup"),
                autoReconnect = o.optBoolean("reconnect"),
                allowClassicalKex = o.optBoolean("classicalKex"),
                jump =
                    o.optString("jumpHost")
                        .takeIf { it.isNotBlank() }
                        ?.let {
                            io.github.franticplant.veil.engine.JumpHost(
                                it,
                                o.optInt("jumpPort", 22),
                            )
                        },
            )
        }
    }

    companion object {
        private val QUICK =
            Regex("^([^@\\s]+)@([^\\s:@\\[\\]]+|\\[[0-9a-fA-F:.]+])(?::(\\d{1,5}))?$")

        /** Parses "user@host" or "user@host:port" ("[v6]:port" for IPv6); null if it is not one. */
        fun parseQuick(s: String): Target? {
            val m = QUICK.matchEntire(s.trim()) ?: return null
            val port = m.groupValues[3].ifEmpty { "22" }.toInt()
            if (port !in 1..65535) return null
            return Target(
                m.groupValues[2].removePrefix("[").removeSuffix("]"),
                port,
                m.groupValues[1],
            )
        }
    }
}
