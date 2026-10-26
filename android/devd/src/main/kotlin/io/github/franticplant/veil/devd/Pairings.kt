package io.github.franticplant.veil.devd

import android.content.Context
import androidx.core.content.edit
import io.github.franticplant.veil.dev.api.ApiException
import io.github.franticplant.veil.dev.api.ErrorCode
import io.github.franticplant.veil.dev.api.Scope
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * A paired client: its certificate's SHA-256 ([id]), a name, and what it may do until [expiresMs].
 */
data class Pairing(
    val id: String,
    val name: String,
    val scopes: Set<String>,
    val createdMs: Long,
    val expiresMs: Long,
)

/** A pairing request waiting for the user on the phone. */
class PairingRequest(
    val clientId: String,
    val name: String,
    val requestedScopes: Set<String>,
    val code: String,
    val createdMs: Long,
    /** The listener address it arrived on, and the peer's address. */
    val local: String,
    val peer: String,
) {
    val result = CompletableFuture<Pairing?>()
}

/**
 * Paired clients and the pending request. Holds only public data (certificate digests, names,
 * scopes), so plain app storage is fine. Pairings are managed only on the phone: there is no API
 * method that lists, adds or revokes them.
 */
class Pairings(context: Context, private val log: DevdLog) {
    private val prefs = context.getSharedPreferences("pairings", Context.MODE_PRIVATE)
    private val _all = MutableStateFlow(load())
    private val _pending = MutableStateFlow<PairingRequest?>(null)
    private val requestTimes = ArrayDeque<Long>()

    val all: StateFlow<List<Pairing>> = _all
    val pending: StateFlow<PairingRequest?> = _pending

    /** The live pairing for a client certificate digest, or null (unknown or expired). */
    fun find(clientId: String): Pairing? =
        _all.value.firstOrNull { it.id == clientId && it.expiresMs > System.currentTimeMillis() }

    /**
     * Opens a request. One may be pending at a time and at most [MAX_REQUESTS] are allowed per
     * [WINDOW_MS] in total (not per address: on loopback every app shares one).
     */
    fun request(
        clientId: String,
        name: String,
        scopes: Set<String>,
        code: String,
        local: String,
        peer: String,
    ): PairingRequest {
        val now = System.currentTimeMillis()
        synchronized(requestTimes) {
            while (requestTimes.isNotEmpty() && now - requestTimes.first() > WINDOW_MS) requestTimes
                .removeFirst()
            if (requestTimes.size >= MAX_REQUESTS) {
                throw ApiException(
                    ErrorCode.RATE_LIMITED,
                    "too many pairing requests; try again later",
                )
            }
            requestTimes.addLast(now)
        }
        val req =
            PairingRequest(
                clientId,
                name.take(64),
                scopes.intersect(Scope.ALL.toSet()),
                code,
                now,
                local,
                peer,
            )
        if (!_pending.compareAndSet(null, req)) {
            throw ApiException(
                ErrorCode.RATE_LIMITED,
                "another pairing request is waiting on the phone",
            )
        }
        log.info(
            "pairing",
            "request from '${req.name}' (${clientId.take(16)}) on $local from $peer, " +
                "scopes ${req.requestedScopes}",
        )
        return req
    }

    fun approve(req: PairingRequest, scopes: Set<String>, days: Int) {
        val now = System.currentTimeMillis()
        val p = Pairing(req.clientId, req.name, scopes, now, now + days * 86_400_000L)
        store(_all.value.filter { it.id != p.id } + p)
        finish(req, p)
        log.info("pairing", "approved '${p.name}' for $days day(s), scopes $scopes")
    }

    fun deny(req: PairingRequest, why: String) {
        finish(req, null)
        log.info("pairing", "denied '${req.name}': $why")
    }

    fun revoke(p: Pairing) {
        store(_all.value.filter { it.id != p.id })
        log.info("pairing", "revoked '${p.name}'")
    }

    /** Drops a request that is still pending after [timeoutMs]. */
    fun expire(req: PairingRequest) {
        if (_pending.value === req) deny(req, "not answered in time")
    }

    private fun finish(req: PairingRequest, p: Pairing?) {
        _pending.compareAndSet(req, null)
        req.result.complete(p)
    }

    private fun store(list: List<Pairing>) {
        val arr = JSONArray()
        for (p in list) {
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("scopes", JSONArray(p.scopes.toList()))
                    .put("created", p.createdMs)
                    .put("expires", p.expiresMs)
            )
        }
        prefs.edit { putString("list", arr.toString()) }
        _all.value = list
    }

    private fun load(): List<Pairing> {
        val arr = JSONArray(prefs.getString("list", "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            val s = o.getJSONArray("scopes")
            Pairing(
                o.getString("id"),
                o.getString("name"),
                (0 until s.length()).map { i -> s.getString(i) }.toSet(),
                o.getLong("created"),
                o.getLong("expires"),
            )
        }
    }

    companion object {
        const val MAX_REQUESTS = 5
        const val WINDOW_MS = 10 * 60_000L
        const val REQUEST_TIMEOUT_MS = 120_000L
    }
}
