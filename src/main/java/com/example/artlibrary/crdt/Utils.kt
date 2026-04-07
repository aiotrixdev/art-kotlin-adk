package com.example.artlibrary.crdt


import kotlin.random.Random



/* ---------- Utils ---------- */

fun isLDEntry(x: Any?): Boolean {
    return x is LDEntry
}

fun toContainer(value: Any?): Any {
    return when (value) {
        is LDMap -> value
        is LDArray -> value
        else -> error("Value is not a container")
    }
}

fun generateId(): String {
    return "${System.currentTimeMillis()}-${Random.nextInt().toString(36).take(6)}"
}

fun determineType(v: LDValue): LDEntryType =
    when (v) {
        is String -> LDEntryType.STRING
        is Number -> LDEntryType.NUMBER
        is Boolean -> LDEntryType.BOOLEAN
        is LDArray -> LDEntryType.ARRAY
        is LDMap -> LDEntryType.OBJECT
        is List<*> -> LDEntryType.ARRAY
        null -> LDEntryType.NULL
        else -> LDEntryType.OBJECT
    }

private fun meta(replicaId: String = "client"): LDMeta =
    LDMeta(
        updatedAt = System.currentTimeMillis(),
        version = 1,
        replicaId = replicaId
    )

/* Convert plain Kotlin to LDValue */

fun toLDValue(v: Any?, replicaId: String = "client"): LDValue {

    // Primitives
    if (v is String || v is Number || v is Boolean) {
        return v
    }

    // Array
    if (v is List<*>) {

        val arr = LDArray(
            entries = mutableMapOf(),
            head = null,
            meta = meta(replicaId)
        )

        var prev: String? = null

        for (item in v) {

            val id = generateId()

            val converted = toLDValue(item, replicaId)

            val entry = LDEntry(
                id = id,
                key = id,
                type = determineType(converted),
                value = converted,
                meta = meta(replicaId).copy(after = prev)
            )

            arr.entries[id] = entry
            prev = id
        }

        arr.head = firstAfter(arr, null)

        return arr
    }

    // Object / Map
    if (v is Map<*, *>) {

        val index = mutableMapOf<String, LDEntry>()

        for ((k, value) in v) {

            val key = k as? String
                ?: throw IllegalArgumentException("Map keys must be String")

            val converted = toLDValue(value, replicaId)

            index[key] = LDEntry(
                id = generateId(),
                key = key,
                type = determineType(converted),
                value = converted,
                meta = meta(replicaId)
            )
        }

        return LDMap(
            index = index,
            meta = meta(replicaId)
        )
    }

    if (v == null) return null

    return null
}

/** helper used by toLDValue array build */
private fun firstAfter(arr: LDArray, after: String?): String? {
    // find any element with meta.after===after; deterministic pick by id
    val ids = arr.entries
        .filter { it.value.meta.after == after }
        .keys
        .sorted()

    return ids.firstOrNull()
}
