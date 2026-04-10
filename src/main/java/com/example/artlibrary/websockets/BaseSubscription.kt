import android.util.Log
import com.example.artlibrary.types.ChannelConfig
import com.example.artlibrary.types.IWebsocketHandler
import com.example.artlibrary.types.PushConfig
import com.example.artlibrary.websockets.EventEmitter
import com.example.artlibrary.websockets.subscribeToChannel
import com.example.artlibrary.websockets.unsubscribeFromChannel
import com.google.gson.Gson
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap


open class BaseSubscription(
    val connectionID: String,
    var channelConfig: ChannelConfig,
    protected val websocketHandler: IWebsocketHandler,
    process: String = "subscribe",
) : EventEmitter() {

    val messageBuffer =
        mutableMapOf<String, MutableList<MutableMap<String, Any?>>>()

    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var isSubscribed = false
    var isListening = false

    private val ACK_TIMEOUT = 50_000L
    private var messageCount = 0

    // FIX: Store both the deferred and the timer Job together, matching JS's { resolve, reject, timer }
    private val pendingAcks =
        ConcurrentHashMap<String, Pair<CompletableDeferred<Any?>, Job>>()

    var presenceUsers =
        channelConfig.presenceUsers.toMutableList()

    init {
        when (process) {
            "subscribe" -> isSubscribed = true
            "presence" -> isListening = true
        }
    }

    /* ---------------- Subscription ---------------- */

    open suspend fun subscribe() {
        if (channelConfig.channelName in listOf("art_config", "art_secure"))
            return

        // FIX: Removed early `if (isSubscribed) return` guard — JS sets isSubscribed = true then proceeds
        isSubscribed = true

        try {
            val updatedConfig = subscribeToChannel(
                channelConfig.channelName,
                "subscribe", this.websocketHandler
            )
            channelConfig = updatedConfig
            Log.d("subscribeToChannel", "")
        } catch (error: Exception) {
            Log.e("BaseSubscription", "Subscribe error: ${error.message}")
            isSubscribed = false
        }
    }

    open suspend fun unsubscribe() {
        val subId = channelConfig.subscriptionID ?: return

        try {
            val success = unsubscribeFromChannel(
                channelConfig.channelName,
                subId,
                "subscribe", this.websocketHandler
            )
            if (success) {
                websocketHandler.removeSubscription(channelConfig.channelName)
            } else {
                Log.e(
                    "BaseSubscription",
                    "Failed to unsubscribe from channel ${channelConfig.channelName}"
                )
            }
        } catch (error: Exception) {
            Log.e("BaseSubscription", "Unsubscribe error: ${error.message}")
        }
    }

    open fun reconnect() {
        if (channelConfig.channelName !in listOf("art_config", "art_secure")) {
            if (isListening) {
                scope.launch { validateSubscription("presence") }
            }
            scope.launch { subscribe() }
        }
    }

    /* ---------------- Validate ---------------- */

    open suspend fun validateSubscription(process: String) {
        if (channelConfig.channelName in listOf("art_config", "art_secure"))
            return

        var channelName = channelConfig.channelName
        channelConfig.channelNamespace.takeIf { it.isNotBlank() }?.let {
            channelName += ":$it"
        }

        try {
            val updatedConfig = subscribeToChannel(
                channelName,
                process,
                this.websocketHandler
            )
            channelConfig = updatedConfig
            if (process == "presence") {
                isListening = true
            }
        } catch (error: Exception) {
            Log.e("BaseSubscription", "Validate subscription error: ${error.message}")
        }
    }

    /* ---------------- Presence ---------------- */

    data class PresenceConfig(
        val unique: Boolean = true
    )

    /**
     * Check for presence on the channel.
     * @param callback Callback to handle the presence data.
     * @throws Error if not subscribed for presence.
     */
    suspend fun fetchPresence(
        callback: (List<String>) -> Unit,
        options: PresenceConfig = PresenceConfig()
    ): suspend () -> Unit {

        // Return cached presence if available
        var previousPresenceData = presenceUsers
        if (previousPresenceData.isNotEmpty()) {
            Log.d("FetchPresence", "Calling callback with cached presence: $previousPresenceData")
            callback(previousPresenceData)
        }

        Log.d("FetchPresence", "Validating subscription for presence")
        validateSubscription("presence")

        if (!isListening) {
            Log.e("FetchPresence", "Not listening for presence after validation")
            throw IllegalStateException("Not subscribed for presence")
        }

        Log.d("FetchPresence", "Registering art_presence listener")
        on("art_presence") { payload ->
            Log.d("FetchPresence", "Received art_presence payload: $payload")
            if (payload is Map<*, *>) {
                val usernames = (payload["usernames"] as? List<*>)
                    ?.filterIsInstance<String>()
                    ?.toMutableList()
                    ?: run {
                        Log.w("FetchPresence", "No usernames in payload")
                        return@on
                    }

                Log.d("FetchPresence", "Received usernames: $usernames")
                presenceUsers = usernames

                // Parse "username:connectionId" format and extract usernames
                val userResponse = mutableListOf<String>()
                usernames.forEach { user ->
                    if (options.unique) {
                        val parts = user.split(":")
                        if (parts.isNotEmpty()) {
                            val userName = parts[0]
                            // Filter unique usernames
                            if (!userResponse.contains(userName)) {
                                userResponse.add(userName)
                            }
                        }
                    } else {
                        // When unique is false, return full user strings
                        userResponse.add(user)
                    }
                }
                callback(userResponse)
            }
        }

        try {
            val result = push("art_presence", emptyMap<String, Any>())
            Log.d("FetchPresence", "Push result: $result")
        } catch (e: Exception) {
            Log.e("FetchPresence", "Error pushing art_presence: ${e.message}", e)
            throw e
        }

        return suspend {
            unsubscribe()
        }
    }


    /* ---------------- ACK Handling ---------------- */

    // FIX: Cancel the stored timer Job on ACK receipt, matching JS's clearTimeout(entry.timer)
    fun handleMessageAcks(event: String, returnFlag: String, payload: MutableMap<String, Any?>) {
        if (returnFlag != "SA") return

        val refId = payload["ref_id"] as? String ?: return
        val entry = pendingAcks.remove(refId) ?: return
        entry.second.cancel()          // cancel the timeout timer
        entry.first.complete(refId)    // resolve the deferred
    }

    /* ---------------- Acknowledge ---------------- */

    fun acknowledge(request: MutableMap<String, Any?>, res: String) {

        if (channelConfig.channelType !in listOf("targeted", "secure"))
            return

        val channel = request["channel"] as? String ?: return
        if (channel in listOf("art_config", "art_secure", "art_presence"))
            return

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

        websocketHandler.sendMessage(response.toJson())
    }

    /* ---------------- Push ---------------- */

    open suspend fun push(
        event: String,
        data: Any,
        options: PushConfig? = null
    ): Any? {

        websocketHandler.wait()

        val connection = websocketHandler.getConnection()
        val to = options?.to ?: emptyList<String>()
        var messageStr = gson.toJson(data)
        // ---- Targeted / Secure validation ----
        if ((channelConfig.channelType == "secure"
                    || channelConfig.channelType == "targeted")
            && event != "art_presence"
        ) {
            if (to.size != 1) {
                throw IllegalArgumentException("Exactly one user must be specified for sending message.")
            }
        }

        /* ---- Secure encryption ---- */
        if (channelConfig.channelType == "secure"
            && event != "art_presence"
        ) {
            val res =
                websocketHandler.pushForSecureLine(
                    "secured_public_key",
                    mapOf("username" to to[0]),
                    true
                )

            val resMap = res as? Map<*, *>
                ?: throw Exception("Invalid secure response format")

            val responseData = resMap["data"] as? Map<*, *>
                ?: throw Exception("Missing data in secure response")

            val status = responseData["status"]?.toString()

            if (status == "unsuccessfull") {
                throw Exception(responseData["error"]?.toString() ?: "Unknown error")
            }

            messageStr = websocketHandler.encrypt(
                messageStr,
                responseData["public_key"].toString()
            )
        }

        /* ---- Ref ID & ACK Promise ---- */

        var refId: String? = null
        val ackDeferred = CompletableDeferred<Any?>()

        if (channelConfig.channelName !in listOf("art_config", "art_secure", "art_presence")) {

            messageCount++
            refId = "${connection?.connectionId}_${channelConfig.channelName}_$messageCount"

            // FIX: Always create the timer, then conditionally store in pendingAcks or resolve immediately
            // This matches JS: timer is always created, pendingAcks.set only for targeted/secure
            val timer = scope.launch {
                delay(ACK_TIMEOUT)
                pendingAcks.remove(refId)
                ackDeferred.completeExceptionally(Exception("ACK timeout"))
            }

            if (channelConfig.channelType == "targeted"
                || channelConfig.channelName == "secure"
            ) {
                // FIX: Store both deferred and timer so handleMessageAcks can cancel the timer
                pendingAcks[refId!!] = Pair(ackDeferred, timer)
            } else {
                // Non-targeted: resolve immediately and cancel the timer
                timer.cancel()
                ackDeferred.complete(refId)
            }

        } else {
            ackDeferred.complete(null)
        }

        val message = mapOf(
            "from" to (connection?.connectionId ?: ""),
            "to" to to,
            "channel" to buildString {
                append(channelConfig.channelName)
                channelConfig.channelNamespace.takeIf { it.isNotBlank() }?.let {
                    append(":$it")
                }
            },
            "event" to event,
            "content" to messageStr,
            "ref_id" to refId
        )

        websocketHandler.sendMessage(message.toJson())

        return ackDeferred
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

/* ---------------- Helpers ---------------- */

private val gson = Gson()

fun Any.toJson(): String = gson.toJson(this)

private fun <T> MutableList<T>.synchronizedAdd(item: T) {
    synchronized(this) { add(item) }
}

private fun <T> MutableList<T>.synchronizedRemove(item: T) {
    synchronized(this) { remove(item) }
}
