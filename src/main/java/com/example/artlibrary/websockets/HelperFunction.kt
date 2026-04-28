package com.example.artlibrary.websockets

import com.example.artlibrary.config.AdkLog
import com.example.artlibrary.types.ChannelConfig
import com.example.artlibrary.types.IWebsocketHandler
import org.json.JSONObject

private const val TAG = "ArtChannel"

/**
 * Server round-trip that asks the backend to subscribe (or fetch presence
 * config for) the given channel and returns the resulting [ChannelConfig].
 */
suspend fun subscribeToChannel(
    channel: String,
    process: String,
    websocketHandler: IWebsocketHandler
): ChannelConfig {
    websocketHandler.wait()

    val subscriptionChannelName =
        if (process == "subscribe") "channel-subscribe" else "channel-presence"

    val response = websocketHandler.pushForSecureLine(
        subscriptionChannelName,
        mapOf("channel" to channel),
        true
    ) as? Map<*, *> ?: throw IllegalStateException("Invalid response for $subscriptionChannelName")

    val dataRaw = response["data"] as? Map<*, *>
        ?: throw IllegalStateException("Missing data for $subscriptionChannelName")

    val data = dataRaw.mapKeys { it.key.toString() }

    if (data["status"] == "not-OK") {
        throw IllegalStateException(data["error"]?.toString() ?: "Unknown error")
    }

    val rawData = data["channelConfig"] as? Map<*, *>
        ?: throw IllegalStateException("channelConfig missing in response")

    return ChannelConfig(
        channelName = data["channel"]?.toString() ?: "",
        channelNamespace = data["channelNamespace"]?.toString() ?: "",
        channelType = rawData["TypeofChannel"]?.toString() ?: "",
        snapshot = data["snapshot"],
        presenceUsers = (data["presenceUsers"] as? List<*>)
            ?.map { it.toString() } ?: emptyList(),
        subscriptionID = data["subscriptionID"]?.toString()
    )
}

suspend fun unsubscribeFromChannel(
    channel: String,
    subscriptionID: String,
    process: String,
    websocketHandler: IWebsocketHandler
): Boolean {
    websocketHandler.wait()

    val subscriptionChannelName =
        if (process == "subscribe") "channel-unsubscribe" else "presence-unsubscribe"

    val response = websocketHandler.pushForSecureLine(
        subscriptionChannelName,
        mapOf(
            "channel" to channel,
            "subscriptionID" to subscriptionID
        ),
        true
    ) as? Map<*, *> ?: throw IllegalStateException("Response is not a Map")

    val data = response["data"] as? Map<*, *>
        ?: throw IllegalStateException("Invalid response format")

    if (data["status"] == "not-OK") {
        throw IllegalStateException(data["error"]?.toString() ?: "Unknown error")
    }

    return true
}

suspend fun getInterceptorConfig(
    interceptor: String,
    websocketHandler: IWebsocketHandler
): Any {
    websocketHandler.wait()

    return try {
        val response = websocketHandler.pushForSecureLine(
            "interceptor-subscribe",
            mapOf("interceptor" to interceptor),
            true
        )

        val data: Map<*, *> = when (response) {
            is Map<*, *> -> response["data"] as? Map<*, *>
            is JSONObject -> response.getJSONObject("data").toMap()
            else -> null
        } ?: throw IllegalStateException("Missing data in response")

        if (data["status"] == "not-OK") {
            throw IllegalStateException(data["error"]?.toString() ?: "Unknown error")
        }

        data["interceptorConfig"]
            ?: throw IllegalStateException("interceptorConfig missing in response")
    } catch (e: Exception) {
        AdkLog.e(TAG, "Interceptor config failed for '$interceptor'", e)
        throw e
    }
}

/**
 * Converts a [JSONObject] to a plain Kotlin [Map] recursively.
 *
 * Lives in the websockets package so it can be shared by every helper that
 * needs to convert payloads. Previously declared in the unnamed (top-level)
 * package, which made imports brittle.
 */
fun JSONObject.toMap(): Map<String, Any?> {
    val map = mutableMapOf<String, Any?>()
    val keys = keys()
    while (keys.hasNext()) {
        val key = keys.next()
        val value = get(key)
        map[key] = when (value) {
            is JSONObject -> value.toMap()
            JSONObject.NULL -> null
            else -> value
        }
    }
    return map
}
