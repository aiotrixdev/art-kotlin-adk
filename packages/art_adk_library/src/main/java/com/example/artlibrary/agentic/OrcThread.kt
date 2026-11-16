package com.example.artlibrary.agentic

import android.os.Build
import androidx.annotation.RequiresApi
import com.example.artlibrary.config.Events
import com.example.artlibrary.storage.FileRef
import com.example.artlibrary.storage.ListFilesResult
import com.example.artlibrary.storage.ListOptions
import com.example.artlibrary.storage.Storage
import com.example.artlibrary.storage.UploadOptions
import com.example.artlibrary.types.ConnectionError
import com.example.artlibrary.types.PushConfig
import com.example.artlibrary.websockets.Subscription
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

class OrchestratorThread(
    private val subscription: Subscription,
    threadId: String? = null
) {
    val threadId: String = threadId ?: generateThreadId()
    private val attachedEvents = mutableSetOf<String>()
    private var traceCbs: MutableList<(Any?) -> Unit> = mutableListOf()
    private var disposed = false
    private var currentState: ThreadState = makeState("idle", "client", "Ready")
    private var stateCallbacks: MutableList<(ThreadState) -> Unit> = mutableListOf()

    private val connectionFailure: (ConnectionError) -> Unit = { error ->
        if (!disposed &&
            currentState.phase !in listOf("idle", "completed", "failed", "cancelled")
        ) {
            setState(makeState("failed", "client", "Connection unavailable").copy(reason = error.message))
            subscription.emit(
                "$threadId-${Events.ALL}",
                mapOf(
                    "event" to "agent_error",
                    "content" to mapOf(
                        "type" to "agent_error_response",
                        "status" to "error",
                        "code" to error.code,
                        "message" to error.message,
                        "thread_id" to threadId,
                        "details" to mapOf(
                            "source" to "connection",
                            "status" to error.status,
                            "execution_status" to "unknown",
                            "reason" to error.message
                        )
                    )
                )
            )
        }
    }

    init {
        subscription.onConnectionFailure(connectionFailure)
    }

    private fun makeState(phase: String, source: String, message: String): ThreadState {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return ThreadState(
            thread_id = threadId,
            phase = phase,
            source = source,
            message = message,
            occurred_at = fmt.format(Date())
        )
    }

    private fun setState(state: ThreadState) {
        if (state.thread_id.isNotEmpty() && state.thread_id != threadId) return
        currentState = state.copy(thread_id = threadId)
        for (callback in stateCallbacks) callback(currentState)
    }

    private fun generateThreadId(): String = UUID.randomUUID().toString()

    private fun ensureActive() {
        if (disposed) {
            throw IllegalStateException("OrchestratorThread $threadId has been disposed")
        }
    }

    suspend fun push(event: String, data: Any?, options: PushConfig? = null) {
        ensureActive()
        val opts: PushConfig = (options ?: PushConfig()).copy(ThreadID = threadId)
        setState(makeState("submitted", "client", "Request submitted"))
        try {
            subscription.push(event, data ?: emptyMap<String, Any?>(), opts)
        } catch (error: Exception) {
            if (error is ConnectionError) connectionFailure(error)
            else setState(
                makeState("failed", "client", "Request could not be sent").copy(reason = error.message)
            )
            throw error
        }
    }

    // NOTE: the TS passes `configId = subscription.configurationId` + `threadId`,
    // but the Kotlin `Subscription` exposes no `configurationId` and
    // `UploadOptions`/`ListOptions` model no `threadId`; we key on the threadId.
    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun upload(file: ByteArray, opts: UploadOptions = UploadOptions()): FileRef {
        ensureActive()
        return Storage().upload(file, opts.copy(configId = threadId))
    }

    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun listFiles(opts: ListOptions = ListOptions()): ListFilesResult {
        ensureActive()
        return Storage().listFiles(opts.copy(configId = threadId))
    }

    fun listen(callback: (Any?) -> Unit) {
        ensureActive()
        subscription.attachThreadListener(threadId, callback)
        attachedEvents.add(Events.ALL)
    }

    fun bind(event: String, callback: (Any?) -> Unit) {
        ensureActive()
        subscription.attachThreadBind(threadId, event, callback)
        attachedEvents.add(event)
    }

    /** @deprecated Use listenState for operational UI. Trace is reserved for diagnostics. */
    @Deprecated("Use listenState for operational UI. Trace is reserved for diagnostics.")
    fun listenTrace(callback: (Any?) -> Unit) {
        ensureActive()
        subscription.bind("trace", callback)
        subscription.attachThreadBind(threadId, "trace", callback)
        traceCbs.add(callback)
        attachedEvents.add("trace")
    }

    fun listenState(callback: (ThreadState) -> Unit, emitCurrent: Boolean = true): () -> Unit {
        ensureActive()
        val wrapped: (Any?) -> Unit = { raw ->
            val state = when (raw) {
                is ThreadState -> raw
                is Map<*, *> -> ThreadState(
                    thread_id = raw["thread_id"]?.toString() ?: threadId,
                    phase = raw["phase"]?.toString() ?: "idle",
                    source = raw["source"]?.toString() ?: "agent",
                    message = raw["message"]?.toString() ?: "",
                    occurred_at = raw["occurred_at"]?.toString() ?: "",
                    sequence = (raw["sequence"] as? Number)?.toInt(),
                    reason = raw["reason"]?.toString(),
                    workspace_id = raw["workspace_id"]?.toString(),
                    agent_id = raw["agent_id"]?.toString()
                )

                else -> null
            }
            if (state != null) setState(state)
        }
        subscription.attachThreadBind(threadId, "thread_state", wrapped)
        stateCallbacks.add(callback)
        attachedEvents.add("thread_state")
        if (emitCurrent) callback(currentState)
        return {
            stateCallbacks = stateCallbacks.filter { it !== callback }.toMutableList()
            subscription.detachThreadListener(threadId, "thread_state", wrapped)
        }
    }

    fun getState(): ThreadState = currentState.copy()

    fun remove(event: String, callback: ((Any?) -> Unit)? = null) {
        if (disposed) return
        subscription.detachThreadListener(threadId, event, callback)
        if (callback == null) {
            attachedEvents.remove(event)
        }
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        subscription.offConnectionFailure(connectionFailure)
        for (event in attachedEvents) {
            subscription.detachThreadListener(threadId, event)
        }
        for (cb in traceCbs) {
            subscription.remove("trace", cb)
        }
        traceCbs = mutableListOf()
        stateCallbacks = mutableListOf()
        attachedEvents.clear()
        subscription.unregisterThread(threadId)
    }

    fun isDisposed(): Boolean = disposed

    fun getId(): String = threadId
}
