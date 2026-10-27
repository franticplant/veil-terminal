package io.github.franticplant.veil.devd

import io.github.franticplant.veil.dev.api.ApiException
import io.github.franticplant.veil.dev.api.ProcSelf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Time series of every component devd can see: itself, and through DevHooks the Veil app process,
 * the engine process and each session's connection. Sampled every [INTERVAL_MS] while the listener
 * runs; the last [KEEP] points of each series are kept in memory.
 *
 * Series are named "<component>.<metric>", for example "veil-engine.rssKb", "veil-app.cpuPct",
 * "net.inBps", "session.3.inBps". Rates (CPU %, bytes per second) come from deltas between samples.
 */
class Metrics(private val app: DevdApp) {
    /** A fixed-size ring of (time, value) points in primitive arrays: no boxing per point. */
    private class Ring {
        val t = LongArray(KEEP)
        val v = DoubleArray(KEEP)
        var start = 0
        var size = 0

        fun add(time: Long, value: Double) {
            val i = (start + size) % KEEP
            t[i] = time
            v[i] = value
            if (size < KEEP) size++ else start = (start + 1) % KEEP
        }

        fun last(): Double? = if (size == 0) null else v[(start + size - 1) % KEEP]

        inline fun forEach(sinceMs: Long, f: (Long, Double) -> Unit) {
            for (k in 0 until size) {
                val i = (start + k) % KEEP
                if (t[i] >= sinceMs) f(t[i], v[i])
            }
        }
    }

    private val series = LinkedHashMap<String, Ring>()
    private var previous: Sample? = null
    private val _latest = MutableStateFlow<JSONObject?>(null)
    private val _version = MutableStateFlow(0L)
    @Volatile private var thread: Thread? = null

    /** The newest raw sample (processes and connections), for tables. */
    val latest: StateFlow<JSONObject?> = _latest

    /** Changes on every sample: observe it to redraw charts. */
    val version: StateFlow<Long> = _version

    private class Sample(val t: Long, val cpuMs: Map<String, Long>, val bytes: Map<String, Long>)

    @Synchronized
    fun start() {
        if (thread != null) return
        thread =
            Thread(
                    {
                        while (thread === Thread.currentThread()) {
                            try {
                                sampleOnce()
                            } catch (e: Exception) {
                                app.log.warn("metrics", "sample failed: $e")
                            }
                            try {
                                Thread.sleep(INTERVAL_MS)
                            } catch (e: InterruptedException) {
                                break
                            }
                        }
                    },
                    "devd-metrics",
                )
                .apply {
                    isDaemon = true
                    start()
                }
    }

    @Synchronized
    fun stop() {
        thread?.interrupt()
        thread = null
    }

    private fun sampleOnce() {
        val now = System.currentTimeMillis()
        val processes = JSONArray().put(ProcSelf.sample("devd"))
        var sessions = JSONArray()
        var veil: JSONObject? = null
        // Only while Veil is bound: sampling must not start Veil by itself.
        if (app.hooks.connected) {
            try {
                val reply = app.hooks.call("metrics.sample", JSONObject())
                veil = reply.optJSONObject("result")
            } catch (e: ApiException) {}
        }
        veil?.let { v ->
            val ps = v.getJSONArray("processes")
            for (i in 0 until ps.length()) processes.put(ps.getJSONObject(i))
            sessions = v.getJSONArray("sessions")
        }

        val cpu = HashMap<String, Long>()
        val bytes = HashMap<String, Long>()
        val prev = previous
        val dt = prev?.let { (now - it.t).coerceAtLeast(1) }
        fun rate(key: String, total: Long): Double? {
            bytes[key] = total
            val before = prev?.bytes?.get(key) ?: return null
            return (total - before).coerceAtLeast(0) * 1000.0 / dt!!
        }

        synchronized(series) {
            for (i in 0 until processes.length()) {
                val p = processes.getJSONObject(i)
                val name = p.getString("name")
                p.optLong("rssKb", -1)
                    .takeIf { it >= 0 }
                    ?.let { add("$name.rssKb", now, it.toDouble()) }
                p.optLong("pssKb", -1)
                    .takeIf { it >= 0 }
                    ?.let { add("$name.pssKb", now, it.toDouble()) }
                p.optInt("threads", -1)
                    .takeIf { it >= 0 }
                    ?.let { add("$name.threads", now, it.toDouble()) }
                p.optInt("fds", -1).takeIf { it >= 0 }?.let { add("$name.fds", now, it.toDouble()) }
                val c = p.optLong("cpuMs", -1)
                if (c >= 0) {
                    cpu[name] = c
                    val before = prev?.cpuMs?.get(name)
                    if (before != null && dt != null) {
                        // Percent of one core.
                        add("$name.cpuPct", now, ((c - before).coerceAtLeast(0) * 100.0 / dt))
                    }
                }
                p.put("cpuPct", series["$name.cpuPct"]?.last() ?: JSONObject.NULL)
            }
            var inTotal = 0L
            var outTotal = 0L
            for (i in 0 until sessions.length()) {
                val s = sessions.getJSONObject(i)
                val id = s.getLong("id")
                inTotal += s.optLong("wireIn")
                outTotal += s.optLong("wireOut")
                rate("session.$id.in", s.optLong("wireIn"))?.let {
                    add("session.$id.inBps", now, it)
                    s.put("inBps", it)
                }
                rate("session.$id.out", s.optLong("wireOut"))?.let {
                    add("session.$id.outBps", now, it)
                    s.put("outBps", it)
                }
            }
            add("sessions.live", now, sessions.length().toDouble())
            // Sessions come and go, so the total rate uses Android's per-app counters.
            veil?.optJSONObject("uidTraffic")?.let { u ->
                rate("uid.rx", u.optLong("rxBytes"))?.let { add("net.inBps", now, it) }
                rate("uid.tx", u.optLong("txBytes"))?.let { add("net.outBps", now, it) }
            }
            add("net.sessionWireInBytes", now, inTotal.toDouble())
            add("net.sessionWireOutBytes", now, outTotal.toDouble())
        }
        previous = Sample(now, cpu, bytes)
        _latest.value =
            JSONObject()
                .put("unixMs", now)
                .put("veilConnected", veil != null)
                .put("engineBackend", veil?.optString("engineBackend") ?: JSONObject.NULL)
                .put("processes", processes)
                .put("sessions", sessions)
        _version.value = now
    }

    private fun add(key: String, t: Long, v: Double) {
        series.getOrPut(key) { Ring() }.add(t, v)
    }

    /** Names of all series. */
    fun keys(): List<String> = synchronized(series) { series.keys.sorted() }

    /** Points of [key] since [sinceMs], oldest first. */
    fun points(key: String, sinceMs: Long = 0): List<Pair<Long, Double>> =
        synchronized(series) {
            val out = ArrayList<Pair<Long, Double>>()
            series[key]?.forEach(sinceMs) { t, v -> out.add(t to v) }
            out
        }

    /** For the API: the series matching [prefixes] (all if empty) since [sinceMs]. */
    fun history(prefixes: List<String>, sinceMs: Long): JSONObject =
        synchronized(series) {
            val out = JSONObject()
            for ((k, s) in series) {
                if (prefixes.isNotEmpty() && prefixes.none { k.startsWith(it) }) continue
                val arr = JSONArray()
                s.forEach(sinceMs) { t, v -> arr.put(JSONArray().put(t).put(v)) }
                out.put(k, arr)
            }
            JSONObject().put("intervalMs", INTERVAL_MS).put("series", out)
        }

    companion object {
        const val INTERVAL_MS = 2_000L
        /** 30 minutes at [INTERVAL_MS]. */
        const val KEEP = 900
    }
}
