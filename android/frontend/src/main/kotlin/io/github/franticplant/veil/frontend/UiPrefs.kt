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
        }
        _values.value = v
    }
}
