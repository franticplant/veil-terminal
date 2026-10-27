package io.github.franticplant.veil.dev.api

import android.os.Debug
import android.os.Process
import android.os.SystemClock
import java.io.File
import org.json.JSONObject

/**
 * This process measuring itself, in the same shape as the engine's ProcessMetrics: pid, uptime, CPU
 * time, RSS, PSS (Android's proportional share, which /proc cannot give cheaply), threads, fds.
 */
object ProcSelf {
    fun sample(name: String): JSONObject {
        val o =
            JSONObject()
                .put("name", name)
                .put("pid", Process.myPid())
                .put("uptimeMs", SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime())
                .put("cpuMs", Process.getElapsedCpuTime())
                .put("pssKb", Debug.getPss())
                .put(
                    "javaHeapKb",
                    (Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }) / 1024,
                )
                .put("nativeHeapKb", Debug.getNativeHeapAllocatedSize() / 1024)
                .put("fds", File("/proc/self/fd").list()?.size ?: -1)
        // Where the PSS goes, by Android's categories (summary.* keys of Debug.MemoryInfo).
        val mi = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        val breakdown = JSONObject()
        for ((k, v) in mi.memoryStats) {
            if (k.startsWith("summary.") && k != "summary.total-pss") {
                breakdown.put(k.removePrefix("summary."), v.toLongOrNull() ?: continue)
            }
        }
        o.put("pssBreakdownKb", breakdown)
        try {
            for (line in File("/proc/self/status").readLines()) {
                val (k, v) =
                    line.split(':', limit = 2).let { it[0] to it.getOrElse(1) { "" }.trim() }
                when (k) {
                    "VmRSS" -> o.put("rssKb", v.substringBefore(' ').toLongOrNull() ?: -1)
                    "Threads" -> o.put("threads", v.toIntOrNull() ?: -1)
                }
            }
        } catch (e: java.io.IOException) {}
        return o
    }
}
