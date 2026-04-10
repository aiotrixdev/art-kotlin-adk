package com.example.artlibrary.types

/**
 * Contract for a transport handler used by subscriptions and interceptors.
 *
 * Marked as a pure interface (no defaults) so that any implementation must
 * be explicit about its capabilities — silent no-ops are how production
 * messages used to disappear in the original port.
 */
interface IWebsocketHandler {

    /** Suspends until a transport-level connection is established. */
    suspend fun wait()

    /** Sends a raw text frame on the underlying transport. */
    fun sendMessage(message: String)

    /** Returns the live connection details, or `null` when not connected. */
    fun getConnection(): ConnectionDetail?

    suspend fun encrypt(data: Any, recipientPublicKey: String): String

    suspend fun decrypt(encryptedHash: String, senderPublicKey: String): String

    /**
     * Sends a message on the secure control channel and optionally suspends
     * until the matching response arrives.
     */
    suspend fun pushForSecureLine(
        event: String,
        data: Any,
        listen: Boolean
    ): Any?

    /** Removes a subscription from the transport's internal registry. */
    suspend fun removeSubscription(channel: String)
}

data class ConnectionDetail(
    val connectionId: String,
    val instanceId: String,
    val tenantName: String,
    val environment: String,
    val projectKey: String
)

data class PushConfig(
    val to: List<String>,
    val instanceId: String
)

data class AdkSecureUserInterface(
    val username: String,
    val firstName: String,
    val lastName: String
)

data class CallApiProps(
    val method: String? = null,
    val payload: Any? = null,
    val queryParams: Map<String, String>? = null,
    val headers: Map<String, String>? = null
)
