package io.openeden.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class OpenAiCapabilityCache(
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val capabilities = mutableMapOf<OpenAiCapabilityCacheKey, OpenAiProviderCapabilities>()
    private val inFlight = mutableMapOf<OpenAiCapabilityCacheKey, CompletableDeferred<OpenAiProviderCapabilities>>()

    suspend fun get(key: OpenAiCapabilityCacheKey): OpenAiProviderCapabilities? = mutex.withLock {
        capabilities[key]?.takeIf { it.expiresAtMs > nowMs() }
    }

    suspend fun getOrProbe(
        key: OpenAiCapabilityCacheKey,
        probe: suspend () -> OpenAiProviderCapabilities,
    ): OpenAiProviderCapabilities {
        val (deferred, isLeader) = mutex.withLock {
            capabilities[key]?.takeIf { it.expiresAtMs > nowMs() }?.let { return it }
            inFlight[key]?.let { return@withLock it to false }
            CompletableDeferred<OpenAiProviderCapabilities>().also { inFlight[key] = it } to true
        }
        if (!isLeader) return deferred.await()

        return try {
            val result = probe()
            finalizeProbe(key, deferred, result = result, failure = null, cacheResult = true)
            result
        } catch (cancelled: CancellationException) {
            val fallback = OpenAiProviderCapabilities.unavailable(nowMs())
            finalizeProbe(key, deferred, result = fallback, failure = null, cacheResult = false)
            throw cancelled
        } catch (failure: Throwable) {
            finalizeProbe(key, deferred, result = null, failure = failure, cacheResult = false)
            throw failure
        }
    }

    private suspend fun finalizeProbe(
        key: OpenAiCapabilityCacheKey,
        deferred: CompletableDeferred<OpenAiProviderCapabilities>,
        result: OpenAiProviderCapabilities?,
        failure: Throwable?,
        cacheResult: Boolean,
    ) = withContext(NonCancellable) {
        mutex.withLock {
            if (inFlight[key] !== deferred) return@withLock
            inFlight.remove(key)
            if (failure != null) {
                deferred.completeExceptionally(failure)
            } else {
                val completed = requireNotNull(result)
                if (cacheResult) capabilities[key] = completed
                deferred.complete(completed)
            }
        }
    }
}

class CachedOpenAiCapabilityProvider(
    private val cache: OpenAiCapabilityCache,
    private val key: OpenAiCapabilityCacheKey,
    private val probe: suspend () -> OpenAiProviderCapabilities,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : OpenAiCapabilityProvider {
    override suspend fun capabilities(): OpenAiProviderCapabilities = try {
        cache.getOrProbe(key, probe)
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Throwable) {
        OpenAiProviderCapabilities.unavailable(nowMs())
    }
}
