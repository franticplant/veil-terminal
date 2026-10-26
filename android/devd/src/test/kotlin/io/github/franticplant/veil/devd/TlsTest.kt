package io.github.franticplant.veil.devd

import org.junit.Assert.assertEquals
import org.junit.Test

class TlsTest {
    @Test
    fun pairingCodeMatchesVeilCtl() {
        // The same vector as veil-ctl's pairing_code test.
        assertEquals("478424", Tls.pairingCode("server".toByteArray(), "client".toByteArray()))
    }

    @Test
    fun fingerprintGroups() {
        assertEquals("abcd ef01", Tls.groups("abcdef0123", 2))
    }
}
