package io.github.franticplant.veil.devd

import android.annotation.SuppressLint
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Calendar
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager
import javax.security.auth.x500.X500Principal

/**
 * devd's TLS identity and policy: TLS 1.3 only, mutual authentication, keys pinned on both sides.
 *
 * The server key is a P-256 key in Android Keystore (it never leaves it); Keystore's self-signed
 * certificate for it is the server certificate, which clients pin at pairing. Clients present a
 * self-signed P-256 certificate; the TLS layer only checks that it is well formed, valid and
 * self-signed (the handshake proves the client holds its key), and whether that certificate is
 * paired is decided after the handshake by its SHA-256 ([Pairings]).
 */
object Tls {
    private const val ALIAS = "devd-tls"

    /** The server certificate, creating the key on first use. */
    fun serverCertificate(): X509Certificate {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getCertificate(ALIAS) as X509Certificate?)?.let {
            return it
        }
        val start = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }.time
        val end = Calendar.getInstance().apply { add(Calendar.YEAR, 30) }.time
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            .apply {
                initialize(
                    KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        // The TLS stack may hash itself and ask for a raw signature.
                        .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_NONE)
                        .setCertificateSubject(X500Principal("CN=veil-devd"))
                        .setCertificateNotBefore(start)
                        .setCertificateNotAfter(end)
                        .build()
                )
            }
            .generateKeyPair()
        return ks.getCertificate(ALIAS) as X509Certificate
    }

    private fun serverKey(): PrivateKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        serverCertificate()
        return ks.getKey(ALIAS, null) as PrivateKey
    }

    fun context(): SSLContext {
        val cert = serverCertificate()
        val km = SingleKeyManager(ALIAS, serverKey(), arrayOf(cert))
        return SSLContext.getInstance("TLSv1.3").apply {
            init(arrayOf(km), arrayOf(ClientCertPolicy), SecureRandom())
        }
    }

    /** SHA256withECDSA over [data] with the server key (in Keystore), DER-encoded. */
    fun sign(data: ByteArray): ByteArray =
        java.security.Signature.getInstance("SHA256withECDSA").run {
            initSign(serverKey())
            update(data)
            sign()
        }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * The six-digit code both sides show while pairing. It binds both certificates, so a machine in
     * the middle (which must present its own certificate to each side) produces different codes.
     */
    fun pairingCode(serverCert: ByteArray, clientCert: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").apply { update(serverCert) }.digest(clientCert)
        val n =
            ((d[0].toLong() and 0xff) shl 24) or
                ((d[1].toLong() and 0xff) shl 16) or
                ((d[2].toLong() and 0xff) shl 8) or
                (d[3].toLong() and 0xff)
        return "%06d".format(n % 1_000_000)
    }

    /** Groups a hex fingerprint for reading aloud: "ab12 cd34 …". */
    fun groups(hex: String, n: Int = 8): String = hex.take(n * 4).chunked(4).joinToString(" ")

    private class SingleKeyManager(
        private val alias: String,
        private val key: PrivateKey,
        private val chain: Array<X509Certificate>,
    ) : X509ExtendedKeyManager() {
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) =
            arrayOf(alias)

        override fun chooseServerAlias(
            keyType: String?,
            issuers: Array<out Principal>?,
            socket: Socket?,
        ) = alias

        override fun chooseEngineServerAlias(
            keyType: String?,
            issuers: Array<out Principal>?,
            engine: SSLEngine?,
        ) = alias

        override fun getCertificateChain(alias: String?) = chain

        override fun getPrivateKey(alias: String?) = key

        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = null

        override fun chooseClientAlias(
            keyType: Array<out String>?,
            issuers: Array<out Principal>?,
            socket: Socket?,
        ) = null
    }

    /**
     * Accepts any well-formed, valid, self-signed P-256 client certificate; pairing decides the
     * rest. Deliberately not Android's default trust manager: there is no CA here, only keys pinned
     * at pairing, checked by [Server] right after the handshake.
     */
    @SuppressLint("CustomX509TrustManager")
    private object ClientCertPolicy : X509ExtendedTrustManager() {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            check(chain)

        override fun checkClientTrusted(
            chain: Array<out X509Certificate>?,
            authType: String?,
            socket: Socket?,
        ) = check(chain)

        override fun checkClientTrusted(
            chain: Array<out X509Certificate>?,
            authType: String?,
            engine: SSLEngine?,
        ) = check(chain)

        private fun check(chain: Array<out X509Certificate>?) {
            val c =
                chain?.singleOrNull()
                    ?: throw CertificateException("expected one self-signed certificate")
            c.checkValidity()
            val key =
                c.publicKey as? ECPublicKey
                    ?: throw CertificateException("client key must be EC P-256")
            if (key.params.curve.field.fieldSize != 256)
                throw CertificateException("client key must be P-256")
            try {
                c.verify(key)
            } catch (e: Exception) {
                throw CertificateException("client certificate is not self-signed", e)
            }
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            throw CertificateException("server role only")

        override fun checkServerTrusted(
            chain: Array<out X509Certificate>?,
            authType: String?,
            socket: Socket?,
        ) = throw CertificateException("server role only")

        override fun checkServerTrusted(
            chain: Array<out X509Certificate>?,
            authType: String?,
            engine: SSLEngine?,
        ) = throw CertificateException("server role only")

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
