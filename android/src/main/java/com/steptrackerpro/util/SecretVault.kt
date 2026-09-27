package com.steptrackerpro.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Seals small secrets - the remote endpoint's auth headers - with an AES-256
 * key that lives in the Android Keystore and never leaves it. What reaches
 * SharedPreferences is `v1:<iv>:<ciphertext>`, useless without the key, so a
 * backup extract or another app reading the file gets nothing it can replay.
 *
 * The key is not part of a backup. A restore onto a new phone brings the
 * sealed blob but not the key, [open] returns null, and the next upload goes
 * out without the headers - the endpoint's 401 raises `syncAuthFailed`, and
 * the app sets fresh ones.
 */
object SecretVault {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "steptrackerpro.secrets"
    private const val PREFIX = "v1:"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    /** The sealed form of [plain], or null when the Keystore cannot be used on this device. */
    fun seal(plain: String): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val encoder = Base64.getEncoder()
        PREFIX + encoder.encodeToString(cipher.iv) + ":" + encoder.encodeToString(sealed)
    }.getOrNull()

    /** The plain text behind [sealed], or null when it cannot be opened here. */
    fun open(sealed: String): String? = runCatching {
        if (!sealed.startsWith(PREFIX)) return null
        val parts = sealed.removePrefix(PREFIX).split(":")
        if (parts.size != 2) return null
        val decoder = Base64.getDecoder()
        val key = key(create = false) ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, decoder.decode(parts[0])))
        String(cipher.doFinal(decoder.decode(parts[1])), Charsets.UTF_8)
    }.getOrNull()

    @Synchronized
    private fun key(create: Boolean): SecretKey? {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        if (!create) return null
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generateKey()
        }
    }
}
