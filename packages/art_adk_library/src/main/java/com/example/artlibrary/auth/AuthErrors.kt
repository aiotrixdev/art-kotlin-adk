package com.example.artlibrary.auth

/**
 * An HTTP-level authentication failure (Kotlin port of the TS `AuthHttpError`).
 * [status] is the HTTP status code; [body] is the parsed error body, if any.
 */
class AuthHttpError(
    message: String,
    val status: Int,
    val body: Any? = null,
) : Exception(message)

/**
 * A terminal auth error is one that retrying cannot fix — the credentials or
 * grant are rejected outright, so the connection must stop instead of looping.
 * Kotlin port of the TS `isTerminalAuthError`.
 *
 * NOTE: 401 (unauthorized) and 403 (forbidden) are the classic terminal cases.
 * If your `auth.ts` treats other statuses (e.g. 400 invalid_grant) as terminal,
 * widen the check here to match it exactly.
 */
fun isTerminalAuthError(err: Any?): Boolean =
    err is AuthHttpError && (err.status == 401 || err.status == 403)
