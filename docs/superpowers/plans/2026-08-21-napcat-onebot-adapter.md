# NapCat OneBot Adapter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an authenticated OneBot v11 reverse WebSocket adapter that lets one configured NapCat QQ bot route private and group text messages through the single server-hosted OpenEden runtime.

**Architecture:** A standalone `:onebot` JVM module owns protocol parsing, one active connection epoch, bounded event processing, correlated actions, and heartbeat delivery. `:server` supplies the existing pipeline through a narrow handler, owns configuration and lifecycle, and installs the adapter route; all persona, VQ-VAE, memory, and vector behavior remains in `:core`.

**Tech Stack:** Kotlin/JVM 21, Ktor Server WebSockets 3.5, kotlinx.coroutines, kotlinx.serialization JSON, kotlin.test, Ktor test host

---

## Prerequisite

Complete `docs/superpowers/plans/2026-08-21-module-boundary-migration.md` first. This plan assumes the root is aggregation-only and `:cli` and `:client` already own their sources.

## File Map

- Create `onebot/build.gradle.kts`: OneBot module dependencies and JVM test configuration.
- Modify `settings.gradle.kts`: include `:onebot`.
- Create focused files under `onebot/src/main/kotlin/io/openeden/onebot/{config,protocol,connection,ingress,egress,heartbeat,route}`.
- Create matching tests under `onebot/src/test/kotlin/io/openeden/onebot/**`.
- Create `server/src/main/kotlin/io/openeden/server/bootstrap/OneBotConfigLoader.kt`: Ktor config mapping.
- Create `server/src/main/kotlin/io/openeden/server/bootstrap/OneBotRuntimeKey.kt`: application attribute ownership.
- Create `server/src/main/kotlin/io/openeden/server/adapter/onebot/OneBotModule.kt`: route installation.
- Modify `server/src/main/kotlin/io/openeden/server/bootstrap/Runtime.kt`: construct and close one adapter, and wire heartbeat delivery.
- Modify `server/src/main/resources/application.yaml`: expose OneBot configuration and module startup.
- Modify `server/build.gradle.kts`: depend on `:onebot`.
- Add server config tests and update README deployment instructions.

### Task 1: Create the OneBot module and validated configuration model

**Files:**
- Create: `onebot/build.gradle.kts`
- Modify: `settings.gradle.kts`
- Create: `onebot/src/main/kotlin/io/openeden/onebot/config/OneBotGroupPolicy.kt`
- Create: `onebot/src/main/kotlin/io/openeden/onebot/config/OneBotConfig.kt`
- Test: `onebot/src/test/kotlin/io/openeden/onebot/config/OneBotConfigTest.kt`

- [ ] **Step 1: Add failing configuration tests**

Create `OneBotConfigTest.kt`:

```kotlin
package io.openeden.onebot.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OneBotConfigTest {
    @Test
    fun `enabled config requires token self id and absolute path`() {
        assertFailsWith<IllegalArgumentException> {
            OneBotConfig(enabled = true, accessToken = "", botSelfId = "10001")
        }
        assertFailsWith<IllegalArgumentException> {
            OneBotConfig(enabled = true, accessToken = "secret", botSelfId = "")
        }
        assertFailsWith<IllegalArgumentException> {
            OneBotConfig(enabled = true, path = "onebot", accessToken = "secret", botSelfId = "10001")
        }
    }

    @Test
    fun `defaults are bounded and mention only`() {
        val config = OneBotConfig(enabled = false)
        assertEquals(OneBotGroupPolicy.MENTION_ONLY, config.groupPolicy)
        assertEquals(64, config.eventQueueCapacity)
        assertEquals(4, config.eventWorkers)
        assertEquals(10_000L, config.actionTimeoutMs)
        assertEquals(2, config.maxActionRetries)
    }
}
```

- [ ] **Step 2: Create the module build and verify tests fail**

Add `include(":onebot")` to `settings.gradle.kts` and create `onebot/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":core"))
    implementation(ktorLibs.server.core)
    implementation(ktorLibs.server.websockets)
    implementation(ktorLibs.serialization.kotlinx.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation(ktorLibs.client.websockets)
    testImplementation(ktorLibs.server.testHost)
    testImplementation(libs.kotlinx.coroutines.test)
}
```

Run:

```powershell
.\gradlew.bat :onebot:test
```

Expected: FAIL because `OneBotConfig` and `OneBotGroupPolicy` do not exist.

- [ ] **Step 3: Implement the pure configuration types**

Create `OneBotGroupPolicy.kt`:

```kotlin
package io.openeden.onebot.config

enum class OneBotGroupPolicy {
    MENTION_ONLY,
    ALL,
    DISABLED,
}
```

Create `OneBotConfig.kt`:

```kotlin
package io.openeden.onebot.config

data class OneBotConfig(
    val enabled: Boolean,
    val path: String = "/onebot/v11",
    val accessToken: String = "",
    val botSelfId: String = "",
    val groupPolicy: OneBotGroupPolicy = OneBotGroupPolicy.MENTION_ONLY,
    val eventQueueCapacity: Int = 64,
    val eventWorkers: Int = 4,
    val actionTimeoutMs: Long = 10_000L,
    val maxActionRetries: Int = 2,
) {
    init {
        require(path.startsWith('/') && !path.contains("?") && !path.contains('#')) {
            "OneBot path must be an absolute route path"
        }
        require(eventQueueCapacity in 1..4096) { "OneBot event queue capacity must be in 1..4096" }
        require(eventWorkers in 1..64) { "OneBot event worker count must be in 1..64" }
        require(actionTimeoutMs in 100L..120_000L) { "OneBot action timeout must be in 100..120000 ms" }
        require(maxActionRetries in 0..5) { "OneBot action retries must be in 0..5" }
        if (enabled) {
            require(accessToken.isNotBlank()) { "OneBot access token is required when enabled" }
            require(botSelfId.isNotBlank()) { "OneBot bot self ID is required when enabled" }
        }
    }
}
```

- [ ] **Step 4: Run and commit the module foundation**

Run:

```powershell
.\gradlew.bat :onebot:test
git add settings.gradle.kts onebot
git commit -m "build(onebot): add adapter module"
```

Expected: tests pass and the commit title is `build(onebot): add adapter module`.

### Task 2: Parse and map OneBot v11 message events

**Files:**
- Create: `onebot/src/main/kotlin/io/openeden/onebot/protocol/OneBotReplyTarget.kt`
- Create: `onebot/src/main/kotlin/io/openeden/onebot/protocol/OneBotMessageEvent.kt`
- Create: `onebot/src/main/kotlin/io/openeden/onebot/protocol/OneBotActionResponse.kt`
- Create: `onebot/src/main/kotlin/io/openeden/onebot/protocol/OneBotInbound.kt`
- Create: `onebot/src/main/kotlin/io/openeden/onebot/protocol/OneBotEventParser.kt`
- Create: `onebot/src/main/kotlin/io/openeden/onebot/ingress/OneBotRequestMapper.kt`
- Test: `onebot/src/test/kotlin/io/openeden/onebot/protocol/OneBotEventParserTest.kt`
- Test: `onebot/src/test/kotlin/io/openeden/onebot/ingress/OneBotRequestMapperTest.kt`

- [ ] **Step 1: Write parser tests for private, group, mention, self, malformed, and action-response frames**

Create table-driven tests that call `OneBotEventParser(Json).parse(raw, config)`. Include these exact assertions:

```kotlin
val private = parser.parse(
    """{"self_id":10001,"post_type":"message","message_type":"private","message_id":7,"user_id":22,"message":"hello"}""",
    config,
) as OneBotInbound.Message
assertEquals("hello", private.event.text)
assertEquals(OneBotReplyTarget.Private("22"), private.event.target)

val group = parser.parse(
    """{"self_id":10001,"post_type":"message","message_type":"group","message_id":8,"group_id":33,"user_id":22,"message":[{"type":"at","data":{"qq":"10001"}},{"type":"text","data":{"text":" hello"}}]}""",
    config,
) as OneBotInbound.Message
assertEquals("hello", group.event.text)
assertEquals(OneBotReplyTarget.Group("33"), group.event.target)

assertIs<OneBotInbound.Ignored>(parser.parse("not-json", config))
assertIs<OneBotInbound.Ignored>(parser.parse(selfMessageJson, config))
assertIs<OneBotInbound.Action>(parser.parse("""{"status":"ok","retcode":0,"echo":"e1"}""", config))
```

Also assert that `MENTION_ONLY` ignores an unmentioned group message, `ALL` accepts it, and `DISABLED` ignores every group message.

- [ ] **Step 2: Write request mapping tests**

Create `OneBotRequestMapperTest.kt` with:

```kotlin
val privateRequest = OneBotRequestMapper.map(privateEvent)
assertEquals("QQ", privateRequest.platform)
assertEquals("22", privateRequest.scopeId)
assertEquals("22", privateRequest.userId)
assertEquals("onebot_10001_7", privateRequest.turnId)

val groupRequest = OneBotRequestMapper.map(groupEvent)
assertEquals("33", groupRequest.scopeId)
assertEquals("22", groupRequest.userId)
```

- [ ] **Step 3: Run tests to verify protocol types are missing**

Run:

```powershell
.\gradlew.bat :onebot:test --tests '*OneBotEventParserTest' --tests '*OneBotRequestMapperTest'
```

Expected: FAIL compiling the missing protocol and mapper types.

- [ ] **Step 4: Implement focused protocol models**

Use one public top-level type per file:

```kotlin
// OneBotReplyTarget.kt
sealed interface OneBotReplyTarget {
    data class Private(val userId: String) : OneBotReplyTarget
    data class Group(val groupId: String) : OneBotReplyTarget
}

// OneBotMessageEvent.kt
data class OneBotMessageEvent(
    val selfId: String,
    val messageId: String,
    val userId: String,
    val text: String,
    val target: OneBotReplyTarget,
)

// OneBotActionResponse.kt
data class OneBotActionResponse(
    val status: String,
    val retCode: Int,
    val echo: String,
)

// OneBotInbound.kt
sealed interface OneBotInbound {
    data class Message(val event: OneBotMessageEvent) : OneBotInbound
    data class Action(val response: OneBotActionResponse) : OneBotInbound
    data class Ignored(val reason: String) : OneBotInbound
}
```

Add package declarations and imports in each physical file.

- [ ] **Step 5: Implement structured JSON parsing**

Create `OneBotEventParser.kt`:

```kotlin
package io.openeden.onebot.protocol

import io.openeden.onebot.config.OneBotConfig
import io.openeden.onebot.config.OneBotGroupPolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class OneBotEventParser(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun parse(raw: String, config: OneBotConfig): OneBotInbound = runCatching {
        parseObject(json.parseToJsonElement(raw).jsonObject, config)
    }.getOrElse { OneBotInbound.Ignored("malformed") }

    private fun parseObject(root: JsonObject, config: OneBotConfig): OneBotInbound {
        root.id("echo")?.let { echo ->
            return OneBotInbound.Action(
                OneBotActionResponse(
                    status = root.id("status").orEmpty(),
                    retCode = root["retcode"]?.jsonPrimitive?.intOrNull ?: -1,
                    echo = echo,
                ),
            )
        }
        if (root.id("post_type") != "message") return OneBotInbound.Ignored("unsupported_post_type")
        val selfId = root.id("self_id") ?: return OneBotInbound.Ignored("missing_self_id")
        if (selfId != config.botSelfId) return OneBotInbound.Ignored("self_id_mismatch")
        val userId = root.id("user_id") ?: return OneBotInbound.Ignored("missing_user_id")
        if (userId == selfId) return OneBotInbound.Ignored("self_message")
        val messageId = root.id("message_id") ?: return OneBotInbound.Ignored("missing_message_id")
        val extracted = extract(root["message"], selfId)
        val text = extracted.text.trim()
        if (text.isEmpty()) return OneBotInbound.Ignored("empty_text")

        val target = when (root.id("message_type")) {
            "private" -> OneBotReplyTarget.Private(userId)
            "group" -> {
                when (config.groupPolicy) {
                    OneBotGroupPolicy.DISABLED -> return OneBotInbound.Ignored("group_disabled")
                    OneBotGroupPolicy.MENTION_ONLY -> if (!extracted.mentioned) {
                        return OneBotInbound.Ignored("mention_required")
                    }
                    OneBotGroupPolicy.ALL -> Unit
                }
                OneBotReplyTarget.Group(
                    root.id("group_id") ?: return OneBotInbound.Ignored("missing_group_id"),
                )
            }
            else -> return OneBotInbound.Ignored("unsupported_message_type")
        }
        return OneBotInbound.Message(
            OneBotMessageEvent(selfId, messageId, userId, text, target),
        )
    }

    private fun extract(message: JsonElement?, selfId: String): ExtractedText = when (message) {
        is JsonArray -> {
            val text = StringBuilder()
            var mentioned = false
            message.forEach { element ->
                val segment = element.jsonObject
                val data = segment["data"]?.jsonObject ?: return@forEach
                when (segment.id("type")) {
                    "text" -> text.append(data.id("text").orEmpty())
                    "at" -> if (data.id("qq") == selfId) mentioned = true
                }
            }
            ExtractedText(text.toString(), mentioned)
        }
        null -> ExtractedText("", false)
        else -> ExtractedText(message.jsonPrimitive.contentOrNull.orEmpty(), false)
    }

    private fun JsonObject.id(name: String): String? =
        get(name)?.jsonPrimitive?.contentOrNull

    private data class ExtractedText(val text: String, val mentioned: Boolean)
}
```

- [ ] **Step 6: Implement the pure request mapper**

Create `OneBotRequestMapper.kt`:

```kotlin
package io.openeden.onebot.ingress

import io.openeden.onebot.protocol.OneBotMessageEvent
import io.openeden.onebot.protocol.OneBotReplyTarget
import io.openeden.runtime.pipeline.DevelopmentMessageRequest

object OneBotRequestMapper {
    fun map(event: OneBotMessageEvent): DevelopmentMessageRequest = DevelopmentMessageRequest(
        turnId = "onebot_${event.selfId}_${event.messageId}",
        platform = "QQ",
        scopeId = when (val target = event.target) {
            is OneBotReplyTarget.Private -> target.userId
            is OneBotReplyTarget.Group -> target.groupId
        },
        userId = event.userId,
        text = event.text,
    )
}
```

- [ ] **Step 7: Run and commit protocol mapping**

Run:

```powershell
.\gradlew.bat :onebot:test --tests '*OneBotEventParserTest' --tests '*OneBotRequestMapperTest'
git add onebot
git commit -m "feat(onebot): parse QQ message events"
```

Expected: focused tests pass.

### Task 3: Manage one active connection epoch

**Files:**
- Create: `onebot/src/main/kotlin/io/openeden/onebot/connection/OneBotSocket.kt`
- Create: `onebot/src/main/kotlin/io/openeden/onebot/connection/OneBotConnection.kt`
- Create: `onebot/src/main/kotlin/io/openeden/onebot/connection/OneBotConnectionRegistry.kt`
- Test: `onebot/src/test/kotlin/io/openeden/onebot/connection/OneBotConnectionRegistryTest.kt`

- [ ] **Step 1: Write connection replacement and stale-disconnect tests**

Use a fake socket that records `close` calls. Assert:

```kotlin
val first = registry.register("10001", firstSocket)
val second = registry.register("10001", secondSocket)
assertTrue(second.epoch > first.epoch)
assertEquals(1, firstSocket.closeCalls)
assertEquals(second, registry.snapshot())

registry.unregister(first.epoch)
assertEquals(second, registry.snapshot())

registry.unregister(second.epoch)
assertNull(registry.snapshot())
```

Also assert that `register("different", socket)` fails when expected self ID is `10001`.

- [ ] **Step 2: Run the test to verify the registry is missing**

Run:

```powershell
.\gradlew.bat :onebot:test --tests '*OneBotConnectionRegistryTest'
```

Expected: FAIL compiling missing connection types.

- [ ] **Step 3: Implement the socket and connection contracts**

```kotlin
// OneBotSocket.kt
interface OneBotSocket {
    suspend fun send(text: String)
    suspend fun close(reason: String)
}

// OneBotConnection.kt
import kotlinx.coroutines.sync.Mutex

class OneBotConnection internal constructor(
    val selfId: String,
    val epoch: Long,
    val socket: OneBotSocket,
    internal val sendMutex: Mutex = Mutex(),
)
```

- [ ] **Step 4: Implement epoch-safe registration**

Create `OneBotConnectionRegistry.kt`:

```kotlin
package io.openeden.onebot.connection

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class OneBotConnectionRegistry(private val expectedSelfId: String) {
    private val epochs = AtomicLong()
    private val mutation = Mutex()

    @Volatile
    private var active: OneBotConnection? = null

    fun snapshot(): OneBotConnection? = active

    fun isActive(epoch: Long): Boolean = active?.epoch == epoch

    suspend fun register(selfId: String, socket: OneBotSocket): OneBotConnection {
        require(selfId == expectedSelfId) { "Unexpected OneBot self ID" }
        var replaced: OneBotConnection? = null
        val connection = mutation.withLock {
            replaced = active
            OneBotConnection(selfId, epochs.incrementAndGet(), socket).also { active = it }
        }
        replaced?.let { runCatching { it.socket.close("replaced") } }
        return connection
    }

    suspend fun unregister(epoch: Long): Boolean = mutation.withLock {
        if (active?.epoch != epoch) return@withLock false
        active = null
        true
    }

    suspend fun close() {
        val connection = mutation.withLock {
            active.also { active = null }
        }
        connection?.socket?.close("server shutdown")
    }
}
```

- [ ] **Step 5: Run and commit the lifecycle boundary**

Run:

```powershell
.\gradlew.bat :onebot:test --tests '*OneBotConnectionRegistryTest'
git add onebot
git commit -m "feat(onebot): track active connection epoch"
```

Expected: focused tests pass.

### Task 4: Send correlated OneBot actions without reconnect replay

**Files:**
- Create: `onebot/src/main/kotlin/io/openeden/onebot/egress/OneBotActionException.kt`
- Create: `onebot/src/main/kotlin/io/openeden/onebot/egress/OneBotActionSender.kt`
- Test: `onebot/src/test/kotlin/io/openeden/onebot/egress/OneBotActionSenderTest.kt`

- [ ] **Step 1: Write action encoding, correlation, timeout, and epoch tests**

Test `sendPrivate` and `sendGroup` using a fake socket. Decode the sent JSON and assert:

```kotlin
assertEquals("send_private_msg", action["action"]!!.jsonPrimitive.content)
assertEquals("22", action["params"]!!.jsonObject["user_id"]!!.jsonPrimitive.content)
assertEquals("hello", action["params"]!!.jsonObject["message"]!!.jsonArray.single()
    .jsonObject["data"]!!.jsonObject["text"]!!.jsonPrimitive.content)
```

Complete the captured echo with `OneBotActionResponse("ok", 0, echo)` and assert the send completes. Add tests asserting non-zero `retcode`, timeout, and a changed registry epoch fail without sending on the replacement socket.

- [ ] **Step 2: Run the focused test to verify sender types are missing**

Run:

```powershell
.\gradlew.bat :onebot:test --tests '*OneBotActionSenderTest'
```

Expected: FAIL compiling missing sender types.

- [ ] **Step 3: Implement action failure categories**

Create `OneBotActionException.kt`:

```kotlin
package io.openeden.onebot.egress

class OneBotActionException(
    val category: Category,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    enum class Category { DISCONNECTED, TIMEOUT, REJECTED, TRANSPORT }
}
```

- [ ] **Step 4: Implement correlated action sending**

Create `OneBotActionSender.kt`:

```kotlin
package io.openeden.onebot.egress

import io.openeden.onebot.connection.OneBotConnection
import io.openeden.onebot.connection.OneBotConnectionRegistry
import io.openeden.onebot.protocol.OneBotActionResponse
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class OneBotActionSender(
    private val registry: OneBotConnectionRegistry,
    private val timeoutMs: Long,
    private val maxRetries: Int,
    private val retryDelay: suspend (Int) -> Unit = { attempt -> delay(250L * (attempt + 1)) },
) {
    private val echoes = AtomicLong()
    private val pending = ConcurrentHashMap<String, PendingAction>()

    suspend fun sendPrivate(userId: String, text: String, requiredEpoch: Long? = null) =
        send("send_private_msg", "user_id", userId, text, requiredEpoch)

    suspend fun sendGroup(groupId: String, text: String, requiredEpoch: Long? = null) =
        send("send_group_msg", "group_id", groupId, text, requiredEpoch)

    fun complete(response: OneBotActionResponse, epoch: Long): Boolean {
        val action = pending[response.echo] ?: return false
        if (action.epoch != epoch) return false
        return action.result.complete(response)
    }

    fun failEpoch(epoch: Long, cause: Throwable) {
        pending.entries.forEach { (echo, action) ->
            if (action.epoch == epoch && pending.remove(echo, action)) {
                action.result.completeExceptionally(cause)
            }
        }
    }

    private suspend fun send(
        action: String,
        idKey: String,
        id: String,
        text: String,
        requiredEpoch: Long?,
    ) {
        val connection = registry.snapshot() ?: disconnected()
        if (requiredEpoch != null && connection.epoch != requiredEpoch) disconnected()
        var lastFailure: OneBotActionException? = null
        repeat(maxRetries + 1) { attempt ->
            try {
                sendOnce(connection, action, idKey, id, text)
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: OneBotActionException) {
                lastFailure = failure
                val retryable = failure.category == OneBotActionException.Category.TIMEOUT ||
                    failure.category == OneBotActionException.Category.TRANSPORT
                if (!retryable || attempt == maxRetries) throw failure
                if (!registry.isActive(connection.epoch)) disconnected()
                retryDelay(attempt)
            }
        }
        throw checkNotNull(lastFailure)
    }

    private suspend fun sendOnce(
        connection: OneBotConnection,
        action: String,
        idKey: String,
        id: String,
        text: String,
    ) {
        if (!registry.isActive(connection.epoch)) disconnected()
        val numericId = id.toLongOrNull() ?: throw OneBotActionException(
            OneBotActionException.Category.REJECTED,
            "OneBot target ID must be numeric",
        )
        val echo = "openeden_${connection.epoch}_${echoes.incrementAndGet()}"
        val waiting = PendingAction(connection.epoch, CompletableDeferred())
        pending[echo] = waiting
        try {
            val payload = buildJsonObject {
                put("action", action)
                put("params", buildJsonObject {
                    put(idKey, numericId)
                    put("message", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "text")
                            put("data", buildJsonObject { put("text", text) })
                        })
                    })
                })
                put("echo", echo)
            }.toString()
            try {
                connection.sendMutex.withLock {
                    if (!registry.isActive(connection.epoch)) disconnected()
                    connection.socket.send(payload)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: OneBotActionException) {
                throw failure
            } catch (failure: Throwable) {
                throw OneBotActionException(
                    OneBotActionException.Category.TRANSPORT,
                    "OneBot WebSocket send failed",
                    failure,
                )
            }
            val response = try {
                withTimeout(timeoutMs) { waiting.result.await() }
            } catch (timeout: TimeoutCancellationException) {
                throw OneBotActionException(
                    OneBotActionException.Category.TIMEOUT,
                    "OneBot action timed out",
                    timeout,
                )
            }
            if (response.status != "ok" || response.retCode != 0) {
                throw OneBotActionException(
                    OneBotActionException.Category.REJECTED,
                    "OneBot action was rejected with retcode ${response.retCode}",
                )
            }
        } finally {
            pending.remove(echo, waiting)
        }
    }

    private fun disconnected(): Nothing = throw OneBotActionException(
        OneBotActionException.Category.DISCONNECTED,
        "OneBot is disconnected",
    )

    private data class PendingAction(
        val epoch: Long,
        val result: CompletableDeferred<OneBotActionResponse>,
    )
}
```

- [ ] **Step 5: Run and commit action transport**

Run:

```powershell
.\gradlew.bat :onebot:test --tests '*OneBotActionSenderTest'
git add onebot
git commit -m "feat(onebot): send correlated message actions"
```

Expected: all sender tests pass and no test observes replay on a replacement epoch.

### Task 5: Process events through a bounded adapter and implement heartbeat delivery

**Files:**
- Create: `onebot/src/main/kotlin/io/openeden/onebot/ingress/OneBotMessageResult.kt`
- Create: `onebot/src/main/kotlin/io/openeden/onebot/ingress/OneBotMessageHandler.kt`
- Create: `onebot/src/main/kotlin/io/openeden/onebot/ingress/OneBotAdapter.kt`
- Create: `onebot/src/main/kotlin/io/openeden/onebot/heartbeat/OneBotHeartbeatDelivery.kt`
- Test: `onebot/src/test/kotlin/io/openeden/onebot/ingress/OneBotAdapterTest.kt`
- Test: `onebot/src/test/kotlin/io/openeden/onebot/heartbeat/OneBotHeartbeatDeliveryTest.kt`

- [ ] **Step 1: Write adapter routing tests**

Use a fake `OneBotMessageHandler` to record requests and return `OneBotMessageResult("reply")`. Submit a private and group frame with their active connection epoch. Assert requests match mapper output and actions return to the original target. Add tests that blank results emit no action and that a full queue invokes `onTrace("onebot=QUEUE_OVERFLOW")`.

- [ ] **Step 2: Write owner-only heartbeat tests**

Assert:

```kotlin
assertFalse(delivery.isConnected(HeartbeatTarget("WEB", "22")))
assertTrue(delivery.isConnected(HeartbeatTarget("QQ", "22")))
delivery.deliver("QQ:22", HeartbeatTarget("QQ", "22"), shock = false, response = "ping")
```

Verify exactly one `send_private_msg` action is emitted. After unregistering the epoch, assert `isConnected` is false and `deliver` throws `DISCONNECTED` without queueing.

- [ ] **Step 3: Run tests to verify adapter boundaries are missing**

Run:

```powershell
.\gradlew.bat :onebot:test --tests '*OneBotAdapterTest' --tests '*OneBotHeartbeatDeliveryTest'
```

Expected: FAIL compiling missing handler, adapter, and heartbeat types.

- [ ] **Step 4: Implement the narrow message boundary**

```kotlin
// OneBotMessageResult.kt
data class OneBotMessageResult(val response: String?)

// OneBotMessageHandler.kt
fun interface OneBotMessageHandler {
    suspend fun handle(request: DevelopmentMessageRequest): OneBotMessageResult
}
```

- [ ] **Step 5: Implement bounded event processing**

Create `OneBotAdapter.kt`:

```kotlin
package io.openeden.onebot.ingress

import io.openeden.onebot.config.OneBotConfig
import io.openeden.onebot.connection.OneBotConnectionRegistry
import io.openeden.onebot.egress.OneBotActionException
import io.openeden.onebot.egress.OneBotActionSender
import io.openeden.onebot.protocol.OneBotEventParser
import io.openeden.onebot.protocol.OneBotInbound
import io.openeden.onebot.protocol.OneBotMessageEvent
import io.openeden.onebot.protocol.OneBotReplyTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

class OneBotAdapter(
    val config: OneBotConfig,
    val registry: OneBotConnectionRegistry,
    val actions: OneBotActionSender,
    private val handler: OneBotMessageHandler,
    scope: CoroutineScope,
    private val onTrace: (String) -> Unit = {},
) {
    private val parser = OneBotEventParser()
    private val workerJob = SupervisorJob(scope.coroutineContext[Job])
    private val workerScope = CoroutineScope(scope.coroutineContext + workerJob)
    private val queue = Channel<QueuedEvent>(config.eventQueueCapacity)
    private val workers = List(config.eventWorkers) {
        workerScope.launch {
            for (queued in queue) process(queued)
        }
    }

    fun onText(raw: String, epoch: Long) {
        when (val inbound = parser.parse(raw, config)) {
            is OneBotInbound.Action -> actions.complete(inbound.response, epoch)
            is OneBotInbound.Message -> if (!queue.trySend(QueuedEvent(inbound.event, epoch)).isSuccess) {
                trace("onebot=QUEUE_OVERFLOW")
            }
            is OneBotInbound.Ignored -> trace(
                if (inbound.reason == "malformed") "onebot=MALFORMED_EVENT"
                else "onebot=EVENT_IGNORED reason=${inbound.reason}",
            )
        }
    }

    fun trace(tag: String) = onTrace(tag)

    suspend fun shutdown() {
        queue.close()
        workers.forEach { it.cancel() }
        workerJob.cancelAndJoin()
        registry.snapshot()?.let { connection ->
            actions.failEpoch(connection.epoch, IllegalStateException("OneBot adapter stopped"))
        }
        registry.close()
    }

    private suspend fun process(queued: QueuedEvent) {
        try {
            val response = handler.handle(OneBotRequestMapper.map(queued.event)).response
                ?.takeIf(String::isNotBlank)
                ?: return
            when (val target = queued.event.target) {
                is OneBotReplyTarget.Private -> actions.sendPrivate(
                    target.userId, response, queued.epoch,
                )
                is OneBotReplyTarget.Group -> actions.sendGroup(
                    target.groupId, response, queued.epoch,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: OneBotActionException) {
            trace(
                if (failure.category == OneBotActionException.Category.TIMEOUT) "onebot=ACTION_TIMEOUT"
                else "onebot=ACTION_FAILED category=${failure.category.name}",
            )
        } catch (_: Throwable) {
            trace("onebot=ACTION_FAILED category=UNKNOWN")
        }
    }

    private data class QueuedEvent(val event: OneBotMessageEvent, val epoch: Long)
}
```

- [ ] **Step 6: Implement immediate owner delivery**

Create `OneBotHeartbeatDelivery.kt`:

```kotlin
package io.openeden.onebot.heartbeat

import io.openeden.onebot.connection.OneBotConnectionRegistry
import io.openeden.onebot.egress.OneBotActionSender
import io.openeden.onebot.egress.OneBotActionException
import io.openeden.runtime.heartbeat.HeartbeatDelivery
import io.openeden.runtime.heartbeat.HeartbeatTarget

class OneBotHeartbeatDelivery(
    private val registry: OneBotConnectionRegistry,
    private val actions: OneBotActionSender,
) : HeartbeatDelivery {
    override fun isConnected(target: HeartbeatTarget): Boolean =
        target.platform == QQ_PLATFORM && registry.snapshot() != null

    override suspend fun deliver(
        sessionId: String,
        target: HeartbeatTarget,
        shock: Boolean,
        response: String?,
    ) {
        val text = response?.takeIf(String::isNotBlank) ?: return
        if (target.platform != QQ_PLATFORM) throw OneBotActionException(
            OneBotActionException.Category.REJECTED,
            "OneBot heartbeat target must use QQ platform",
        )
        val epoch = registry.snapshot()?.epoch ?: throw OneBotActionException(
            OneBotActionException.Category.DISCONNECTED,
            "OneBot is disconnected",
        )
        actions.sendPrivate(target.userId, text, requiredEpoch = epoch)
    }

    private companion object { const val QQ_PLATFORM = "QQ" }
}
```

- [ ] **Step 7: Run and commit adapter processing**

Run:

```powershell
.\gradlew.bat :onebot:test --tests '*OneBotAdapterTest' --tests '*OneBotHeartbeatDeliveryTest'
git add onebot
git commit -m "feat(onebot): route turns and owner heartbeats"
```

Expected: focused tests pass.

### Task 6: Install the authenticated reverse WebSocket route

**Files:**
- Create: `onebot/src/main/kotlin/io/openeden/onebot/route/KtorOneBotSocket.kt`
- Create: `onebot/src/main/kotlin/io/openeden/onebot/route/OneBotReverseWebSocketRoute.kt`
- Test: `onebot/src/test/kotlin/io/openeden/onebot/route/OneBotReverseWebSocketRouteTest.kt`

- [ ] **Step 1: Write Ktor route tests**

Using `testApplication`, install `WebSockets`, create an adapter with a fake handler, and install the route. Verify:

- wrong bearer token closes with `VIOLATED_POLICY` and registers no connection;
- mismatched `X-Self-ID` closes and registers no connection;
- valid headers register a connection;
- sending a private message frame produces `send_private_msg` on the same socket;
- disconnect clears the matching epoch;
- reconnect does not receive an action created for the old epoch.

Client setup:

```kotlin
val client = createClient { install(io.ktor.client.plugins.websocket.WebSockets) }
client.webSocket(
    request = {
        url("/onebot/v11")
        header(HttpHeaders.Authorization, "Bearer secret")
        header("X-Self-ID", "10001")
    },
) {
    send(Frame.Text(privateMessageJson))
    val action = (incoming.receive() as Frame.Text).readText()
    assertTrue(action.contains("send_private_msg"))
}
```

- [ ] **Step 2: Run route tests to verify route types are missing**

Run:

```powershell
.\gradlew.bat :onebot:test --tests '*OneBotReverseWebSocketRouteTest'
```

Expected: FAIL compiling missing route installation.

- [ ] **Step 3: Implement the Ktor socket wrapper**

Create `KtorOneBotSocket.kt`:

```kotlin
package io.openeden.onebot.route

import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.send
import io.openeden.onebot.connection.OneBotSocket

class KtorOneBotSocket(
    private val session: DefaultWebSocketServerSession,
) : OneBotSocket {
    override suspend fun send(text: String) {
        session.send(Frame.Text(text))
    }

    override suspend fun close(reason: String) {
        session.close(CloseReason(CloseReason.Codes.NORMAL, reason.take(120)))
    }
}
```

- [ ] **Step 4: Implement authentication and socket lifecycle**

Create `OneBotReverseWebSocketRoute.kt`:

```kotlin
package io.openeden.onebot.route

import io.ktor.http.HttpHeaders
import io.ktor.server.request.header
import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.openeden.onebot.connection.OneBotConnection
import io.openeden.onebot.ingress.OneBotAdapter
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

fun Route.oneBotReverseWebSocket(adapter: OneBotAdapter) {
    webSocket(adapter.config.path) {
        val supplied = call.request.header(HttpHeaders.Authorization)
            ?.removePrefix("Bearer ")
            .orEmpty()
        if (!secureEquals(adapter.config.accessToken, supplied)) {
            adapter.trace("onebot=AUTH_REJECTED")
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
            return@webSocket
        }
        val selfId = call.request.header("X-Self-ID").orEmpty()
        if (selfId != adapter.config.botSelfId) {
            adapter.trace("onebot=AUTH_REJECTED")
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "self id mismatch"))
            return@webSocket
        }

        var connection: OneBotConnection? = null
        try {
            val registered = adapter.registry.register(selfId, KtorOneBotSocket(this))
            connection = registered
            adapter.trace("onebot=CONNECTED self_id=$selfId epoch=${registered.epoch}")
            for (frame in incoming) {
                if (frame is Frame.Text) {
                    adapter.onText(frame.readText(), registered.epoch)
                } else {
                    adapter.trace("onebot=EVENT_IGNORED reason=binary_frame")
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            connection?.let {
                adapter.actions.failEpoch(it.epoch, IllegalStateException("OneBot disconnected"))
                adapter.registry.unregister(it.epoch)
                adapter.trace("onebot=DISCONNECTED self_id=${it.selfId} epoch=${it.epoch}")
            }
        }
    }
}

private fun secureEquals(expected: String, supplied: String): Boolean = MessageDigest.isEqual(
    expected.encodeToByteArray(),
    supplied.encodeToByteArray(),
)
```

Binary frames are ignored. Never log authorization headers or frame content.

- [ ] **Step 5: Run all OneBot tests and commit the route**

Run:

```powershell
.\gradlew.bat :onebot:test
git add onebot
git commit -m "feat(onebot): accept reverse websocket connections"
```

Expected: BUILD SUCCESSFUL for `:onebot:test`.

### Task 7: Load OneBot server configuration

**Files:**
- Create: `server/src/main/kotlin/io/openeden/server/bootstrap/OneBotConfigLoader.kt`
- Test: `server/src/test/kotlin/io/openeden/server/bootstrap/OneBotConfigLoaderTest.kt`
- Modify: `server/src/main/resources/application.yaml`

- [ ] **Step 1: Write Ktor application config tests**

Use `MapApplicationConfig` to assert defaults disable OneBot and explicit values parse exactly. Add failure tests for enabled-without-token, enabled-without-self-ID, invalid group policy, timeout, queue capacity, worker count, and retry count.

Representative assertion:

```kotlin
val config = loadOneBotConfig(MapApplicationConfig(
    "openeden.onebot.enabled" to "true",
    "openeden.onebot.path" to "/qq",
    "openeden.onebot.accessToken" to "secret",
    "openeden.onebot.botSelfId" to "10001",
    "openeden.onebot.groupPolicy" to "ALL",
))
assertEquals("/qq", config.path)
assertEquals(OneBotGroupPolicy.ALL, config.groupPolicy)
```

- [ ] **Step 2: Run the focused server test to verify loader is missing**

Run:

```powershell
.\gradlew.bat :server:test --tests '*OneBotConfigLoaderTest'
```

Expected: FAIL compiling `loadOneBotConfig`.

- [ ] **Step 3: Implement strict configuration loading**

Create `OneBotConfigLoader.kt` with one internal function:

```kotlin
internal fun loadOneBotConfig(config: ApplicationConfig): OneBotConfig = OneBotConfig(
    enabled = config.propertyOrNull("openeden.onebot.enabled")?.getString()
        ?.equals("true", ignoreCase = true) == true,
    path = config.propertyOrNull("openeden.onebot.path")?.getString()?.ifBlank { null } ?: "/onebot/v11",
    accessToken = config.propertyOrNull("openeden.onebot.accessToken")?.getString().orEmpty(),
    botSelfId = config.propertyOrNull("openeden.onebot.botSelfId")?.getString().orEmpty(),
    groupPolicy = OneBotGroupPolicy.valueOf(
        config.propertyOrNull("openeden.onebot.groupPolicy")?.getString()?.uppercase() ?: "MENTION_ONLY",
    ),
    eventQueueCapacity = config.propertyOrNull("openeden.onebot.eventQueueCapacity")?.getString()?.toInt() ?: 64,
    eventWorkers = config.propertyOrNull("openeden.onebot.eventWorkers")?.getString()?.toInt() ?: 4,
    actionTimeoutMs = config.propertyOrNull("openeden.onebot.actionTimeoutMs")?.getString()?.toLong() ?: 10_000L,
    maxActionRetries = config.propertyOrNull("openeden.onebot.maxActionRetries")?.getString()?.toInt() ?: 2,
)
```

Wrap enum and numeric parsing failures with `IllegalArgumentException` messages naming the invalid property.

- [ ] **Step 4: Add environment-backed YAML properties**

Add under `openeden` in `server/src/main/resources/application.yaml`:

```yaml
  onebot:
    enabled: "$OPENEDEN_ONEBOT_ENABLED:false"
    path: "$OPENEDEN_ONEBOT_PATH:/onebot/v11"
    accessToken: "$?OPENEDEN_ONEBOT_ACCESS_TOKEN:"
    botSelfId: "$?OPENEDEN_ONEBOT_SELF_ID:"
    groupPolicy: "$OPENEDEN_ONEBOT_GROUP_POLICY:MENTION_ONLY"
    eventQueueCapacity: "$OPENEDEN_ONEBOT_EVENT_QUEUE_CAPACITY:64"
    eventWorkers: "$OPENEDEN_ONEBOT_EVENT_WORKERS:4"
    actionTimeoutMs: "$OPENEDEN_ONEBOT_ACTION_TIMEOUT_MS:10000"
    maxActionRetries: "$OPENEDEN_ONEBOT_MAX_ACTION_RETRIES:2"
```

- [ ] **Step 5: Run and commit configuration**

Run:

```powershell
.\gradlew.bat :server:test --tests '*OneBotConfigLoaderTest'
git add server
git commit -m "feat(server): load OneBot adapter configuration"
```

Expected: focused tests pass.

### Task 8: Wire one adapter into server runtime and heartbeat delivery

**Files:**
- Modify: `server/build.gradle.kts`
- Create: `server/src/main/kotlin/io/openeden/server/bootstrap/OneBotAdapterKey.kt`
- Create: `server/src/main/kotlin/io/openeden/server/adapter/onebot/OneBotModule.kt`
- Modify: `server/src/main/kotlin/io/openeden/server/bootstrap/Runtime.kt`
- Modify: `server/src/main/resources/application.yaml`
- Test: `server/src/test/kotlin/io/openeden/server/adapter/onebot/OneBotModuleTest.kt`

- [ ] **Step 1: Add a server module test for disabled and enabled route installation**

Configure a lightweight application attribute with a fake adapter. Assert the route is absent when OneBot is disabled and accepts a valid socket when enabled. The test must not start models, SQLite, or the LLM runtime.

- [ ] **Step 2: Add the module dependency and verify wiring test fails**

Add to `server/build.gradle.kts`:

```kotlin
implementation(project(":onebot"))
```

Run:

```powershell
.\gradlew.bat :server:test --tests '*OneBotModuleTest'
```

Expected: FAIL because the adapter attribute and server module do not exist.

- [ ] **Step 3: Add the focused application attribute**

Create `OneBotAdapterKey.kt`:

```kotlin
package io.openeden.server.bootstrap

import io.ktor.util.AttributeKey
import io.openeden.onebot.ingress.OneBotAdapter

val OneBotAdapterKey = AttributeKey<OneBotAdapter>("openeden.onebot-adapter")
```

- [ ] **Step 4: Install the adapter route from a server module**

Create `OneBotModule.kt`:

```kotlin
package io.openeden.server.adapter.onebot

import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import io.openeden.onebot.route.oneBotReverseWebSocket
import io.openeden.server.bootstrap.OneBotAdapterKey

fun Application.configureOneBot() {
    attributes.getOrNull(OneBotAdapterKey)?.let { adapter ->
        routing { oneBotReverseWebSocket(adapter) }
    }
}
```

Register this module after `configureRuntime` and before general routing in `application.yaml`:

```yaml
- io.openeden.server.bootstrap.RuntimeKt.configureRuntime
- io.openeden.server.adapter.onebot.OneBotModuleKt.configureOneBot
- io.openeden.server.api.route.RoutingKt.configureRouting
```

- [ ] **Step 5: Construct the adapter from the production pipeline**

In `Runtime.kt`, add `oneBotConfig` to `ServerRuntimeConfig` and load it with `loadOneBotConfig`. After the application runtime scope and pipeline exist, construct the adapter only when enabled:

```kotlin
val oneBotAdapter = serverConfig.oneBot.takeIf { it.enabled }?.let { config ->
    val registry = OneBotConnectionRegistry(config.botSelfId)
    val actions = OneBotActionSender(
        registry = registry,
        timeoutMs = config.actionTimeoutMs,
        maxRetries = config.maxActionRetries,
    )
    OneBotAdapter(
        config = config,
        registry = registry,
        actions = actions,
        handler = OneBotMessageHandler { request ->
            val result = pipeline.handle(request)
            OneBotMessageResult(result.response.takeIf { result.validationErrors.isEmpty() })
        },
        scope = scope,
        onTrace = log::info,
    ).also { attributes.put(OneBotAdapterKey, it) }
}
```

Use:

```kotlin
delivery = oneBotAdapter?.let { OneBotHeartbeatDelivery(it.registry, it.actions) }
    ?: NoopHeartbeatDelivery
```

Pass the same delivery to the existing `HeartbeatScheduler`. Add adapter shutdown to startup cleanup and `RuntimeShutdownCoordinator`; cancel workers and close the active socket without blocking an application monitor callback.

Change the scheduler's production `onDeliveryDropped` callback to log `onebot=HEARTBEAT_DROPPED` with session ID, target platform, target user ID, and failure category, but never message text or credentials.

- [ ] **Step 6: Enforce QQ heartbeat-owner consistency**

During server config validation, when OneBot is enabled and heartbeat owner is configured, require `owner.platform == "QQ"`. Preserve the existing rule that owner platform and user ID must either both be present or both be absent.

- [ ] **Step 7: Run server and adapter tests**

Run:

```powershell
.\gradlew.bat :onebot:test :server:test
```

Expected: BUILD SUCCESSFUL. Existing server routes and scheduler tests remain green.

- [ ] **Step 8: Commit runtime wiring**

Run:

```powershell
git add server onebot
git commit -m "feat(server): host NapCat OneBot adapter"
```

Expected title: `feat(server): host NapCat OneBot adapter`.

### Task 9: Document NapCat setup and run the end-to-end verification gate

**Files:**
- Modify: `README.md`
- Modify: `README.zh-CN.md`
- Test: all affected Gradle modules

- [ ] **Step 1: Add NapCat reverse WebSocket instructions**

Document that NapCat must create a **WebSocket client** connection, not a WebSocket server. Include:

```text
Local URL:  ws://127.0.0.1:8080/onebot/v11
Docker URL: ws://openeden-server:8080/onebot/v11
Public URL: wss://eden.example.com/onebot/v11
Token:      same value as OPENEDEN_ONEBOT_ACCESS_TOKEN
Self ID:    QQ bot ID in OPENEDEN_ONEBOT_SELF_ID
```

Document that OpenEden must start first, NapCat reconnects, one OpenEden instance accepts one configured QQ bot, and independent bots require independent server/database instances.

- [ ] **Step 2: Add a manual NapCat smoke checklist**

Include these exact checks:

```text
1. Start OpenEden with OneBot enabled and valid token/self ID.
2. Enable NapCat's OneBot v11 WebSocket client pointing at OpenEden.
3. Confirm onebot=CONNECTED without logging the token.
4. Send a QQ private text message and confirm one reply.
5. Mention the bot in a QQ group and confirm the group shares QQ:<group_id>.
6. Disconnect NapCat, wait for or trigger a heartbeat, and confirm no stale message appears after reconnect.
7. Reconnect NapCat and confirm new messages work normally.
```

- [ ] **Step 3: Run focused and aggregate tests**

Run:

```powershell
.\gradlew.bat :client:allTests :cli:test :core:allTests :onebot:test :server:test :trainer:test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Check forbidden coupling and non-blocking constraints**

Run:

```powershell
rg -n "Thread\.sleep|runBlocking|java\.util\.concurrent\.BlockingQueue" onebot server/src/main/kotlin/io/openeden/server/adapter
rg -n "Persona|PromptBuilder|VqVae|BioVector|VectorDelta|RetrievalMode" onebot/src/main
git diff --check
```

Expected: no blocking primitives; no persona/vector/retrieval logic in OneBot; no whitespace errors. Imports of `DevelopmentMessageRequest` and `HeartbeatDelivery` are expected core boundaries.

- [ ] **Step 5: Verify Conventional Commits and final repository state**

Run:

```powershell
git status --short
git log -8 --format=%s
```

Expected: only intended documentation changes remain before the final commit; every implementation commit follows the repository convention.

- [ ] **Step 6: Commit operator documentation**

Run:

```powershell
git add README.md README.zh-CN.md
git commit -m "docs(onebot): document NapCat reverse websocket setup"
git log -1 --format=%s
```

Expected title: `docs(onebot): document NapCat reverse websocket setup`.

### Task 10: Perform architecture compliance review

**Files:**
- Verify: `core`, `onebot`, `server`, `client`, `cli`, persona data, and test reports

- [ ] **Step 1: Confirm Persona-as-Data**

Verify OneBot contains only connection, routing, filtering, and protocol policy. It must not contain tone, catchphrases, emotional response rules, or persona examples.

- [ ] **Step 2: Confirm the VQ-VAE path is unchanged**

Trace one QQ event to `DevelopmentMessagePipeline.handle` and verify quantization, heuristic fallback tagging, prompt validation, memory writes, and vector write-back are unchanged and cannot be bypassed by the adapter.

- [ ] **Step 3: Confirm non-blocking execution**

Verify all socket I/O, queue workers, action waits, retries, pipeline calls, and shutdown use coroutines. Confirm adapter code performs no inference or coordinate mapping and therefore does not escape `InferenceDispatcher` ownership.

- [ ] **Step 4: Confirm session and heartbeat invariants**

Verify:

```text
private session = QQ:<user_id>
group session = QQ:<group_id>
memory sender metadata = event user_id
one runtime accepts one configured self_id
heartbeat target platform must be QQ
heartbeat sends only to configured owner user_id
disconnect/reconnect does not replay queued output
```

- [ ] **Step 5: Record final verification evidence**

Run:

```powershell
git status --short
git log -1 --format=%s
```

Expected: clean worktree and a valid Conventional Commit title after all plan tasks are complete.
