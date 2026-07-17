package com.example.artlibrary.agentic

import kotlinx.coroutines.*


typealias UserListener = (e: AgentEventOrUnknown) -> Unit
typealias HumanInputHandler = suspend (req: HumanInputRequest, run: Run) -> Unit

data class RunDeps(val reply_id: String? = null)

class AgentThread(val agent: Agent, threadId: String? = null) {
    val threadId: String = threadId ?: generateThreadId()
    private var masterListenerJob: Deferred<Unit>? = null
    private val userListeners: MutableList<UserListener> = mutableListOf()
    private val feedbackRequestHandlers: MutableList<HumanInputHandler> = mutableListOf()
    private var activeRun: Run? = null

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private fun generateThreadId(): String {
        return "thread_${System.currentTimeMillis()}_${(Math.random() * 36).toInt().toString(36)}"
    }

    /**
     * Install the single subscription-level listener that fans out to all
     * user `listen` callbacks and the active `Run`. Idempotent — first caller
     * wins; subsequent calls await the same job.
     *
     * Uses `async`/`await` rather than `launch`/`join`: `join` does not rethrow,
     * so a failed subscribe would escape the scope uncaught and kill the process
     * instead of surfacing to the caller. On failure the job is cleared so a
     * later `listen`/`run` can retry the subscription.
     */
    private suspend fun ensureMasterListener() {
        masterListenerJob?.let { return it.await() }

        val job = scope.async {
            val sub = agent.getSubscription()
            sub.listen { raw -> dispatch(raw) }
            sub.attachThreadListener(threadId) { raw -> dispatch(raw) }
        }
        masterListenerJob = job

        try {
            job.await()
        } catch (e: Exception) {
            if (masterListenerJob === job) masterListenerJob = null
            throw e
        }
    }

    fun dispatch(raw: Any?) {
        var evt = when (raw) {
            is AgentEventOrUnknown -> raw
            is Map<*, *> -> AgentEventOrUnknown(
                event = raw["event"]?.toString() ?: "agent_output",
                content = raw["content"] ?: emptyMap<String, Any?>()
            )

            else -> AgentEventOrUnknown(
                event = "agent_output",
                content = raw ?: emptyMap<String, Any?>()
            )
        }

        // Surface wire-level transport errors as typed `agent_error`
        // so the active Run rejects and user listeners get a normalized envelope.
        if (evt.event == "error" || evt.event == "transport_error") {
            val c = evt.content as? Map<*, *> ?: emptyMap<Any?, Any?>()
            val message = (c["message"] as? String)
                ?: (c["error"] as? String)
                ?: "WebSocket error"
            evt = AgentEventOrUnknown(
                event = "agent_error",
                content = AgentError(
                    type = "agent_error_response",
                    status = "error",
                    code = "TRANSPORT_ERROR",
                    message = message,
                    details = (c as? Map<String, Any?>),
                    thread_id = threadId,
                    ref_id = "",
                    agent_id = agent.agentId,
                    reply_to = ""
                )
            )
        }

        if (!isKnownAgentEvent(evt)) {
            println(
                "[adk] unknown agent event \"${evt.event}\" — passing through untyped. " +
                        "Add it to AGENT_EVENTS in agentic/events.ts to type it."
            )
        }

        activeRun?.let { run ->
            try {
                run.push(evt)
            } catch (e: Exception) {
                println("[adk] active run dispatch failed: $e")
            }
        }

        for (callback in userListeners) {
            try {
                callback(evt)
            } catch (e: Exception) {
                println("[adk] user listen callback threw: $e")
            }
        }
    }

    /**
     * Subscribe to typed events from this agent's channel.
     *
     * Multiple `listen` callbacks may be registered; each receives every event.
     * The callback receives a discriminated `AgentEventOrUnknown` envelope —
     * `evt.event` narrows `evt.content` to the matching shape (`AgentOutput`,
     * `AgentError`, `HumanInputRequest`, etc.). Unknown event names are logged
     * via `console.warn` and forwarded as `UnknownAgentEvent`.
     */
    suspend fun listen(callback: UserListener) {
        ensureMasterListener()
        userListeners.add(callback)
    }

    /**
     * Register a handler invoked whenever a `human_input_request` arrives for
     * an active run. The handler is responsible for collecting input from the
     * user and calling `run.sendFeedback(value)` to continue the loop.
     */
    fun feedbackRequest(handler: HumanInputHandler) {
        feedbackRequestHandlers.add(handler)
    }

    /** @internal — invoked by Run when a human_input_request arrives. */
    fun fireRequestFeedback(req: HumanInputRequest, run: Run) {
        for (feedbackHandler in feedbackRequestHandlers) {
            scope.launch {
                try {
                    feedbackHandler(req, run)
                } catch (e: Exception) {
                    println("[adk] onHumanInput handler threw: $e")
                }
            }
        }
    }

    /** @internal — clear active run on terminal event. */
    fun closeRun(run: Run) {
        if (activeRun === run) activeRun = null
    }

    /** @internal — send a `user_reply` carrying `reply_id`. */
    suspend fun sendReply(value: Any?, replyId: String): String {
        val sub = agent.getSubscription()
        val content: MutableMap<String, Any?> = mutableMapOf(
            "user_input" to value,
            "thread_id" to threadId,
            "reply_id" to replyId
        )
        // Subscription.push declares Promise<void> but actually resolves with
        // the generated ref_id; cast through unknown for type safety.
        val ack = sub.push("user_reply", content)
        return if (ack is String) ack else ""
    }

    /**
     * Start a new run on this thread. Returns a `Run` handle whose `done()`
     * promise resolves with the terminal `AgentOutput` or rejects with an
     * `AgentError`. Multi-turn HITL stays inside this run; respond via
     * `run.sendFeedback(value)` or by registering `onHumanInput`.
     *
     * If a previous run is still active, it is force-closed with a warning —
     * each thread may only have one active run at a time.
     */
    suspend fun run(user_input: Any?, deps: RunDeps? = null): Run {
        ensureMasterListener()

        activeRun?.let { existingRun ->
            if (!existingRun.isClosed()) {
                println(
                    "[adk] starting new run while previous run is still active — closing previous"
                )
                existingRun.close("Superseded by new run on the same thread")
            }
        }

        val run = Run(this)
        activeRun = run

        val sub = agent.getSubscription()
        val event = if (deps?.reply_id != null) "user_reply" else "user_input"
        val content: MutableMap<String, Any?> = mutableMapOf(
            "user_input" to user_input,
            "thread_id" to threadId
        )
        if (deps?.reply_id != null) content["reply_id"] = deps.reply_id

        // Subscription.push declares Promise<void> but resolves with ref_id.
        val ack = sub.push(event, content)
        run.setRefId(if (ack is String) ack else "")
        return run
    }
}
