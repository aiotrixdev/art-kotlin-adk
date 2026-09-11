// art_notifier — opt-in notifications adapter for the Kotlin ART ADK.
//
// Kotlin port of the JS `@art/adk-notifications` package. Installs into a core
// Adk through its plugin system (`adk.use(notifications())`) and rides the same
// websocket + REST + auth via the [AdkPluginContext] it is handed:
//
//   val adk = Adk(AdkConfig(uri = "...")).also { it.setCredentials(creds) }
//   adk.connect()
//   val notifications = adk.use(notifications())                  // install the plugin
//
//   val off = notifications.onNew { n -> render(n) }              // live (websocket)
//   val (items, total) = notifications.list()                     // history (REST)
//   notifications.markRead(listOf(id))
//   notifications.send(SendInput(recipients = listOf("bob"), type = "mention",
//                                title = title, body = body))
//   notifications.registerDevice(RegisterDeviceInput(token = fcmToken,
//                                platform = DevicePlatform.ANDROID))   // push (FCM)
//   notifications.unregisterDevice(fcmToken)                          // on logout
//   off()                                                             // unsubscribe
package com.example.art_notifier

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.example.artlibrary.types.CallApiProps
import com.example.artlibrary.websockets.AdkPlugin
import com.example.artlibrary.websockets.AdkPluginContext
import com.example.artlibrary.websockets.Subscription

/** The default channel for ART notifications. */
private const val CHANNEL = "art_notifications"

/** The event name for new notifications. */
private const val EVENT = "notification.new"

// ── Logging (mirrors the Flutter `adk_notifier` `ADK-Notifier` traces) ────────
// Filter logcat by the tag "ADK-Notifier" to see just this flow. Gated by
// NotificationsOptions.debug (set once when the plugin is installed), except
// failures, which always log.
private const val LOG_NAME = "ADK-Notifier"
private var verboseLogging = false

private fun logStart(op: String, detail: String? = null) {
    if (verboseLogging) Log.d(LOG_NAME, "API call: $op" + (detail?.let { " ($it)" } ?: ""))
}

private fun logOk(op: String, detail: String? = null) {
    if (verboseLogging) Log.d(LOG_NAME, "Success: $op" + (detail?.let { " ($it)" } ?: ""))
}

private fun logFail(op: String, error: Throwable) {
    Log.e(LOG_NAME, "Error during $op: ${error.message}", error)
}

data class ArtNotification(
    val id: String,
    val type: String = "",
    val title: String = "",
    val body: String = "",
    val data: Map<String, Any?>? = null,
    /** "unread" | "read" | "archived" */
    val status: String = "unread",
    val createdAt: String = "",
)

data class NotificationsOptions(
    /**
     * Optional REST origin override. When null, the ADK's own configured base
     * URL (gateway origin) is used. Applied in [NotificationsApi.base].
     */
    val apiBaseUrl: String? = "https://demo.arealtimetech.com",
    /** Enable verbose logging (logcat tag "ADK-Notifier") for debugging the flow. */
    val debug: Boolean = false,
)

data class ListParams(
    /** "unread" | "read" | "archived" */
    val status: String? = null,
    val page: Int? = null,
    val limit: Int? = null,
)

data class SendInput(
    val recipients: List<String>,
    val type: String,
    val title: String,
    val body: String,
    val data: Map<String, Any?>? = null,
    val channels: List<String>? = null,
    val dedupKey: String? = null,
)

enum class DevicePlatform { ANDROID, IOS, WEB }

data class RegisterDeviceInput(
    /** Push token from the device's FCM SDK. */
    val token: String,
    val platform: DevicePlatform,
    /**
     * Only honored (and required) when the ADK session authenticates with
     * client credentials — a backend registering on behalf of a user. For user
     * (passcode) sessions the service derives the owner from the authenticated
     * username and ignores this field.
     */
    val username: String? = null,
)

data class PushDevice(
    val id: String? = null,
    val username: String = "",
    val token: String = "",
    val platform: String = "",
    val provider: String? = null,
    val createdAt: String? = null,
    val lastSeenAt: String? = null,
    val updatedAt: String? = null,
)

data class ListResult(val notifications: List<ArtNotification>, val total: Int)
data class SendResult(val created: Int, val skipped: Int)
data class DevicesResult(val devices: List<PushDevice>, val total: Int)

@RequiresApi(Build.VERSION_CODES.O)
class NotificationsApi(
    private val ctx: AdkPluginContext,
    private val opts: NotificationsOptions = NotificationsOptions(),
) {
    private var sub: Subscription? = null

    init {
        logOk("NotificationsApi.create", "tenant=\"${runCatching { tenant() }.getOrDefault("")}\"")
    }

    // ---- live (websocket) ----

    /**
     * Subscribe to live notifications and return an unsubscribe function.
     * Multiple listeners share one underlying channel subscription (mirrors the
     * Flutter `onNew`).
     */
    suspend fun onNew(cb: (ArtNotification) -> Unit): () -> Unit {
        logStart("onNew", "hasSubscription=${sub != null}")
        val s = sub ?: run {
            logStart("transport.subscribe", "channel=\"$CHANNEL\"")
            (ctx.subscribe(CHANNEL) as Subscription).also {
                sub = it
                logOk("onNew.subscribe", "channel=\"$CHANNEL\"")
            }
        }
        val handler: (Any?) -> Unit = { payload ->
            if (verboseLogging) Log.d(LOG_NAME, "Event received: $EVENT")
            val notification = normalize(payload)
            if (notification != null) {
                logOk("onNew.callback", "id=${notification.id} title=\"${notification.title}\"")
                cb(notification)
            } else if (verboseLogging) {
                Log.w(LOG_NAME, "Payload normalization failed for event: $EVENT")
            }
        }
        s.bind(EVENT, handler)
        logOk("onNew", "listener bound on \"$EVENT\"")
        return {
            try {
                logStart("onNew.remove")
                sub?.remove(EVENT, handler)
            } catch (_: Exception) {
                /* no-op */
            }
        }
    }

    // ---- CRUD (REST, via the gateway) ----

    suspend fun list(params: ListParams = ListParams()): ListResult {
        logStart("list", "query=${toQuery(params)}")
        try {
            val data = callData(path(), CallApiProps(method = "GET", queryParams = toQuery(params)))
            val raw = data?.get("notifications") as? List<*> ?: emptyList<Any?>()
            val result = ListResult(
                notifications = raw.mapNotNull { normalize(it) },
                total = intOf(data?.get("total")),
            )
            logOk("list", "${result.notifications.size}/${result.total} items")
            return result
        } catch (e: Exception) {
            logFail("list", e); throw e
        }
    }

    suspend fun unreadCount(): Int {
        logStart("unreadCount")
        try {
            val data = callData(path("/unread-count"), CallApiProps(method = "GET"))
            val count = intOf(data?.get("unread_count"))
            logOk("unreadCount", "$count")
            return count
        } catch (e: Exception) {
            logFail("unreadCount", e); throw e
        }
    }

    /** Mark the given notifications read, or all unread when no ids are passed. */
    suspend fun markRead(ids: List<String>? = null): Int {
        logStart("markRead", if (ids.isNullOrEmpty()) "all" else "ids=$ids")
        try {
            val data = callData(
                path("/mark-read"),
                CallApiProps(
                    method = "POST",
                    payload = mapOf(
                        "ids" to (ids ?: emptyList<String>()),
                        "all" to ids.isNullOrEmpty(),
                    ),
                ),
            )
            val modified = intOf(data?.get("modified"))
            logOk("markRead", "modified=$modified")
            return modified
        } catch (e: Exception) {
            logFail("markRead", e); throw e
        }
    }

    /** Send a notification to one or more recipients (authorized server-side). */
    suspend fun send(input: SendInput): SendResult {
        logStart(
            "send",
            "type=${input.type} recipients=${input.recipients} channels=${input.channels} dedupKey=${input.dedupKey}",
        )
        try {
            val data = callData(
                path("/notify"),
                CallApiProps(
                    method = "POST",
                    payload = mapOf(
                        "tenant" to tenant(),
                        "type" to input.type,
                        "recipients" to input.recipients,
                        "title" to input.title,
                        "body" to input.body,
                        "data" to input.data,
                        "notify_channels" to input.channels,
                        "dedup_key" to input.dedupKey,
                    ),
                ),
            )
            val result = SendResult(
                created = intOf(data?.get("created")),
                skipped = intOf(data?.get("skipped")),
            )
            logOk("send", "created=${result.created} skipped=${result.skipped}")
            return result
        } catch (e: Exception) {
            logFail("send", e); throw e
        }
    }

    // ---- push devices (REST, via the gateway) ----

    /**
     * Register this device's FCM token for push. The owner is the authenticated
     * user (from the session's auth token). Idempotent: the service upserts on
     * the token, so call it on every app launch and on FCM token rotation.
     */
    suspend fun registerDevice(input: RegisterDeviceInput): PushDevice? {
        logStart("registerDevice", "platform=${input.platform} username=${input.username}")
        try {
            val payload = mutableMapOf<String, Any?>(
                "token" to input.token,
                "platform" to input.platform.name.lowercase(),
            )
            input.username?.let { payload["username"] = it }
            val data = callData(devicePath("/register"), CallApiProps(method = "POST", payload = payload))
            val result = (data?.get("device") as? Map<*, *>)?.let { toPushDevice(it) }
            logOk("registerDevice", if (result != null) "registered" else "no device returned")
            return result
        } catch (e: Exception) {
            logFail("registerDevice", e); throw e
        }
    }

    /**
     * Remove a device token (call on logout so the device stops receiving this
     * user's pushes). Returns the number of deleted registrations.
     */
    suspend fun unregisterDevice(token: String): Int {
        logStart("unregisterDevice", "token=$token")
        try {
            val data = callData(
                devicePath("/unregister"),
                CallApiProps(method = "POST", payload = mapOf("token" to token)),
            )
            val deleted = intOf(data?.get("deleted"))
            logOk("unregisterDevice", "deleted=$deleted")
            return deleted
        } catch (e: Exception) {
            logFail("unregisterDevice", e); throw e
        }
    }

    /** List the authenticated user's registered devices in this scope. */
    suspend fun listDevices(): DevicesResult {
        logStart("listDevices")
        try {
            val data = callData(devicePath(), CallApiProps(method = "GET"))
            val raw = data?.get("devices") as? List<*> ?: emptyList<Any?>()
            val result = DevicesResult(
                devices = raw.mapNotNull { (it as? Map<*, *>)?.let(::toPushDevice) },
                total = intOf(data?.get("total")),
            )
            logOk("listDevices", "${result.devices.size}/${result.total}")
            return result
        } catch (e: Exception) {
            logFail("listDevices", e); throw e
        }
    }

    // ---- internals ----

    /** Fire the request through the plugin context (core `Adk.call`) and return the `data` object. */
    private suspend fun callData(endpoint: String, options: CallApiProps): Map<*, *>? {
        // /api/<tenant>/* REST lives at the gateway ORIGIN, not under the "/ws"
        // websocket path. Route via base() (mirrors the JS `base()`), otherwise
        // the request hits https://host/ws/api/... and is rejected as
        // "Invalid Auth Token" by the websocket gateway.
        if (verboseLogging) Log.d(LOG_NAME, "--> ${options.method} ${base()}$endpoint")
        val res = ctx.call(endpoint, options.copy(baseUrl = base()), Map::class.java)
        if (verboseLogging) Log.d(LOG_NAME, "<-- ${options.method} $endpoint raw=${res?.toString()?.take(600)}")
        val root = res as? Map<*, *> ?: return null
        // Mirror the Flutter `_data`: use `data` when it is a map, else the root
        // itself (some endpoints return a flat body with no `data` wrapper).
        return (root["data"] as? Map<*, *>) ?: root
    }

    /** REST origin for /api calls: explicit override, else the core gateway origin (strips "/ws"). */
    private fun base(): String = opts.apiBaseUrl ?: ctx.baseUrl()

    private fun tenant(): String = ctx.getCredentials().orgTitle

    private fun path(suffix: String = ""): String = "/api/${tenant()}/notifications$suffix"

    private fun devicePath(suffix: String = ""): String = "/api/${tenant()}/push-devices$suffix"
}

/**
 * Plugin factory — install with `adk.use(notifications())`. Mirrors the JS
 * `notifications()`. REST/live calls require the ADK to be authenticated, so
 * install and use it after `connect()`.
 */
@RequiresApi(Build.VERSION_CODES.O)
fun notifications(options: NotificationsOptions = NotificationsOptions()): AdkPlugin<String, NotificationsApi> =
    object : AdkPlugin<String, NotificationsApi> {
        override val name: String = "notifications"
        override fun install(ctx: AdkPluginContext): NotificationsApi {
            // Mirrors the Flutter `NotificationsApi.fromAdk`: set the verbose flag
            // once, from options.debug, before creating the api.
            verboseLogging = options.debug
            logStart("NotificationsApi.fromAdk", "apiBaseUrl=${options.apiBaseUrl}")
            return NotificationsApi(ctx, options)
        }
    }

private fun normalize(payload: Any?): ArtNotification? {
    val map = payload as? Map<*, *> ?: return null
    val c: Map<*, *> = when {
        map["id"] != null || map["title"] != null -> map
        else -> (map["content"] as? Map<*, *>) ?: (map["data"] as? Map<*, *>) ?: return null
    }
    val id = c["id"]?.toString() ?: return null
    @Suppress("UNCHECKED_CAST")
    return ArtNotification(
        id = id,
        type = c["type"]?.toString() ?: "",
        title = c["title"]?.toString() ?: "",
        body = c["body"]?.toString() ?: "",
        data = c["data"] as? Map<String, Any?>,
        status = c["status"]?.toString() ?: "unread",
        createdAt = c["created_at"]?.toString() ?: "",
    )
}

private fun toPushDevice(m: Map<*, *>): PushDevice = PushDevice(
    id = m["id"]?.toString(),
    username = m["username"]?.toString() ?: "",
    token = m["token"]?.toString() ?: "",
    platform = m["platform"]?.toString() ?: "",
    provider = m["provider"]?.toString(),
    createdAt = m["created_at"]?.toString(),
    lastSeenAt = m["last_seen_at"]?.toString(),
    updatedAt = m["updated_at"]?.toString(),
)

private fun toQuery(params: ListParams): Map<String, String> {
    val q = mutableMapOf<String, String>()
    params.status?.let { q["status"] = it }
    params.page?.let { q["page"] = it.toString() }
    params.limit?.let { q["limit"] = it.toString() }
    return q
}

private fun intOf(v: Any?): Int = (v as? Number)?.toInt() ?: 0
