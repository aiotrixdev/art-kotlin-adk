# ART - A Realtime Tech communication

A powerful, coroutine-based Android library for building real-time, highly collaborative, and secure
applications. **ART ADK** provides a robust set of tools for WebSocket management, Conflict-free
Replicated Data Types (CRDTs) for multi-user state synchronization, end-to-end encrypted
messaging using Libsodium and AI orchestration integration..

## Features

- **Secure Authentication:** Easy and maintainable credential structures to manage environments,
  projects, organization keys, and dynamic interceptors.
- **Channel Subscription:** subscribe to broadcast, targeted, group, encrypted, and shared object
  channels.
- **Listen and Bind:** Capture all events with listen() or react to specific event types with
  bind().
- **User Presence:** Track online/offline status of users in real time.
- **End-to-End Encryption:** Harnessing the power of `libsodium-jni`, ART ADK lets you securely
  exchange keys and encrypt payloads out of the box (`CryptoBox`).
- **CRDT Synchronization:** Built-in Conflict-free Replicated Data Types allow you to efficiently
  trace, sync, and reconcile state dynamically across multiple clients in real-time.
- **Real-Time Connectivity:** Seamlessly connect robust WebSocket subscriptions, with smart
  reconnection, billing limit handling, and fallback capabilities.

---

## Installation

### Add Dependency

In your app-level `build.gradle.kts`:

```kotlin
implementation(project(":art_adk_library"))
```

## Quick Start

### 1. Initialization

Add this to your root build.gradle:

```kotlin
dependencyResolutionManagement {
    repositories {
        maven { url = uri("https://jitpack.io") }
    }
}
```

Configure the core ADK client with your environment credentials:

```kotlin
import com.example.art_adk_library.websockets.Adk
import com.example.art_adk_library.types.AdkConfig
import com.example.art_adk_library.types.AuthCredentials

val adkConfig = AdkConfig(
    uri = "your_server_webSocket.com",
    getCredentials = {
        AuthCredentials(
            environment = "YOUR_ENVIRONMENT",
            projectKey = "YOUR_PROJECT_KEY",
            orgTitle = "YOUR_ORG",
            clientID = "CLIENT_ID",
            clientSecret = "CLIENT_SECRET",
            accessToken = "OPTIONAL_INITIAL_TOKEN"
        )
    }
)

val adk = Adk(adkConfig)
```

### 2. Generate Client Credentials

Log in to the [ART Live Dashboard](https://dev.arealtimetech.com) and generate your client
credentials. You'll receive a JSON like:

```json
{
  "Client-ID": "xxxxxxxxxx",
  "Client-Secret": "xxxxxxxxxxx",
  "Org-Title": "YOUR_ORG",
  "ProjectKey": "YOUR_PROJECT_KEY",
  "Environment": "YOUR_ENV_NAME"
}
```

### 3. Generate a Passcode

- Use the [ART REST API]() to generate an authentication passcode before connecting.

### 4. Connecting & Managing Lifecycle

Use coroutines to manage connections:

```kotlin
lifecycleScope.launch {
    // Connect to WebSockets
    adk.connect()

    // Subscribe to connection lifecycle events
    adk.onConnection {
        Log.d("ADK", "Connected Successfully!")
    }

    adk.onLimitExceeded { code, error ->
        Log.e("ADK", "Billing limit or concurrent limit reached: $error")
    }
}
```

### 5. User Presence

```kotlin
val sub = subscription ?: return
sub.fetchPresence(callback = { users ->
    Log.d(
        "Presence", "Presence received ${users.size}"
    )
}
)
```

### 6. Secure Messaging

You can automatically generate key pairs and encrypt payloads directly over the wire
using `libsodium`:

```kotlin
lifecycleScope.launch {
    // Generate an asymmetric key-pair internally
    val myKeyPair = adk.generateKeyPair()

    // Encrypt and push secure data
    val response = adk.pushForSecureLine(
        event = "CONFIDENTIAL_UPDATE",
        data = "My secret message",
        listen = true
    )
}
```

### 7. Working with Subscriptions & CRDTs

Subscribe to regular channels and dispatch synchronous updates:

```kotlin
lifecycleScope.launch {
    val subscription = adk.subscribe("live-cursor-channel")

    subscription.on("cursor-move") { payload ->
        // Handle payload containing latest coordinates via CRDT updates
    }
}
```

### 8. Interceptors

```kotlin
adk.intercept(name) { payload, resolve, reject ->
    Log.d(
        "Intercepted", "Intercepted on '$name': $payload"
    )
}
```

### Documentation

Full documentation is available at [docs.arealtimetech.com/docs/adk]().

| Topic                  | Link                                                                                       |
|------------------------|--------------------------------------------------------------------------------------------|
| Overview               | [ADK Overview](https://docs.arealtimetech.com/docs/adk/)                                   |
| Installation           | [Installation](https://docs.arealtimetech.com/docs/adk/kotlin/installation)                |
| Publish & Subscribe    | [Pub/Sub Docs](https://docs.arealtimetech.com/docs/adk/kotlin/pub-sub)                     |
| Connection Management  | [Connection Docs](https://docs.arealtimetech.com/docs/adk/kotlin/connection-management)    |
| User Presence          | [Presence Docs](https://docs.arealtimetech.com/docs/adk/kotlin/user-presence)              |
| Encrypted Channels     | [Encryption Docs](https://docs.arealtimetech.com/docs/adk/kotlin/encrypted-channel)        |
| Shared Object Channels | [Shared Object Docs](https://docs.arealtimetech.com/docs/adk/kotlin/shared-object-channel) |
| Interceptors           | [Interceptor Docs](https://docs.arealtimetech.com/docs/adk/kotlin/intercept-channel)       |