package io.github.franticplant.veil.devd

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The third-party notices the build puts in the APK (assets/third_party_notices.txt), read off the
 * main thread and shown line by line.
 */
@Composable
fun LicensesDialog(onDismiss: () -> Unit) {
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
                    items(l) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 10.sp) }
                }
            }
        },
    )
}
