# ADK Notifier

A Kotlin package for integrating with the ART Real-time Notification Service. This package provides tools for notification history management, live WebSocket updates, and push device registration.

## Features

- **Notification History**: List all notifications for a tenant.
- **Real-time Events**: Listen for new notifications via WebSocket subscriptions.
- **Unread Management**: Track unread counts and mark notifications as read.
- **Push Registration**: Register and unregister device tokens for push notifications (e.g., FCM).
- **Notification Sending**: Send targeted notifications to specific recipients.

## Getting started

Add `art_notifier` to your app module's `build.gradle.kts` along with the core `art-kotlin-adk`:

```kotlin
dependencies {     
    implementation("com.github.aiotrixdev:art-kotlin-adk:TAG")
    implementation("com.github.aiotrixdev:art-kotlin-notifier:TAG")
}
```

## Usage

All API methods are `suspend` functions — call them from a coroutine (requires API level 26+).

### Initialize the API

The `NotificationsApi` is obtained from an existing, connected `Adk` instance via the plugin system:

```kotlin
import com.example.artlibrary.websockets.Adk
import com.example.art_notifier.notifications
import com.example.art_notifier.NotificationsOptions

val adk = Adk(adkConfig)
adk.connect()

val notificationsApi = adk.use(
    notifications(
        NotificationsOptions(
            debug = true,
            apiBaseUrl = "your_base_url", // Optional override
        )
    )
)
```

### Listen for Real-time Notifications

```kotlin
val removeListener = notificationsApi.onNew { notification ->
    println("New notification: ${notification.title}")
    println("Payload: ${notification.data}")
}

// To stop listening later:
// removeListener()
```

### Load History and Unread Counts

```kotlin
// Fetch notifications
val result = notificationsApi.list(
    ListParams(status = "unread", limit = 20)
)
println("Total notifications: ${result.total}")

// Get unread count
val count = notificationsApi.unreadCount()
println("Unread: $count")
```

### Mark as Read

```kotlin
// Mark specific notifications as read
notificationsApi.markRead(listOf("notif_id_1", "notif_id_2"))

// Mark all as read
notificationsApi.markRead()
```

### Push Device Management

```kotlin
import com.example.art_notifier.RegisterDeviceInput
import com.example.art_notifier.DevicePlatform

// Register device for push
notificationsApi.registerDevice(
    RegisterDeviceInput(
        token = "FCM_TOKEN",
        platform = DevicePlatform.ANDROID,
    )
)

// List registered devices
val deviceResult = notificationsApi.listDevices()
println("Registered devices: ${deviceResult.total}")

// Unregister device
notificationsApi.unregisterDevice("FCM_TOKEN")
```

### Send a Notification

```kotlin
import com.example.art_notifier.SendInput

notificationsApi.send(
    SendInput(
        recipients = listOf("user_123"),
        type = "alert",
        title = "Hello",
        body = "This is a test notification",
        data = mapOf("click_action" to "open_settings"),
        channels = listOf("in_app", "push"),
        dedupKey = "unique_request_id_123",
    )
)
```

## Additional information

For more documentation on the underlying ART platform, visit [docs.arealtimetech.com](https://docs.arealtimetech.com/).