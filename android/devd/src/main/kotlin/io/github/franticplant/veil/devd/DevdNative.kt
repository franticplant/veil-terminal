package io.github.franticplant.veil.devd

/**
 * devd's network side in Rust (android/devd-native, libveil_devd.so): listeners, TLS 1.3 with
 * client certificates (rustls) and framing. It calls back into [Callback] to sign handshakes with
 * the Keystore key and to answer each request. Java's TLS stack (Conscrypt) is not used: it left a
 * thread behind per handshake.
 */
internal object DevdNative {
    init {
        System.loadLibrary("veil_devd")
    }

    interface Callback {
        /** SHA256withECDSA over [data] with devd's Keystore key, DER; null to refuse. */
        fun sign(data: ByteArray): ByteArray?

        /** Answers one request from the client whose certificate is [clientCertHex]. */
        fun request(clientCertHex: String, local: String, peer: String, json: String): String
    }

    @JvmStatic external fun start(callback: Callback, certDer: ByteArray): Long

    /** Listens on [address]:[port]; returns null, or why it cannot. */
    @JvmStatic external fun bind(handle: Long, address: String, port: Int): String?

    @JvmStatic external fun unbind(handle: Long, address: String, port: Int)

    @JvmStatic external fun connections(handle: Long): Int

    @JvmStatic external fun stop(handle: Long)
}
