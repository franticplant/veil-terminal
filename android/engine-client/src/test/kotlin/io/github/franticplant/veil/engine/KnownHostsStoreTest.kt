package io.github.franticplant.veil.engine

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KnownHostsStoreTest {
    private val fingerprint = "SHA256:Q8UZlmi2uaTZOAvy8TS81yF9L4NVmFdnPRYC11H8qrk"

    private fun file(): File =
        File(Files.createTempDirectory("veil-known-hosts").toFile(), "known_hosts.frontend")

    private fun check(store: KnownHostsStore, fingerprint: String = this.fingerprint) =
        store.check("keynest.example", 27015, "ssh-ed25519", fingerprint)

    @Test
    fun remembersAcrossReloadsAndForgets() {
        val path = file()
        val store = KnownHostsStore(path, LogRing(logcat = false))
        assertEquals(KnownHostsStore.Check.Unknown, check(store))

        store.remember("keynest.example", 27015, "ssh-ed25519", fingerprint)
        assertEquals(
            KnownHostsStore.Check.Known(
                KnownHostsStore.Pin("keynest.example", 27015, "ssh-ed25519", fingerprint)
            ),
            check(store),
        )
        val changed = check(store, "SHA256:different")
        assertTrue(changed is KnownHostsStore.Check.Changed)
        assertEquals(fingerprint, (changed as KnownHostsStore.Check.Changed).pin.fingerprint)

        val reloaded = KnownHostsStore(path, LogRing(logcat = false))
        assertEquals(1, reloaded.list().size)
        assertTrue(check(reloaded) is KnownHostsStore.Check.Known)
        assertEquals(1, reloaded.forget("keynest.example", 27015))
        assertEquals(KnownHostsStore.Check.Unknown, check(reloaded))
        assertEquals(0, reloaded.forget("keynest.example", 27015))
    }

    @Test
    fun replacesTheOldPinForTheSameHost() {
        val store = KnownHostsStore(file(), LogRing(logcat = false))
        store.remember("host", 22, "ssh-ed25519", "SHA256:old")
        store.remember("host", 22, "ssh-ed25519", "SHA256:new")
        assertEquals(1, store.list().size)
        assertTrue(
            store.check("host", 22, "ssh-ed25519", "SHA256:new") is KnownHostsStore.Check.Known
        )
        assertEquals(
            "SHA256:new",
            (store.check("host", 22, "ssh-ed25519", "SHA256:other")
                    as KnownHostsStore.Check.Changed)
                .pin
                .fingerprint,
        )
    }

    @Test
    fun skipsMalformedLines() {
        val path = file()
        requireNotNull(path.parentFile).mkdirs()
        path.writeText(
            """
            # comment
            good.example	2222	ssh-ed25519	SHA256:good
            bad-port	not-a-port	ssh-ed25519	SHA256:bad
            too-few	fields
            """
                .trimIndent()
        )
        val store = KnownHostsStore(path, LogRing(logcat = false))
        assertEquals(
            listOf(KnownHostsStore.Pin("good.example", 2222, "ssh-ed25519", "SHA256:good")),
            store.list(),
        )
    }
}
