package io.github.franticplant.veil.devd

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlinx.coroutines.launch
import org.json.JSONObject

private val RAM = Color(0xFF61AFEF)
private val CPU = Color(0xFFE5C07B)
private val NET_IN = Color(0xFF98C379)
private val NET_OUT = Color(0xFFC678DD)
private val DIM = Color(0xFF7F848E)
private val RSS = Color(0xFF56B6C2)
private val THREADS = Color(0xFFE06C75)

private fun rate(bps: Double) = "${humanBytes(bps)}/s"

private fun percent(v: Double) = "%.1f%%".format(v)

fun humanBytes(b: Double): String =
    when {
        b < 1024 -> "%.0f B".format(b)
        b < 1024 * 1024 -> "%.1f KiB".format(b / 1024)
        b < 1024.0 * 1024 * 1024 -> "%.1f MiB".format(b / 1024 / 1024)
        else -> "%.2f GiB".format(b / 1024 / 1024 / 1024)
    }

/**
 * The time window every chart on the tab shows, and the crosshair they share: scrubbing one chart
 * moves the cursor on all of them, so RAM, CPU and traffic line up at the same moment.
 */
class ChartWindow {
    /** Width of the window in ms. */
    var spanMs by mutableLongStateOf(5 * 60_000L)
    /** Right edge of the window; null follows the newest sample. */
    var endMs by mutableStateOf<Long?>(null)
    /** Time under the crosshair, or null. */
    var cursorMs by mutableStateOf<Long?>(null)

    fun end(newest: Long) = endMs ?: newest

    fun zoom(factor: Float, newest: Long) {
        spanMs = (spanMs / factor).toLong().coerceIn(MIN_SPAN_MS, MAX_SPAN_MS)
        endMs?.let { if (it >= newest) endMs = null }
    }

    fun pan(deltaMs: Long, newest: Long) {
        val e = (end(newest) + deltaMs).coerceIn(newest - MAX_SPAN_MS + spanMs, newest)
        endMs = if (e >= newest) null else e
    }

    companion object {
        const val MIN_SPAN_MS = 30_000L
        const val MAX_SPAN_MS = Metrics.KEEP * Metrics.INTERVAL_MS
    }
}

/** A series to draw: its key, what to call it, and its color. */
class SeriesSpec(val key: String, val label: String, val color: Color)

private val PLOT_BG = Color(0xFF15181E)
private val GRID = Color(0x337F848E)
private val CURSOR = Color(0xCCE6E6E6)

/** The point nearest to [t] in [pts] (sorted by time), if one lies within two intervals. */
private fun nearest(pts: List<Pair<Long, Double>>, t: Long): Pair<Long, Double>? {
    if (pts.isEmpty()) return null
    var lo = 0
    var hi = pts.size - 1
    while (lo < hi) {
        val mid = (lo + hi) / 2
        if (pts[mid].first < t) lo = mid + 1 else hi = mid
    }
    val best =
        listOfNotNull(pts.getOrNull(lo - 1), pts.getOrNull(lo)).minByOrNull { abs(it.first - t) }
    return best?.takeIf { abs(it.first - t) <= 2 * Metrics.INTERVAL_MS }
}

/** A "nice" axis maximum at or above [v]: 1, 2 or 5 times a power of ten. */
private fun niceMax(v: Double): Double {
    if (v <= 0) return 1.0
    val p = 10.0.pow(floor(log10(v)))
    return listOf(1.0, 2.0, 5.0, 10.0).first { it * p >= v } * p
}

private val clock = SimpleDateFormat("HH:mm:ss", Locale.ROOT)

/**
 * An interactive line chart of [series] in the shared [window].
 *
 * Pinch zooms time, a horizontal drag scrolls back (vertical drags still scroll the page), a tap
 * puts the crosshair down, a long press and drag scrubs it, and a double tap clears it and returns
 * to live. Tapping a legend entry hides or shows that series. Values are formatted by [fmt].
 */
@Composable
private fun Chart(
    app: RemoteDevd,
    window: ChartWindow,
    series: List<SeriesSpec>,
    fmt: (Double) -> String,
    height: Int = 96,
) {
    val version by app.metricsVersion.collectAsState()
    val keys = series.map { it.key }
    var data by remember(keys) { mutableStateOf<Map<String, List<Pair<Long, Double>>>>(emptyMap()) }
    LaunchedEffect(version, keys) {
        data =
            try {
                keys.associateWith { app.points(it) }
            } catch (e: Exception) {
                data
            }
    }
    var hidden by remember(keys) { mutableStateOf(emptySet<String>()) }
    val newest = data.values.maxOfOrNull { it.lastOrNull()?.first ?: 0L } ?: 0L
    val t1 = window.end(newest)
    val t0 = t1 - window.spanMs
    val shown = series.filter { it.key !in hidden }
    val visible = shown.associate { s ->
        s.key to data[s.key].orEmpty().filter { it.first in t0..t1 }
    }
    val yMax = niceMax(visible.values.flatten().maxOfOrNull { it.second } ?: 0.0)
    val cursor = window.cursorMs?.takeIf { it in t0..t1 }
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = DIM, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
    val latestNewest by rememberUpdatedState(newest)

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // Legend: the value at the crosshair, or the newest one; tap to hide a series.
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (s in series) {
                val pts = data[s.key].orEmpty()
                val v =
                    if (cursor != null) nearest(pts, cursor)?.second else pts.lastOrNull()?.second
                val off = s.key in hidden
                Text(
                    "${if (off) "○" else "●"} ${s.label} ${v?.let(fmt) ?: "–"}",
                    color = if (off) DIM else s.color,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier =
                        Modifier.clickable { hidden = if (off) hidden - s.key else hidden + s.key },
                )
            }
        }
        Canvas(
            Modifier.fillMaxWidth()
                .height(height.dp)
                .background(PLOT_BG)
                .pointerInput(window) {
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        fun timeAt(x: Float) =
                            window.end(latestNewest) - window.spanMs +
                                (x / size.width * window.spanMs).toLong()
                        // 0 undecided, 1 pan, 2 zoom, 3 scrub, -1 a vertical scroll: not ours.
                        var mode = 0
                        var moved = Offset.Zero
                        while (true) {
                            val ev = awaitPointerEvent()
                            val pressed = ev.changes.filter { it.pressed }
                            if (pressed.isEmpty()) {
                                if (mode == 0) window.cursorMs = timeAt(down.position.x)
                                break
                            }
                            if (pressed.size >= 2) mode = 2
                            when (mode) {
                                2 -> {
                                    val z = ev.calculateZoom()
                                    if (z != 1f) window.zoom(z, latestNewest)
                                    ev.changes.forEach { it.consume() }
                                }
                                1 -> {
                                    val dx = ev.calculatePan().x
                                    val msPerPx = window.spanMs.toFloat() / size.width
                                    window.pan((-dx * msPerPx).toLong(), latestNewest)
                                    ev.changes.forEach { it.consume() }
                                }
                                3 -> {
                                    window.cursorMs = timeAt(pressed[0].position.x)
                                    ev.changes.forEach { it.consume() }
                                }
                                0 -> {
                                    moved += ev.calculatePan()
                                    val held =
                                        pressed[0].uptimeMillis - down.uptimeMillis >
                                            viewConfiguration.longPressTimeoutMillis
                                    when {
                                        abs(moved.y) > slop && abs(moved.y) > abs(moved.x) ->
                                            mode = -1
                                        abs(moved.x) > slop -> mode = 1
                                        held -> {
                                            mode = 3
                                            window.cursorMs = timeAt(pressed[0].position.x)
                                        }
                                    }
                                }
                            }
                            if (mode == -1) break
                        }
                    }
                }
                .pointerInput(window) {
                    detectTapGestures(
                        onDoubleTap = {
                            window.cursorMs = null
                            window.endMs = null
                        }
                    )
                }
        ) {
            val w = size.width
            val top = 4f
            val bottom = size.height - 14f
            fun x(t: Long) = (t - t0).toFloat() / (t1 - t0).coerceAtLeast(1) * w

            fun y(v: Double) = bottom - (v / yMax).toFloat() * (bottom - top)
            // Grid at 0, half and full, labelled on the left.
            for (f in listOf(0.0, 0.5, 1.0)) {
                val gy = y(yMax * f)
                drawLine(GRID, Offset(0f, gy), Offset(w, gy))
                if (f > 0) drawText(measurer, fmt(yMax * f), Offset(2f, gy + 1f), labelStyle)
            }
            // Time labels at the window's ends.
            drawText(measurer, clock.format(Date(t0)), Offset(2f, bottom + 1f), labelStyle)
            val endLabel = if (window.endMs == null) "live" else clock.format(Date(t1))
            val endW = measurer.measure(endLabel, labelStyle).size.width
            drawText(measurer, endLabel, Offset(w - endW - 2f, bottom + 1f), labelStyle)
            for (s in shown) {
                val pts = visible[s.key].orEmpty()
                if (pts.size < 2) continue
                val path = Path()
                var last = Long.MIN_VALUE / 2
                for (p in pts) {
                    // A gap of more than two intervals (sampling stopped) breaks the line.
                    if (p.first - last > 2 * Metrics.INTERVAL_MS) {
                        path.moveTo(x(p.first), y(p.second))
                    } else {
                        path.lineTo(x(p.first), y(p.second))
                    }
                    last = p.first
                }
                drawPath(path, s.color, style = Stroke(width = 2f))
            }
            if (cursor != null) {
                val cx = x(cursor)
                drawLine(CURSOR, Offset(cx, top), Offset(cx, bottom))
                for (s in shown) {
                    nearest(visible[s.key].orEmpty(), cursor)?.let { p ->
                        drawCircle(s.color, 4f, Offset(x(p.first), y(p.second)))
                    }
                }
                val label = clock.format(Date(cursor))
                val lw = measurer.measure(label, labelStyle).size.width
                drawText(
                    measurer,
                    label,
                    Offset((cx - lw / 2f).coerceIn(0f, (w - lw).coerceAtLeast(0f)), top),
                    labelStyle.copy(color = CURSOR),
                )
            }
        }
    }
}

/** Window controls: fixed ranges, and a way back to live after scrolling. */
@Composable
private fun RangeBar(window: ChartWindow) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val ranges =
            listOf("1m" to 60_000L, "5m" to 300_000L, "15m" to 900_000L, "30m" to 1_800_000L)
        for ((label, ms) in ranges) {
            FilterChip(
                selected = window.spanMs == ms,
                onClick = { window.spanMs = ms },
                label = { Text(label, fontSize = 12.sp) },
            )
        }
        if (window.endMs != null || window.cursorMs != null) {
            TextButton(
                onClick = {
                    window.endMs = null
                    window.cursorMs = null
                }
            ) {
                Text("Live")
            }
        }
    }
}

/**
 * devd's Metrics tab: every process it can see, the network, and each connection, over 30 minutes.
 */
@Composable
fun MetricsView(app: RemoteDevd) {
    val version by app.metricsVersion.collectAsState()
    var latest by remember { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(version) {
        latest =
            try {
                app.call("metrics.latest") as? JSONObject
            } catch (e: Exception) {
                null
            }
    }
    val offAt by app.offAtMs.collectAsState()
    val scope = rememberCoroutineScope()
    val window = remember { ChartWindow() }
    Column(
        Modifier.padding(12.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (offAt == null) {
            Text("Sampling runs while the listener is on.", color = DIM)
        }
        RangeBar(window)
        val l = latest
        if (l != null && !l.optBoolean("veilConnected")) {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        try {
                            app.call("hooks.connect")
                        } catch (e: Exception) {}
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
                ProcessCard(app, window, p)
            }
        }
        Card {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Network (Veil)", fontWeight = FontWeight.Bold)
                Chart(
                    app,
                    window,
                    listOf(
                        SeriesSpec("net.inBps", "in", NET_IN),
                        SeriesSpec("net.outBps", "out", NET_OUT),
                    ),
                    ::rate,
                )
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
                            window,
                            listOf(
                                SeriesSpec("session.$id.inBps", "in", NET_IN),
                                SeriesSpec("session.$id.outBps", "out", NET_OUT),
                            ),
                            ::rate,
                            72,
                        )
                    }
                }
            }
        }
        Text(
            "Samples every ${Metrics.INTERVAL_MS / 1000} s, kept for 30 minutes. Pinch to zoom, " +
                "drag sideways to scroll back, tap or hold to read values, double tap for live. " +
                "From the laptop: veil-ctl top, or call metrics.history.",
            style = MaterialTheme.typography.bodySmall,
            color = DIM,
        )
    }
}

@Composable
private fun ProcessCard(app: RemoteDevd, window: ChartWindow, p: JSONObject) {
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
            Chart(
                app,
                window,
                listOf(
                    SeriesSpec("$name.pssKb", "PSS", RAM),
                    SeriesSpec("$name.rssKb", "RSS", RSS),
                ),
                { humanBytes(it * 1024) },
            )
            Chart(app, window, listOf(SeriesSpec("$name.cpuPct", "CPU", CPU)), ::percent, 72)
            Chart(
                app,
                window,
                listOf(
                    SeriesSpec("$name.threads", "threads", THREADS),
                    SeriesSpec("$name.fds", "fds", DIM),
                ),
                { "%.0f".format(it) },
                64,
            )
        }
    }
}
