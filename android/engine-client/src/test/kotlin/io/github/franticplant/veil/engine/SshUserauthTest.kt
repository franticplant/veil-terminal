package io.github.franticplant.veil.engine

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SshUserauthTest {
    private val pair =
        KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()
    private val pub = pair.public as ECPublicKey
    private val blob = SshUserauth.ecdsaP256Blob(pub.w.affineX, pub.w.affineY)

    private fun str(out: ByteArrayOutputStream, v: ByteArray) {
        out.write(ByteBuffer.allocate(4).putInt(v.size).array())
        out.write(v)
    }

    private fun login(
        user: String,
        key: ByteArray = blob,
        extra: ByteArray = ByteArray(0),
        method: String = "publickey",
    ) =
        ByteArrayOutputStream()
            .apply {
                str(this, ByteArray(32) { 7 })
                write(50)
                str(this, user.toByteArray())
                str(this, "ssh-connection".toByteArray())
                str(this, method.toByteArray())
                write(1)
                str(this, "ecdsa-sha2-nistp256".toByteArray())
                str(this, key)
                write(extra)
            }
            .toByteArray()

    @Test
    fun acceptsExactlyTheLogin() {
        assertTrue(SshUserauth.check(login("alice"), "alice", blob))
        assertFalse("another user", SshUserauth.check(login("mallory"), "alice", blob))
        assertFalse(
            "another key",
            SshUserauth.check(
                login("alice", key = blob.copyOf().also { it[it.size - 1] = 0 }),
                "alice",
                blob,
            ),
        )
        assertFalse(
            "trailing bytes",
            SshUserauth.check(login("alice", extra = byteArrayOf(1)), "alice", blob),
        )
        assertFalse(
            "not publickey",
            SshUserauth.check(login("alice", method = "password"), "alice", blob),
        )
        assertFalse("arbitrary bytes", SshUserauth.check("hello".toByteArray(), "alice", blob))
        assertFalse("truncated", SshUserauth.check(login("alice").copyOf(40), "alice", blob))
    }

    @Test
    fun publicKeyAndSignatureFormats() {
        val line = SshUserauth.openssh(blob, "veil")
        assertTrue(line.startsWith("ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTY"))
        assertTrue(SshUserauth.blobOf(line).contentEquals(blob))
        assertEquals("ecdsa-sha2-nistp256", SshUserauth.algorithmOf(blob))

        val der =
            Signature.getInstance("SHA256withECDSA").run {
                initSign(pair.private)
                update(login("alice"))
                sign()
            }
        val sig = ByteBuffer.wrap(SshUserauth.ecdsaSignatureBlob(der))
        fun s(): ByteArray = ByteArray(sig.int).also { sig.get(it) }
        assertEquals("ecdsa-sha2-nistp256", String(s()))
        val inner = ByteBuffer.wrap(s())
        // Two mpints, each at most 33 bytes (a leading zero when the high bit is set).
        repeat(2) {
            val n = inner.int
            assertTrue(n in 1..33)
            inner.position(inner.position() + n)
        }
        assertFalse(inner.hasRemaining())
        assertFalse(sig.hasRemaining())
    }
}
