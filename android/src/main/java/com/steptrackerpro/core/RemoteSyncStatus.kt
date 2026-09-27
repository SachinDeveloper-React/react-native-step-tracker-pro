package com.steptrackerpro.core

/**
 * What remote uploads last did, as [StepStateStore] keeps it. Uploads run in
 * a WorkManager job, usually with no JS alive to hear `syncAuthFailed`; this
 * is what the app reads instead when it next comes up, through
 * `getSyncStatus()`.
 */
data class RemoteSyncStatus(
    /** Epoch ms the last upload began, or was refused before sending. 0 when never. */
    val lastAttemptAt: Long = 0L,
    /** Epoch ms of the last accepted upload. 0 when never. */
    val lastSuccessAt: Long = 0L,
    /** Failed attempts since the last success. */
    val consecutiveFailures: Int = 0,
    val lastFailure: Failure? = null,
    /**
     * Epoch ms the credentials last changed: the URL, the headers or the
     * auth mode through config, or the key through `attestDevice()`.
     */
    val credentialsChangedAt: Long = 0L
) {
    data class Failure(
        /** When the refused attempt began - its credentials were read then. */
        val at: Long,
        /** One of the reason constants below. */
        val reason: String,
        /** The HTTP status, when the server answered. */
        val status: Int?,
        val message: String,
        /** Whether the same attempt could succeed later on its own. */
        val retryable: Boolean
    ) {
        fun toMap(): Map<String, Any?> = mapOf(
            "at" to at,
            "reason" to reason,
            "status" to status,
            "message" to message,
            "retryable" to retryable
        )
    }

    /**
     * The last attempt was refused over its credentials, and nothing has
     * fixed that since: no new headers, URL, auth mode or key, and no
     * upload accepted. Every later scheduled upload would be refused the
     * same way, so this is what an app acts on at launch.
     */
    val authFailed: Boolean
        get() {
            val failure = lastFailure ?: return false
            return failure.reason in AUTH_REASONS &&
                failure.at >= credentialsChangedAt &&
                failure.at > lastSuccessAt
        }

    fun toMap(configured: Boolean, pendingRecords: Int): Map<String, Any?> = mapOf(
        "configured" to configured,
        "pendingRecords" to pendingRecords,
        "lastAttemptAt" to lastAttemptAt,
        "lastSuccessAt" to lastSuccessAt,
        "consecutiveFailures" to consecutiveFailures,
        "lastFailure" to lastFailure?.toMap(),
        "authFailed" to authFailed
    )

    companion object {
        /** 401. */
        const val UNAUTHORIZED = "unauthorized"

        /** 403. */
        const val FORBIDDEN = "forbidden"

        /** `remoteSyncAuth: 'signature'` with no key from `attestDevice()`. */
        const val NO_KEY = "no_key"

        /** An `http://` URL without `remoteSyncAllowHttp`. */
        const val INSECURE_URL = "insecure_url"

        /** Any other non-2xx answer. Retried. */
        const val HTTP_ERROR = "http_error"

        /** No answer at all: a timeout, a refused connection, TLS. Retried. */
        const val NO_RESPONSE = "no_response"

        val AUTH_REASONS: Set<String> = setOf(UNAUTHORIZED, FORBIDDEN, NO_KEY)
    }
}
