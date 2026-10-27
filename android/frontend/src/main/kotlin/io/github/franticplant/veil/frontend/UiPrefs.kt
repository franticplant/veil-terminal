package io.github.franticplant.veil.frontend

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** How the terminal screen looks and what it lets a remote do. Device-local, holds no secrets. */
class UiPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE)

    /** What to do when a remote offers text for the clipboard (OSC 52). */
    enum class ClipboardPolicy {
        ASK,
        ALLOW,
        DENY,
    }

    data class Values(
        val extraKeys: Boolean = true,
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
    )

    private val _values =
        MutableStateFlow(
            Values(
                extraKeys = prefs.getBoolean("extraKeys", true),
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
            )
        )

    val values: StateFlow<Values> = _values

    fun update(f: (Values) -> Values) {
        val v = f(_values.value)
        prefs.edit {
            putBoolean("extraKeys", v.extraKeys)
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
        }
        _values.value = v
    }
}
