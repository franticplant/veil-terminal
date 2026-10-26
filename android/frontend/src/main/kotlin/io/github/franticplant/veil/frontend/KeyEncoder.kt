package io.github.franticplant.veil.frontend

/** Turns keys and text into the bytes an xterm-compatible remote expects. */
object KeyEncoder {
    enum class Key {
        ENTER,
        TAB,
        BACKSPACE,
        ESCAPE,
        UP,
        DOWN,
        LEFT,
        RIGHT,
        HOME,
        END,
        PAGE_UP,
        PAGE_DOWN,
        INSERT,
        DELETE,
        F1,
        F2,
        F3,
        F4,
        F5,
        F6,
        F7,
        F8,
        F9,
        F10,
        F11,
        F12,
    }

    data class Mods(
        val ctrl: Boolean = false,
        val alt: Boolean = false,
        val shift: Boolean = false,
    ) {
        /** xterm's modifier parameter: 1 + shift + 2*alt + 4*ctrl. */
        val param: Int
            get() = 1 + (if (shift) 1 else 0) + (if (alt) 2 else 0) + (if (ctrl) 4 else 0)

        val any: Boolean
            get() = ctrl || alt || shift
    }

    private const val ESC = "\u001b"

    fun key(key: Key, mods: Mods = Mods(), appCursor: Boolean = false): ByteArray {
        val m = mods.param
        val s =
            when (key) {
                Key.ENTER -> (if (mods.alt) ESC else "") + "\r"
                Key.TAB -> if (mods.shift) "$ESC[Z" else "\t"
                Key.BACKSPACE -> (if (mods.alt) ESC else "") + if (mods.ctrl) "\u0008" else "\u007f"
                Key.ESCAPE -> ESC
                Key.UP -> cursor('A', m, appCursor)
                Key.DOWN -> cursor('B', m, appCursor)
                Key.RIGHT -> cursor('C', m, appCursor)
                Key.LEFT -> cursor('D', m, appCursor)
                Key.HOME -> cursor('H', m, appCursor)
                Key.END -> cursor('F', m, appCursor)
                Key.PAGE_UP -> tilde(5, m)
                Key.PAGE_DOWN -> tilde(6, m)
                Key.INSERT -> tilde(2, m)
                Key.DELETE -> tilde(3, m)
                Key.F1 -> ss3('P', m)
                Key.F2 -> ss3('Q', m)
                Key.F3 -> ss3('R', m)
                Key.F4 -> ss3('S', m)
                Key.F5 -> tilde(15, m)
                Key.F6 -> tilde(17, m)
                Key.F7 -> tilde(18, m)
                Key.F8 -> tilde(19, m)
                Key.F9 -> tilde(20, m)
                Key.F10 -> tilde(21, m)
                Key.F11 -> tilde(23, m)
                Key.F12 -> tilde(24, m)
            }
        return s.toByteArray()
    }

    /** Typed text. Ctrl applies to a single character; Alt prefixes ESC. */
    fun text(text: String, mods: Mods = Mods()): ByteArray {
        var s = text.replace("\n", "\r")
        if (mods.ctrl && s.codePointCount(0, s.length) == 1) {
            control(s[0])?.let { s = it.toString() }
        }
        if (mods.alt) s = ESC + s
        return s.toByteArray()
    }

    /**
     * Pasted text. With bracketed paste on, it is wrapped in ESC[200~ … ESC[201~, and any end
     * marker inside the text is removed first: otherwise pasted content could end the paste early
     * and have the rest run as typed commands.
     */
    fun paste(text: String, bracketed: Boolean): ByteArray {
        val normalized = text.replace("\r\n", "\r").replace('\n', '\r')
        if (!bracketed) return normalized.toByteArray()
        val safe = normalized.replace("$ESC[201~", "").replace("$ESC[200~", "")
        return "$ESC[200~$safe$ESC[201~".toByteArray()
    }

    /** The control character for Ctrl+[c], or null if there is none. */
    fun control(c: Char): Char? =
        when (val u = c.uppercaseChar()) {
            in 'A'..'Z' -> (u.code - 'A'.code + 1).toChar()
            '@',
            ' ',
            '2' -> 0.toChar()
            '[',
            '3' -> 27.toChar()
            '\\',
            '4' -> 28.toChar()
            ']',
            '5' -> 29.toChar()
            '^',
            '6' -> 30.toChar()
            '_',
            '-',
            '7' -> 31.toChar()
            '?',
            '8' -> 127.toChar()
            else -> null
        }

    private fun cursor(c: Char, m: Int, app: Boolean): String =
        when {
            m > 1 -> "$ESC[1;$m$c"
            app -> "${ESC}O$c"
            else -> "$ESC[$c"
        }

    private fun tilde(n: Int, m: Int): String = if (m > 1) "$ESC[$n;$m~" else "$ESC[$n~"

    private fun ss3(c: Char, m: Int): String = if (m > 1) "$ESC[1;$m$c" else "${ESC}O$c"
}
