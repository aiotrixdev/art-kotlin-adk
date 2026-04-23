
package com.example.artlibrary.crdt

/* ---------- LDValue ---------- */
typealias LDValue = Any?


/* ---------- LDMeta ---------- */
data class LDMeta(
    var updatedAt: Long,
    var version: Int,
    var replicaId: String,
    var order: Int? = null,
    var after: String? = null,       // RGA predecessor (null = insert at head)
    var next: String? = null,        // optional convenience
    var tombstone: Boolean? = null
)


/* ---------- LDEntry ---------- */
data class LDEntry(
    val id: String,
    val key: String,
    val type: LDEntryType,
    val value: LDValue,
    val meta: LDMeta                 // after + next already live here
)

enum class LDEntryType {
    @com.google.gson.annotations.SerializedName("string")  STRING,
    @com.google.gson.annotations.SerializedName("number")  NUMBER,
    @com.google.gson.annotations.SerializedName("boolean") BOOLEAN,
    @com.google.gson.annotations.SerializedName("object")  OBJECT,
    @com.google.gson.annotations.SerializedName("array")   ARRAY,
    @com.google.gson.annotations.SerializedName("null")    NULL;

    companion object {
        fun fromWire(value: String): LDEntryType =
            values().firstOrNull { it.name.lowercase() == value.lowercase() }
                ?: error("Unknown LDEntryType: $value")
    }
}


/* ---------- LDMap ---------- */
data class LDMap(
    val index: MutableMap<String, LDEntry>,
    val meta: LDMeta
)


/* ---------- LDArray ---------- */
data class LDArray(
    val entries: MutableMap<String, LDEntry>,
    var head: String? = null,
    val meta: LDMeta
)


/* ---------- CRDTOperation ---------- */
sealed class CRDTOperation {
    abstract val op: String
    abstract val path: List<String>
    abstract val entry: Any?
    abstract val timestamp: Long
    abstract val replicaId: String
    abstract val ref: String?

    data class Add(
        override val op: String = "add",
        override val path: List<String>,
        override val entry: Any? = null,
        override val timestamp: Long,
        override val replicaId: String,
        override val ref: String? = null
    ) : CRDTOperation()

    data class Replace(
        override val op: String = "replace",
        override val path: List<String>,
        override val entry: Any? = null,
        override val timestamp: Long,
        override val replicaId: String,
        override val ref: String? = null
    ) : CRDTOperation()

    data class Remove(
        override val op: String = "remove",
        override val path: List<String>,
        override val entry: Any? = null,
        override val timestamp: Long,
        override val replicaId: String,
        override val ref: String? = null
    ) : CRDTOperation()

    data class ArrayPush(
        override val op: String = "array-push",
        override val path: List<String>,
        override val entry: Any? = null,
        override val timestamp: Long,
        override val replicaId: String,
        override val ref: String? = null
    ) : CRDTOperation()

    data class ArrayUnshift(
        override val op: String = "array-unshift",
        override val path: List<String>,
        override val entry: Any? = null,
        override val timestamp: Long,
        override val replicaId: String,
        override val ref: String? = null
    ) : CRDTOperation()

    data class ArrayRemove(
        override val op: String = "array-remove",
        override val path: List<String>,
        override val entry: Any? = null,
        override val timestamp: Long,
        override val replicaId: String,
        override val ref: String? = null
    ) : CRDTOperation()
}

fun LDEntryType.toWireType(): String = when (this) {
    LDEntryType.STRING -> "string"
    LDEntryType.NUMBER -> "number"
    LDEntryType.BOOLEAN -> "boolean"
    LDEntryType.OBJECT -> "object"
    LDEntryType.ARRAY -> "array"
    LDEntryType.NULL -> "null"
}

fun LDMeta.toWireMap(): Map<String, Any> {
    val out = linkedMapOf<String, Any>(
        "updatedAt" to updatedAt,
        "version" to version,
        "replicaId" to replicaId
    )
    order?.let { out["order"] = it }
    after?.let { out["after"] = it }
    next?.let { out["next"] = it }
    tombstone?.let { out["tombstone"] = it }
    return out
}

fun LDValue.toWireValue(): Any? = when (this) {
    is LDMap -> this.toWireMap()
    is LDArray -> this.toWireMap()
    else -> this
}

fun LDEntry.toWireMap(): Map<String, Any?> = linkedMapOf(
    "id" to id,
    "key" to key,
    "type" to type.toWireType(),
    "value" to value.toWireValue(),
    "meta" to meta.toWireMap()
)

fun LDMap.toWireMap(): Map<String, Any> = linkedMapOf(
    "index" to index.mapValues { (_, entry) -> entry.toWireMap() },
    "meta" to meta.toWireMap()
)

fun LDArray.toWireMap(): Map<String, Any> {
    val out = linkedMapOf<String, Any>(
        "entries" to entries.mapValues { (_, entry) -> entry.toWireMap() },
        "meta" to meta.toWireMap()
    )
    head?.let { out["head"] = it }
    return out
}

fun CRDTOperation.toWireMap(): Map<String, Any?> {
    val out = linkedMapOf<String, Any?>(
        "op" to op,
        "path" to path
    )

    val wireEntry = when (val current = entry) {
        null -> null
        is LDEntry -> current.toWireMap()
        is LDMap -> current.toWireMap()
        is LDArray -> current.toWireMap()
        else -> current
    }
    if (wireEntry != null) {
        out["entry"] = wireEntry
    }

    out["timestamp"] = timestamp
    out["replicaId"] = replicaId
    if (ref != null) {
        out["ref"] = ref
    }

    return out
}