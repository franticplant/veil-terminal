package io.github.franticplant.veil.frontend

import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import io.github.franticplant.veil.engine.LogRing
import io.github.franticplant.veil.engine.LoginSigner
import io.github.franticplant.veil.engine.SshUserauth
import io.github.franticplant.veil.engine.proto.SignRequest
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * SSH keys in Android Keystore: ECDSA P-256 (StrongBox has no Ed25519), in StrongBox when the
 * device has it, else the TEE. Private keys never leave Keystore; this class only asks it to sign,
 * and only logins ([SshUserauth.check]).
 *
 * This is the v0.x stand-in for the design's Vault: the same [LoginSigner] boundary, implemented in
 * the app's process for now. Every new key requires strong-biometric authentication for each
 * signing operation; the private key never leaves Keystore.
 */
class SshKeys(private val log: LogRing) : LoginSigner {
    /** Where a key lives. */
    enum class Security {
        STRONGBOX,
        TEE,
        SOFTWARE,
        UNKNOWN,
    }

    /** Where a new key should be generated. */
    enum class Where {
        /** StrongBox when the device has it, otherwise the TEE. */
        AUTO,
        /** Require the secure element; fail if this device has none. */
        STRONGBOX,
        /** The TEE (or software-backed Keystore) only. */
        TEE,
    }

    data class Key(
        val name: String,
        val publicKey: String,
        val fingerprint: String,
        val security: Security,
        /** True only for keys generated with Android's auth-per-use policy. */
        val authPerUse: Boolean,
    )

    private val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val _keys = MutableStateFlow(load())

    val keys: StateFlow<List<Key>> = _keys

    /**
     * Creates a key in [where]. With [whileUnlocked], Keystore itself refuses to use the key while
     * the phone is locked.
     */
    fun create(
        name: String,
        where: Where = Where.AUTO,
        whileUnlocked: Boolean = true,
    ): Key {
        require(NAME.matches(name)) { "a key name is 1-40 letters, digits, '.', '_' or '-'" }
        require(!ks.containsAlias(alias(name))) { "a key named '$name' exists" }
        fun generate(sb: Boolean) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
                .apply {
                    initialize(
                        KeyGenParameterSpec.Builder(alias(name), KeyProperties.PURPOSE_SIGN)
                            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                            .setDigests(KeyProperties.DIGEST_SHA256)
                            .setIsStrongBoxBacked(sb)
                            .setUnlockedDeviceRequired(whileUnlocked)
                            .setUserAuthenticationRequired(true)
                            // Every signing operation needs a fresh strong-biometric
                            // authorization. This is Keystore policy, not an app check.
                            .setUserAuthenticationParameters(
                                0,
                                KeyProperties.AUTH_BIOMETRIC_STRONG,
                            )
                            .setInvalidatedByBiometricEnrollment(true)
                            .build()
                    )
                }
                .generateKeyPair()
        }
        when (where) {
            Where.AUTO -> {
                try {
                    generate(true)
                } catch (e: StrongBoxUnavailableException) {
                    log.info("keys", "no StrongBox on this device; '$name' lives in the TEE")
                    generate(false)
                }
            }
            Where.STRONGBOX ->
                try {
                    generate(true)
                } catch (e: StrongBoxUnavailableException) {
                    log.warn("keys", "StrongBox was requested but is unavailable: ${e.message}")
                    throw IllegalArgumentException(
                        "StrongBox is not available on this device",
                        e,
                    )
                }
            Where.TEE -> generate(false)
        }
        refresh()
        val k = _keys.value.first { it.name == name }
        log.info("keys", "created '$name' (${k.security}, ${k.fingerprint})")
        return k
    }

    fun delete(name: String) {
        ks.deleteEntry(alias(name))
        refresh()
        log.info("keys", "deleted '$name'")
    }

    override fun publicKey(keyId: String): String? =
        _keys.value.firstOrNull { it.name == keyId }?.publicKey

    override fun sign(keyId: String, user: String, request: SignRequest): ByteArray? {
        val key = _keys.value.firstOrNull { it.name == keyId } ?: return null
        val blob = SshUserauth.blobOf(key.publicKey)
        // Sign a login for this user with this key, and nothing else.
        if (!SshUserauth.blobOf(request.publicKey).contentEquals(blob)) {
            log.warn("keys", "sign request for another key; refused")
            return null
        }
        if (!SshUserauth.check(request.data.toByteArray(), user, blob)) {
            log.warn("keys", "sign request is not a login for '$user' with '$keyId'; refused")
            return null
        }
        if (!key.authPerUse) {
            log.warn(
                "keys",
                "key '$keyId' predates per-signature biometric policy; recreate it",
            )
            return null
        }
        val private = ks.getKey(alias(keyId), null) as? PrivateKey ?: return null
        val signature =
            try {
                Signature.getInstance("SHA256withECDSA").apply { initSign(private) }
            } catch (e: java.security.GeneralSecurityException) {
                log.warn("keys", "cannot prepare '$keyId' for signing: ${e.message}")
                return null
            }
        val authorized = authorizeSignature(signature, keyId, user, request) ?: return null
        val der =
            try {
                authorized.update(request.data.toByteArray())
                authorized.sign()
            } catch (e: java.security.GeneralSecurityException) {
                log.warn("keys", "Keystore refused to use '$keyId': ${e.message}")
                return null
            }
        return SshUserauth.ecdsaSignatureBlob(der)
    }

    /**
     * Binds a biometric prompt to the exact Signature operation. Authentication succeeds only for
     * the CryptoObject passed here; the returned Signature is the one that may sign the
     * already-validated login request.
     */
    private fun authorizeSignature(
        signature: Signature,
        keyId: String,
        user: String,
        request: SignRequest,
    ): Signature? {
        val activity = MainActivity.current
        if (activity == null) {
            log.warn("keys", "no foreground activity for biometric signing")
            return null
        }
        val latch = CountDownLatch(1)
        val result = AtomicReference<Signature?>(null)
        val failure = AtomicReference<String?>(null)
        val cancellation = CancellationSignal()
        val callback =
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    authenticationResult: BiometricPrompt.AuthenticationResult
                ) {
                    val authenticated = authenticationResult.cryptoObject?.signature
                    if (authenticated == null) {
                        failure.set("biometric result carried no signature")
                    } else {
                        result.set(authenticated)
                    }
                    latch.countDown()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    failure.set("biometric error $errorCode: $errString")
                    latch.countDown()
                }
            }
        activity.runOnUiThread {
            try {
                BiometricPrompt.Builder(activity)
                    .setTitle("Authorize SSH login")
                    .setSubtitle("$user@${request.host}:${request.port} \u00b7 $keyId")
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                    .setNegativeButton("Cancel", activity.mainExecutor) { _, _ ->
                        failure.set("cancelled")
                        latch.countDown()
                    }
                    .build()
                    .authenticate(
                        BiometricPrompt.CryptoObject(signature),
                        cancellation,
                        activity.mainExecutor,
                        callback,
                    )
            } catch (e: Exception) {
                failure.set(e.message ?: e.javaClass.simpleName)
                latch.countDown()
            }
        }
        if (!latch.await(60, TimeUnit.SECONDS)) {
            failure.set("biometric prompt timed out")
            cancellation.cancel()
        }
        val authenticated = result.get()
        if (authenticated == null) {
            log.warn("keys", "signing refused: ${failure.get() ?: "no authorization"}")
        }
        return authenticated
    }

    private fun refresh() {
        _keys.value = load()
    }

    private fun load(): List<Key> =
        ks.aliases()
            .toList()
            .filter { it.startsWith(PREFIX) }
            .sorted()
            .mapNotNull { a ->
                val pub = ks.getCertificate(a)?.publicKey as? ECPublicKey ?: return@mapNotNull null
                val blob = SshUserauth.ecdsaP256Blob(pub.w.affineX, pub.w.affineY)
                val name = a.removePrefix(PREFIX)
                Key(
                    name,
                    SshUserauth.openssh(blob, "veil-$name"),
                    fingerprint(blob),
                    security(a),
                    authPerUse(a),
                )
            }

    private fun authPerUse(alias: String): Boolean =
        try {
            val key = ks.getKey(alias, null) as PrivateKey
            val info =
                KeyFactory.getInstance(key.algorithm, "AndroidKeyStore")
                    .getKeySpec(key, KeyInfo::class.java)
            info.isUserAuthenticationRequired
        } catch (_: Exception) {
            false
        }

    private fun security(alias: String): Security =
        try {
            val key = ks.getKey(alias, null) as PrivateKey
            val info =
                KeyFactory.getInstance(key.algorithm, "AndroidKeyStore")
                    .getKeySpec(key, KeyInfo::class.java)
            when (info.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> Security.STRONGBOX
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> Security.TEE
                KeyProperties.SECURITY_LEVEL_SOFTWARE -> Security.SOFTWARE
                else -> Security.UNKNOWN
            }
        } catch (e: Exception) {
            Security.UNKNOWN
        }

    companion object {
        private const val PREFIX = "veil-ssh:"
        private val NAME = Regex("[A-Za-z0-9._-]{1,40}")

        private fun alias(name: String) = PREFIX + name

        /** OpenSSH's fingerprint: SHA256 of the key blob, base64 without padding. */
        fun fingerprint(blob: ByteArray): String =
            "SHA256:" +
                Base64.getEncoder()
                    .withoutPadding()
                    .encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))
    }
}
