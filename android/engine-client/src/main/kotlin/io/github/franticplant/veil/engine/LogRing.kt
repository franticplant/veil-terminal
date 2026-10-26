package io.github.franticplant.veil.engine

import android.util.Log

/** One app-side log entry. [sessionId] is 0 when it belongs to no session. */
data class LogEntry(
    val seq: Long,
    val unixMs: Long,
    val level: LogRing.Level,
    val component: String,
    val sessionId: Long,
    val message: String,
)

/**
 * The app's bounded log: the frontend-side counterpart of the engine's log ring. Entries are also
 * written to logcat. Messages must never contain secrets, typed input or screen content.
 *
 * It is handed to the code that logs; nothing reaches it through a global.
 */
class LogRing(private val capacity: Int = 4000, private val logcat: Boolean = true) {
    enum class Level {
        DEBUG,
        INFO,
        WARN,
        ERROR,
    }

    private val entries = ArrayDeque<LogEntry>(capacity)
    private var nextSeq = 1L

    fun log(level: Level, component: String, message: String, sessionId: Long = 0) {
        val msg = if (message.length > MAX_MESSAGE) message.take(MAX_MESSAGE) + "…" else message
        synchronized(this) {
            if (entries.size == capacity) entries.removeFirst()
            entries.addLast(
                LogEntry(nextSeq++, System.currentTimeMillis(), level, component, sessionId, msg)
            )
        }
        if (logcat) {
            val tag = "Veil/$component"
            when (level) {
                Level.DEBUG -> Log.d(tag, msg)
                Level.INFO -> Log.i(tag, msg)
                Level.WARN -> Log.w(tag, msg)
                Level.ERROR -> Log.e(tag, msg)
            }
        }
    }

    fun info(component: String, message: String, sessionId: Long = 0) =
        log(Level.INFO, component, message, sessionId)

    fun warn(component: String, message: String, sessionId: Long = 0) =
        log(Level.WARN, component, message, sessionId)

    /** The newest entry's seq, 0 if there is none. */
    val lastSeq: Long
        get() = synchronized(this) { nextSeq - 1 }

    /**
     * Entries after [afterSeq], oldest first, at most [max], at or above [minLevel], from
     * [component] if given, containing [grep] if given. Returns them with the seq to pass next time
     * and how many entries after [afterSeq] the ring had already dropped.
     */
    fun read(
        afterSeq: Long,
        max: Int,
        minLevel: Level = Level.DEBUG,
        component: String? = null,
        grep: String? = null,
    ): Batch =
        synchronized(this) {
            val oldest = entries.firstOrNull()?.seq ?: nextSeq
            val dropped = (oldest - afterSeq - 1).coerceAtLeast(0)
            var next = maxOf(afterSeq, oldest - 1)
            val out = ArrayList<LogEntry>()
            for (e in entries) {
                if (e.seq <= afterSeq) continue
                if (out.size == max) break
                next = e.seq
                if (e.level < minLevel) continue
                if (component != null && e.component != component) continue
                if (grep != null && !e.message.contains(grep, ignoreCase = true)) continue
                out.add(e)
            }
            Batch(out, next, dropped)
        }

    data class Batch(val entries: List<LogEntry>, val nextSeq: Long, val dropped: Long)

    companion object {
        const val MAX_MESSAGE = 1024
    }
}
