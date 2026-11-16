package com.example.artlibrary.websockets

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Thread-safe Node-style event emitter.
 *
 * The original port used a plain `HashMap<String, MutableList<…>>` which is
 * not safe to mutate concurrently — `emit` would race with `on`/`off` and
 * surface as `ConcurrentModificationException` on production devices. We use
 * a [ConcurrentHashMap] keyed by event name and [CopyOnWriteArrayList] for
 * the listener bucket so that:
 *
 *  - `emit` snapshots a stable iterator while listeners are added/removed.
 *  - `on`/`off` operate atomically without external locking.
 */
open class EventEmitter {

    private val events = ConcurrentHashMap<String, CopyOnWriteArrayList<(Any?) -> Unit>>()

    fun on(event: String, listener: (Any?) -> Unit) {
        events.computeIfAbsent(event) { CopyOnWriteArrayList() }.add(listener)
    }

    fun off(event: String, listener: (Any?) -> Unit) {
        events[event]?.remove(listener)
    }

    fun emit(event: String, data: Any?) {
        // Fire only this exact event's listeners, as Swift's `EventEmitter.emit`
        // does. The emitter must NOT fan every event out to the "all" bucket:
        // `Subscription.handleMessage` already routes each frame explicitly to
        // "all" / "<threadId>-all" (see `emitEvent`). An emitter-level wildcard
        // re-delivered every thread-tagged frame to the channel `listen`
        // callback as `{event: "<threadId>-all", content: {event, content}}`,
        // which `AgentThread.dispatch` then surfaced as spurious empty bubbles
        // titled "<threadId>-all" (the routing key leaking in as the event).
        events[event]?.forEach { it(data) }
    }

    fun listeners(event: String): List<(Any?) -> Unit> =
        events[event]?.toList() ?: emptyList()

    fun removeAllListeners(event: String) {
        events.remove(event)
    }

    /** Removes every listener for every event. */
    fun removeAllListeners() {
        events.clear()
    }
}
