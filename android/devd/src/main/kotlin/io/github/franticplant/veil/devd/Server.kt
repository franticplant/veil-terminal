package io.github.franticplant.veil.devd

import io.github.franticplant.veil.dev.api.ApiException
import io.github.franticplant.veil.dev.api.ErrorCode
import io.github.franticplant.veil.dev.api.MethodSpec
import io.github.franticplant.veil.dev.api.Params
import io.github.franticplant.veil.dev.api.Reply
import io.github.franticplant.veil.dev.api.Scope
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * The dev API listener.
 *
 * Wire format, after a TLS 1.3 handshake with client certificates: frames of a 4-byte big-endian
 * length and that many bytes of UTF-8 JSON. A request is {"id", "method", "params"}; the reply
 * echoes "id" and carries "result" or "error" {code, message}. Requests on one connection are
 * handled in order.
 *
 * Before pairing, a client may only call devd.hello and pair.request. After pairing, a call must be
 * in a scope the pairing was granted, and a secret parameter also needs the secrets scope.
 */
class Server(private val app: DevdApp) {
    private val serverCert by lazy { Tls.serverCertificate().encoded }
    /** "address:port" keys of the listeners the native side has open. */
    private val listeners = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var native = 0L

    /** What the native side calls: Keystore signing and request handling. */
    private val callback =
        object : DevdNative.Callback {
            override fun sign(data: ByteArray): ByteArray? =
                try {
                    Tls.sign(data)
                } catch (e: Exception) {
                    app.log.warn("listener", "signing the handshake failed: $e")
                    null
                }

            override fun request(
                clientCertHex: String,
                local: String,
                peer: String,
                json: String,
            ): String {
                val cert = hexToBytes(clientCertHex)
                val req =
                    try {
                        JSONObject(json)
                    } catch (e: JSONException) {
                        return Reply.error(ErrorCode.INVALID_PARAMS, "request is not a JSON object")
                            .put("id", JSONObject.NULL)
                            .toString()
                    }
                val reply = dispatch(Conn(Tls.sha256Hex(cert), cert, local, peer), req)
                return reply.put("id", req.opt("id") ?: JSONObject.NULL).toString()
            }
        }

    private fun hexToBytes(h: String): ByteArray =
        ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private val _addresses = MutableStateFlow<List<String>>(emptyList())
    private val unpairedCalls = ArrayDeque<Long>()

    @Volatile private var running = false
    private var watcher: Thread? = null

    /** What the listener is bound to right now, as "address:port". */
    val addresses: StateFlow<List<String>> = _addresses

    val activeConnections: Int
        get() = native.takeIf { it != 0L }?.let { DevdNative.connections(it) } ?: 0

    /** The server certificate's SHA-256, which clients pin. */
    val fingerprint: String by lazy { Tls.sha256Hex(serverCert) }

    @Synchronized
    fun start() {
        if (running) return
        running = true
        app.log.info("listener", "starting")
        if (native == 0L) native = DevdNative.start(callback, serverCert)
        watcher =
            Thread(
                    {
                        while (running) {
                            try {
                                reconcile()
                            } catch (e: Exception) {
                                // Keep watching: one bad pass must not end the listener.
                                app.log.warn("listener", "reconcile failed: $e")
                            }
                            try {
                                Thread.sleep(3_000)
                            } catch (e: InterruptedException) {
                                break
                            }
                        }
                    },
                    "devd-listener-watch",
                )
                .apply {
                    isDaemon = true
                    start()
                }
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        watcher?.interrupt()
        for (key in listeners) app.log.info("listener", "closed $key")
        listeners.clear()
        if (native != 0L) DevdNative.stop(native)
        native = 0L
        _addresses.value = emptyList()
        _binds.value = emptyList()
        reported.clear()
    }

    /**
     * One thing the listener should be on: loopback, or an enabled interface. [address] is
     * "addr:port" when there is one; [state] is "listening", "down" (the interface has no IPv4
     * address now) or "error" with [detail].
     */
    data class Bind(
        val iface: String,
        val address: String?,
        val state: String,
        val detail: String = "",
    )

    private val _binds = MutableStateFlow<List<Bind>>(emptyList())
    private val reported = HashMap<String, String>()

    /** The state of each enabled interface and address, for the screen and devd.info. */
    val binds: StateFlow<List<Bind>> = _binds

    /** Binds loopback and the enabled interfaces' IPv4 addresses; closes anything else. */
    private fun reconcile() {
        val v = app.settings.values.value
        val up = ipv4Interfaces().toMap()
        // iface name -> its addresses now (empty: down).
        val wanted = LinkedHashMap<String, List<String>>()
        if (v.loopback) wanted[LOOPBACK] = listOf("127.0.0.1")
        for (name in v.ifaces.sorted()) {
            wanted[name] = up[name].orEmpty().mapNotNull { it.hostAddress }
        }
        val keys = wanted.values.flatten().map { "$it:${v.port}" }.toSet()
        for (key in listeners.toList()) {
            if (key !in keys) {
                val (a, p) = key.substringBeforeLast(':') to key.substringAfterLast(':').toInt()
                if (native != 0L) DevdNative.unbind(native, a, p)
                listeners.remove(key)
                app.log.info("listener", "closed $key")
            }
        }
        val binds = ArrayList<Bind>()
        for ((iface, addrs) in wanted) {
            if (addrs.isEmpty()) {
                binds.add(Bind(iface, null, "down", "no IPv4 address; bound when it has one"))
                continue
            }
            for (addr in addrs) {
                val key = "$addr:${v.port}"
                if (key !in listeners && running) {
                    val error = bind(addr, v.port, key)
                    if (error != null) {
                        binds.add(Bind(iface, key, "error", error))
                        report(key, "error: $error") {
                            app.log.warn("listener", "cannot listen on $key: $error")
                        }
                        continue
                    }
                }
                binds.add(Bind(iface, key, "listening"))
                report(key, "listening") { app.log.info("listener", "listening on $key ($iface)") }
            }
        }
        _binds.value = binds
        _addresses.value = listeners.sorted()
    }

    /** Logs [what] for [key] only when it changed since the last pass. */
    private fun report(key: String, what: String, log: () -> Unit) {
        if (reported[key] != what) {
            reported[key] = what
            log()
        }
    }

    /** Opens a listener on [addr] (in the native server); returns why not, or null. */
    private fun bind(addr: String, port: Int, key: String): String? {
        if (native == 0L) return "the server is not running"
        val error = DevdNative.bind(native, addr, port)
        if (error == null) listeners.add(key)
        return error
    }

    /** Who is on a connection and where it arrived. */
    private class Conn(
        val clientId: String,
        val clientCert: ByteArray,
        val local: String,
        val peer: String,
    )

    private fun dispatch(conn: Conn, req: JSONObject): JSONObject {
        val clientId = conn.clientId
        val t0 = System.nanoTime()
        val method = req.optString("method")
        val raw = req.optJSONObject("params") ?: JSONObject()
        val pairing = app.pairings.find(clientId)
        val client = pairing?.name ?: "unpaired:${clientId.take(12)}"
        var paramNames = raw.keys().asSequence().toList().sorted()
        val reply =
            try {
                when {
                    method == "devd.hello" -> Reply.ok(hello(pairing, clientId))
                    method == "pair.request" -> Reply.ok(pair(conn, raw))
                    pairing == null -> {
                        throttleUnpaired()
                        Reply.error(
                            ErrorCode.UNAUTHORIZED,
                            "this client is not paired (or its pairing expired); run: veil-ctl pair",
                        )
                    }
                    else -> {
                        val (spec, params) = check(method, raw)
                        paramNames = params.names.sorted()
                        authorize(spec, params, pairing)
                        Caller.current.set(pairing)
                        try {
                            if (spec.name in app.methods.table)
                                app.methods.table.call(spec.name, raw)
                            else app.hooks.call(spec.name, params.toJson())
                        } finally {
                            Caller.current.remove()
                        }
                    }
                }
            } catch (e: ApiException) {
                Reply.error(e)
            } catch (e: RuntimeException) {
                app.log.warn("api", "$method failed: $e")
                Reply.error(ErrorCode.INTERNAL, "${e.javaClass.simpleName}: ${e.message}")
            }
        val outcome = reply.optJSONObject("error")?.optString("code") ?: "ok"
        app.access.add(
            Access(
                client,
                method.take(64),
                paramNames,
                outcome,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0),
                conn.local,
                conn.peer,
            )
        )
        return reply
    }

    /** Finds the method (devd's own, then Veil's) and checks the parameters. */
    private fun check(method: String, raw: JSONObject): Pair<MethodSpec, Params> {
        val spec =
            app.methods.table.spec(method)
                ?: try {
                    app.hooks.catalog().firstOrNull { it.name == method }
                } catch (e: ApiException) {
                    throw ApiException(
                        e.code,
                        "$method is not a devd method, and Veil is unavailable: ${e.message}",
                    )
                }
                ?: throw ApiException(
                    ErrorCode.UNKNOWN_METHOD,
                    "no method $method (see: veil-ctl methods)",
                )
        return spec to spec.check(raw)
    }

    private fun authorize(spec: MethodSpec, params: Params, pairing: Pairing) {
        if (spec.scope !in pairing.scopes) {
            throw ApiException(
                ErrorCode.FORBIDDEN,
                "${spec.name} needs scope '${spec.scope}'; this pairing has ${pairing.scopes.sorted()}",
            )
        }
        if (params.hasSecret && Scope.SECRETS !in pairing.scopes) {
            throw ApiException(
                ErrorCode.FORBIDDEN,
                "passing a secret parameter needs scope '${Scope.SECRETS}'",
            )
        }
    }

    private fun hello(pairing: Pairing?, clientId: String): JSONObject =
        JSONObject()
            .put("service", "veil-devd")
            .put("version", BuildConfig.VERSION_NAME)
            .put("serverCertSha256", fingerprint)
            .put("clientCertSha256", clientId)
            .put("paired", pairing != null)
            .apply {
                if (pairing != null) {
                    put("name", pairing.name)
                    put("scopes", JSONArray(pairing.scopes.sorted()))
                    put("expiresMs", pairing.expiresMs)
                }
            }

    /**
     * Asks the user on the phone. Blocks until they decide or [Pairings.REQUEST_TIMEOUT_MS] passes.
     * The code shown on the phone must match the one the client shows.
     */
    private fun pair(conn: Conn, raw: JSONObject): JSONObject {
        val clientId = conn.clientId
        val name = raw.optString("name").ifBlank { "unnamed client" }
        val scopesRaw = raw.optJSONArray("scopes") ?: JSONArray(Scope.ALL)
        val scopes = (0 until scopesRaw.length()).map { scopesRaw.getString(it) }.toSet()
        val code = Tls.pairingCode(serverCert, conn.clientCert)
        val req = app.pairings.request(clientId, name, scopes, code, conn.local, conn.peer)
        app.notifications.pairingRequest(req)
        val result =
            try {
                req.result.get(Pairings.REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (e: java.util.concurrent.TimeoutException) {
                app.pairings.expire(req)
                null
            } finally {
                app.notifications.clearPairingRequest()
            }
        result
            ?: throw ApiException(
                ErrorCode.UNAUTHORIZED,
                "pairing was denied, or not answered within 2 minutes",
            )
        return JSONObject()
            .put("name", result.name)
            .put("scopes", JSONArray(result.scopes.sorted()))
            .put("expiresMs", result.expiresMs)
            .put("serverCertSha256", fingerprint)
    }

    /** Unpaired clients get at most [UNPAIRED_PER_MINUTE] answers a minute, all together. */
    private fun throttleUnpaired() {
        val now = System.currentTimeMillis()
        val wait =
            synchronized(unpairedCalls) {
                while (
                    unpairedCalls.isNotEmpty() && now - unpairedCalls.first() > 60_000
                ) unpairedCalls.removeFirst()
                unpairedCalls.addLast(now)
                unpairedCalls.size > UNPAIRED_PER_MINUTE
            }
        if (wait) Thread.sleep(2_000)
    }

    private fun closeQuietly(c: java.io.Closeable) {
        try {
            c.close()
        } catch (e: IOException) {}
    }

    companion object {
        const val LOOPBACK = "loopback"
        const val MAX_CONNECTIONS = 8
        const val MAX_FRAME_BYTES = 4 * 1024 * 1024
        const val HANDSHAKE_TIMEOUT_MS = 10_000
        const val IDLE_TIMEOUT_MS = 10 * 60_000
        const val UNPAIRED_PER_MINUTE = 20
    }
}

/** The pairing whose call is running on this thread, for methods that report on the caller. */
object Caller {
    val current = ThreadLocal<Pairing>()
}
