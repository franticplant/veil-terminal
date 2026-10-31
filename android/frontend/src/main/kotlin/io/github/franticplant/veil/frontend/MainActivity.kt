package io.github.franticplant.veil.frontend

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.franticplant.veil.engine.ClipboardOffer
import io.github.franticplant.veil.engine.JumpHost
import io.github.franticplant.veil.engine.Prompt
import io.github.franticplant.veil.engine.Session
import io.github.franticplant.veil.engine.Target
import io.github.franticplant.veil.engine.proto.AuthPrompt
import io.github.franticplant.veil.engine.proto.HostKeyDecision
import io.github.franticplant.veil.engine.proto.HostKeyPrompt
import io.github.franticplant.veil.engine.proto.SessionPhase
import io.github.franticplant.veil.frontend.KeyEncoder.Key
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The v0.x frontend: saved hosts, session tabs, the terminal, and the dialogs the engine asks for.
 * Deliberately plain; it is meant to be replaced, and only talks to the engine through
 * [EngineHost.sessions].
 */
class MainActivity : ComponentActivity() {
    companion object {
        @Volatile var current: MainActivity? = null
    }

    private val inPip = mutableStateOf(false)

    override fun onResume() {
        super.onResume()
        current = this
    }

    override fun onPause() {
        if (current === this) current = null
        super.onPause()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip.value = isInPictureInPictureMode
    }

    /** The floating "hover" window: the terminal keeps its font size and live session. */
    fun enterHover() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        enterPictureInPictureMode(
            PictureInPictureParams.Builder().setAspectRatio(Rational(3, 4)).build()
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val host = application as EngineHost
        val profiles = host.profiles
        setContent {
            val ui by host.uiPrefs.values.collectAsState()
            val palette = Palette.byId(ui.theme)
            val bg = Color(palette.bg)
            val fg = Color(palette.fg)
            val scheme =
                if (palette.dark) {
                    darkColorScheme(
                        primary = Color(palette.chromeText),
                        onPrimary = bg,
                        primaryContainer = Color(palette.chromeActive),
                        onPrimaryContainer = Color(palette.chromeText),
                        secondaryContainer = Color(palette.chromeActive),
                        onSecondaryContainer = Color(palette.chromeText),
                        background = bg,
                        surface = bg,
                        surfaceVariant = Color(palette.chrome),
                        onBackground = fg,
                        onSurface = fg,
                        onSurfaceVariant = Color(palette.chromeText),
                    )
                } else {
                    lightColorScheme(
                        primary = Color(palette.chromeText),
                        onPrimary = bg,
                        primaryContainer = Color(palette.chromeActive),
                        onPrimaryContainer = Color(palette.chromeText),
                        secondaryContainer = Color(palette.chromeActive),
                        onSecondaryContainer = Color(palette.chromeText),
                        background = bg,
                        surface = bg,
                        surfaceVariant = Color(palette.chrome),
                        onBackground = fg,
                        onSurface = fg,
                        onSurfaceVariant = Color(palette.chromeText),
                    )
                }
            CompositionLocalProvider(LocalPalette provides palette) {
                MaterialTheme(colorScheme = scheme) {
                    Surface(Modifier.fillMaxSize(), color = bg) {
                        VeilApp(host, profiles, inPip.value)
                    }
                }
            }
        }
    }
}

/** Grid size used to open a session before the terminal view has measured itself. */
private const val DEFAULT_COLS = 80
private const val DEFAULT_ROWS = 24

@Composable
private fun VeilApp(host: EngineHost, profiles: Profiles, pip: Boolean) {
    val sessions by host.sessions.sessions.collectAsState()
    var currentId by remember { mutableStateOf<Long?>(null) }
    var showHosts by remember { mutableStateOf(true) }
    var showSessions by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var failedTarget by remember { mutableStateOf<Target?>(null) }
    var lastGrid by remember { mutableStateOf(DEFAULT_COLS to DEFAULT_ROWS) }
    val scope = rememberCoroutineScope()
    val current = sessions.firstOrNull { it.id == currentId }
    val ui by host.uiPrefs.values.collectAsState()
    val inTerminal = !showHosts && current != null
    val immersive = ui.fullscreen && inTerminal && !pip
    val activity = LocalContext.current as? Activity
    val main = LocalContext.current as? MainActivity
    // Immersive: the system bars hide and come back with a swipe from the edge.
    val coverCutout = immersive && ui.coverCutout
    LaunchedEffect(immersive, coverCutout, pip) {
        if (pip) return@LaunchedEffect
        val window = activity?.window ?: return@LaunchedEffect
        window.attributes =
            window.attributes.apply {
                layoutInDisplayCutoutMode =
                    if (coverCutout) WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            }
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (immersive) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    fun connect(target: Target) {
        scope.launch {
            try {
                val saved = profiles.touch(target)
                host.history.record(saved)
                val s = host.sessions.open(saved, lastGrid.first, lastGrid.second)
                currentId = s.id
                showHosts = false
                failedTarget = null
            } catch (e: Exception) {
                failedTarget = target
                error = "Cannot start a session: ${e.message}"
            }
        }
    }

    // A session that reconnected by itself keeps its tab.
    LaunchedEffect(Unit) {
        host.sessions.replaced.collect { (old, new) -> if (currentId == old) currentId = new }
    }

    // Covering the notch: only the keyboard pushes the terminal; text may sit under the cutout.
    val insets = if (coverCutout) WindowInsets.ime else WindowInsets.safeDrawing
    Column(Modifier.fillMaxSize().windowInsetsPadding(insets)) {
        // While the native terminal view is visible, the terminal screen owns
        // the bar. Keeping it outside a native AndroidView lets the terminal
        // cover it on some devices.
        if (!pip && (showHosts || current == null)) {
            if (ui.tabsOnTop) {
                SessionTabs(
                    sessions = sessions,
                    current = null,
                    onSelect = {
                        currentId = it.id
                        showHosts = false
                    },
                    onHosts = { showHosts = true },
                )
            }
        }
        if (showHosts || current == null) {
            HostsScreen(
                host,
                profiles,
                onConnect = ::connect,
                onSessions = { showSessions = true },
            )
        } else {
            TerminalScreen(
                session = current,
                sessions = sessions,
                prefs = host.uiPrefs,
                pip = pip,
                onSelect = {
                    currentId = it.id
                    showHosts = false
                },
                onHosts = { showHosts = true },
                onSessions = { showSessions = true },
                onEnterHover = { main?.enterHover() },
                onGrid = { lastGrid = it },
                onReconnect = {
                    host.sessions.forget(current)
                    connect(current.target)
                },
                onDismiss = {
                    host.sessions.forget(current)
                    currentId = sessions.firstOrNull { it !== current }?.id
                    if (currentId == null) showHosts = true
                },
            )
        }
    }

    if (showSessions) {
        SessionsDialog(
            sessions = sessions,
            current = if (showHosts) null else current,
            onSelect = {
                currentId = it.id
                showHosts = false
                showSessions = false
            },
            onKill = { s ->
                s.close()
                host.sessions.forget(s)
                if (currentId == s.id) {
                    currentId = sessions.firstOrNull { it !== s }?.id
                    if (currentId == null) showHosts = true
                }
            },
            onReconnect = { s ->
                host.sessions.forget(s)
                connect(s.target)
                showSessions = false
            },
            onHosts = {
                showHosts = true
                showSessions = false
            },
            onDismiss = { showSessions = false },
        )
    }

    error?.let { message ->
        AlertDialog(
            onDismissRequest = {
                error = null
                failedTarget = null
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    val target = failedTarget
                    if (target != null) {
                        TextButton(
                            onClick = {
                                error = null
                                failedTarget = null
                                connect(target)
                            }
                        ) {
                            Text("Retry")
                        }
                    }
                    if (target != null && host.backendKind == "vm") {
                        TextButton(
                            onClick = {
                                if (host.useNativeEngine()) {
                                    error = null
                                    failedTarget = null
                                    connect(target)
                                } else {
                                    error = "Close live sessions before switching to native."
                                }
                            }
                        ) {
                            Text("Use native")
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        error = null
                        failedTarget = null
                    }
                ) {
                    Text("Close")
                }
            },
            text = { Text(message) },
        )
    }
}

@Composable
private fun SessionTabs(
    sessions: List<Session>,
    current: Session?,
    modifier: Modifier = Modifier,
    onSelect: (Session) -> Unit,
    onHosts: () -> Unit,
) {
    val palette = LocalPalette.current
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .background(Color(palette.chrome))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TabChip(
            label = "Hosts",
            selected = current == null,
            palette = palette,
            onClick = onHosts,
        )
        for (s in sessions) {
            val status by s.status.collectAsState()
            val v by s.screenVersion.collectAsState()
            val title =
                remember(v) { synchronized(s.screen) { s.screen.title } }
                    .ifBlank { s.target.displayName }
            TabChip(
                label =
                    buildString {
                        append(title.take(32))
                        status.security?.let {
                            append(if (it.postQuantum) "  PQ" else "  classical")
                        }
                    },
                selected = s === current,
                palette = palette,
                onClick = { onSelect(s) },
                leading = {
                    val color =
                        when (status.phase) {
                            SessionPhase.SESSION_PHASE_OPEN -> Color(0xFF98C379)
                            SessionPhase.SESSION_PHASE_CLOSED -> Color(0xFFE06C75)
                            else -> Color(0xFFE5C07B)
                        }
                    Box(Modifier.size(8.dp).background(color, CircleShape))
                },
            )
        }
    }
}

@Composable
private fun TabChip(
    label: String,
    selected: Boolean,
    palette: Palette,
    onClick: () -> Unit,
    leading: (@Composable () -> Unit)? = null,
) {
    val background = if (selected) Color(palette.chromeActive) else Color(palette.chrome)
    Surface(
        color = background,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.height(36.dp).clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp).widthIn(max = 240.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            leading?.invoke()
            Text(
                label,
                color = Color(palette.chromeText),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun sessionTitle(s: Session): String {
    val v by s.screenVersion.collectAsState()
    return remember(v, s) { synchronized(s.screen) { s.screen.title } }
        .ifBlank { s.target.displayName }
}

@Composable
private fun CompactSessionBar(
    current: Session,
    count: Int,
    onOpen: () -> Unit,
    onHosts: () -> Unit,
) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().background(Color(palette.chrome)).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onOpen) {
            Text(sessionTitle(current).take(40), color = Color(palette.chromeText))
        }
        if (count > 1) {
            Text(
                "$count sessions",
                style = MaterialTheme.typography.labelSmall,
                color = Color(palette.chromeText),
            )
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onOpen) { Text("Sessions", color = Color(palette.chromeText)) }
        TextButton(onClick = onHosts) { Text("Hosts", color = Color(palette.chromeText)) }
    }
}

/** A searchable session list with the lifecycle actions that are useful away from the terminal. */
@Composable
private fun SessionsDialog(
    sessions: List<Session>,
    current: Session?,
    onSelect: (Session) -> Unit,
    onKill: (Session) -> Unit,
    onReconnect: (Session) -> Unit,
    onHosts: () -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var peek by remember { mutableStateOf<Session?>(null) }
    var killing by remember { mutableStateOf<Session?>(null) }
    val q = query.trim().lowercase()
    val titles = LinkedHashMap<Long, String>()
    for (s in sessions) titles[s.id] = sessionTitle(s)
    val filtered = sessions.filter {
        q.isEmpty() ||
            titles[it.id].orEmpty().lowercase().contains(q) ||
            it.target.displayName.lowercase().contains(q) ||
            it.target.label.lowercase().contains(q)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sessions") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    query,
                    { query = it },
                    label = { Text("Search sessions") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                when {
                    sessions.isEmpty() -> Text("No sessions yet.")
                    filtered.isEmpty() -> Text("No sessions match.")
                    else ->
                        LazyColumn(Modifier.heightIn(max = 360.dp)) {
                            items(filtered, key = { it.id }) { s ->
                                val status by s.status.collectAsState()
                                val title = titles[s.id].orEmpty()
                                Row(
                                    Modifier.fillMaxWidth()
                                        .clickable { onSelect(s) }
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(
                                        Modifier.size(8.dp)
                                            .background(
                                                when (status.phase) {
                                                    SessionPhase.SESSION_PHASE_OPEN ->
                                                        Color(0xFF98C379)
                                                    SessionPhase.SESSION_PHASE_CLOSED ->
                                                        Color(0xFFE06C75)
                                                    else -> Color(0xFFE5C07B)
                                                },
                                                CircleShape,
                                            )
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            title.take(60) + if (s === current) "  ✓" else "",
                                            maxLines = 1,
                                        )
                                        Text(
                                            s.target.displayName,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                            maxLines = 1,
                                        )
                                        val state =
                                            status.phase.name
                                                .removePrefix("SESSION_PHASE_")
                                                .lowercase() +
                                                if (status.detail.isBlank()) ""
                                                else ": ${status.detail.take(80)}"
                                        Text(
                                            state,
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                        )
                                    }
                                    TextButton(onClick = { onSelect(s) }) { Text("Go") }
                                    TextButton(onClick = { peek = s }) { Text("See") }
                                    if (status.closed) {
                                        TextButton(onClick = { onReconnect(s) }) {
                                            Text("Reconnect")
                                        }
                                    } else {
                                        TextButton(onClick = { killing = s }) { Text("Kill") }
                                    }
                                }
                            }
                        }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = { TextButton(onClick = onHosts) { Text("Hosts") } },
    )

    peek?.let { s ->
        ScreenPeek(
            session = s,
            onGo = {
                peek = null
                onSelect(s)
            },
            onDismiss = { peek = null },
        )
    }

    killing?.let { s ->
        AlertDialog(
            onDismissRequest = { killing = null },
            title = { Text("Kill this session?") },
            text = {
                Text("The remote process ends. The host stays in your saved hosts and history.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        killing = null
                        onKill(s)
                    }
                ) {
                    Text("Kill")
                }
            },
            dismissButton = { TextButton(onClick = { killing = null }) { Text("Cancel") } },
        )
    }
}

/** A read-only look at a session's current screen, before switching to it. */
@Composable
private fun ScreenPeek(session: Session, onGo: () -> Unit, onDismiss: () -> Unit) {
    val version by session.screenVersion.collectAsState()
    val text = remember(version, session) { screenText(session) }
    val title = sessionTitle(session)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title.take(60)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(text, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
            }
        },
        confirmButton = { TextButton(onClick = onGo) { Text("Go") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private fun screenText(session: Session): String =
    synchronized(session.screen) {
        session.screen.cells().joinToString("\n") { row ->
            row.joinToString("") { it ?: " " }.trimEnd()
        }
    }

// ---- Hosts --------------------------------------------------------------

@Composable
private fun HostsScreen(
    host: EngineHost,
    profiles: Profiles,
    onConnect: (Target) -> Unit,
    onSessions: () -> Unit,
) {
    val all by profiles.all.collectAsState()
    val recent by host.history.all.collectAsState()
    val liveSessions by host.sessions.sessions.collectAsState()
    var editing by remember { mutableStateOf<Target?>(null) }
    var adding by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var keysOpen by remember { mutableStateOf(false) }
    var knownHosts by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var showRecent by remember { mutableStateOf(false) }

    fun matches(t: Target): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return true
        return t.label.lowercase().contains(q) ||
            t.host.lowercase().contains(q) ||
            t.username.lowercase().contains(q) ||
            t.port.toString().contains(q)
    }

    val hosts = all.filter(::matches)
    val filteredRecent = recent.filter { matches(it.target) }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Veil Terminal", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.weight(1f))
            if (liveSessions.isNotEmpty()) {
                TextButton(onClick = onSessions) { Text("Sessions (${liveSessions.size})") }
            }
            Button(onClick = { adding = true }) { Text("Add host") }
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { keysOpen = true }) { Text("Keys") }
            TextButton(onClick = { knownHosts = true }) { Text("Known hosts") }
            TextButton(onClick = { settings = true }) { Text("Settings") }
        }
        if (settings) SettingsDialog(host.uiPrefs) { settings = false }
        if (keysOpen)
            KeysDialog(host.sshKeys, host.uiPrefs.values.value.keysWhileUnlocked) {
                keysOpen = false
            }
        if (knownHosts) KnownHostsDialog(host) { knownHosts = false }

        // Quick connect: user@host[:port]. It appears in Recent; saving it as a host is explicit.
        var quick by remember { mutableStateOf("") }
        val parsed = Profiles.parseQuick(quick)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                quick,
                { quick = it },
                label = { Text("user@host[:port]") },
                singleLine = true,
                isError = quick.isNotBlank() && parsed == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(
                enabled = parsed != null,
                onClick = {
                    parsed?.let(onConnect)
                    quick = ""
                },
            ) {
                Text("Connect")
            }
        }
        OutlinedTextField(
            query,
            { query = it },
            label = { Text("Search hosts and recent connections") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = !showRecent,
                onClick = { showRecent = false },
                label = { Text("Hosts (${all.size})") },
            )
            FilterChip(
                selected = showRecent,
                onClick = { showRecent = true },
                label = { Text("Recent (${recent.size})") },
            )
        }

        val info = host.buildInfo
        Text(
            "${info.versionName} · engine: ${host.backendKind}" +
                if (info.devTools) " · development build" else "",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )

        if (!showRecent) {
            when {
                all.isEmpty() -> Text("No saved hosts yet. Add one to connect.")
                hosts.isEmpty() -> Text("No hosts match the search.")
                else ->
                    LazyColumn(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(hosts) { t ->
                            Surface(
                                tonalElevation = 2.dp,
                                shape = MaterialTheme.shapes.medium,
                                modifier = Modifier.fillMaxWidth().clickable { onConnect(t) },
                            ) {
                                Row(
                                    Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            t.label.ifEmpty { t.host },
                                            style = MaterialTheme.typography.titleMedium,
                                        )
                                        Text(
                                            "${t.username}@${t.host}:${t.port}",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                        )
                                    }
                                    TextButton(onClick = { editing = t }) { Text("Edit") }
                                }
                            }
                        }
                    }
            }
        } else {
            when {
                recent.isEmpty() -> Text("No connections yet.")
                filteredRecent.isEmpty() -> Text("No recent connections match the search.")
                else ->
                    LazyColumn(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(filteredRecent, key = { "${it.key}@${it.lastUsed}" }) { e ->
                            Surface(
                                tonalElevation = 2.dp,
                                shape = MaterialTheme.shapes.medium,
                                modifier =
                                    Modifier.fillMaxWidth().clickable { onConnect(e.target) },
                            ) {
                                Row(
                                    Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            e.target.label.ifEmpty { e.target.host },
                                            style = MaterialTheme.typography.titleMedium,
                                        )
                                        Text(
                                            "${e.target.username}@${e.target.host}:${e.target.port}",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                        )
                                        Text(
                                            relativeTime(e.lastUsed) +
                                                if (e.count > 1) " · ${e.count}×" else "",
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                    if (profiles.contains(e.target)) {
                                        Text("Saved", style = MaterialTheme.typography.labelSmall)
                                    } else {
                                        TextButton(onClick = { profiles.save(null, e.target) }) {
                                            Text("Save")
                                        }
                                    }
                                }
                            }
                        }
                    }
            }
        }
    }

    if (adding || editing != null) {
        ProfileDialog(
            keys = host.sshKeys,
            initial = editing,
            onSave = {
                profiles.save(editing, it)
                adding = false
                editing = null
            },
            onDelete =
                editing?.let { t ->
                    {
                        profiles.delete(t)
                        editing = null
                    }
                },
            onDismiss = {
                adding = false
                editing = null
            },
        )
    }
}

/** Short, human-readable age for recent connections. */
private fun relativeTime(then: Long, now: Long = System.currentTimeMillis()): String {
    val seconds = ((now - then).coerceAtLeast(0)) / 1000
    return when {
        seconds < 60 -> "just now"
        seconds < 3600 -> "${seconds / 60}m ago"
        seconds < 86_400 -> "${seconds / 3600}h ago"
        else -> "${seconds / 86_400}d ago"
    }
}

@Composable
private fun ProfileDialog(
    keys: SshKeys,
    initial: Target?,
    onSave: (Target) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var label by remember { mutableStateOf(initial?.label ?: "") }
    var hostName by remember { mutableStateOf(initial?.host ?: "") }
    var port by remember { mutableStateOf((initial?.port ?: 22).toString()) }
    var user by remember { mutableStateOf(initial?.username ?: "") }
    var jumpHost by remember { mutableStateOf(initial?.jump?.host ?: "") }
    var jumpPort by remember { mutableStateOf((initial?.jump?.port ?: 22).toString()) }
    var keyId by remember { mutableStateOf(initial?.keyId ?: "") }
    var startup by remember { mutableStateOf(initial?.startup ?: "") }
    var reconnect by remember { mutableStateOf(initial?.autoReconnect ?: false) }
    var classical by remember { mutableStateOf(initial?.allowClassicalKex ?: false) }
    val allKeys by keys.keys.collectAsState()
    val portNum = port.toIntOrNull()
    val jumpPortNum = jumpPort.toIntOrNull()
    val valid =
        hostName.isNotBlank() &&
            !hostName.any { it.isWhitespace() } &&
            user.isNotBlank() &&
            portNum != null &&
            portNum in 1..65535 &&
            (jumpHost.isBlank() ||
                (!jumpHost.any { it.isWhitespace() } &&
                    jumpPortNum != null &&
                    jumpPortNum in 1..65535))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add host" else "Edit host") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    label,
                    { label = it },
                    label = { Text("Name (optional)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    hostName,
                    { hostName = it.trim() },
                    label = { Text("Host") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    port,
                    { port = it.filter(Char::isDigit).take(5) },
                    label = { Text("Port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    user,
                    { user = it.trim() },
                    label = { Text("Username") },
                    singleLine = true,
                )
                OutlinedTextField(
                    jumpHost,
                    { jumpHost = it.trim() },
                    label = { Text("Jump host (optional bastion)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                if (jumpHost.isNotBlank()) {
                    OutlinedTextField(
                        jumpPort,
                        { jumpPort = it.filter(Char::isDigit).take(5) },
                        label = { Text("Jump host port") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                Text("Key (tried before the password)", style = MaterialTheme.typography.labelLarge)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(
                        selected = keyId.isEmpty(),
                        onClick = { keyId = "" },
                        label = { Text("none") },
                    )
                    for (k in allKeys) {
                        FilterChip(
                            selected = keyId == k.name,
                            onClick = { keyId = k.name },
                            label = { Text(k.name) },
                        )
                    }
                }
                if (allKeys.isEmpty()) {
                    Text(
                        "No keys yet: create one under Keys.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedTextField(
                    startup,
                    { startup = it },
                    label = { Text("Run after login (e.g. tmux new -A -s main)") },
                    singleLine = true,
                )
                SettingSwitch("Reconnect when the connection drops", reconnect) { reconnect = it }
                SettingSwitch(
                    "Allow classical key exchange (servers without post-quantum)",
                    classical,
                ) {
                    classical = it
                }
            }
        },
        confirmButton = {
            Button(
                enabled = valid,
                onClick = {
                    onSave(
                        Target(
                            hostName,
                            portNum!!,
                            user,
                            label.trim(),
                            keyId,
                            startup.trim(),
                            reconnect,
                            classical,
                            jump =
                                if (jumpHost.isBlank()) null else JumpHost(jumpHost, jumpPortNum!!),
                        )
                    )
                },
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            Row {
                onDelete?.let { TextButton(onClick = it) { Text("Delete") } }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

// ---- Terminal -----------------------------------------------------------

@Composable
private fun TerminalScreen(
    session: Session,
    sessions: List<Session>,
    prefs: UiPrefs,
    pip: Boolean,
    onSelect: (Session) -> Unit,
    onHosts: () -> Unit,
    onSessions: () -> Unit,
    onEnterHover: () -> Unit,
    onGrid: (Pair<Int, Int>) -> Unit,
    onReconnect: () -> Unit,
    onDismiss: () -> Unit,
) {
    val status by session.status.collectAsState()
    val prompt by session.prompt.collectAsState()
    val version by session.screenVersion.collectAsState()
    var view by remember { mutableStateOf<TerminalView?>(null) }
    val stickyState = remember { mutableStateListOf(false, false, false) } // ctrl, alt, shift
    val ui by prefs.values.collectAsState()
    val context = LocalContext.current
    var offer by remember { mutableStateOf<ClipboardOffer?>(null) }
    var selecting by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    if (settings) SettingsDialog(prefs) { settings = false }

    LaunchedEffect(session) {
        session.bell.collect {
            val p = prefs.values.value
            view?.bell(flash = p.bellFlash, vibrate = p.bellVibrate)
        }
    }

    // Clipboard offers from the remote (OSC 52), under the user's policy.
    LaunchedEffect(session) {
        session.clipboard.collect { o ->
            when (prefs.values.value.clipboard) {
                UiPrefs.ClipboardPolicy.ALLOW -> copyToClipboard(context, o)
                UiPrefs.ClipboardPolicy.DENY -> {}
                UiPrefs.ClipboardPolicy.ASK -> offer = o
            }
        }
    }

    val immersive = ui.fullscreen && !pip
    val coverCutout = immersive && ui.coverCutout
    val showBar = !pip && (ui.tabsOnTop || !coverCutout)
    val barSpace = if (!showBar) 0.dp else if (ui.tabsOnTop) 44.dp else 48.dp
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // Reserve the bar's space; the bar itself is drawn after the native
            // terminal in the enclosing Box so a native view cannot cover it.
            Spacer(Modifier.height(barSpace))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        TerminalView(ctx).apply {
                            fontSizeSp = prefs.values.value.fontSizeSp
                            onStickyConsumed = {
                                stickyState[0] = false
                                stickyState[1] = false
                                stickyState[2] = false
                            }
                            onFontSizeChanged = { size ->
                                prefs.update { it.copy(fontSizeSp = size) }
                            }
                            onPasteRequest = { pasteClipboard(ctx, this) }
                            onSelectionChanged = { selecting = it }
                        }
                    },
                    update = { v ->
                        view = v
                        v.onGridSize = { cols, rows ->
                            onGrid(cols to rows)
                            v.session?.resize(cols, rows)
                        }
                        v.session = session
                        v.stickyCtrl = stickyState[0]
                        v.stickyAlt = stickyState[1]
                        v.stickyShift = stickyState[2]
                        v.palette = Palette.byId(ui.theme)
                    },
                )
                if (selecting) {
                    val palette = LocalPalette.current
                    Row(
                        Modifier.align(Alignment.TopCenter)
                            .padding(8.dp)
                            .background(Color(palette.chromeActive), MaterialTheme.shapes.medium)
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = {
                                view?.selectedText()?.let { text ->
                                    context
                                        .getSystemService(ClipboardManager::class.java)
                                        .setPrimaryClip(ClipData.newPlainText("terminal", text))
                                    Toast.makeText(
                                            context,
                                            "Copied ${text.length} characters",
                                            Toast.LENGTH_SHORT,
                                        )
                                        .show()
                                }
                                view?.clearSelection()
                            }
                        ) {
                            Text("Copy", color = Color(palette.chromeText))
                        }
                        TextButton(onClick = { view?.selectAll() }) {
                            Text("All", color = Color(palette.chromeText))
                        }
                        TextButton(onClick = { view?.clearSelection() }) {
                            Text("✕", color = Color(palette.chromeText))
                        }
                    }
                }
                if (!ui.extraKeys && !pip) {
                    // The keys bar is hidden: a small handle brings it back.
                    Box(
                        Modifier.align(Alignment.BottomEnd)
                            .padding(6.dp)
                            .size(32.dp)
                            .background(
                                Color(LocalPalette.current.chromeActive).copy(alpha = 0.35f),
                                CircleShape,
                            )
                            .clickable { prefs.update { it.copy(extraKeys = true) } },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("▴", color = Color(LocalPalette.current.chromeText).copy(alpha = 0.6f))
                    }
                }
            }
            // Redraw on every new frame.
            LaunchedEffect(version) { view?.invalidate() }

            if (status.phase != SessionPhase.SESSION_PHASE_OPEN) {
                StatusLine(status.phase, status.detail, status.closed, onReconnect, onDismiss)
            }
            if (ui.extraKeys && !pip) {
                ExtraKeys(
                    view = view,
                    ctrl = stickyState[0],
                    alt = stickyState[1],
                    shift = stickyState[2],
                    fullscreen = ui.fullscreen,
                    rows = ui.keysRows,
                    onCtrl = { stickyState[0] = !stickyState[0] },
                    onAlt = { stickyState[1] = !stickyState[1] },
                    onShift = { stickyState[2] = !stickyState[2] },
                    onHide = { prefs.update { it.copy(extraKeys = false) } },
                    onFullscreen = { prefs.update { it.copy(fullscreen = !it.fullscreen) } },
                    onSettings = { settings = true },
                    onHover = onEnterHover,
                    onFontSize = { size ->
                        view?.fontSizeSp = size
                        view?.let { v -> prefs.update { it.copy(fontSizeSp = v.fontSizeSp) } }
                    },
                )
            }
        }
        if (showBar) {
            if (ui.tabsOnTop) {
                SessionTabs(
                    sessions = sessions,
                    current = session,
                    modifier =
                        Modifier.align(Alignment.TopStart)
                            .fillMaxWidth()
                            .zIndex(2f)
                            .then(
                                if (coverCutout)
                                    Modifier.windowInsetsPadding(WindowInsets.safeDrawing)
                                else Modifier
                            ),
                    onSelect = onSelect,
                    onHosts = onHosts,
                )
            } else {
                CompactSessionBar(
                    current = session,
                    count = sessions.size,
                    onOpen = onSessions,
                    onHosts = onHosts,
                )
            }
        }
    }

    offer?.let { o ->
        ClipboardDialog(
            o,
            onCopy = {
                copyToClipboard(context, o)
                offer = null
            },
            onAlways = {
                copyToClipboard(context, o)
                prefs.update { it.copy(clipboard = UiPrefs.ClipboardPolicy.ALLOW) }
                offer = null
            },
            onIgnore = { offer = null },
        )
    }

    when (val p = prompt) {
        is Prompt.HostKey -> HostKeyDialog(p.prompt) { session.answerHostKey(p.prompt, it) }
        is Prompt.Auth -> AuthDialog(p.prompt) { session.answerAuth(p.prompt, it) }
        null -> {}
    }
}

@Composable
private fun StatusLine(
    phase: SessionPhase,
    detail: String,
    closed: Boolean,
    onReconnect: () -> Unit,
    onDismiss: () -> Unit,
) {
    val label =
        when (phase) {
            SessionPhase.SESSION_PHASE_CONNECTING -> "Connecting…"
            SessionPhase.SESSION_PHASE_VERIFYING_HOST -> "Verifying host key…"
            SessionPhase.SESSION_PHASE_AUTHENTICATING -> "Authenticating…"
            SessionPhase.SESSION_PHASE_CLOSED -> "Closed"
            else -> ""
        }
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            if (detail.isNotEmpty()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (closed) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onReconnect) { Text("Reconnect") }
                    OutlinedButton(onClick = onDismiss) { Text("Close tab") }
                }
            }
        }
    }
}

@Composable
private fun ExtraKeys(
    view: TerminalView?,
    ctrl: Boolean,
    alt: Boolean,
    shift: Boolean,
    fullscreen: Boolean,
    rows: Int,
    onCtrl: () -> Unit,
    onAlt: () -> Unit,
    onShift: () -> Unit,
    onHide: () -> Unit,
    onFullscreen: () -> Unit,
    onSettings: () -> Unit,
    onHover: () -> Unit,
    onFontSize: (Float) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val palette = LocalPalette.current
    var fn by remember { mutableStateOf(false) }
    @Composable
    fun K(label: String, active: Boolean = false, action: () -> Unit) {
        Box(
            Modifier.height(38.dp)
                .width(46.dp)
                .background(Color(if (active) palette.chromeActive else palette.chrome))
                .clickable(onClick = action),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                maxLines = 1,
                color = Color(palette.chromeText),
            )
        }
    }

    // Control and navigation. Horizontal scroll keeps every key reachable without shrinking the
    // terminal; the order is grouped so the common ones stay close to the left edge.
    @Composable
    fun Controls() {
        K("▾", action = onHide)
        K(if (fullscreen) "⤡" else "⤢", fullscreen, onFullscreen)
        K("Hover", action = onHover)
        K("⌨") { view?.showKeyboard() }
        K("⚙", action = onSettings)
        K("Esc") { view?.sendKey(Key.ESCAPE) }
        K("Ctrl", ctrl, onCtrl)
        K("Alt", alt, onAlt)
        K("Shift", shift, onShift)
        K("Tab") { view?.sendKey(Key.TAB) }
        K("←") { view?.sendKey(Key.LEFT) }
        K("↓") { view?.sendKey(Key.DOWN) }
        K("↑") { view?.sendKey(Key.UP) }
        K("→") { view?.sendKey(Key.RIGHT) }
        K("Home") { view?.sendKey(Key.HOME) }
        K("End") { view?.sendKey(Key.END) }
        K("PgUp") { view?.sendKey(Key.PAGE_UP) }
        K("PgDn") { view?.sendKey(Key.PAGE_DOWN) }
        K("Ins") { view?.sendKey(Key.INSERT) }
        K("Del") { view?.sendKey(Key.DELETE) }
        K("Paste") { view?.let { pasteClipboard(context, it) } }
        K("A−") { view?.let { onFontSize(it.fontSizeSp - 1f) } }
        K("A+") { view?.let { onFontSize(it.fontSizeSp + 1f) } }
    }

    // Symbols, or function keys while Fn is on. This keeps either set reachable without a menu.
    @Composable
    fun Symbols() {
        K("Fn", fn) { fn = !fn }
        K("⏎") { view?.sendKey(Key.ENTER) }
        K("⌫") { view?.sendKey(Key.BACKSPACE) }
        if (fn) {
            for (n in 1..12) K("F$n") { view?.sendKey(Key.entries[Key.F1.ordinal + n - 1]) }
        } else {
            for (c in SYMBOL_KEYS) K(c) { view?.sendText(c) }
        }
    }

    if (rows <= 1) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Controls()
            Symbols()
        }
    } else {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Controls()
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Symbols()
        }
    }
}

private val SYMBOL_KEYS =
    listOf(
        "|",
        "~",
        "/",
        "\\",
        "-",
        "_",
        "=",
        "+",
        "[",
        "]",
        "{",
        "}",
        "(",
        ")",
        "<",
        ">",
        ";",
        ":",
        "'",
        "\"",
        "`",
        "@",
        "#",
        "$",
        "%",
        "^",
        "&",
        "*",
        "!",
        "?",
    )

@Composable
private fun HostKeyDialog(p: HostKeyPrompt, answer: (HostKeyDecision) -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Unknown host key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("No key is recorded for ${p.host}:${p.port}. It presented:")
                Text(
                    "${p.algorithm}\n${p.fingerprint}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                )
                Text(
                    "Compare the fingerprint with one obtained from the server through a channel you trust " +
                        "before accepting.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                Button(
                    onClick = { answer(HostKeyDecision.HOST_KEY_DECISION_ACCEPT_AND_REMEMBER) }
                ) {
                    Text("Trust and remember")
                }
                TextButton(onClick = { answer(HostKeyDecision.HOST_KEY_DECISION_ACCEPT_ONCE) }) {
                    Text("Trust once")
                }
                TextButton(onClick = { answer(HostKeyDecision.HOST_KEY_DECISION_REJECT) }) {
                    Text("Reject")
                }
            }
        },
    )
}

@Composable
private fun AuthDialog(p: AuthPrompt, answer: (List<String>?) -> Unit) {
    val values = remember(p.promptId) { mutableStateListOf(*Array(p.fieldsCount) { "" }) }
    AlertDialog(
        onDismissRequest = {},
        title = { Text(if (p.retry) "Try again" else "Sign in as ${p.username}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (p.retry) Text("The server rejected that.", color = Color(0xFFE06C75))
                // Name and instruction come from the server: shown as plain text, labelled as such.
                val serverText =
                    listOf(p.name, p.instruction).filter { it.isNotBlank() }.joinToString("\n")
                if (serverText.isNotEmpty()) {
                    Text("Server says: $serverText", style = MaterialTheme.typography.bodySmall)
                }
                p.fieldsList.forEachIndexed { i, field ->
                    OutlinedTextField(
                        value = values[i],
                        onValueChange = { values[i] = it },
                        label = { Text(field.label.trim().ifEmpty { "Response" }) },
                        singleLine = true,
                        visualTransformation =
                            if (field.echo) VisualTransformation.None
                            else PasswordVisualTransformation(),
                        keyboardOptions =
                            KeyboardOptions(
                                keyboardType =
                                    if (field.echo) KeyboardType.Text else KeyboardType.Password
                            ),
                    )
                }
            }
        },
        confirmButton = { Button(onClick = { answer(values.toList()) }) { Text("Sign in") } },
        dismissButton = { TextButton(onClick = { answer(null) }) { Text("Cancel") } },
    )
}

private fun pasteClipboard(context: Context, view: TerminalView) {
    val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
    val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
    if (!text.isNullOrEmpty()) view.paste(text)
}

private fun copyToClipboard(context: Context, offer: ClipboardOffer) {
    context
        .getSystemService(ClipboardManager::class.java)
        .setPrimaryClip(ClipData.newPlainText("remote", offer.text))
    Toast.makeText(
            context,
            "Copied ${offer.text.length} characters from the remote",
            Toast.LENGTH_SHORT,
        )
        .show()
}

/** The remote wants to set the clipboard. Shows what, as plain text, before anything is copied. */
@Composable
private fun ClipboardDialog(
    offer: ClipboardOffer,
    onCopy: () -> Unit,
    onAlways: () -> Unit,
    onIgnore: () -> Unit,
) {
    val preview = offer.text.take(400) + if (offer.text.length > 400) "…" else ""
    AlertDialog(
        onDismissRequest = onIgnore,
        title = { Text("Copy from the remote?") },
        text = {
            Column {
                Text(
                    "The remote program wants to put ${offer.text.length} characters on the clipboard:"
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    preview,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier =
                        Modifier.fillMaxWidth()
                            .background(Color(LocalPalette.current.chrome))
                            .padding(8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onCopy) { Text("Copy") } },
        dismissButton = {
            Row {
                TextButton(onClick = onAlways) { Text("Always") }
                TextButton(onClick = onIgnore) { Text("Ignore") }
            }
        },
    )
}

/** The current theme, for composables that draw chrome around the terminal. */
val LocalPalette = staticCompositionLocalOf { Palette.VEIL }

/** Theme, clipboard policy and screen options. */
@Composable
private fun SettingsDialog(prefs: UiPrefs, onDismiss: () -> Unit) {
    val ui by prefs.values.collectAsState()
    var showLicenses by remember { mutableStateOf(false) }
    if (showLicenses) LicensesDialog { showLicenses = false }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Settings") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Theme", style = MaterialTheme.typography.labelLarge)
                for (p in Palette.ALL) {
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            prefs.update { it.copy(theme = p.id) }
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // A swatch: the background with a few of the theme's colors.
                        Row(Modifier.background(Color(p.bg)).padding(4.dp)) {
                            for (c in
                                listOf(
                                    p.fg,
                                    p.resolve(0x01000001, 0),
                                    p.resolve(0x01000002, 0),
                                    p.resolve(0x01000004, 0),
                                )) {
                                Box(Modifier.size(10.dp).background(Color(c)))
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(p.name + if (ui.theme == p.id) "  ✓" else "")
                    }
                }
                Text("Input", style = MaterialTheme.typography.labelLarge)
                Text("Remote clipboard (OSC 52)", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (c in UiPrefs.ClipboardPolicy.entries) {
                        FilterChip(
                            selected = ui.clipboard == c,
                            onClick = { prefs.update { it.copy(clipboard = c) } },
                            label = { Text(c.name.lowercase()) },
                        )
                    }
                }
                SettingSwitch("Keys bar", ui.extraKeys) { on ->
                    prefs.update { it.copy(extraKeys = on) }
                }
                Text("Keys bar rows", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (n in 1..2) {
                        FilterChip(
                            selected = ui.keysRows == n,
                            onClick = { prefs.update { it.copy(keysRows = n) } },
                            label = { Text(if (n == 1) "1 row" else "2 rows") },
                        )
                    }
                }
                SettingSwitch("Tabs on top", ui.tabsOnTop) { on ->
                    prefs.update { it.copy(tabsOnTop = on) }
                }
                SettingSwitch("Fullscreen terminal", ui.fullscreen) { on ->
                    prefs.update { it.copy(fullscreen = on) }
                }
                SettingSwitch("Fullscreen covers the notch", ui.coverCutout) { on ->
                    prefs.update { it.copy(coverCutout = on) }
                }
                Text("Connections", style = MaterialTheme.typography.labelLarge)
                SettingSwitch("Require post-quantum key exchange", ui.requirePostQuantum) { on ->
                    prefs.update { it.copy(requirePostQuantum = on) }
                }
                Text(
                    "Hosts can still allow classical key exchange in their own settings.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Reconnect attempts (hosts with reconnect on)",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (n in listOf(3, 6, 12)) {
                        FilterChip(
                            selected = ui.reconnectAttempts == n,
                            onClick = { prefs.update { it.copy(reconnectAttempts = n) } },
                            label = { Text("$n") },
                        )
                    }
                }
                Text("Bell", style = MaterialTheme.typography.labelLarge)
                SettingSwitch("Vibrate", ui.bellVibrate) { on ->
                    prefs.update { it.copy(bellVibrate = on) }
                }
                SettingSwitch("Flash the screen", ui.bellFlash) { on ->
                    prefs.update { it.copy(bellFlash = on) }
                }
                Text("Engine", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (e in UiPrefs.EnginePlace.entries) {
                        FilterChip(
                            selected = ui.engine == e,
                            onClick = { prefs.update { it.copy(engine = e) } },
                            label = { Text(e.label) },
                        )
                    }
                }
                Text(
                    "Takes effect the next time Veil starts. Native: a process of its own without a " +
                        "JVM, the lightest. Service: a Java process of its own. In app: inside Veil.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (ui.engine == UiPrefs.EnginePlace.VM) {
                    Text("VM layout", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (l in UiPrefs.VmLayout.entries) {
                            FilterChip(
                                selected = ui.vmLayout == l,
                                onClick = { prefs.update { it.copy(vmLayout = l) } },
                                label = { Text(l.label) },
                            )
                        }
                    }
                    Text(
                        "Applies at the next Veil start. Workers and tenants are experimental; " +
                            "the matching guest payload or role packages must be installed.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text("VM guest memory", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (mib in listOf(64, 96, 128, 192, 256)) {
                        FilterChip(
                            selected = ui.vmMemoryMiB == mib,
                            onClick = { prefs.update { it.copy(vmMemoryMiB = mib) } },
                            label = { Text("$mib MiB") },
                        )
                    }
                }
                Text(
                    "Applies at the next Veil start. Lower values need testing; 128 MiB is the current default.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("Keys", style = MaterialTheme.typography.labelLarge)
                SettingSwitch("New keys usable only while unlocked", ui.keysWhileUnlocked) { on ->
                    prefs.update { it.copy(keysWhileUnlocked = on) }
                }
                Text(
                    "Text size ${ui.fontSizeSp} sp (pinch the terminal to change)",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { showLicenses = true }) { Text("Open-source licenses") }
            }
        },
    )
}

/**
 * The third-party notices the build puts in the APK (assets/third_party_notices.txt): every Java,
 * Kotlin and Rust component Veil ships, with its license text. Read off the main thread and shown
 * line by line, since it is a few hundred kilobytes.
 */
@Composable
private fun LicensesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var lines by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(Unit) {
        lines =
            withContext(Dispatchers.IO) {
                try {
                    context.assets.open("third_party_notices.txt").bufferedReader().readLines()
                } catch (e: java.io.IOException) {
                    listOf("This build has no third-party notices.")
                }
            }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Open-source licenses") },
        text = {
            val l = lines
            if (l == null) {
                Text("Loading…")
            } else {
                LazyColumn {
                    items(l) {
                        Text(
                            it,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            softWrap = true,
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun SettingSwitch(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = onChange)
    }
}

/**
 * SSH keys: create in StrongBox, the TEE, or whichever is available; copy the public key for
 * authorized_keys; delete. Private keys never leave Android Keystore.
 */
@Composable
private fun KeysDialog(keys: SshKeys, unlockedDefault: Boolean, onDismiss: () -> Unit) {
    val all by keys.keys.collectAsState()
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    val hasStrongBox = remember {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
    }
    var where by remember {
        mutableStateOf(if (hasStrongBox) SshKeys.Where.STRONGBOX else SshKeys.Where.TEE)
    }
    var error by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<SshKeys.Key?>(null) }
    var whileUnlocked by remember { mutableStateOf(unlockedDefault) }
    val q = query.trim().lowercase()
    val shown = all.filter { q.isEmpty() || it.name.lowercase().contains(q) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Keys") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (all.isNotEmpty()) {
                    OutlinedTextField(
                        query,
                        { query = it },
                        label = { Text("Search keys") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (all.isEmpty()) Text("No keys yet.")
                else if (shown.isEmpty()) Text("No keys match the search.")
                for (k in shown) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                k.name,
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                when (k.security) {
                                    SshKeys.Security.STRONGBOX -> "StrongBox"
                                    SshKeys.Security.TEE -> "TEE"
                                    SshKeys.Security.SOFTWARE -> "software"
                                    SshKeys.Security.UNKNOWN -> "?"
                                } + if (k.authPerUse) " · biometric/sign" else " · legacy key",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        Text(k.fingerprint, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        Row {
                            TextButton(
                                onClick = {
                                    context
                                        .getSystemService(ClipboardManager::class.java)
                                        .setPrimaryClip(
                                            ClipData.newPlainText("public key", k.publicKey)
                                        )
                                    Toast.makeText(context, "Public key copied", Toast.LENGTH_SHORT)
                                        .show()
                                }
                            ) {
                                Text("Copy public key")
                            }
                            TextButton(onClick = { deleting = k }) { Text("Delete") }
                        }
                    }
                }
                OutlinedTextField(
                    name,
                    { name = it.trim() },
                    label = { Text("New key name") },
                    singleLine = true,
                )
                Text("Generate in", style = MaterialTheme.typography.labelLarge)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (w in SshKeys.Where.entries) {
                        FilterChip(
                            selected = where == w,
                            onClick = { where = w },
                            label = {
                                Text(
                                    when (w) {
                                        SshKeys.Where.AUTO -> "StrongBox preferred"
                                        SshKeys.Where.STRONGBOX -> "StrongBox required"
                                        SshKeys.Where.TEE -> "TEE compatibility"
                                    }
                                )
                            },
                        )
                    }
                }
                if (where == SshKeys.Where.TEE || !hasStrongBox) {
                    Text(
                        if (!hasStrongBox)
                            "This device has no StrongBox. The key uses the TEE, which is still " +
                                "hardware-backed, but is not the separate StrongBox secure element."
                        else
                            "TEE compatibility is selected. The key is still hardware-backed, but " +
                                "it is not stored in the separate StrongBox secure element. StrongBox " +
                                "is the default for new keys on this device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(LocalPalette.current.chromeText),
                    )
                }
                SettingSwitch("Usable only while the phone is unlocked", whileUnlocked) {
                    whileUnlocked = it
                }
                Text(
                    "Each new key requires a fresh strong-biometric approval for every SSH " +
                        "signature. Older keys are refused; recreate them to adopt the policy.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(LocalPalette.current.chromeText),
                )
                Button(
                    enabled = name.isNotEmpty(),
                    onClick = {
                        error =
                            try {
                                keys.create(name, where, whileUnlocked = whileUnlocked)
                                name = ""
                                null
                            } catch (e: Exception) {
                                e.message
                            }
                    },
                ) {
                    Text("Create key")
                }
                error?.let { Text(it, color = Color(0xFFE06C75)) }
                Text(
                    "Put the public key in the server's ~/.ssh/authorized_keys, then choose the key " +
                        "in the host's settings.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
    )
    deleting?.let { k ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete '${k.name}'?") },
            text = {
                Text("The private key is destroyed; servers that trust only it will refuse you.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        keys.delete(k.name)
                        deleting = null
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

/** Trusted host keys (engine known_hosts plus frontend VM pins); forget one here. */
@Composable
private fun KnownHostsDialog(host: EngineHost, onDismiss: () -> Unit) {
    var entries by remember {
        mutableStateOf<List<io.github.franticplant.veil.engine.proto.HostKeyEntry>?>(null)
    }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(reload) {
        try {
            entries = host.sessions.hostKeys()
            error = null
        } catch (e: Exception) {
            error = e.message
        }
    }
    val q = query.trim().lowercase()
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Known hosts") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                error?.let { Text(it, color = Color(0xFFE06C75)) }
                OutlinedTextField(
                    query,
                    { query = it },
                    label = { Text("Search host or fingerprint") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                val list = entries
                val shown = list?.filter {
                    q.isEmpty() ||
                        it.host.lowercase().contains(q) ||
                        it.fingerprint.lowercase().contains(q) ||
                        it.algorithm.lowercase().contains(q)
                }
                when {
                    list == null -> Text("Loading…")
                    list.isEmpty() -> Text("No host keys recorded yet.")
                    shown.isNullOrEmpty() -> Text("No host keys match the search.")
                    else ->
                        for (k in shown) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(if (k.port == 22) k.host else "${k.host}:${k.port}")
                                    Text(
                                        "${k.algorithm} ${k.fingerprint}",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                    )
                                }
                                TextButton(
                                    onClick = {
                                        scope.launch {
                                            try {
                                                host.sessions.forgetHostKey(k.host, k.port)
                                            } catch (e: Exception) {
                                                error = e.message
                                            }
                                            reload++
                                        }
                                    }
                                ) {
                                    Text("Forget")
                                }
                            }
                        }
                }
                Text(
                    "Forget a key only when you know why the server's key changed; Veil then asks again " +
                        "on the next connection.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
    )
}
