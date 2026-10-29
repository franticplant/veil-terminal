package io.github.franticplant.veil.frontend

import io.github.franticplant.veil.frontend.KeyEncoder.Key
import io.github.franticplant.veil.frontend.KeyEncoder.Mods
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyEncoderTest {
    private fun s(b: ByteArray) = String(b)

    @Test
    fun arrowsFollowCursorMode() {
        assertEquals("\u001b[A", s(KeyEncoder.key(Key.UP)))
        assertEquals("\u001bOA", s(KeyEncoder.key(Key.UP, appCursor = true)))
        assertEquals("\u001b[1;5D", s(KeyEncoder.key(Key.LEFT, Mods(ctrl = true))))
    }

    @Test
    fun editingKeys() {
        assertEquals("\r", s(KeyEncoder.key(Key.ENTER)))
        assertEquals("\u007f", s(KeyEncoder.key(Key.BACKSPACE)))
        assertEquals("\u001b[Z", s(KeyEncoder.key(Key.TAB, Mods(shift = true))))
        assertEquals("\u001b[3~", s(KeyEncoder.key(Key.DELETE)))
        assertEquals("\u001b[5;3~", s(KeyEncoder.key(Key.PAGE_UP, Mods(alt = true))))
        assertEquals("\u001bOP", s(KeyEncoder.key(Key.F1)))
        assertEquals("\u001b[24~", s(KeyEncoder.key(Key.F12)))
    }

    @Test
    fun controlAndAlt() {
        assertEquals("\u0003", s(KeyEncoder.text("c", Mods(ctrl = true))))
        assertEquals("\u0003", s(KeyEncoder.text("C", Mods(ctrl = true))))
        assertEquals("\u001bx", s(KeyEncoder.text("x", Mods(alt = true))))
        assertEquals("\u001b\u0004", s(KeyEncoder.text("d", Mods(ctrl = true, alt = true))))
        assertEquals("ls\r", s(KeyEncoder.text("ls\n")))
    }

    @Test
    fun stickyShiftShiftsTheNextCharacter() {
        assertEquals("A", s(KeyEncoder.text("a", Mods(shift = true))))
        assertEquals("!", s(KeyEncoder.text("1", Mods(shift = true))))
        assertEquals("|", s(KeyEncoder.text("\\", Mods(shift = true))))
        assertEquals("|", s(KeyEncoder.text("|")))
        assertEquals("\u0003", s(KeyEncoder.text("c", Mods(ctrl = true, shift = true))))
    }

    @Test
    fun ctrlShiftAndAltCombine() {
        assertEquals("\u001b[1;6D", s(KeyEncoder.key(Key.LEFT, Mods(ctrl = true, shift = true))))
        assertEquals(
            "\u001b[1;8A",
            s(KeyEncoder.key(Key.UP, Mods(ctrl = true, alt = true, shift = true))),
        )
        assertEquals("\u001b\u0003", s(KeyEncoder.text("c", Mods(ctrl = true, alt = true))))
    }

    @Test
    fun pasteCannotEscapeBracketedMode() {
        val evil = "harmless\u001b[201~rm -rf ~\n"
        assertEquals(
            "\u001b[200~harmlessrm -rf ~\r\u001b[201~",
            s(KeyEncoder.paste(evil, bracketed = true)),
        )
        assertEquals("a\rb", s(KeyEncoder.paste("a\r\nb", bracketed = false)))
    }
}
