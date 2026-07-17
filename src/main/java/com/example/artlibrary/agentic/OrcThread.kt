package com.example.artlibrary.agentic

import com.example.artlibrary.config.Events
import com.example.artlibrary.types.PushConfig
import com.example.artlibrary.websockets.Subscription
import java.util.UUID

class OrchestratorThread(
    private val subscription: Subscription,
    threadId: String? = null
) {
    val threadId: String = threadId ?: UUID.randomUUID().toString()
    private val attachedEvents = mutableSetOf<String>()
    private var disposed = false

    private fun ensureActive() {
        check(!disposed) { "OrchestratorThread $threadId has been disposed" }
    }

    suspend fun push(event: String, data: Any?, options: PushConfig? = null): Any? {
        ensureActive()
        return subscription.push(
            event = event,
            data = data ?: emptyMap<String, Any?>(),
            options = PushConfig(
                to = options?.to ?: emptyList(),
                instanceId = options?.instanceId,
                threadId = threadId
            )
        )
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
        attachedEvents.toList().forEach { event ->
            subscription.detachThreadListener(threadId, event)
        }
        attachedEvents.clear()
        subscription.unregisterThread(threadId)
    }

    fun isDisposed(): Boolean = disposed

    fun getId(): String = threadId
}
