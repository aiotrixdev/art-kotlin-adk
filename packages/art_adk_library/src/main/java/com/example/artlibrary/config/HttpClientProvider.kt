package com.example.artlibrary.config

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Single shared [OkHttpClient] used across the SDK.
 *
 * OkHttp clients hold connection pools, dispatcher thread pools and DNS
 * caches. Creating one per call (as the original port did) defeats the entire
 * design. Every component that needs an HTTP / WebSocket / SSE client should
 * derive from [shared] via `shared.newBuilder()` so that the underlying
 * resources are reused.
 */
object HttpClientProvider {

    /** The single root client used as a base for all derived clients. */
    val shared: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** Client tuned for WebSocket use — no read timeout. */
    val webSocket: OkHttpClient by lazy {
        shared.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(0, TimeUnit.MILLISECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** Client tuned for long-poll requests where the server may hold the
     *  connection open for tens of seconds. */
    val longPoll: OkHttpClient by lazy {
        shared.newBuilder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /** Client tuned for Server-Sent Events. */
    val sse: OkHttpClient by lazy {
        shared.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }
}
