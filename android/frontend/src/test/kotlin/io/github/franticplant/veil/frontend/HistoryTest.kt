package io.github.franticplant.veil.frontend

import io.github.franticplant.veil.engine.Target
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryTest {
    private val now = 1_000_000_000_000L
    private val minute = 60_000L

    private fun target(host: String, user: String = "me") = Target(host, 22, user)

    @Test
    fun repeatsInsideTheWindowShareAnEntry() {
        val first = History.recorded(emptyList(), target("example.org"), now)
        val second = History.recorded(first, target("example.org"), now + minute)

        assertEquals(1, second.size)
        assertEquals(2, second[0].count)
        assertEquals(now + minute, second[0].lastUsed)
    }

    @Test
    fun repeatsOutsideTheWindowStaySeparate() {
        val first = History.recorded(emptyList(), target("example.org"), now)
        val later =
            History.recorded(first, target("example.org"), now + History.GROUP_WINDOW_MS + 1)

        assertEquals(2, later.size)
        assertEquals(1, later[0].count)
        assertEquals(1, later[1].count)
    }

    @Test
    fun differentEndpointsStaySeparate() {
        val a = History.recorded(emptyList(), target("a.example", "me"), now)
        val b = History.recorded(a, target("a.example", "other"), now + minute)
        val c = History.recorded(b, target("b.example", "me"), now + 2 * minute)

        assertEquals(3, c.map { it.key }.toSet().size)
    }

    @Test
    fun entriesAreNewestFirst() {
        val a = History.recorded(emptyList(), target("a.example"), now)
        val b = History.recorded(a, target("b.example"), now + minute)

        assertEquals("me@b.example:22", b[0].key)
        assertEquals("me@a.example:22", b[1].key)
    }
}
