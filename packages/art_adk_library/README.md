# ART - A Realtime Tech communication

Kotlin ADK for ART — A Realtime Tech communication, a realtime communication with WebSocket
channels, AI Agents, AI Orchestrators, presence tracking, end-to-end encrypted messaging and
CRDT-backed shared objects.

## Features

- **WebSocket connection management** — connect, pause, resume, and auto-reconnect with exponential
  backoff
- **Channel subscriptions** — default, targeted, group, secure (encrypted), and shared-object (CRDT)
  channels
- **Push messages** — send structured payloads with optional per-user targeting
- **Event listening** — receive every message with listen() or bind to named events via emitter.on()
- **Presence tracking** — observe users online on a channel in real time
- **End-to-end encryption** — crypto applied transparently on secure channels
- **Interceptors** — hook into the message pipeline to resolve or reject payloads
- **Shared objects (CRDT)** — collaborative state with automatic conflict resolution
- **AI Agents** — interact with ART Agent Builder using threads, runs, typed events, and
  Human-in-the-Loop (HITL)
- **AI Orchestrators** — execute multi-agent workflows with thread-scoped conversations and workflow
  events
- **Storage** — upload, list, retrieve, and delete files with signed URLs and progress tracking

## Installation

Add this to your app's `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
```

Then add the dependency in your app module:

```kotlin
dependencies {
    implementation("io.github.aiotrixdev:art-kotlin-adk:TAG")
}
```

Replace `TAG` with the GitHub release tag you publish through JitPack, for example `1.0.0`.

## Configuration

### 1. Initialization

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

### 2. Generate Client Credentials

Log in to the [ART Live Dashboard](https://dev.arealtimetech.com) and generate your client
credentials. Save this JSON file under asset/adk-services.json:

```json
{
  "Client-ID": "xxxxxxxxxx",
  "Client-Secret": "xxxxxxxxxxx",
  "Org-Title": "YOUR_ORG",
  "ProjectKey": "YOUR_PROJECT_KEY",
  "Environment": "YOUR_ENV_NAME"
}
```

See [Workspace and Project]() for how to obtain ProjectKey and Environment.

## 3. Load credentials

```kotlin
fun loadCredentials(context: Context): AuthenticationConfig {
    val jsonString = context.assets.open("adk-services.json").bufferedReader().use { it.readText() }

    val json = JSONObject(jsonString)

    return AuthenticationConfig(
        environment = json.getString("Environment"),
        projectKey = json.getString("ProjectKey"),
        orgTitle = json.getString("Org-Title"),
        clientID = json.getString("Client-ID"),
        clientSecret = json.getString("Client-Secret"),
    )
}
```

### 4. Generate a Passcode

Use the [ART REST API]() to generate an authentication passcode before connecting.

## Quick Start

The package includes a complete Kotlin example demonstrating every major ART ADK capability from a
single application.
The example covers

- Connection management
- Channel subscriptions
- Presence tracking
- Publish / Subscribe messaging
- Secure messaging
- CRDT shared objects
- Message interceptors
- AI Agent integration
- AI Orchestrator workflows
- Storage

### Connecting & Managing Lifecycle

Use coroutines to manage connections:

```kotlin
lifecycleScope.launch {

    val creds = loadCredentials()
    val passcode = fetchPasscode(creds)
    val updatedCreds = creds.copy(accessToken = passcode)

    val adk = Adk(
        AdkConfig(
            uri = "ws.arealtimetech.com",
            authToken = token,
            root = "",
            getCredentials = {
                updatedCreds
            }
        )
    )

    adk.on {
        Log.d("ADK", "Connected Successfully!")
    }
    // Listen FIRST
    adk.onConnection {
        Log.d("ADK", "Connected Successfully!")
    }

    adk.onLimitExceeded { code, error ->
        Log.e("ADK", "Billing limit or concurrent limit reached: $error")
    }
    // Connect to WebSockets
    adk.connect()
}
```

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

### Pushing messages

```kotlin
// Send a message
sub.push(
    event = "message",
    data = mapOf("text" to "Hello")
)
```

Target a specific user (required for secure and targeted channels — exactly one recipient):

```kotlin
sub.push(
    event = "message",
    data = mapOf("text" to "Hi Bob"),
    options = PushConfig(to = listOf("bob"))
)
```

Attempting to target zero or more than one recipient on a secure / targeted channel throws
ARTError.serverError.

###Receiving messages

Bind to a specific event on the subscription's emitter:

```kotlin
sub.emitter.on("message") { data ->
    Log.d("ART", "got: $data")
}
```

Or, on default channels, cast to Subscription and stream every event:

```kotlin
if (sub is Subscription) {
    sub.listen { data: Map<String, Any> ->
        Log.d(
            "ART",
            "event=${data["event"]} content=${data["content"]}"
        )
    }
}
```

Unbind a specific listener:

```kotlin
if (sub is Subscription) {
    sub.remove(event = "message")
}
```

### Encrypted channels

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

Shared-object channels expose a JSON-like document that every subscriber edits concurrently;
conflicts are resolved automatically via the SDK's CRDT engine.

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

## Array operations

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

```kotlin
adk.intercept(name) { payload, resolve, reject ->
    Log.d(
        "Intercepted", "Intercepted on '$name': $payload"
    )
}
```

## Agent Lab

ART ADK provides two AI integrations:

- **Agent** — interact with a single AI agent.
- **Orchestrator** — execute multi-agent workflows coordinated by an orchestrator.

## Agents

Agents let you drive a single conversational AI agent over its own real-time channel
(`agent_com_<agentId>`). Each agent exposes threads; a thread runs one logical conversation
at a time and streams typed events, terminal responses, and human-in-the-loop (HITL) feedback.

> Connect and authenticate the ADK client first (see **Installation**, **Configuration**, and
> **Quick Start** below) — agents and orchestrators run on top of an active `Adk` session.

### Connect an agent

```kotlin
// Opens the agent_com_<agentId> subscription
val agent = adk.agent(agentId)?.connect()
// Open a conversation thread (each thread has a unique threadId)
val thread = agent.thread()
```

### Run a prompt

`thread.run(...)` starts a run and returns a `Run` handle. `run.done()` suspends until the
agent emits its terminal `agent_output`, or throws `AgentErrorException` on `agent_error`
or a transport failure.

```kotlin
lifecycleScope.launch {
    val run = thread.run(user_input = "Summarise my last invoice")

    try {
        val output = run.done()          // suspends until final_response
        Log.d("Agent", "Reply: ${output.message}")
    } catch (e: AgentErrorException) {
        Log.e("Agent", "Agent failed: ${e.message}")
    }
}
```

### Listen to streamed events

Register one or more `listen` callbacks to receive every typed event on the thread.
`evt.event` is the discriminator; the matching `evt.content` carries the typed payload.

```kotlin
lifecycleScope.launch {
    thread.listen { evt ->
        when (evt.event) {
            // Terminal success — AgentOutput (type: "agent_general_response")
            "agent_output" -> Log.d("Agent", "final: ${evt.content}")

            // Terminal failure — AgentError (type: "agent_error_response")
            "agent_error" -> Log.e("Agent", "error: ${evt.content}")

            // Mid-run prompt for the user — HumanInputRequest (type: "human_input_request")
            "human_input_request" -> Log.d("Agent", "needs input: ${evt.content}")

            // Waiting on another agent — AgentWait (type: "agent_wait_response")
            "agent_wait" -> Log.d("Agent", "waiting: ${evt.content}")

            // Planner re-plan — PlannerCorrection (type: "planner_correction_request")
            "planner_correction" -> Log.d("Agent", "correction: ${evt.content}")

            // Anything the SDK does not model arrives as an untyped UnknownAgentEvent
            else -> Log.d("Agent", "unknown ${evt.event}: ${evt.content}")
        }
    }
}
```

## Orchestrator

Orchestrators coordinate multi-agent workflows over a dedicated channel
(`orch_com_<orchestratorId>`). An orchestrator thread is a lightweight, event-driven channel
you push into and listen on directly — ideal for fan-out / fan-in flows and HITL prompts
routed through a coordinator.

### Connect and open a thread

```kotlin
// Opens the orch_com_<orchestratorId> subscription
val orchestrator = adk.orchestrator("workflow-1").connect()

lifecycleScope.launch {
    // Start a thread (pass a threadId to resume an existing one)
    val thread = orchestrator.thread()
    Log.d("Orch", "thread: ${thread.getId()}")
}
```

### Push and receive

```kotlin
lifecycleScope.launch {
    val thread = orchestrator.thread()

    // Stream every event on this thread
    thread.listen { data ->
        Log.d("Orch", "event: $data")
    }

    // Or bind to a single event type
    thread.bind("agent_output") { data ->
        Log.d("Orch", "output: $data")
    }

    // Kick off the workflow
    thread.push(
        event = "user_input",
        data = mapOf("user_input" to "Plan my week")
    )
}
```

Target specific recipients or a server instance with `PushConfig`:

```kotlin
thread.push(
    event = "user_input",
    data = mapOf("user_input" to "hello"),
    options = PushConfig(to = listOf("kiran"))
)
```

### Clean up

```kotlin
// Remove a single listener / binding
thread.remove(event = "agent_output")

// Tear down the thread and detach all listeners
thread.dispose()
```      

### Storage

The Storage service allows you to manage files within your ART project. It supports uploading binary
data, listing files, fetching file metadata/read URLs, and deleting files.

### Upload a file

```kotlin
val bytes = withContext(Dispatchers.IO) {
    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
} ?: throw IllegalStateException("could not read file")

val ref = agent.upload(
    bytes,
    UploadOptions(
        filename = 'my_image.jpg',
        contentType = 'image/jpeg',
        onProgress = { p -> progress = p },
    )
)
Log.d('File ID: ${fileRef.fileId}')
Log.d('Read URL: ${fileRef.readUrl}')
```  
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

## API reference

| Class/Type          | Purpose                                                                        |
|---------------------|--------------------------------------------------------------------------------|
| Adk                 | Top-level facade — connect, subscribe, intercept, key management               |
| AdkConfig           | Connection configuration (URI, credentials, auth token)                        |
| CredentialStore     | Immutable holder for org / project / client credentials                        |
| BaseSubscription    | Base type for all channel subscriptions (push, fetchPresence, unsubscribe)     |
| Subscription        | Default / secure / targeted / group channel — listen(), bind(), emitter.on()   |
| LiveObjSubscription | CRDT-backed shared-object channel — state(), query(), flush()                  |
| CRDTProxy           | Chainable accessor for reading / mutating CRDT state                           |
| PushConfig          | Push options — to: List<String> for targeted delivery                          |
| KeyPairType         | KeyPairType for secure channels                                                |
| ConnectionDetail    | Emitted on 'connection' — contains connectionId, instanceId, tenant info       |
| Orchestrator        | Orchestrator handle bound to `orch_com_<orchestratorId>` — connect(), thread() |
| OrchestratorThread  | Thread channel — push(), listen(), bind(), remove(), dispose(), getId()        |
| PushConfig          | Push options — to: List<String>, instanceId, threadId                          

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