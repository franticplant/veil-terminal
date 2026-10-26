package io.github.franticplant.veil.frontend

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.franticplant.veil.engine.EngineConnection
import kotlin.time.measureTimedValue
import kotlinx.coroutines.launch

/**
 * v0.0 screen: proves the path UI -> EngineBackend -> engine protocol -> Rust engine and back.
 * Replaced by the session UI once SSH lands.
 */
class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val host = application as EngineHost
    setContent { MaterialTheme(colorScheme = darkColorScheme()) { EngineCheck(host) } }
  }
}

@Composable
private fun EngineCheck(host: EngineHost) {
  val scope = rememberCoroutineScope()
  var conn by remember { mutableStateOf<EngineConnection?>(null) }
  var status by remember { mutableStateOf("connecting…") }
  var lastPing by remember { mutableStateOf("") }

  LaunchedEffect(Unit) {
    status =
        try {
          val c = host.engineBackend.connect("veil-frontend", scope)
          conn = c
          "connected: engine ${c.hello.engineVersion}, protocol v${c.hello.protocolVersion}"
        } catch (e: Exception) {
          "engine failed: ${e.message}"
        }
  }
  // Each connection starts its own engine for now; don't leave one behind per screen.
  DisposableEffect(Unit) { onDispose { conn?.close() } }

  Scaffold { padding ->
    Column(
        Modifier.fillMaxSize().padding(padding).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text("Veil Terminal", style = MaterialTheme.typography.headlineSmall)
      val info = host.buildInfo
      Text(
          "${info.versionName} (${info.versionCode})" +
              if (info.devTools) " · development build" else "",
          fontFamily = FontFamily.Monospace,
      )
      Text("backend: ${host.engineBackend.kind}", fontFamily = FontFamily.Monospace)
      Text(status, fontFamily = FontFamily.Monospace)
      Button(
          enabled = conn != null,
          onClick = {
            val c = conn ?: return@Button
            scope.launch {
              lastPing =
                  try {
                    val (pong, took) = measureTimedValue { c.ping(System.nanoTime()) }
                    "pong in $took, engine up ${pong.engineUptimeMs} ms"
                  } catch (e: Exception) {
                    "ping failed: ${e.message}"
                  }
            }
          },
      ) {
        Text("Ping engine")
      }
      if (lastPing.isNotEmpty()) Text(lastPing, fontFamily = FontFamily.Monospace)
    }
  }
}
