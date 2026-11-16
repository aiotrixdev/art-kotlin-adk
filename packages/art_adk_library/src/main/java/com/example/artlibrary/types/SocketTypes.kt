package com.example.artlibrary.types

/**
 * A client transport failure, not confirmation that a server execution failed.
 */
class ConnectionError(
    message: String,
    val code: String,
    val status: Int? = null
) : Exception(message) {

    val errorName: String = "ConnectionError"
}

/**
 * WebSocket handler interface.
 */
interface IWebsocketHandler {

    suspend fun waitForConnection()

    suspend fun ensureAuthorization()

    fun sendMessage(message: String)

    fun getConnection(): ConnectionDetail?

    suspend fun encrypt(
        data: String,
        receipientPublicKey: String
    ): String

    suspend fun decrypt(
        encryptedHash: String,
        senderPublicKey: String
    ): String

    suspend fun pushForSecureLine(
        event: String,
        data: Any?,
        listen: Boolean
    ): Any?

    fun removeSubscription(channel: String)

    fun onConnectionFailure(
        listener: (ConnectionError) -> Unit
    )

    fun offConnectionFailure(
        listener: (ConnectionError) -> Unit
    )
}

/**
 * Connection details.
 */
data class ConnectionDetail(
    val ConnectionId: String,
    val InstanceId: String,
    val TenantName: String,
    val Environment: String,
    val ProjectKey: String
)

/**
 * Push configuration.
 */
data class PushConfig(
    val ExecutionOrigin: String? = null,
    val to: List<String>? = null,
    val ThreadID: String? = null,
    val FileMeta: List<MediaRef>? = null
)

/**
 * Media reference.
 */
data class MediaRef(
    val id: String,
    val name: String,
    val content_type: String
)

/**
 * Secure ADK user interface.
 */
data class AdkSecureUserInterface(
    val Username: String,
    val FirstName: String,
    val LastName: String
)

/**
 * HTTP API call options.
 */
data class CallApiProps(
    val method: String? = null,
    val payload: Any? = null,
    val queryParams: Map<String, String>? = null,
    val headers: Map<String, String>? = null,
    val signal: AbortSignal? = null,
    val timeoutMs: Long? = null,
    val baseUrl: String? = null
)

/**
 * Long-poll response.
 */
data class LongPollResponse(
    val connection_id: String,
    val messages: List<Any?>
)

/**
 * Long-poll options.
 */
data class LongPollOptions(
    val endpoint: String,
    val initialConnectionId: String? = null,
    val getAuthHeaders: suspend () -> Map<String, String>,
    val onMessages: (List<Any?>) -> Unit,
    val onError: ((Throwable) -> Unit)? = null,
    val retryDelayMs: Long? = null,
    val emptyPollDelayMs: Long? = null,
    val maxEmptyPollDelayMs: Long? = null
)

/**
 * Configuration type.
 */
typealias ConfigType = String

/**
 * Upload options.
 */
data class UploadOptions(
    val configId: String? = null,
    val configType: ConfigType? = null,
    val threadId: String? = null,
    val filename: String? = null,
    val ttlSeconds: Long? = null,
    val allowedMimeTypes: List<String>? = null,
    val onProgress: ((Float) -> Unit)? = null,
    val signal: AbortSignal? = null,
    val timeoutMs: Long? = 60000
)

/**
 * File reference.
 */
data class FileRef(
    val fileId: String,
    val name: String,
    val readUrl: String,
    val size: Long,
    val contentType: String
)

/**
 * Upload error.
 */
class UploadError(
    message: String,
    val step: String,
    val status: Int? = null,
    cause: Throwable? = null
) : Exception(message, cause) {

    val errorName: String = "UploadError"
}

/**
 * File listing options.
 */
data class ListOptions(
    val configId: String? = null,
    val configType: ConfigType? = null,
    val threadId: String? = null,
    val page: Int? = null,
    val limit: Int? = null,
    val signal: AbortSignal? = null
)

/**
 * File access options.
 */
data class FileAccessOptions(
    val threadId: String,
    val signal: AbortSignal? = null,
    val timeoutMs: Long? = null
)

/**
 * Storage file information.
 */
data class StorageFile(
    val fileId: String,
    val name: String,
    val configType: String,
    val configId: String,
    val size: Long,
    val contentType: String,
    val status: String,
    val createdAt: String,
    val expiresAt: String? = null,
    val readUrl: String? = null
)

/**
 * Profile update data.
 */
data class UpdateProfileData(
    val firstName: String? = null,
    val lastName: String? = null,
    val email: String? = null,
    val attributes: Map<String, Any?>? = null
)

/**
 * Connector field.
 */
data class ConnectorField(
    val key: String,
    val label: String,
    val type: String,
    val placeholder: String? = null,
    val required: Boolean? = null,
    val description: String? = null
)

/**
 * Connector profile.
 */
data class ConnectorProfile(
    val connectorId: String,
    val provider: String,
    val allowedFields: List<ConnectorField>,
    val metadata: Map<String, String>
)

/**
 * Placeholder for external cancellation.
 *
 * Connect this to your coroutine Job or HTTP client's
 * cancellation mechanism when implementing cancellation.
 */
interface AbortSignal