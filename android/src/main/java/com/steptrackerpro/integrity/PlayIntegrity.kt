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
    class Failure(message: String, val errorCode: Int?, cause: Throwable?) : Exception(message, cause) {
        /** Play's name for [errorCode], or null. */
        val errorName: String? get() = errorCode?.let { ERROR_NAMES[it] ?: "UNKNOWN" }

        /** Whether the same call can succeed later without anything changing. See [isRetryable]. */
        val retryable: Boolean get() = isRetryable(errorCode)
    }

    /**
     * Prepares - or reuses - the token provider for [cloudProjectNumber], so
     * the first [requestToken] does not pay for it. Preparing warms Play's
     * side up and can take seconds; call it at app start or before the
     * screen that will need a token.
     */
    suspend fun prepare(context: Context, cloudProjectNumber: Long) {
        wrap("Play Integrity prepare failed") { Play.prepare(context.applicationContext, cloudProjectNumber) }
    }

    suspend fun requestToken(context: Context, cloudProjectNumber: Long, requestHash: String): String =
        wrap("Play Integrity request failed") {
            Play.request(context.applicationContext, cloudProjectNumber, requestHash)
        }

    private suspend fun <T> wrap(fallbackMessage: String, block: suspend () -> T): T =
        try {
            block()
        } catch (failure: Failure) {
            throw failure
        } catch (error: Exception) {
            throw Failure(error.message ?: fallbackMessage, Play.errorCodeOf(error), error)
        }

    /**
     * Play's `StandardIntegrityErrorCode`s that are temporary: retry with
     * exponential backoff and the same call can succeed. Everything else
     * needs something to change first - the user updates the Play Store or
     * Play services, the app is installed from Play, or the developer fixes
     * the cloud project number or the hash. An unknown code or no code at
     * all is treated as permanent, so a caller never retries blindly.
     * `INTEGRITY_TOKEN_PROVIDER_INVALID` is retried once here already, with
     * a freshly prepared provider; seeing it means that retry failed too.
     */
    fun isRetryable(errorCode: Int?): Boolean = errorCode in RETRYABLE

    private val RETRYABLE = setOf(
        -3, // NETWORK_ERROR
        -8, // TOO_MANY_REQUESTS
        -9, // CANNOT_BIND_TO_SERVICE
        -12, // GOOGLE_SERVER_UNAVAILABLE
        -18, // CLIENT_TRANSIENT_ERROR
        -19, // INTEGRITY_TOKEN_PROVIDER_INVALID
        -100 // INTERNAL_ERROR
    )

    /** `StandardIntegrityErrorCode` names, as of Play Integrity 1.4. */
    private val ERROR_NAMES = mapOf(
        -1 to "API_NOT_AVAILABLE",
        -2 to "PLAY_STORE_NOT_FOUND",
        -3 to "NETWORK_ERROR",
        -5 to "APP_NOT_INSTALLED",
        -6 to "PLAY_SERVICES_NOT_FOUND",
        -7 to "APP_UID_MISMATCH",
        -8 to "TOO_MANY_REQUESTS",
        -9 to "CANNOT_BIND_TO_SERVICE",
        -12 to "GOOGLE_SERVER_UNAVAILABLE",
        -14 to "PLAY_STORE_VERSION_OUTDATED",
        -15 to "PLAY_SERVICES_VERSION_OUTDATED",
        -16 to "CLOUD_PROJECT_NUMBER_IS_INVALID",
        -17 to "REQUEST_HASH_TOO_LONG",
        -18 to "CLIENT_TRANSIENT_ERROR",
        -19 to "INTEGRITY_TOKEN_PROVIDER_INVALID",
        -100 to "INTERNAL_ERROR"
    )

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

        suspend fun prepare(context: Context, cloudProjectNumber: Long) {
            provider(context, cloudProjectNumber)
        }

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
