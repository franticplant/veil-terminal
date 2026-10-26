package io.github.franticplant.veil.frontend

/**
 * A color theme: the terminal's default colors, the 16 ANSI colors (xterm's 256-color table is
 * built from them), and the colors of the app's own chrome around the terminal.
 */
class Palette(
    val id: String,
    val name: String,
    val fg: Int,
    val bg: Int,
    val cursor: Int,
    ansi: IntArray,
    /** Chrome: the keys bar and bars around the terminal. */
    val chrome: Int,
    val chromeActive: Int,
    val chromeText: Int,
    /** Whether the app's own UI (dialogs, lists) should be dark. */
    val dark: Boolean = true,
) {
    init {
        require(ansi.size == 16)
    }

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

    companion object {
        private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

        private fun c(hex: Long) = hex.toInt()

        private fun ansi(vararg hex: Long) = IntArray(16) { c(hex[it]) }

        private val VEIL_ANSI =
            ansi(
                0xFF1C1F24,
                0xFFE06C75,
                0xFF98C379,
                0xFFE5C07B,
                0xFF61AFEF,
                0xFFC678DD,
                0xFF56B6C2,
                0xFFD8DEE9,
                0xFF5C6370,
                0xFFFF7A85,
                0xFFB5E890,
                0xFFFFD68A,
                0xFF82C4FF,
                0xFFDE9AF0,
                0xFF7FD6E0,
                0xFFFFFFFF,
            )

        val VEIL =
            Palette(
                "veil",
                "Veil Dark",
                c(0xFFD8DEE9),
                c(0xFF101318),
                c(0xFFE5C07B),
                VEIL_ANSI,
                chrome = c(0xFF1C2028),
                chromeActive = c(0xFF3A4150),
                chromeText = c(0xFFD8DEE9),
            )

        /** True black: pixels off on OLED screens, everywhere including the chrome. */
        val AMOLED =
            Palette(
                "amoled",
                "AMOLED Black",
                c(0xFFD0D0D0),
                c(0xFF000000),
                c(0xFFE5C07B),
                VEIL_ANSI.clone().also { it[0] = c(0xFF000000) },
                chrome = c(0xFF000000),
                chromeActive = c(0xFF262626),
                chromeText = c(0xFFB8B8B8),
            )

        val GRUVBOX =
            Palette(
                "gruvbox",
                "Gruvbox Dark",
                c(0xFFEBDBB2),
                c(0xFF282828),
                c(0xFFFE8019),
                ansi(
                    0xFF282828,
                    0xFFCC241D,
                    0xFF98971A,
                    0xFFD79921,
                    0xFF458588,
                    0xFFB16286,
                    0xFF689D6A,
                    0xFFA89984,
                    0xFF928374,
                    0xFFFB4934,
                    0xFFB8BB26,
                    0xFFFABD2F,
                    0xFF83A598,
                    0xFFD3869B,
                    0xFF8EC07C,
                    0xFFEBDBB2,
                ),
                chrome = c(0xFF32302F),
                chromeActive = c(0xFF504945),
                chromeText = c(0xFFEBDBB2),
            )

        val DRACULA =
            Palette(
                "dracula",
                "Dracula",
                c(0xFFF8F8F2),
                c(0xFF282A36),
                c(0xFFF8F8F2),
                ansi(
                    0xFF21222C,
                    0xFFFF5555,
                    0xFF50FA7B,
                    0xFFF1FA8C,
                    0xFFBD93F9,
                    0xFFFF79C6,
                    0xFF8BE9FD,
                    0xFFF8F8F2,
                    0xFF6272A4,
                    0xFFFF6E6E,
                    0xFF69FF94,
                    0xFFFFFFA5,
                    0xFFD6ACFF,
                    0xFFFF92DF,
                    0xFFA4FFFF,
                    0xFFFFFFFF,
                ),
                chrome = c(0xFF21222C),
                chromeActive = c(0xFF44475A),
                chromeText = c(0xFFF8F8F2),
            )

        val SOLARIZED =
            Palette(
                "solarized",
                "Solarized Dark",
                c(0xFF93A1A1),
                c(0xFF002B36),
                c(0xFF93A1A1),
                ansi(
                    0xFF073642,
                    0xFFDC322F,
                    0xFF859900,
                    0xFFB58900,
                    0xFF268BD2,
                    0xFFD33682,
                    0xFF2AA198,
                    0xFFEEE8D5,
                    0xFF002B36,
                    0xFFCB4B16,
                    0xFF586E75,
                    0xFF657B83,
                    0xFF839496,
                    0xFF6C71C4,
                    0xFF93A1A1,
                    0xFFFDF6E3,
                ),
                chrome = c(0xFF073642),
                chromeActive = c(0xFF124654),
                chromeText = c(0xFF93A1A1),
            )

        val LIGHT =
            Palette(
                "light",
                "Light",
                c(0xFF24292F),
                c(0xFFFAFAFA),
                c(0xFF0969DA),
                ansi(
                    0xFF24292F,
                    0xFFCF222E,
                    0xFF116329,
                    0xFF4D2D00,
                    0xFF0969DA,
                    0xFF8250DF,
                    0xFF1B7C83,
                    0xFF6E7781,
                    0xFF57606A,
                    0xFFA40E26,
                    0xFF1A7F37,
                    0xFF633C01,
                    0xFF218BFF,
                    0xFFA475F9,
                    0xFF3192AA,
                    0xFF8C959F,
                ),
                chrome = c(0xFFEAECEF),
                chromeActive = c(0xFFD0D7DE),
                chromeText = c(0xFF24292F),
                dark = false,
            )

        val ALL = listOf(VEIL, AMOLED, GRUVBOX, DRACULA, SOLARIZED, LIGHT)

        fun byId(id: String): Palette = ALL.firstOrNull { it.id == id } ?: VEIL
    }
}
