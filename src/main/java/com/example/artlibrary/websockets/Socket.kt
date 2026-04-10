import android.app.usage.UsageEvents
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.example.artlibrary.auth.Auth
import com.example.artlibrary.config.Constant
import com.example.artlibrary.types.AuthenticationConfig
import com.example.artlibrary.types.ChannelConfig
import com.example.artlibrary.types.ConnectionDetail
import com.example.artlibrary.types.IWebsocketHandler
import com.example.artlibrary.websockets.EventEmitter
import com.example.artlibrary.websockets.Interception
import com.example.artlibrary.websockets.LiveObjSubscription
import com.example.artlibrary.websockets.Subscription
import com.example.artlibrary.websockets.subscribeToChannel
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONObject
import java.net.URLEncoder
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume


class Socket private constructor(
    private val encryptFn: suspend (data: String, recipientPublicKey: String) -> String,
    private val decryptFn: suspend (encryptedHash: String, senderPublicKey: String) -> String,
    private val lpClient: LongPollClient
) : EventEmitter(), IWebsocketHandler {

    private var websocket: WebSocket? = null
    private lateinit var credentials: AuthenticationConfig
    private val subscriptions: MutableMap<String, BaseSubscription> = mutableMapOf()
    private val interceptors = mutableMapOf<String, Interception>()
    private var connection: ConnectionDetail? = null
    var isConnectionActive = false
    private var heartbeatInterval: Timer? = null

    val pendingSendMessges = mutableListOf<String>()
    val secureCallbacks = mutableMapOf<String, (Any?) -> Unit>()
    private val pendingIncomingMessages = mutableMapOf<String, MutableList<IncomingMessage>>()

    protected var pullSource: String = "socket"
    protected var pushSource: String = "socket"
    private var isConnecting = false
    var isReConnecting = false
    private var autoReconnect = false
    private val gson = Gson()
    private val okHttpClient = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()

    companion object {

        @RequiresApi(Build.VERSION_CODES.O)
        fun create(
            encrypt: suspend (String, String) -> String, decrypt: suspend (String, String) -> String
        ): Socket {
            var socketRef: Socket? = null
            val lp = LongPollClient(opts = LongPollOptions(endpoint = Constant.LPOLL,
                getAuthHeaders = suspend {
                    val auth = Auth.getInstance()
                    auth.authenticate()
                    val authData = auth.getAuthData()
                    val creds = auth.getCredentials()
                    mapOf(
                        "Authorization" to "Bearer ${authData.accessToken}",
                        "X-Org" to creds.orgTitle,
                        "Environment" to creds.environment,
                        "ProjectKey" to creds.projectKey
                    )
                }, onMessages = { msgs ->
                    CoroutineScope(Dispatchers.IO).launch {
                        socketRef?.processIncomingMessages(msgs)
                    }
                },
                onError = { err ->
                    Log.e("LP error:", "$err")
                })
            )
            val socket = Socket(encrypt, decrypt, lp)
            socketRef = socket
            return socket
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun initiateSocket(credentials: AuthenticationConfig) {
        // 1. Guard clause: Check if already active
        if (this.websocket != null && this.isConnectionActive) return

        this.credentials = credentials
        connectWebSocket()
        // 3. Attempt SSE (Server-Sent Events) Connection
        try {
            connectSSE()
            this.pullSource = "sse"
            this.pushSource = "http"
            return
        } catch (sseErr: Exception) {
            println("SSE failed, falling back to HTTP poll: ${sseErr.message}")
        }

        // 4. Fallback to HTTP Polling
        this.pullSource = "http"
        this.pushSource = "http"
        this.lpClient.start(this.connection?.connectionId)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun connectWebSocket() {
        if (isConnecting) return
        isConnecting = true

        val auth = Auth.getInstance(credentials)
        val authData = auth.authenticate()

        suspendCancellableCoroutine<Unit> { cont ->

            val params = mutableMapOf(
                "connection_id" to (connection?.connectionId ?: ""),
                "Org-Title" to credentials.orgTitle,
                "token" to authData.accessToken,
                "environment" to credentials.environment,
                "project-key" to credentials.projectKey
            )

            val query = params.map {
                "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}"
            }.joinToString("&")

            val fullWsUrl = "${Constant.WS_URL}?$query"

            Log.d("WS_URL", fullWsUrl)

            val request = Request.Builder().url(fullWsUrl).build()

            websocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {

                override fun onOpen(ws: WebSocket, response: Response) {
                    isConnecting = false
                    isConnectionActive = false // WAIT for art_ready
                    cont.resume(Unit) {}
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    Log.d("Web socket MESSAGE ", text)

                    CoroutineScope(Dispatchers.IO).launch {
                        parseIncomingMessage(text)
                    }
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    Log.e("WS_FAIL", t.message ?: "error", t)
                    if (cont.isActive) cont.resumeWith(Result.failure(t))
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    isConnectionActive = false
                    isConnecting = false
                }
            })
        }
    }

    private suspend fun safeClose(timeout: Long = 1000L) {
        val ws = websocket ?: return

        return withTimeoutOrNull(timeout) {

            suspendCancellableCoroutine<Unit> { continuation ->

                val listener = object : WebSocketListener() {

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        websocket = null
                        if (continuation.isActive) {
                            continuation.resume(Unit) {}
                        }
                    }
                }

                try {
                    ws.close(1000, "Normal Closure")
                } catch (_: Exception) {
                    if (continuation.isActive) {
                        continuation.resume(Unit) {}
                    }
                }
            }
        } ?: run {
            websocket = null
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun connectSSE() {

        val auth = Auth.getInstance(credentials)

        val authData = try {
            auth.authenticate()
        } catch (err: Exception) {
            println("Authentication failed: $err")
            emit("close", mapOf("type" to "error"))
            return
        }

        // Build params
        val params = mapOf(
            "Org-Title" to credentials.orgTitle,
            "token" to authData.accessToken,
            "environment" to credentials.environment,
            "project-key" to credentials.projectKey
        )

        val httpUrl = Constant.SSE_URL.toHttpUrl().newBuilder()

            .addQueryParameter("Org-Title", credentials.orgTitle)

            .addQueryParameter("token", authData.accessToken)

            .addQueryParameter("environment", credentials.environment)

            .addQueryParameter("project-key", credentials.projectKey)

            .build()

        val request = Request.Builder()

            .url(httpUrl)

            .addHeader("Accept", "text/event-stream")

            .build()

        val okHttpClient = OkHttpClient()

        val factory = EventSources.createFactory(okHttpClient)

        factory.newEventSource(request, object : EventSourceListener() {

            override fun onOpen(eventSource: EventSource, response: Response) {

                isConnectionActive = true

                emit("open", UsageEvents.Event())

            }

            override fun onEvent(
                eventSource: EventSource, id: String?, type: String?, data: String
            ) {

                CoroutineScope(Dispatchers.IO).launch {

                    parseIncomingMessage(data)

                }

            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                emit("error", t)

            }

            override fun onClosed(eventSource: EventSource) {

                isConnectionActive = false

            }

        })

    }

    private fun handleConnectionBinding(data: String) {
        setAutoReconnect(true)

        val json = JSONObject(data)

        connection = ConnectionDetail(
            connectionId = json["connection_id"] as String,
            instanceId = json["instance_id"] as String,
            tenantName = credentials.orgTitle,
            environment = credentials.environment,
            projectKey = credentials.projectKey
        )


        emit("connection", connection)

        isConnectionActive = true

        startHeartbeat()

        if (autoReconnect) {

            subscriptions.forEach { (_, subscriptionInstance) ->
                subscriptionInstance.reconnect()
            }

            interceptors.forEach { (_, interceptorInstance) ->
                interceptorInstance.reconnect()
            }
        }
    }

    override suspend fun pushForSecureLine(
        event: String,
        data: Any,
        listen: Boolean
    ): Any? {

        val refId = "${connection?.connectionId}_secure_${System.currentTimeMillis()}_${
            UUID.randomUUID().toString().take(6)
        }"

        val message = mapOf(
            "from" to (connection?.connectionId ?: ""),
            "ref_id" to refId,
            "event" to event,
            "channel" to "art_secure",
            "content" to gson.toJson(data ?: emptyMap<String, Any>())
        )


        return if (listen) {

            suspendCancellableCoroutine { cont ->

                secureCallbacks["secure-$refId"] = { response ->

                    if (cont.isActive) {
                        cont.resume(response)
                    }
                }

                sendMessage(gson.toJson(message))
            }

        } else {
            sendMessage(gson.toJson(message))
            null
        }
    }

    override suspend fun removeSubscription(channel: String) {
        this.subscriptions.remove(channel)
    }

    suspend fun subscribe(channel: String): BaseSubscription {
        return handleSubscription(channel)
    }

    private suspend fun handleSubscription(channel: String): BaseSubscription {

        wait()

        val connectionId: String = connection?.connectionId ?: ""

        // Check if subscription already exists
        if (subscriptions.containsKey(channel)) {

            val sub = subscriptions[channel]!!

            // same connection → just resubscribe
            sub.subscribe()

            return sub
        }

        val channelConfig = validateSubscription(channel, "subscribe")
            ?: throw Exception("Channel $channel not found")

        val subscription: BaseSubscription = if (channelConfig.channelType == "shared-object") {
            LiveObjSubscription(connectionId, channelConfig, this, "subscribe")
        } else {
            Subscription(connectionId, channelConfig, this, "subscribe")
        }

        subscriptions[channel] = subscription

        val buf = pendingIncomingMessages[channel]

        if (buf != null) {
            for (msg in buf) {
                val event = msg.event
                val payload = msg.payload
                subscription.handleMessage(event, payload)
            }

            pendingIncomingMessages.remove(channel)
        }

        return subscription
    }


    suspend fun validateSubscription(channelName: String, process: String): ChannelConfig? {
        if (listOf("art_config", "art_secure").contains(channelName)) {
            return ChannelConfig(
                channelName = channelName,
                channelNamespace = "",
                channelType = "default",
                presenceUsers = emptyList(),
                snapshot = null,
                subscriptionID = ""
            )
        }
        return try {
            subscribeToChannel(
                channelName, process, this
            )
        } catch (e: Exception) {
            throw e
        }
    }

    override fun getConnection(): ConnectionDetail? {
        return connection
    }

    /**
     * Register interceptor.
     * @param interceptor The interceptor name to register to.
     * @param fn The interceptor function.
     * @return Interception instance.
     */
    suspend fun intercept(
        interceptor: String,
        fn: (payload: Any?, resolve: (Any?) -> Unit, reject: (Any?) -> Unit) -> Unit
    ): Interception {

        wait()

        // If interceptor already exists, return it
        if (interceptors.containsKey(interceptor)) {
            return interceptors[interceptor]!!
        }

        // Create a new Interception instance
        val interception = Interception(interceptor, fn, this)

        try {
            interception.validateInterception()
            interceptors[interceptor] = interception
            return interception
        } catch (error: Exception) {
            throw error
        }
    }

    suspend fun parseIncomingMessage(message: String) {
        try {
            val type = object : TypeToken<Any>() {}.type
            val parsed = gson.fromJson<Any>(message, type)

            if (parsed is List<*>) {
                processIncomingMessages(parsed as List<Any>)
            } else {
                handleIncomingMessage(parsed)
            }
        } catch (e: Exception) {
            Log.e("Socket", "Failed to parse JSON")
        }
    }

    suspend fun processIncomingMessages(messages: List<Any>) {
        messages.forEach { handleIncomingMessage(it) }
    }

    /**
     * Handle incoming WebSocket messages and route them to the appropriate Subscription.
     * @param message The raw message received from the WebSocket.
     */
    suspend fun handleIncomingMessage(parsedMessage: Any) {
        try {
            val msg = parsedMessage as? MutableMap<String, Any?> ?: return

            val channel = msg["channel"] as? String
            val namespace = msg["namespace"] as? String
            val refId = msg["ref_id"] as? String
            val event = msg["event"] as? String
            val returnFlag = msg["return_flag"] as? String
            val interceptorName = msg["interceptor_name"] as? String
            val data = msg["content"]

            // Handle connection ready
            if (channel == "art_ready" && event == "ready") {
                handleConnectionBinding(data.toString())
                return
            }

            // Handle secure channel (MOST IMPORTANT FIX AREA)
            else if (channel == "art_secure") {

                if (refId == null) {
                    Log.e("SECURE_ERROR", "refId is NULL: $msg")
                    return
                }

                val key = "secure-$refId"
                val callback = secureCallbacks[key]

                if (callback != null) {

                    // Parse content safely (STRING → MAP)
                    val parsedData: Map<String, Any?> = try {
                        when (data) {
                            is String -> gson.fromJson(
                                data,
                                object :
                                    com.google.gson.reflect.TypeToken<Map<String, Any?>>() {}.type
                            )

                            is Map<*, *> -> data as Map<String, Any?>
                            else -> emptyMap()
                        }
                    } catch (e: Exception) {
                        Log.e("PARSE_ERROR", "Failed to parse content: $data", e)
                        emptyMap()
                    }

                    // Match TS structure exactly
                    val response = mapOf(
                        "channel" to channel,
                        "namespace" to namespace,
                        "data" to parsedData,
                        "ref_id" to refId,
                        "event" to event
                    )

                    Log.d("SECURE_FLOW", "Invoking callback for $refId")

                    callback.invoke(response)
                    secureCallbacks.remove(key)

                } else {
                    Log.e("SECURE_ERROR", "No callback found for refId: $refId")
                }

                return
            }

            // Validate message
            if (channel == null || (event == null && returnFlag != "SA")) {
                Log.w("Socket", "Received message without channel or event: $msg")
                return
            }

            // Shift to HTTP
            if (event == "shift_to_http") {
                switchToHttpPoll()
                return
            }

            // Error log
            if (event == "error") {
                Log.e("Socket", "Received error message: $msg")
            }

            // Replace content → data (like JS)
            msg.remove("content")
            msg["data"] = data

            // Interceptor handling
            if (!interceptorName.isNullOrEmpty()) {

                val interception = interceptors[interceptorName]

                if (interception != null) {
                    interception.handleMessage(channel, msg)
                } else {
                    Log.w("Socket", "No Interception found for channel: $channel")
                }

            } else {

                //Subscription handling
                var subscriptionKey = channel

                if (!namespace.isNullOrEmpty()) {
                    subscriptionKey += ":$namespace"
                }

                val subscription = subscriptions[subscriptionKey]

                if (subscription != null) {

                    if (subscription is Subscription) {
                        subscription.handleMessage(event ?: "", msg)
                    } else if (subscription is LiveObjSubscription) {
                        @Suppress("UNCHECKED_CAST")
                        subscription.handleMessage(event ?: "", msg as MutableMap<String, Any>)
                    } else {
                        subscription.handleMessage(event ?: "", msg)
                    }

                } else {

                    Log.w(
                        "Socket",
                        "No subscription found for channel: $subscriptionKey, adding to buffer"
                    )

                    val arr =
                        pendingIncomingMessages.getOrPut(subscriptionKey) { mutableListOf() }

                    arr.add(IncomingMessage(event ?: "", msg))
                }
            }

        } catch (e: Exception) {
            Log.e("Socket", "Failed to parse incoming message", e)
        }
    }

    private fun switchToHttpPoll() {
        if (this.pullSource == "http") return
        this.pullSource = "http"
        this.pushSource = "http"
        this.lpClient.start(this.connection?.connectionId ?: "")
    }

    /**
     * Send a message through the WebSocket.
     * @param message The message to send.
     */
    override fun sendMessage(message: String) {
        Log.d("WS_SEND", message)
        if (websocket != null && isConnectionActive) {
            websocket!!.send(message)
        } else {
            pendingSendMessges.add(message)
        }
    }

    fun setAutoReconnect(value: Boolean) {
        this.autoReconnect = value
    }

    suspend fun closeWebSocket(clearConnection: Boolean = false) {
        safeClose()

        isConnectionActive = false
        connection = null
        isConnecting = false

        if (clearConnection) {
            pendingIncomingMessages.clear()
            pendingSendMessges.clear()
            subscriptions.clear()
            interceptors.clear()
        }

        heartbeatInterval?.let {
            //  clearInterval(it)
            heartbeatInterval = null
        }
    }


    override suspend fun wait() {

        if (isConnectionActive) return

        suspendCancellableCoroutine<Unit> { continuation ->

            lateinit var listener: (Any?) -> Unit

            listener = {
                off("connection", listener)
                continuation.resume(Unit)
            }

            on("connection", listener)
        }
    }

    private fun _runHeartBeatPayload(): Map<String, Any?> {
        val subList = subscriptions.map { (key, sub) ->
            mapOf(
                "name" to key,
                (("presenceTracking" to sub.isListening) ?: false) as Pair<Any, Any>,
            )
        }
        return mapOf(
            "connectionId" to this.connection?.connectionId,
            "timestamp" to System.currentTimeMillis(),
            "subscriptions" to subList
        )
    }

    private fun startHeartbeat() {
        if (heartbeatInterval != null) return
        heartbeatInterval = Timer()
        heartbeatInterval?.schedule(object : TimerTask() {
            override fun run() {
                if (!isConnectionActive) return
                CoroutineScope(Dispatchers.IO).launch {
                    pushForSecureLine(
                        "heartbeat", _runHeartBeatPayload(), false
                    )
                }
            }
        }, 30000, 30000)
    }

    suspend fun waitConnection() {
        if (this.isConnectionActive) return
        while (!isConnectionActive) {
            kotlinx.coroutines.delay(100)
        }
    }

    override suspend fun encrypt(data: Any, recipientPublicKey: String): String {
        return encryptFn(data.toString(), recipientPublicKey)
    }

    override suspend fun decrypt(encryptedHash: String, senderPublicKey: String): String {
        return decryptFn(encryptedHash, senderPublicKey)
    }


    data class IncomingMessage(val event: String, val payload: Any)
}

