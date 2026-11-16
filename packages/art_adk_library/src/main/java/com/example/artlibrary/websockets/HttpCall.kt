package com.example.artlibrary.websockets

import android.os.Build
import androidx.annotation.RequiresApi
import com.example.artlibrary.auth.Auth
import com.example.artlibrary.config.Constant
import com.example.artlibrary.types.CallApiProps
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.util.concurrent.TimeUnit

private val httpClient = OkHttpClient.Builder().build()
val gson = Gson()

@RequiresApi(Build.VERSION_CODES.O)
private suspend inline fun <reified T> httpCall(
    endpoint: String,
    options: CallApiProps = CallApiProps()
): T = withContext(Dispatchers.IO) {

    // 1. Fresh token
    val auth = Auth.getInstance()
    auth.authenticate()

    val authData = auth.getAuthData()
    val credentials = auth.getCredentials()

    val accessToken = authData.accessToken

    // 2. Build URL with optional query parameters
    val baseUrl = options.baseUrl ?: Constant.BASE_URL
    val urlBuilder = "$baseUrl$endpoint".toHttpUrl().newBuilder()

    options.queryParams?.forEach { (key, value) ->
        urlBuilder.addQueryParameter(key, value)
    }

    val url = urlBuilder.build()

    // 3. Headers
    val headers = mutableMapOf(
        "Authorization" to "Bearer $accessToken",
        "Accept" to "application/json",
        "X-Org" to credentials.orgTitle,
        "Environment" to credentials.environment,
        "ProjectKey" to credentials.projectKey
    )

    options.headers?.let { headers.putAll(it) }

    // 4. Build request body
    val requestBody = when (val payload = options.payload) {
        null -> null

        is MultipartBody -> payload

        is okhttp3.RequestBody -> payload

        else -> {
            headers["Content-Type"] = "application/json"
            gson.toJson(payload)
                .toRequestBody("application/json; charset=utf-8".toMediaType())
        }
    }

    // 5. Build request with optional timeout
    val client = if (options.timeoutMs != null && options.timeoutMs > 0) {
        httpClient.newBuilder()
            .callTimeout(options.timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .build()
    } else {
        httpClient
    }

    val requestBuilder = Request.Builder().url(url)

    headers.forEach { (key, value) ->
        requestBuilder.header(key, value)
    }

    val method = (options.method ?: "GET").uppercase()
    requestBuilder.method(method, requestBody)

    val request = requestBuilder.build()
    val call = client.newCall(request)

    // Cancel the HTTP call if the coroutine is cancelled
    val job = currentCoroutineContext()[kotlinx.coroutines.Job]
    val cancellationHandler = job?.invokeOnCompletion { cause ->
        if (cause != null) {
            call.cancel()
        }
    }

    try {
        currentCoroutineContext().ensureActive()

        call.execute().use { response ->

            // 6. Error handling
            if (!response.isSuccessful) {
                val errorBody = response.body?.string()

                val errorMessage = try {
                    val json = errorBody?.let {
                        com.google.gson.JsonParser.parseString(it)
                    }

                    if (json != null && json.isJsonObject &&
                        json.asJsonObject.has("message")
                    ) {
                        json.asJsonObject.get("message").asString
                    } else {
                        errorBody ?: response.message
                    }
                } catch (_: Exception) {
                    response.message
                }

                throw IOException(
                    "API $endpoint failed: $errorMessage"
                )
            }

            // 7. Handle 204 No Content
            if (response.code == 204) {
                return@withContext null as T
            }

            // 8. Parse response
            val responseBody = response.body?.string()
                ?: throw IOException("API $endpoint returned an empty response")

            gson.fromJson<T>(
                responseBody,
                object : TypeToken<T>() {}.type
            )
        }
    } finally {
        cancellationHandler?.dispose()
    }
}