package com.example.artlibrary.websockets

import android.os.Build
import androidx.annotation.RequiresApi
import com.example.artlibrary.agentic.OrchestratorThread
import com.example.artlibrary.config.ChannelTypes
import com.example.artlibrary.config.Events
import com.example.artlibrary.config.ReservedChannels
import com.example.artlibrary.config.ReturnFlags
import com.example.artlibrary.storage.FileRef
import com.example.artlibrary.storage.ListFilesResult
import com.example.artlibrary.storage.ListOptions
import com.example.artlibrary.storage.Storage
import com.example.artlibrary.storage.UploadOptions
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

    // Mirrors the `threads` Map in the JS class
    private val threads = mutableMapOf<String, OrchestratorThread>()

    fun listen(callback: (Any?) -> Unit) {
        drainAndAttach(messageBuffer, Events.ALL, callback)
    }

    fun attachThreadListener(threadId: String, callback: (Any?) -> Unit) {
        val buffer = threadBuffers[threadId] ?: mutableMapOf()
        drainAndAttach(buffer, "$threadId-${Events.ALL}", callback)
        threadBuffers.remove(threadId)
    }

    fun bind(event: String, callback: (Any?) -> Unit) {
        drainEventAndAttach(messageBuffer, event, event, callback)
    }

    fun attachThreadBind(threadId: String, event: String, callback: (Any?) -> Unit) {
        val buffer = threadBuffers[threadId] ?: mutableMapOf()
        drainEventAndAttach(buffer, event, "$threadId-$event", callback)
    }

    fun remove(event: String, callback: ((Any?) -> Unit)? = null) {
        if (callback != null) off(event, callback) else removeAllListeners(event)
        messageBuffer.remove(event)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun upload(
        file: ByteArray,
        opts: UploadOptions = UploadOptions()
    ): FileRef {

        if (!channelConfig.orchestratorEnabled) {
            throw IllegalStateException(
                "Storage requires an orchestrator-enabled channel"
            )
        }

        return Storage().upload(
            file = file,
            opts = opts.copy(
                configId = channelConfig.channelName
            )
        )
    }

    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun listFiles(
        opts: ListOptions = ListOptions()
    ): ListFilesResult {

        if (!channelConfig.orchestratorEnabled) {
            throw IllegalStateException(
                "Storage requires an orchestrator-enabled channel"
            )
        }

        return Storage().listFiles(
            opts = opts.copy(
                configId = channelConfig.channelName
            )
        )
    }

    fun detachThreadListener(
        threadId: String,
        event: String,
        callback: ((Any?) -> Unit)? = null
    ) {
        val attachEvent = "$threadId-$event"
        if (callback != null) {
            off(attachEvent, callback)
        } else {
            removeAllListeners(attachEvent)
        }
        threadBuffers[threadId]?.remove(event)
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
        // NOTE: JS does NOT gate this on event != ART_PRESENCE, so the guard is removed here
        if (channelConfig.channelType == ChannelTypes.SECURE) {
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
        var content: Map<String, Any?> = if (payload.containsKey("data")) {
            runCatching { JSONObject(payload["data"].toString()).toMap() }.getOrDefault(emptyMap())
        } else {
            runCatching { JSONObject(payload.toString()).toMap() }.getOrDefault(emptyMap())
        }

        // 5. Human feedback detection — mirrors JS humanFeedbackRequest block
        val isHumanFeedbackRequest =
            returnFlag == "requestFeedback" ||
                    event == "human_input_request" ||
                    content["type"]?.toString() == "human_input_request"

        if (isHumanFeedbackRequest) {
            // Wrap content with a `reply` lambda, equivalent to JS:
            //   content = { ...content, reply: (replyData) => this.sendHumanFeedback(payload, replyData) }
            content = content.toMutableMap().apply {
                put("reply", fun(replyData: Any?) { sendHumanFeedback(payload, replyData) })
            }
        }

        val emitConfig = mapOf("thread_id" to payload["thread_id"])

        // 6. Presence — emit directly, no extra processing
        if (event == ReservedChannels.ART_PRESENCE) {
            emitEvent(ReservedChannels.ART_PRESENCE, content, emitConfig)
            return
        }

        // 7. Normal message flow
        if (!isSubscribed) return

        val threadId = payload["thread_id"]?.toString()?.takeIf { it.isNotBlank() }
        val specificEvent = threadId?.let { "$it-$event" } ?: event
        val allEvent = threadId?.let { "$it-${Events.ALL}" } ?: Events.ALL
        val hasSpecific = listeners(specificEvent).isNotEmpty()
        val hasAll = listeners(allEvent).isNotEmpty()

        if (hasSpecific || hasAll) {
            if (hasSpecific) emitEvent(event, content, emitConfig)
            if (hasAll) emitEvent(Events.ALL, mapOf("event" to event, "content" to content), emitConfig)
            acknowledge(payload, ReturnFlags.CLIENT_ACK)
        } else {
            addToBuffer(
                event,
                mutableMapOf(
                    "id" to payload["id"],
                    "from" to payload["from"],
                    "channel" to payload["channel"],
                    "to" to payload["to"],
                    "pipeline_id" to payload["pipeline_id"],
                    "thread_id" to payload["thread_id"],
                    "attempt_id" to payload["attempt_id"],
                    "interceptor_name" to payload["interceptor_name"],
                    "to_user_id" to payload["to_user_id"],
                    "to_username" to payload["to_username"],
                    "content" to content
                )
            )
        }
    }

    private fun emitEvent(event: String, content: Any?, config: Map<String, Any?> = emptyMap()) {
        val threadId = config["thread_id"]?.toString()?.takeIf { it.isNotBlank() }
        val eventName = threadId?.let { "$it-$event" } ?: event
        emit(eventName, content)
    }

    private fun addToBuffer(event: String, payload: MutableMap<String, Any?>) {
        val threadId = payload["thread_id"]?.toString()?.takeIf { it.isNotBlank() }
        if (threadId != null) {
            val threadBuffer = threadBuffers.getOrPut(threadId) { mutableMapOf() }
            val bucket = threadBuffer.getOrPut(event) { mutableListOf() }
            bucket.add(payload)
            return
        }

        val bucket = messageBuffer.getOrPut(event) { mutableListOf() }
        bucket.add(payload)
    }

    private fun drainAndAttach(
        buffer: MutableMap<String, MutableList<MutableMap<String, Any?>>>,
        allEvent: String,
        callback: (Any?) -> Unit
    ) {
        buffer.forEach { (eventName, messages) ->
            messages.forEach { request ->
                callback(mapOf("event" to eventName, "content" to request["content"]))
                acknowledge(request, ReturnFlags.CLIENT_ACK)
            }
        }
        buffer.clear()
        on(allEvent, callback)
    }

    private fun drainEventAndAttach(
        buffer: MutableMap<String, MutableList<MutableMap<String, Any?>>>,
        bufferEvent: String,
        attachEvent: String,
        callback: (Any?) -> Unit
    ) {
        buffer[bufferEvent]?.forEach { request ->
            callback(request["content"])
            acknowledge(request, ReturnFlags.CLIENT_ACK)
        }
        buffer.remove(bufferEvent)
        on(attachEvent, callback)
    }

    // -------------------------------------------------------------------------
    // Thread management — mirrors JS thread() / _threadUnchecked() / getThread()
    // / _unregisterThread()
    // -------------------------------------------------------------------------

    /**
     * Returns (or creates) an [OrchestratorThread] for this subscription.
     * Requires the channel to have orchestrator enabled, matching the JS guard.
     */
    fun thread(threadId: String? = null): OrchestratorThread {
        if (!channelConfig.orchestratorEnabled) {
            throw IllegalStateException("Thread works only in case of orchestrator enabled channels")
        }
        return threadUnchecked(threadId)
    }

    /**
     * Bypasses the orchestratorEnabled gate.
     * Intended for callers that have already committed to orchestrator semantics.
     * Do not call directly from app code.
     */
    fun threadUnchecked(threadId: String? = null): OrchestratorThread {
        if (threadId != null) {
            val existing = threads[threadId]
            if (existing != null && !existing.isDisposed()) return existing
        }
        val t = OrchestratorThread(this, threadId)
        threads[t.threadId] = t
        return t
    }

    fun getThread(threadId: String): OrchestratorThread? = threads[threadId]

    fun unregisterThread(threadId: String) {
        threads.remove(threadId)
        threadBuffers.remove(threadId)
    }

    // -------------------------------------------------------------------------
    // Human feedback — mirrors JS sendHumanFeedback()
    // -------------------------------------------------------------------------

    private fun sendHumanFeedback(originalReq: Map<String, Any?>, replyData: Any?) {
        val conn = websocketHandler.getConnection()
        val reply = mutableMapOf<String, Any?>(
            "channel"         to originalReq["channel"],
            "namespace"       to originalReq["namespace"],
            "id"              to originalReq["id"],
            "ref_id"          to originalReq["ref_id"],
            "from"            to (conn?.connectionId ?: ""),
            "to"              to (if (originalReq["from"] != null) listOf(originalReq["from"]) else emptyList<String>()),
            "to_username"     to originalReq["to_username"],
            "from_username"   to originalReq["from_username"],
            "return_flag"     to "HF",
            "thread_id"       to originalReq["thread_id"],
            "node_id"         to originalReq["node_id"],
            "iteration_id"    to originalReq["iteration_id"],
            "root_workflow_id" to originalReq["root_workflow_id"],
            "agent_node_id"   to originalReq["agent_node_id"],
            "agent_id"        to originalReq["agent_id"],
            "environment_id"  to originalReq["environment_id"],
            "pipeline_id"     to originalReq["pipeline_id"],
            "attempt_id"      to originalReq["attempt_id"],
            "interceptor_name" to originalReq["interceptor_name"],
            "content"         to JSONObject(replyData as? Map<*, *> ?: emptyMap<String, Any?>()).toString()
        )
        websocketHandler.sendMessage(JSONObject(reply).toString())
    }
}
