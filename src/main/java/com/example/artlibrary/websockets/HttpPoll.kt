package com.example.artlibrary.websockets

import android.net.Uri
import com.example.artlibrary.config.AdkLog
import com.example.artlibrary.config.HttpClientProvider
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * HTTP long-polling fallback used when both WebSockets and SSE are
 * unavailable. The client sits in a loop, sending each new request as soon
 * as the previous one returns. On error it backs off; on `204 No Content`
 * it backs off with an exponential delay capped by [LongPollOptions.maxEmptyPollDelayMs].
 *
 * Lifecycle: call [start] to begin polling and [stop] to cancel and release
 * the internal coroutine scope. The class is safe to start/stop repeatedly,
 * but [release] should be called once when the owning SDK instance is being
 * disposed of so that the scope is shut down for good.
 */
class LongPollClient(private val opts: LongPollOptions) {

    private val client = HttpClientProvider.longPoll
    private val gson = Gson()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var connectionId: String? = opts.initialConnectionId
    @Volatile private var isRunning = false
    private var pollingJob: Job? = null

    fun start(connectionId: String? = null) {
        if (isRunning) return
        connectionId?.let { this.connectionId = it }
        isRunning = true
        pollingJob = scope.launch { pollLoop() }
    }

    fun stop() {
        isRunning = false
        pollingJob?.cancel()
        pollingJob = null
    }

    /**
     * Permanently shuts down the client and cancels its internal scope.
     * After calling this, [start] cannot be used again.
     */
    fun release() {
        stop()
        scope.cancel()
    }

    private suspend fun pollLoop() {
        var backoffEmpty = opts.emptyPollDelayMs.toLong()

        while (isRunning && coroutineContext.isActive) {
            try {
                val url = Uri.parse(opts.endpoint).buildUpon().apply {
                    connectionId?.let { appendQueryParameter("connection_id", it) }
                }.build().toString()

                val authHeaders = opts.getAuthHeaders()

                val requestBuilder = Request.Builder().url(url)
                authHeaders.forEach { (k, v) -> requestBuilder.addHeader(k, v) }

                val response: Response = client.newCall(requestBuilder.build()).execute()

                response.use { resp ->
                    if (resp.code == 204) {
                        delay(backoffEmpty)
                        backoffEmpty = (backoffEmpty * 2)
                            .coerceAtMost(opts.maxEmptyPollDelayMs.toLong())
                        return@use
                    }
                    if (!resp.isSuccessful) {
                        throw IOException("Longpoll error: ${resp.code}")
                    }

                    backoffEmpty = opts.emptyPollDelayMs.toLong()

                    val bodyString = resp.body?.string().orEmpty()
                    if (bodyString.isEmpty()) return@use

                    val data = gson.fromJson(bodyString, LongPollResponse::class.java)
                    if (connectionId == null) connectionId = data.connection_id

                    val msgs = data.messages
                    if (!msgs.isNullOrEmpty()) {
                        opts.onMessages(msgs)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AdkLog.w("LongPollClient", "Poll error", e)
                opts.onError?.invoke(e)
                delay(opts.retryDelayMs.toLong())
            }
        }
    }
}

/**
 * Configuration for [LongPollClient].
 */
data class LongPollOptions(
    val endpoint: String,
    val initialConnectionId: String? = null,
    val getAuthHeaders: suspend () -> Map<String, String>,
    val onMessages: (List<Any>) -> Unit,
    val onError: ((Throwable) -> Unit)? = null,
    val retryDelayMs: Int = 1000,
    val emptyPollDelayMs: Int = 500,
    val maxEmptyPollDelayMs: Int = 5000
)

/** Server response shape for the long-poll endpoint. */
@Suppress("PropertyName")
data class LongPollResponse(
    val connection_id: String,
    val messages: List<Any>?
)
