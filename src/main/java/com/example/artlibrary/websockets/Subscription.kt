package com.example.artlibrary.websockets

import BaseSubscription
import com.example.artlibrary.config.Events
import com.example.artlibrary.config.ReservedChannels
import com.example.artlibrary.config.ReturnFlags
import com.example.artlibrary.config.ChannelTypes
import com.example.artlibrary.types.ChannelConfig
import com.example.artlibrary.types.IWebsocketHandler
import com.example.artlibrary.types.PushConfig
import org.json.JSONObject

class Subscription(
    connectionID: String,
    channelConfig: ChannelConfig,
    websocketHandler: IWebsocketHandler,
    process: String = "subscribe"
) : BaseSubscription(connectionID, channelConfig, websocketHandler, process) {

    fun listen(callback: (Any?) -> Unit) {
        messageBuffer.forEach { (evt, msgs) ->
            msgs.forEach { reqData ->
                callback(mapOf("event" to evt, "content" to reqData["content"]))
                acknowledge(reqData, ReturnFlags.CLIENT_ACK)
            }
        }
        messageBuffer.clear()
        on(Events.ALL, callback)
    }

    fun bind(event: String, callback: (Any?) -> Unit) {
        messageBuffer[event]?.forEach { reqData ->
            callback(reqData["content"])
            acknowledge(reqData, ReturnFlags.CLIENT_ACK)
        }
        messageBuffer.remove(event)
        on(event, callback)
    }

    fun remove(event: String, callback: ((Any?) -> Unit)? = null) {
        if (callback != null) off(event, callback) else removeAllListeners(event)
        messageBuffer.remove(event)
    }

    override suspend fun push(event: String, data: Any, options: PushConfig?): Any? {
        return super.push(event, data, options)
    }

    suspend fun handleMessage(event: String, payload: MutableMap<String, Any?>) {
        // 1. Server ack — return early
        val returnFlag = payload["return_flag"]?.toString()
        if (returnFlag == ReturnFlags.SERVER_ACK) {
            handleMessageAcks(event, returnFlag, payload)
            return
        }

        // 2. Message ack
        acknowledge(payload, ReturnFlags.MESSAGE_ACK)

        // 3. Secure channel decryption
        if (channelConfig.channelType == ChannelTypes.SECURE &&
            event != ReservedChannels.ART_PRESENCE
        ) {
            val pubResWrapper = websocketHandler.pushForSecureLine(
                "secured_public_key",
                mapOf("username" to payload["from_username"]),
                true
            )

            val pubRes: Map<*, *> = when (pubResWrapper) {
                is Map<*, *> -> pubResWrapper["data"] as? Map<*, *> ?: emptyMap<String, Any?>()
                is JSONObject -> pubResWrapper.optJSONObject("data")?.toMap()
                    ?: emptyMap<String, Any?>()

                else -> emptyMap<String, Any?>()
            }

            if (pubRes["status"]?.toString() == "unsuccessfull") {
                throw IllegalStateException(
                    pubRes["error"]?.toString() ?: "Public key lookup failed"
                )
            }

            payload["data"] = websocketHandler.decrypt(
                payload["data"].toString(),
                pubRes["public_key"].toString()
            )
        }

        // 4. Parse content
        val content: Map<String, Any?> = if (payload.containsKey("data")) {
            runCatching { JSONObject(payload["data"].toString()).toMap() }.getOrDefault(emptyMap())
        } else {
            runCatching { JSONObject(payload.toString()).toMap() }.getOrDefault(emptyMap())
        }

        // 5. Presence — emit directly, no extra processing
        if (event == ReservedChannels.ART_PRESENCE) {
            emit(ReservedChannels.ART_PRESENCE, content)
            return
        }

        // 6. Normal message flow
        if (!isSubscribed) return

        val hasSpecific = listeners(event).isNotEmpty()
        val hasAll = listeners(Events.ALL).isNotEmpty()

        if (hasSpecific || hasAll) {
            if (hasSpecific) emit(event, content)
            if (hasAll) emit(Events.ALL, mapOf("event" to event, "content" to content))
            acknowledge(payload, ReturnFlags.CLIENT_ACK)
        } else {
            val buf = messageBuffer.getOrPut(event) { mutableListOf() }
            buf.add(
                mutableMapOf(
                    "id" to payload["id"],
                    "from" to payload["from"],
                    "channel" to payload["channel"],
                    "to" to payload["to"],
                    "pipeline_id" to payload["pipeline_id"],
                    "attempt_id" to payload["attempt_id"],
                    "interceptor_name" to payload["interceptor_name"],
                    "to_username" to payload["to_username"],
                    "content" to content
                )
            )
        }
    }
}