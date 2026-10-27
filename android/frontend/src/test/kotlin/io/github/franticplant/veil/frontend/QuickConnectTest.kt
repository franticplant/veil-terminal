package io.github.franticplant.veil.frontend

import io.github.franticplant.veil.engine.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuickConnectTest {
    @Test
    fun parses() {
        assertEquals(Target("example.org", 22, "me"), Profiles.parseQuick("me@example.org"))
        assertEquals(Target("10.0.0.2", 2222, "root"), Profiles.parseQuick(" root@10.0.0.2:2222 "))
        assertEquals(Target("fe80::1", 22, "u"), Profiles.parseQuick("u@[fe80::1]"))
        assertEquals(Target("fe80::1", 2200, "u"), Profiles.parseQuick("u@[fe80::1]:2200"))
    }

    @Test
    fun refuses() {
        assertNull(Profiles.parseQuick("example.org"))
        assertNull(Profiles.parseQuick("me@"))
        assertNull(Profiles.parseQuick("me@host:0"))
        assertNull(Profiles.parseQuick("me@host:99999"))
        assertNull(Profiles.parseQuick("me@ho st"))
        assertNull(Profiles.parseQuick("a@b@c"))
    }
}
