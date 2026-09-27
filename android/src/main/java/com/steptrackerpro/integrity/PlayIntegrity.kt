package com.steptrackerpro.integrity

import android.content.Context
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Play Integrity's standard requests, when the host app ships
 * `com.google.android.play:integrity`. Compile-only here, like Activity
 * Recognition: an app that wants it adds the dependency, and one that does
 * not never loads a Play class - [isAvailable] probes before anything in
 * [Play] is reached.
 *
 * Key attestation says which device and which signed app hold the key.
 * Play Integrity adds what only Google knows: that this exact binary is the
 * one Play distributed, whether the install came from Play, and Google's own
 * device verdict. The token is opaque here; the server decrypts and checks
 * it with Google, and checks that its `requestHash` is the payload hash it
 * expected - a verification snapshot's `signature.payloadSha256`.
 */
object PlayIntegrityBridge {

    /** Play Integrity caps a standard request's hash at 500 characters. */
    const val MAX_REQUEST_HASH = 500

    fun isAvailable(): Boolean = available

    private val available: Boolean by lazy { runCatching { Play.probe() }.getOrDefault(false) }

    /** A Play Integrity failure, with Play's own error code when it gave one. */
    class Failure(message: String, val errorCode: Int?, cause: Throwable?) : Exception(message, cause)

    suspend fun requestToken(context: Context, cloudProjectNumber: Long, requestHash: String): String =
        try {
            Play.request(context.applicationContext, cloudProjectNumber, requestHash)
        } catch (failure: Failure) {
            throw failure
        } catch (error: Exception) {
            throw Failure(error.message ?: "Play Integrity request failed", Play.errorCodeOf(error), error)
        }

    /** Every Play reference lives here, reached only after [isAvailable]. */
    internal object Play {

        fun probe(): Boolean =
            com.google.android.play.core.integrity.IntegrityManagerFactory::class.java.name.isNotEmpty()

        private val lock = Mutex()

        /**
         * Preparing a provider is the slow part - it warms up Play's side -
         * so one is kept per cloud project and reused until Play says it has
         * gone stale.
         */
        private val providers =
            HashMap<Long, com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenProvider>()

        suspend fun request(context: Context, cloudProjectNumber: Long, requestHash: String): String {
            val first = provider(context, cloudProjectNumber)
            return try {
                token(first, requestHash)
            } catch (error: Exception) {
                if (errorCodeOf(error) != PROVIDER_INVALID) throw error
                // Stale provider: prepare a fresh one and try exactly once more.
                lock.withLock { providers.remove(cloudProjectNumber) }
                token(provider(context, cloudProjectNumber), requestHash)
            }
        }

        private suspend fun token(
            provider: com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenProvider,
            requestHash: String
        ): String {
            val request = com.google.android.play.core.integrity.StandardIntegrityManager
                .StandardIntegrityTokenRequest.builder()
                .setRequestHash(requestHash)
                .build()
            return provider.request(request).await().token()
        }

        private suspend fun provider(
            context: Context,
            cloudProjectNumber: Long
        ): com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenProvider =
            lock.withLock {
                providers[cloudProjectNumber] ?: run {
                    val manager = com.google.android.play.core.integrity.IntegrityManagerFactory.createStandard(context)
                    val prepare = com.google.android.play.core.integrity.StandardIntegrityManager
                        .PrepareIntegrityTokenRequest.builder()
                        .setCloudProjectNumber(cloudProjectNumber)
                        .build()
                    manager.prepareIntegrityToken(prepare).await().also { providers[cloudProjectNumber] = it }
                }
            }

        fun errorCodeOf(error: Throwable): Int? = runCatching {
            (error as? com.google.android.play.core.integrity.StandardIntegrityException)?.errorCode
        }.getOrNull()

        /** `StandardIntegrityErrorCode.INTEGRITY_TOKEN_PROVIDER_INVALID`. */
        private const val PROVIDER_INVALID = -19

        private suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T =
            suspendCancellableCoroutine { continuation ->
                addOnSuccessListener { continuation.resume(it) }
                addOnFailureListener { continuation.resumeWithException(it) }
                addOnCanceledListener { continuation.cancel() }
            }
    }
}
