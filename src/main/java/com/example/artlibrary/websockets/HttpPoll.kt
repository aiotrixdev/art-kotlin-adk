import android.net.Uri
import com.google.gson.Gson
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

class LongPollClient(private val opts: LongPollOptions) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS) // Long polling needs high timeouts
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private var connectionId: String? = opts.initialConnectionId
    private var isRunning = false
    private var pollingJob: Job? = null
    private val gson = Gson()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start(connectionId: String? = null) {
        if (isRunning) return
        if (connectionId != null) {
            this.connectionId = connectionId
        }
        isRunning = true

        // Launch the loop in a background coroutine
        pollingJob = scope.launch {
            pollLoop()
        }
    }

    fun stop() {
        isRunning = false
        pollingJob?.cancel() // This replaces AbortController
    }

    private suspend fun pollLoop() {
        var backoffEmpty = opts.emptyPollDelayMs.toLong()

        while (isRunning && coroutineContext.isActive) {
            try {
                // 1. Build URL
                val uriBuilder = Uri.parse(opts.endpoint).buildUpon()
                connectionId?.let {
                    uriBuilder.appendQueryParameter("connection_id", it)
                }
                val url = uriBuilder.build().toString()

                // Get Auth Headers
                val authHeaders = opts.getAuthHeaders()

                // Prepare Request
                val requestBuilder = Request.Builder().url(url)
                authHeaders.forEach { (key, value) ->
                    requestBuilder.addHeader(key, value)
                }

                // 4. Execute Request
                val response: Response = withContext(Dispatchers.IO) {
                    client.newCall(requestBuilder.build()).execute()
                }

                response.use { resp ->
                    // Handle 204 No Content
                    if (resp.code == 204) {
                        delay(backoffEmpty)
                        backoffEmpty = (backoffEmpty * 2).coerceAtMost(opts.maxEmptyPollDelayMs.toLong())
                        return@use // continue loop
                    }

                    if (!resp.isSuccessful) {
                        throw IOException("Longpoll error: ${resp.code}")
                    }

                    // Reset backoff on success
                    backoffEmpty = opts.emptyPollDelayMs.toLong()

                    val bodyString = resp.body?.string() ?: ""
                    val data = gson.fromJson(bodyString, LongPollResponse::class.java)

                    // Save connection ID if not present
                    if (connectionId == null) {
                        connectionId = data.connection_id
                    }

                    // Dispatch messages
                    if (!data.messages.isNullOrEmpty()) {
                        withContext(Dispatchers.Main) {
                            opts.onMessages(data.messages)
                        }
                    }
                }

            } catch (e: CancellationException) {
                // This happens when stop() or job.cancel() is called
                break
            } catch (e: Exception) {
                // Network or Server error
                withContext(Dispatchers.Main) {
                    opts.onError?.invoke(e)
                }
                // Backoff before retrying after an error
                delay(opts.retryDelayMs.toLong())
            }
        }
    }
}

/**
 * Supporting Data Classes for Kotlin
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

data class LongPollResponse(
    val connection_id: String,
    val messages: List<Any>?
)
