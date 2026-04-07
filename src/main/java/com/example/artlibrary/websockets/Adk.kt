package com.example.artlibrary.websockets

import android.os.Build
import androidx.annotation.RequiresApi
import com.example.artlibrary.auth.Auth
import com.example.artlibrary.config.AdkLog
import com.example.artlibrary.config.Constant
import com.example.artlibrary.config.Events
import com.example.artlibrary.config.HttpClientProvider
import com.example.artlibrary.config.LimitCodes
import com.example.artlibrary.crypto.CryptoBox
import com.example.artlibrary.types.AdkConfig
import com.example.artlibrary.types.AuthenticationConfig
import com.example.artlibrary.types.CallApiProps
import com.example.artlibrary.types.ConnectionDetail
import com.example.artlibrary.types.KeyPairType
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private const val TAG = "Adk"

/**
 * Public entry-point for the ART SDK.
 *
 * Responsibilities:
 *  - Wraps the underlying [Socket] transport, hiding lifecycle details
 *    behind [connect], [pause], [resume], [disconnect] and [close].
 *  - Owns the SDK-wide reconnection policy with exponential backoff.
 *  - Exposes the secure messaging primitives (subscribe, intercept, push).
 *  - Provides a typed REST helper [call] that reuses the shared OkHttp
 *    instance.
 *
 * Lifecycle: instances must be released by calling [close]. After [close]
 * the instance is unusable.
 */
@RequiresApi(Build.VERSION_CODES.O)
class Adk(private val config: AdkConfig) : AutoCloseable {

    private val socket: Socket /*= Socket.create(::encrypt, ::decrypt)*/

    private val maxReconnectAttempts: Int = 5
    private val initialReconnectDelay: Long = 3_000L
    private val maxReconnectDelay: Long = 30_000L

    private var reconnectAttempts: Int = 0
    private var reconnectDelay: Long = initialReconnectDelay

    @Volatile private var isPaused: Boolean = false
    @Volatile var isConnectable: Boolean = false
        private set
    @Volatile private var isLimitExceeded: Boolean = false
    @Volatile private var isClosed: Boolean = false

    private var myKeyPair: KeyPairType? = null
    private val httpClient = HttpClientProvider.shared
    private val gson = Gson()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        require(config.uri.isNotBlank()) { "AdkConfig.uri is required" }
        val sanitized = config.uri.trim().removePrefix("https://").removePrefix("http://")
        Constant.BASE_URL = "https://$sanitized"
        Constant.WS_URL = "wss://$sanitized/v1/connect"
        Constant.SSE_URL = "https://$sanitized/v1/connect/sse"
        Constant.LPOLL = "https://$sanitized/v1/connect/longpoll"

        socket = Socket.create(::encrypt, ::decrypt)

        socket.on(Events.CONNECTION) { handleOnConnection(it as? ConnectionDetail) }
        socket.on(Events.CLOSE) { handleOnClose() }
        socket.on(Events.LIMIT_EXCEEDED) { detail ->
            val map = detail as? Map<*, *>
            val code = map?.get("code") as? String
            val error = map?.get("error") as? String
            val msg = if (code == LimitCodes.CONCURRENT_LIMIT_EXCEEDED) {
                "Concurrent connection limit reached: $error. Reconnection halted; call connect() to retry."
            } else {
                "Billing limit reached: $error. Reconnection permanently halted."
            }
            AdkLog.w(TAG, msg)
            isLimitExceeded = true
            isConnectable = false
        }
    }

    // ---------------- public API ----------------

    suspend fun connect() {
        checkNotClosed()
        isConnectable = true
        initiateSocketConnection()
    }

    suspend fun pause() {
        if (isPaused) return
        isPaused = true
        reconnectAttempts = maxReconnectAttempts
        socket.closeWebSocket()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun resume() {
        if (!isPaused) return
        isPaused = false
        reconnectAttempts = 0
        reconnectDelay = initialReconnectDelay
        socket.connectWebSocket()
    }

    fun disconnect() {
        isConnectable = false
        reconnectAttempts = maxReconnectAttempts
        scope.launch {
            socket.closeWebSocket(clearConnection = true)
        }
    }

    /** Releases every resource owned by this SDK instance. */
    override fun close() {
        if (isClosed) return
        isClosed = true
        isConnectable = false
        scope.launch {
            runCatching { socket.release() }
            scope.cancel()
            Auth.destroy()
        }
    }

    fun getState(): String = when {
        isClosed -> "closed"
        isPaused -> "paused"
        isLimitExceeded -> "limitExceeded"
        socket.isConnectionActive -> "connected"
        reconnectAttempts in 1 until maxReconnectAttempts -> "retrying"
        else -> "stopped"
    }

    // ---------------- event helpers ----------------

    fun on(event: String, callback: (Any?) -> Unit): Adk {
        socket.on(event, callback); return this
    }

    fun off(event: String, callback: (Any?) -> Unit): Adk {
        socket.off(event, callback); return this
    }

    fun onConnection(callback: (ConnectionDetail?) -> Unit): Adk {
        socket.on(Events.CONNECTION) { callback(it as? ConnectionDetail) }
        return this
    }

    fun onLimitExceeded(callback: (String?, String?) -> Unit): Adk {
        socket.on(Events.LIMIT_EXCEEDED) { detail ->
            val map = detail as? Map<*, *>
            callback(map?.get("code") as? String, map?.get("error") as? String)
        }
        return this
    }

    fun onClose(callback: () -> Unit): Adk {
        socket.on(Events.CLOSE) { callback() }; return this
    }

    fun onError(callback: (Any?) -> Unit): Adk {
        socket.on(Events.ERROR) { callback(it) }; return this
    }

    fun offLimitExceeded(callback: (Any?) -> Unit): Adk {
        socket.off(Events.LIMIT_EXCEEDED, callback); return this
    }

    /**
     * Subscribe to a channel.
     */
    suspend fun subscribe(channel: String): BaseSubscription = socket.subscribe(channel)

    /**
     * Register an interceptor.
     */
    suspend fun intercept(
        interceptor: String,
        fn: (payload: Any?, resolve: (Any?) -> Unit, reject: (Any?) -> Unit) -> Unit
    ): Interception = socket.intercept(interceptor, fn)

    /** Closes the underlying WebSocket without releasing the SDK instance. */
    suspend fun closeWebSocket() {
        socket.closeWebSocket()
    }

    suspend fun pushForSecureLine(event: String, data: Any, listen: Boolean): Any? =
        socket.pushForSecureLine(event, data, listen)

    // ---------------- internal helpers ----------------

    private suspend fun initiateSocketConnection() {
        val authConfig = AuthenticationConfig(
            environment = "",
            projectKey = "",
            orgTitle = "",
            clientID = "",
            clientSecret = "",
            accessToken = null
        ).apply {
            this.config = config
            this.getCredentials = config?.getCredentials
        }

        config.getCredentials?.invoke()?.let { creds ->
            authConfig.environment = creds.environment
            authConfig.projectKey = creds.projectKey
            authConfig.orgTitle = creds.orgTitle
            authConfig.clientID = creds.clientID
            authConfig.clientSecret = creds.clientSecret
            authConfig.accessToken = creds.accessToken
        }

        socket.initiateSocket(authConfig)
    }

    private fun handleOnConnection(connection: ConnectionDetail?) {
        reconnectAttempts = 0
        reconnectDelay = initialReconnectDelay
        connection?.let { onConnectedHook(it) }
    }

    private fun handleOnClose() {
        if (isLimitExceeded || isClosed || isPaused) return
        if (isConnectable) {
            socket.isReConnecting = true
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (isLimitExceeded || isClosed || isPaused) return
        scope.launch {
            if (reconnectAttempts < maxReconnectAttempts) {
                reconnectAttempts++
                delay(reconnectDelay)
                reconnectDelay = (reconnectDelay * 2).coerceAtMost(maxReconnectDelay)
                if (!isClosed && !isPaused) connect()
            } else {
                AdkLog.w(TAG, "Max reconnect attempts reached. Connection stopped.")
                isConnectable = false
            }
        }
    }

    private fun onConnectedHook(connection: ConnectionDetail) {
        // Hook intentionally left empty for subclasses or future extensions.
    }

    // ---------------- crypto helpers ----------------

    suspend fun encrypt(
        data: String,
        recipientPublicKey: String,
        privateKey: String? = null
    ): String {
        val key = privateKey ?: myKeyPair?.privateKey
        ?: throw IllegalStateException("Provide a private key or call generateKeyPair() first")
        return CryptoBox.encrypt(data, recipientPublicKey, key)
    }

    suspend fun decrypt(
        data: String,
        senderPublicKey: String,
        privateKey: String? = null
    ): String {
        val key = privateKey ?: myKeyPair?.privateKey
        ?: throw IllegalStateException("Provide a private key or call generateKeyPair() first")
        return CryptoBox.decrypt(data, senderPublicKey, key)
    }

    suspend fun loadConfig(): AuthenticationConfig = withContext(Dispatchers.IO) {
        val response = URL(Constant.CONFIG_JSON_PATH).readText()
        val credentials = JSONObject(response)
        AuthenticationConfig(
            clientID = credentials.getString("Client-ID"),
            clientSecret = credentials.getString("Client-Secret"),
            environment = credentials.getString("Environment"),
            orgTitle = credentials.getString("Org-Title"),
            projectKey = credentials.getString("ProjectKey")
        )
    }

    suspend fun savePublicKey(keyPair: KeyPairType): JSONObject = withContext(Dispatchers.IO) {
        val auth = Auth.getInstance()
        auth.authenticate()
        val authData = auth.getAuthData()
        val credentials = auth.getCredentials()

        val body = JSONObject().put("public_key", keyPair.publicKey)
        val requestBody = body.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("${Constant.BASE_URL}/v1/update-publickey")
            .post(requestBody)
            .addHeader("Content-Type", "application/json")
            .addHeader("X-Org", credentials.orgTitle)
            .addHeader("Authorization", "Bearer ${authData.accessToken}")
            .addHeader("Environment", credentials.environment)
            .addHeader("ProjectKey", credentials.projectKey)
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("Error updating keypair: HTTP ${response.code}")
            val responseBody = response.body?.string()
                ?: throw IllegalStateException("Empty response body")
            myKeyPair = keyPair
            JSONObject(responseBody)
        }
    }

    suspend fun generateKeyPair(): KeyPairType = withContext(Dispatchers.IO) {
        val generated = CryptoBox.generateKeyPair()
        myKeyPair = generated
        generated
    }

    suspend fun setKeyPair(keyPair: KeyPairType?) {
        if (keyPair == null || keyPair.publicKey.isBlank() || keyPair.privateKey.isBlank()) {
            throw IllegalArgumentException(
                "Invalid KeyPair: PublicKey and PrivateKey must be non-empty"
            )
        }
        myKeyPair = keyPair
        savePublicKey(keyPair)
    }

    /**
     * Typed REST helper. Uses the shared [HttpClientProvider.shared] client
     * so each call reuses the connection pool instead of building a new
     * client per request.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun <T> call(
        endpoint: String,
        options: CallApiProps = CallApiProps(),
        responseType: Class<T>
    ): T = withContext(Dispatchers.IO) {
        val auth = Auth.getInstance()
        auth.authenticate()
        val accessToken = auth.getAuthData().accessToken
        val credentials = auth.getCredentials()

        var url = "${Constant.BASE_URL}$endpoint"
        if (!options.queryParams.isNullOrEmpty()) {
            // Sort the keys to produce a deterministic URL — important for
            // signed-URL caches and integration tests.
            val query = options.queryParams.entries
                .sortedBy { it.key }
                .joinToString("&") { (k, v) ->
                    "${URLEncoder.encode(k, StandardCharsets.UTF_8.name())}=" +
                            URLEncoder.encode(v, StandardCharsets.UTF_8.name())
                }
            url += "?$query"
        }

        val headers = mutableMapOf(
            "Authorization" to "Bearer $accessToken",
            "Accept" to "application/json",
            "X-Org" to credentials.orgTitle,
            "Environment" to credentials.environment,
            "ProjectKey" to credentials.projectKey
        )
        options.headers?.let { headers.putAll(it) }

        var body: RequestBody? = null
        if (options.payload != null) {
            headers["Content-Type"] = "application/json"
            body = gson.toJson(options.payload)
                .toRequestBody("application/json".toMediaType())
        }

        val method = (options.method ?: "GET").uppercase()
        val request = Request.Builder()
            .url(url)
            .apply {
                headers.forEach { (k, v) -> addHeader(k, v) }
                method(method, if (method == "GET") null else body)
            }
            .build()

        httpClient.newCall(request).execute().use { res ->
            if (!res.isSuccessful) {
                val errMsg = runCatching {
                    val json = JSONObject(res.body?.string() ?: "")
                    json.optString("message").ifEmpty { json.toString() }
                }.getOrElse { res.message }
                throw IllegalStateException("API $endpoint failed: $errMsg")
            }
            if (res.code == 204) {
                @Suppress("UNCHECKED_CAST")
                return@withContext null as T
            }
            val responseBody = res.body?.string()
                ?: throw IllegalStateException("Empty response body")
            gson.fromJson(responseBody, responseType)
        }
    }

    private fun checkNotClosed() {
        check(!isClosed) { "Adk instance has been closed" }
    }
}
