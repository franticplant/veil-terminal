package io.github.franticplant.veil.dev

import android.os.SystemClock
import io.github.franticplant.veil.VeilApplication
import io.github.franticplant.veil.dev.api.ApiException
import io.github.franticplant.veil.dev.api.ErrorCode
import io.github.franticplant.veil.dev.api.Params
import io.github.franticplant.veil.engine.Prompt
import io.github.franticplant.veil.engine.Session
import io.github.franticplant.veil.engine.Target
import io.github.franticplant.veil.engine.proto.HostKeyDecision
import io.github.franticplant.veil.engine.proto.SessionPhase
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Built-in, versioned test scenarios. Each drives a real session through the same session layer the
 * UI uses and returns a step-by-step report. Reports never contain the password or screen content
 * beyond the markers the scenario itself prints.
 */
internal class Scenarios(private val app: VeilApplication) {
    private val registry get() = app.sessions

    companion object {
        /** Bumped when a scenario's steps or report change meaning. */
        const val VERSION = 1

        /** File descriptors or threads a leak-check tolerates gaining (caches, pools). */
        const val LEAK_SLACK = 8

        val ALL =
            linkedMapOf(
                "connect-echo" to
                    "Connect, authenticate, print a marker and find it on screen, resize, exit.",
                "resize-storm" to
                    "Connect, resize `count` times (default 50) to random sizes, then check the " +
                        "remote and the screen agree on the final size.",
                "throughput" to
                    "Connect, print `bytes` of output (default 1000000), time it and report the " +
                        "engine's counters.",
                "reconnect" to
                    "Connect, authenticate and close `count` times (default 5), timing each.",
                "leak-check" to
                    "Runs `count` full sessions (default 30: connect, echo, exit) and compares the " +
                        "process's file descriptors, threads and memory before and after.",
            )
    }

    private class Failed(message: String) : Exception(message)

    private class Report(val name: String) {
        val steps = JSONArray()
        val started = SystemClock.elapsedRealtime()
        var current = ""
        var ok = true
        var error: String? = null
        val metrics = JSONObject()

        suspend fun <T> step(name: String, block: suspend () -> T): T {
            current = name
            val t0 = SystemClock.elapsedRealtime()
            try {
                val v = block()
                steps.put(
                    JSONObject()
                        .put("step", name)
                        .put("ok", true)
                        .put("ms", SystemClock.elapsedRealtime() - t0)
                )
                return v
            } catch (e: Exception) {
                steps.put(
                    JSONObject()
                        .put("step", name)
                        .put("ok", false)
                        .put("ms", SystemClock.elapsedRealtime() - t0)
                        .put("detail", e.message)
                )
                throw e
            }
        }

        fun json(): JSONObject =
            JSONObject()
                .put("scenario", name)
                .put("version", VERSION)
                .put("ok", ok)
                .put("error", error ?: JSONObject.NULL)
                .put("totalMs", SystemClock.elapsedRealtime() - started)
                .put("steps", steps)
                .put("metrics", metrics)
    }

    private data class Setup(val target: Target, val password: String?, val hostKey: String)

    fun run(p: Params): JSONObject {
        val name = p.string("name")
        val target =
            if (p.has("profile")) {
                    val label = p.string("profile")
                    app.profiles.all.value.firstOrNull {
                        it.label == label || it.displayName == label
                    } ?: throw ApiException(ErrorCode.NOT_FOUND, "no profile '$label'")
                } else {
                    if (!p.has("host") || !p.has("user")) {
                        throw ApiException(
                            ErrorCode.INVALID_PARAMS,
                            "give either profile, or host and user",
                        )
                    }
                    Target(p.string("host"), p.intOr("port", 22), p.string("user"))
                }
                .let { if (p.has("key")) it.copy(keyId = p.string("key")) else it }
        val setup =
            Setup(
                target,
                if (p.has("password")) p.string("password") else null,
                p.stringOr("hostKey", "once"),
            )
        val report = Report(name)
        val opened = ArrayList<Session>()
        app.log.info("dev", "scenario $name v$VERSION starting against ${target.displayName}")
        runBlocking {
            val done =
                withTimeoutOrNull(p.longOr("timeoutMs", 60_000)) {
                    try {
                        when (name) {
                            "connect-echo" -> connectEcho(report, setup, opened)
                            "resize-storm" ->
                                resizeStorm(report, setup, opened, p.intOr("count", 50))
                            "throughput" ->
                                throughput(report, setup, opened, p.longOr("bytes", 1_000_000))
                            "reconnect" -> reconnect(report, setup, opened, p.intOr("count", 5))
                            "leak-check" -> leakCheck(report, setup, opened, p.intOr("count", 30))
                        }
                    } catch (e: Failed) {
                        report.ok = false
                        report.error = "${report.current}: ${e.message}"
                    } catch (e: ApiException) {
                        report.ok = false
                        report.error = "${report.current}: ${e.message}"
                    }
                    true
                }
            if (done == null) {
                report.ok = false
                report.error = "timed out during '${report.current}'"
            }
        }
        // Leave nothing behind: close what is still open, forget what is closed.
        for (s in opened) {
            if (!s.status.value.closed) s.close()
        }
        runBlocking {
            for (s in opened) withTimeoutOrNull(3_000) { s.status.first { it.closed } }
        }
        opened.filter { it.status.value.closed }.forEach { registry.forget(it) }
        app.log.info("dev", "scenario $name finished: ${if (report.ok) "ok" else report.error}")
        return report.json()
    }

    // ---- scenarios ----------------------------------------------------------

    private suspend fun connectEcho(r: Report, setup: Setup, opened: MutableList<Session>) {
        val s = connect(r, setup, opened, 80, 24)
        shellReady(r, s)
        echoMarker(r, s)
        r.step("resize to 100x30") {
            s.resize(100, 30)
            val nonce = nonce()
            s.input("stty size; printf 'veil-%s-ok\\n' $nonce\r".toByteArray())
            waitLine(s, Regex("^30 100$"), 10_000)
            waitLine(s, Regex("^veil-$nonce-ok$"), 10_000)
            if (!screenIs(s, 100, 30)) throw Failed("screen is ${dims(s)}, not 100x30")
        }
        exit(r, s)
    }

    private suspend fun resizeStorm(
        r: Report,
        setup: Setup,
        opened: MutableList<Session>,
        count: Int,
    ) {
        val s = connect(r, setup, opened, 80, 24)
        shellReady(r, s)
        r.step("$count resizes") {
            repeat(count) { s.resize(Random.nextInt(20, 201), Random.nextInt(5, 61)) }
            s.resize(97, 31)
        }
        r.step("sizes agree") {
            val nonce = nonce()
            s.input("stty size; printf 'veil-%s-ok\\n' $nonce\r".toByteArray())
            waitLine(s, Regex("^veil-$nonce-ok$"), 15_000)
            waitLine(s, Regex("^31 97$"), 5_000)
            if (!screenIs(s, 97, 31)) throw Failed("screen is ${dims(s)}, not 97x31")
        }
        exit(r, s)
    }

    private suspend fun throughput(
        r: Report,
        setup: Setup,
        opened: MutableList<Session>,
        bytes: Long,
    ) {
        val s = connect(r, setup, opened, 100, 40)
        shellReady(r, s)
        val before = sessionStats(s)
        val nonce = nonce()
        val line = "x".repeat(99)
        val t0 = SystemClock.elapsedRealtime()
        r.step("print $bytes bytes") {
            s.input(
                "yes $line | head -c $bytes; printf '\\nveil-%s-done\\n' $nonce\r".toByteArray()
            )
            waitLine(s, Regex("^veil-$nonce-done$"), 300_000)
        }
        val ms = SystemClock.elapsedRealtime() - t0
        val after = sessionStats(s)
        r.metrics
            .put("bytesRequested", bytes)
            .put("ms", ms)
            .put("bytesFromRemote", after.first - before.first)
            .put("framesSent", after.second - before.second)
            .put("bytesPerSecond", if (ms > 0) (after.first - before.first) * 1000 / ms else 0)
        exit(r, s)
    }

    private suspend fun reconnect(
        r: Report,
        setup: Setup,
        opened: MutableList<Session>,
        count: Int,
    ) {
        val times = JSONArray()
        for (i in 1..count) {
            val t0 = SystemClock.elapsedRealtime()
            val s = connect(r, setup, opened, 80, 24, "#$i ")
            times.put(SystemClock.elapsedRealtime() - t0)
            r.step("#$i close") {
                s.close()
                s.status.first { it.closed }
            }
        }
        r.metrics.put("connectMs", times)
    }

    private suspend fun leakCheck(
        r: Report,
        setup: Setup,
        opened: MutableList<Session>,
        count: Int,
    ) {
        // One session first, so lazily created things (the engine, thread pools) are not counted.
        val warm = connect(r, setup, opened, 80, 24, "warm-up ")
        shellReady(r, warm)
        exit(r, warm)
        settle()
        val before = ProcessStats.now()
        for (i in 1..count) {
            val s = connect(r, setup, opened, 80, 24, "#$i ")
            shellReady(r, s)
            echoMarker(r, s)
            exit(r, s)
            // Forget as we go, as the UI's "close tab" does.
            registry.forget(s)
            opened.remove(s)
        }
        settle()
        val after = ProcessStats.now()
        r.metrics.put("before", before.json()).put("after", after.json()).put("sessions", count)
        val fdGrowth = after.fds - before.fds
        val threadGrowth = after.threads - before.threads
        r.step("no leak") {
            if (fdGrowth > LEAK_SLACK)
                throw Failed("file descriptors grew by $fdGrowth over $count sessions")
            if (threadGrowth > LEAK_SLACK)
                throw Failed("threads grew by $threadGrowth over $count sessions")
        }
    }

    /** Lets finished sessions' resources go (closing sockets, ending tasks) before measuring. */
    private suspend fun settle() {
        delay(1500)
        System.gc()
        delay(500)
    }

    // ---- steps --------------------------------------------------------------

    private suspend fun connect(
        r: Report,
        setup: Setup,
        opened: MutableList<Session>,
        cols: Int,
        rows: Int,
        prefix: String = "",
    ): Session {
        val s = r.step("${prefix}open") { registry.open(setup.target, cols, rows) }
        opened.add(s)
        r.step("${prefix}authenticate") {
            while (true) {
                val (prompt, status) =
                    combine(s.prompt, s.status) { a, b -> a to b }
                        .first { (a, b) ->
                            a != null || b.phase == SessionPhase.SESSION_PHASE_OPEN || b.closed
                        }
                if (status.phase == SessionPhase.SESSION_PHASE_OPEN) break
                if (status.closed) throw Failed("session closed: ${status.detail}")
                when (prompt) {
                    is Prompt.HostKey -> {
                        val d =
                            when (setup.hostKey) {
                                "remember" -> HostKeyDecision.HOST_KEY_DECISION_ACCEPT_AND_REMEMBER
                                "once" -> HostKeyDecision.HOST_KEY_DECISION_ACCEPT_ONCE
                                else -> {
                                    s.answerHostKey(
                                        prompt.prompt,
                                        HostKeyDecision.HOST_KEY_DECISION_REJECT,
                                    )
                                    throw Failed(
                                        "unknown host key ${prompt.prompt.fingerprint}, rejected as asked"
                                    )
                                }
                            }
                        r.metrics.put("hostKey", prompt.prompt.fingerprint)
                        s.answerHostKey(prompt.prompt, d)
                    }
                    is Prompt.Auth -> {
                        val password =
                            setup.password
                                ?: throw Failed(
                                    "the server asks for credentials; no password given"
                                )
                        if (prompt.prompt.retry) throw Failed("the password was rejected")
                        s.answerAuth(prompt.prompt, List(prompt.prompt.fieldsCount) { password })
                    }
                    null -> {}
                }
            }
        }
        return s
    }

    /** Waits for the shell to print something, then for output to settle. */
    private suspend fun shellReady(r: Report, s: Session) =
        r.step("shell ready") {
            waitLine(s, Regex("\\S"), 15_000)
            var last = s.screenVersion.value
            repeat(20) {
                delay(150)
                val now = s.screenVersion.value
                if (now == last) return@step
                last = now
            }
        }

    private suspend fun echoMarker(r: Report, s: Session) =
        r.step("echo marker") {
            val nonce = nonce()
            // The typed command never contains the marker as printed, so finding it proves the
            // remote ran it.
            s.input("printf 'veil-%s-ok\\n' $nonce\r".toByteArray())
            waitLine(s, Regex("^veil-$nonce-ok$"), 10_000)
        }

    private suspend fun exit(r: Report, s: Session) =
        r.step("exit") {
            s.input("exit\r".toByteArray())
            val st = withTimeoutOrNull(10_000) { s.status.first { it.closed } }
            if (st == null) {
                s.close()
                throw Failed("the shell did not exit")
            }
        }

    // ---- helpers ------------------------------------------------------------

    private suspend fun waitLine(s: Session, regex: Regex, timeoutMs: Long) {
        val st =
            withTimeoutOrNull(timeoutMs) {
                combine(s.screenVersion, s.status) { _, st -> st }
                    .first { st -> lines(s).any { regex.containsMatchIn(it) } || st.closed }
            } ?: throw Failed("no line matching /${regex.pattern}/ within $timeoutMs ms")
        if (lines(s).none { regex.containsMatchIn(it) })
            throw Failed("session closed: ${st.detail}")
    }

    private fun lines(s: Session): List<String> =
        synchronized(s.screen) { s.screen.text().map { it.trim() } }

    private fun screenIs(s: Session, cols: Int, rows: Int) =
        synchronized(s.screen) { s.screen.cols == cols && s.screen.rows == rows }

    private fun dims(s: Session) = synchronized(s.screen) { "${s.screen.cols}x${s.screen.rows}" }

    /** (bytes from remote, frames sent) for [s], from the engine. */
    private suspend fun sessionStats(s: Session): Pair<Long, Long> {
        val st =
            registry.engineStats().sessionsList.firstOrNull { it.sessionId == s.id }
                ?: return 0L to 0L
        return st.bytesFromRemote to st.framesSent
    }

    private fun nonce() = Random.nextLong(0x1000_0000, 0xffff_ffff).toString(16)
}

/** File descriptors, threads and memory of this process, from /proc. */
internal data class ProcessStats(val fds: Int, val threads: Int, val rssKb: Long, val pssKb: Long) {
    fun json(): JSONObject =
        JSONObject().put("fds", fds).put("threads", threads).put("rssKb", rssKb).put("pssKb", pssKb)

    companion object {
        fun now(): ProcessStats {
            val status =
                java.io.File("/proc/self/status").readLines().associate {
                    it.substringBefore(':') to it.substringAfter(':').trim()
                }
            return ProcessStats(
                fds = java.io.File("/proc/self/fd").list()?.size ?: -1,
                threads = status["Threads"]?.toIntOrNull() ?: -1,
                rssKb = status["VmRSS"]?.substringBefore(' ')?.toLongOrNull() ?: -1,
                pssKb = android.os.Debug.getPss(),
            )
        }
    }
}
