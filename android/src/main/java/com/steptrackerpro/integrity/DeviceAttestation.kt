package com.steptrackerpro.integrity

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * A per-install signing key in the Android Keystore, with a hardware
 * attestation of it for the server.
 *
 * The flow a server builds on:
 *
 *  1. The server hands the app a random challenge.
 *  2. [attest] generates a fresh EC P-256 key bound to that challenge and
 *     returns its certificate chain. The server verifies the chain up to
 *     Google's attestation root, checks the challenge, and reads the
 *     attestation extension: security level, verified boot state, whether
 *     the bootloader is locked, which app the key belongs to. It then stores
 *     the public key against the install.
 *  3. From then on [sign] signs verification snapshots and remote uploads
 *     with that key, and the server checks each signature against the stored
 *     key. A rooted phone, an edited database or a replayed body fails there
 *     rather than on the device, where it could be patched out.
 *
 * The private key never leaves secure hardware where the device has it. A
 * device that refuses attestation - some emulators, some old HALs - still
 * gets a key, reported with `attested: false`, and the server decides what
 * that is worth.
 */
object DeviceAttestation {

    private const val KEYSTORE = "AndroidKeyStore"
    const val ALIAS = "steptrackerpro.integrity"
    const val ALGORITHM = "SHA256withECDSA"
    private const val PREFS = "StepTrackerProIntegrity"
    private const val KEY_ATTESTED = "key_attested"
    private const val KEY_CREATED_AT = "key_created_at"

    /** The Keystore caps an attestation challenge at 128 bytes. */
    const val MAX_CHALLENGE_BYTES = 128

    /**
     * Replaces the key with one bound to [challenge] and describes it. Every
     * earlier signature was made with the old key, which is gone afterwards;
     * a server stores the new public key when it accepts the chain.
     */
    @Synchronized
    fun attest(context: Context, challenge: ByteArray): Map<String, Any?> {
        require(challenge.isNotEmpty() && challenge.size <= MAX_CHALLENGE_BYTES) {
            "challenge must be 1-$MAX_CHALLENGE_BYTES bytes"
        }
        val store = keyStore()
        if (store.containsAlias(ALIAS)) store.deleteEntry(ALIAS)
        val attested = runCatching { generate(challenge); true }.getOrElse {
            // Attestation is optional hardware; a key without it is still
            // better than no key, and the server is told which it got.
            if (store.containsAlias(ALIAS)) store.deleteEntry(ALIAS)
            generate(null)
            false
        }
        prefs(context).edit()
            .putBoolean(KEY_ATTESTED, attested)
            .putLong(KEY_CREATED_AT, System.currentTimeMillis())
            .apply()
        return describe(context) ?: emptyMap()
    }

    /** Whether a key exists yet - [attest] or a first [sign] made one. */
    fun hasKey(): Boolean = runCatching { keyStore().containsAlias(ALIAS) }.getOrDefault(false)

    /** The current key's id, chain and security level, or null when there is none. */
    fun describe(context: Context): Map<String, Any?>? {
        val store = runCatching { keyStore() }.getOrNull() ?: return null
        if (!store.containsAlias(ALIAS)) return null
        val chain = store.getCertificateChain(ALIAS)?.toList().orEmpty()
        val publicKey = chain.firstOrNull()?.publicKey ?: return null
        val encoder = Base64.getEncoder()
        return mapOf(
            "keyId" to keyId(publicKey.encoded),
            "algorithm" to ALGORITHM,
            "publicKey" to encoder.encodeToString(publicKey.encoded),
            "certificateChain" to chain.map { encoder.encodeToString(it.encoded) },
            "attested" to prefs(context).getBoolean(KEY_ATTESTED, false),
            "securityLevel" to securityLevel(store),
            "createdAt" to prefs(context).getLong(KEY_CREATED_AT, 0L)
        )
    }

    /**
     * Signs [payload] with the install's key, creating an unattested one if
     * [attest] has never run. Returns `keyId`, `algorithm`, `value` (base64
     * DER ECDSA) and `attested`.
     */
    @Synchronized
    fun sign(context: Context, payload: ByteArray): Map<String, Any?> {
        val store = keyStore()
        if (!store.containsAlias(ALIAS)) {
            generate(null)
            prefs(context).edit()
                .putBoolean(KEY_ATTESTED, false)
                .putLong(KEY_CREATED_AT, System.currentTimeMillis())
                .apply()
        }
        val privateKey = store.getKey(ALIAS, null) as PrivateKey
        val publicKey = store.getCertificate(ALIAS).publicKey
        val signature = Signature.getInstance(ALGORITHM).run {
            initSign(privateKey)
            update(payload)
            sign()
        }
        return mapOf(
            "keyId" to keyId(publicKey.encoded),
            "algorithm" to ALGORITHM,
            "value" to Base64.getEncoder().encodeToString(signature),
            "attested" to prefs(context).getBoolean(KEY_ATTESTED, false)
        )
    }

    /** Hex SHA-256 of the X.509 SubjectPublicKeyInfo, the id a server files the key under. */
    fun keyId(encodedPublicKey: ByteArray): String = sha256Hex(encodedPublicKey)

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun generate(challenge: ByteArray?) {
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .apply { if (challenge != null) setAttestationChallenge(challenge) }
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE).run {
            initialize(spec)
            generateKeyPair()
        }
    }

    private fun securityLevel(store: KeyStore): String = runCatching {
        val key = store.getKey(ALIAS, null) as PrivateKey
        val info = KeyFactory.getInstance(key.algorithm, KEYSTORE).getKeySpec(key, KeyInfo::class.java)
        if (Build.VERSION.SDK_INT >= 31) {
            when (info.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> "strongbox"
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "tee"
                KeyProperties.SECURITY_LEVEL_SOFTWARE -> "software"
                else -> "unknown"
            }
        } else {
            @Suppress("DEPRECATION")
            if (info.isInsideSecureHardware) "tee" else "software"
        }
    }.getOrDefault("unknown")

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
