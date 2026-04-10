import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.example.artlibrary.auth.Auth
import com.example.artlibrary.config.Constant
import com.example.artlibrary.crypto.CryptoBox
import com.example.artlibrary.types.AdkConfig
import com.example.artlibrary.types.AuthenticationConfig
import com.example.artlibrary.types.CallApiProps
import com.example.artlibrary.types.ConnectionDetail
import com.example.artlibrary.types.KeyPairType
import com.example.artlibrary.websockets.Interception
import com.google.gson.Gson
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder

@RequiresApi(Build.VERSION_CODES.O)
class Adk(config: AdkConfig? = null) {
    protected val socket: Socket = Socket.create(::encrypt, ::decrypt)
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 5
    private var reconnectDelay = 3000L
    private val maxDelay = 5000L
    protected var myKeyPair: KeyPairType? = null
    private var isPaused = false
    protected var adkConfig: AdkConfig? = null
    var isConnectable = false
    private var isLimitExceeded = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        val rawUrl = config?.uri
        Constant.BASE_URL = "https://${rawUrl}"
        Constant.WS_URL = "wss://${rawUrl}/v1/connect"
        Constant.SSE_URL = "https://${rawUrl}/v1/connect/sse"
        Constant.LPOLL = "https://${rawUrl}/v1/connect/longpoll"

        socket.on("connection") { handleOnConnection(it as ConnectionDetail) }
        socket.on("close") { handleOnClose() }
        socket.on("limitExceeded") { detail ->
            val map = detail as? Map<*, *>
            val code = map?.get("code") as? String
            val error = map?.get("error") as? String
            val msg = if (code == "CONCURRENT_LIMIT_EXCEEDED") {
                "[ART] Concurrent connection limit reached: ${error}. All reconnection attempts stopped. Call connect() again to retry."
            } else {
                "[ART] Billing limit reached : ${error}. All reconnection attempts permanently stopped."
            }
            println(msg)
            isLimitExceeded = true
            isConnectable = false
        }
        adkConfig = config
    }


    suspend fun connect() {
        isConnectable = true
        initiateSocketConnection()
    }

    suspend fun pause() {
        if (isPaused) return
        isPaused = true
        // stop any reconnection loop
        reconnectAttempts = maxReconnectAttempts
        // close the socket
        socket.closeWebSocket()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun resume() {
        if (!isPaused) return
        isPaused = false
        // reset backoff/reconnect
        reconnectAttempts = 0
        reconnectDelay = 3000
        // new readyPromise so callers can await
        socket.connectWebSocket()
    }

    fun disconnect() {
        isConnectable = false
        // stop any further reconnection attempts
        reconnectAttempts = maxReconnectAttempts
        scope.launch {
            socket.closeWebSocket(true)
        }
    }

    // fun setCredentials(credentials: AuthenticationConfig) {
    //     this.credentials = {
    //         ClientID: credentials.ClientID,
    //         ClientSecret: credentials.ClientSecret,
    //         Environment: credentials.Environment,
    //         OrgTitle: credentials.OrgTitle,
    //         ProjectKey: credentials.ProjectKey,
    //     }
    //     Auth.getInstance().setCredentials(this.credentials)
    // }

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
        val config = adkConfig
            ?: throw IllegalStateException("AdkConfig is null. Initialize SDK properly.")
        val authConfig = AuthenticationConfig(
            environment = "",
            projectKey = "",
            orgTitle = "",
            clientID = "",
            clientSecret = "",
            accessToken = null
        ).apply {
            this.config = config
            this.getCredentials = config.getCredentials
        }

        config.getCredentials?.invoke()?.let { creds ->
            authConfig.environment = creds.environment
            authConfig.projectKey = creds.projectKey
            authConfig.orgTitle = creds.orgTitle
            authConfig.clientID = creds.clientID
            authConfig.clientSecret = creds.clientSecret
            authConfig.accessToken = creds.accessToken
        }

        this.socket.initiateSocket(authConfig)
    }

    private fun handleOnConnection(connection: ConnectionDetail) {
        this.reconnectAttempts = 0
        this.reconnectDelay = 3000

        // Call the hook so child classes can do something here
        this.onConnectedHook(connection)
    }

    private fun handleOnClose() {
        if (this.isLimitExceeded) return // billing limit hit — never reconnect
        if (this.isConnectable) {
            socket.isReConnecting = true
            handleReconnection()
        }
    }

    private fun handleReconnection() {
        if (this.isLimitExceeded) return // billing limit hit — never reconnect
        scope.launch {
            if (reconnectAttempts < maxReconnectAttempts) {
                reconnectAttempts++

                delay(reconnectDelay)

                reconnectDelay = minOf(reconnectDelay + 2000, maxDelay)

                connect()
            } else {
                Log.w("ADK", "Max reconnect attempts reached. Retrying every ${maxDelay / 1000}s")

                delay(maxDelay)

                connect()
            }
        }
    }

    // Overload for 'open' event
    fun on(event: String, callback: (Any?) -> Unit): Adk {
        socket.on(event, callback)
        return this
    }

    fun off(event: String, callback: (Any?) -> Unit): Adk {
        socket.off(event, callback)
        return this
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

    /**
     * Close the WebSocket connection.
     */
    suspend fun closeWebSocket() {
        socket.closeWebSocket()
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


    suspend fun savePublicKey(keyPair: KeyPairType): JSONObject = withContext(Dispatchers.IO) {
        val auth = Auth.getInstance()
        auth.authenticate()
        val authData = auth.getAuthData();
        val credentials = auth.getCredentials();

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
            throw Error("Error updating keypair");
        }

        val responseBody = response.body?.string() ?: throw Exception("Empty response body")

        val jsonResponse = JSONObject(responseBody)

        // Store keypair like JS
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
            var url = "${Constant.BASE_URL}${endpoint}"
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

    private val httpClient = OkHttpClient()

}
