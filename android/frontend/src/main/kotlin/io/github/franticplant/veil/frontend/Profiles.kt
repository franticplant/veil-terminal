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
    private val _all = MutableStateFlow(load())

    val all: StateFlow<List<Target>> = _all

    fun save(old: Target?, new: Target) {
        val list = _all.value.toMutableList()
        val i = if (old == null) -1 else list.indexOf(old)
        if (i >= 0) list[i] = new else list.add(new)
        store(list)
    }

    fun delete(t: Target) = store(_all.value - t)

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
            )
        }
        prefs.edit().putString("list", arr.toString()).apply()
        _all.value = list
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
            )
        }
    }
}
