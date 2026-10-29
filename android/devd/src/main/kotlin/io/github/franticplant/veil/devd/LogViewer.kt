package io.github.franticplant.veil.devd

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.franticplant.veil.dev.api.ApiException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Where the viewer reads from. */
enum class LogSource(val label: String) {
    DEVD("devd"),
    ACCESS("Access"),
    APP("Veil app"),
    ENGINE("Veil engine"),
    VM("Veil VM"),
}

/** Entries kept in memory per source; older ones fall off the top. */
private const val KEEP = 1500

private object Palette {
    val bg = Color(0xFF111318)
    val row = Color(0xFF161A21)
    val dim = Color(0xFF6B7280)
    val text = Color(0xFFD8DEE9)
    val error = Color(0xFFE06C75)
    val warn = Color(0xFFE5C07B)
    val info = Color(0xFF61AFEF)
    val debug = Color(0xFF7F848E)
    val session = Color(0xFFC678DD)
    val ident = Color(0xFF56B6C2)
    val number = Color(0xFFD19A66)
    val string = Color(0xFF98C379)
    val ok = Color(0xFF98C379)
    val match = Color(0xFF6B5A00)
    val components =
        listOf(
            Color(0xFF61AFEF),
            Color(0xFFC678DD),
            Color(0xFF56B6C2),
            Color(0xFFE5C07B),
            Color(0xFF98C379),
            Color(0xFFD19A66),
            Color(0xFFBE5046),
            Color(0xFF8FBCBB),
        )

    fun level(l: String) =
        when (l) {
            "error" -> error
            "warn" -> warn
            "debug" -> debug
            else -> info
        }

    fun component(c: String) = components[Math.floorMod(c.hashCode(), components.size)]
}

private val LEVELS = listOf("debug", "info", "warn", "error")

private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)

/** Tokens highlighted inside a message, in priority order. */
private val TOKENS =
    listOf(
        Regex("\"[^\"]*\"|'[^']*'") to Palette.string,
        Regex("\\[s\\d+]") to Palette.session,
        Regex("\\b[a-z][a-zA-Z]*(\\.[a-zA-Z]+)+\\b") to Palette.ident,
        Regex("\\b[\\w-]+(?==)") to Palette.dim,
        Regex("(?<![\\w.])\\d+(\\.\\d+)?(ms|s|KiB|MiB|B)?\\b") to Palette.number,
    )

/** The one-line text of an entry, whatever its source. */
private fun lineOf(e: JSONObject, source: LogSource): String =
    if (e.optString("kind") == "access") {
        val params =
            e.optJSONArray("params")?.let { a ->
                (0 until a.length()).joinToString(",") { a.getString(it) }
            }
        "${e.optString("client")} ${e.optString("method")}(${params.orEmpty()}) " +
            "${e.optString("outcome")} ${e.optLong("ms")}ms ${e.optString("peer")}→${e.optString("local")}"
    } else {
        val session = e.optLong("session", 0)
        (if (session > 0 && source != LogSource.DEVD) "[s$session] " else "") +
            e.optString("message")
    }

private fun highlight(e: JSONObject, source: LogSource, search: String): AnnotatedString {
    val level = e.optString("level", "info")
    val component = e.optString("component")
    val line = lineOf(e, source)
    val text = buildAnnotatedString {
        withStyle(SpanStyle(color = Palette.dim)) { append(time.format(Date(e.optLong("unixMs")))) }
        append(' ')
        withStyle(SpanStyle(color = Palette.level(level), fontWeight = FontWeight.Bold)) {
            append(level.uppercase().padEnd(5))
        }
        append(' ')
        withStyle(SpanStyle(color = Palette.component(component))) { append(component.padEnd(10)) }
        append(' ')
        val start = length
        withStyle(SpanStyle(color = Palette.text)) { append(line) }
        // Token colors, first match wins per character.
        val taken = BooleanArray(line.length)
        for ((re, color) in TOKENS) {
            for (m in re.findAll(line)) {
                if ((m.range).any { taken[it] }) continue
                m.range.forEach { taken[it] = true }
                addStyle(SpanStyle(color = color), start + m.range.first, start + m.range.last + 1)
            }
        }
        if (e.optString("kind") == "access") {
            val outcome = e.optString("outcome")
            val i = line.indexOf(" $outcome ")
            if (i >= 0) {
                addStyle(
                    SpanStyle(
                        color = if (outcome == "ok") Palette.ok else Palette.error,
                        fontWeight = FontWeight.Bold,
                    ),
                    start + i + 1,
                    start + i + 1 + outcome.length,
                )
            }
        }
    }
    if (search.isEmpty()) return text
    return buildAnnotatedString {
        append(text)
        val lower = text.text.lowercase()
        val needle = search.lowercase()
        var i = lower.indexOf(needle)
        while (i >= 0) {
            addStyle(
                SpanStyle(background = Palette.match, color = Color.White),
                i,
                i + needle.length,
            )
            i = lower.indexOf(needle, i + needle.length)
        }
    }
}

/** Reads entries after [cursor] from [source]; returns them and the next cursor. */
private suspend fun fetch(
    d: RemoteDevd,
    source: LogSource,
    cursor: Long,
    first: Boolean,
): Pair<List<JSONObject>, Long> =
    withContext(Dispatchers.IO) {
        fun list(a: JSONArray?) = (0 until (a?.length() ?: 0)).map { a!!.getJSONObject(it) }
        when (source) {
            LogSource.DEVD,
            LogSource.ACCESS -> {
                val kind = if (source == LogSource.DEVD) "log" else "access"
                if (first) {
                    // Open at the end: the newest entries, then follow from the head.
                    val tail =
                        list(
                            d.call("journal.tail", JSONObject().put("max", KEEP).put("kind", kind))
                                as JSONArray
                        )
                    tail to (d.call("journal.head") as JSONObject).getLong("seq")
                } else {
                    val r =
                        d.call(
                            "journal.read",
                            JSONObject().put("after", cursor).put("max", 1000).put("kind", kind),
                        ) as JSONObject
                    list(r.getJSONArray("entries")) to r.getLong("nextSeq")
                }
            }
            LogSource.VM -> {
                val text = d.call("vm.engineLog") as? String ?: ""
                val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
                val start =
                    if (first || cursor > lines.size) 0
                    else cursor.toInt().coerceAtLeast(0)
                val now = System.currentTimeMillis()
                val got =
                    lines.drop(start).mapIndexed { i, line ->
                        JSONObject()
                            .put("seq", (start + i + 1).toLong())
                            .put("unixMs", now)
                            .put("level", "info")
                            .put("component", "vm")
                            .put("session", 0)
                            .put("message", line)
                    }
                got to lines.size.toLong()
            }
            LogSource.APP,
            LogSource.ENGINE -> {
                val method = if (source == LogSource.APP) "app.logs" else "engine.logs"
                val reply =
                    d.call(
                        "veil.call",
                        JSONObject()
                            .put("method", method)
                            .put("params", JSONObject().put("after", cursor).put("max", 1000)),
                    ) as JSONObject
                reply.optJSONObject("error")?.let {
                    throw ApiException(it.getString("code"), it.getString("message"))
                }
                val r = reply.getJSONObject("result")
                list(r.getJSONArray("entries")) to r.getLong("nextSeq")
            }
        }
    }

@Composable
fun LogViewer(app: RemoteDevd) {
    var source by remember { mutableStateOf(LogSource.DEVD) }
    val entries = remember { mutableStateListOf<JSONObject>() }
    var search by remember { mutableStateOf("") }
    var minLevel by remember { mutableStateOf("debug") }
    var component by remember { mutableStateOf<String?>(null) }
    var follow by remember { mutableStateOf(true) }
    var problem by remember { mutableStateOf<String?>(null) }
    var chain by remember { mutableStateOf<JSONObject?>(null) }
    var expanded by remember { mutableStateOf(setOf<Long>()) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun verify() {
        chain = null
        scope.launch { chain = app.call("journal.verify") as? JSONObject }
    }

    // Load and poll the chosen source.
    LaunchedEffect(source) {
        entries.clear()
        expanded = emptySet()
        component = null
        problem = null
        if (source == LogSource.DEVD || source == LogSource.ACCESS) verify()
        var cursor = 0L
        var first = true
        while (true) {
            try {
                var more = true
                while (more) {
                    val (got, next) = fetch(app, source, cursor, first)
                    first = false
                    cursor = next
                    entries.addAll(got)
                    if (entries.size > KEEP) entries.removeRange(0, entries.size - KEEP)
                    more = got.size >= 1000
                }
                problem = null
            } catch (e: ApiException) {
                problem = e.message
            } catch (e: Exception) {
                problem = "${e.javaClass.simpleName}: ${e.message}"
            }
            delay(1000)
        }
    }

    val components =
        remember(entries.size) {
            entries.map { it.optString("component") }.filter { it.isNotEmpty() }.distinct().sorted()
        }
    val minIndex = LEVELS.indexOf(minLevel)
    val shown =
        remember(entries.size, search, minLevel, component, source) {
            entries.filter { e ->
                LEVELS.indexOf(e.optString("level", "info")) >= minIndex &&
                    (component == null || e.optString("component") == component) &&
                    (search.isEmpty() ||
                        lineOf(e, source).contains(search, ignoreCase = true) ||
                        e.optString("component").contains(search, ignoreCase = true))
            }
        }

    // Follow: keep the newest entry in view; scrolling up by hand pauses it.
    LaunchedEffect(shown.size, follow) {
        if (follow && shown.isNotEmpty()) listState.scrollToItem(shown.size - 1)
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .collect { (scrolling, canForward) -> if (scrolling && canForward) follow = false }
    }

    Column(Modifier.fillMaxSize().background(Palette.bg)) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (s in LogSource.entries) {
                FilterChip(
                    selected = source == s,
                    onClick = { source = s },
                    label = { Text(s.label) },
                )
            }
        }
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            singleLine = true,
            placeholder = { Text("Search") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (l in LEVELS) {
                FilterChip(
                    selected = minLevel == l,
                    onClick = { minLevel = l },
                    label = { Text("$l+", color = Palette.level(l)) },
                )
            }
            FilterChip(
                selected = follow,
                onClick = { follow = !follow },
                label = { Text(if (follow) "following" else "follow") },
            )
            TextButton(
                onClick = {
                    follow = false
                    scope.launch { listState.scrollToItem(0) }
                }
            ) {
                Text("top")
            }
            TextButton(onClick = { follow = true }) { Text("end") }
        }
        if (components.size > 1) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip(
                    selected = component == null,
                    onClick = { component = null },
                    label = { Text("all") },
                )
                for (c in components) {
                    FilterChip(
                        selected = component == c,
                        onClick = { component = if (component == c) null else c },
                        label = { Text(c, color = Palette.component(c)) },
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${shown.size} of ${entries.size}",
                color = Palette.dim,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
            )
            if (source == LogSource.DEVD || source == LogSource.ACCESS) {
                val c = chain
                val (label, color) =
                    when {
                        c == null -> "checking chain…" to Palette.dim
                        c.optBoolean("ok") ->
                            "chain ok · ${c.optLong("entries")} entries" to Palette.ok
                        else -> "chain broken: ${c.optString("brokenAt")}" to Palette.error
                    }
                Text(label, color = color, fontSize = 12.sp)
                TextButton(onClick = ::verify) { Text("re-check") }
            }
        }
        problem?.let {
            Text(
                it,
                color = Palette.error,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }

        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
            itemsIndexed(shown, key = { i, e -> "${e.optLong("seq")}-$i" }) { i, e ->
                val seq = e.optLong("seq")
                val open = seq in expanded
                Column(
                    Modifier.fillMaxWidth()
                        .background(if (i % 2 == 0) Palette.bg else Palette.row)
                        .clickable { expanded = if (open) expanded - seq else expanded + seq }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        highlight(e, source, search),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        maxLines = if (open) Int.MAX_VALUE else 2,
                    )
                    if (open) {
                        for (k in e.keys().asSequence().sorted()) {
                            Text(
                                buildAnnotatedString {
                                    withStyle(SpanStyle(color = Palette.dim)) { append("$k: ") }
                                    withStyle(SpanStyle(color = Palette.text)) {
                                        append(e.get(k).toString())
                                    }
                                },
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                            )
                        }
                        Box(Modifier.fillMaxWidth()) {
                            TextButton(
                                onClick = {
                                    context
                                        .getSystemService(ClipboardManager::class.java)
                                        .setPrimaryClip(
                                            ClipData.newPlainText("log entry", e.toString(2))
                                        )
                                },
                                modifier = Modifier.align(Alignment.CenterEnd),
                            ) {
                                Text("copy JSON")
                            }
                        }
                    }
                }
            }
        }
    }
}
