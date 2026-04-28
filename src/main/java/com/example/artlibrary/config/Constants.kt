package com.example.artlibrary.config

/**
 * Mutable runtime configuration. Populated by [com.example.artlibrary.websockets.Adk]
 * once the SDK is initialized with a host URI.
 */
object Constant {
    const val ROOT = "/"
    const val CONFIG_FILE_NAME = "adk-services.json"
    const val CONFIG_JSON_PATH = "/adk-services.json"

    @Volatile var BASE_URL: String = ""
    @Volatile var WS_URL: String = "/v1/connect"
    @Volatile var SSE_URL: String = "/v1/connect/sse"
    @Volatile var LPOLL: String = "/v1/longpoll"
}

/**
 * Reserved channel identifiers used by the protocol. Centralised so that
 * a rename does not require touching string literals across the codebase.
 */
object ReservedChannels {
    const val ART_CONFIG = "art_config"
    const val ART_SECURE = "art_secure"
    const val ART_PRESENCE = "art_presence"
    const val ART_READY = "art_ready"

    val SYSTEM = setOf(ART_CONFIG, ART_SECURE)
    val SYSTEM_AND_PRESENCE = setOf(ART_CONFIG, ART_SECURE, ART_PRESENCE)
}

/**
 * Wire-level event names exchanged with the server.
 */
object Events {
    const val CONNECTION = "connection"
    const val CLOSE = "close"
    const val OPEN = "open"
    const val ERROR = "error"
    const val LIMIT_EXCEEDED = "limitExceeded"
    const val READY = "ready"
    const val SHIFT_TO_HTTP = "shift_to_http"
    const val ALL = "all"
    const val MERGE = "merge"
    const val UPDATE = "update"
    const val HEARTBEAT = "heartbeat"
}

/**
 * Server return-flag identifiers used in the request/response protocol.
 */
object ReturnFlags {
    /** Server acknowledged a client message. */
    const val SERVER_ACK = "SA"

    /** Client acknowledged delivery to the consumer. */
    const val CLIENT_ACK = "CA"

    /** Message received but not yet consumed. */
    const val MESSAGE_ACK = "MA"

    /** Interceptor acknowledgement. */
    const val INTERCEPTOR_ACK = "IA"
}

/**
 * Channel type discriminators.
 */
object ChannelTypes {
    const val SECURE = "secure"
    const val TARGETED = "targeted"
    const val SHARED_OBJECT = "shared-object"
    const val DEFAULT = "default"
}

/**
 * Limit-exceeded codes returned by the server when billing or concurrency
 * limits are reached.
 */
object LimitCodes {
    const val CONCURRENT_LIMIT_EXCEEDED = "CONCURRENT_LIMIT_EXCEEDED"
}
