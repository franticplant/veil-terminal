package io.github.franticplant.veil.devd

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Process
import io.github.franticplant.veil.dev.api.DevIds
import org.json.JSONArray
import org.json.JSONObject

/**
 * What devd's screen may ask of the service. The screen runs in its own process (:ui) so that the
 * service, which runs whenever the listener is on, does not carry the UI's memory. Reachable only
 * from this app (not exported, and the caller's UID is checked).
 */
class UiService : Service() {
    private val app
        get() = application as DevdApp

    override fun onBind(intent: Intent?): IBinder =
        object : IDevdUi.Stub() {
            override fun call(method: String, paramsJson: String): String {
                if (Binder.getCallingUid() != Process.myUid())
                    throw SecurityException("devd's UI only")
                return try {
                    JSONObject().put("result", handle(method, JSONObject(paramsJson))).toString()
                } catch (e: Exception) {
                    JSONObject().put("error", "${e.javaClass.simpleName}: ${e.message}").toString()
                }
            }
        }

    private fun strings(a: JSONArray?) = (0 until (a?.length() ?: 0)).map { a!!.getString(it) }

    private fun handle(method: String, p: JSONObject): Any? =
        when (method) {
            "state" -> state()
            "settings.update" -> {
                val v = app.settings.values.value
                app.settings.update(
                    v.copy(
                        loopback = p.optBoolean("loopback", v.loopback),
                        ifaces =
                            if (p.has("ifaces")) strings(p.getJSONArray("ifaces")).toSet()
                            else v.ifaces,
                        autoOffMinutes = p.optInt("autoOffMinutes", v.autoOffMinutes),
                    )
                )
                null
            }
            "pairing.approve" -> {
                val req =
                    app.pairings.pending.value?.takeIf { it.clientId == p.getString("clientId") }
                if (req != null)
                    app.pairings.approve(
                        req,
                        strings(p.getJSONArray("scopes")).toSet(),
                        p.getInt("days"),
                    )
                null
            }
            "pairing.deny" -> {
                app.pairings.pending.value
                    ?.takeIf { it.clientId == p.getString("clientId") }
                    ?.let { app.pairings.deny(it, "denied on the phone") }
                null
            }
            "pairing.revoke" -> {
                app.pairings.all.value
                    .firstOrNull { it.id == p.getString("id") }
                    ?.let { app.pairings.revoke(it) }
                null
            }
            "journal.tail" ->
                app.journal.tail(p.getInt("max"), JournalFilter.of(p.getString("kind")))
            "journal.read" ->
                app.journal.read(
                    p.getLong("after"),
                    p.getInt("max"),
                    JournalFilter.of(p.getString("kind")),
                )
            "journal.head" -> app.journal.head()
            "journal.verify" -> app.journal.verify()
            "veil.call" -> app.hooks.call(p.getString("method"), p.getJSONObject("params"))
            "hooks.connect" -> {
                app.hooks.get()
                null
            }
            "metrics.points" ->
                JSONArray().apply {
                    for ((t, v) in app.metrics.points(p.getString("key"))) put(
                        JSONArray().put(t).put(v)
                    )
                }
            "metrics.latest" -> app.metrics.latest.value ?: JSONObject.NULL
            else -> throw IllegalArgumentException("unknown method $method")
        }

    private fun state(): JSONObject {
        val s = app.settings.values.value
        val veil = app.updater.installed(DevIds.VEIL_PACKAGE)
        return JSONObject()
            .put("offAtMs", app.offAtMs.value ?: JSONObject.NULL)
            .put("addresses", JSONArray(app.server.addresses.value))
            .put(
                "binds",
                JSONArray().apply {
                    for (b in app.server.binds.value) {
                        put(
                            JSONObject()
                                .put("iface", b.iface)
                                .put("address", b.address ?: JSONObject.NULL)
                                .put("state", b.state)
                                .put("detail", b.detail)
                        )
                    }
                },
            )
            .put(
                "settings",
                JSONObject()
                    .put("port", s.port)
                    .put("loopback", s.loopback)
                    .put("ifaces", JSONArray(s.ifaces.sorted()))
                    .put("autoOffMinutes", s.autoOffMinutes),
            )
            .put("fingerprint", app.server.fingerprint)
            .put(
                "pairings",
                JSONArray().apply {
                    for (pr in app.pairings.all.value) {
                        put(
                            JSONObject()
                                .put("id", pr.id)
                                .put("name", pr.name)
                                .put("scopes", JSONArray(pr.scopes.sorted()))
                                .put("created", pr.createdMs)
                                .put("expires", pr.expiresMs)
                        )
                    }
                },
            )
            .put(
                "pending",
                app.pairings.pending.value?.let {
                    JSONObject()
                        .put("clientId", it.clientId)
                        .put("name", it.name)
                        .put("scopes", JSONArray(it.requestedScopes.sorted()))
                        .put("code", it.code)
                        .put("local", it.local)
                        .put("peer", it.peer)
                } ?: JSONObject.NULL,
            )
            .put(
                "veil",
                veil?.let {
                    JSONObject()
                        .put("versionName", it.versionName)
                        .put("versionCode", it.longVersionCode)
                } ?: JSONObject.NULL,
            )
            .put("metricsVersion", app.metrics.version.value)
    }
}
