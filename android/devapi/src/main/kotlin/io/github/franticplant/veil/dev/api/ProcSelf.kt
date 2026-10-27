package io.github.franticplant.veil.dev.api

import android.os.Debug
import android.os.Process
import android.os.SystemClock
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * This process measuring itself, in the same shape as the engine's ProcessMetrics: pid, uptime, CPU
 * time, RSS, PSS (Android's proportional share, which /proc cannot give cheaply), threads, fds.
 */
object ProcSelf {
    /**
     * Where this process's PSS lives, mapping by mapping: /proc/self/smaps grouped by mapping name
     * (a file path, or an anonymous region's label such as "[anon:dalvik-main space]"), the [top]
     * largest first. Reading smaps walks every mapping, so this is for on-demand calls, not the
     * sampler.
     */
    fun smaps(top: Int): JSONObject {
        class Acc(var pss: Long = 0, var privateDirty: Long = 0, var swap: Long = 0, var n: Int = 0)
        val groups = HashMap<String, Acc>()
        var current: Acc? = null
        var total = 0L
        File("/proc/self/smaps").forEachLine { line ->
            val colon = line.indexOf(':')
            val first = line.substringBefore(' ')
            if (colon < 0 || first.contains('-') && !first.endsWith(':')) {
                // A mapping header: "start-end perms offset dev inode [name]".
                val parts = line.split(Regex("\\s+"), limit = 6)
                val name = parts.getOrNull(5)?.trim()?.ifEmpty { null } ?: "[anon]"
                current = groups.getOrPut(name) { Acc() }.also { it.n++ }
                return@forEachLine
            }
            val kb = line.substring(colon + 1).trim().substringBefore(' ').toLongOrNull() ?: 0
            val acc = current ?: return@forEachLine
            when (line.substring(0, colon)) {
                "Pss" -> {
                    acc.pss += kb
                    total += kb
                }
                "Private_Dirty" -> acc.privateDirty += kb
                "Swap" -> acc.swap += kb
            }
        }
        val rows =
            JSONArray().apply {
                for ((name, a) in groups.entries.sortedByDescending { it.value.pss }.take(top)) {
                    put(
                        JSONObject()
                            .put("name", name)
                            .put("pssKb", a.pss)
                            .put("privateDirtyKb", a.privateDirty)
                            .put("swapKb", a.swap)
                            .put("mappings", a.n)
                    )
                }
            }
        return JSONObject().put("totalPssKb", total).put("mappings", rows)
    }

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
                .put("javaHeapFootprintKb", Runtime.getRuntime().totalMemory() / 1024)
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
