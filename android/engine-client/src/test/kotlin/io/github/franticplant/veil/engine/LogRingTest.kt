package io.github.franticplant.veil.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class LogRingTest {
    @Test
    fun readsAfterACursorWithFilters() {
        val ring = LogRing(capacity = 10, logcat = false)
        ring.info("a", "one")
        ring.warn("b", "two")
        ring.info("a", "three")
        assertEquals(listOf("two", "three"), ring.read(1, 10).entries.map { it.message })
        val warn = ring.read(0, 10, minLevel = LogRing.Level.WARN)
        assertEquals(listOf("two"), warn.entries.map { it.message })
        assertEquals(3, warn.nextSeq)
        assertEquals(
            listOf("one", "three"),
            ring.read(0, 10, component = "a").entries.map { it.message },
        )
        assertEquals(listOf("three"), ring.read(0, 10, grep = "THR").entries.map { it.message })
        assertEquals(2, ring.read(0, 2).nextSeq)
    }

    @Test
    fun reportsDropped() {
        val ring = LogRing(capacity = 3, logcat = false)
        repeat(5) { ring.info("a", "m$it") }
        val b = ring.read(0, 10)
        assertEquals(2, b.dropped)
        assertEquals(listOf("m2", "m3", "m4"), b.entries.map { it.message })
        assertEquals(5, ring.lastSeq)
    }
}
