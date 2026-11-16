package com.example.artlibrary.websockets

import android.util.Log
import com.example.artlibrary.types.ChannelConfig
import com.example.artlibrary.types.ConnectionError
import com.example.artlibrary.types.IWebsocketHandler
import com.example.artlibrary.types.PushConfig
import com.example.artlibrary.websockets.subscribeToChannel
import com.example.artlibrary.websockets.unsubscribeFromChannel
import com.google.gson.Gson
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

open class BaseSubscription(
    val connectionID: String,
    var channelConfig: ChannelConfig,
    protected val websocketHandler: IWebsocketHandler,
    process: String = "subscribe",
) : EventEmitter() {

    var isSubscribed = false
    var isListening = false

    val messageBuffer =
        mutableMapOf<String, MutableList<MutableMap<String, Any?>>>()
    protected val threadBuffers =
        mutableMapOf<String, MutableMap<String, MutableList<MutableMap<String, Any?>>>>()

    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val pendingAcks =
        ConcurrentHashMap<String, Pair<CompletableDeferred<Any?>, Job>>()
    protected val ACK_TIMEOUT = 50_000L
    protected var messageCount = 0
    protected var presenceUsers: MutableList<String> =
        channelConfig.presenceUsers.toMutableList()

    init {
        when (process) {
            "subscribe" -> isSubscribed = true
            "presence" -> isListening = true
        }
    }

    fun onConnectionFailure(listener: (error: ConnectionError) -> Unit) {
        websocketHandler.onConnectionFailure(listener)
    }

    fun offConnectionFailure(listener: (error: ConnectionError) -> Unit) {
        websocketHandler.offConnectionFailure(listener)
    }

    open suspend fun validateSubscription(process: String) {
        try {
            if (channelConfig.channelName in listOf("art_config", "art_secure")) return
            var channelName = channelConfig.channelName
            if (channelConfig.channelNamespace.isNotBlank()) {
                channelName += ":" + channelConfig.channelNamespace
            }
            try {
                val config = subscribeToChannel(channelName, process, websocketHandler)
                channelConfig = config
                if (process == "presence") {
                    isListening = true
                }
            } catch (error: Exception) {
                Log.e("BaseSubscription", "$error")
            }
        } catch (error: Exception) {
            Log.e("BaseSubscription", "$error")
        }
    }

    /**
     * Check for presence on the channel.
     * @param callback Callback to handle the presence data.
     * @throws Error if not subscribed for presence.
     */
    suspend fun fetchPresence(
        callback: (List<String>) -> Unit,
        options: PresenceConfig = PresenceConfig(unique = true)
    ): PresenceSubscription {
        var previousPresenceData: List<String> = presenceUsers
        if (previousPresenceData.isNotEmpty()) {
            callback(previousPresenceData)
        }
        validateSubscription("presence")
        if (!isListening) {
            throw IllegalStateException("Not subscribed for presence")
        }
        on("art_presence") { payload ->
            val map = payload as? Map<*, *>
            val error = map?.get("error")
            if (error == null) {
                val usernames = (map?.get("usernames") as? List<*>)
                    ?.filterIsInstance<String>() ?: emptyList()
                previousPresenceData = usernames
                presenceUsers = usernames.toMutableList()
                // ['b:69a1332c...', 'a:69a132f8...', 'a:69a13318...']
                var userResponse = mutableListOf<String>()
                if (options.unique) {
                    usernames.forEach { user ->
                        val userName = user.split(":").getOrElse(0) { user }
                        if (userResponse.indexOf(userName) < 0) {
                            userResponse.add(userName)
                        }
                    }
                } else {
                    userResponse = usernames.toMutableList()
                }
                callback(userResponse)
            }
        }
        scope.launch { runCatching { push("art_presence", emptyMap<String, Any?>()) } }
        return PresenceSubscription(unsubscribe = {
            val success = unsubscribeFromChannel(
                channelConfig.channelName,
                channelConfig.subscriptionID ?: "",
                "presence",
                websocketHandler
            )
            if (!success) {
                Log.e("BaseSubscription", "Failed to unsubscribe from channel ${channelConfig.channelName}")
            }
        })
    }

    fun acknowledge(request: MutableMap<String, Any?>, res: String) {
        if (channelConfig.channelType != "targeted" && channelConfig.channelType != "secure") {
            return
        }
        val channel = request["channel"] as? String
        if (channel in listOf("art_config", "art_secure", "art_presence")) {
            return
        }
        val response = mapOf(
            "channel" to channel,
            "namespace" to request["namespace"],
            "id" to request["id"],
            "ref_id" to request["ref_id"],
            "from" to request["from"],
            "to_username" to request["to_username"],
            "to" to request["to"],
            "return_flag" to res,
            "pipeline_id" to request["pipeline_id"],
            "interceptor_name" to request["interceptor_name"],
            "attempt_id" to request["attempt_id"]
        )
        websocketHandler.sendMessage(Gson().toJson(response))
    }

    fun handleMessageAcks(event: String, returnFlag: String, data: MutableMap<String, Any?>) {
        if (returnFlag == "SA") {
            val refId = data["ref_id"] as? String ?: return
            val entry = pendingAcks[refId] ?: return
            entry.second.cancel()
            entry.first.complete(refId)
            pendingAcks.remove(refId)
        }
    }

    open suspend fun subscribe() {
        if (channelConfig.channelName in listOf("art_config", "art_secure", "art_notifications")) {
            return
        }
        isSubscribed = true
        try {
            channelConfig = subscribeToChannel(channelConfig.channelName, "subscribe", websocketHandler)
        } catch (error: Exception) {
            Log.e("BaseSubscription", "$error")
            isSubscribed = false
            throw error
        }
    }

    open suspend fun unsubscribe() {
        val subId = channelConfig.subscriptionID ?: return
        val success = unsubscribeFromChannel(
            channelConfig.channelName,
            subId,
            "subscribe",
            websocketHandler
        )
        if (!success) {
            Log.e("BaseSubscription", "Failed to unsubscribe from channel ${channelConfig.channelName}")
        } else {
            websocketHandler.removeSubscription(channelConfig.channelName)
        }
    }

    open fun reconnect() {
        if (channelConfig.channelName != "art_config" && channelConfig.channelName != "art_secure") {
            if (isListening) {
                scope.launch { validateSubscription("presence") }
            }
            scope.launch {
                runCatching { subscribe() }
                    .onFailure { Log.e("BaseSubscription", "Channel reconnection failed: $it") }
            }
        }
    }

    /**
     * Push a message to the channel with a specific event and data.
     * @param event The event name.
     * @param data The data to send.
     */
    open suspend fun push(event: String, data: Any, options: PushConfig? = null): Any? {
        websocketHandler.waitForConnection()
        websocketHandler.ensureAuthorization()

        val connection = websocketHandler.getConnection()
        val to = options?.to ?: emptyList<String>()
        val threadId = options?.ThreadID
        var messageStr = gson.toJson(data)

        if (channelConfig.channelType == "secure" || channelConfig.channelType == "targeted") {
            if ((to.isEmpty() || to.size != 1) && event != "art_presence") {
                throw IllegalArgumentException("Exactly one user must be specified for sending message.")
            }
        }

        if (channelConfig.channelType == "secure" && event != "art_presence") {
            val secureRes = websocketHandler.pushForSecureLine(
                "secured_public_key",
                mapOf("username" to to[0]),
                true
            )
            val resData = (secureRes as? Map<*, *>)?.get("data") as? Map<*, *>
                ?: throw Exception("Missing data in secure response")
            if (resData["status"]?.toString() == "unsuccessfull") {
                throw Exception(resData["error"]?.toString() ?: "")
            }
            messageStr = websocketHandler.encrypt(messageStr, resData["public_key"].toString())
        }

        var refId: String? = null
        val ackDeferred = CompletableDeferred<Any?>()
        var rejectAck: ((ConnectionError) -> Unit)? = null

        if (channelConfig.channelName !in listOf("art_config", "art_secure", "art_presence")) {
            messageCount++
            refId = "${websocketHandler.getConnection()?.ConnectionId}_${channelConfig.channelName}_$messageCount"
            if (channelConfig.channelType == "targeted" || channelConfig.channelType == "secure") {
                val rid = refId
                val timer = scope.launch {
                    delay(ACK_TIMEOUT)
                    pendingAcks.remove(rid)
                    ackDeferred.completeExceptionally(
                        ConnectionError(
                            "The server has not acknowledged this request. Check its status before retrying.",
                            "ACK_TIMEOUT"
                        )
                    )
                }
                rejectAck = { error -> ackDeferred.completeExceptionally(error) }
                pendingAcks[rid] = Pair(ackDeferred, timer)
            } else {
                ackDeferred.complete(refId)
            }
        } else {
            ackDeferred.complete(null)
        }

        val message = mutableMapOf<String, Any?>(
            "from" to (connection?.ConnectionId ?: ""),
            "to" to to,
            "channel" to channelConfig.channelName +
                (if (channelConfig.channelNamespace.isNotBlank()) ":${channelConfig.channelNamespace}" else ""),
            "event" to event,
            "content" to messageStr,
            "ref_id" to refId,
            "thread_id" to threadId
        )
        options?.FileMeta?.let { if (it.isNotEmpty()) message["file_meta"] = it }
        options?.ExecutionOrigin?.let { message["execution_origin"] = it }

        val onFailure: (ConnectionError) -> Unit = { error -> rejectAck?.invoke(error) }
        onConnectionFailure(onFailure)
        return try {
            websocketHandler.sendMessage(Gson().toJson(message))
            ackDeferred.await()
        } finally {
            offConnectionFailure(onFailure)
            refId?.let { rid ->
                pendingAcks[rid]?.let { it.second.cancel() }
                pendingAcks.remove(rid)
            }
        }
    }

    open fun handleMessage(event: String, payload: Any) {}

    open fun release() {
        scope.cancel()
        pendingAcks.values.forEach { (deferred, timer) ->
            timer.cancel()
            deferred.completeExceptionally(Exception("Subscription released"))
        }
        pendingAcks.clear()
    }
}

data class PresenceConfig(
    val unique: Boolean = true
)

/** Handle returned by [BaseSubscription.fetchPresence]; mirrors the TS `{ unsubscribe }`. */
class PresenceSubscription(val unsubscribe: suspend () -> Unit)

/* ---------------- Helpers ---------------- */


