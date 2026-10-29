package io.github.franticplant.veil.frontend

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** How the terminal screen looks and what it lets a remote do. Device-local, holds no secrets. */
class UiPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE)

    /** What to do when a remote offers text for the clipboard (OSC 52). */
    /** Where the engine runs; see the backends in engine-client. */
    enum class EnginePlace(val label: String) {
        /** veil-engine-host, a native process without a JVM (HostBackend). */
        NATIVE("Native"),
        /** The `:engine` Java process (ProcessBackend). */
        SERVICE("Service"),
        /** Inside the app process, through JNI (LocalBackend). */
        LOCAL("In app"),
        /** The fixed engine payload in a Microdroid VM (VmBackend). */
        VM("VM (Microdroid)"),
    }

    enum class ClipboardPolicy {
        ASK,
        ALLOW,
        DENY,
    }

    data class Values(
        val extraKeys: Boolean = true,
        /** Keep a tab strip above the terminal; off uses a compact session picker instead. */
        val tabsOnTop: Boolean = true,
        /** Rows in the extra-keys bar: 1 is compact, 2 shows symbols and function keys at once. */
        val keysRows: Int = 2,
        val fullscreen: Boolean = false,
        val fontSizeSp: Float = 13f,
        val clipboard: ClipboardPolicy = ClipboardPolicy.ASK,
        /** A [Palette] id. */
        val theme: String = Palette.VEIL.id,
        /** In fullscreen, draw into the display cutout (notch) area too. */
        val coverCutout: Boolean = false,
        /** Refuse servers without post-quantum key exchange (hosts may allow classical). */
        val requirePostQuantum: Boolean = true,
        val reconnectAttempts: Int = 6,
        val bellVibrate: Boolean = true,
        val bellFlash: Boolean = true,
        /** The default for new keys: usable only while the phone is unlocked. */
        val keysWhileUnlocked: Boolean = true,
        /** Where the engine runs (applies at the next start). */
        val engine: EnginePlace = EnginePlace.NATIVE,
        /** Guest RAM for the Microdroid engine (applies at the next start). */
        val vmMemoryMiB: Int = 128,
    )

    private val _values =
        MutableStateFlow(
            Values(
                extraKeys = prefs.getBoolean("extraKeys", true),
                tabsOnTop = prefs.getBoolean("tabsOnTop", true),
                keysRows = prefs.getInt("keysRows", 2),
                fullscreen = prefs.getBoolean("fullscreen", false),
                fontSizeSp = prefs.getFloat("fontSize", 13f),
                clipboard =
                    runCatching { ClipboardPolicy.valueOf(prefs.getString("clipboard", "ASK")!!) }
                        .getOrDefault(ClipboardPolicy.ASK),
                theme = prefs.getString("theme", Palette.VEIL.id)!!,
                coverCutout = prefs.getBoolean("coverCutout", false),
                requirePostQuantum = prefs.getBoolean("requirePQ", true),
                reconnectAttempts = prefs.getInt("reconnectAttempts", 6),
                bellVibrate = prefs.getBoolean("bellVibrate", true),
                bellFlash = prefs.getBoolean("bellFlash", true),
                keysWhileUnlocked = prefs.getBoolean("keysWhileUnlocked", true),
                engine =
                    runCatching { EnginePlace.valueOf(prefs.getString("engine", "NATIVE")!!) }
                        .getOrDefault(EnginePlace.NATIVE),
                vmMemoryMiB = prefs.getInt("vmMemoryMiB", 128).coerceIn(32, 1024),
            )
        )

    val values: StateFlow<Values> = _values

    fun update(f: (Values) -> Values) {
        val v = f(_values.value)
        prefs.edit {
            putBoolean("extraKeys", v.extraKeys)
            putBoolean("tabsOnTop", v.tabsOnTop)
            putInt("keysRows", v.keysRows)
            putBoolean("fullscreen", v.fullscreen)
            putFloat("fontSize", v.fontSizeSp)
            putString("clipboard", v.clipboard.name)
            putString("theme", v.theme)
            putBoolean("coverCutout", v.coverCutout)
            putBoolean("requirePQ", v.requirePostQuantum)
            putInt("reconnectAttempts", v.reconnectAttempts)
            putBoolean("bellVibrate", v.bellVibrate)
            putBoolean("bellFlash", v.bellFlash)
            putBoolean("keysWhileUnlocked", v.keysWhileUnlocked)
            putString("engine", v.engine.name)
            putInt("vmMemoryMiB", v.vmMemoryMiB)
        }
        _values.value = v
    }
}
