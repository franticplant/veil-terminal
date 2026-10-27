package io.github.franticplant.veil.devd

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private val RAM = Color(0xFF61AFEF)
private val CPU = Color(0xFFE5C07B)
private val NET_IN = Color(0xFF98C379)
private val NET_OUT = Color(0xFFC678DD)
private val DIM = Color(0xFF7F848E)

fun humanBytes(b: Double): String =
    when {
        b < 1024 -> "%.0f B".format(b)
        b < 1024 * 1024 -> "%.1f KiB".format(b / 1024)
        b < 1024.0 * 1024 * 1024 -> "%.1f MiB".format(b / 1024 / 1024)
        else -> "%.2f GiB".format(b / 1024 / 1024 / 1024)
    }

/** Every series [keys] over the same window, scaled to the largest value, in [colors]. */
@Composable
private fun Chart(app: DevdApp, keys: List<String>, colors: List<Color>, height: Int = 56) {
    val version by app.metrics.version.collectAsState()
    val data = remember(version, keys) { keys.map { app.metrics.points(it) } }
    val maxV = data.flatten().maxOfOrNull { it.second }?.takeIf { it > 0 } ?: 1.0
    val t1 = data.flatten().maxOfOrNull { it.first } ?: 0L
    val t0 = t1 - Metrics.KEEP * Metrics.INTERVAL_MS
    Canvas(Modifier.fillMaxWidth().height(height.dp).background(Color(0xFF15181E))) {
        for ((i, pts) in data.withIndex()) {
            if (pts.size < 2) continue
            val path = Path()
            for ((j, p) in pts.withIndex()) {
                val x = ((p.first - t0).toFloat() / (t1 - t0).coerceAtLeast(1)) * size.width
                val y = size.height - (p.second / maxV).toFloat() * (size.height - 4) - 2
                if (j == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, colors[i % colors.size], style = Stroke(width = 2f))
        }
        drawLine(
            DIM.copy(alpha = 0.3f),
            Offset(0f, size.height - 1),
            Offset(size.width, size.height - 1),
        )
    }
}

@Composable
private fun Legend(vararg items: Pair<String, Color>) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for ((label, color) in items) Text("● $label", color = color, fontSize = 11.sp)
    }
}

/**
 * devd's Metrics tab: every process it can see, the network, and each connection, over 30 minutes.
 */
@Composable
fun MetricsView(app: DevdApp) {
    val latest by app.metrics.latest.collectAsState()
    val offAt by app.offAtMs.collectAsState()
    val scope = rememberCoroutineScope()
    Column(
        Modifier.padding(12.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (offAt == null) {
            Text("Sampling runs while the listener is on.", color = DIM)
        }
        val l = latest
        if (l != null && !l.optBoolean("veilConnected")) {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            try {
                                app.hooks.get()
                            } catch (e: Exception) {}
                        }
                    }
                }
            ) {
                Text("Connect to Veil to measure it")
            }
        }
        val processes = l?.optJSONArray("processes")
        if (processes != null) {
            for (i in 0 until processes.length()) {
                val p = processes.getJSONObject(i)
                ProcessCard(app, p)
            }
        }
        Card {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Network (Veil)", fontWeight = FontWeight.Bold)
                val inNow = app.metrics.points("net.inBps").lastOrNull()?.second ?: 0.0
                val outNow = app.metrics.points("net.outBps").lastOrNull()?.second ?: 0.0
                Legend(
                    "in ${humanBytes(inNow)}/s" to NET_IN,
                    "out ${humanBytes(outNow)}/s" to NET_OUT,
                )
                Chart(app, listOf("net.inBps", "net.outBps"), listOf(NET_IN, NET_OUT))
            }
        }
        val sessions = l?.optJSONArray("sessions")
        Card {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Connections", fontWeight = FontWeight.Bold)
                if (sessions == null || sessions.length() == 0) Text("None open.", color = DIM)
                if (sessions != null) {
                    for (i in 0 until sessions.length()) {
                        val s = sessions.getJSONObject(i)
                        val id = s.getLong("id")
                        val pq =
                            when (s.opt("postQuantum")) {
                                true -> "PQ"
                                false -> "classical"
                                else -> ""
                            }
                        Text(
                            "#$id ${s.optString("host")}:${s.optInt("port")} → ${s.optString("peer")} $pq",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                        )
                        Text(
                            "${s.optString("phase")} · in ${humanBytes(s.optLong("wireIn").toDouble())}" +
                                " (${humanBytes(s.optDouble("inBps", 0.0))}/s) · out " +
                                "${humanBytes(s.optLong("wireOut").toDouble())}" +
                                " (${humanBytes(s.optDouble("outBps", 0.0))}/s) · terminal " +
                                humanBytes(s.optLong("payloadIn").toDouble()),
                            fontSize = 11.sp,
                            color = DIM,
                        )
                        Chart(
                            app,
                            listOf("session.$id.inBps", "session.$id.outBps"),
                            listOf(NET_IN, NET_OUT),
                            36,
                        )
                    }
                }
            }
        }
        Text(
            "Samples every ${Metrics.INTERVAL_MS / 1000} s, kept for 30 minutes. From the laptop: " +
                "veil-ctl top, or call metrics.history.",
            style = MaterialTheme.typography.bodySmall,
            color = DIM,
        )
    }
}

@Composable
private fun ProcessCard(app: DevdApp, p: JSONObject) {
    val name = p.getString("name")
    Card {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row {
                Text(name, fontWeight = FontWeight.Bold, modifier = Modifier.width(120.dp))
                Text(
                    "pid ${p.optInt("pid")} · up ${p.optLong("uptimeMs") / 1000}s",
                    color = DIM,
                    fontSize = 12.sp,
                )
            }
            val rss = p.optLong("rssKb", -1)
            val pss = p.optLong("pssKb", -1)
            val cpu = p.opt("cpuPct") as? Double
            Text(
                listOfNotNull(
                        if (rss >= 0) "RSS ${humanBytes(rss * 1024.0)}" else null,
                        if (pss >= 0) "PSS ${humanBytes(pss * 1024.0)}" else null,
                        cpu?.let { "CPU %.1f%%".format(it) },
                        "${p.optInt("threads")} threads",
                        "${p.optInt("fds")} fds",
                    )
                    .joinToString(" · "),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
            Legend("RAM (RSS)" to RAM, "CPU %" to CPU)
            Chart(app, listOf("$name.rssKb"), listOf(RAM))
            Chart(app, listOf("$name.cpuPct"), listOf(CPU), 40)
        }
    }
}
