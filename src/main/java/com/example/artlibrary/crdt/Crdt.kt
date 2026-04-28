package com.example.artlibrary.crdt

import com.example.artlibrary.config.AdkLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "ArtCRDT"

typealias Listener = (Any?) -> Unit

// ---------- RGA helpers ----------

private fun cmpIds(a: LDEntry, b: LDEntry): Int {
    if (a.meta.updatedAt != b.meta.updatedAt) return a.meta.updatedAt.compareTo(b.meta.updatedAt)
    if (a.meta.replicaId != b.meta.replicaId) return if (a.meta.replicaId < b.meta.replicaId) -1 else 1
    return a.id.compareTo(b.id)
}

private fun linearizeRGA(arr: LDArray): List<String> {
    val afterToKids = mutableMapOf<String?, MutableList<LDEntry>>()
    for (e in arr.entries.values) {
        val after = e.meta.after
        afterToKids.getOrPut(after) { mutableListOf() }.add(e)
    }
    afterToKids.values.forEach { it.sortWith(::cmpIds) }

    val out = mutableListOf<String>()
    val seen = mutableSetOf<String>()

    fun walk(parent: String?) {
        val kids = afterToKids[parent] ?: return
        for (e in kids) {
            if (seen.add(e.id)) {
                if (e.meta.tombstone != true) out.add(e.id)
                walk(e.id)
            }
        }
    }
    walk(null)
    return out
}

/* ---------- CRDT ---------- */

class CRDT(
    private var snapshot: LDMap,
    private val merge_callback: (List<CRDTOperation>) -> Unit,
    private val scope: CoroutineScope
) {

    val listeners = mutableMapOf<String, MutableSet<Listener>>()
    private var pending = mutableListOf<CRDTOperation>()
    private var rootProxy: Any? = null
    private var clientReplicaId = "client"
    private var lastFlushAt = 0L
    private val minFlushMs = 50L
    private var trailingTimer: kotlinx.coroutines.Job? = null

    /* ---------- Public ---------- */

    fun state(): CRDTProxy {
        if (rootProxy == null) rootProxy = makeProxy(emptyList())
        return rootProxy as CRDTProxy
    }

    fun flush() {
        if (pending.isEmpty()) return
        val ops = compactOps(pending)
        pending = mutableListOf()
        AdkLog.d(TAG, "Flushing ${ops.size} local ops")
        // Local ops are already applied in-place by the proxy as the user
        // mutates the state, so do NOT re-merge them here. We only push
        // them out to the server. Re-merging would re-process array `after`
        // chains and risk re-inserts.
        merge_callback(ops)
    }

    private fun scheduleFlush() {
        val now = System.currentTimeMillis()
        val dueIn = (lastFlushAt + minFlushMs) - now
        if (trailingTimer != null) return
        if (dueIn <= 0) {
            lastFlushAt = now
            scope.launch { flush() }
        } else {
            trailingTimer = scope.launch {
                delay(dueIn)
                trailingTimer = null
                lastFlushAt = System.currentTimeMillis()
                flush()
            }
        }
        notifyListeners()
    }

    // ---------- Op compaction ----------

    private fun compactOps(batch: List<CRDTOperation>): List<CRDTOperation> {
        val parentAddsSeen = mutableSetOf<String>()
        val parentAdds = mutableListOf<CRDTOperation>()
        val arrayOps = mutableListOf<CRDTOperation>()

        data class Acc(val op: String, val entry: LDEntry? = null)

        val leafMap = mutableMapOf<String, Acc>()

        for (op in batch) {
            if (op.op == "array-push" || op.op == "array-unshift" || op.op == "array-remove") {
                arrayOps.add(op)
                continue
            }

            if (op.op == "add") {
                val entry = op.entry as? LDEntry
                if (entry != null &&
                    entry.type != LDEntryType.STRING &&
                    entry.type != LDEntryType.NUMBER &&
                    entry.type != LDEntryType.BOOLEAN
                ) {
                    val k = op.path.joinToString(".")
                    if (!parentAddsSeen.contains(k)) {
                        parentAddsSeen.add(k)
                        parentAdds.add(op)
                    }
                    continue
                }
            }

            val key = op.path.joinToString(".")
            if (op.op == "remove") {
                leafMap[key] = Acc("remove")
                continue
            }
            if (op.op == "add" || op.op == "replace") {
                val entry = op.entry
                if (entry is LDEntry) leafMap[key] = Acc("replace", entry = entry)
            }
        }

        // parentAdds removed from output entirely
        val leaves = mutableListOf<CRDTOperation>()
        val timestamp = System.currentTimeMillis()

        for ((k, acc) in leafMap) {
            val path = k.split(".")
            if (acc.op == "remove") {
                leaves.add(
                    CRDTOperation.Remove(
                        path = path, timestamp = timestamp, replicaId = clientReplicaId
                    )
                )
            } else {
                leaves.add(
                    CRDTOperation.Replace(
                        path = path, entry = acc.entry,
                        timestamp = timestamp, replicaId = clientReplicaId
                    )
                )
            }
        }

        parentAdds.sortBy { it.path.size }
        return parentAdds + leaves + arrayOps
    }

    // ---------- RGA visibility helpers ----------

    private fun pendingOpsFor(parentPath: List<String>): List<CRDTOperation> {
        val key = parentPath.joinToString(".")
        return pending.filter { op ->
            (op.op == "array-push" || op.op == "array-unshift" || op.op == "array-remove") &&
                    op.path.joinToString(".") == key
        }
    }

    private fun ensureArrayContainer(path: List<String>): LDArray {
        val cont = getContainerAt(path)
        if (cont is LDArray) return cont

        val parent = navigateToParent(path, true)
        val key = path.last()
        val newArr = LDArray(
            entries = mutableMapOf(),
            head = null,
            meta = LDMeta(
                updatedAt = System.currentTimeMillis(),
                version = 1,
                replicaId = clientReplicaId
            )
        )
        val entry = LDEntry(
            id = generateId(),
            key = key,
            type = LDEntryType.ARRAY,
            value = newArr,
            meta = LDMeta(
                updatedAt = System.currentTimeMillis(),
                version = 1,
                replicaId = clientReplicaId
            )
        )
        if (parent is LDMap) {
            parent.index[key] = entry
        } else {
            throw IllegalStateException("Cannot create named array inside an array item")
        }
        return newArr
    }

    private fun ensureMapParents(root: LDMap, path: List<String>, ts: Long, replicaId: String) {
        var node: Any = root
        for (i in 0 until path.size - 1) {
            val seg = path[i]
            when (node) {
                is LDMap -> {
                    if (!node.index.containsKey(seg)) {
                        node.index[seg] = LDEntry(
                            id = generateId(),
                            key = seg,
                            type = LDEntryType.OBJECT,
                            value = toLDValue(emptyMap<String, Any>())!!,
                            meta = LDMeta(updatedAt = ts, version = 1, replicaId = replicaId)
                        )
                    }
                    node = node.index[seg]!!.value!!
                }

                is LDArray -> {
                    if (!node.entries.containsKey(seg)) {
                        node.entries[seg] = LDEntry(
                            id = seg,
                            key = seg,
                            type = LDEntryType.OBJECT,
                            value = toLDValue(emptyMap<String, Any>())!!,
                            meta = LDMeta(
                                updatedAt = ts,
                                version = 1,
                                replicaId = replicaId,
                                after = null
                            )
                        )
                    }
                    node = node.entries[seg]!!.value!!
                }
            }
        }
    }

    private fun baseIdsFor(path: List<String>): List<String> {
        val arr = getContainerAt(path) as? LDArray ?: return emptyList()
        return linearizeRGA(arr)
    }

    // FIX: overlay pending array ops on top of snapshot (was just calling baseIdsFor before)
    private fun visibleIdsFor(path: List<String>): List<String> {
        val ids = baseIdsFor(path).toMutableList()
        val pend = pendingOpsFor(path)
        for (op in pend) {
            when (op.op) {
                "array-push" -> {
                    val after = op.ref
                    val pos = if (after != null) {
                        val i = ids.indexOf(after)
                        if (i >= 0) i + 1 else ids.size
                    } else ids.size
                    val entryId = (op.entry as? LDEntry)?.id ?: continue
                    ids.add(pos, entryId)
                }

                "array-unshift" -> {
                    val entryId = (op.entry as? LDEntry)?.id ?: continue
                    ids.add(0, entryId)
                }

                "array-remove" -> {
                    ids.remove(op.ref)
                }
            }
        }
        return ids
    }

    private fun getArrayIdAt(path: List<String>, idx: Int): String? {
        val ids = visibleIdsFor(path)
        val n = ids.size
        val i = if (idx < 0) n + idx else idx
        return if (i in 0 until n) ids[i] else null
    }

    // ---------- Navigation ----------

    private fun navigate(path: List<String>): LDValue {
        var node: LDValue = snapshot
        for (i in path.indices) {
            val seg = path[i]
            if (i == 0 && seg == "index") continue
            node = when (node) {
                is LDMap -> {
                    node.index[seg]?.value
                        ?: throw IllegalArgumentException("Path not found: ${path.joinToString(".")}")
                }

                is LDArray -> {
                    node.entries[seg]?.value
                        ?: throw IllegalArgumentException("Path not found: ${path.joinToString(".")}")
                }

                else -> throw IllegalStateException("Cannot navigate into primitive at \"$seg\"")
            }
        }
        return node
    }

    private fun readJSONAt(path: List<String>): Any? {
        return try {
            var node: Any? = snapshot
            for (seg in path) {
                node = when (node) {
                    is LDMap -> node.index[seg]?.value ?: return null
                    is LDArray -> node.entries[seg]?.value ?: return null
                    else -> return null
                }
            }
            toJSON(node)
        } catch (e: Exception) {
            null
        }
    }

    private fun navigateToParent(path: List<String>, forceCreate: Boolean): Any {
        var node: Any = snapshot
        for (i in 0 until path.size - 1) {
            val seg = path[i]
            if (i == 0 && seg == "index") continue
            when (node) {
                is LDMap -> {
                    if (!node.index.containsKey(seg)) {
                        if (!forceCreate) throw IllegalArgumentException(
                            "Invalid path: ${
                                path.joinToString(
                                    "."
                                )
                            }"
                        )
                        node.index[seg] = forceCreatePath(node, seg, path[i + 1])
                    }
                    node = toContainer(node.index[seg]!!.value)
                }

                is LDArray -> {
                    var e = node.entries[seg]
                    if (e == null) {
                        if (!forceCreate) throw IllegalArgumentException(
                            "Invalid path: ${
                                path.joinToString(
                                    "."
                                )
                            }"
                        )
                        e = LDEntry(
                            id = seg, key = seg,
                            type = LDEntryType.OBJECT,
                            value = toLDValue(emptyMap<String, Any>())
                                ?: throw IllegalStateException("Failed to create LDValue"),
                            meta = LDMeta(
                                updatedAt = System.currentTimeMillis(),
                                version = 1,
                                replicaId = clientReplicaId,
                                after = null
                            )
                        )
                        node.entries[seg] = e
                    }
                    node = toContainer(e.value)
                }
            }
        }
        return node
    }

    private fun forceCreatePath(node: Any, seg: String, nextSeg: String): LDEntry {
        val isArray = nextSeg.matches(Regex("^\\d+$")) || nextSeg.startsWith("[")
        val value =
            (if (isArray) toLDValue(emptyList<Any>()) else toLDValue(emptyMap<String, Any>()))
                ?: throw IllegalStateException("Failed to create LDValue")
        val entry = LDEntry(
            id = generateId(), key = seg,
            type = if (isArray) LDEntryType.ARRAY else LDEntryType.OBJECT,
            value = value,
            meta = LDMeta(
                updatedAt = System.currentTimeMillis(),
                version = 1,
                replicaId = clientReplicaId
            )
        )
        if (node is LDArray) throw IllegalStateException("Cannot create named path inside array")
        if (node is LDMap) node.index[seg] = entry
        return entry
    }

    private fun getContainerAt(path: List<String>): Any? {
        var node: Any? = snapshot
        for (seg in path) {
            node = when (node) {
                is LDMap -> node.index[seg]?.value ?: return null
                is LDArray -> node.entries[seg]?.value ?: return null
                else -> return null
            }
        }
        return when (node) {
            is LDMap, is LDArray -> node
            else -> null
        }
    }

    // ---------- JSON view ----------

    private fun toJSON(value: Any?): Any? {
        if (value is String || value is Number || value is Boolean) return value
        if (value is LDMap) {
            val out = mutableMapOf<String, Any?>()
            for ((k, e) in value.index) out[k] = toJSON(e.value)
            return out
        }
        if (value is LDArray) {
            val ids = linearizeRGA(value)
            return ids.mapNotNull { id -> value.entries[id]?.let { toJSON(it.value) } }
        }
        return null
    }

    private fun notifyListeners() {
        scope.launch {
            listeners.forEach { (pathKey, set) ->
                val segments = if (pathKey == "") emptyList() else pathKey.split(".")
                val currentData = try {
                    toJSON(navigate(segments))
                } catch (e: Exception) {
                    null
                }
                set.forEach { it(currentData) }
            }
        }
    }

    fun getState(): LDMap = snapshot
    fun setReplicaId(id: String) {
        clientReplicaId = id
    }

    fun getReplicaId(): String = clientReplicaId

    private fun makeProxy(parentPath: List<String>) = CRDTProxy(parentPath, this)

    /* ---------- CRDTProxy ---------- */

    class CRDTProxy(
        private val parentPath: List<String>,
        private val self: CRDT
    ) {
        fun length(): Int = self.visibleIdsFor(parentPath).size

        fun push(vararg items: Any?): Int {
            self.ensureArrayContainer(parentPath)
            val currentIds = self.visibleIdsFor(parentPath)
            var prev: String? = currentIds.lastOrNull()

            for (item in items) {
                if (item == null) continue
                val id = generateId()
                val now = System.currentTimeMillis()
                val converted = toLDValue(item, self.getReplicaId())
                val entry = LDEntry(
                    id = id, key = id,
                    type = determineType(converted),
                    value = converted,
                    meta = LDMeta(
                        updatedAt = now,
                        version = 1,
                        replicaId = self.clientReplicaId,
                        after = prev
                    )
                )
                val op = CRDTOperation.ArrayPush(
                    path = parentPath, entry = entry, ref = prev,
                    timestamp = now, replicaId = self.clientReplicaId
                )
                self.pending.add(op)
                prev = id
            }
            self.scheduleFlush()
            return self.visibleIdsFor(parentPath).size
        }

        fun pop(): Any? {
            val ids = self.visibleIdsFor(parentPath)
            if (ids.isEmpty()) return null
            val idToRemove = ids.last()
            val arr = self.getContainerAt(parentPath) as? LDArray

            val returnValue = arr?.entries?.get(idToRemove)?.value
            val op = CRDTOperation.ArrayRemove(
                ref = idToRemove, path = parentPath,
                timestamp = System.currentTimeMillis(), replicaId = self.clientReplicaId
            )
            self.pending.add(op)
            self.scheduleFlush()
            return returnValue
        }

        fun removeAt(index: Int): Any? {
            val ids = self.visibleIdsFor(parentPath)
            if (index !in ids.indices) return null
            val id = ids[index]
            val arr = self.getContainerAt(parentPath) as? LDArray
            val returnValue = arr?.entries?.get(id)?.value
            val op = CRDTOperation.ArrayRemove(
                ref = id, path = parentPath,
                timestamp = System.currentTimeMillis(), replicaId = self.clientReplicaId
            )
            self.pending.add(op)
            self.scheduleFlush()
            return returnValue
        }

        operator fun get(index: Int): Any? {
            val ids = self.visibleIdsFor(parentPath)
            if (index !in ids.indices) return null
            val id = ids[index]
            val maybeNode = self.getContainerAt(parentPath)
            if (maybeNode is LDArray) {
                val valNode = maybeNode.entries[id]?.value
                if (valNode is LDMap || valNode is LDArray) return self.makeProxy(parentPath + id)
                return valNode
            }
            return null
        }

        operator fun get(key: String): Any? {
            val fullPath = parentPath + key
            val data = self.readJSONAt(fullPath)
            if (data is Map<*, *> || data is List<*>) return self.makeProxy(fullPath)
            return data
        }

        operator fun set(index: Int, value: Any?) {
            val ids = self.visibleIdsFor(parentPath)
            if (index !in ids.indices) return
            val id = ids[index]
            val now = System.currentTimeMillis()
            val converted = toLDValue(value, self.getReplicaId())
            val entry = LDEntry(
                id = id, key = id,
                type = determineType(converted),
                value = converted,
                meta = LDMeta(updatedAt = now, version = 1, replicaId = self.clientReplicaId)
            )
            (self.getContainerAt(parentPath) as? LDArray)?.entries?.set(id, entry)
            val op = CRDTOperation.Replace(
                path = parentPath + id,
                entry = entry,
                timestamp = now,
                replicaId = self.clientReplicaId
            )
            self.pending.add(op)
            self.scheduleFlush()
        }

        operator fun set(key: String, value: Any?) {
            val now = System.currentTimeMillis()
            val fullPath = parentPath + key
            val converted = toLDValue(value, self.getReplicaId())
            val entry = LDEntry(
                id = generateId(), key = key,
                type = determineType(converted),
                value = converted,
                meta = LDMeta(updatedAt = now, version = 1, replicaId = self.clientReplicaId)
            )
            try {
                self.ensureMapParents(self.getState(), fullPath, now, self.clientReplicaId)
                val parentNode = self.navigateToParent(fullPath, true)
                if (parentNode is LDMap) parentNode.index[key] = entry
                val op = CRDTOperation.Replace(
                    path = fullPath,
                    entry = entry,
                    timestamp = now,
                    replicaId = self.clientReplicaId
                )
                // Snapshot was already mutated in-place above; do NOT
                // re-merge locally — the original port double-applied
                // every local op which racked up redundant listener fires
                // and could re-shuffle RGA `after` chains.
                self.pending.add(op)
                self.scheduleFlush()
            } catch (e: Exception) {
                AdkLog.e("ArtCRDTProxy", "Failed to set property $key", e)
            }
        }

        fun remove(key: String) {
            val now = System.currentTimeMillis()
            val fullPath = parentPath + key
            try {
                val parentNode = self.navigateToParent(fullPath, false)
                if (parentNode is LDMap) parentNode.index.remove(key)
                self.pending.add(
                    CRDTOperation.Remove(
                        path = fullPath,
                        timestamp = now,
                        replicaId = self.clientReplicaId
                    )
                )
                self.scheduleFlush()
            } catch (e: Exception) {
                // safe delete — ignore if path doesn't exist
            }
        }

        fun set(value: Map<String, Any?>) {
            value.forEach { (key, v) -> this[key] = v }
        }

        fun splice(index: Int, removeCount: Int, vararg items: Any?) {
            val ids = self.visibleIdsFor(parentPath)
            val toRemove = ids.drop(index).take(removeCount)
            toRemove.forEach { id ->
                self.pending.add(
                    CRDTOperation.ArrayRemove(
                        ref = id, path = parentPath,
                        timestamp = System.currentTimeMillis(), replicaId = self.clientReplicaId
                    )
                )
            }

            if (items.isNotEmpty()) {
                val currentIds = self.visibleIdsFor(parentPath)
                var prev: String? = when {
                    index > 0 && index <= currentIds.size -> currentIds[index - 1]
                    index == 0 -> null
                    else -> currentIds.lastOrNull()
                }
                for (item in items) {
                    val id = generateId()
                    val now = System.currentTimeMillis()
                    val converted = toLDValue(item, self.getReplicaId())
                    val entry = LDEntry(
                        id = id, key = id,
                        type = determineType(converted),
                        value = converted,
                        meta = LDMeta(
                            updatedAt = now,
                            version = 1,
                            replicaId = self.clientReplicaId,
                            after = prev
                        )
                    )
                    val op = if (prev != null) {
                        CRDTOperation.ArrayPush(
                            path = parentPath,
                            entry = entry,
                            ref = prev,
                            timestamp = now,
                            replicaId = self.clientReplicaId
                        )
                    } else {
                        CRDTOperation.ArrayUnshift(
                            path = parentPath,
                            entry = entry,
                            timestamp = now,
                            replicaId = self.clientReplicaId
                        )
                    }
                    self.pending.add(op)
                    prev = id
                }
            }
            self.scheduleFlush()
        }
    }

    // ---------- ensureParents ----------

    private fun ensureParents(full: List<String>): List<CRDTOperation> {
        val ops = mutableListOf<CRDTOperation>()
        for (i in 0 until full.size - 1) {
            val sub = full.subList(0, i + 1)
            if (readJSONAt(sub) != null) continue
            val seg = full[i]
            val parentCont = getContainerAt(full.subList(0, i))
            if (parentCont is LDArray) continue
            val now = System.currentTimeMillis()
            val entry = LDEntry(
                id = generateId(), key = seg,
                type = LDEntryType.OBJECT,
                value = toLDValue(emptyMap<String, Any>())!!,
                meta = LDMeta(updatedAt = now, version = 1, replicaId = clientReplicaId)
            )
            ops.add(
                CRDTOperation.Add(
                    path = sub.toList(),
                    entry = entry,
                    timestamp = now,
                    replicaId = clientReplicaId
                )
            )
        }
        return ops
    }

    // ---------- Merge ----------

    fun merge(ops: List<CRDTOperation>) {
        for (op in ops) {

            if (op.op == "array-push" || op.op == "array-unshift") {
                val arr = ensureArrayContainer(op.path)
                val entryObj = op.entry ?: continue   // FIX: continue, not return
                val e = when {
                    entryObj is LDEntry -> entryObj
                    entryObj is Map<*, *> -> reconstructLDEntry(entryObj as Map<String, Any?>)
                    else -> continue
                }
                if (op.op == "array-push") e.meta.after = op.ref
                if (op.op == "array-unshift") e.meta.after = null
                e.meta.updatedAt = op.timestamp
                e.meta.replicaId = op.replicaId
                arr.entries[e.id] = e
                continue
            }

            if (op.op == "array-remove") {
                val arr = ensureArrayContainer(op.path)
                val target = arr.entries[op.ref]
                if (target != null) {
                    if (target.meta.tombstone != true || target.meta.updatedAt <= op.timestamp) {
                        target.meta.tombstone = true
                        target.meta.updatedAt = op.timestamp
                        target.meta.replicaId = op.replicaId
                    }
                }
                continue
            }

            if (op.op == "remove") {
                val parent = navigateToParent(op.path, false)
                val key = op.path.last()
                when (parent) {
                    is LDMap -> parent.index.remove(key)
                    is LDArray -> parent.entries.remove(key)
                }
                continue
            }

            if (op.op == "add" || op.op == "replace") {
                val parent: Any
                try {
                    ensureMapParents(snapshot, op.path, op.timestamp, op.replicaId)
                    parent = navigateToParent(op.path, op.op == "add")
                } catch (err: Exception) {
                    val p = op.path
                    if (op.op == "replace" && p.size >= 3) {
                        val arrayPath = p.dropLast(2)
                        val elemId = p[p.size - 2]
                        val maybeArr = getContainerAt(arrayPath)
                        if (maybeArr is LDArray) {
                            if (!maybeArr.entries.containsKey(elemId)) {
                                maybeArr.entries[elemId] = LDEntry(
                                    id = elemId, key = elemId,
                                    type = LDEntryType.OBJECT,
                                    value = toLDValue(emptyMap<String, Any>())
                                        ?: throw IllegalStateException("Failed to create LDValue"),
                                    meta = LDMeta(
                                        updatedAt = op.timestamp, version = 1,
                                        replicaId = op.replicaId, after = null
                                    )
                                )
                            }
                            val resolvedParent = try {
                                navigateToParent(op.path, false)
                            } catch (e2: Exception) {
                                continue
                            }
                            applyEntryToParent(resolvedParent, op)
                        } else {
                            AdkLog.w(TAG, "merge: could not resolve parent for ${op.path}", err)
                        }
                    } else {
                        AdkLog.w(TAG, "merge: could not resolve parent for ${op.path}", err)
                    }
                    continue
                }
                applyEntryToParent(parent, op)
                continue
            }
        }

        val affected = ops.map { it.path.joinToString(".") }.toSet()
        listeners.forEach { (subPath, callbacks) ->
            if (affected.any { it.startsWith(subPath) }) {
                scope.launch {
                    val json = query(subPath).execute()
                    callbacks.forEach { cb -> cb(json) }
                }
            }
        }
    }

    private fun applyEntryToParent(parent: Any, op: CRDTOperation) {
        val key = op.path.last()
        val entryObj = op.entry ?: run {
            AdkLog.w(TAG, "applyEntryToParent: entry is null for path ${op.path}")
            return
        }
        val entry: LDEntry = when {
            entryObj is LDEntry -> entryObj
            entryObj is Map<*, *> && (entryObj as Map<*, *>).containsKey("id") ->
                reconstructLDEntry(entryObj as Map<String, Any?>)

            else -> {
                val convertedValue = when {
                    entryObj is LDMap || entryObj is LDArray -> entryObj
                    entryObj is Map<*, *> -> when {
                        (entryObj as Map<*, *>).containsKey("index") -> reconstructLDMap(entryObj as Map<String, Any?>)
                        entryObj.containsKey("entries") -> reconstructLDArray(entryObj as Map<String, Any?>)
                        else -> toLDValue(entryObj, op.replicaId)
                    }

                    else -> toLDValue(entryObj, op.replicaId)
                }
                LDEntry(
                    id = generateId(), key = key,
                    type = determineType(convertedValue),
                    value = convertedValue,
                    meta = LDMeta(updatedAt = op.timestamp, version = 1, replicaId = op.replicaId)
                )
            }
        }
        when (parent) {
            is LDMap -> parent.index[key] = entry
            is LDArray -> parent.entries[key] = entry
        }
    }

    //Query

    fun query(path: String? = null): QueryResult {
        val segments = when {
            path.isNullOrEmpty() || path == "index" -> emptyList()
            else -> path.split(".")
        }

        suspend fun execute(): Any? = try {
            toJSON(navigate(segments))
        } catch (e: Exception) {
            null
        }

        suspend fun listen(cb: Listener): () -> Unit {
            val key = path ?: ""
            listeners.getOrPut(key) { mutableSetOf() }.add(cb)
            cb(execute())
            return { listeners[key]?.remove(cb) }
        }
        return QueryResult(execute = ::execute, listen = ::listen)
    }

    data class QueryResult(
        val execute: suspend () -> Any?,
        val listen: suspend (Listener) -> () -> Unit
    )

    companion object {
        fun reconstructLDEntry(map: Map<String, Any?>): LDEntry {
            val metaMap = map["meta"] as? Map<String, Any?> ?: emptyMap()
            val typeStr = map["type"] as? String ?: "object"
            val value = map["value"]
            val convertedValue = when (typeStr) {
                "object" -> if (value is Map<*, *>) reconstructLDMap(value as Map<String, Any?>) else value
                "array" -> if (value is Map<*, *>) reconstructLDArray(value as Map<String, Any?>) else value
                else -> value
            }
            return LDEntry(
                id = map["id"] as? String ?: generateId(),
                key = map["key"] as? String ?: "",
                type = LDEntryType.fromWire(typeStr),
                value = convertedValue,
                meta = reconstructLDMeta(metaMap)
            )
        }

        fun reconstructLDMap(map: Map<String, Any?>): LDMap {
            val index = mutableMapOf<String, LDEntry>()
            val rawIndex = map["index"] as? Map<String, Any?> ?: emptyMap()
            for ((k, v) in rawIndex) {
                if (v is Map<*, *>) index[k] = reconstructLDEntry(v as Map<String, Any?>)
                else AdkLog.w("ArtCRDTReconstruct", "Skipping non-map index entry: $k -> $v")
            }
            return LDMap(index, reconstructLDMeta(map["meta"] as? Map<String, Any?> ?: emptyMap()))
        }

        fun reconstructLDArray(map: Map<String, Any?>): LDArray {
            val entries = mutableMapOf<String, LDEntry>()
            val rawEntries = map["entries"] as? Map<String, Any?> ?: emptyMap()
            for ((k, v) in rawEntries) {
                if (v is Map<*, *>) entries[k] = reconstructLDEntry(v as Map<String, Any?>)
            }
            return LDArray(
                entries,
                map["head"] as? String,
                reconstructLDMeta(map["meta"] as? Map<String, Any?> ?: emptyMap())
            )
        }

        fun reconstructLDMeta(map: Map<String, Any?>): LDMeta {
            return LDMeta(
                updatedAt = (map["updatedAt"] as? Number)?.toLong() ?: 0L,
                version = (map["version"] as? Number)?.toInt() ?: 0,
                replicaId = map["replicaId"] as? String ?: "",
                order = (map["order"] as? Number)?.toInt(),
                after = map["after"] as? String,
                next = map["next"] as? String,
                tombstone = map["tombstone"] as? Boolean
            )
        }
    }
}
