package com.example.artlibrary.websockets

/**
 * Typed connection state, mirroring the Swift ADK's `ConnectionState`.
 * Derived from [Adk.getState]; read via the [Adk.state] property.
 */
enum class ConnectionState {
    CONNECTED,
    DISCONNECTED,
    RECONNECTING,
    PAUSED
}
