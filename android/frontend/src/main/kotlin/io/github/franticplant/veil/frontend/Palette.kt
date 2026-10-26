package io.github.franticplant.veil.frontend

/** Colors for frame runs: the default pair, the 16 ANSI colors, and xterm's 256-color table. */
object Palette {
    const val DEFAULT_FG = 0xFFD8DEE9.toInt()
    const val DEFAULT_BG = 0xFF101318.toInt()
    const val CURSOR = 0xFFE5C07B.toInt()

    private val ansi =
        intArrayOf(
            0xFF1C1F24.toInt(), // black
            0xFFE06C75.toInt(), // red
            0xFF98C379.toInt(), // green
            0xFFE5C07B.toInt(), // yellow
            0xFF61AFEF.toInt(), // blue
            0xFFC678DD.toInt(), // magenta
            0xFF56B6C2.toInt(), // cyan
            0xFFD8DEE9.toInt(), // white
            0xFF5C6370.toInt(), // bright black
            0xFFFF7A85.toInt(), // bright red
            0xFFB5E890.toInt(), // bright green
            0xFFFFD68A.toInt(), // bright yellow
            0xFF82C4FF.toInt(), // bright blue
            0xFFDE9AF0.toInt(), // bright magenta
            0xFF7FD6E0.toInt(), // bright cyan
            0xFFFFFFFF.toInt(), // bright white
        )

    private val table =
        IntArray(256) { i ->
            when {
                i < 16 -> ansi[i]
                i < 232 -> {
                    val n = i - 16
                    val steps = intArrayOf(0, 95, 135, 175, 215, 255)
                    rgb(steps[n / 36], steps[(n / 6) % 6], steps[n % 6])
                }
                else -> {
                    val v = 8 + (i - 232) * 10
                    rgb(v, v, v)
                }
            }
        }

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /** Resolves a frame color (core/protocol's encoding); [fallback] for the default. */
    fun resolve(color: Int, fallback: Int, bold: Boolean = false): Int =
        when (color ushr 24) {
            1 -> {
                var i = color and 0xFF
                // Bold brightens the 8 basic colors, as most terminals do.
                if (bold && i < 8) i += 8
                table[i]
            }
            2 -> 0xFF000000.toInt() or (color and 0xFFFFFF)
            else -> fallback
        }
}
