package io.github.franticplant.veil.devd

import io.github.franticplant.veil.dev.api.ApiException
import io.github.franticplant.veil.dev.api.ErrorCode
import io.github.franticplant.veil.dev.api.MethodSpec
import io.github.franticplant.veil.dev.api.Params
import io.github.franticplant.veil.dev.api.Reply
import io.github.franticplant.veil.dev.api.Scope
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
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
    private val tls by lazy { Tls.context() }
    private val serverCert by lazy { Tls.serverCertificate().encoded }
    private val listeners = ConcurrentHashMap<String, SSLServerSocket>()
    private val clients = ConcurrentHashMap.newKeySet<SSLSocket>()
    private val slots = Semaphore(MAX_CONNECTIONS)
    private val _addresses = MutableStateFlow<List<String>>(emptyList())
    private val unpairedCalls = ArrayDeque<Long>()

    @Volatile private var running = false
    private var watcher: Thread? = null

    /** What the listener is bound to right now, as "address:port". */
    val addresses: StateFlow<List<String>> = _addresses

    val activeConnections: Int
        get() = clients.size

    /** The server certificate's SHA-256, which clients pin. */
    val fingerprint: String by lazy { Tls.sha256Hex(serverCert) }

    @Synchronized
    fun start() {
        if (running) return
        running = true
        app.log.info("listener", "starting")
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
        for ((addr, s) in listeners) {
            closeQuietly(s)
            app.log.info("listener", "closed $addr")
        }
        listeners.clear()
        clients.forEach { closeQuietly(it) }
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
        for ((key, sock) in listeners) {
            if (key !in keys || sock.isClosed) {
                closeQuietly(sock)
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
                if (!listeners.containsKey(key) && running) {
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
        _addresses.value = listeners.keys.sorted()
    }

    /** Logs [what] for [key] only when it changed since the last pass. */
    private fun report(key: String, what: String, log: () -> Unit) {
        if (reported[key] != what) {
            reported[key] = what
            log()
        }
    }

    /** Opens a listener on [addr]; returns why not, or null. */
    private fun bind(addr: String, port: Int, key: String): String? =
        try {
            val sock = tls.serverSocketFactory.createServerSocket() as SSLServerSocket
            sock.reuseAddress = true
            sock.bind(InetSocketAddress(InetAddress.getByName(addr), port), 16)
            sock.needClientAuth = true
            sock.enabledProtocols = arrayOf("TLSv1.3")
            listeners[key] = sock
            Thread({ accept(sock, key) }, "devd-accept-$key").apply {
                isDaemon = true
                start()
            }
            null
        } catch (e: Exception) {
            // IOException for the socket; a Keystore or TLS failure is reported, not fatal.
            "${e.javaClass.simpleName}: ${e.message}"
        }

    private fun accept(server: SSLServerSocket, key: String) {
        while (running && !server.isClosed) {
            val sock =
                try {
                    server.accept() as SSLSocket
                } catch (e: IOException) {
                    break
                }
            if (!slots.tryAcquire()) {
                app.log.warn(
                    "listener",
                    "refused a connection on $key: $MAX_CONNECTIONS already open",
                )
                closeQuietly(sock)
                continue
            }
            clients.add(sock)
            Thread(
                    {
                        try {
                            serve(sock)
                        } finally {
                            clients.remove(sock)
                            closeQuietly(sock)
                            slots.release()
                        }
                    },
                    "devd-conn",
                )
                .apply {
                    isDaemon = true
                    start()
                }
        }
    }

    private fun serve(sock: SSLSocket) {
        val peer = addr(sock.remoteSocketAddress)
        val local = addr(sock.localSocketAddress)
        val clientCert =
            try {
                sock.soTimeout = HANDSHAKE_TIMEOUT_MS
                sock.startHandshake()
                sock.session.peerCertificates.first().encoded
            } catch (e: IOException) {
                app.log.warn("listener", "handshake with $peer failed: ${e.message}")
                return
            }
        sock.soTimeout = IDLE_TIMEOUT_MS
        val clientId = Tls.sha256Hex(clientCert)
        val input = DataInputStream(BufferedInputStream(sock.inputStream))
        val output = DataOutputStream(BufferedOutputStream(sock.outputStream))
        while (running) {
            val len =
                try {
                    input.readInt()
                } catch (e: EOFException) {
                    return
                } catch (e: IOException) {
                    return
                }
            if (len <= 0 || len > MAX_FRAME_BYTES) {
                write(
                    output,
                    Reply.error(
                        ErrorCode.INVALID_PARAMS,
                        "frame of $len bytes (limit $MAX_FRAME_BYTES)",
                    ),
                )
                return
            }
            val buf = ByteArray(len)
            try {
                input.readFully(buf)
            } catch (e: IOException) {
                return
            }
            val req =
                try {
                    JSONObject(String(buf, Charsets.UTF_8))
                } catch (e: JSONException) {
                    write(
                        output,
                        Reply.error(ErrorCode.INVALID_PARAMS, "request is not a JSON object"),
                    )
                    continue
                }
            val reply = dispatch(Conn(clientId, clientCert, local, peer), req)
            reply.put("id", req.opt("id") ?: JSONObject.NULL)
            try {
                write(output, reply)
            } catch (e: SocketException) {
                return
            }
        }
    }

    private fun write(out: DataOutputStream, msg: JSONObject) {
        val bytes = msg.toString().toByteArray(Charsets.UTF_8)
        out.writeInt(bytes.size)
        out.write(bytes)
        out.flush()
    }

    /** Who is on a connection and where it arrived. */
    private class Conn(
        val clientId: String,
        val clientCert: ByteArray,
        val local: String,
        val peer: String,
    )

    private fun addr(a: java.net.SocketAddress?): String =
        (a as? InetSocketAddress)?.let { "${it.address.hostAddress}:${it.port}" } ?: a.toString()

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
