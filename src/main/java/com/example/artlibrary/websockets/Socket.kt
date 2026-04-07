package com.example.artlibrary.websockets

import android.os.Build
import androidx.annotation.RequiresApi
import com.example.artlibrary.auth.Auth
import com.example.artlibrary.config.AdkLog
import com.example.artlibrary.config.ChannelTypes
import com.example.artlibrary.config.Constant
import com.example.artlibrary.config.Events
import com.example.artlibrary.config.HttpClientProvider
import com.example.artlibrary.config.ReservedChannels
import com.example.artlibrary.config.ReturnFlags
import com.example.artlibrary.types.AuthenticationConfig
import com.example.artlibrary.types.ChannelConfig
import com.example.artlibrary.types.ConnectionDetail
import com.example.artlibrary.types.IWebsocketHandler
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONObject
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

private const val TAG = "ArtSocket"

/**
 * Concrete transport handler used by [Adk]. Owns the WebSocket connection,
 * the SSE fallback, the long-poll fallback, the per-channel subscription
 * registry and the secure-line callback table.
 *
 * Concurrency model:
 *  - All mutable collections are concurrent containers.
 *  - The WebSocket lifecycle is guarded by [connectionMutex] so that
 *    overlapping `connect`, `pause`, `resume` and `close` calls cannot
 *    interleave half-states.
 *  - The internal coroutine scope is exposed via [release] which cancels
 *    everything when the SDK is shut down.
 */
class Socket private constructor(
    private val encryptFn: suspend (data: String, recipientPublicKey: String) -> String,
    private val decryptFn: suspend (encryptedHash: String, senderPublicKey: String) -> String
) : EventEmitter(), IWebsocketHandler {

    private var websocket: WebSocket? = null
    private lateinit var credentials: AuthenticationConfig

    private val subscriptions: MutableMap<String, BaseSubscription> = ConcurrentHashMap()
    private val interceptors = ConcurrentHashMap<String, Interception>()
    private val pendingIncomingMessages =
        ConcurrentHashMap<String, MutableList<IncomingMessage>>()
    private val pendingSendMessages: MutableList<String> =
        Collections.synchronizedList(mutableListOf())
    val secureCallbacks = ConcurrentHashMap<String, (Any?) -> Unit>()

    private var connection: ConnectionDetail? = null

    @Volatile
    var isConnectionActive: Boolean = false
    @Volatile
    var isReConnecting: Boolean = false

    private var pullSource: String = "socket"
    private var pushSource: String = "socket"

    @Volatile
    private var isConnecting: Boolean = false
    @Volatile
    private var autoReconnect: Boolean = false

    private val gson = Gson()
    private val okHttpClient = HttpClientProvider.webSocket
    private val sseClient = HttpClientProvider.sse

    private val connectionMutex = Mutex()
    private val socketScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var heartbeatJob: Job? = null

    /** Late-bound long-poll client; created via [create] before publication. */
    internal lateinit var longPollClient: LongPollClient

    companion object {

        @RequiresApi(Build.VERSION_CODES.O)
        fun create(
            encrypt: suspend (String, String) -> String,
            decrypt: suspend (String, String) -> String
        ): Socket {
            val socket = Socket(encrypt, decrypt)
            // Build the long-poll client AFTER the socket exists so we can
            // safely capture a real reference instead of patching a mutable
            // var from a callback closure.
            val lp = LongPollClient(
                opts = LongPollOptions(
                    endpoint = Constant.LPOLL,
                    getAuthHeaders = suspend {
                        val auth = Auth.getInstance()
                        val authData = auth.authenticate()
                        val creds = auth.getCredentials()
                        mapOf(
                            "Authorization" to "Bearer ${authData.accessToken}",
                            "X-Org" to creds.orgTitle,
                            "Environment" to creds.environment,
                            "ProjectKey" to creds.projectKey
                        )
                    },
                    onMessages = { msgs ->
                        socket.socketScope.launch {
                            socket.processIncomingMessages(msgs)
                        }
                    },
                    onError = { err ->
                        AdkLog.e("ArtLongPoll", "error: ${err.message}", err)
                    }
                )
            )
            socket.longPollClient = lp
            return socket
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun initiateSocket(credentials: AuthenticationConfig) {
        if (websocket != null && isConnectionActive) return

        this.credentials = credentials
        connectWebSocket()

        // SSE / long-poll fallbacks are best-effort and intentionally tolerant.
        try {
            connectSSE()
            this.pullSource = "sse"
            this.pushSource = "http"
            return
        } catch (e: Exception) {
            AdkLog.w(TAG, "SSE failed, falling back to HTTP poll: ${e.message}")
        }

        this.pullSource = "http"
        this.pushSource = "http"
        longPollClient.start(connection?.connectionId)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun connectWebSocket() = connectionMutex.withLock {
        if (isConnecting) return@withLock
        isConnecting = true

        val auth = Auth.getInstance(credentials)
        val authData = auth.authenticate()

        try {
            suspendCancellableCoroutine<Unit> { cont ->
                // Authenticate via headers, NOT via query parameters, so the
                // bearer token never lands in proxy / server access logs.
                val request = Request.Builder()
                    .url(Constant.WS_URL)
                    .addHeader("Authorization", "Bearer ${authData.accessToken}")
                    .addHeader("X-Org", credentials.orgTitle)
                    .addHeader("Environment", credentials.environment)
                    .addHeader("ProjectKey", credentials.projectKey)
                    .apply {
                        connection?.connectionId
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { addHeader("X-Connection-Id", it) }
                    }
                    .build()

                AdkLog.d(TAG, "Opening WebSocket to ${Constant.WS_URL}")

                websocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(ws: WebSocket, response: Response) {
                        isConnecting = false
                        // Connection is *open* but the protocol-level
                        // 'art_ready' has not yet arrived; do not flip
                        // isConnectionActive here — wait() listens for the
                        // connection event and resumes its callers then.
                        isConnectionActive = false
                        if (cont.isActive) cont.resume(Unit) {}
                    }

                    override fun onMessage(ws: WebSocket, text: String) {
                        socketScope.launch { parseIncomingMessage(text) }
                    }

                    override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                        AdkLog.e(TAG, "WebSocket failure: ${t.message}", t)
                        val isTimeout = t is java.net.SocketTimeoutException
                        if (isTimeout) {
                            AdkLog.w(TAG, "WebSocket ping timeout — will reconnect")
                        } else {
                            AdkLog.e(TAG, "WebSocket failure: ${t.message}", t)
                        }
                        isConnecting = false
                        isConnectionActive = false
                        if (cont.isActive) cont.resumeWith(Result.failure(t))
                        emit(Events.ERROR, t)
                        emit(Events.CLOSE, mapOf("type" to "failure"))
                    }

                    override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                        AdkLog.d(TAG, "WebSocket closed code=$code reason=$reason")
                        isConnectionActive = false
                        isConnecting = false
                        emit(
                            Events.CLOSE,
                            mapOf("type" to "closed", "code" to code, "reason" to reason)
                        )
                    }
                })
            }
        } catch (e: Exception) {
            isConnecting = false
            throw e
        }
    }

    /**
     * Performs a graceful WebSocket close. OkHttp does not support swapping
     * a listener after `newWebSocket`, so we use the existing listener's
     * `onClosed` callback (which already toggles `isConnectionActive`) and
     * just poll for completion under a timeout.
     */
    private suspend fun safeClose(timeout: Long = 1_000L) {
        val ws = websocket ?: return
        runCatching { ws.close(1000, "Normal Closure") }
        withTimeoutOrNull(timeout) {
            while (isConnectionActive) delay(20)
        }
        websocket = null
    }

    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun connectSSE() {
        val auth = Auth.getInstance(credentials)

        val authData = try {
            auth.authenticate()
        } catch (err: Exception) {
            AdkLog.e(TAG, "SSE authentication failed", err)
            emit(Events.CLOSE, mapOf("type" to "error"))
            return
        }

        val httpUrl = Constant.SSE_URL.toHttpUrl().newBuilder().build()

        val request = Request.Builder()
            .url(httpUrl)
            .addHeader("Accept", "text/event-stream")
            .addHeader("Authorization", "Bearer ${authData.accessToken}")
            .addHeader("X-Org", credentials.orgTitle)
            .addHeader("Environment", credentials.environment)
            .addHeader("ProjectKey", credentials.projectKey)
            .build()

        val openSignal = CompletableDeferred<Unit>()
        val factory = EventSources.createFactory(sseClient)
        factory.newEventSource(request, object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                isConnectionActive = true
                emit(Events.OPEN, Unit)
                if (!openSignal.isCompleted) openSignal.complete(Unit)
            }

            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                socketScope.launch { parseIncomingMessage(data) }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                emit(Events.ERROR, t)
                if (!openSignal.isCompleted) {
                    openSignal.completeExceptionally(
                        t ?: IllegalStateException("SSE failed to open")
                    )
                }
            }

            override fun onClosed(eventSource: EventSource) {
                isConnectionActive = false
            }
        })

        // Wait briefly for the SSE handshake. If it doesn't open in time we
        // throw, which lets the caller fall back to long-polling.
        withTimeoutOrNull(5_000) { openSignal.await() }
            ?: throw IllegalStateException("SSE handshake timed out")
    }

    private fun handleConnectionBinding(data: String) {
        setAutoReconnect(true)

        val json = JSONObject(data)
        connection = ConnectionDetail(
            connectionId = json.optString("connection_id"),
            instanceId = json.optString("instance_id"),
            tenantName = credentials.orgTitle,
            environment = credentials.environment,
            projectKey = credentials.projectKey
        )

        emit(Events.CONNECTION, connection)
        isConnectionActive = true

        startHeartbeat()
        drainPendingMessages()

        if (autoReconnect) {
            subscriptions.values.forEach { it.reconnect() }
            interceptors.values.forEach { it.reconnect() }
        }
    }

    private fun drainPendingMessages() {
        synchronized(pendingSendMessages) {
            if (pendingSendMessages.isEmpty()) return
            val ws = websocket ?: return
            val iterator = pendingSendMessages.iterator()
            while (iterator.hasNext()) {
                val msg = iterator.next()
                ws.send(msg)
                iterator.remove()
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
            "channel" to ReservedChannels.ART_SECURE,
            "content" to gson.toJson(data)
        )

        return if (listen) {
            suspendCancellableCoroutine { cont ->
                val key = "secure-$refId"
                secureCallbacks[key] = { response ->
                    if (cont.isActive) cont.resume(response)
                }
                cont.invokeOnCancellation { secureCallbacks.remove(key) }
                sendMessage(gson.toJson(message))
            }
        } else {
            sendMessage(gson.toJson(message))
            null
        }
    }

    override suspend fun removeSubscription(channel: String) {
        subscriptions.remove(channel)
    }

    suspend fun subscribe(channel: String): BaseSubscription = handleSubscription(channel)

    private suspend fun handleSubscription(channel: String): BaseSubscription {
        wait()

        val connectionId: String = connection?.connectionId ?: ""

        subscriptions[channel]?.let { existing ->
            existing.subscribe()
            return existing
        }

        val channelConfig = validateSubscription(channel, "subscribe")
            ?: throw IllegalStateException("Channel $channel not found")

        val subscription: BaseSubscription =
            if (channelConfig.channelType == ChannelTypes.SHARED_OBJECT) {
                LiveObjSubscription(connectionId, channelConfig, this, "subscribe")
            } else {
                Subscription(connectionId, channelConfig, this, "subscribe")
            }

        subscriptions[channel] = subscription

        pendingIncomingMessages.remove(channel)?.forEach { msg ->
            subscription.handleMessage(msg.event, msg.payload)
        }

        return subscription
    }

    suspend fun validateSubscription(channelName: String, process: String): ChannelConfig? {
        if (channelName in ReservedChannels.SYSTEM) {
            return ChannelConfig(
                channelName = channelName,
                channelNamespace = "",
                channelType = ChannelTypes.DEFAULT,
                presenceUsers = emptyList(),
                snapshot = null,
                subscriptionID = ""
            )
        }
        return subscribeToChannel(channelName, process, this)
    }

    override fun getConnection(): ConnectionDetail? = connection

    /**
     * Registers a server interceptor binding. Subsequent calls with the same
     * name return the existing instance.
     */
    suspend fun intercept(
        interceptor: String,
        fn: (payload: Any?, resolve: (Any?) -> Unit, reject: (Any?) -> Unit) -> Unit
    ): Interception {
        wait()

        interceptors[interceptor]?.let { return it }

        val interception = Interception(interceptor, fn, this)
        interception.validateInterception()
        interceptors[interceptor] = interception
        return interception
    }

    suspend fun parseIncomingMessage(message: String) {
        try {
            val type = object : TypeToken<Any>() {}.type
            val parsed = gson.fromJson<Any>(message, type)
            if (parsed is List<*>) {
                @Suppress("UNCHECKED_CAST")
                processIncomingMessages(parsed as List<Any>)
            } else {
                handleIncomingMessage(parsed)
            }
        } catch (e: Exception) {
            AdkLog.e(TAG, "Failed to parse JSON frame", e)
        }
    }

    suspend fun processIncomingMessages(messages: List<Any>) {
        messages.forEach { handleIncomingMessage(it) }
    }

    /**
     * Routes a parsed inbound message to the matching subscription or
     * interceptor. Buffers messages whose subscription has not yet been
     * registered.
     */
    suspend fun handleIncomingMessage(parsedMessage: Any) {
        try {
            @Suppress("UNCHECKED_CAST")
            val msg = parsedMessage as? MutableMap<String, Any?> ?: return

            val channel = msg["channel"] as? String
            val namespace = msg["namespace"] as? String
            val refId = msg["ref_id"] as? String
            val event = msg["event"] as? String
            val returnFlag = msg["return_flag"] as? String
            val interceptorName = msg["interceptor_name"] as? String
            val data = msg["content"]

            // ---- art_ready handshake ----
            if (channel == ReservedChannels.ART_READY && event == Events.READY) {
                handleConnectionBinding(data.toString())
                return
            }

            // ---- secure callback dispatch ----
            if (channel == ReservedChannels.ART_SECURE) {
                if (refId == null) {
                    AdkLog.e(TAG, "Secure message missing refId")
                    return
                }
                val key = "secure-$refId"
                val callback = secureCallbacks.remove(key)
                if (callback == null) {
                    AdkLog.e(TAG, "No callback for refId=$refId")
                    return
                }

                @Suppress("UNCHECKED_CAST")
                val parsedData: Map<String, Any?> = try {
                    when (data) {
                        is String -> gson.fromJson(
                            data,
                            object : TypeToken<Map<String, Any?>>() {}.type
                        )

                        is Map<*, *> -> data as Map<String, Any?>
                        else -> emptyMap()
                    }
                } catch (e: Exception) {
                    AdkLog.e(TAG, "Failed to parse secure content", e)
                    emptyMap()
                }

                callback.invoke(
                    mapOf(
                        "channel" to channel,
                        "namespace" to namespace,
                        "data" to parsedData,
                        "ref_id" to refId,
                        "event" to event
                    )
                )
                return
            }

            if (channel == null || (event == null && returnFlag != ReturnFlags.SERVER_ACK)) {
                AdkLog.w(TAG, "Received message without channel or event")
                return
            }

            if (event == Events.SHIFT_TO_HTTP) {
                switchToHttpPoll()
                return
            }

            if (event == Events.ERROR) {
                AdkLog.e(TAG, "Server error event received")
            }

            // Replace `content` -> `data` to match the rest of the SDK.
            msg.remove("content")
            msg["data"] = data

            // ---- interceptor routing ----
            if (!interceptorName.isNullOrEmpty()) {
                val interception = interceptors[interceptorName]
                if (interception != null) {
                    interception.handleMessage(channel, msg)
                } else {
                    AdkLog.w(TAG, "No Interception found for channel: $channel")
                }
                return
            }

            // ---- subscription routing ----
            // The subscription registry is keyed by the *bare* channel name,
            // so a namespaced lookup must fall back to the bare channel
            // when no namespaced subscription is registered. The original
            // port silently dropped namespaced messages.
            val namespacedKey = if (!namespace.isNullOrEmpty()) "$channel:$namespace" else channel
            val subscription = subscriptions[namespacedKey] ?: subscriptions[channel]

            if (subscription != null) {
                when (subscription) {
                    is Subscription -> subscription.handleMessage(event ?: "", msg)
                    is LiveObjSubscription -> {
                        @Suppress("UNCHECKED_CAST")
                        subscription.handleMessage(event ?: "", msg as MutableMap<String, Any>)
                    }

                    else -> subscription.handleMessage(event ?: "", msg)
                }
            } else {
                AdkLog.w(TAG, "No subscription for $namespacedKey, buffering")
                val arr = pendingIncomingMessages.getOrPut(namespacedKey) { mutableListOf() }
                synchronized(arr) { arr.add(IncomingMessage(event ?: "", msg)) }
            }

        } catch (e: Exception) {
            AdkLog.e(TAG, "Failed to handle incoming message", e)
        }
    }

    private fun switchToHttpPoll() {
        if (pullSource == "http") return
        pullSource = "http"
        pushSource = "http"
        longPollClient.start(connection?.connectionId.orEmpty())
    }

    /** Sends a frame on the WebSocket, queueing it if not yet connected. */
    override fun sendMessage(message: String) {
        val ws = websocket
        if (ws != null && isConnectionActive) {
            ws.send(message)
        } else {
            pendingSendMessages.add(message)
        }
    }

    fun setAutoReconnect(value: Boolean) {
        autoReconnect = value
    }

    /**
     * Closes the WebSocket and (optionally) clears every piece of session
     * state. Use [release] when you intend to dispose of the SDK instance.
     */
    suspend fun closeWebSocket(clearConnection: Boolean = false) {
        safeClose()
        isConnectionActive = false
        connection = null
        isConnecting = false

        heartbeatJob?.cancel()
        heartbeatJob = null

        if (clearConnection) {
            pendingIncomingMessages.clear()
            synchronized(pendingSendMessages) { pendingSendMessages.clear() }
            subscriptions.values.forEach { it.release() }
            subscriptions.clear()
            interceptors.values.forEach { it.release() }
            interceptors.clear()
            secureCallbacks.clear()
        }
    }

    /**
     * Releases every resource owned by this socket. After calling, the
     * instance must not be used again.
     */
    suspend fun release() {
        closeWebSocket(clearConnection = true)
        runCatching { longPollClient.release() }
        socketScope.cancel()
    }

    override suspend fun wait() {
        if (isConnectionActive) return
        suspendCancellableCoroutine<Unit> { cont ->
            lateinit var listener: (Any?) -> Unit
            listener = {
                off(Events.CONNECTION, listener)
                if (cont.isActive) cont.resume(Unit)
            }
            on(Events.CONNECTION, listener)
            cont.invokeOnCancellation { off(Events.CONNECTION, listener) }
        }
    }

    private fun heartbeatPayload(): Map<String, Any?> {
        val subList = subscriptions.map { (key, sub) ->
            mapOf(
                "name" to key,
                "presenceTracking" to sub.isListening
            )
        }
        return mapOf(
            "connectionId" to connection?.connectionId,
            "timestamp" to System.currentTimeMillis(),
            "subscriptions" to subList
        )
    }

    private fun startHeartbeat() {
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = socketScope.launch {
            while (isActive) {
                delay(30_000)
                if (!isConnectionActive) continue
                runCatching {
                    pushForSecureLine(Events.HEARTBEAT, heartbeatPayload(), false)
                }.onFailure { AdkLog.w(TAG, "Heartbeat failed", it) }
            }
        }
    }

    override suspend fun encrypt(data: Any, recipientPublicKey: String): String =
        encryptFn(data.toString(), recipientPublicKey)

    override suspend fun decrypt(encryptedHash: String, senderPublicKey: String): String =
        decryptFn(encryptedHash, senderPublicKey)

    data class IncomingMessage(val event: String, val payload: MutableMap<String, Any?>)
}
