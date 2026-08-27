package io.openeden.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
            mutex.withLock {
                capabilities[key] = result
                inFlight.remove(key)
                deferred.complete(result)
            }
            result
        } catch (failure: Throwable) {
            mutex.withLock {
                inFlight.remove(key)
                deferred.completeExceptionally(failure)
            }
            throw failure
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
