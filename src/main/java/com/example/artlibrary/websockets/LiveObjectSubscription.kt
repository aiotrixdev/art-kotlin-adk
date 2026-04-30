package com.example.artlibrary.websockets

import BaseSubscription
import com.example.artlibrary.config.AdkLog
import com.example.artlibrary.config.Events
import com.example.artlibrary.config.ReservedChannels
import com.example.artlibrary.config.ReturnFlags
import com.example.artlibrary.crdt.CRDT
import com.example.artlibrary.crdt.CRDTOperation
import com.example.artlibrary.crdt.LDMap
import com.example.artlibrary.crdt.LDMeta
import com.example.artlibrary.types.ChannelConfig
import com.example.artlibrary.types.IWebsocketHandler
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonParseException
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.lang.reflect.Type
import java.security.SecureRandom

private const val TAG = "LiveObjSubscription"

private val crdtGson = GsonBuilder()
    .registerTypeAdapter(CRDTOperation::class.java, CRDTOperationAdapter())
    .create()

private val replicaIdRandom = SecureRandom()

private fun newReplicaId(): String {
    val bytes = ByteArray(6)
    replicaIdRandom.nextBytes(bytes)
    return "r-" + bytes.joinToString("") { "%02x".format(it) }
}

/**
 * Subscription type backed by a CRDT shared object. Local mutations through
 * [state] are debounced and pushed via the `merge` event; incoming `update`
 * events are merged into the local replica.
 */
class LiveObjSubscription(
    connectionID: String,
    channelConfig: ChannelConfig,
    websocketHandler: IWebsocketHandler,
    process: String = "subscribe"
) : BaseSubscription(connectionID, channelConfig, websocketHandler, process) {

    val crdt: CRDT

    init {
        require(process == "subscribe" || process == "presence") {
            "Process must be 'subscribe' or 'presence'"
        }

        val snapshot = channelConfig.snapshot ?: mapOf("index" to emptyMap<String, Any?>())

        val initialSnapshot: LDMap = when (snapshot) {
            is LDMap -> snapshot
            is Map<*, *> -> {
                try {
                    @Suppress("UNCHECKED_CAST")
                    CRDT.reconstructLDMap(snapshot as Map<String, Any?>)
                } catch (e: Exception) {
                    AdkLog.e(TAG, "Failed to reconstruct initial snapshot", e)
                    emptyLDMap()
                }
            }
            else -> emptyLDMap()
        }

        crdt = CRDT(initialSnapshot, ::executeServerMerge, scope)
        crdt.setReplicaId(newReplicaId())
    }

    private fun emptyLDMap(): LDMap = LDMap(
        index = mutableMapOf(),
        meta = LDMeta(
            replicaId = "server",
            updatedAt = System.currentTimeMillis(),
            version = 0
        )
    )

    fun state(): CRDT.CRDTProxy = crdt.state()

    fun flush() = crdt.flush()

    fun query(path: String): CRDT.QueryResult = crdt.query(path)

    private fun executeServerMerge(ops: List<CRDTOperation>) {
        scope.launch {
            try {
                push(Events.MERGE, ops)
            } catch (e: Exception) {
                AdkLog.e(TAG, "Failed to push update", e)
            }
        }
    }

    override fun handleMessage(event: String, payload: Any) {
        @Suppress("UNCHECKED_CAST")
        val data = payload as? MutableMap<String, Any> ?: return

        if ((data["return_flag"] as? String) == ReturnFlags.SERVER_ACK) return

        if (event == ReservedChannels.ART_PRESENCE) {
            val content = JSONObject((data["data"] as? String) ?: "{}")
            emit(ReservedChannels.ART_PRESENCE, content)
            return
        }

        val rawData = data["data"] as? String ?: return

        val operations: List<CRDTOperation> = try {
            val type = object : TypeToken<List<CRDTOperation>>() {}.type
            crdtGson.fromJson<List<CRDTOperation>>(rawData, type) ?: emptyList()
        } catch (e: Exception) {
            AdkLog.e(TAG, "Failed to deserialize operations", e)
            return
        }

        if (event == Events.UPDATE) {
            val myId = crdt.getReplicaId()
            val allMine = operations.isNotEmpty() && operations.all { it.replicaId == myId }
            if (!allMine) crdt.merge(operations)
        }
    }
}

class CRDTOperationAdapter : JsonDeserializer<CRDTOperation> {
    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext
    ): CRDTOperation {
        val obj = json.asJsonObject
        return when (val opType = obj.get("op").asString) {
            "add" -> context.deserialize(obj, CRDTOperation.Add::class.java)
            "replace" -> context.deserialize(obj, CRDTOperation.Replace::class.java)
            "remove" -> context.deserialize(obj, CRDTOperation.Remove::class.java)
            "array-push" -> context.deserialize(obj, CRDTOperation.ArrayPush::class.java)
            "array-unshift" -> context.deserialize(obj, CRDTOperation.ArrayUnshift::class.java)
            "array-remove" -> context.deserialize(obj, CRDTOperation.ArrayRemove::class.java)
            else -> throw JsonParseException("Unknown op type: $opType")
        }
    }
}
