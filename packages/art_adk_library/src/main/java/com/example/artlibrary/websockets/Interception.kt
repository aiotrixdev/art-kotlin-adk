package com.example.artlibrary.websockets

import com.example.artlibrary.config.AdkLog
import com.example.artlibrary.config.ReturnFlags
import com.example.artlibrary.types.IWebsocketHandler
import org.json.JSONObject

private const val TAG = "ArtInterception"

class Interception(
    private val interceptor: String,
    private val fn: (payload: Any?, resolve: (Any?) -> Unit, reject: (Any?) -> Unit) -> Unit,
    private val websocketHandler: IWebsocketHandler
) {

    private var interceptorData: Any? = null

    suspend fun validateInterception() {
        try {
            interceptorData = getInterceptorConfig(interceptor, websocketHandler)
        } catch (e: Exception) {
            throw e
        }
    }

    suspend fun reconnect() {
        AdkLog.d(TAG, "reconnecting interceptor $interceptor")
        validateInterception()
    }

    private fun createResponse(
        config: Map<String, Any?>,
        id: Any?,
        refId: Any?,
        channel: Any?,
        namespace: Any?,
        event: Any?,
        pipelineId: Any?,
        interceptorName: Any?,
        attemptId: Any?,
        type: String,
        content: Any?
    ): Map<String, Any?> {
        val response = config.toMutableMap()
        response["channel"] = channel
        response["namespace"] = namespace
        response["event"] = event
        response["id"] = id
        response["ref_id"] = refId
        response["return_flag"] = type
        response["pipeline_id"] = pipelineId
        response["interceptor_name"] = interceptorName
        response["attempt_id"] = attemptId
        // matches JS: content: JSON.stringify(content)
        response["content"] = jsonStringify(content)
        return response
    }

    private fun execute(request: MutableMap<String, Any?>) {
        acknowledge(request)

        val id = request["id"]
        val channel = request["channel"]
        val namespace = request["namespace"]
        val from = request["from"]
        val to = request["to"]
        val event = request["event"]
        val interceptorName = request["interceptor_name"]
        val pipelineId = request["pipeline_id"]
        val attemptId = request["attempt_id"]
        val refId = request["ref_id"]
        val rawData = request["data"]

        val threadId = request["thread_id"]
        val nodeId = request["node_id"]
        val agentNodeId = request["agent_node_id"]
        val agentId = request["agent_id"]
        val environmentId = request["environment_id"]
        val toUsername = request["to_username"]
        val configurationId = request["configuration_id"]
        val rootWorkflowId = request["root_workflow_id"]

        val config: Map<String, Any?> = mapOf(
            "channel" to channel,
            "namespace" to namespace,
            "event" to event,
            "interceptor_name" to interceptorName,
            "from" to from,
            "to" to to,
            "to_username" to toUsername,
            "thread_id" to threadId,
            "node_id" to nodeId,
            "agent_node_id" to agentNodeId,
            "agent_id" to agentId,
            "environment_id" to environmentId,
            "configuration_id" to configurationId,
            "root_workflow_id" to rootWorkflowId
        )

        val resolve: (Any?) -> Unit = { resolved ->
            // JS: if (data === null || typeof data !== 'object') -> log error, return
            if (resolved == null || resolved !is Map<*, *>) {
                AdkLog.e(TAG, "Invalid data: Expected a JSON object or array of objects. $resolved")
            } else {
                var data: Any? = resolved
                val map = resolved as Map<*, *>
                if (map.containsKey("attempt_id") || map.containsKey("pipeline_id")) {
                    data = map["data"] ?: emptyMap<String, Any?>()
                }

                val response = createResponse(
                    config, id, refId, channel, namespace, event,
                    pipelineId, interceptorName, attemptId, "resolve", data
                )
                websocketHandler.sendMessage(jsonStringify(response))
            }
        }

        val reject: (Any?) -> Unit = { error ->
            // JS: if (typeof error !== 'string') throw new Error('Error must be a string');
            if (error !is String) {
                throw IllegalArgumentException("Error must be a string")
            }
            val errorResponse = mapOf(
                "rawData" to rawData,
                "error" to error
            )
            val response = createResponse(
                config, id, refId, channel, namespace, event,
                pipelineId, interceptorName, attemptId, "reject", errorResponse
            )
            websocketHandler.sendMessage(jsonStringify(response))
        }

        fn(request, resolve, reject)
    }

    private fun acknowledge(request: MutableMap<String, Any?>) {
        val response = mapOf(
            "channel" to request["channel"],
            "namespace" to request["namespace"],
            "id" to request["id"],
            "ref_id" to request["ref_id"],
            "from" to request["from"],
            "to" to request["to"],
            "to_username" to request["to_username"],
            "return_flag" to ReturnFlags.INTERCEPTOR_ACK,
            "pipeline_id" to request["pipeline_id"],
            "interceptor_name" to request["interceptor_name"],
            "attempt_id" to request["attempt_id"],
            "thread_id" to request["thread_id"],
            "node_id" to request["node_id"],
            "agent_node_id" to request["agent_node_id"],
            "agent_id" to request["agent_id"],
            "environment_id" to request["environment_id"],
            "configuration_id" to request["configuration_id"],
            "root_workflow_id" to request["root_workflow_id"],
            "content" to jsonStringify(request["data"])
        )

        websocketHandler.sendMessage(jsonStringify(response))
    }

    @Suppress("UNCHECKED_CAST")
    fun handleMessage(channel: String, data: MutableMap<String, Any?>) {
        try {
            val rawData = data["data"]
            data["data"] = JSONObject(rawData as String)
            execute(data)
        } catch (e: Exception) {
            throw e
        }
    }

    /**
     * Equivalent of JS `JSON.stringify(value)` for our Map/List/primitive
     * representation, using org.json under the hood.
     */
    private fun jsonStringify(value: Any?): String {
        return when (value) {
            null -> "null"
            is Map<*, *> -> JSONObject(value).toString()
            is List<*> -> org.json.JSONArray(value).toString()
            is String -> JSONObject.quote(value)
            else -> JSONObject.wrap(value)?.toString() ?: value.toString()
        }
    }
}