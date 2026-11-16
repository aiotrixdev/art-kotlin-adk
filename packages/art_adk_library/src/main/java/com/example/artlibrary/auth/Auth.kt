package com.example.artlibrary.auth

import android.os.Build
import androidx.annotation.RequiresApi
import com.example.artlibrary.config.Constant
import com.example.artlibrary.config.HttpClientProvider
import com.example.artlibrary.types.AuthData
import com.example.artlibrary.types.AuthenticationConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Headers.Companion.toHeaders
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Base64

// AuthHttpError and isTerminalAuthError live in AuthErrors.kt (same package).

class Auth private constructor(
    private var credentials: AuthenticationConfig
) {

    private var authData: AuthData = AuthData(accessToken = "", refreshToken = "")
    private val httpClient = HttpClientProvider.shared
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Dedup concurrent authenticate() calls onto a single in-flight request.
    private var authenticationInFlight: Deferred<AuthData>? = null

    companion object {
        @Volatile
        private var instance: Auth? = null

        fun getInstance(credentials: AuthenticationConfig? = null): Auth {
            synchronized(this) {
                val existing = instance
                if (existing != null && credentials != null && identityChanged(existing.credentials, credentials)) {
                    instance = Auth(credentials)
                } else if (existing != null && credentials != null) {
                    existing.credentials = credentials
                }
                if (instance == null) {
                    if (credentials == null) throw IllegalStateException("Forbidden")
                    instance = Auth(credentials)
                }
                return instance!!
            }
        }

        // Mirrors the TS identity fields: ["OrgTitle","Environment","ProjectKey","ClientID"].
        private fun identityChanged(a: AuthenticationConfig, b: AuthenticationConfig): Boolean =
            a.orgTitle != b.orgTitle ||
                a.environment != b.environment ||
                a.projectKey != b.projectKey ||
                a.clientID != b.clientID
    }

    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun authenticate(forceAuth: Boolean = false): AuthData {
        authenticationInFlight?.let { return it.await() }
        val pending = scope.async { authenticateOnce(forceAuth) }
        authenticationInFlight = pending
        try {
            return pending.await()
        } finally {
            authenticationInFlight = null
        }
    }

    // Match the WebSocket gateway's grant selection when both are supplied.
    private fun usesClientCredentials(): Boolean =
        credentials.clientID.isNotEmpty() && credentials.clientSecret.isNotEmpty()

    private data class TokenResult(
        val ok: Boolean,
        val status: Int,
        val statusMessage: String,
        val body: JSONObject?,
    )

    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun requestToken(request: Request): TokenResult {
        val timeout = credentials.config?.connectionTimeoutMs?.toLong() ?: 30_000L
        val response = try {
            withTimeout(timeout) {
                withContext(Dispatchers.IO) { httpClient.newCall(request).execute() }
            }
        } catch (e: TimeoutCancellationException) {
            throw IllegalStateException("Authentication timed out. Please check your connection and sign in again.")
        }
        return response.use { resp ->
            val bodyStr = resp.body?.string()
            val body = runCatching { JSONObject(bodyStr ?: "") }.getOrNull()
            TokenResult(resp.isSuccessful, resp.code, resp.message, body)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun authenticateOnce(forceAuth: Boolean): AuthData {
        if (!forceAuth && authData.accessToken.isNotEmpty() && !isTokenExpired(authData.accessToken)) {
            return authData
        }

        val c = credentials
        if (c.orgTitle.isEmpty() || c.environment.isEmpty() || c.projectKey.isEmpty()) {
            throw IllegalArgumentException("OrgTitle, Environment, and ProjectKey are required for authentication.")
        }

        // Client credentials renew through authentication, never through refresh.
        // Ignore legacy refresh tokens returned by older IDP deployments.
        if (usesClientCredentials()) return generateAuthToken()

        if (!getRefreshTokenExpiryInfo(authData.refreshToken).expired) {
            return refreshAuthToken()
        }

        return generateAuthToken()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun generateAuthToken(): AuthData {
        val c = credentials

        if (c.accessToken.isNullOrEmpty()) {
            if (c.clientID.isEmpty() || c.clientSecret.isEmpty()) {
                throw IllegalArgumentException("ClientID and ClientSecret are required when AccessToken is not present.")
            }
        }

        // Mirrors the Swift ADK (Auth.generateAuthToken): a complete client ID +
        // secret pair uses the client-credentials grant and is sent INSTEAD of
        // `T-pass`; `T-pass` (the access token) is sent only without client
        // credentials. Sending the user's passcode as `T-pass` alongside client
        // credentials made every token renewal (tokens live ~5 min) and every
        // reconnect re-present an already-used passcode, which the server rejects
        // as "Invalid Passcode". The passcode still reaches the server as `X-pass`.
        val headers = mutableMapOf(
            "Content-Type" to "application/json",
            "X-Org" to c.orgTitle,
            "Environment" to c.environment,
            "ProjectKey" to c.projectKey,
        )
        if (usesClientCredentials()) {
            headers["Client-Id"] = c.clientID
            headers["Client-Secret"] = c.clientSecret
        } else {
            headers["T-pass"] = c.accessToken.orEmpty()
        }
        c.config?.authToken?.takeIf { it.isNotEmpty() }?.let { headers["X-pass"] = it }

        val request = Request.Builder()
            .url("${Constant.BASE_URL}/auth/token")
            .post("".toRequestBody(null))
            .headers(headers.toHeaders())
            .build()

        val result = requestToken(request)
        if (!result.ok) {
            val errorMessage = result.body?.optString("message").takeUnless { it.isNullOrEmpty() }
                ?: "HTTP ${result.status} ${result.statusMessage}"
            throw AuthHttpError(errorMessage, result.status, result.body)
        }
        val data = result.body?.optJSONObject("data")
        val accessToken = data?.optString("access_token").takeUnless { it.isNullOrEmpty() }
            ?: throw IllegalStateException("Authentication did not return an access token. Please sign in again.")
        authData = AuthData(
            accessToken = accessToken,
            refreshToken = if (usesClientCredentials()) "" else data?.optString("refresh_token").orEmpty(),
        )
        return authData
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun refreshAuthToken(): AuthData {
        val c = credentials

        if (c.accessToken.isNullOrEmpty()) {
            if (c.clientID.isEmpty()) {
                throw IllegalArgumentException("ClientID is required when AccessToken is not present.")
            }
        }

        val headers = mutableMapOf(
            "Content-Type" to "application/json",
            "X-Org" to c.orgTitle,
            "Environment" to c.environment,
            "ProjectKey" to c.projectKey,
        )
        if (c.clientID.isNotEmpty()) headers["Client-Id"] = c.clientID
        if (!c.accessToken.isNullOrEmpty()) headers["T-pass"] = c.accessToken!!
        c.config?.authToken?.let { headers["X-pass"] = it }

        val body = JSONObject().put("refresh_token", authData.refreshToken).toString()
        val request = Request.Builder()
            .url("${Constant.BASE_URL}/auth/token/refresh")
            .post(body.toRequestBody(null))
            .headers(headers.toHeaders())
            .build()

        val result = requestToken(request)
        if (!result.ok) {
            val serverError = result.body?.optString("error").orEmpty()
            if (result.status == 500 && serverError == "Failed to get WebSocket backend") {
                // keep existing AccessToken + RefreshToken
                throw IllegalStateException(serverError.ifEmpty { "Internal server error" })
            }
            val errorMessage = result.body?.optString("message").takeUnless { it.isNullOrEmpty() }
                ?: "HTTP ${result.status} ${result.statusMessage}"
            throw AuthHttpError(errorMessage, result.status, result.body)
        }
        val data = result.body?.optJSONObject("data")
        val accessToken = data?.optString("access_token").takeUnless { it.isNullOrEmpty() }
            ?: throw IllegalStateException("Authentication refresh did not return an access token. Please sign in again.")
        authData = AuthData(
            accessToken = accessToken,
            refreshToken = if (usesClientCredentials()) "" else data?.optString("refresh_token").orEmpty(),
        )
        return authData
    }

    fun getAuthData(): AuthData = authData

    fun getCredentials(): AuthenticationConfig = credentials

    // ---------------- helpers ----------------

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
            if (exp == -1L) true else exp <= (System.currentTimeMillis() / 1000) + 30
        } catch (e: Exception) {
            true
        }
    }

    /**
     * Mirrors the TS implementation: the "refresh token" here is treated as a
     * raw numeric expiry value in its second dot-segment, NOT a JWT.
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
