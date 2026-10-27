package io.github.franticplant.veil.devd

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/** A pairing request as the screen shows it. */
data class PendingView(
    val clientId: String,
    val name: String,
    val requestedScopes: Set<String>,
    val code: String,
    val local: String,
    val peer: String,
)

/**
 * devd's service as seen from the screen's process (:ui): state polled once a second through
 * [UiService], actions and reads as calls. The screen holds no service objects itself.
 */
class RemoteDevd(private val context: Context) {
    private var service: IDevdUi? = null
    private var connection: ServiceConnection? = null

    val offAtMs = MutableStateFlow<Long?>(null)
    val addresses = MutableStateFlow<List<String>>(emptyList())
    val binds = MutableStateFlow<List<Server.Bind>>(emptyList())
    val settings = MutableStateFlow(Settings.Values(Settings.DEFAULT_PORT, true, emptySet(), 60))
    val pairings = MutableStateFlow<List<Pairing>>(emptyList())
    val pending = MutableStateFlow<PendingView?>(null)
    val fingerprint = MutableStateFlow("")
    val veil = MutableStateFlow<String?>(null)
    private val _metricsVersion = MutableStateFlow(0L)
    val metricsVersion: StateFlow<Long> = _metricsVersion

    private suspend fun bound(): IDevdUi {
        service?.let {
            return it
        }
        val ready = CompletableDeferred<IDevdUi>()
        val conn =
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, b: IBinder?) {
                    service = IDevdUi.Stub.asInterface(b)
                    ready.complete(service!!)
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    service = null
                }
            }
        connection = conn
        context.bindService(Intent(context, UiService::class.java), conn, Context.BIND_AUTO_CREATE)
        return withTimeout(10_000) { ready.await() }
    }

    fun close() {
        connection?.let { context.unbindService(it) }
        connection = null
        service = null
    }

    /** Calls [method]; returns its result (JSONObject, JSONArray, or null), or throws. */
    suspend fun call(method: String, params: JSONObject = JSONObject()): Any? =
        withContext(Dispatchers.IO) {
            val reply = JSONObject(bound().call(method, params.toString()))
            reply
                .optString("error")
                .takeIf { it.isNotEmpty() }
                ?.let { throw IllegalStateException(it) }
            reply.opt("result").takeIf { it != JSONObject.NULL }
        }

    /** Keeps the state fresh while the screen is open. */
    suspend fun poll() {
        while (true) {
            try {
                apply(call("state") as JSONObject)
            } catch (e: Exception) {}
            delay(1_000)
        }
    }

    private fun strings(a: JSONArray) = (0 until a.length()).map { a.getString(it) }

    private fun apply(s: JSONObject) {
        offAtMs.value = if (s.isNull("offAtMs")) null else s.getLong("offAtMs")
        addresses.value = strings(s.getJSONArray("addresses"))
        val b = s.getJSONArray("binds")
        binds.value =
            (0 until b.length()).map {
                val o = b.getJSONObject(it)
                Server.Bind(
                    o.getString("iface"),
                    o.optString("address").ifEmpty { null },
                    o.getString("state"),
                    o.optString("detail"),
                )
            }
        val st = s.getJSONObject("settings")
        settings.value =
            Settings.Values(
                st.getInt("port"),
                st.getBoolean("loopback"),
                strings(st.getJSONArray("ifaces")).toSet(),
                st.getInt("autoOffMinutes"),
            )
        fingerprint.value = s.getString("fingerprint")
        val pr = s.getJSONArray("pairings")
        pairings.value =
            (0 until pr.length()).map {
                val o = pr.getJSONObject(it)
                Pairing(
                    o.getString("id"),
                    o.getString("name"),
                    strings(o.getJSONArray("scopes")).toSet(),
                    o.getLong("created"),
                    o.getLong("expires"),
                )
            }
        pending.value =
            s.optJSONObject("pending")?.let {
                PendingView(
                    it.getString("clientId"),
                    it.getString("name"),
                    strings(it.getJSONArray("scopes")).toSet(),
                    it.getString("code"),
                    it.getString("local"),
                    it.getString("peer"),
                )
            }
        veil.value =
            s.optJSONObject("veil")?.let {
                "Veil ${it.getString("versionName")} (${it.getLong("versionCode")})"
            }
        _metricsVersion.value = s.getLong("metricsVersion")
    }

    suspend fun updateSettings(v: Settings.Values) {
        call(
            "settings.update",
            JSONObject()
                .put("loopback", v.loopback)
                .put("ifaces", JSONArray(v.ifaces.toList()))
                .put("autoOffMinutes", v.autoOffMinutes),
        )
        settings.value = v
    }

    suspend fun approve(p: PendingView, scopes: Set<String>, days: Int) =
        call(
            "pairing.approve",
            JSONObject()
                .put("clientId", p.clientId)
                .put("scopes", JSONArray(scopes.toList()))
                .put("days", days),
        )

    suspend fun deny(p: PendingView) =
        call("pairing.deny", JSONObject().put("clientId", p.clientId))

    suspend fun revoke(p: Pairing) = call("pairing.revoke", JSONObject().put("id", p.id))

    suspend fun points(key: String): List<Pair<Long, Double>> {
        val a =
            call("metrics.points", JSONObject().put("key", key)) as? JSONArray ?: return emptyList()
        return (0 until a.length()).map {
            val x = a.getJSONArray(it)
            x.getLong(0) to x.getDouble(1)
        }
    }
}
