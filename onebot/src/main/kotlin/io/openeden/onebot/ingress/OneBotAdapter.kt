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
