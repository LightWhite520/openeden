package io.openeden.server.vector

import io.openeden.memory.MemoryEntry
import io.openeden.memory.VectorIndex
import io.openeden.server.persistence.sqldelight.MemoryVectorProjectionStore
import io.openeden.server.vector.qdrant.QdrantClientException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlin.random.Random

interface ProjectionWorkStore {
    suspend fun recoverRunning(nowMs: Long)
    suspend fun claimDue(nowMs: Long, batchSize: Int, activeModelId: String = ""): List<MemoryVectorProjectionStore.ProjectionWork>
    suspend fun markReady(memoryIds: Collection<String>, nowMs: Long)
    suspend fun reschedule(memoryId: String, nowMs: Long, error: String?)
    suspend fun resetReady(modelId: String, nowMs: Long, batchSize: Int): List<String> = emptyList()
    suspend fun rescheduleWithJitter(memoryId: String, nowMs: Long, error: String?, jitterMs: Long, baseDelayMs: Long = 1_000L) =
        reschedule(memoryId, nowMs, error)
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class QdrantProjectionSynchronizer(
    private val store: ProjectionWorkStore,
    private val loadEntry: suspend (String) -> MemoryEntry?,
    private val project: suspend (List<MemoryEntry>) -> Unit,
    private val modelId: String,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val intervalMs: Long = 30_000L,
    private val batchSize: Int = 128,
    private val circuit: QdrantCircuitBreaker? = null,
    private val onCollectionLoss: (suspend () -> Unit)? = null,
    private val retryJitterMs: (MemoryVectorProjectionStore.ProjectionWork) -> Long = {
        Random.nextLong(0L, 501L)
    },
) {
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var child: Job? = null

    constructor(
        store: ProjectionWorkStore,
        index: VectorIndex,
        loadEntry: suspend (String) -> MemoryEntry?,
        modelId: String,
        nowMs: () -> Long = { System.currentTimeMillis() },
        intervalMs: Long = 30_000L,
        batchSize: Int = 128,
        circuit: QdrantCircuitBreaker? = null,
        retryJitterMs: (MemoryVectorProjectionStore.ProjectionWork) -> Long = { Random.nextLong(0L, 501L) },
        onCollectionLoss: (suspend () -> Unit)? = null,
    ) : this(
        store = store,
        loadEntry = loadEntry,
        project = { entries -> entries.forEach { index.insert(it) } },
        modelId = modelId,
        nowMs = nowMs,
        intervalMs = intervalMs,
        batchSize = batchSize,
        circuit = circuit,
        onCollectionLoss = onCollectionLoss,
        retryJitterMs = retryJitterMs,
    )

    init {
        require(modelId.isNotBlank()) { "modelId must not be blank" }
        require(intervalMs > 0) { "intervalMs must be positive" }
        require(batchSize > 0) { "batchSize must be positive" }
    }

    fun start(scope: CoroutineScope): Job {
        child?.let { if (it.isActive) return it }
        return scope.launch {
            store.recoverRunning(nowMs())
            while (isActive) {
                select<Unit> {
                    wake.onReceive { }
                    onTimeout(intervalMs) { }
                }
                while (isActive && drainOnce() == batchSize) Unit
            }
        }.also { child = it }
    }

    fun signal() { wake.trySend(Unit) }

    suspend fun drainOnce(now: Long = nowMs()): Int {
        val claimed = store.claimDue(now, batchSize, modelId)
        if (claimed.isEmpty()) return 0
        val loaded = ArrayList<MemoryEntry>(claimed.size)
        val missing = ArrayList<MemoryVectorProjectionStore.ProjectionWork>()
        try {
            claimed.forEach { work ->
                val entry = loadEntry(work.memoryId)
                if (entry == null) missing += work else loaded += entry
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            rescheduleAll(claimed, nowMs(), failure)
            return claimed.size
        }

        try {
            if (loaded.isNotEmpty()) {
                if (circuit != null) {
                    circuit.execute {
                        project(loaded)
                        true
                    } ?: error("Qdrant circuit is open")
                } else {
                    project(loaded)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (failure is QdrantClientException && failure.statusCode == 404) {
                handleCollectionLoss()
            }
            rescheduleAll(claimed, nowMs(), failure)
            return claimed.size
        }

        val completedAt = nowMs()
        missing.forEach { work ->
            store.rescheduleWithJitter(
                work.memoryId,
                completedAt,
                "memory source missing",
                retryJitterMs(work).coerceAtLeast(0L),
                intervalMs,
            )
        }
        if (loaded.isNotEmpty()) store.markReady(loaded.map { it.id }, completedAt)
        return claimed.size
    }

    private suspend fun rescheduleAll(
        claimed: List<MemoryVectorProjectionStore.ProjectionWork>,
        atMs: Long,
        failure: Throwable,
    ) {
        claimed.forEach { work ->
            store.rescheduleWithJitter(
                work.memoryId,
                atMs,
                failure.message ?: failure::class.simpleName,
                retryJitterMs(work).coerceAtLeast(0L),
                intervalMs,
            )
        }
    }

    private suspend fun handleCollectionLoss() {
        try {
            store.resetReady(modelId, nowMs(), batchSize)
            onCollectionLoss?.invoke()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Projection retry remains durable even when the rebuild hint cannot be persisted.
        }
    }

    suspend fun stop() {
        child?.cancel()
        child?.join()
        child = null
    }
}

fun MemoryVectorProjectionStore.asProjectionWorkStore(): ProjectionWorkStore = object : ProjectionWorkStore {
    override suspend fun recoverRunning(nowMs: Long) = this@asProjectionWorkStore.recoverRunning(nowMs)
    override suspend fun claimDue(nowMs: Long, batchSize: Int, activeModelId: String) = this@asProjectionWorkStore.claimDue(nowMs, batchSize, activeModelId)
    override suspend fun markReady(memoryIds: Collection<String>, nowMs: Long) = this@asProjectionWorkStore.markReady(memoryIds, nowMs)
    override suspend fun reschedule(memoryId: String, nowMs: Long, error: String?) = this@asProjectionWorkStore.reschedule(memoryId, nowMs, error)
    override suspend fun resetReady(modelId: String, nowMs: Long, batchSize: Int) = this@asProjectionWorkStore.requeueReady(modelId, nowMs, batchSize)
}
