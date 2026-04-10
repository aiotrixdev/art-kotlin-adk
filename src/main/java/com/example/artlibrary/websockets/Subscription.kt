package com.example.artlibrary.websockets

import BaseSubscription
import com.example.artlibrary.config.AdkLog
import com.example.artlibrary.config.ChannelTypes
import com.example.artlibrary.config.Events
import com.example.artlibrary.config.ReservedChannels
import com.example.artlibrary.config.ReturnFlags
import com.example.artlibrary.types.ChannelConfig
import com.example.artlibrary.types.IWebsocketHandler
import com.example.artlibrary.types.PushConfig
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "ArtSubscription"

class Subscription(
    connectionID: String,
    channelConfig: ChannelConfig,
    websocketHandler: IWebsocketHandler,
    process: String = "subscribe"
) : BaseSubscription(connectionID, channelConfig, websocketHandler, process) {
    private val connectionStore = ConnectionStore.getInstance()

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

        val returnFlag = payload["return_flag"]?.toString()

        // ✅ SERVER ACK
        if (returnFlag == ReturnFlags.SERVER_ACK) {
            handleMessageAcks(event, returnFlag, payload)
            return
        }

        // ✅ MESSAGE ACK
        acknowledge(payload, ReturnFlags.MESSAGE_ACK)

        // 🚨 IMPORTANT FIX: DO NOT DECRYPT PRESENCE
        if (channelConfig.channelType == ChannelTypes.SECURE &&
            event != ReservedChannels.ART_PRESENCE
        ) {
            try {
                val senderLookup = extractSenderLookup(payload)
                    ?: throw IllegalStateException("Missing sender identity for secure message")

                val senderPublicKey = connectionStore.getKey(senderLookup) ?: run {
                    val pubResWrapper = websocketHandler.pushForSecureLine(
                        "secured_public_key",
                        mapOf("username" to senderLookup),
                        true
                    )

                    val pubRes: Map<*, *> = when (pubResWrapper) {
                        is Map<*, *> -> pubResWrapper["data"] as? Map<*, *> ?: emptyMap<String, Any?>()
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

                    pubRes["public_key"]?.toString()
                        ?.takeIf { it.isNotBlank() }
                        ?.also { connectionStore.addKey(senderLookup, it) }
                        ?: throw IllegalStateException("Missing sender public key")
                }

                val decrypted = websocketHandler.decrypt(
                    payload["data"].toString(),
                    senderPublicKey
                )

                payload["data"] = decrypted

            } catch (e: Exception) {
                AdkLog.e(TAG, "Decryption failed: ${e.message}", e)
            }
        }

        // ✅ SAFE CONTENT PARSE
        val dataRaw = payload["data"] ?: payload["content"]

        val content: Map<String, Any?> = try {
            when (dataRaw) {
                is String -> {
                    if (dataRaw.isBlank()) emptyMap()
                    else runCatching { JSONObject(dataRaw).toMap() }
                        .getOrElse { mapOf("message" to dataRaw, "content" to dataRaw) }
                }

                is Map<*, *> -> dataRaw as Map<String, Any?>

                is JSONObject -> dataRaw.toMap()

                else -> emptyMap()
            }
        } catch (e: Exception) {
            AdkLog.e(TAG, "Content parse failed", e)
            emptyMap()
        }

        // ✅ SPECIAL HANDLING: PRESENCE
        if (event == ReservedChannels.ART_PRESENCE) {

            val fixedContent = content.toMutableMap()

            val usernamesRaw = fixedContent["usernames"]

            val usernames: List<String> = when (usernamesRaw) {

                // ✅ Already correct
                is List<*> -> usernamesRaw.mapNotNull { it?.toString() }

                // ❌ String → FIX
                is String -> {
                    try {
                        val jsonArray = org.json.JSONArray(usernamesRaw)
                        List(jsonArray.length()) { i -> jsonArray.getString(i) }
                    } catch (e: Exception) {
                        emptyList()
                    }
                }

                else -> emptyList()
            }

            fixedContent["usernames"] = usernames

            emit(ReservedChannels.ART_PRESENCE, fixedContent)

            return
        }

        // ✅ NORMAL FLOW
        if (!isSubscribed) return

        val hasSpecific = listeners(event).isNotEmpty()
        val hasAll = listeners(Events.ALL).isNotEmpty()

        if (hasSpecific || hasAll) {

            if (hasSpecific) emit(event, content)

            if (hasAll) {
                emit(
                    Events.ALL,
                    mapOf(
                        "event" to event,
                        "content" to content
                    )
                )
            }

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

private fun extractSenderLookup(payload: Map<String, Any?>): String? =
    payload["from_username"]?.toString()?.takeIf { it.isNotBlank() }
        ?: payload["username"]?.toString()?.takeIf { it.isNotBlank() }
        ?: payload["sender"]?.toString()?.takeIf { it.isNotBlank() }
        ?: payload["from"]?.toString()?.takeIf { it.isNotBlank() }
