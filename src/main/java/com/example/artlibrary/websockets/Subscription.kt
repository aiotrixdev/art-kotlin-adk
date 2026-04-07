package com.example.artlibrary.websockets

import com.example.artlibrary.config.AdkLog
import com.example.artlibrary.config.ChannelTypes
import com.example.artlibrary.config.Events
import com.example.artlibrary.config.ReservedChannels
import com.example.artlibrary.config.ReturnFlags
import com.example.artlibrary.types.ChannelConfig
import com.example.artlibrary.types.IWebsocketHandler
import com.example.artlibrary.types.PushConfig
import org.json.JSONObject

private const val TAG = "ArtSubscription"

/**
 * Standard channel subscription. Buffers messages received before any
 * listener has been bound and replays them in order when the consumer
 * eventually subscribes.
 */
class Subscription(
    connectionID: String,
    channelConfig: ChannelConfig,
    websocketHandler: IWebsocketHandler,
    process: String = "subscribe"
) : BaseSubscription(connectionID, channelConfig, websocketHandler, process) {

    /** Subscribes to all events and replays buffered messages. */
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

    /** Subscribes to a single event name. */
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

    /** Routes a parsed incoming frame to listeners or buffers it. */
    suspend fun handleMessage(event: String, payload: MutableMap<String, Any?>) {
        val returnFlag = payload["return_flag"]?.toString()
        if (returnFlag == ReturnFlags.SERVER_ACK) {
            handleMessageAcks(event, returnFlag, payload)
            return
        }

        acknowledge(payload, ReturnFlags.MESSAGE_ACK)

        if (channelConfig.channelType == ChannelTypes.SECURE) {
            try {
                val pubResWrapper = websocketHandler.pushForSecureLine(
                    "secured_public_key",
                    mapOf("username" to payload["from_username"]),
                    true
                )

                val pubRes: Map<*, *> = when (pubResWrapper) {
                    is Map<*, *> -> {
                        when (val data = pubResWrapper["data"]) {
                            is Map<*, *> -> data
                            is JSONObject -> data.toMap()
                            else -> emptyMap<String, Any?>()
                        }
                    }

                    is JSONObject -> pubResWrapper.optJSONObject("data")?.toMap()
                        ?: emptyMap<String, Any?>()

                    else -> emptyMap<String, Any?>()
                }

                val status = pubRes["status"]?.toString()
                if (status == "unsuccessful" || status == "unsuccessfull") {
                    throw IllegalStateException(
                        pubRes["error"]?.toString() ?: "Public key lookup failed"
                    )
                }

                val decrypted = websocketHandler.decrypt(
                    payload["data"].toString(),
                    pubRes["public_key"]?.toString()
                        ?: throw IllegalStateException("Missing sender public key")
                )

                payload["data"] = decrypted
            } catch (e: Exception) {
                AdkLog.e(TAG, "Decryption failed for secure channel message: ${e.message}", e)
            }
        }

        val dataRaw = payload["data"] ?: payload["content"]

        @Suppress("UNCHECKED_CAST")
        val content: Map<String, Any?> = try {
            when (dataRaw) {
                is String -> if (dataRaw.isEmpty()) emptyMap() else JSONObject(dataRaw).toMap()
                is Map<*, *> -> dataRaw as Map<String, Any?>
                else -> emptyMap()
            }
        } catch (e: Exception) {
            AdkLog.e(TAG, "Failed to parse content", e)
            emptyMap()
        }

        if (event == ReservedChannels.ART_PRESENCE) {
            emit(ReservedChannels.ART_PRESENCE, content)
            return
        }

        if (!isSubscribed) return

        val hasSpecific = listeners(event).isNotEmpty()
        val hasAll = listeners(Events.ALL).isNotEmpty()

        if (hasSpecific || hasAll) {
            if (hasSpecific) emit(event, content)
            if (hasAll) emit(
                Events.ALL,
                mapOf("event" to event, "content" to content)
            )
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
            return
        }
    }
}
