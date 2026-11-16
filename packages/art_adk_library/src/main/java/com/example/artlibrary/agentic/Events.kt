package com.example.artlibrary.agentic

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Typed event envelopes emitted on the `agent_com_<agentId>` channel, plus the
 * per-thread operational state and its store. Port of the Swift ADK's
 * `Agentic/Events.swift` (+ `ThreadStateStore.swift`) — the envelope and
 * `parseAgentEvent` live here, in the event file, not in a separate one.
 *
 * The wire shape is `{ event, content }`. The backend's canonical discriminator
 * is `content.type` — the top-level wire `event` is now `user_input` for every
 * agent reply, so it can no longer be used. `parseAgentEvent` normalises every
 * recognised envelope's `event` to one of [agentEvents]; anything else is
 * forwarded as [UnknownAgentEvent].
 */

/**
 * Routing/correlation fields injected into every egress `content` body by
 * `_content_with_meta` on the server. May be empty strings if the upstream task
 * lacked them.
 */
interface EnvelopeMeta {
    val thread_id: String
    val ref_id: String
    val agent_id: String
    /** ref_id of the request this message is responding to. */
    val reply_to: String
}

/**
 * Error response from an agent run; terminal for the originating [Run]. Carried
 * by [AgentErrorException] so a run can surface it through [Run.done].
 */
data class AgentError(
    override val thread_id: String,
    override val ref_id: String,
    override val agent_id: String,
    override val reply_to: String,
    val type: String = "agent_error_response",
    val status: String = "error",
    /** Framework codes are defined in `response_types.ErrorCode`; agents may
     *  also emit free-form codes from prompts. */
    val code: String,
    val message: String,
    val details: Map<String, Any?>? = null
) : EnvelopeMeta

/** Where a run was started, sent as `execution_origin` (Swift `ExecutionOrigin`). */
enum class ExecutionOrigin(val value: String) {
    PLAYGROUND("playground")
}

/** The canonical agent event types (Swift `agentEvents`). */
val agentEvents: List<String> = listOf(
    "agent_general_response",
    "agent_error_response",
    "human_input_request",
    "agent_wait_response",
    "planner_correction_request",
    "thread_state",
)

/** Returns `true` if [name] is a recognised canonical agent event type. */
fun isKnownAgentEvent(name: String): Boolean = name in agentEvents

/** Discriminated envelope wrapping any inbound agent event (Swift `AgentEventEnvelope`). */
data class AgentEventEnvelope(
    /** The normalised canonical event type — one of [agentEvents] when known,
     *  otherwise the raw wire event name. */
    val event: String,
    /** The typed payload. */
    val payload: AgentEventPayload,
    /** The event's fields exactly as the server sent them. */
    val content: Map<String, Any?> = emptyMap(),
) {
    /** Whether [payload] is one of the typed (non-Unknown) variants. */
    val isKnown: Boolean get() = isKnownAgentEvent(event)
}

/** The typed payload carried by an [AgentEventEnvelope] (Swift `AgentEvent`). */
sealed class AgentEventPayload {
    data class Output(val content: AgentOutputContent) : AgentEventPayload()
    data class Error(val content: AgentErrorContent) : AgentEventPayload()
    data class HumanInput(val content: HumanInputContent) : AgentEventPayload()
    data class Wait(val content: AgentWaitContent) : AgentEventPayload()
    data class PlannerCorrection(val content: PlannerCorrectionContent) : AgentEventPayload()
    data class ThreadState(val state: com.example.artlibrary.agentic.ThreadState) : AgentEventPayload()
    data class Unknown(val unknown: UnknownAgentEvent) : AgentEventPayload()
}

/** Successful terminal response from an agent run (Swift `AgentOutput`). */
data class AgentOutputContent(
    val message: String,
    val data: Map<String, Any?>?,
    val metadata: Map<String, Any?>?,
    val threadId: String,
    val refId: String,
    val agentId: String,
    val replyTo: String,
) {
    val type: String get() = "agent_general_response"
    val status: String get() = "final_response"

    companion object {
        fun from(m: Map<String, Any?>) = AgentOutputContent(
            message = m.str("message"),
            data = m.map("data"),
            metadata = m.map("metadata"),
            threadId = m.str("thread_id"),
            refId = m.str("ref_id"),
            agentId = m.str("agent_id"),
            replyTo = m.str("reply_to"),
        )
    }
}

/** Error response content from an agent run (Swift `AgentError`). */
data class AgentErrorContent(
    val code: String,
    val message: String,
    val details: Map<String, Any?>?,
    val threadId: String = "",
    val refId: String = "",
    val agentId: String = "",
    val replyTo: String = "",
) {
    val type: String get() = "agent_error_response"
    val status: String get() = "error"

    /** The snake_case [AgentError] carried by [AgentErrorException]. */
    fun toAgentError() = AgentError(
        thread_id = threadId,
        ref_id = refId,
        agent_id = agentId,
        reply_to = replyTo,
        code = code,
        message = message,
        details = details,
    )

    companion object {
        fun from(m: Map<String, Any?>) = AgentErrorContent(
            code = m.str("code"),
            message = m.str("message"),
            details = m.map("details"),
            threadId = m.str("thread_id"),
            refId = m.str("ref_id"),
            agentId = m.str("agent_id"),
            replyTo = m.str("reply_to"),
        )
    }
}

/** An interactive prompt the agent is awaiting a human reply on (Swift `HumanInputRequest`). */
data class HumanInputContent(
    val prompt: String,
    val context: Map<String, Any?>?,
    /** The raw `expected_response_type` string as received (defaults to "text"). */
    val expectedResponseTypeRaw: String,
    /** Optional client-side timeout in seconds; the Run enforces it. */
    val timeout: Double?,
    val schema: Any?,
    val threadId: String,
    val refId: String,
    val agentId: String,
    val replyTo: String,
) {
    val type: String get() = "human_input_request"
    val status: String get() = "awaiting_input"

    companion object {
        fun from(m: Map<String, Any?>) = HumanInputContent(
            prompt = m.str("prompt"),
            context = m.map("context"),
            expectedResponseTypeRaw = m["expected_response_type"] as? String ?: "text",
            timeout = m.double("timeout"),
            schema = m["schema"],
            threadId = m.str("thread_id"),
            refId = m.str("ref_id"),
            agentId = m.str("agent_id"),
            replyTo = m.str("reply_to"),
        )
    }
}

/** Notification that the agent is awaiting another agent (Swift `AgentWait`). */
data class AgentWaitContent(
    val waitingForAgentId: String,
    val invocationId: String?,
    val reason: String?,
    val timeout: Double?,
    val progress: Map<String, Any?>?,
    val threadId: String,
    val refId: String,
    val agentId: String,
    val replyTo: String,
) {
    val type: String get() = "agent_wait_response"
    val status: String get() = "waiting_for_agent"

    companion object {
        fun from(m: Map<String, Any?>) = AgentWaitContent(
            waitingForAgentId = m.str("waiting_for_agent_id"),
            invocationId = m["invocation_id"] as? String,
            reason = m["reason"] as? String,
            timeout = m.double("timeout"),
            progress = m.map("progress"),
            threadId = m.str("thread_id"),
            refId = m.str("ref_id"),
            agentId = m.str("agent_id"),
            replyTo = m.str("reply_to"),
        )
    }
}

/** Server-initiated planner correction request (Swift `PlannerCorrection`). */
data class PlannerCorrectionContent(
    val correctionRequired: Boolean,
    val reason: String,
    val newGoal: String?,
    val suggestedAgents: List<String>?,
    val threadId: String,
    val refId: String,
    val agentId: String,
    val replyTo: String,
) {
    val type: String get() = "planner_correction_request"

    companion object {
        fun from(m: Map<String, Any?>) = PlannerCorrectionContent(
            correctionRequired = m["correction_required"] as? Boolean ?: false,
            reason = m.str("reason"),
            newGoal = m["new_goal"] as? String,
            suggestedAgents = (m["suggested_agents"] as? List<*>)?.map { "$it" },
            threadId = m.str("thread_id"),
            refId = m.str("ref_id"),
            agentId = m.str("agent_id"),
            replyTo = m.str("reply_to"),
        )
    }
}

/** Catch-all envelope when the server emits an event the SDK doesn't yet model. */
data class UnknownAgentEvent(
    val event: String,
    val content: Map<String, Any?>
)

/**
 * Operational state of a thread, surfaced via [AgentThread.listenState].
 *
 * `phase`: idle | submitted | waiting_for_approval | waiting_for_workspace |
 *          waiting_for_agent | completed | failed.
 * `source`: client | agent | workspace.
 * `sequence` monotonically orders states; out-of-order updates are dropped.
 */
data class ThreadState(
    val thread_id: String,
    val phase: String,
    val source: String,
    val message: String,
    val occurred_at: String,
    val sequence: Int? = null,
    val reason: String? = null,
    val workspace_id: String? = null,
    val agent_id: String? = null,
)

/**
 * Holds one thread's current [ThreadState] and its `listenState` callbacks.
 * Shared by [AgentThread] (sequence-checked) and [OrchestratorThread] (no
 * sequence check). Port of the Swift ADK's `ThreadStateStore`.
 */
internal class ThreadStateStore(
    val threadId: String,
    private val enforcesSequence: Boolean,
) {
    private val lock = Any()
    private var current: ThreadState = ThreadState(
        thread_id = threadId, phase = "idle", source = "client",
        message = "Ready", occurred_at = isoTimestamp(),
    )
    private val listeners = LinkedHashMap<String, (ThreadState) -> Unit>()

    /** Snapshot of the current state. */
    val state: ThreadState get() = synchronized(lock) { current }

    val hasListeners: Boolean get() = synchronized(lock) { listeners.isNotEmpty() }

    fun addListener(callback: (ThreadState) -> Unit): String {
        val id = UUID.randomUUID().toString()
        synchronized(lock) { listeners[id] = callback }
        return id
    }

    fun removeListener(id: String) {
        synchronized(lock) { listeners.remove(id) }
    }

    fun removeAllListeners() {
        synchronized(lock) { listeners.clear() }
    }

    /**
     * Applies a state, ignoring states for other threads and (for agent threads)
     * states older than the current `sequence`. Listeners are notified outside
     * the lock.
     */
    fun apply(incoming: ThreadState) {
        val result: Pair<ThreadState, List<(ThreadState) -> Unit>>? = synchronized(lock) {
            if (incoming.thread_id.isNotEmpty() && incoming.thread_id != threadId) {
                return@synchronized null
            }
            var next = incoming.copy(thread_id = threadId)
            if (enforcesSequence) {
                val previous = current.sequence ?: -1
                val sequence = incoming.sequence ?: (previous + 1)
                if (sequence < previous) return@synchronized null
                next = next.copy(sequence = sequence)
            }
            current = next
            next to listeners.values.toList()
        }
        result?.let { (state, callbacks) -> callbacks.forEach { it(state) } }
    }

    /** Records a client-side transition. */
    fun transition(
        phase: String,
        source: String,
        message: String,
        reason: String? = null,
        workspaceId: String? = null,
        agentId: String? = null,
    ) {
        apply(
            ThreadState(
                thread_id = threadId, phase = phase, source = source, message = message,
                occurred_at = isoTimestamp(), reason = reason,
                workspace_id = workspaceId, agent_id = agentId,
            )
        )
    }

    private companion object {
        /** ISO-8601 UTC timestamp, without a `java.time` (API-26) dependency. */
        fun isoTimestamp(): String {
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("UTC")
            return fmt.format(Date())
        }
    }
}

/** Parses a `thread_state` content map (Swift `ThreadState.init(from:)`). */
fun threadStateFrom(m: Map<String, Any?>): ThreadState = ThreadState(
    thread_id = m.str("thread_id"),
    phase = m["phase"] as? String ?: "idle",
    source = m["source"] as? String ?: "client",
    message = m.str("message"),
    occurred_at = m.str("occurred_at"),
    sequence = m.int("sequence"),
    reason = m["reason"] as? String,
    workspace_id = m["workspace_id"] as? String,
    agent_id = m["agent_id"] as? String,
)

/**
 * Parses a raw `{ event, content }` envelope into a typed [AgentEventEnvelope]
 * (Swift `parseAgentEvent`). Discrimination is by `content.type`; the wire `event`
 * is only a legacy fallback. The returned `event` is normalised to the canonical type.
 */
fun parseAgentEvent(raw: Map<String, Any?>): AgentEventEnvelope {
    val wireEvent = raw["event"] as? String ?: ""
    val outerContent = agentContentMap(raw["content"]) ?: raw
    val contentMap = resolveAgentContent(outerContent)

    val rawType = contentMap["type"] as? String ?: ""
    val type = rawType.ifEmpty { wireEvent }

    return when (type) {
        "agent_general_response", "agent_output" -> AgentEventEnvelope(
            "agent_general_response", AgentEventPayload.Output(AgentOutputContent.from(contentMap)), contentMap
        )
        "agent_error_response", "agent_error" -> AgentEventEnvelope(
            "agent_error_response", AgentEventPayload.Error(AgentErrorContent.from(contentMap)), contentMap
        )
        "human_input_request" -> AgentEventEnvelope(
            "human_input_request", AgentEventPayload.HumanInput(HumanInputContent.from(contentMap)), contentMap
        )
        "agent_wait_response", "agent_wait" -> AgentEventEnvelope(
            "agent_wait_response", AgentEventPayload.Wait(AgentWaitContent.from(contentMap)), contentMap
        )
        "planner_correction_request", "planner_correction" -> AgentEventEnvelope(
            "planner_correction_request",
            AgentEventPayload.PlannerCorrection(PlannerCorrectionContent.from(contentMap)),
            contentMap
        )
        "thread_state" -> AgentEventEnvelope(
            "thread_state", AgentEventPayload.ThreadState(threadStateFrom(contentMap)), contentMap
        )
        else -> AgentEventEnvelope(
            wireEvent, AgentEventPayload.Unknown(UnknownAgentEvent(wireEvent, contentMap)), contentMap
        )
    }
}

/**
 * `value` as a field map: an object as-is, or an object encoded as a JSON string.
 * The agent backend double-encodes some bodies (a JSON string holding a JSON
 * string), so a string that decodes to another string is decoded once more.
 */
private fun agentContentMap(value: Any?): Map<String, Any?>? = when (value) {
    is Map<*, *> -> value.entries.associate { (k, v) -> k.toString() to v }
    is String -> {
        var parsed: Any? = parseJson(value)
        if (parsed is String) parsed = parseJson(parsed)
        @Suppress("UNCHECKED_CAST")
        parsed as? Map<String, Any?>
    }
    else -> null
}

/**
 * Resolves the variant map inside a raw content payload: it may sit at the top
 * level (`raw["type"]`) or one level deeper (`raw["content"]["type"]`).
 */
private fun resolveAgentContent(raw: Map<String, Any?>): Map<String, Any?> {
    if (raw["type"] is String) return raw
    val nested = agentContentMap(raw["content"])
    if (nested != null && nested["type"] is String) return nested
    return raw
}

private fun parseJson(text: String): Any? =
    runCatching { unwrapJson(JSONTokener(text).nextValue()) }.getOrNull()

private fun unwrapJson(value: Any?): Any? = when (value) {
    is JSONObject -> value.keys().asSequence().associateWith { unwrapJson(value.opt(it)) }
    is JSONArray -> (0 until value.length()).map { unwrapJson(value.opt(it)) }
    JSONObject.NULL -> null
    else -> value
}

private fun Map<String, Any?>.str(key: String): String = this[key] as? String ?: ""

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.map(key: String): Map<String, Any?>? =
    (this[key] as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v }

private fun Map<String, Any?>.double(key: String): Double? = when (val v = this[key]) {
    is Number -> v.toDouble()
    is String -> v.toDoubleOrNull()
    else -> null
}

private fun Map<String, Any?>.int(key: String): Int? = when (val v = this[key]) {
    is Number -> v.toInt()
    is String -> v.toIntOrNull()
    else -> null
}
