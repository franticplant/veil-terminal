package io.github.franticplant.veil.frontend

/**
 * Mouse reports as xterm sends them, for remote programs that turned mouse reporting on (the
 * frame's `Modes.mouse`). SGR encoding (`Modes.mouse_sgr`) when the remote asked for it, else the
 * classic encoding, which cannot express columns or rows past 223 (such events are dropped).
 */
object MouseEncoder {
    enum class Button(val code: Int) {
        LEFT(0),
        MIDDLE(1),
        RIGHT(2),
        /** A motion report with no button held. */
        NONE(3),
        WHEEL_UP(64),
        WHEEL_DOWN(65),
    }

    enum class Kind {
        PRESS,
        RELEASE,
        /** Motion, with [Button] held (or NONE while hovering). */
        MOTION,
    }

    /**
     * One report at zero-based [col], [row]. Returns null when the classic encoding cannot carry
     * the position.
     */
    fun encode(
        button: Button,
        kind: Kind,
        col: Int,
        row: Int,
        sgr: Boolean,
        mods: KeyEncoder.Mods = KeyEncoder.Mods(),
    ): ByteArray? {
        var b = button.code
        if (mods.shift) b += 4
        if (mods.alt) b += 8
        if (mods.ctrl) b += 16
        if (kind == Kind.MOTION) b += 32
        val x = col + 1
        val y = row + 1
        if (sgr) {
            val end = if (kind == Kind.RELEASE) 'm' else 'M'
            return "\u001b[<$b;$x;$y$end".toByteArray()
        }
        // Classic: a release has no button identity (3); wheel events have no release.
        if (kind == Kind.RELEASE) b = (b and 3.inv()) or 3
        if (x > 223 || y > 223) return null
        return byteArrayOf(
            0x1b,
            '['.code.toByte(),
            'M'.code.toByte(),
            (32 + b).toByte(),
            (32 + x).toByte(),
            (32 + y).toByte(),
        )
    }

    /** Press then release, as one click. */
    fun click(button: Button, col: Int, row: Int, sgr: Boolean): ByteArray? {
        val down = encode(button, Kind.PRESS, col, row, sgr) ?: return null
        val up = encode(button, Kind.RELEASE, col, row, sgr) ?: return null
        return down + up
    }
}
