package io.github.franticplant.veil.frontend

import io.github.franticplant.veil.frontend.MouseEncoder.Button
import io.github.franticplant.veil.frontend.MouseEncoder.Kind
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MouseEncoderTest {
    private fun s(b: ByteArray?) = b?.let { String(it, Charsets.ISO_8859_1) }

    @Test
    fun sgr() {
        assertEquals(
            "\u001b[<0;3;2M",
            s(MouseEncoder.encode(Button.LEFT, Kind.PRESS, 2, 1, sgr = true)),
        )
        assertEquals(
            "\u001b[<0;3;2m",
            s(MouseEncoder.encode(Button.LEFT, Kind.RELEASE, 2, 1, sgr = true)),
        )
        assertEquals(
            "\u001b[<64;1;1M",
            s(MouseEncoder.encode(Button.WHEEL_UP, Kind.PRESS, 0, 0, sgr = true)),
        )
        assertEquals(
            "\u001b[<32;5;5M",
            s(MouseEncoder.encode(Button.LEFT, Kind.MOTION, 4, 4, sgr = true)),
        )
        assertEquals(
            "\u001b[<18;1;1M",
            s(
                MouseEncoder.encode(
                    Button.RIGHT,
                    Kind.PRESS,
                    0,
                    0,
                    sgr = true,
                    KeyEncoder.Mods(ctrl = true),
                )
            ),
        )
        // SGR has no position limit.
        assertEquals(
            "\u001b[<0;400;300M",
            s(MouseEncoder.encode(Button.LEFT, Kind.PRESS, 399, 299, sgr = true)),
        )
    }

    @Test
    fun classic() {
        assertArrayEquals(
            byteArrayOf(0x1b, '['.code.toByte(), 'M'.code.toByte(), 32, 33, 33),
            MouseEncoder.encode(Button.LEFT, Kind.PRESS, 0, 0, sgr = false),
        )
        // Release carries button 3.
        assertArrayEquals(
            byteArrayOf(0x1b, '['.code.toByte(), 'M'.code.toByte(), 35, 33, 33),
            MouseEncoder.encode(Button.RIGHT, Kind.RELEASE, 0, 0, sgr = false),
        )
        assertNull(MouseEncoder.encode(Button.LEFT, Kind.PRESS, 300, 0, sgr = false))
    }
}
