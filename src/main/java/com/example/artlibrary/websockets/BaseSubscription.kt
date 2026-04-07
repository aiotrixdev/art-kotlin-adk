import android.R.attr
import android.util.Log
import com.example.artlibrary.types.ChannelConfig
import com.example.artlibrary.types.IWebsocketHandler
import com.example.artlibrary.types.PushConfig
import com.example.artlibrary.websockets.subscribeToChannel
import com.example.artlibrary.websockets.unsubscribeFromChannel
import com.google.gson.Gson
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger


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

    private val pendingAcks =
        ConcurrentHashMap<String, CompletableDeferred<Any>>()

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

        if (isSubscribed) return // Already subscribed

        isSubscribed = true

        try {

            // Call subscribe_to_channel helper
            val updatedConfig = subscribeToChannel(
                channelConfig.channelName,
                "subscribe", this.websocketHandler
            )
            channelConfig = updatedConfig
            Log.d("subscribeToChannel", "");
        } catch (error: Exception) {
            android.util.Log.e("BaseSubscription", "Subscribe error: ${error.message}")
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
                android.util.Log.e(
                    "BaseSubscription",
                    "Failed to unsubscribe from channel ${channelConfig.channelName}"
                )
            }
        } catch (error: Exception) {
            android.util.Log.e("BaseSubscription", "Unsubscribe error: ${error.message}")
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
        channelConfig.channelNamespace?.let {
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
            android.util.Log.e("BaseSubscription", "Validate subscription error: ${error.message}")
        }
    }

    /* ---------------- Presence ---------------- */

    data class PresenceConfig(
        val unique: Boolean = true
    )

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

                Log.d("FetchPresence", "Calling callback with processed users: $userResponse")
                callback(userResponse)
            }
        }

        Log.d("FetchPresence", "Pushing art_presence request")
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

    fun handleMessageAcks(event: String, returnFlag: String, payload: MutableMap<String, Any?>) {
        if (returnFlag != "SA") return

        val refId = payload["ref_id"] as? String ?: return
        pendingAcks.remove(refId)?.complete(refId)
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

        /* ---- Secure encryption (JS equivalent) ---- */
        if (channelConfig.channelType == "secure"
            && event != "art_presence"
        ) {
            val res =
                websocketHandler.pushForSecureLine(
                    "secured_public_key",
                    mapOf("username" to to[0]),
                    true
                )

            val resJson = res as JSONObject
            val responseData = resJson.getJSONObject("data")

            val status = responseData.getString("status")
            if (status == "unsuccessfull") {
                throw Exception(responseData.getString("error"))
            }

            messageStr = websocketHandler.encrypt(
                messageStr,
                responseData["public_key"].toString()
            )
        }

        /* ---- Ref ID ---- */

        var refId: String? = null
        var ackPromise: CompletableDeferred<Any?> = CompletableDeferred()

        if (channelConfig.channelName !in listOf("art_config", "art_secure", "art_presence")) {

            messageCount++

            refId =
                "${connection?.connectionId}_${channelConfig.channelName}_$messageCount"

            if (channelConfig.channelType == "targeted"
                || channelConfig.channelName == "secure"
            ) {


                val timer = CoroutineScope(Dispatchers.IO).launch {
                    delay(ACK_TIMEOUT)
                    pendingAcks.remove(refId)
                    ackPromise.completeExceptionally(Exception("ACK timeout"))
                }

            } else {
                ackPromise.complete(refId)
            }
        } else {
            ackPromise.complete(null)
        }

        val message = mapOf(
            "from" to (connection?.connectionId ?: ""),
            "to" to to,
            "channel" to buildString {
                append(channelConfig.channelName)
                channelConfig.channelNamespace?.let {
                    append(":$it")
                }
            },
            "event" to event,
            "content" to messageStr,
            "ref_id" to refId
        )

        websocketHandler.sendMessage(message.toJson())

        return ackPromise
    }

    open fun handleMessage(event: String, payload: Any) {}
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
