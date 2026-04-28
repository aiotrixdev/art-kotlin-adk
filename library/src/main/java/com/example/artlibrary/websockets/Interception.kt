package com.example.artlibrary.websockets

import com.example.artlibrary.config.AdkLog
import com.example.artlibrary.config.ReturnFlags
import com.example.artlibrary.types.IWebsocketHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

private const val TAG = "ArtInterception"

/**
 * Server-side interceptor binding. Each instance owns a coroutine scope used
 * to run the user's interceptor callback off the WebSocket reader thread.
 */
class Interception(
    private val interceptor: String,
    private val fn: (payload: Any?, resolve: (Any?) -> Unit, reject: (Any?) -> Unit) -> Unit,
    private val websocketHandler: IWebsocketHandler,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    private var interceptorData: Any? = null
    private var reconnectJob: Job? = null

    suspend fun validateInterception() {
        try {
            interceptorData = getInterceptorConfig(interceptor, websocketHandler)
        } catch (e: Exception) {
            AdkLog.e(TAG, "Failed for interceptor: '$interceptor'", e)
            throw e
        }
    }

    fun reconnect() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            try {
                validateInterception()
            } finally {
                reconnectJob = null
            }
        }
    }

    /** Cancels the internal scope. Call when the SDK is being torn down. */
    fun release() {
        scope.cancel()
    }

    private fun createResponse(
        config: Map<String, Any>,
        id: String,
        refId: String,
        channel: String,
        namespace: String,
        event: String,
        pipelineId: String,
        interceptorName: String,
        attemptId: String,
        type: String,
        content: Any
    ): Map<String, Any> = config.toMutableMap().apply {
        this["channel"] = channel
        this["namespace"] = namespace
        this["event"] = event
        this["id"] = id
        this["ref_id"] = refId
        this["return_flag"] = type
        this["pipeline_id"] = pipelineId
        this["interceptor_name"] = interceptorName
        this["attempt_id"] = attemptId
        this["content"] = JSONObject.wrap(content) ?: content
    }

    private fun execute(request: MutableMap<String, Any>) {
        acknowledge(request)

        val id = request["id"] as String
        val refId = request["ref_id"] as String
        val channel = request["channel"] as String
        val namespace = request["namespace"] as String
        val event = request["event"] as String
        val pipelineId = request["pipeline_id"] as String
        val interceptorName = request["interceptor_name"] as String
        val attemptId = request["attempt_id"] as String
        val from = request["from"]
            ?: throw IllegalStateException("Interceptor request missing 'from'")
        val to = request["to"]
            ?: throw IllegalStateException("Interceptor request missing 'to'")
        val rawData = request["data"]

        val config: MutableMap<String, Any> = mutableMapOf(
            "channel" to channel,
            "namespace" to namespace,
            "event" to event,
            "interceptor_name" to interceptorName,
            "from" to from,
            "to" to to
        )

        // FIX: previously used `let@` which caused early-return *from let* and
        // silently dropped non-Map payloads. We now resolve normally and
        // unwrap nested envelopes when present.
        val resolve: (Any?) -> Unit = { resolved ->
            val data: Any = when {
                resolved is Map<*, *> &&
                        (resolved.containsKey("attempt_id") || resolved.containsKey("pipeline_id")) ->
                    (resolved["data"] as? Map<*, *>) ?: emptyMap<String, Any>()
                resolved is Map<*, *> -> resolved
                resolved == null -> emptyMap<String, Any>()
                else -> mapOf("value" to resolved)
            }
            val response = createResponse(
                config, id, refId, channel, namespace, event,
                pipelineId, interceptorName, attemptId, "resolve", data
            )
            websocketHandler.sendMessage(JSONObject(response).toString())
        }

        val reject: (Any?) -> Unit = { error ->
            val errorResponse = mapOf(
                "rawData" to (rawData ?: JSONObject.NULL),
                "error" to (error ?: "unknown error")
            )
            val response = createResponse(
                config, id, refId, channel, namespace, event,
                pipelineId, interceptorName, attemptId, "reject", errorResponse
            )
            websocketHandler.sendMessage(JSONObject(response).toString())
        }

        scope.launch {
            try {
                fn(request, resolve, reject)
            } catch (e: Exception) {
                AdkLog.e(TAG, "Interceptor function threw", e)
                reject(e.message ?: "interceptor failed")
            }
        }
    }

    private fun acknowledge(request: MutableMap<String, Any>) {
        val response = mapOf(
            "channel" to request["channel"],
            "namespace" to request["namespace"],
            "id" to request["id"],
            "ref_id" to request["ref_id"],
            "from" to request["from"],
            "to" to request["to"],
            "return_flag" to ReturnFlags.INTERCEPTOR_ACK,
            "pipeline_id" to request["pipeline_id"],
            "interceptor_name" to request["interceptor_name"],
            "attempt_id" to request["attempt_id"],
            "content" to JSONObject.wrap(request["data"])
        )
        websocketHandler.sendMessage(JSONObject(response).toString())
    }

    @Suppress("UNCHECKED_CAST")
    fun handleMessage(channel: String, data: MutableMap<String, Any?>) {
        try {
            val rawData = data["data"]
            if (rawData is String && rawData.isNotEmpty()) {
                data["data"] = JSONObject(rawData)
            }
            execute(data as MutableMap<String, Any>)
        } catch (e: Exception) {
            AdkLog.e(TAG, "Error handling message", e)
        }
    }
}
