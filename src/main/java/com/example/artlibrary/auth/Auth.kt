package com.example.artlibrary.auth

import android.os.Build
import androidx.annotation.RequiresApi
import com.example.artlibrary.config.Constant
import com.example.artlibrary.config.HttpClientProvider
import com.example.artlibrary.types.AuthData
import com.example.artlibrary.types.AuthenticationConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers.Companion.toHeaders
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Base64

class Auth private constructor(
    private val credentials: AuthenticationConfig
) {

    private var authData: AuthData = AuthData(accessToken = "", refreshToken = "")
    private val httpClient = HttpClientProvider.shared

    companion object {
        @Volatile
        private var instance: Auth? = null

        fun getInstance(credentials: AuthenticationConfig? = null): Auth {
            val existing = instance
            if (existing != null) return existing
            return synchronized(this) {
                instance ?: run {
                    val seed = credentials
                        ?: throw IllegalStateException("Forbidden")
                    Auth(seed).also { instance = it }
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun authenticate(forceAuth: Boolean = false): AuthData {
        if (!forceAuth &&
            authData.accessToken.isNotEmpty() &&
            !isTokenExpired(authData.accessToken)
        ) {
            return authData
        }

        val c = credentials

        if (c.orgTitle.isEmpty() || c.environment.isEmpty() || c.projectKey.isEmpty()) {
            throw IllegalArgumentException("OrgTitle, Environment, and ProjectKey are required for authentication.")
        }

        if (!getRefreshTokenExpiryInfo(authData.refreshToken).expired) {
            return refreshAuthToken()
        }

        return generateAuthToken()
    }

    private suspend fun generateAuthToken(): AuthData = withContext(Dispatchers.IO) {
        val c = credentials

        if (c.accessToken.isNullOrEmpty()) {
            if (c.clientID.isEmpty() || c.clientSecret.isEmpty()) {
                throw IllegalArgumentException("ClientID and ClientSecret are required when AccessToken is not present.")
            }
        }

        val headers = mutableMapOf<String, String>().apply {
            put("Client-Id", c.clientID)
            put("Client-Secret", c.clientSecret)
            put("X-Org", c.orgTitle)
            put("Environment", c.environment)
            put("ProjectKey", c.projectKey)

            if (!c.accessToken.isNullOrEmpty()) {
                put("T-pass", c.accessToken!!)
            }

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
                val message = parseErrorMessage(body) ?: "HTTP ${response.code} ${response.message}"
                throw IllegalStateException(message)
            }
            authData = parseTokenResponse(body)
            authData
        }
    }

    private suspend fun refreshAuthToken(): AuthData = withContext(Dispatchers.IO) {
        val c = credentials

        if (c.accessToken.isNullOrEmpty()) {
            if (c.clientID.isEmpty()) {
                throw IllegalArgumentException("ClientID is required when AccessToken is not present.")
            }
        }

        val headers = mutableMapOf<String, String>().apply {
            put("X-Org", c.orgTitle)
            put("Environment", c.environment)
            put("ProjectKey", c.projectKey)

            if (c.clientID.isNotEmpty()) {
                put("Client-Id", c.clientID)
            }

            if (!c.accessToken.isNullOrEmpty()) {
                put("T-pass", c.accessToken!!)
            }

            c.config?.authToken?.let { put("X-pass", it) }
        }

        val requestBody = JSONObject()
            .put("refresh_token", authData.refreshToken)
            .toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("${Constant.BASE_URL}/auth/token/refresh")
            .post(requestBody)
            .headers(headers.toHeaders())
            .build()

        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string()
            if (!response.isSuccessful) {
                val json = runCatching { JSONObject(responseBody.orEmpty()) }.getOrNull()

                if (response.code == 500 &&
                    json?.optString("error") == "Failed to get WebSocket backend"
                ) {
                    // keep existing AccessToken + RefreshToken
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
        val payload = token.split(".").getOrElse(1) { "" }
        val padded = payload
            .replace('-', '+')
            .replace('_', '/')
            .padEnd(payload.length + (4 - payload.length % 4) % 4, '=')
        val decoded = Base64.getDecoder().decode(padded)
        return JSONObject(String(decoded, Charsets.UTF_8))
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun isTokenExpired(token: String): Boolean {
        return try {
            val exp = decodeJwtPayload(token).optLong("exp", -1L)
            if (exp == -1L) {
                true
            } else {
                exp < (System.currentTimeMillis() / 1000) - 100
            }
        } catch (e: Exception) {
            true
        }
    }

    /**
     * Mirrors the JS implementation: the "refresh token" here is treated as
     * a raw numeric expiry value in its second dot-segment, NOT a JWT.
     */
    private fun getRefreshTokenExpiryInfo(token: String): RefreshTokenExpiryInfo {
        val parts = token.split(".")
        val expStr = parts.getOrElse(1) { "" }
        val exp = expStr.toLongOrNull()

        if (exp == null || exp == 0L) {
            return RefreshTokenExpiryInfo(expired = true, exp = null, remaining = 0)
        }

        val now = System.currentTimeMillis() / 1000
        return RefreshTokenExpiryInfo(
            expired = now >= exp,
            exp = exp,
            remaining = exp - now
        )
    }

    private data class RefreshTokenExpiryInfo(
        val expired: Boolean,
        val exp: Long?,
        val remaining: Long
    )
}