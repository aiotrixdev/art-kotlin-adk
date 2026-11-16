package com.example.artlibrary.agentic

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.example.artlibrary.storage.FileRef
import com.example.artlibrary.storage.ListFilesResult
import com.example.artlibrary.storage.ListOptions
import com.example.artlibrary.storage.Storage
import com.example.artlibrary.storage.UploadOptions
import com.example.artlibrary.types.ConnectionError
import com.example.artlibrary.types.MediaRef
import com.example.artlibrary.types.PushConfig
import com.example.artlibrary.websockets.Subscription
import kotlinx.coroutines.*
import java.util.UUID

/** Callback fired for every event delivered to a thread (Swift `AgentUserListener`). */
typealias UserListener = (e: AgentEventEnvelope) -> Unit

/**
 * Fired when the agent emits a human-input request for the active [Run]. The
 * handler collects input and calls `run.sendFeedback(...)` (Swift
 * `AgentHumanInputHandler`).
 */
typealias HumanInputHandler = (req: HumanInputContent, run: Run) -> Unit

/** Kept for source compatibility; [AgentThread.run] takes `replyId` directly. */
data class RunDeps(val reply_id: String? = null)

/**
 * A single conversation thread within an [Agent] (port of Swift `AgentThread`).
 *
 * Outbound messages carry a top-level `thread_id`, and replies tagged with this
 * thread's id are routed back to it (as well as untagged channel events). At most
 * one [Run] is active at a time — starting a new run force-closes the previous one.
 * When the connection fails, the active run ends with an `agent_error` whose details
 * have `source: connection`.
 */
class AgentThread(val agent: Agent, threadId: String? = null) {

    /** Stable id carried as `thread_id` on every outbound message (top level and in content). */
    val threadId: String = threadId ?: generateThreadId()

    private val lock = Any()
    private var masterListenerJob: Deferred<Boolean>? = null
    private val userListeners = mutableListOf<UserListener>()
    private val feedbackHandlers = mutableListOf<HumanInputHandler>()
    private var activeRun: Run? = null
    private val stateStore = ThreadStateStore(this.threadId, enforcesSequence = true)
    private var disposed = false

    /** Origin of the latest run; replies keep it. */
    private var executionOrigin: ExecutionOrigin? = null
    private var subscription: Subscription? = null
    private var channelListener: ((Any?) -> Unit)? = null
    private var threadListener: ((Any?) -> Unit)? = null
    private var connectionListener: ((ConnectionError) -> Unit)? = null

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /** Whether [dispose] has been called. */
    val isDisposed: Boolean get() = synchronized(lock) { disposed }

    /**
     * Stops this thread: ends its active run and removes only this thread's
     * listeners. The agent's channel stays connected for other threads. Calling it
     * again does nothing; afterwards `run` and replies throw and new listeners are
     * ignored.
     */
    fun dispose() {
        val detached = synchronized(lock) {
            if (disposed) return
            disposed = true
            val d = Detached(activeRun, subscription, channelListener, threadListener, connectionListener)
            activeRun = null
            userListeners.clear()
            feedbackHandlers.clear()
            channelListener = null
            threadListener = null
            connectionListener = null
            d
        }
        detached.run?.abort("Thread disposed")
        stateStore.removeAllListeners()
        detached.connection?.let { agent.offConnectionFailure(it) }
        val sub = detached.subscription ?: return
        detached.channel?.let { sub.remove("all", it) }
        detached.thread?.let { sub.detachThreadListener(threadId, "all", it) }
    }

    private class Detached(
        val run: Run?,
        val subscription: Subscription?,
        val channel: ((Any?) -> Unit)?,
        val thread: ((Any?) -> Unit)?,
        val connection: ((ConnectionError) -> Unit)?,
    )

    private fun ensureActive() {
        if (isDisposed) throw IllegalStateException("AgentThread $threadId has been disposed")
    }

    private fun guardActive(operation: String): Boolean {
        if (isDisposed) {
            Log.w(TAG, "AgentThread $threadId has been disposed; ignoring $operation")
            return false
        }
        return true
    }

    // ── Listeners ─────────────────────────────────────────────────────────────

    /**
     * Subscribes [callback] to every typed event delivered to this thread. The
     * first call also installs the underlying subscription listeners.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun listen(callback: UserListener) {
        ensureMasterListener()
        if (!guardActive("listen")) return
        synchronized(lock) { userListeners.add(callback) }
    }

    /**
     * Subscribes [callback] to this thread's operational state. When [emitCurrent]
     * is `true` the current state is delivered immediately. Returns a function that
     * removes the callback.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun listenState(callback: (ThreadState) -> Unit, emitCurrent: Boolean = true): () -> Unit {
        ensureMasterListener()
        if (!guardActive("listenState")) return {}
        val id = stateStore.addListener(callback)
        if (emitCurrent) callback(stateStore.state)
        return { stateStore.removeListener(id) }
    }

    /** The thread's current operational state. */
    fun getState(): ThreadState = stateStore.state

    /** Registers a handler invoked whenever a human-input request arrives for the active Run. */
    fun feedbackRequest(handler: HumanInputHandler) {
        if (!guardActive("feedbackRequest")) return
        synchronized(lock) { feedbackHandlers.add(handler) }
    }

    // ── Run ───────────────────────────────────────────────────────────────────

    /**
     * Starts a new [Run] on this thread. When [replyId] is set the outbound event is
     * `user_reply` (carrying `reply_id`) rather than `user_input`. A previous active
     * run is force-closed. Throws once the thread is disposed, and rethrows a
     * [ConnectionError] when the connection fails before the request is sent (the run
     * then ends with an `agent_error` too). The request is never resent.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun run(
        user_input: Any?,
        replyId: String? = null,
        fileMeta: List<MediaRef>? = null,
        executionOrigin: ExecutionOrigin? = null
    ): Run {
        synchronized(lock) { this.executionOrigin = executionOrigin }
        ensureActive()
        ensureMasterListener()
        val sub = agent.getSubscription()
        ensureActive()

        val run = Run(this)
        val previous = synchronized(lock) {
            val p = activeRun
            activeRun = run
            p
        }
        if (previous != null && !previous.isClosed()) {
            Log.w(TAG, "[adk] starting new run while previous run is still active — closing previous")
            previous.close("Superseded by new run on the same thread")
        }

        stateStore.transition("submitted", "client", "Request submitted")

        val event = if (replyId != null) "user_reply" else "user_input"
        val content = mutableMapOf<String, Any?>(
            "user_input" to user_input,
            "thread_id" to threadId,
        )
        if (replyId != null) content["reply_id"] = replyId

        try {
            val ack = sub.push(
                event,
                content,
                PushConfig(
                    ThreadID = threadId,
                    FileMeta = fileMeta?.takeIf { it.isNotEmpty() },
                    ExecutionOrigin = executionOrigin?.value
                )
            )
            run.setRefId(ack as? String ?: "")
            return run
        } catch (error: Exception) {
            if (error is ConnectionError) handleConnectionFailure(error)
            run.abort(error.message ?: "The request could not be sent.")
            closeRun(run)
            throw error
        }
    }

    // ── Storage (thread-scoped) ───────────────────────────────────────────────

    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun upload(file: ByteArray, opts: UploadOptions = UploadOptions()): FileRef =
        Storage().upload(file, opts.copy(configId = threadId))

    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun listFiles(opts: ListOptions = ListOptions()): ListFilesResult =
        Storage().listFiles(opts.copy(configId = threadId))

    // ── Internal (called by Run) ──────────────────────────────────────────────

    /** Invoked by [Run] when a human-input request arrives. */
    fun fireRequestFeedback(req: HumanInputContent, run: Run) {
        val handlers = synchronized(lock) { feedbackHandlers.toList() }
        for (handler in handlers) handler(req, run)
    }

    /** Clears the active run reference on a terminal event. */
    fun closeRun(run: Run) {
        synchronized(lock) { if (activeRun === run) activeRun = null }
    }

    /** Sends a `user_reply` carrying [replyId] for the active human-input prompt. */
    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun sendReply(value: Any?, replyId: String): String {
        ensureActive()
        val sub = agent.getSubscription()
        ensureActive()
        val content = mutableMapOf<String, Any?>(
            "user_input" to value,
            "thread_id" to threadId,
            "reply_id" to replyId,
        )
        val ack = sub.push(
            "user_reply",
            content,
            PushConfig(
                ThreadID = threadId,
                ExecutionOrigin = synchronized(lock) { executionOrigin }?.value
            )
        )
        return ack as? String ?: ""
    }

    // ── Dispatch ──────────────────────────────────────────────────────────────

    /**
     * Installs, once, the subscription listeners that fan out to user callbacks and
     * the active run: the channel-wide `listen`, the thread-scoped route for frames
     * tagged with this `thread_id`, and the connection-failure listener. A failed
     * subscribe is logged (not thrown) and retried on the next call.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun ensureMasterListener() {
        val job: Deferred<Boolean> = synchronized(lock) {
            if (disposed) return
            masterListenerJob ?: scope.async {
                try {
                    attachListeners(agent.getSubscription())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "[adk] agent subscription failed: $e")
                    false
                }
            }.also { masterListenerJob = it }
        }
        if (!job.await()) {
            synchronized(lock) { if (masterListenerJob === job) masterListenerJob = null }
        }
    }

    /** Returns `false`, leaving nothing attached, when the thread was disposed meanwhile. */
    private fun attachListeners(sub: Subscription): Boolean {
        if (isDisposed) return false
        val channel: (Any?) -> Unit = { raw -> dispatchRaw(raw) }
        val thread: (Any?) -> Unit = { raw -> dispatchRaw(raw) }
        val connection: (ConnectionError) -> Unit = { error -> handleConnectionFailure(error) }
        sub.listen(channel)
        sub.attachThreadListener(threadId, thread)
        agent.onConnectionFailure(connection)
        val kept = synchronized(lock) {
            if (disposed) {
                false
            } else {
                subscription = sub
                channelListener = channel
                threadListener = thread
                connectionListener = connection
                true
            }
        }
        if (!kept) {
            sub.remove("all", channel)
            sub.detachThreadListener(threadId, "all", thread)
            agent.offConnectionFailure(connection)
        }
        return kept
    }

    /**
     * Ends the active run with an `agent_error` when the connection fails, since no
     * channel event can arrive after that. Details carry `source: connection` and
     * `execution_status: unknown`.
     */
    private fun handleConnectionFailure(error: ConnectionError) {
        if (synchronized(lock) { activeRun == null }) return
        val details = mutableMapOf<String, Any?>(
            "reason" to error.message.orEmpty(),
            "source" to "connection",
            "execution_status" to "unknown",
        )
        error.status?.let { details["status"] = it }
        dispatch(
            mapOf(
                "event" to "agent_error",
                "content" to mapOf(
                    "type" to "agent_error_response",
                    "status" to "error",
                    "code" to error.code,
                    "message" to error.message.orEmpty(),
                    "details" to details,
                    "thread_id" to threadId,
                    "ref_id" to "",
                    "agent_id" to agent.agentId,
                    "reply_to" to "",
                )
            )
        )
    }

    private fun dispatchRaw(raw: Any?) {
        val map = (raw as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v } ?: return
        dispatch(map)
    }

    private fun dispatch(raw: Map<String, Any?>) {
        if (isDisposed) return
        var envelope = parseAgentEvent(raw)

        // Surface wire-level transport errors as a typed `agent_error_response` so
        // the active Run rejects and listeners get a normalised envelope.
        if (envelope.event == "error" || envelope.event == "transport_error") {
            var message = "WebSocket error"
            var details: Map<String, Any?>? = null
            (envelope.payload as? AgentEventPayload.Unknown)?.let { unknown ->
                message = unknown.unknown.content["message"] as? String
                    ?: unknown.unknown.content["error"] as? String
                    ?: message
                details = unknown.unknown.content
            }
            val err = AgentErrorContent(
                code = "TRANSPORT_ERROR",
                message = message,
                details = details,
                threadId = threadId,
                refId = "",
                agentId = agent.agentId,
                replyTo = "",
            )
            // Same fields as a server-sent `agent_error_response`.
            val content = mutableMapOf<String, Any?>(
                "type" to err.type,
                "status" to err.status,
                "code" to err.code,
                "message" to err.message,
                "thread_id" to err.threadId,
                "ref_id" to err.refId,
                "agent_id" to err.agentId,
                "reply_to" to err.replyTo,
            )
            details?.let { content["details"] = it }
            envelope = AgentEventEnvelope("agent_error_response", AgentEventPayload.Error(err), content)
        }

        if (!envelope.isKnown) {
            Log.w(TAG, "[adk] unknown agent event \"${envelope.event}\" — passing through untyped.")
        }

        updateState(envelope.payload)

        val (run, listeners) = synchronized(lock) { activeRun to userListeners.toList() }

        run?.push(envelope)

        for (callback in listeners) {
            try {
                callback(envelope)
            } catch (e: Exception) {
                Log.e(TAG, "[adk] user listen callback threw: $e")
            }
        }
    }

    /** Operational state transitions driven by inbound events. */
    private fun updateState(payload: AgentEventPayload) {
        when (payload) {
            is AgentEventPayload.ThreadState -> stateStore.apply(payload.state)
            is AgentEventPayload.HumanInput -> stateStore.transition(
                "waiting_for_approval", "agent", "Waiting for your input",
                reason = payload.content.prompt
            )
            is AgentEventPayload.Wait -> {
                val wait = payload.content
                val workspaceId = artString(wait.progress?.get("workspace_id"))
                val waitingFor = wait.waitingForAgentId.ifEmpty { null }
                if (workspaceId != null) {
                    stateStore.transition(
                        "waiting_for_workspace", "workspace", "Waiting for workspace",
                        reason = wait.reason, workspaceId = workspaceId, agentId = waitingFor
                    )
                } else {
                    stateStore.transition(
                        "waiting_for_agent", "agent", "Waiting for another agent",
                        reason = wait.reason, agentId = waitingFor
                    )
                }
            }
            is AgentEventPayload.Output -> stateStore.transition("completed", "agent", "Completed")
            is AgentEventPayload.Error -> {
                val error = payload.content
                if (error.details?.get("source") as? String == "connection") {
                    stateStore.transition("failed", "client", "Connection unavailable", reason = error.message)
                } else {
                    stateStore.transition("failed", "agent", "Execution failed", reason = error.message)
                }
            }
            is AgentEventPayload.PlannerCorrection, is AgentEventPayload.Unknown -> Unit
        }
    }

    private companion object {
        const val TAG = "AgentThread"

        /** A random lowercase UUID (Swift `generateThreadId`). */
        fun generateThreadId(): String = UUID.randomUUID().toString().lowercase()

        /** Swift `ArtJSON.string`: a non-empty string, or a number's text, else null. */
        fun artString(value: Any?): String? = when (value) {
            is String -> value.ifEmpty { null }
            is Number -> {
                val d = value.toDouble()
                if (d == Math.floor(d) && !d.isInfinite()) d.toLong().toString() else value.toString()
            }
            else -> null
        }
    }
}
