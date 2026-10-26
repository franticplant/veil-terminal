package io.github.franticplant.veil.devd

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * devd's persistent, hash-chained journal: its log lines and every API call (the access log).
 *
 * Entries are JSON lines in segment files named by their first seq. Each entry has `prev` (the
 * previous entry's hash) and `hash` = SHA-256(prev ‖ the entry's canonical JSON without `hash`).
 * Editing, inserting or deleting an entry in the middle breaks the chain at that point, and
 * [verify] reports where. Rotation keeps the chain: the first entry of a new segment points at the
 * last of the previous one, and when old segments are dropped the oldest kept entry's `prev` is the
 * anchor.
 *
 * Truncating the newest entries cannot be detected from the phone alone; a client that records
 * [head] (veil-ctl does) can detect it later, because the entry it saw must still be there with the
 * same hash.
 */
class Journal(
    private val dir: File,
    private val segmentBytes: Long = 2L * 1024 * 1024,
    private val keepSegments: Int = 16,
) {
    private var out: RandomAccessFile? = null
    private var outSize = 0L
    private var lastSeq = 0L
    private var lastHash = GENESIS
    private val _version = MutableStateFlow(0L)

    /** Changes on every append: observe it to refresh views. */
    val version: StateFlow<Long> = _version

    init {
        dir.mkdirs()
        recover()
    }

    /** Appends an entry with [fields] (kind, level, component, message, ...). Returns it. */
    @Synchronized
    fun append(fields: JSONObject): JSONObject {
        val e = JSONObject()
        e.put("seq", lastSeq + 1)
        e.put("unixMs", System.currentTimeMillis())
        for (k in fields.keys()) e.put(k, fields.get(k))
        e.put("prev", lastHash)
        val hash = hashOf(e)
        e.put("hash", hash)
        val line = (e.toString() + "\n").toByteArray(Charsets.UTF_8)
        if (out == null || outSize + line.size > segmentBytes) rotate(e.getLong("seq"))
        out!!.write(line)
        outSize += line.size
        lastSeq = e.getLong("seq")
        lastHash = hash
        _version.value = lastSeq
        return e
    }

    /** The newest entry's seq and hash, for clients that keep anchors. */
    @Synchronized fun head(): JSONObject = JSONObject().put("seq", lastSeq).put("hash", lastHash)

    /**
     * Entries with seq > [after], oldest first, at most [max], that pass [filter]. Also returns the
     * seq to continue from (it advances past filtered-out entries).
     */
    fun read(after: Long, max: Int, filter: (JSONObject) -> Boolean = { true }): JSONObject {
        val files = segments()
        val out = JSONArray()
        var next = after
        // Start in the last segment whose first seq is <= after + 1.
        val start = files.indexOfLast { firstSeqOf(it) <= after + 1 }.coerceAtLeast(0)
        loop@ for (f in files.drop(start)) {
            f.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val e = parse(line) ?: continue
                    val seq = e.getLong("seq")
                    if (seq <= after) continue
                    if (out.length() >= max) return@useLines
                    next = seq
                    if (filter(e)) out.put(e)
                }
            }
            if (out.length() >= max) break@loop
        }
        val oldest = files.firstOrNull()?.let { firstSeqOf(it) } ?: (lastSeq + 1)
        return JSONObject()
            .put("entries", out)
            .put("nextSeq", next)
            .put("dropped", (oldest - after - 1).coerceAtLeast(0))
    }

    /**
     * The newest [max] entries passing [filter], oldest first (for a viewer opening at the end).
     */
    fun tail(max: Int, filter: (JSONObject) -> Boolean = { true }): JSONArray {
        val ring = ArrayDeque<JSONObject>(max)
        for (f in segments()) {
            f.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val e = parse(line) ?: continue
                    if (!filter(e)) continue
                    if (ring.size == max) ring.removeFirst()
                    ring.addLast(e)
                }
            }
        }
        return JSONArray(ring.toList())
    }

    /** Walks the whole chain. Reports the first break, if any. */
    @Synchronized
    fun verify(): JSONObject {
        var prev: String? = null
        var prevSeq = 0L
        var count = 0L
        var first = 0L
        for (f in segments()) {
            var lineNo = 0
            for (line in f.readLines()) {
                lineNo++
                val e =
                    parse(line)
                        ?: return broken(count, first, "${f.name}:$lineNo is not a JSON entry")
                val seq = e.getLong("seq")
                if (prev == null) {
                    first = seq
                } else {
                    if (seq != prevSeq + 1)
                        return broken(count, first, "seq $seq follows $prevSeq (${f.name}:$lineNo)")
                    if (e.optString("prev") != prev) {
                        return broken(
                            count,
                            first,
                            "entry $seq does not point at entry $prevSeq's hash (${f.name}:$lineNo)",
                        )
                    }
                }
                val claimed = e.optString("hash")
                val copy = JSONObject(e.toString()).apply { remove("hash") }
                if (hashOf(copy) != claimed)
                    return broken(
                        count,
                        first,
                        "entry $seq's hash does not match its content (${f.name}:$lineNo)",
                    )
                prev = claimed
                prevSeq = seq
                count++
            }
        }
        return JSONObject()
            .put("ok", true)
            .put("entries", count)
            .put("firstSeq", first)
            .put("lastSeq", prevSeq)
            .put("headHash", prev ?: GENESIS)
    }

    private fun broken(count: Long, first: Long, why: String) =
        JSONObject()
            .put("ok", false)
            .put("entries", count)
            .put("firstSeq", first)
            .put("brokenAt", why)

    private fun segments(): List<File> =
        dir.listFiles { f -> f.name.endsWith(".jsonl") }.orEmpty().sortedBy { firstSeqOf(it) }

    private fun firstSeqOf(f: File) = f.name.removeSuffix(".jsonl").toLongOrNull() ?: 0L

    private fun rotate(firstSeq: Long) {
        out?.close()
        val f = File(dir, "%016d.jsonl".format(firstSeq))
        out = RandomAccessFile(f, "rw").apply { seek(length()) }
        outSize = f.length()
        val all = segments()
        if (all.size > keepSegments) all.take(all.size - keepSegments).forEach { it.delete() }
    }

    /** Reads the newest segment's last complete entry; drops a torn final line from a crash. */
    private fun recover() {
        val last = segments().lastOrNull() ?: return
        val bytes = last.readBytes()
        var end = bytes.size
        if (end > 0 && bytes[end - 1] != '\n'.code.toByte()) {
            end = bytes.lastIndexOf('\n'.code.toByte()) + 1
            RandomAccessFile(last, "rw").use { it.setLength(end.toLong()) }
        }
        val text = String(bytes, 0, end, Charsets.UTF_8)
        text
            .lineSequence()
            .filter { it.isNotBlank() }
            .lastOrNull()
            ?.let { parse(it) }
            ?.let {
                lastSeq = it.getLong("seq")
                lastHash = it.getString("hash")
            }
        out = RandomAccessFile(last, "rw").apply { seek(length()) }
        outSize = last.length()
    }

    private fun parse(line: String): JSONObject? =
        try {
            JSONObject(line)
        } catch (e: org.json.JSONException) {
            null
        }

    companion object {
        const val GENESIS = "0000000000000000000000000000000000000000000000000000000000000000"

        /**
         * SHA-256 over `prev` and the entry's canonical form: keys sorted, compact JSON, without
         * `hash`. Canonical so that any reader (veil-ctl, a script) can recompute it.
         */
        fun hashOf(e: JSONObject): String {
            val md = MessageDigest.getInstance("SHA-256")
            md.update(e.optString("prev").toByteArray(Charsets.UTF_8))
            md.update(canonical(e).toByteArray(Charsets.UTF_8))
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        fun canonical(v: Any?): String =
            when (v) {
                is JSONObject ->
                    v.keys()
                        .asSequence()
                        .filter { it != "hash" }
                        .sorted()
                        .joinToString(",", "{", "}") {
                            JSONObject.quote(it) + ":" + canonical(v.get(it))
                        }
                is JSONArray ->
                    (0 until v.length()).joinToString(",", "[", "]") { canonical(v.get(it)) }
                is String -> JSONObject.quote(v)
                null,
                JSONObject.NULL -> "null"
                else -> v.toString()
            }
    }
}
