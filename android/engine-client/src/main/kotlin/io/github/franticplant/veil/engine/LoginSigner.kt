package io.github.franticplant.veil.engine

import io.github.franticplant.veil.engine.proto.SignRequest
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.util.Base64

/**
 * Signs SSH logins with keys the engine never sees. The engine asks through a SignRequest; the
 * signer checks that the bytes really are a publickey login for the expected user and key
 * ([SshUserauth.check]) and signs only then.
 */
interface LoginSigner {
    /**
     * The OpenSSH public key ("ecdsa-sha2-nistp256 AAAA…") of [keyId], or null if there is none.
     */
    fun publicKey(keyId: String): String?

    /**
     * Signs [request] with [keyId] for [user]; returns the SSH signature blob, or null to refuse
     * (unknown key, a request that is not the expected login, the user cancelled).
     */
    fun sign(keyId: String, user: String, request: SignRequest): ByteArray?
}

/** The SSH wire formats a signer needs (RFC 4251, 4252, 5656). */
object SshUserauth {
    private const val SSH_MSG_USERAUTH_REQUEST = 50

    /** The key blob of an OpenSSH public key line. */
    fun blobOf(openssh: String): ByteArray {
        val parts = openssh.trim().split(Regex("\\s+"))
        require(parts.size >= 2) { "not an OpenSSH public key" }
        return Base64.getDecoder().decode(parts[1])
    }

    /**
     * Whether [data] is exactly what an SSH client signs to log in as [user] with the key [blob]:
     * string session id, byte 50, string user, string "ssh-connection", string "publickey", true,
     * string algorithm, string key blob, and nothing after it.
     */
    fun check(data: ByteArray, user: String, blob: ByteArray): Boolean =
        try {
            val b = ByteBuffer.wrap(data)
            val sessionId = string(b)
            sessionId.isNotEmpty() &&
                b.get().toInt() == SSH_MSG_USERAUTH_REQUEST &&
                String(string(b), Charsets.UTF_8) == user &&
                String(string(b), Charsets.UTF_8) == "ssh-connection" &&
                String(string(b), Charsets.UTF_8) == "publickey" &&
                b.get().toInt() == 1 &&
                String(string(b), Charsets.UTF_8) == algorithmOf(blob) &&
                string(b).contentEquals(blob) &&
                !b.hasRemaining()
        } catch (e: RuntimeException) {
            // Short or malformed: not a login.
            false
        }

    /** The algorithm name at the start of a key blob. */
    fun algorithmOf(blob: ByteArray): String = String(string(ByteBuffer.wrap(blob)), Charsets.UTF_8)

    /** An ecdsa-sha2-nistp256 public key blob from the curve point's coordinates. */
    fun ecdsaP256Blob(x: BigInteger, y: BigInteger): ByteArray {
        val point = ByteArray(65)
        point[0] = 4
        unsigned(x, 32).copyInto(point, 1)
        unsigned(y, 32).copyInto(point, 33)
        return ByteArrayOutputStream()
            .apply {
                putString(this, "ecdsa-sha2-nistp256".toByteArray())
                putString(this, "nistp256".toByteArray())
                putString(this, point)
            }
            .toByteArray()
    }

    /** The SSH signature blob for an ECDSA P-256 signature in DER (what Java's Signature makes). */
    fun ecdsaSignatureBlob(der: ByteArray): ByteArray {
        val (r, s) = parseDerSignature(der)
        val inner =
            ByteArrayOutputStream()
                .apply {
                    putString(this, r.toByteArray()) // mpint: two's complement, minimal
                    putString(this, s.toByteArray())
                }
                .toByteArray()
        return ByteArrayOutputStream()
            .apply {
                putString(this, "ecdsa-sha2-nistp256".toByteArray())
                putString(this, inner)
            }
            .toByteArray()
    }

    fun openssh(blob: ByteArray, comment: String = ""): String =
        (algorithmOf(blob) +
            " " +
            Base64.getEncoder().encodeToString(blob) +
            if (comment.isEmpty()) "" else " $comment")

    private fun parseDerSignature(der: ByteArray): Pair<BigInteger, BigInteger> {
        val b = ByteBuffer.wrap(der)
        require(b.get().toInt() == 0x30) { "not a DER sequence" }
        derLength(b)
        fun integer(): BigInteger {
            require(b.get().toInt() == 0x02) { "not a DER integer" }
            val n = derLength(b)
            val v = ByteArray(n).also { b.get(it) }
            return BigInteger(v)
        }
        return integer() to integer()
    }

    private fun derLength(b: ByteBuffer): Int {
        val first = b.get().toInt() and 0xff
        if (first < 0x80) return first
        var n = 0
        repeat(first and 0x7f) { n = (n shl 8) or (b.get().toInt() and 0xff) }
        return n
    }

    private fun unsigned(v: BigInteger, size: Int): ByteArray {
        val raw = v.toByteArray()
        val out = ByteArray(size)
        val from = maxOf(0, raw.size - size)
        raw.copyInto(out, size - (raw.size - from), from, raw.size)
        return out
    }

    private fun string(b: ByteBuffer): ByteArray {
        val n = b.int
        require(n in 0..b.remaining()) { "string longer than the data" }
        return ByteArray(n).also { b.get(it) }
    }

    private fun putString(out: ByteArrayOutputStream, v: ByteArray) {
        out.write(ByteBuffer.allocate(4).putInt(v.size).array())
        out.write(v)
    }
}
