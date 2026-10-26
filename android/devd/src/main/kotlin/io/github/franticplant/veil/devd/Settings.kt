package io.github.franticplant.veil.devd

import android.content.Context
import androidx.core.content.edit
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Listener settings. Changed only on the phone: the API has no method for its own configuration.
 *
 * The listener always binds loopback. [iface], if set, adds that one interface's IPv4 address (for
 * example the USB tether, ncm0); it is never all interfaces.
 */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    data class Values(val port: Int, val iface: String?, val autoOffMinutes: Int)

    private val _values =
        MutableStateFlow(
            Values(
                port = prefs.getInt("port", DEFAULT_PORT),
                iface = prefs.getString("iface", null),
                autoOffMinutes = prefs.getInt("autoOff", 60),
            )
        )

    val values: StateFlow<Values> = _values

    fun update(v: Values) {
        prefs.edit {
            putInt("port", v.port)
            putString("iface", v.iface)
            putInt("autoOff", v.autoOffMinutes)
        }
        _values.value = v
    }

    companion object {
        const val DEFAULT_PORT = 7390
        val AUTO_OFF_CHOICES = listOf(15, 60, 240, 480)
    }
}

/** The device's up, non-loopback interfaces with their IPv4 addresses. */
fun ipv4Interfaces(): List<Pair<String, List<Inet4Address>>> =
    try {
        NetworkInterface.getNetworkInterfaces()
            .toList()
            .filter { it.isUp && !it.isLoopback }
            .map { nif -> nif.name to nif.inetAddresses.toList().filterIsInstance<Inet4Address>() }
            .filter { it.second.isNotEmpty() }
            .sortedBy { it.first }
    } catch (e: Exception) {
        emptyList()
    }
