package com.example.artlibrary.websockets

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.example.artlibrary.auth.Auth
import com.example.artlibrary.auth.AuthHttpError
import com.example.artlibrary.auth.isTerminalAuthError
import com.example.artlibrary.config.Constant
import com.example.artlibrary.types.AuthenticationConfig
import com.example.artlibrary.types.ChannelConfig
import com.example.artlibrary.types.ConnectionDetail
import com.example.artlibrary.types.ConnectionError
import com.example.artlibrary.types.IWebsocketHandler
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Kotlin port of the TypeScript `Socket`. In addition to the original
 * WebSocket -> SSE -> HTTP-poll fallback it carries the newer resilience layer:
 *
 *  - a terminal [ConnectionError] state ([failConnection]) with a `connectionFailed`
 *    event, so in-flight waiters reject instead of hanging;
 *  - a [connectionGeneration] counter so a stale connect attempt that resolves
 *    late is discarded rather than clobbering a newer one;
 *  - an authorization-lease refresh ([ensureAuthorization]) that re-leases the
 *    access token over HTTP without reopening the socket;
 *  - an HTTP push path ([sendViaHttp]) for when `pushSource == "http"`; and
 *  - `limitExceeded` / `authenticationFailed` events.
 */
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

    // --- newer resilience state (from the updated TS) ---
    private var authorizedAccessToken: String = ""
    private var authorizationRefreshInFlight: Deferred<Unit>? = null
    private var connectionFailure: ConnectionError? = null
    private var connectionGeneration: Int = 0
    private var eventSource: EventSource? = null
    private var connectionTimeoutMs: Long = 30_000

    // Maps a typed connectionFailed listener to the raw emitter wrapper so that
    // offConnectionFailure can remove it by the caller's own reference.
    private val connectionFailureWrappers =
        ConcurrentHashMap<(ConnectionError) -> Unit, (Any?) -> Unit>()

    companion object {
        @RequiresApi(Build.VERSION_CODES.O)
        fun create(
            encrypt: suspend (String, String) -> String, decrypt: suspend (String, String) -> String
        ): Socket {
            var socketRef: Socket? = null
            val lp = LongPollClient(
                opts = LongPollOptions(
                    endpoint = Constant.LPOLL,
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
                        if (isTerminalAuthError(err)) {
                            socketRef?.stopAfterAuthenticationFailure(err)
                        } else {
                            Log.e("LP error:", "$err")
                        }
                    })
            )
            val socket = Socket(encrypt, decrypt, lp)
            socketRef = socket
            return socket
        }
    }

    // ---------------------------------------------------------------------
    // Connection lifecycle / failure state
    // ---------------------------------------------------------------------

    fun beginConnection(timeoutMs: Long = 30_000) {
        require(timeoutMs > 0) { "ConnectionTimeoutMs must be positive" }
        connectionTimeoutMs = timeoutMs
        connectionFailure = null
        connectionGeneration++
    }

    fun failConnection(error: ConnectionError) {
        if (connectionFailure != null) return
        connectionFailure = error
        connectionGeneration++
        isConnectionActive = false
        isConnecting = false
        isReConnecting = false
        autoReconnect = false
        pendingSendMessges.clear()
        lpClient.stop()
        eventSource?.cancel()
        eventSource = null
        emit("connectionFailed", error)
        scope.launch { closeWebSocket() }
    }

    override fun onConnectionFailure(listener: (ConnectionError) -> Unit) {
        // The emitter stores (Any?) -> Unit listeners, so wrap the typed callback
        // and remember the wrapper against the caller's reference — that lets
        // offConnectionFailure remove exactly this registration later.
        val wrapper: (Any?) -> Unit = { error ->
            if (error is ConnectionError) {
                listener(error)
            }
        }
        connectionFailureWrappers[listener] = wrapper
        on("connectionFailed", wrapper)
    }

    override fun offConnectionFailure(listener: (ConnectionError) -> Unit) {
        val wrapper = connectionFailureWrappers.remove(listener) ?: return
        off("connectionFailed", wrapper)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun initiateSocket(credentials: AuthenticationConfig) {
        val generation = connectionGeneration
        connectionFailure?.let { throw it }
        if (websocket != null && isConnectionActive) return

        this.credentials = credentials

        try {
            connectWebSocket()
            pullSource = "socket"
            pushSource = "socket"
            return
        } catch (wsErr: Exception) {
            if (generation != connectionGeneration) throw (connectionFailure ?: wsErr)
            connectionFailure?.let { throw it }
            Log.w("Socket", "WebSocket failed, falling back to SSE", wsErr)
        }

        try {
            connectSSE()
            pullSource = "sse"
            pushSource = "http"   // SSE is pull only
            return
        } catch (sseErr: Exception) {
            if (generation != connectionGeneration) throw (connectionFailure ?: sseErr)
            connectionFailure?.let { throw it }
            Log.w("Socket", "SSE failed, falling back to HTTP poll", sseErr)
        }

        pullSource = "http"
        pushSource = "http"
        lpClient.start(connection?.ConnectionId)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun connectWebSocket() {
        val generation = connectionGeneration
        try {
            connectionFailure?.let { throw it }
            if (isConnecting) return
            isConnecting = true

            val auth = Auth.getInstance(credentials)
            val authData = try {
                auth.authenticate(forceAuth = isReConnecting)
            } catch (err: Exception) {
                if (generation != connectionGeneration) throw (connectionFailure ?: err)
                Log.e("Socket", "Authentication failed", err)
                isConnecting = false
                // Authentication is not a transport outage. A failed forced
                // refresh during reconnect must terminate that cycle instead of
                // entering an endless retry loop.
                if (isReConnecting || isTerminalAuthError(err)) {
                    stopAfterAuthenticationFailure(err)
                    throw connectionFailure!!
                }
                throw err
            }
            if (generation != connectionGeneration) {
                throw (connectionFailure
                    ?: ConnectionError("Connection attempt cancelled.", "CONNECTION_CANCELLED"))
            }

            val params = mapOf(
                "connection_id" to (connection?.ConnectionId ?: ""),
                "Org-Title" to credentials.orgTitle,
                "token" to authData.accessToken,
                "environment" to credentials.environment,
                "project-key" to credentials.projectKey
            )
            val query = params.map { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
                .joinToString("&")
            val fullWsUrl = "${Constant.WS_URL}?$query"
            Log.d("WS_URL", fullWsUrl)

            safeClose()
            if (generation != connectionGeneration) {
                throw (connectionFailure
                    ?: ConnectionError("Connection attempt cancelled.", "CONNECTION_CANCELLED"))
            }

            suspendCancellableCoroutine<Unit> { cont ->
                val request = Request.Builder().url(fullWsUrl).build()
                var timedOut = false

                val timer = scope.launch {
                    delay(5_000)
                    timedOut = true
                    websocket?.close(1000, "handshake timeout")
                    if (cont.isActive) cont.resumeWith(Result.failure(Exception("WebSocket handshake timeout")))
                }

                websocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(ws: WebSocket, response: Response) {
                        timer.cancel()
                        if (timedOut) return
                        if (generation != connectionGeneration) {
                            ws.close(1000, "cancelled")
                            if (cont.isActive) cont.resumeWith(
                                Result.failure(
                                    connectionFailure
                                        ?: ConnectionError(
                                            "Connection attempt cancelled.",
                                            "CONNECTION_CANCELLED"
                                        )
                                )
                            )
                            return
                        }
                        isConnecting = false
                        emit("open", response)
                        websocket = ws
                        authorizedAccessToken = authData.accessToken
                        // isConnectionActive stays false until art_ready binds.
                        if (cont.isActive) cont.resume(Unit) {}
                    }

                    override fun onMessage(ws: WebSocket, text: String) {
                        if (generation != connectionGeneration) return
                        scope.launch { parseIncomingMessage(text) }
                    }

                    override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                        if (timedOut || generation != connectionGeneration) return
                        timer.cancel()
                        Log.e("Socket", "Live error", t)
                        isConnecting = false
                        isConnectionActive = false
                        emit("close", mapOf("type" to "error"))
                        if (cont.isActive) cont.resumeWith(Result.failure(Exception("WebSocket connection error")))
                    }

                    override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                        if (timedOut || generation != connectionGeneration) return
                        timer.cancel()
                        isConnecting = false
                        isConnectionActive = false
                        if (generation == connectionGeneration) {
                            emit("close", mapOf("code" to code, "reason" to reason))
                        }
                        if (cont.isActive) cont.resumeWith(Result.failure(Exception("WebSocket closed with code $code")))
                    }
                })
            }
        } catch (e: Exception) {
            if (generation == connectionGeneration) {
                isConnecting = false
                isConnectionActive = false
            }
            throw e
        }
    }

    private suspend fun safeClose(timeout: Long = 1000L) {
        val ws = websocket ?: return
        websocket = null
        ws.close(1000, "Normal Closure")
        withTimeoutOrNull(timeout) {
            suspendCancellableCoroutine<Unit> { continuation ->
                val closed = object : WebSocketListener() {
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        if (continuation.isActive) continuation.resume(Unit) {}
                    }
                }
                // Best-effort: OkHttp has already been asked to close above; if it
                // never reports closed, the outer timeout resolves this.
                try {
                    ws.close(1000, "Normal Closure")
                } catch (_: Exception) {
                    if (continuation.isActive) continuation.resume(Unit) {}
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun connectSSE() {
        val generation = connectionGeneration
        val auth = Auth.getInstance(credentials)

        val authData = try {
            auth.authenticate()
        } catch (err: Exception) {
            if (generation != connectionGeneration) throw (connectionFailure ?: err)
            Log.e("Socket", "Authentication failed", err)
            if (isTerminalAuthError(err)) {
                stopAfterAuthenticationFailure(err)
                throw connectionFailure!!
            }
            throw err
        }
        if (generation != connectionGeneration) {
            throw (connectionFailure
                ?: ConnectionError("Connection attempt cancelled.", "CONNECTION_CANCELLED"))
        }

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

        val factory = EventSources.createFactory(OkHttpClient())
        eventSource = factory.newEventSource(request, object : EventSourceListener() {
            override fun onOpen(es: EventSource, response: Response) {
                emit("open", response)
            }

            override fun onEvent(es: EventSource, id: String?, type: String?, data: String) {
                if (generation != connectionGeneration) return
                scope.launch { parseIncomingMessage(data) }
            }

            override fun onFailure(es: EventSource, t: Throwable?, response: Response?) {
                emit("error", t)
            }

            override fun onClosed(es: EventSource) {
                isConnectionActive = false
                es.cancel()
            }
        })
    }

    private fun handleConnectionBinding(data: String) {
        if (connectionFailure != null) return
        setAutoReconnect(true)

        val json = JSONObject(data)
        connection = ConnectionDetail(
            ConnectionId = json.getString("connection_id"),
            InstanceId = json.optString("instance_id"),
            TenantName = credentials.orgTitle,
            Environment = credentials.environment,
            ProjectKey = credentials.projectKey
        )

        Log.d("Socket", "Live connection opened ${connection?.ConnectionId}")
        isConnectionActive = true
        isReConnecting = false
        emit("connection", connection)
        startHeartbeat()

        if (autoReconnect) {
            scope.launch {
                subscriptions.forEach { (_, subscriptionInstance) -> subscriptionInstance.reconnect() }
                interceptors.forEach { (_, interceptorInstance) -> interceptorInstance.reconnect() }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Secure line / authorization
    // ---------------------------------------------------------------------

    @RequiresApi(Build.VERSION_CODES.O)
    override suspend fun pushForSecureLine(event: String, data: Any?, listen: Boolean): Any? {
        waitForConnection()

        val refId = "${connection?.ConnectionId}_secure_${System.currentTimeMillis()}_${
            UUID.randomUUID().toString().take(6)
        }"

        val message = mapOf(
            "from" to (connection?.ConnectionId ?: ""),
            "channel" to "art_secure",
            "event" to event,
            "content" to gson.toJson(data ?: emptyMap<String, Any>()),
            "ref_id" to refId
        )

        return if (listen) {
            suspendCancellableCoroutine { cont ->
                val key = "secure-$refId"
                lateinit var onFailure: (Any?) -> Unit
                lateinit var timerJob: Job
                val cleanup = {
                    timerJob.cancel()
                    off("connectionFailed", onFailure)
                    secureCallbacks.remove(key)
                }
                onFailure = { err ->
                    cleanup()
                    if (cont.isActive) cont.resumeWith(Result.failure(err as ConnectionError))
                }
                timerJob = scope.launch {
                    delay(connectionTimeoutMs)
                    onFailure(
                        ConnectionError(
                            "The connection did not respond in time. Please reconnect and try again.",
                            "CONNECTION_TIMEOUT"
                        )
                    )
                }
                on("connectionFailed", onFailure)
                secureCallbacks[key] = { reply ->
                    cleanup()
                    if (cont.isActive) cont.resume(reply)
                }
                try {
                    sendMessage(gson.toJson(message))
                } catch (error: Exception) {
                    failConnection(
                        if (error is ConnectionError) error
                        else ConnectionError("The connection was lost.", "CONNECTION_LOST")
                    )
                    cleanup()
                    if (cont.isActive) cont.resumeWith(Result.failure(error))
                }
            }
        } else {
            sendMessage(gson.toJson(message))
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override suspend fun ensureAuthorization() {
        waitForConnection()
        authorizationRefreshInFlight?.let { return it.await() }

        val auth = Auth.getInstance(credentials)
        val accessToken = try {
            auth.authenticate().accessToken
        } catch (error: Exception) {
            stopAfterAuthenticationFailure(error)
            throw connectionFailure!!
        }
        connectionFailure?.let { throw it }

        if (accessToken == authorizedAccessToken) return

        val pending = scope.async { refreshAuthorizationLease(accessToken) }
        authorizationRefreshInFlight = pending
        try {
            pending.await()
        } finally {
            authorizationRefreshInFlight = null
        }
    }

    private suspend fun refreshAuthorizationLease(accessToken: String) {
        val connectionId = connection?.ConnectionId
            ?: throw Exception("Connection is not ready for authorization refresh")

        val body = JSONObject()
            .put("channel", "art_secure")
            .put("event", "authorization-refresh")
            .put("content", "{}")
            .put("connection_id", connectionId)
            .put("to", JSONArray())
            .toString()

        val request = Request.Builder()
            .url("${Constant.BASE_URL}/v1/push-message")
            .post(body.toRequestBody("application/json".toMediaType()))
            .addHeader("Authorization", "Bearer $accessToken")
            .addHeader("Accept", "application/json")
            .addHeader("X-Org", credentials.orgTitle)
            .addHeader("Environment", credentials.environment)
            .addHeader("ProjectKey", credentials.projectKey)
            .build()

        val response = try {
            withTimeout(connectionTimeoutMs) {
                withContext(Dispatchers.IO) { okHttpClient.newCall(request).execute() }
            }
        } catch (e: TimeoutCancellationException) {
            throw ConnectionError(
                "Connection authorization timed out. Please reconnect.",
                "CONNECTION_AUTHORIZATION_FAILED"
            )
        } catch (e: Exception) {
            throw ConnectionError(
                "Connection authorization failed: ${e.message ?: "network error"}",
                "CONNECTION_AUTHORIZATION_FAILED"
            )
        }

        response.use { resp ->
            if (!resp.isSuccessful) {
                val bodyStr = resp.body?.string()
                val json = runCatching { JSONObject(bodyStr ?: "") }.getOrNull()
                val error = AuthHttpError(
                    json?.optString("message").takeUnless { it.isNullOrEmpty() }
                        ?: "HTTP ${resp.code} ${resp.message}",
                    resp.code,
                    bodyStr
                )
                if (isTerminalAuthError(error)) stopAfterAuthenticationFailure(error)
                throw error
            }
            authorizedAccessToken = accessToken
        }
    }

    private fun stopAfterAuthenticationFailure(error: Any?) {
        val message = (error as? Throwable)?.message
        val failure = ConnectionError(
            message ?: "Authentication failed. Please sign in again.",
            "AUTHENTICATION_FAILED",
            (error as? AuthHttpError)?.status
        )
        failConnection(failure)
        emit(
            "authenticationFailed",
            mapOf(
                "status" to (if (isTerminalAuthError(error)) (error as AuthHttpError).status else 0),
                "error" to (message ?: "Authentication refresh failed")
            )
        )
    }

    // ---------------------------------------------------------------------
    // Subscriptions / interceptors
    // ---------------------------------------------------------------------

    override fun removeSubscription(channel: String) {
        subscriptions.remove(channel)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun subscribe(channel: String): BaseSubscription {
        return handleSubscription(channel)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun handleSubscription(channel: String): BaseSubscription {
        waitForConnection()
        ensureAuthorization()
        val connectionId: String = connection?.ConnectionId ?: ""

        if (subscriptions.containsKey(channel)) {
            val sub = subscriptions[channel]!!
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
            for (msg in buf) deliver(subscription, msg.event, msg.payload)
            pendingIncomingMessages.remove(channel)
        }

        return subscription
    }

    suspend fun validateSubscription(channelName: String, process: String): ChannelConfig? {
        if (listOf("art_config", "art_secure", "art_notifications").contains(channelName)) {
            return ChannelConfig(
                channelName = channelName,
                channelNamespace = "",
                channelType = "default",
                presenceUsers = emptyList(),
                snapshot = null,
                subscriptionID = "",
                orchestratorEnabled = false
            )
        }
        return try {
            subscribeToChannel(channelName, process, this)
        } catch (e: Exception) {
            throw e
        }
    }

    override fun getConnection(): ConnectionDetail? = connection


    suspend fun intercept(
        interceptor: String,
        fn: (payload: Any?, resolve: (Any?) -> Unit, reject: (Any?) -> Unit) -> Unit
    ): Interception {
        waitForConnection()
        if (interceptors.containsKey(interceptor)) return interceptors[interceptor]!!

        val interception = Interception(interceptor, fn, this)
        interception.validateInterception()
        interceptors[interceptor] = interception
        return interception
    }

    // ---------------------------------------------------------------------
    // Incoming messages
    // ---------------------------------------------------------------------

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
            Log.e("Socket", "Failed to parse JSON", e)
        }
    }

    suspend fun processIncomingMessages(messages: List<Any?>) {
        messages.forEach { it?.let { m -> handleIncomingMessage(m) } }
    }

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

            if (channel == "art_ready" && event == "ready") {
                handleConnectionBinding(data.toString())
                return
            } else if (channel == "art_secure") {
                if (refId == null) {
                    Log.e("SECURE_ERROR", "refId is NULL: $msg")
                    return
                }
                val key = "secure-$refId"
                val callback = secureCallbacks[key]
                if (callback != null) {
                    val parsedData: Map<String, Any?> = try {
                        when (data) {
                            is String -> gson.fromJson(
                                data,
                                object : TypeToken<Map<String, Any?>>() {}.type
                            )

                            is Map<*, *> -> {
                                @Suppress("UNCHECKED_CAST")
                                data as Map<String, Any?>
                            }

                            else -> emptyMap()
                        }
                    } catch (e: Exception) {
                        Log.e("PARSE_ERROR", "Failed to parse content: $data", e)
                        emptyMap()
                    }
                    val response = mapOf(
                        "channel" to channel,
                        "namespace" to namespace,
                        "data" to parsedData,
                        "ref_id" to refId,
                        "event" to event
                    )
                    callback.invoke(response)
                    secureCallbacks.remove(key)
                }
                return
            }

            // Connection / concurrency limit block (no channel).
            val code = msg["code"] as? String
            if (channel == null &&
                (code == "CONNECTION_MINUTES_LIMIT_EXCEEDED" || code == "CONCURRENT_LIMIT_EXCEEDED")
            ) {
                Log.e(
                    "Socket",
                    if (code == "CONCURRENT_LIMIT_EXCEEDED")
                        "Concurrent connection limit reached. Reconnection blocked."
                    else "Connection minutes limit exceeded. Reconnection blocked."
                )
                emit("limitExceeded", mapOf("code" to code, "error" to msg["error"]))
                failConnection(
                    ConnectionError(
                        (msg["error"] as? String) ?: "Your connection limit has been reached.",
                        code
                    )
                )
                return
            }

            if (channel == null || (event == null && returnFlag != "SA")) {
                Log.w("Socket", "Received message without channel or event: $msg")
                return
            }

            if (event == "shift_to_http") {
                switchToHttpPoll()
                return
            }

            if (event == "error") {
                Log.e("Socket", "Received error message: $msg")
            }

            msg.remove("content")
            msg["data"] = data

            if (!interceptorName.isNullOrEmpty()) {
                val interception = interceptors[interceptorName]
                if (interception != null) {
                    interception.handleMessage(channel, msg)
                } else {
                    Log.w("Socket", "No Interception found for channel: $channel")
                }
            } else {
                var subscriptionKey = channel
                if (!namespace.isNullOrEmpty()) {
                    subscriptionKey += ":$namespace"
                }
                val subscription = subscriptions[subscriptionKey]
                if (subscription != null) {
                    deliver(subscription, event ?: "", msg)
                } else {
                    Log.w(
                        "Socket",
                        "No subscription found for channel: $subscriptionKey, adding to buffer"
                    )
                    val arr = pendingIncomingMessages.getOrPut(subscriptionKey) { mutableListOf() }
                    arr.add(IncomingMessage(event ?: "", msg))
                }
            }
        } catch (e: Exception) {
            Log.e("Socket", "Failed to parse incoming message", e)
        }
    }

    /**
     * Hands a frame to the subscription's real handler. `Subscription.handleMessage`
     * is a suspend overload taking the frame map — not an override of the
     * `BaseSubscription.handleMessage(event, Any)` no-op — so calling it through the
     * `BaseSubscription` type runs the empty base method and silently drops the frame.
     * The smart cast below selects the `Subscription` overload;
     * `LiveObjSubscription` overrides the base method, so plain dispatch reaches it.
     */
    private suspend fun deliver(subscription: BaseSubscription, event: String, payload: Any) {
        if (subscription is Subscription) {
            @Suppress("UNCHECKED_CAST")
            val frame = payload as? MutableMap<String, Any?> ?: return
            subscription.handleMessage(event, frame)
        } else {
            subscription.handleMessage(event, payload)
        }
    }

    private fun switchToHttpPoll() {
        if (pullSource == "http") return
        Log.i("Socket", "Shifting pullSource -> http, pushSource -> http")
        pullSource = "http"
        pushSource = "http"
        lpClient.start(connection?.ConnectionId ?: "")
    }

    // ---------------------------------------------------------------------
    // Sending
    // ---------------------------------------------------------------------

    @RequiresApi(Build.VERSION_CODES.O)
    override fun sendMessage(message: String) {
        connectionFailure?.let { throw it }

        if (pushSource == "http") {
            val connectionId = connection?.ConnectionId
            if (connectionId == null || !isConnectionActive) {
                throw ConnectionError(
                    "The connection is not ready. Please reconnect.",
                    "CONNECTION_LOST"
                )
            }
            val generation = connectionGeneration
            // Dispatch once; a timeout does not establish whether it ran, so never
            // queue or replay the operation after reconnecting.
            scope.launch {
                try {
                    sendViaHttp(message, connectionId, generation)
                } catch (error: Exception) {
                    if (generation != connectionGeneration) return@launch
                    failConnection(
                        if (error is ConnectionError) error
                        else ConnectionError(
                            "Could not send the request: ${error.message ?: "network error"}",
                            "CONNECTION_SEND_FAILED"
                        )
                    )
                }
            }
            return
        }

        val ws = websocket
        if (ws != null && isConnectionActive) {
            ws.send(message)
        } else {
            // Never queue an execution for silent replay after reconnection.
            throw ConnectionError(
                "The connection was lost before the message could be sent. Please reconnect.",
                "CONNECTION_LOST"
            )
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun sendViaHttp(message: String, connectionId: String, generation: Int) {
        val payload = JSONObject(message)
        val auth = Auth.getInstance(credentials)
        val accessToken = auth.authenticate().accessToken
        if (generation != connectionGeneration || connectionId != connection?.ConnectionId) return

        payload.put("connection_id", connectionId)

        val request = Request.Builder()
            .url("${Constant.BASE_URL}/v1/push-message")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .addHeader("Authorization", "Bearer $accessToken")
            .addHeader("Content-Type", "application/json")
            .addHeader("X-Org", credentials.orgTitle)
            .addHeader("Environment", credentials.environment)
            .addHeader("ProjectKey", credentials.projectKey)
            .build()

        val response = try {
            withTimeout(connectionTimeoutMs) {
                withContext(Dispatchers.IO) { okHttpClient.newCall(request).execute() }
            }
        } catch (e: TimeoutCancellationException) {
            throw ConnectionError(
                "The request timed out. Check its outcome before trying again.",
                "CONNECTION_TIMEOUT"
            )
        }

        response.use { resp ->
            if (!resp.isSuccessful) {
                val bodyStr = resp.body?.string()
                val json = runCatching { JSONObject(bodyStr ?: "") }.getOrNull()
                val errorMessage = json?.optString("error").takeUnless { it.isNullOrEmpty() }
                    ?: json?.optString("message").takeUnless { it.isNullOrEmpty() }
                val errorCode = json?.optString("error_code").takeUnless { it.isNullOrEmpty() }
                    ?: json?.optString("code").takeUnless { it.isNullOrEmpty() }
                    ?: "CONNECTION_SEND_FAILED"
                throw ConnectionError(
                    errorMessage ?: "Request failed (HTTP ${resp.code}).",
                    errorCode,
                    resp.code
                )
            }
        }
    }

    // ---------------------------------------------------------------------
    // Teardown / misc
    // ---------------------------------------------------------------------

    fun setAutoReconnect(value: Boolean) {
        autoReconnect = value
    }

    suspend fun closeWebSocket(clearConnection: Boolean = false) {
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
            it.cancel()
            heartbeatInterval = null
        }
        safeClose()
    }

    override suspend fun waitForConnection() {
        connectionFailure?.let { throw it }
        if (isConnectionActive) return

        suspendCancellableCoroutine<Unit> { cont ->
            lateinit var onConn: (Any?) -> Unit
            lateinit var onFailure: (Any?) -> Unit
            lateinit var timerJob: Job
            val cleanup = {
                timerJob.cancel()
                off("connection", onConn)
                off("connectionFailed", onFailure)
            }
            onConn = {
                cleanup()
                if (cont.isActive) cont.resume(Unit) {}
            }
            onFailure = { err ->
                cleanup()
                if (cont.isActive) cont.resumeWith(Result.failure(err as ConnectionError))
            }
            timerJob = scope.launch {
                delay(connectionTimeoutMs)
                failConnection(
                    ConnectionError(
                        "Unable to establish a connection. Please check your connection and try again.",
                        "CONNECTION_TIMEOUT"
                    )
                )
            }
            on("connection", onConn)
            on("connectionFailed", onFailure)
        }
    }

    private fun runHeartBeatPayload(): Map<String, Any?> {
        val subList = subscriptions.map { (key, sub) ->
            mapOf(
                "name" to key,
                "presenceTracking" to (sub.isListening ?: false)
            )
        }
        return mapOf(
            "connectionId" to connection?.ConnectionId,
            "timestamp" to System.currentTimeMillis(),
            "subscriptions" to subList
        )
    }

    private fun startHeartbeat() {
        if (heartbeatInterval != null) return
        heartbeatInterval = Timer()
        heartbeatInterval?.schedule(object : TimerTask() {
            @RequiresApi(Build.VERSION_CODES.O)
            override fun run() {
                if (!isConnectionActive) return
                scope.launch {
                    try {
                        pushForSecureLine("heartbeat", runHeartBeatPayload(), false)
                    } catch (_: Exception) {
                        // Lifecycle errors are delivered through connectionFailed.
                    }
                }
            }
        }, 30000, 30000)
    }

    override suspend fun encrypt(data: String, recipientPublicKey: String): String =
        encryptFn(data, recipientPublicKey)

    override suspend fun decrypt(encryptedHash: String, senderPublicKey: String): String =
        decryptFn(encryptedHash, senderPublicKey)

    data class IncomingMessage(val event: String, val payload: Any)
}
