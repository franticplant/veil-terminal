package io.github.franticplant.veil.frontend

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.franticplant.veil.engine.ClipboardOffer
import io.github.franticplant.veil.engine.Prompt
import io.github.franticplant.veil.engine.Session
import io.github.franticplant.veil.engine.Target
import io.github.franticplant.veil.engine.proto.AuthPrompt
import io.github.franticplant.veil.engine.proto.HostKeyDecision
import io.github.franticplant.veil.engine.proto.HostKeyPrompt
import io.github.franticplant.veil.engine.proto.SessionPhase
import io.github.franticplant.veil.frontend.KeyEncoder.Key
import kotlinx.coroutines.launch

/**
 * The v0.x frontend: saved hosts, session tabs, the terminal, and the dialogs the engine asks for.
 * Deliberately plain; it is meant to be replaced, and only talks to the engine through
 * [EngineHost.sessions].
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val host = application as EngineHost
        val profiles = host.profiles
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize(), color = Color(Palette.DEFAULT_BG)) {
                    VeilApp(host, profiles)
                }
            }
        }
    }
}

/** Grid size used to open a session before the terminal view has measured itself. */
private const val DEFAULT_COLS = 80
private const val DEFAULT_ROWS = 24

@Composable
private fun VeilApp(host: EngineHost, profiles: Profiles) {
    val sessions by host.sessions.sessions.collectAsState()
    var currentId by remember { mutableStateOf<Long?>(null) }
    var showHosts by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var lastGrid by remember { mutableStateOf(DEFAULT_COLS to DEFAULT_ROWS) }
    val scope = rememberCoroutineScope()
    val current = sessions.firstOrNull { it.id == currentId }
    val ui by host.uiPrefs.values.collectAsState()
    val inTerminal = !showHosts && current != null
    val immersive = ui.fullscreen && inTerminal
    val activity = LocalContext.current as? Activity
    // Immersive: the system bars hide and come back with a swipe from the edge.
    LaunchedEffect(immersive) {
        val window = activity?.window ?: return@LaunchedEffect
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
                val s = host.sessions.open(target, lastGrid.first, lastGrid.second)
                currentId = s.id
                showHosts = false
            } catch (e: Exception) {
                error = "Cannot start a session: ${e.message}"
            }
        }
    }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        if (!immersive) {
            SessionTabs(
                sessions = sessions,
                current = if (showHosts) null else current,
                onSelect = {
                    currentId = it.id
                    showHosts = false
                },
                onHosts = { showHosts = true },
            )
        }
        if (showHosts || current == null) {
            HostsScreen(host, profiles, onConnect = ::connect)
        } else {
            TerminalScreen(
                session = current,
                prefs = host.uiPrefs,
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

    error?.let {
        AlertDialog(
            onDismissRequest = { error = null },
            confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } },
            text = { Text(it) },
        )
    }
}

@Composable
private fun SessionTabs(
    sessions: List<Session>,
    current: Session?,
    onSelect: (Session) -> Unit,
    onHosts: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(selected = current == null, onClick = onHosts, label = { Text("Hosts") })
        for (s in sessions) {
            val status by s.status.collectAsState()
            FilterChip(
                selected = s === current,
                onClick = { onSelect(s) },
                label = { Text(s.target.displayName, maxLines = 1) },
                leadingIcon = {
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

// ---- Hosts --------------------------------------------------------------

@Composable
private fun HostsScreen(host: EngineHost, profiles: Profiles, onConnect: (Target) -> Unit) {
    val all by profiles.all.collectAsState()
    var editing by remember { mutableStateOf<Target?>(null) }
    var adding by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Veil Terminal", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.weight(1f))
            Button(onClick = { adding = true }) { Text("Add host") }
        }
        val info = host.buildInfo
        Text(
            "${info.versionName} · engine: ${host.backendKind}" +
                if (info.devTools) " · development build" else "",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        if (all.isEmpty()) {
            Text(
                "No saved hosts yet. Add one to connect.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(all) { t ->
                Surface(
                    tonalElevation = 2.dp,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().clickable { onConnect(t) },
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
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

    if (adding || editing != null) {
        ProfileDialog(
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

@Composable
private fun ProfileDialog(
    initial: Target?,
    onSave: (Target) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var label by remember { mutableStateOf(initial?.label ?: "") }
    var hostName by remember { mutableStateOf(initial?.host ?: "") }
    var port by remember { mutableStateOf((initial?.port ?: 22).toString()) }
    var user by remember { mutableStateOf(initial?.username ?: "") }
    val portNum = port.toIntOrNull()
    val valid =
        hostName.isNotBlank() &&
            !hostName.any { it.isWhitespace() } &&
            user.isNotBlank() &&
            portNum != null &&
            portNum in 1..65535

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
            }
        },
        confirmButton = {
            Button(
                enabled = valid,
                onClick = { onSave(Target(hostName, portNum!!, user, label.trim())) },
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
    prefs: UiPrefs,
    onGrid: (Pair<Int, Int>) -> Unit,
    onReconnect: () -> Unit,
    onDismiss: () -> Unit,
) {
    val status by session.status.collectAsState()
    val prompt by session.prompt.collectAsState()
    val version by session.screenVersion.collectAsState()
    var view by remember { mutableStateOf<TerminalView?>(null) }
    val stickyState = remember { mutableStateListOf(false, false) } // ctrl, alt
    val ui by prefs.values.collectAsState()
    val context = LocalContext.current
    var offer by remember { mutableStateOf<ClipboardOffer?>(null) }

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

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    TerminalView(ctx).apply {
                        fontSizeSp = prefs.values.value.fontSizeSp
                        onStickyConsumed = {
                            stickyState[0] = false
                            stickyState[1] = false
                        }
                        onFontSizeChanged = { size -> prefs.update { it.copy(fontSizeSp = size) } }
                        onPasteRequest = { pasteClipboard(ctx, this) }
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
                },
            )
            if (!ui.extraKeys) {
                // The keys bar is hidden: a small handle brings it back.
                Box(
                    Modifier.align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .size(36.dp)
                        .background(Color(0x99303643), CircleShape)
                        .clickable { prefs.update { it.copy(extraKeys = true) } },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("▴", color = Color(0xFFD8DEE9))
                }
            }
        }
        // Redraw on every new frame.
        LaunchedEffect(version) { view?.invalidate() }

        if (status.phase != SessionPhase.SESSION_PHASE_OPEN) {
            StatusLine(status.phase, status.detail, status.closed, onReconnect, onDismiss)
        }
        if (ui.extraKeys) {
            ExtraKeys(
                view = view,
                ctrl = stickyState[0],
                alt = stickyState[1],
                fullscreen = ui.fullscreen,
                onCtrl = { stickyState[0] = !stickyState[0] },
                onAlt = { stickyState[1] = !stickyState[1] },
                onHide = { prefs.update { it.copy(extraKeys = false) } },
                onFullscreen = { prefs.update { it.copy(fullscreen = !it.fullscreen) } },
            )
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
    fullscreen: Boolean,
    onCtrl: () -> Unit,
    onAlt: () -> Unit,
    onHide: () -> Unit,
    onFullscreen: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    @Composable
    fun K(label: String, active: Boolean = false, action: () -> Unit) {
        Box(
            Modifier.height(40.dp)
                .width(44.dp)
                .background(if (active) Color(0xFF3A4150) else Color(0xFF1C2028))
                .clickable(onClick = action),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                color = Color(0xFFD8DEE9),
            )
        }
    }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        K("▾", action = onHide)
        K(if (fullscreen) "⤡" else "⤢", fullscreen, onFullscreen)
        K("⌨") { view?.showKeyboard() }
        K("Esc") { view?.sendKey(Key.ESCAPE) }
        K("Ctrl", ctrl, onCtrl)
        K("Alt", alt, onAlt)
        K("Tab") { view?.sendKey(Key.TAB) }
        K("←") { view?.sendKey(Key.LEFT) }
        K("↓") { view?.sendKey(Key.DOWN) }
        K("↑") { view?.sendKey(Key.UP) }
        K("→") { view?.sendKey(Key.RIGHT) }
        K("Home") { view?.sendKey(Key.HOME) }
        K("End") { view?.sendKey(Key.END) }
        K("PgUp") { view?.sendKey(Key.PAGE_UP) }
        K("PgDn") { view?.sendKey(Key.PAGE_DOWN) }
        for (c in listOf("|", "~", "/", "-")) K(c) { view?.sendText(c) }
        K("Paste") { view?.let { pasteClipboard(context, it) } }
        K("A−") { view?.let { it.fontSizeSp -= 1f } }
        K("A+") { view?.let { it.fontSizeSp += 1f } }
        K("⌨") { view?.showKeyboard() }
    }
}

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
                    modifier = Modifier.fillMaxWidth().background(Color(0xFF1C2028)).padding(8.dp),
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
