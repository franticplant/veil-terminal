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
 * The listener binds loopback (unless switched off) and the IPv4 addresses of each interface the
 * user enabled, for example the USB tether (ncm0). Never all interfaces.
 */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    data class Values(
        val port: Int,
        val loopback: Boolean,
        val ifaces: Set<String>,
        val autoOffMinutes: Int,
    )

    private val _values =
        MutableStateFlow(
            Values(
                port = prefs.getInt("port", DEFAULT_PORT),
                loopback = prefs.getBoolean("loopback", true),
                // Earlier versions kept one interface under "iface".
                ifaces =
                    prefs.getStringSet("ifaces", null)?.toSet()
                        ?: setOfNotNull(prefs.getString("iface", null)),
                autoOffMinutes = prefs.getInt("autoOff", 60),
            )
        )

    val values: StateFlow<Values> = _values

    fun update(v: Values) {
        prefs.edit {
            putInt("port", v.port)
            putBoolean("loopback", v.loopback)
            putStringSet("ifaces", v.ifaces)
            remove("iface")
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
