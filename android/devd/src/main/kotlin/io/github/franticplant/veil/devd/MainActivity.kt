package io.github.franticplant.veil.devd

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import io.github.franticplant.veil.dev.api.Scope
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * devd's screen. Everything that grants or widens access happens here and only here: switching the
 * listener on, choosing the interface, approving pairings and their scopes, revoking them.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        val app = RemoteDevd(this)
        remote = app
        setContent {
            LaunchedEffect(Unit) { app.poll() }
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    var tab by remember { mutableIntStateOf(0) }
                    Column(Modifier.safeDrawingPadding()) {
                        TabRow(selectedTabIndex = tab) {
                            Tab(
                                selected = tab == 0,
                                onClick = { tab = 0 },
                                text = { Text("Status") },
                            )
                            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Logs") })
                            Tab(
                                selected = tab == 2,
                                onClick = { tab = 2 },
                                text = { Text("Metrics") },
                            )
                        }
                        when (tab) {
                            0 -> Screen(app)
                            1 -> LogViewer(app)
                            else -> MetricsView(app)
                        }
                    }
                }
            }
        }
    }

    private var remote: RemoteDevd? = null

    override fun onDestroy() {
        remote?.close()
        super.onDestroy()
    }

    @Composable
    private fun Screen(app: RemoteDevd) {
        val scope = rememberCoroutineScope()
        val offAt by app.offAtMs.collectAsState()
        val addresses by app.addresses.collectAsState()
        val settings by app.settings.collectAsState()
        val pending by app.pending.collectAsState()
        val pairings by app.pairings.collectAsState()
        val binds by app.binds.collectAsState()
        val fingerprint by app.fingerprint.collectAsState()
        val veil by app.veil.collectAsState()
        fun update(v: Settings.Values) = scope.launch { runCatching { app.updateSettings(v) } }
        var tick by remember { mutableIntStateOf(0) }
        LaunchedEffect(Unit) {
            while (true) {
                delay(5_000)
                tick++
            }
        }
        val time = DateFormat.getTimeInstance(DateFormat.SHORT)

        Column(
            Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Veil devd", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                "${BuildConfig.VERSION_NAME} · development control for Veil. Never install on a production setup.",
                style = MaterialTheme.typography.bodySmall,
            )

            pending?.let { PairingCard(app, it) }

            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Listener", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                        Switch(
                            checked = offAt != null,
                            onCheckedChange = { on ->
                                if (on) DevdService.start(this@MainActivity)
                                else DevdService.stop(this@MainActivity)
                            },
                        )
                    }
                    if (offAt != null) {
                        Text("On: ${addresses.joinToString().ifEmpty { "binding…" }}")
                        Text("Switches off at ${time.format(Date(offAt!!))}")
                    } else {
                        Text("Off")
                    }
                    Text("Listen on:", style = MaterialTheme.typography.labelLarge)
                    val ifaces = remember(tick) { ipv4Interfaces() }
                    @Composable
                    fun IfaceRow(
                        name: String,
                        addrs: String,
                        on: Boolean,
                        toggle: (Boolean) -> Unit,
                    ) {
                        val mine = binds.filter { it.iface == name }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(name, fontFamily = FontFamily.Monospace)
                                Text(addrs, style = MaterialTheme.typography.bodySmall)
                                if (on && offAt != null) {
                                    for (b in mine) {
                                        val (label, color) =
                                            when (b.state) {
                                                "listening" ->
                                                    "listening on ${b.address}" to Color(0xFF98C379)
                                                "down" -> "not up: ${b.detail}" to Color(0xFFE5C07B)
                                                else ->
                                                    "${b.address}: ${b.detail}" to Color(0xFFE06C75)
                                            }
                                        Text(label, color = color, fontSize = 12.sp)
                                    }
                                }
                            }
                            Switch(checked = on, onCheckedChange = toggle)
                        }
                    }
                    IfaceRow(
                        Server.LOOPBACK,
                        "127.0.0.1 (this phone; tailnet forwards arrive here)",
                        settings.loopback,
                    ) { on ->
                        update(settings.copy(loopback = on))
                    }
                    val names = (ifaces.map { it.first } + settings.ifaces).distinct().sorted()
                    for (name in names) {
                        val addrs =
                            ifaces
                                .firstOrNull { it.first == name }
                                ?.second
                                ?.joinToString { it.hostAddress ?: "" }
                        IfaceRow(name, addrs ?: "not up now", name in settings.ifaces) { on ->
                            update(
                                settings.copy(
                                    ifaces =
                                        if (on) settings.ifaces + name else settings.ifaces - name
                                )
                            )
                        }
                    }
                    Text("Port ${settings.port}. Auto-off after:")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (m in Settings.AUTO_OFF_CHOICES) {
                            FilterChip(
                                selected = settings.autoOffMinutes == m,
                                onClick = {
                                    update(settings.copy(autoOffMinutes = m))
                                },
                                label = { Text(if (m < 60) "${m}m" else "${m / 60}h") },
                            )
                        }
                    }
                    Text(
                        "Server key: ${Tls.groups(fingerprint)}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                }
            }

            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Installs", fontWeight = FontWeight.Bold)
                    Text(veil ?: "Veil is not installed")
                    if (!packageManager.canRequestPackageInstalls()) {
                        Text("devd may not install apps yet.")
                        OutlinedButton(
                            onClick = {
                                startActivity(
                                    Intent(
                                        AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                        "package:$packageName".toUri(),
                                    )
                                )
                            }
                        ) {
                            Text("Allow installs from devd")
                        }
                    } else {
                        Text("devd may install Veil packages.")
                    }
                }
            }

            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Paired clients", fontWeight = FontWeight.Bold)
                    if (pairings.isEmpty()) Text("None. On the laptop: veil-ctl -a ADDRESS pair")
                    for (p in pairings) {
                        val expired = p.expiresMs < System.currentTimeMillis()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(p.name + if (expired) " (expired)" else "")
                                Text(
                                    p.scopes.sorted().joinToString() +
                                        " · until " +
                                        DateFormat.getDateInstance(DateFormat.SHORT)
                                            .format(Date(p.expiresMs)),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            TextButton(
                                onClick = { scope.launch { runCatching { app.revoke(p) } } }
                            ) {
                                Text("Revoke")
                            }
                        }
                    }
                }
            }

            Text(
                "API calls and devd's own log are in the Logs tab (persistent, hash-chained).",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    @Composable
    private fun PairingCard(app: RemoteDevd, req: PendingView) {
        val scope = rememberCoroutineScope()
        var scopes by remember(req) { mutableStateOf(req.requestedScopes) }
        var days by remember(req) { mutableIntStateOf(7) }
        Card {
            Column(
                Modifier.padding(12.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Pairing request", fontWeight = FontWeight.Bold)
                Text("'${req.name}' wants to control Veil.")
                Text(
                    "Arrived on ${req.local} from ${req.peer}" +
                        if (req.peer.startsWith("127."))
                            " (loopback: a local app or a forward, e.g. the tailnet)"
                        else "",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("Code: ${req.code}", fontSize = 28.sp, fontFamily = FontFamily.Monospace)
                Text(
                    "Approve only if the laptop shows the same code.",
                    style = MaterialTheme.typography.bodySmall,
                )
                for (s in Scope.ALL) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = s in scopes,
                            onCheckedChange = { on -> scopes = if (on) scopes + s else scopes - s },
                        )
                        Text(s + if (s !in req.requestedScopes) " (not asked for)" else "")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (d in listOf(1, 7, 30)) {
                        FilterChip(
                            selected = days == d,
                            onClick = { days = d },
                            label = { Text("${d}d") },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            scope.launch { runCatching { app.approve(req, scopes, days) } }
                        },
                        enabled = scopes.isNotEmpty(),
                    ) {
                        Text("Approve")
                    }
                    OutlinedButton(onClick = { scope.launch { runCatching { app.deny(req) } } }) {
                        Text("Deny")
                    }
                }
            }
        }
    }
}
