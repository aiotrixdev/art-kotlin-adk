package com.example.artlibrary.types

// TS union "media" | "knowledge_base" → enum (String is the looser alternative)
enum class ConfigType(val value: String) {
    MEDIA("media"),
    KNOWLEDGE_BASE("knowledge_base"),
}

data class ConnectionDetail(
    val connectionId: String,   // JSON: ConnectionId
    val instanceId: String,     // JSON: InstanceId
    val tenantName: String,     // JSON: TenantName
    val environment: String,    // JSON: Environment
    val projectKey: String,     // JSON: ProjectKey
)

interface IWebsocketHandler {
    // Signatures match the Socket implementation (JS Promise-returning members
    // map to `suspend`; `data` is a non-null Any, not string).
    suspend fun wait()
    fun sendMessage(message: String)
    fun getConnection(): ConnectionDetail?
    suspend fun encrypt(data: Any, recipientPublicKey: String): String
    suspend fun decrypt(encryptedHash: String, senderPublicKey: String): String
    suspend fun pushForSecureLine(event: String, data: Any, listen: Boolean): Any?
    suspend fun removeSubscription(channel: String)
}

data class PushConfig(
    val to: List<String>? = null,
    val instanceId: String? = null,   // used by OrchestratorThread.push()
    val threadId: String? = null,
)

data class AdkSecureUserInterface(
    val username: String,
    val firstName: String,
    val lastName: String,
)

data class CallApiProps(
    val method: String? = null,
    val payload: Any? = null,
    val queryParams: Map<String, String>? = null,
    val headers: Map<String, String>? = null,
    // signal?: AbortSignal → coroutine cancellation (no field)
    val timeoutMs: Long? = null,
    val baseUrl: String? = null,
)

data class LongPollResponse(
    val connectionId: String,     // JSON: connection_id
    val messages: List<Any?>,
)

data class LongPollOptions(
    val endpoint: String,
    val initialConnectionId: String? = null,
    val getAuthHeaders: suspend () -> Map<String, String>,       // () => Promise<Record<string,string>>
    val onMessages: (List<Any?>) -> Unit,
    val onError: ((Any?) -> Unit)? = null,
    val retryDelayMs: Long? = null,
    val emptyPollDelayMs: Long? = null,
    val maxEmptyPollDelayMs: Long? = null,
)

data class UploadOptions(
    val configId: String? = null,               // default: ProjectKey
    val configType: ConfigType? = null,         // default: MEDIA
    val filename: String? = null,
    val scopes: List<String>? = null,
    val ttlSeconds: Long? = null,
    val onProgress: ((Float) -> Unit)? = null,  // 0..1
    // signal?: AbortSignal → coroutine cancellation (no field)
    val timeoutMs: Long? = null,                // default 60_000
)

data class FileRef(
    val fileId: String,
    val name: String,
    val readUrl: String,
    val size: Long,
    val contentType: String,
)

class UploadError(
    message: String,
    val step: Step,
    val status: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {
    enum class Step(val value: String) {
        VALIDATE("validate"),
        SIGNED_URL("signed-url"),
        PUT("put"),
        CONFIRM("confirm"),
        LIST("list"),
        GET("get"),
        DELETE("delete"),
    }
}

data class ListOptions(
    val configId: String? = null,
    val configType: ConfigType? = null,
    val page: Int? = null,
    val limit: Int? = null,
    // signal?: AbortSignal → coroutine cancellation (no field)
)

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
    val readUrl: String? = null,   // present from getFile(), absent from listFiles()
)