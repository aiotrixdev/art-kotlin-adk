package com.example.artlibrary.config

import android.util.Log

/**
 * Internal logging facade for the ART SDK.
 *
 * All library logging must go through this object instead of [android.util.Log]
 * directly. This gives us a single switch to disable logging in release builds
 * (so that operational data and tokens never end up in logcat) and a single
 * place to redact sensitive material before it is written.
 *
 * Consumers can disable logging entirely by calling [setEnabled] with `false`,
 * or restrict it to a minimum severity via [setMinLevel].
 */
@Suppress("LogTagMismatch")
object AdkLog {

    /**
     * Severity levels in increasing order. A message is only emitted when its
     * level is greater than or equal to [minLevel] and [enabled] is `true`.
     */
    enum class Level(internal val priority: Int) {
        VERBOSE(2), DEBUG(3), INFO(4), WARN(5), ERROR(6), NONE(99)
    }

    @Volatile
    private var enabled: Boolean = true

    @Volatile
    private var minLevel: Level = Level.INFO

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    fun setMinLevel(level: Level) {
        minLevel = level
    }

    fun isLoggable(level: Level): Boolean =
        enabled && level.priority >= minLevel.priority

    fun v(tag: String, message: String) {
        if (isLoggable(Level.VERBOSE)) Log.v(tag, message)
    }

    fun d(tag: String, message: String) {
        if (isLoggable(Level.DEBUG)) Log.d(tag, message)
    }

    fun i(tag: String, message: String) {
        if (isLoggable(Level.INFO)) Log.i(tag, message)
    }

    fun w(tag: String, message: String) {
        if (isLoggable(Level.WARN)) Log.w(tag, message)
    }

    fun w(tag: String, message: String, throwable: Throwable?) {
        if (isLoggable(Level.WARN)) Log.w(tag, message, throwable)
    }

    fun e(tag: String, message: String) {
        if (isLoggable(Level.ERROR)) Log.e(tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable?) {
        if (isLoggable(Level.ERROR)) Log.e(tag, message, throwable)
    }

    /**
     * Redacts a JWT or opaque token, keeping only the first six characters
     * for diagnostic purposes. Used by callers that *must* log a token-bearing
     * message; never log the raw value.
     */
    fun redact(secret: String?): String {
        if (secret.isNullOrEmpty()) return "<empty>"
        val visible = secret.take(6)
        return "$visible…(${secret.length})"
    }
}
