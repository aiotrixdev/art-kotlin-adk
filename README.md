# ART Kotlin ADK

The ART Kotlin ADK connects android apps to [ART (A Realtime Tech)](https://arealtimetech.com/), a
realtime communication platform for building intelligent applications with WebSocket-based
messaging, AI Agents, AI Orchestrators, presence tracking, end-to-end encrypted channels, and
CRDT-backed shared objects.

With the ADK you can:

- Send and receive messages in real time
- See who is online in a channel
- Encrypt conversations end-to-end
- Keep shared data in sync across users and devices
- Check or block messages before they are delivered
- Chat with AI agents and run multi-agent workflows
- Upload files and share them with AI agents
- Receive in-app notifications

## Contents

- [Requirements](#requirements)
- [Installation](#installation)
- [Getting started](#getting-started)
- [Connection](#connection)
- [Channels and messages](#channels-and-messages)
- [Presence](#presence)
- [Encrypted channels](#encrypted-channels)
- [Shared objects](#shared-objects)
- [Interceptors](#interceptors)
- [AI agents](#ai-agents)
- [AI orchestrators](#ai-orchestrators)
- [File storage](#file-storage)
- [Profiles and connectors](#profiles-and-connectors)
- [Notifications](#notifications)
- [Calling ART APIs](#calling-art-apis)
- [Errors](#errors)
- [Logging](#logging)
- [Documentation](#documentation)
- [License](#license)

## Requirements

- Android 8.0 (API 26) or later
- Kotlin 1.9+ with coroutines
- Android Studio Hedgehog or later
- An ART project with its environment, project key, organisation and client ID

## Installation

Add the repository in `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
```

Add the dependency in your app module:

```kotlin
dependencies {
    implementation("io.github.aiotrixdev:art-kotlin-adk:TAG")
    implementation("com.aiotrix:art-adk-notifications:TAG")   // optional

}
```

Replace `TAG` with the GitHub release tag you publish through JitPack, for example `1.0.0`.

## Getting started

#### 1. Get a user passcode

ART signs users in with a short-lived passcode. Your backend requests the passcode from ART using
your client secret, and returns only the passcode to the app:

```kotlin
suspend fun fetchPasscode(username: String): String = withContext(Dispatchers.IO) {
    val body = JSONObject().put("username", username).toString()
        .toRequestBody("application/json".toMediaType())
    val request = Request.Builder().url("PASSCODE_URL").post(body).build()
    OkHttpClient().newCall(request).execute().use { response ->
        JSONObject(response.body?.string().orEmpty()).optString("passcode")
    }
}
```

**Important:** Never put your client secret in the app. Anything shipped inside an app can be
extracted. Whether users can sign in using only a passcode depends on your ART project settings, so
confirm this with the ART team before release.

### 2. Create credentials and connect

Configure the core ADK client with your environment credentials:

```kotlin


val adkConfig = AdkConfig(
    uri = "your_server_webSocket.com",
    authToken = "YOUR_AUTH_TOKEN",
    root = "",
    getCredentials = {
        CredentialStore(
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

### 3. Send and receive a message

```kotlin
val channel = adk.subscribe("CHANNEL_NAME")

channel.on("all") { data ->
    Log.d("ART", "Received: $data")
}

channel.push(event = "message", data = mapOf("text" to "Hello ART!"))
```

Run these calls from a coroutine such as viewModelScope.launch { ... }. For anything that blocks,
such as presence or interceptors, switch to Dispatchers.IO.

## Connection

`connect()` opens the connection, signs the user in, and returns once ART has accepted the
connection. If the connection drops, the ADK reconnects automatically.

```kotlin
val onConnection: (Any?) -> Unit = { Log.d("ART", "Connected") }
val onClose: (Any?) -> Unit = { reason -> Log.d("ART", "Closed, reconnecting: $reason") }

adk.on("connection", onConnection)
adk.on("close", onClose)

// Remove the listeners when you no longer need them:
adk.off("connection", onConnection)
adk.off("close", onClose)

try {
    adk.connect()
} catch (e: Exception) {
    Log.e("ART", "Couldn't connect", e)
}
```

connect() throws a ConnectionError when it can't connect: AUTHENTICATION_FAILED when the credentials
are missing or ART rejects them, or CONNECTION_TIMEOUT when the connection isn't ready in time.
Calls made while a connection attempt is in progress share it.

By default the ADK waits up to 30 seconds for the connection, for a sign-in request and for ART's
reply to a request such as a subscribe. To change this, set connectionTimeoutMs to a positive
number; anything else makes connect() throw ARTError.invalidArgument. It doesn't limit how long an
agent can take to answer.

## When the connection fails

Sometimes the connection can't continue: the credentials were rejected, a limit was reached, you
paused or closed the connection, or it couldn't be restored after five attempts. The ADK then emits
`connectionFailed` with a `ConnectionError` and stops reconnecting. Agent runs in progress end with
an error, and later sends and subscribes throw the same `ConnectionError` until you call `connect()`
again.

```Kotlin
adk.on("connectionFailed") { data -> Log.w("ART", "Connection failed: $data") }
```

| **Code**                                                         | **Meaning**                                                                                          |
|------------------------------------------------------------------|------------------------------------------------------------------------------------------------------|
| `AUTHENTICATION_FAILED`                                          | The credentials are missing or were rejected. `status` holds the HTTP status when ART rejected them. |
| `CONNECTION_TIMEOUT`                                             | The connection, or ART's reply to a request, didn't arrive in time                                   |
| `CONNECTION_LOST`                                                | The connection dropped before a message could be sent, or couldn't be restored                       |
| `CONNECTION_PAUSED`, `CONNECTION_CLOSED`                         | You called `pause()`, `closeWebSocket()` or `disconnect()`                                           |
| `CONNECTION_CANCELLED`                                           | A newer connection attempt replaced this one                                                         |
| `CONNECTION_AUTHORIZATION_FAILED`                                | The connection's sign-in couldn't be renewed                                                         |
| `ACK_TIMEOUT`                                                    | ART didn't confirm a message                                                                         |
| `CONNECTION_MINUTES_LIMIT_EXCEEDED`, `CONCURRENT_LIMIT_EXCEEDED` | Your project reached its billing or concurrency limit                                                |

`adk.state` tells you where the connection stands:

| **State**     | **Meaning**                                              |
|---------------|----------------------------------------------------------|
| `.connecting` | Opening the connection, or reconnecting after it dropped |
| `.connected`  | Connected and signed in                                  |
| `.paused`     | Paused with `pause()`                                    |
| `.stopped`    | Not connected                                            |

If your project reaches its billing or concurrency limit, the ADK also emits a `limitExceeded`event.

## Channels and messages

A channel is a named stream of messages. Subscribe to a channel to send and receive messages on it.
`subscribe` returns a `Subscription` for regular channels and a `LiveObjSubscription`
for [shared-object channels](#shared-objects).

```kotlin
val channel = adk.subscribe("room-42")

// When you no longer need the channel:
channel.unsubscribe()
```

### Sending messages

```kotlin
channel.push(event = "message", data = mapOf("text" to "Hello"))
```

To send a message to specific users, list their usernames in the `to` option:

```kotlin
channel.push(
    event = "message",
    data = mapOf("text" to "Hi Bob"),
    options = PushConfig(to = listOf("bob"))
)
```

### Receiving messages

```kotlin
channel.emitter.on("message") {
    data in
            print("Received:", data)
}
```

On regular channels, `bind(event:)` also delivers messages that arrived before you started
listening. It returns a token that you can use to remove that listener later:

### User Presence

```kotlin
val sub = subscription ?: return
sub.fetchPresence(callback = { users ->
    Log.d(
        "Presence", "Presence received ${users.size}"
    )
}
)
```

### Subscriptions to a channel

adk.subscribe returns a BaseSubscription. For default / secure / targeted / group channels it is a
Subscription; for shared-object channels it is a LiveObjSubscription

```kotlin
lifecycleScope.launch {
    val subscription = adk.subscribe("live-cursor-channel")

    subscription.on("cursor-move") { payload ->
        // Handle payload containing latest coordinates via CRDT updates
    }
}
```

Unsubscribe when you no longer need the channel:

```kotlin
sub.unsubscribe()
```

### Encrypted channels

Messages on encrypted channels are encrypted on the sender's device, and only the recipients can
read them. Before using an encrypted channel, create a key pair for the current user.
generateKeyPair() creates the keys and registers the public key with ART.

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

Then push as usual. Encryption and decryption happen transparently:

```kotlin
val secure = adk.subscribe(
    channel = "YOUR_SECURE_CHANNEL"
)

secure.push(
    event = "message",
    data = mapOf("text" to "This is private"),
    options = PushConfig(
        to = listOf("bob") // secure requires exactly one recipient
    )
)
secure.emitter.on("message") { data ->
    Log.d("ART", "decrypted: $data")
}
```

Interceptors never see encrypted channel traffic — ART preserves message privacy end-to-end.

## Shared object channels (CRDT)

A shared-object channel holds a document that every subscriber can read and edit. Changes are merged
automatically using CRDTs (conflict-free replicated data types), so everyone ends up with the same
data.

```kotlin
val sub = adk.subscribe("YOUR_CRDT_CHANNEL")

if (sub is LiveObjSubscription) {
    val crdt = sub.crdt ?: return@launch

    // 1. Write
    crdt.state()["document"].set("My Doc")
    sub.flush()

    // 1. Read once
    val query = crdt.query("document")
    val snapshot = query.execute()
    Log.d("ADK", "doc: $snapshot")

    // 3. Observe updates
    val dispose = query.listen { data ->
        Log.d(
            "ADK",
            "document updated: $data"
        )
    }
    dispose()
}
```

## Lists support the usual operations:

```kotlin
val items = sub.state()["items"]   // CRDTProxy

// Write operations
items.push("alpha")       // append
items.unshift("zero")     // prepend
items.pop()               // remove last
items.removeAt(2)         // remove by index
items.splice(
    start = 1,
    deleteCount = 2,
    insert = listOf("x", "y")
)

// Sync changes
sub.flush()

Log.d("ADK", "length: ${items.length}")
```

### Interceptors

An interceptor sees messages before they are delivered and decides what happens to each one. Call
resolve to deliver the message, with or without changes, or reject to block it.

```kotlin
adk.intercept(name) { payload, resolve, reject ->
    Log.d(
        "Intercepted", "Intercepted on '$name': $payload"
    )
}
```

## AI agents

Chat with an agent built on ART. Each conversation takes place in a thread.

### Start a conversation

```kotlin
val agent = adk.agent("your-agent-id")
val thread = agent.thread()   // or agent.thread("THREAD_ID") to continue a conversation

thread.listen { envelope -> Log.d("ART", "Event: $envelope") }

val run = thread.run("Plan a 3-day trip to Dubai")

try {
    val output = run.done()
    Log.d("ART", output.message)
} catch (e: Exception) {
    Log.e("ART", "The agent couldn't finish", e)
}
```

### Display progress

Use the thread's state to drive a status indicator, such as "Queued" or "Waiting for your input":

```kotlin
thread.listenState { state -> Log.d("ART", "${state.phase}: ${state.message}") }
```

The phase is one of idle, submitted, queued, running, waitingForAgent, waitingForApproval,
waitingForWorkspace, completed, failed or cancelled.

### Answer questions from the agent

An agent can pause and ask the user for more information (human-in-the-loop). Register a handler
before you start the run, and send the answer with sendFeedback:

```kotlin
thread.feedbackRequest { request, run ->
    Log.d("ART", "The agent asks: ${request.prompt}")
    scope.launch { run.sendFeedback("Budget is 50,000, travelling in December") }
}
```

## AI orchestrators

An orchestrator coordinates several agents to complete a larger task. Start a thread, listen for its
events, and send the user's input:

```kotlin
val orchestrator = adk.orchestrator("your-orchestrator-id")
val thread = withContext(Dispatchers.IO) { orchestrator.thread() }

thread.listen { event -> Log.d("ART", "Event: $event") }

thread.listenState(callback = { state -> Log.d("ART", "Phase: ${state.phase}") })

withContext(Dispatchers.IO) {
    thread.push(
        event = "user_input",
        data = mapOf("user_input" to "Plan a 3-day trip to Goa"),
        options = PushConfig()
    )
}
```

If the connection fails while a request is in progress, `listen` receives an `agent_error` event
whose details have `source: connection`, and the thread's state becomes `failed`. Call
`thread.dispose()` when you no longer need the thread.

## File storage

Files are temporary media that belong to a conversation thread. Upload a file, then list, fetch or
delete it. Each upload returns a file reference with the file's ID, name, size, content type and a
URL for reading it.

```kotlin
val ref = adk.upload(
    data = pngBytes, filename = "chart.png", contentType = "image/png",
    options = UploadOptions(threadId = "THREAD_ID", allowedMimeTypes = listOf("image/png"))
)

val page = adk.listFiles(ListOptions(threadId = "THREAD_ID", page = 1, limit = 20))
val file = adk.getFile(fileId = ref.fileId, threadId = "THREAD_ID")
adk.deleteFile(fileId = ref.fileId)
```

Every file has an owner, which depends on where you upload it from:

| **Uploaded with**                                            | **Owner**                                                                 |
|--------------------------------------------------------------|---------------------------------------------------------------------------|
| `adk.upload`                                                 | Your project, or the configuration you pass in `UploadOptions(configId:)` |
| `agent.upload`, or `upload` on an agent thread               | The agent                                                                 |
| `orchestrator.upload`, or `upload` on an orchestrator thread | The orchestrator                                                          |
| `upload` on a channel                                        | The channel's configuration (orchestrator-enabled channels only)          |

When an upload fails, the ADK throws an `UploadError`. Its `step` tells you which stage failed (
`.validate`, `.upload`, `.list`, `.get` or `.delete`), and `status` holds the HTTP status code. A
`403` means the user's role doesn't have storage permission.

### List a file

```kotlin
val a = viewModel.agent ?: return
loadingFiles = true
try {
    files = a.listFiles().files.sortedByDescending { it.createdAt }
} catch (e: Exception) {
    status = "List failed: ${e.message}"
} finally {
    loadingFiles = false
}
``` 

### Delete file

```kotlin
Storage().deleteFile(f.fileId)
```

### Notifications

Notifications are in the optional art_notifier library. Add it to your Adk instance once and keep
the object it returns:

```kotlin

val inbox = adk.use(notifications(NotificationsOptions(debug = true)))

// Receive new notifications as they arrive.
inbox.onNew { n -> Log.d("ART", "New: ${n.title}") }

val history = inbox.list()                 // history.notifications, history.total
val unread = inbox.unreadCount()
inbox.markRead()                           // marks every unread notification as read
```

### Push notifications (FCM)

Register the device's FCM token so notifications reach the device when the app is in the background:

```kotlin
val devices = inbox.listDevices()
if (devices.devices.none { it.token == fcmToken }) {
    inbox.registerDevice(RegisterDeviceInput(token = fcmToken, platform = DevicePlatform.ANDROID))
}
```

This needs google-services.json in your app module and the Firebase Messaging dependency.

### Calling ART APIs

call sends an authenticated request to an ART REST endpoint and returns the decoded JSON response:

```kotlin
val result = adk.call(
    "/v1/some-endpoint",
    CallApiProps(method = "POST", payload = mapOf("key" to "value")),
    Map::class.java
) as? Map<*, *>
```

For GET requests, pass query parameters and a base URL:

```kotlin
val result = adk.call(
    "/api/YOUR_ORG/agent-configuration/agent/configs",
    CallApiProps(
        method = "GET",
        baseUrl = "https://your-console-host",
        queryParams = mapOf("page" to "1", "search_text" to "")
    ),
    Map::class.java
) as? Map<*, *>
```

### Errors

| **Error**           | **Thrown when**                                                                                                                                                                                                                             |
|---------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `ConnectionError`   | The connection failed or isn't available. See [When the connection fails](#when-the-connection-fails).                                                                                                                                      |
| `AuthHttpError`     | ART rejected a sign-in request. `status` holds the HTTP status, and `isTerminal` is `true` for rejected credentials (401 or 403).                                                                                                           |
| `AgentError`        | An agent run failed, or ended because the connection failed                                                                                                                                                                                 |
| `RunLifecycleError` | `run.done()` ended without an answer from the agent, because a newer run on the thread replaced it (`.superseded`) or its thread was disposed (`.closed`). `sendFeedback` also throws `.closed` when the agent isn't waiting for an answer. |
| `UploadError`       | A storage request failed                                                                                                                                                                                                                    |
| `ConnectorError`    | A connector ID is empty or can't be resolved, or you updated a field the connector doesn't allow                                                                                                                                            |
| `HTTPCallError`     | An ART REST request failed in `updateProfile` or a plugin                                                                                                                                                                                   |
| `ArtJSONError`      | Data you passed can't be converted to JSON                                                                                                                                                                                                  |
| `ARTError`          | Other errors, for example `.invalidArgument` for an agent ID that isn't a slug, or `.serverError` when ART refuses a request or `adk.call` gets an error response                                                                           |                                                                        |

## Logging

The ADK doesn't print anything by default. To see its warnings and errors, set a log handler:

## Documentation

Full documentation is available
at [docs.arealtimetech.com/docs/adk](https://docs.arealtimetech.com/docs/adk).

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
| Agent                  | [Agent Docs](https://docs.arealtimetech.com/docs/adk/kotlin/agent)                         |
| Orchestrator           | [Orchestrator Docs](https://docs.arealtimetech.com/docs/adk/kotlin/orchestrator)           |

## Licence

Licensed under the Apache License 2.0. 