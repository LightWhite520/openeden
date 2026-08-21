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
