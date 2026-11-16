package com.example.artlibrary.websockets

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.example.artlibrary.agentic.Agent
import com.example.artlibrary.agentic.Orchestrator
import com.example.artlibrary.auth.Auth
import com.example.artlibrary.config.Constant
import com.example.artlibrary.crypto.CryptoBox
import com.example.artlibrary.storage.FileRef
import com.example.artlibrary.storage.ListFilesResult
import com.example.artlibrary.storage.ListOptions
import com.example.artlibrary.storage.Storage
import com.example.artlibrary.storage.StorageFile
import com.example.artlibrary.storage.UploadOptions
import com.example.artlibrary.types.AdkConfig
import com.example.artlibrary.types.AuthenticationConfig
import com.example.artlibrary.types.CallApiProps
import com.example.artlibrary.types.ConnectConfig
import com.example.artlibrary.types.ConnectionDetail
import com.example.artlibrary.types.ConnectionError
import com.example.artlibrary.types.CredentialStore
import com.example.artlibrary.types.KeyPairType
import com.example.artlibrary.types.UpdateProfileData
import com.google.gson.Gson
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URI
import java.net.URL
import java.net.URLEncoder


interface AdkPluginContext {
    suspend fun subscribe(channel: String): BaseSubscription
    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun <T> call(endpoint: String, options: CallApiProps = CallApiProps(), responseType: Class<T>): T

    fun getCredentials(): CredentialStore

    fun baseUrl(): String
}

interface AdkPlugin<N : CharSequence, T> {
    val name: N
    fun install(ctx: AdkPluginContext): T
}

@RequiresApi(Build.VERSION_CODES.O)
class Adk(config: AdkConfig? = null) {
    protected val socket: Socket = Socket.create(::encrypt, ::decrypt)
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 5
    private var reconnectDelay = 3000L
    private val maxDelay = 5000L
    protected var myKeyPair: KeyPairType? = null
    protected var adkConfig: AdkConfig? = null
    private var isPaused = false
    var isConnectable = false
    private var isLimitExceeded = false
    private var credentialData: CredentialStore? = null
    private var reconnectTimer: Job? = null
    private var connectionAttempt: Deferred<Unit>? = null
    private val storage = Storage()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val plugins = mutableMapOf<String, Any?>()
    private val httpClient = OkHttpClient()

    private fun cancelReconnect() {
        reconnectTimer?.cancel()
        reconnectTimer = null
    }

    private val handleConnectionFailed: (Any?) -> Unit = {
        isConnectable = false
        cancelReconnect()
    }

    private val handleLimitExceeded: (Any?) -> Unit = { detail ->
        val map = detail as? Map<*, *>
        val code = map?.get("code") as? String
        val error = map?.get("error") as? String
        val msg = if (code == "CONCURRENT_LIMIT_EXCEEDED") {
            "[ART] Concurrent connection limit reached: $error. All reconnection attempts stopped. Call connect() again to retry."
        } else {
            "[ART] Billing limit reached: $error. All reconnection attempts permanently stopped."
        }
        Log.e("ADK", msg)
        isLimitExceeded = true
        isConnectable = false
        cancelReconnect()
    }

    private val handleAuthenticationFailed: (Any?) -> Unit = { detail ->
        val map = detail as? Map<*, *>
        val status = (map?.get("status") as? Number)?.toInt() ?: 0
        val error = map?.get("error") as? String
        Log.e(
            "ADK",
            "[ART] Authentication rejected${if (status != 0) " with HTTP $status" else ""}: $error. " +
                "Automatic reconnection has been stopped. Update the credentials and call connect() again."
        )
        isConnectable = false
        cancelReconnect()
        socket.isReConnecting = false
    }

    private val handleOnConnection: (Any?) -> Unit = { raw ->
        val wasReconnecting = reconnectAttempts > 0 || socket.isReConnecting
        cancelReconnect()
        reconnectAttempts = 0
        reconnectDelay = 3000
        (raw as? ConnectionDetail)?.let { onConnectedHook(it) }
        if (wasReconnecting) socket.emit("reconnected", null)
    }

    private val handleOnClose: (Any?) -> Unit = {
        if (!isLimitExceeded) {   // billing limit hit — never reconnect
            if (isConnectable && connectionAttempt == null) {
                socket.isReConnecting = true
                handleReconnection()
            }
        }
    }

    init {
        val rawUrl = config?.uri
        Constant.BASE_URL = "https://${rawUrl}"
        Constant.WS_URL = "wss://${rawUrl}/v1/connect"
        Constant.SSE_URL = "https://${rawUrl}/v1/connect/sse"
        Constant.LPOLL = "https://${rawUrl}/v1/connect/longpoll"

        adkConfig = config
        attachLifecycle()
    }

    private fun attachLifecycle() {
        detachLifecycle()
        on("connection", handleOnConnection)
        on("close", handleOnClose)
        socket.on("limitExceeded", handleLimitExceeded)
        socket.on("authenticationFailed", handleAuthenticationFailed)
        socket.on("connectionFailed", handleConnectionFailed)
    }

    private fun detachLifecycle() {
        off("connection", handleOnConnection)
        off("close", handleOnClose)
        socket.off("limitExceeded", handleLimitExceeded)
        socket.off("authenticationFailed", handleAuthenticationFailed)
        socket.off("connectionFailed", handleConnectionFailed)
    }

    suspend fun connect(config: ConnectConfig? = null) {
        connectionAttempt?.let { return it.await() }
        if (socket.isConnectionActive) return
        attachLifecycle()
        cancelReconnect()
        isPaused = false
        isLimitExceeded = false
        reconnectAttempts = 0
        reconnectDelay = 3000
        socket.beginConnection(adkConfig?.connectionTimeoutMs?.toLong() ?: 30_000L)
        if (adkConfig?.autoLoadCredsFromJSON == true) {
            credentialData = loadConfig().toCredentialStore()
        }
        isConnectable = true
        attemptConnection().await()
    }

    private fun attemptConnection(): Deferred<Unit> {
        connectionAttempt?.let { return it }
        val attempt = scope.async {
            coroutineScope {
                val a = async { initiateSocketConnection() }
                val b = async { socket.waitForConnection() }
                a.await()
                b.await()
            }
            Unit
        }
        connectionAttempt = attempt
        attempt.invokeOnCompletion {
            if (connectionAttempt === attempt) connectionAttempt = null
        }
        return attempt
    }

    fun pause() {
        if (isPaused) return
        isPaused = true
        isConnectable = false
        cancelReconnect()
        // stop any reconnection loop
        reconnectAttempts = maxReconnectAttempts
        // close the socket
        socket.failConnection(ConnectionError("The connection is paused.", "CONNECTION_PAUSED"))
    }

    suspend fun resume() {
        if (!isPaused) return
        connect()
    }

    suspend fun disconnect() {
        isConnectable = false
        cancelReconnect()
        socket.failConnection(ConnectionError("The connection was closed.", "CONNECTION_CLOSED"))
        // stop any further reconnection attempts
        reconnectAttempts = maxReconnectAttempts
        // close the underlying WebSocket
        socket.closeWebSocket(true)
        // reset internal state so you can reconnect later if needed
        socket.isConnectionActive = false
        socket.pendingSendMessges.clear()
        detachLifecycle()
    }

    fun getState(): String {
        return when {
            isPaused -> "paused"
            reconnectAttempts >= maxReconnectAttempts -> "stopped"
            reconnectAttempts > 0 -> "retrying"
            socket.isConnectionActive -> "connected"
            else -> "stopped"
        }
    }

    private suspend fun initiateSocketConnection() {
        val creds = credentialData
        if (creds == null) {
            val error = ConnectionError(
                "Connection credentials are missing. Please sign in again.",
                "AUTHENTICATION_FAILED"
            )
            socket.failConnection(error)
            throw error
        }
        val authConfig = AuthenticationConfig(
            environment = creds.environment,
            projectKey = creds.projectKey,
            orgTitle = creds.orgTitle,
            clientID = creds.clientID,
            clientSecret = creds.clientSecret,
            config = adkConfig,
            accessToken = creds.accessToken,
            getCredentials = adkConfig?.getCredentials
        )
        socket.initiateSocket(authConfig)
        socket.waitForConnection()
    }

    private fun handleReconnection() {
        if (isLimitExceeded || !isConnectable || isPaused || reconnectTimer != null) return
        if (reconnectAttempts < maxReconnectAttempts) {
            reconnectAttempts++
            Log.i("ADK", "Attempting to reconnect in ${reconnectDelay / 1000} seconds... (Attempt $reconnectAttempts)")
            reconnectTimer = scope.launch {
                delay(reconnectDelay)
                reconnectTimer = null
                if (!isConnectable || isPaused || isLimitExceeded) return@launch
                scope.launch {
                    runCatching { attemptConnection().await() }.onFailure {
                        if (isConnectable) handleReconnection()
                    }
                }
                // Exponential backoff, capped at maxDelay
                reconnectDelay = minOf(reconnectDelay + 2000, maxDelay)
            }
        } else {
            socket.failConnection(
                ConnectionError(
                    "The connection could not be restored. Please reconnect to check your execution.",
                    "CONNECTION_LOST"
                )
            )
        }
    }

    fun on(event: String, callback: (Any?) -> Unit): Adk {
        socket.on(event, callback)
        return this
    }

    fun off(event: String, callback: (Any?) -> Unit): Adk {
        socket.off(event, callback)
        return this
    }

    /**
     * Fires each time the transport reconnects after a drop. Returns the
     * registered listener handle to pass to [offReconnected].
     */
    fun onReconnected(callback: () -> Unit): (Any?) -> Unit {
        val wrapper: (Any?) -> Unit = { callback() }
        on("reconnected", wrapper)
        return wrapper
    }

    fun offReconnected(handle: (Any?) -> Unit) {
        off("reconnected", handle)
    }

    /** Typed connection state (mirrors the Swift `adk.state`). */
    val state: ConnectionState
        get() = when (getState()) {
            "connected" -> ConnectionState.CONNECTED
            "paused" -> ConnectionState.PAUSED
            "retrying" -> ConnectionState.RECONNECTING
            else -> ConnectionState.DISCONNECTED
        }

    // Kotlin doesn't have overloads by event name like TS,
    // so handle specific event types inside the callback
    fun onConnection(callback: (ConnectionDetail?) -> Unit): Adk {
        socket.on("connection") { callback(it as? ConnectionDetail) }
        return this
    }

    fun onLimitExceeded(callback: (String?, String?) -> Unit): Adk {
        socket.on("limitExceeded") { detail ->
            val map = detail as? Map<*, *>
            val code = map?.get("code") as? String
            val error = map?.get("error") as? String
            callback(code, error)
        }
        return this
    }

    fun onClose(callback: () -> Unit): Adk {
        socket.on("close") { callback() }
        return this
    }

    fun onError(callback: (Any?) -> Unit): Adk {
        socket.on("error") { callback(it) }
        return this
    }

    fun offLimitExceeded(callback: (Any?) -> Unit): Adk {
        socket.off("limitExceeded", callback)
        return this
    }

    fun setCredentials(config: CredentialStore) {
        credentialData = config
    }

    /**
     * Subscribe to a specific channel via Socket.
     * @param channel The channel name to subscribe to.
     * @returns A Subscription instance for the specified channel.
     */
    suspend fun subscribe(channel: String): BaseSubscription {
        return this.socket.subscribe(channel)
    }

    /**
     * Register an interceptor via Socket.
     * @param interceptor The interceptor name to subscribe to.
     * @returns A interception instance for the specified interceptor.
     */
    suspend fun intercept(
        interceptor: String,
        fn: (payload: Any?, resolve: (Any?) -> Unit, reject: (Any?) -> Unit) -> Unit
    ): Interception {
        return socket.intercept(interceptor, fn)
    }

    fun agent(agentId: String): Agent {
        return Agent(agentId, socket)
    }

    fun orchestrator(orchestratorId: String): Orchestrator {
        return Orchestrator(orchestratorId, socket)
    }

    // NOTE: the TS `connector(connectorId)` returns `new Connector(connectorId, socket)`,
    // but there is no `Connector` class in the Kotlin codebase yet — port that file
    // first and this method can be added verbatim.

    fun <N : CharSequence, T> use(plugin: AdkPlugin<N, T>): T {
        val api = plugin.install(pluginContext())
        plugins[plugin.name.toString()] = api
        return api
    }

    /** Retrieve a previously [use]d plugin's API by name. */
    @Suppress("UNCHECKED_CAST")
    fun <T> plugin(name: String): T =
        (plugins[name]
            ?: throw IllegalStateException("Plugin '$name' not installed. Call use(...) first.")) as T

    // The slice of this Adk a plugin is handed at install time.
    private fun pluginContext(): AdkPluginContext = object : AdkPluginContext {
        override suspend fun subscribe(channel: String): BaseSubscription =
            socket.subscribe(channel)

        @RequiresApi(Build.VERSION_CODES.O)
        override suspend fun <T> call(endpoint: String, options: CallApiProps, responseType: Class<T>): T =
            this@Adk.call(endpoint, options, responseType)

        override fun getCredentials(): CredentialStore =
            Auth.getInstance().getCredentials().toCredentialStore()

        override fun baseUrl(): String = try {
            val uri = URI(Constant.BASE_URL)
            buildString {
                append(uri.scheme).append("://").append(uri.host)
                if (uri.port != -1) append(":").append(uri.port)
            }
        } catch (e: Exception) {
            Constant.BASE_URL
        }
    }

    /**
     * Close the WebSocket connection.
     */
    fun closeWebSocket() {
        isConnectable = false
        cancelReconnect()
        socket.failConnection(ConnectionError("The connection was closed.", "CONNECTION_CLOSED"))
    }

    suspend fun pushForSecureLine(
        event: String, data: Any, listen: Boolean
    ): Any? {
        return socket.pushForSecureLine(event, data, listen)
    }

    /**
     * Hook that the child class can override if needed.
     * This is called whenever a 'connection' event is received
     */
    private fun onConnectedHook(connection: ConnectionDetail) {}

    /**
     * Method used to encrypt the message.
     * This is called whenever a message is sent through secure channel subsciption
     */
    suspend fun encrypt(
        data: String, recipientPublicKey: String, privateKey: String? = null
    ): String {

        val key = privateKey ?: myKeyPair?.privateKey
        ?: throw IllegalStateException("Please provide a private key or generate a new key pair")

        return CryptoBox.encrypt(
            data, recipientPublicKey, key
        )
    }

    /**
     * Method used to decrypt the message.
     * This is called whenever a message is recieved through secure channel
     */
    suspend fun decrypt(
        data: String, senderPublicKey: String, privateKey: String? = null
    ): String {

        val key = privateKey ?: myKeyPair?.privateKey
        ?: throw IllegalStateException("Please provide a private key or generate a new key pair")

        return CryptoBox.decrypt(
            data, senderPublicKey, key
        )
    }

    fun loadConfig(): AuthenticationConfig {
        val response = URL(Constant.CONFIG_JSON_PATH).readText()
        val credentials = JSONObject(response)

        return AuthenticationConfig(
            clientID = credentials.getString("Client-ID"),
            clientSecret = credentials.getString("Client-Secret"),
            environment = credentials.getString("Environment"),
            orgTitle = credentials.getString("Org-Title"),
            projectKey = credentials.getString("ProjectKey")
        )
    }

    private fun AuthenticationConfig.toCredentialStore(): CredentialStore {
        return CredentialStore(
            environment = environment,
            projectKey = projectKey,
            orgTitle = orgTitle,
            clientID = clientID,
            clientSecret = clientSecret,
            config = config,
            accessToken = accessToken
        )
    }

    suspend fun savePublicKey(keyPair: KeyPairType): JSONObject = withContext(Dispatchers.IO) {
        val auth = Auth.getInstance()
        auth.authenticate()
        val authData = auth.getAuthData()
        val credentials = auth.getCredentials()

        val body = JSONObject().put("public_key", keyPair.publicKey)

        val requestBody = body.toString().toRequestBody("application/json".toMediaType())

        val request =
            Request.Builder().url("${Constant.BASE_URL}/v1/update-publickey").post(requestBody)
                .addHeader("Content-Type", "application/json")
                .addHeader("X-Org", credentials.orgTitle)
                .addHeader("Authorization", "Bearer ${authData.accessToken}")
                .addHeader("Environment", credentials.environment)
                .addHeader("ProjectKey", credentials.projectKey).build()

        val response = httpClient.newCall(request).execute()

        if (!response.isSuccessful) {
            throw Error("Error updating keypair")
        }

        val responseBody = response.body?.string() ?: throw Exception("Empty response body")

        val jsonResponse = JSONObject(responseBody)

        myKeyPair = keyPair

        jsonResponse
    }

    suspend fun generateKeyPair(): KeyPairType {
        val generated = CryptoBox.generateKeyPair()
        this.myKeyPair = generated
        return generated
    }

    suspend fun setKeyPair(keyPair: KeyPairType?) {

        if (keyPair == null || keyPair.publicKey.isBlank() || keyPair.privateKey.isBlank()) {
            throw IllegalArgumentException(
                "Invalid KeyPair: PublicKey and PrivateKey must be non-empty strings"
            )
        }
        this.myKeyPair = keyPair
        savePublicKey(keyPair)
    }

    /**
     * Update the authenticated user's profile.
     * @param data Profile fields to update (all optional).
     */
    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun updateProfile(data: UpdateProfileData) {
        call(
            "/v1/update-profile",
            CallApiProps(
                method = "POST",
                payload = mapOf(
                    "first_name" to data.firstName,
                    "last_name" to data.lastName,
                    "email" to data.email,
                    "attributes" to data.attributes
                )
            ),
            Any::class.java
        )
    }

    // API Call Helper
    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun <T> call(
        endpoint: String,
        options: CallApiProps = CallApiProps(),
        responseType: Class<T>
    ): T = withContext(Dispatchers.IO) {
        try {
            // 1) grab fresh token
            val auth = Auth.getInstance()
            auth.authenticate()
            val accessToken = auth.getAuthData().accessToken
            val credentials = auth.getCredentials()

            // 2) build URL + optional ?foo=bar
            var url = "${options.baseUrl ?: Constant.BASE_URL}$endpoint"
            if (!options.queryParams.isNullOrEmpty()) {
                val query = options.queryParams.entries.joinToString("&") { (k, v) ->
                    "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
                }
                url += "?$query"
            }

            // 3) assemble headers
            val headers = mutableMapOf(
                "Authorization" to "Bearer $accessToken",
                "Accept" to "application/json",
                "X-Org" to credentials.orgTitle,
                "Environment" to credentials.environment,
                "ProjectKey" to credentials.projectKey
            )
            options.headers?.let { headers.putAll(it) }

            // 4) stringify payload if present
            var body: RequestBody? = null
            if (options.payload != null) {
                headers["Content-Type"] = "application/json"
                val jsonBody = Gson().toJson(options.payload).toString()
                body = jsonBody.toRequestBody("application/json".toMediaType())
            }

            // 5) fire off request
            val client = OkHttpClient()
            val method = (options.method ?: "GET").uppercase()
            val request = Request.Builder()
                .url(url)
                .apply {
                    headers.forEach { (k, v) -> addHeader(k, v) }
                    method(method, if (method == "GET") null else body)
                }
                .build()

            val res = client.newCall(request).execute()

            // 6) error handling
            if (!res.isSuccessful) {
                val errMsg = try {
                    val errBody = JSONObject(res.body?.string() ?: "")
                    errBody.optString("message").ifEmpty { errBody.toString() }
                } catch (e: Exception) {
                    res.message
                }
                throw Exception("API $endpoint failed: $errMsg")
            }

            // 7) handle 204 No Content
            if (res.code == 204) {
                @Suppress("UNCHECKED_CAST")
                return@withContext null as T
            }

            // 8) parse and resolve
            val responseBody = res.body?.string() ?: throw Exception("Empty response body")
            Gson().fromJson(responseBody, responseType)

        } catch (err: Exception) {
            throw err
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun upload(file: ByteArray, opts: UploadOptions = UploadOptions()): FileRef {
        return storage.upload(file, opts)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun listFiles(opts: ListOptions = ListOptions()): ListFilesResult {
        return storage.listFiles(opts)
    }

    // NOTE: the TS `getFile(fileId, opts: FileAccessOptions)` / `deleteFile(fileId, { hard, timeoutMs })`
    // are adapted to the Kotlin Storage signatures (there is no FileAccessOptions type).
    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun getFile(fileId: String, timeoutMs: Long? = null): StorageFile {
        return storage.getFile(fileId, timeoutMs)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun deleteFile(fileId: String, hard: Boolean = false, timeoutMs: Long? = null) {
        return storage.deleteFile(fileId, hard, timeoutMs)
    }
}
