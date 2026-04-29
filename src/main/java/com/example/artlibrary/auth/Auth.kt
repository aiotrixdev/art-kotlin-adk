package com.example.artlibrary.auth

import android.os.Build
import androidx.annotation.RequiresApi
import com.example.artlibrary.config.AdkLog
import com.example.artlibrary.config.Constant
import com.example.artlibrary.config.HttpClientProvider
import com.example.artlibrary.types.AuthData
import com.example.artlibrary.types.AuthenticationConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Headers.Companion.toHeaders
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Base64

/**
 * Singleton holder for the SDK's authentication state.
 *
 * Responsibilities:
 *  - Caches the current [AuthData] (access + refresh tokens) and serves it
 *    to other SDK components.
 *  - Drives the token-acquisition / refresh flow.
 *  - Coordinates concurrent callers via a [Mutex] so only one network round
 *    trip is in flight at any time, even when several coroutines call
 *    [authenticate] simultaneously.
 *
 * Lifecycle: call [destroy] when the SDK is being torn down to wipe the
 * cached credentials and tokens from memory. The next call to [getInstance]
 * will then require a fresh [AuthenticationConfig] again.
 */
class Auth private constructor(
    private var credentials: AuthenticationConfig
) {

    private var authData: AuthData = AuthData(accessToken = "", refreshToken = "")
    private val mutex = Mutex()
    private val httpClient = HttpClientProvider.shared

    companion object {
        private const val TAG = "ArtAuth"

        @Volatile
        private var instance: Auth? = null

        /**
         * Returns the singleton, creating it on first call.
         *
         * Subsequent calls may pass `null` and will return the existing
         * instance. The first call MUST supply a non-null [credentials]
         * value or [IllegalStateException] is thrown.
         */
        fun getInstance(credentials: AuthenticationConfig? = null): Auth {
            val existing = instance
            if (existing != null) return existing
            return synchronized(this) {
                instance ?: run {
                    val seed = credentials
                        ?: throw IllegalStateException(
                            "Auth.getInstance(): credentials are required on first call"
                        )
                    Auth(seed).also { instance = it }
                }
            }
        }

        /** Tears down the singleton; subsequent [getInstance] calls require new credentials. */
        fun destroy() {
            synchronized(this) {
                instance?.wipe()
                instance = null
            }
        }
    }

    /**
     * Returns a valid (non-expired) [AuthData], obtaining or refreshing
     * tokens as required. Safe to call concurrently.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun authenticate(forceAuth: Boolean = false): AuthData = mutex.withLock {
        if (!forceAuth &&
            authData.accessToken.isNotEmpty() &&
            !isTokenExpired(authData.accessToken)
        ) {
            return@withLock authData
        }

        // Allow the host application to refresh credentials lazily.
        credentials.getCredentials?.invoke()?.let { fresh ->
            credentials = credentials.copy(
                accessToken = fresh.accessToken,
                clientID = fresh.clientID,
                clientSecret = fresh.clientSecret,
                orgTitle = fresh.orgTitle,
                environment = fresh.environment,
                projectKey = fresh.projectKey
            )
        }

        require(credentials.orgTitle.isNotEmpty()) { "OrgTitle is required for authentication" }
        require(credentials.environment.isNotEmpty()) { "Environment is required for authentication" }
        require(credentials.projectKey.isNotEmpty()) { "ProjectKey is required for authentication" }

        // If a refresh token exists and is still valid, prefer the refresh path.
        if (authData.refreshToken.isNotEmpty() &&
            !isTokenExpired(authData.refreshToken)
        ) {
            return@withLock refreshAuthToken()
        }

        return@withLock generateAuthToken()
    }

    /** Acquires a brand new token pair from the auth server. */
    suspend fun generateAuthToken(): AuthData = withContext(Dispatchers.IO) {
        val c = credentials

        val headers = mutableMapOf<String, String>().apply {
            put("Client-Id", c.clientID)
            put("Client-Secret", c.clientSecret)
            put("X-Org", c.orgTitle)
            put("Environment", c.environment)
            put("ProjectKey", c.projectKey)
            c.accessToken?.let { put("T-pass", it) }
            c.config?.authToken?.let { put("X-pass", it) }
        }

        val request = Request.Builder()
            .url("${Constant.BASE_URL}/auth/token")
            .post("".toRequestBody(null))
            .headers(headers.toHeaders())
            .build()

        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string()
            if (!response.isSuccessful) {
                val message = parseErrorMessage(body) ?: "HTTP ${response.code}"
                throw IllegalStateException(message)
            }
            authData = parseTokenResponse(body)
            authData
        }
    }

    /** Exchanges the current refresh token for a fresh access token. */
    private suspend fun refreshAuthToken(): AuthData = withContext(Dispatchers.IO) {
        val c = credentials
        if (c.accessToken.isNullOrEmpty() && c.clientID.isEmpty()) {
            throw IllegalArgumentException("ClientID is required when AccessToken is not present")
        }

        val headers = mapOf(
            "Client-Id" to c.clientID,
            "X-Org" to c.orgTitle,
            "Environment" to c.environment,
            "ProjectKey" to c.projectKey
        )

        val body = JSONObject()
            .put("refresh_token", authData.refreshToken)
            .toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("${Constant.BASE_URL}/auth/token/refresh")
            .post(body)
            .headers(headers.toHeaders())
            .build()

        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string()
            if (!response.isSuccessful) {
                val json = runCatching { JSONObject(responseBody.orEmpty()) }.getOrNull()
                if (response.code == 500 &&
                    json?.optString("error") == "Failed to get WebSocket backend"
                ) {
                    throw IllegalStateException(
                        json.optString("error", "Internal server error")
                    )
                }
                val errorMessage = json?.optString("message")
                    .takeUnless { it.isNullOrEmpty() }
                    ?: "HTTP ${response.code} ${response.message}"
                throw IllegalStateException(errorMessage)
            }
            authData = parseTokenResponse(responseBody)
            authData
        }
    }

    fun getAuthData(): AuthData = authData

    fun getCredentials(): AuthenticationConfig = credentials

    /** Wipes cached tokens and credentials in-place. */
    private fun wipe() {
        authData = AuthData(accessToken = "", refreshToken = "")
        credentials = credentials.copy(
            clientID = "",
            clientSecret = "",
            accessToken = null
        )
    }

    // ---------------- helpers ----------------

    private fun parseTokenResponse(body: String?): AuthData {
        val safe = body ?: throw IllegalStateException("Empty token response")
        val data = JSONObject(safe).getJSONObject("data")
        return AuthData(
            accessToken = data.getString("access_token"),
            refreshToken = data.getString("refresh_token")
        )
    }

    private fun parseErrorMessage(body: String?): String? {
        if (body.isNullOrEmpty()) return null
        return runCatching { JSONObject(body).optString("message") }
            .getOrNull()
            ?.takeUnless { it.isEmpty() }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun decodeJwtPayload(token: String): JSONObject {
        val payload = token.split(".").getOrNull(1)
            ?: throw IllegalArgumentException("Malformed JWT")
        val padded = payload
            .replace('-', '+')
            .replace('_', '/')
            .padEnd(payload.length + (4 - payload.length % 4) % 4, '=')
        val decoded = Base64.getDecoder().decode(padded)
        return JSONObject(String(decoded, Charsets.UTF_8))
    }

    /**
     * Returns `true` when the JWT's `exp` is in the past (with a 100s skew
     * buffer) or the token cannot be decoded.
     *
     * NOTE: this only inspects the unsigned payload. It is a hint to avoid
     * useless network round-trips, NOT a security check — the server remains
     * the source of truth.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun isTokenExpired(token: String): Boolean {
        if (token.isEmpty()) return true
        return try {
            val exp = decodeJwtPayload(token).getLong("exp")
            exp < (System.currentTimeMillis() / 1000) - 100
        } catch (e: Exception) {
            AdkLog.w(TAG, "Failed to decode JWT for expiry check", e)
            true
        }
    }
}
