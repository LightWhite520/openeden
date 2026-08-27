package io.openeden.llm

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class OpenAiCapabilityCache(
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val capabilities = mutableMapOf<OpenAiCapabilityCacheKey, OpenAiProviderCapabilities>()

    suspend fun get(key: OpenAiCapabilityCacheKey): OpenAiProviderCapabilities? = mutex.withLock {
        capabilities[key]?.takeIf { it.expiresAtMs > nowMs() }
    }

    suspend fun getOrProbe(
        key: OpenAiCapabilityCacheKey,
        probe: suspend () -> OpenAiProviderCapabilities,
    ): OpenAiProviderCapabilities = mutex.withLock {
        capabilities[key]?.takeIf { it.expiresAtMs > nowMs() } ?: probe().also { capabilities[key] = it }
    }
}
