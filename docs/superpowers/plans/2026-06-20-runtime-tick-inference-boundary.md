# Runtime Tick and Inference Boundary Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Harden OpenEden runtime internals with an explicit inference boundary, unified physiological tick, monotonic Ω accumulation, ShockState passive decay, runtime configuration, and bootstrap centroid provider.

**Architecture:** Keep all domain behavior in `core`; server wiring only composes durable storage, owner config, dispatcher-backed inference executor, heartbeat, and tick scheduler. Message-time and tick-time runtime math flows through `InferenceExecutor`; state mutation remains serialized through `VectorWriteService`.

**Tech Stack:** Kotlin Multiplatform core, Kotlin/JVM server, coroutines, kotlinx serialization, SQLDelight-backed session state, kotlin.test.

---

## File Structure

- Create `core/src/commonMain/kotlin/io/openeden/runtime/InferenceExecutor.kt`: common inference boundary and direct executor for tests/defaults.
- Create `core/src/jvmMain/kotlin/io/openeden/runtime/JvmInferenceExecutor.kt`: JVM dispatcher-backed executor for server runtime.
- Create `core/src/commonTest/kotlin/io/openeden/runtime/InferenceExecutorTest.kt`: executor smoke tests.
- Modify `core/src/commonMain/kotlin/io/openeden/runtime/MessagePipeline.kt`: run quantization, derived D, mapping, retrieval selection, and shock back-detection through `InferenceExecutor`.
- Modify `core/src/commonTest/kotlin/io/openeden/runtime/MessagePipelineTest.kt`: prove pipeline uses executor boundary.
- Create `core/src/commonMain/kotlin/io/openeden/runtime/RuntimeConfig.kt`: owner, tick, omega, and shock config defaults.
- Create `core/src/commonTest/kotlin/io/openeden/runtime/RuntimeConfigTest.kt`: config validation tests.
- Create `core/src/commonMain/kotlin/io/openeden/runtime/OmegaAccumulation.kt`: deterministic monotonic Ω wear engine.
- Create `core/src/commonTest/kotlin/io/openeden/runtime/OmegaAccumulationTest.kt`: S/D/F Ω accumulation tests.
- Create `core/src/commonMain/kotlin/io/openeden/runtime/HomeostasisCentroid.kt`: bootstrap centroid provider.
- Create `core/src/commonTest/kotlin/io/openeden/runtime/HomeostasisCentroidProviderTest.kt`: default centroid tests.
- Create `core/src/commonMain/kotlin/io/openeden/runtime/RuntimeTick.kt`: unified tick scheduler and tick result model.
- Delete `core/src/commonMain/kotlin/io/openeden/runtime/BackgroundDrift.kt`: replaced by `RuntimeTickScheduler`.
- Replace `core/src/commonTest/kotlin/io/openeden/runtime/BackgroundDriftSchedulerTest.kt` with `core/src/commonTest/kotlin/io/openeden/runtime/RuntimeTickSchedulerTest.kt`.
- Modify `core/src/commonMain/kotlin/io/openeden/runtime/RuntimeContracts.kt`: add tick write method and trace tags for drift/shock decay/omega update.
- Modify `core/src/commonMain/kotlin/io/openeden/trace/TraceTag.kt`: add `shock=DECAYED`, `omega=ACCUMULATED`, `tick=SESSION_FAILED`.
- Modify `server/src/main/kotlin/Runtime.kt`: use `RuntimeConfig`, `JvmInferenceExecutor`, and `RuntimeTickScheduler`.
- Modify `docs/runtime-bootstrap.md`: document owner env vars and runtime tick behavior.

---

### Task 1: Inference Executor Boundary

**Files:**
- Create: `core/src/commonMain/kotlin/io/openeden/runtime/InferenceExecutor.kt`
- Create: `core/src/jvmMain/kotlin/io/openeden/runtime/JvmInferenceExecutor.kt`
- Create: `core/src/commonTest/kotlin/io/openeden/runtime/InferenceExecutorTest.kt`

- [ ] **Step 1: Write the failing common executor tests**

Create `core/src/commonTest/kotlin/io/openeden/runtime/InferenceExecutorTest.kt`:

```kotlin
package io.openeden.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class InferenceExecutorTest {
    @Test
    fun `direct executor returns block result`() = runTest {
        val result = DirectInferenceExecutor.run { "ok" }

        assertEquals("ok", result)
    }

    @Test
    fun `recording executor counts inference boundary crossings`() = runTest {
        val executor = RecordingInferenceExecutor()

        val result = executor.run { 42 }

        assertEquals(42, result)
        assertEquals(1, executor.calls)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```powershell
.\gradlew.bat :core:jvmTest --tests io.openeden.runtime.InferenceExecutorTest
```

Expected: compilation fails with unresolved references `DirectInferenceExecutor` and `RecordingInferenceExecutor`.

- [ ] **Step 3: Add common inference executor implementations**

Create `core/src/commonMain/kotlin/io/openeden/runtime/InferenceExecutor.kt`:

```kotlin
package io.openeden.runtime

fun interface InferenceExecutor {
    suspend fun <T> run(block: suspend () -> T): T
}

object DirectInferenceExecutor : InferenceExecutor {
    override suspend fun <T> run(block: suspend () -> T): T = block()
}

class RecordingInferenceExecutor(
    private val delegate: InferenceExecutor = DirectInferenceExecutor,
) : InferenceExecutor {
    var calls: Int = 0
        private set

    override suspend fun <T> run(block: suspend () -> T): T {
        calls += 1
        return delegate.run(block)
    }
}
```

- [ ] **Step 4: Add JVM dispatcher-backed executor**

Create `core/src/jvmMain/kotlin/io/openeden/runtime/JvmInferenceExecutor.kt`:

```kotlin
package io.openeden.runtime

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class JvmInferenceExecutor(
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : InferenceExecutor {
    override suspend fun <T> run(block: suspend () -> T): T =
        withContext(dispatcher) { block() }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run:

```powershell
.\gradlew.bat :core:jvmTest --tests io.openeden.runtime.InferenceExecutorTest
```

Expected: build succeeds and both tests pass.

- [ ] **Step 6: Commit**

```powershell
git add core/src/commonMain/kotlin/io/openeden/runtime/InferenceExecutor.kt core/src/jvmMain/kotlin/io/openeden/runtime/JvmInferenceExecutor.kt core/src/commonTest/kotlin/io/openeden/runtime/InferenceExecutorTest.kt
git commit -m "feat: add inference executor boundary"
```

---

### Task 2: Pipeline Uses Inference Boundary

**Files:**
- Modify: `core/src/commonMain/kotlin/io/openeden/runtime/MessagePipeline.kt`
- Modify: `core/src/commonTest/kotlin/io/openeden/runtime/MessagePipelineTest.kt`

- [ ] **Step 1: Add failing pipeline boundary test**

Append this test inside `MessagePipelineTest` before `testPersonaConfig()`:

```kotlin
    @Test
    fun `pipeline runs runtime math through inference executor`() = runTest {
        val executor = RecordingInferenceExecutor()
        val pipeline = DevelopmentMessagePipeline.create(
            personaConfig = testPersonaConfig(),
            inferenceExecutor = executor,
        )

        pipeline.handle(
            DevelopmentMessageRequest(
                platform = "QQ",
                scopeId = "100",
                userId = "u1",
                text = "hello",
                emotionConfidence = 0.49f,
            ),
        )

        assertEquals(1, executor.calls)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```powershell
.\gradlew.bat :core:jvmTest --tests io.openeden.runtime.MessagePipelineTest
```

Expected: compilation fails because `DevelopmentMessagePipeline.create` has no `inferenceExecutor` parameter.

- [ ] **Step 3: Thread executor through pipeline constructor and factory**

In `MessagePipeline.kt`, add a constructor property after `diaryQueue`:

```kotlin
    private val inferenceExecutor: InferenceExecutor,
```

Update `DevelopmentMessagePipeline.create` signature:

```kotlin
        fun create(
            personaConfig: PersonaConfig,
            llmClient: LlmClient = DevelopmentLlmStub(),
            store: SessionStateStore = MutableSessionStateStore(),
            vectorWriteService: VectorWriteService = VectorWriteService(store),
            inferenceExecutor: InferenceExecutor = DirectInferenceExecutor,
        ): DevelopmentMessagePipeline {
            return DevelopmentMessagePipeline(
                personaConfig = personaConfig,
                store = store,
                quantizer = HeuristicCodebookFallback(),
                memoryRetriever = EmptyMemoryRetriever,
                promptBuilder = DefaultPromptBuilder(),
                llmClient = llmClient,
                vectorWriteService = vectorWriteService,
                diaryQueue = SessionDiaryQueue(),
                inferenceExecutor = inferenceExecutor,
            )
        }
```

- [ ] **Step 4: Move runtime math into one inference block**

Replace the current pre-prompt math in `handle`:

```kotlin
        val dissonance = preTick.preTicked.derivedDissonance()
        val quantization = quantizer.quantize(preTick.preTicked, dissonance)
        val internalVector = VectorMapping.toInternal(preTick.preTicked, current.origin)
        val retrievalMode = RetrievalModeSelector.select(
            internalVector = internalVector,
            omegaState = current.omega,
            shockState = current.shockState,
        )
```

with:

```kotlin
        val inference = inferenceExecutor.run {
            val dissonance = preTick.preTicked.derivedDissonance()
            val quantization = quantizer.quantize(preTick.preTicked, dissonance)
            val internalVector = VectorMapping.toInternal(preTick.preTicked, current.origin)
            val retrievalMode = RetrievalModeSelector.select(
                internalVector = internalVector,
                omegaState = current.omega,
                shockState = current.shockState,
            )
            PipelineInferenceResult(
                dissonance = dissonance,
                quantization = quantization,
                retrievalMode = retrievalMode,
            )
        }
```

Update later references:

```kotlin
                mode = inference.retrievalMode,
```

```kotlin
                derivedDissonance = inference.dissonance,
                quantization = inference.quantization,
```

```kotlin
            traceTags = inference.quantization.traceTags + write.traceTags + diaryOutcome.traceTags + sourceTags,
```

Add this private data class near `DiaryOutcome`:

```kotlin
private data class PipelineInferenceResult(
    val dissonance: Float,
    val quantization: io.openeden.codebook.CodebookQuantization,
    val retrievalMode: RetrievalMode,
)
```

- [ ] **Step 5: Move shock back-detection into inference executor**

Replace:

```kotlin
            val shockWrite = ShockStateEngine.detectFromLlmOutput(
                vectorDelta = validation.delta,
                emotionConfidence = request.emotionConfidence,
                internalLogic = validation.output?.internalLogic.orEmpty(),
            )?.let { shock ->
                vectorWriteService.applyShock(sessionId, shock)
            }
```

with:

```kotlin
            val detectedShock = inferenceExecutor.run {
                ShockStateEngine.detectFromLlmOutput(
                    vectorDelta = validation.delta,
                    emotionConfidence = request.emotionConfidence,
                    internalLogic = validation.output?.internalLogic.orEmpty(),
                )
            }
            val shockWrite = detectedShock?.let { shock ->
                vectorWriteService.applyShock(sessionId, shock)
            }
```

Update the test from Step 1 to expect two boundary crossings when LLM output is valid:

```kotlin
        assertEquals(2, executor.calls)
```

- [ ] **Step 6: Run test to verify it passes**

Run:

```powershell
.\gradlew.bat :core:jvmTest --tests io.openeden.runtime.MessagePipelineTest
```

Expected: all `MessagePipelineTest` tests pass.

- [ ] **Step 7: Commit**

```powershell
git add core/src/commonMain/kotlin/io/openeden/runtime/MessagePipeline.kt core/src/commonTest/kotlin/io/openeden/runtime/MessagePipelineTest.kt
git commit -m "feat: run pipeline math through inference executor"
```

---

### Task 3: Runtime Config

**Files:**
- Create: `core/src/commonMain/kotlin/io/openeden/runtime/RuntimeConfig.kt`
- Create: `core/src/commonTest/kotlin/io/openeden/runtime/RuntimeConfigTest.kt`

- [ ] **Step 1: Write failing config tests**

Create `core/src/commonTest/kotlin/io/openeden/runtime/RuntimeConfigTest.kt`:

```kotlin
package io.openeden.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RuntimeConfigTest {
    @Test
    fun `default runtime config is conservative`() {
        val config = RuntimeConfig.Default

        assertEquals(60_000L, config.tick.intervalMs)
        assertEquals(0.75f, config.omega.highThreshold)
        assertEquals(null, config.owner)
    }

    @Test
    fun `rejects invalid tick interval`() {
        assertFailsWith<IllegalArgumentException> {
            RuntimeConfig.Default.copy(tick = TickConfig(intervalMs = 0L))
        }
    }

    @Test
    fun `rejects invalid omega coefficients`() {
        assertFailsWith<IllegalArgumentException> {
            OmegaAccumulationConfig(sWearRate = -0.1f)
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```powershell
.\gradlew.bat :core:jvmTest --tests io.openeden.runtime.RuntimeConfigTest
```

Expected: compilation fails with unresolved references `RuntimeConfig`, `TickConfig`, and `OmegaAccumulationConfig`.

- [ ] **Step 3: Add runtime config model**

Create `core/src/commonMain/kotlin/io/openeden/runtime/RuntimeConfig.kt`:

```kotlin
package io.openeden.runtime

data class RuntimeConfig(
    val tick: TickConfig = TickConfig(),
    val omega: OmegaAccumulationConfig = OmegaAccumulationConfig(),
    val owner: HeartbeatOwner? = null,
) {
    companion object {
        val Default = RuntimeConfig()
    }
}

data class TickConfig(
    val intervalMs: Long = 60_000L,
) {
    init {
        require(intervalMs > 0) { "tick interval must be positive" }
    }
}

data class OmegaAccumulationConfig(
    val highThreshold: Float = 0.75f,
    val sWearRate: Float = 0.00005f,
    val dissonanceWearRate: Float = 0.00005f,
    val fearEntropyMultiplier: Float = 1.5f,
) {
    init {
        require(highThreshold in 0.0f..1.0f) { "omega high threshold must be in [0, 1]" }
        require(sWearRate >= 0.0f) { "sWearRate must be non-negative" }
        require(dissonanceWearRate >= 0.0f) { "dissonanceWearRate must be non-negative" }
        require(fearEntropyMultiplier >= 1.0f) { "fearEntropyMultiplier must be at least 1" }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:

```powershell
.\gradlew.bat :core:jvmTest --tests io.openeden.runtime.RuntimeConfigTest
```

Expected: all tests pass.

- [ ] **Step 5: Commit**

```powershell
git add core/src/commonMain/kotlin/io/openeden/runtime/RuntimeConfig.kt core/src/commonTest/kotlin/io/openeden/runtime/RuntimeConfigTest.kt
git commit -m "feat: add runtime config model"
```

---

### Task 4: Omega Accumulation Engine

**Files:**
- Create: `core/src/commonMain/kotlin/io/openeden/runtime/OmegaAccumulation.kt`
- Create: `core/src/commonTest/kotlin/io/openeden/runtime/OmegaAccumulationTest.kt`

- [ ] **Step 1: Write failing Ω tests**

Create `core/src/commonTest/kotlin/io/openeden/runtime/OmegaAccumulationTest.kt`:

```kotlin
package io.openeden.runtime

import io.openeden.bio.BioVector
import kotlin.test.Test
import kotlin.test.assertEquals

class OmegaAccumulationTest {
    private val config = OmegaAccumulationConfig(
        highThreshold = 0.75f,
        sWearRate = 0.01f,
        dissonanceWearRate = 0.02f,
        fearEntropyMultiplier = 2.0f,
    )

    @Test
    fun `high entropy increases omega monotonically`() {
        val updated = OmegaAccumulationEngine.accumulate(
            omega = OmegaState(0.2f),
            vector = BioVector.Neutral.copy(s = 0.9f),
            elapsedMillis = 1_000L,
            config = config,
        )

        assertEquals(0.21f, updated.value, 0.0001f)
    }

    @Test
    fun `high dissonance increases omega`() {
        val updated = OmegaAccumulationEngine.accumulate(
            omega = OmegaState(0.2f),
            vector = BioVector(l = 1.0f, p = 0.5f, e = 0.0f, s = 0.5f, tau = 0.0f, v = 0.5f, m = 0.5f, f = 0.5f),
            elapsedMillis = 1_000L,
            config = config,
        )

        assertEquals(0.22f, updated.value, 0.0001f)
    }

    @Test
    fun `high fear with high entropy accelerates omega`() {
        val updated = OmegaAccumulationEngine.accumulate(
            omega = OmegaState(0.2f),
            vector = BioVector.Neutral.copy(s = 0.9f, f = 0.9f),
            elapsedMillis = 1_000L,
            config = config,
        )

        assertEquals(0.22f, updated.value, 0.0001f)
    }

    @Test
    fun `low pressure state does not reduce omega`() {
        val updated = OmegaAccumulationEngine.accumulate(
            omega = OmegaState(0.2f),
            vector = BioVector.Neutral.copy(s = 0.1f, f = 0.1f),
            elapsedMillis = 1_000L,
            config = config,
        )

        assertEquals(0.2f, updated.value)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```powershell
.\gradlew.bat :core:jvmTest --tests io.openeden.runtime.OmegaAccumulationTest
```

Expected: compilation fails with unresolved reference `OmegaAccumulationEngine`.

- [ ] **Step 3: Implement Ω accumulation**

Create `core/src/commonMain/kotlin/io/openeden/runtime/OmegaAccumulation.kt`:

```kotlin
package io.openeden.runtime

import io.openeden.bio.BioVector

object OmegaAccumulationEngine {
    fun accumulate(
        omega: OmegaState,
        vector: BioVector,
        elapsedMillis: Long,
        config: OmegaAccumulationConfig,
    ): OmegaState {
        val seconds = elapsedMillis.coerceAtLeast(0).toFloat() / 1000.0f
        val entropyWear = if (vector.s >= config.highThreshold) config.sWearRate * seconds else 0.0f
        val dissonance = vector.derivedDissonance()
        val dissonanceWear = if (dissonance >= config.highThreshold) {
            config.dissonanceWearRate * seconds
        } else {
            0.0f
        }
        val multiplier = if (vector.s >= config.highThreshold && vector.f >= config.highThreshold) {
            config.fearEntropyMultiplier
        } else {
            1.0f
        }
        return omega.increase((entropyWear + dissonanceWear) * multiplier)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:

```powershell
.\gradlew.bat :core:jvmTest --tests io.openeden.runtime.OmegaAccumulationTest
```

Expected: all tests pass.

- [ ] **Step 5: Commit**

```powershell
git add core/src/commonMain/kotlin/io/openeden/runtime/OmegaAccumulation.kt core/src/commonTest/kotlin/io/openeden/runtime/OmegaAccumulationTest.kt
git commit -m "feat: add omega accumulation engine"
```

---

### Task 5: Homeostasis Centroid Provider

**Files:**
- Create: `core/src/commonMain/kotlin/io/openeden/runtime/HomeostasisCentroid.kt`
- Create: `core/src/commonTest/kotlin/io/openeden/runtime/HomeostasisCentroidProviderTest.kt`

- [ ] **Step 1: Write failing centroid test**

Create `core/src/commonTest/kotlin/io/openeden/runtime/HomeostasisCentroidProviderTest.kt`:

```kotlin
package io.openeden.runtime

import io.openeden.bio.BioVector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class HomeostasisCentroidProviderTest {
    @Test
    fun `bootstrap centroid provider returns persisted origin`() = runTest {
        val store = MutableSessionStateStore()
        val origin = BioVector.Neutral.copy(p = 0.35f, v = 0.4f)
        store.write(SessionStateStore.neutral("QQ:centroid").copy(origin = origin))
        val provider = StoredOriginCentroidProvider(store)

        assertEquals(origin, provider.centroidFor("QQ:centroid"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```powershell
.\gradlew.bat :core:jvmTest --tests io.openeden.runtime.HomeostasisCentroidProviderTest
```

Expected: compilation fails with unresolved reference `StoredOriginCentroidProvider`.

- [ ] **Step 3: Add provider interface and bootstrap implementation**

Create `core/src/commonMain/kotlin/io/openeden/runtime/HomeostasisCentroid.kt`:

```kotlin
package io.openeden.runtime

import io.openeden.bio.BioVector

fun interface HomeostasisCentroidProvider {
    suspend fun centroidFor(sessionId: String): BioVector
}

class StoredOriginCentroidProvider(
    private val store: SessionStateStore,
) : HomeostasisCentroidProvider {
    override suspend fun centroidFor(sessionId: String): BioVector =
        store.read(sessionId).origin
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:

```powershell
.\gradlew.bat :core:jvmTest --tests io.openeden.runtime.HomeostasisCentroidProviderTest
```

Expected: test passes.

- [ ] **Step 5: Commit**

```powershell
git add core/src/commonMain/kotlin/io/openeden/runtime/HomeostasisCentroid.kt core/src/commonTest/kotlin/io/openeden/runtime/HomeostasisCentroidProviderTest.kt
git commit -m "feat: add bootstrap centroid provider"
```

---

### Task 6: Runtime Tick Scheduler

**Files:**
- Create: `core/src/commonMain/kotlin/io/openeden/runtime/RuntimeTick.kt`
- Delete: `core/src/commonMain/kotlin/io/openeden/runtime/BackgroundDrift.kt`
- Create: `core/src/commonTest/kotlin/io/openeden/runtime/RuntimeTickSchedulerTest.kt`
- Delete: `core/src/commonTest/kotlin/io/openeden/runtime/BackgroundDriftSchedulerTest.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/runtime/RuntimeContracts.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/trace/TraceTag.kt`

- [ ] **Step 1: Write failing runtime tick tests**

Create `core/src/commonTest/kotlin/io/openeden/runtime/RuntimeTickSchedulerTest.kt`:

```kotlin
package io.openeden.runtime

import io.openeden.bio.BioVector
import io.openeden.trace.TraceTag
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.test.runTest
import kotlin.time.Instant

class RuntimeTickSchedulerTest {
    @Test
    fun `tick applies sine drift and does not increment evolution index`() = runTest {
        val store = MutableSessionStateStore()
        store.write(SessionStateStore.neutral("QQ:drift"))
        val writer = VectorWriteService(store)
        val fluctuation = constantFluctuation()
        val executor = RecordingInferenceExecutor()
        val scheduler = RuntimeTickScheduler(
            store = store,
            writer = writer,
            fluctuation = fluctuation,
            inferenceExecutor = executor,
            config = RuntimeConfig.Default.copy(omega = OmegaAccumulationConfig(sWearRate = 0.0f, dissonanceWearRate = 0.0f)),
            startedAtMs = 0L,
        )

        val result = scheduler.evaluateOnce(nowMs = 1_000L)

        val state = store.read("QQ:drift")
        assertEquals(0, state.evolutionIndex)
        assertEquals(0.5f + fluctuation.deltaAt(1_000L).p, state.vector.p, 0.0001f)
        assertEquals(1, executor.calls)
        assertContains(result.single().traceTags, TraceTag.BackgroundDrift)
    }

    @Test
    fun `tick decays shock state and marks inactive below threshold`() = runTest {
        val store = MutableSessionStateStore()
        store.write(
            SessionStateStore.neutral("QQ:shock").copy(
                shockState = ShockState(
                    active = true,
                    intensity = 0.06f,
                    description = "shock",
                    triggeredAt = Instant.fromEpochMilliseconds(0),
                    decayLambda = 1.0f,
                    shockHeartbeatFired = true,
                ),
            ),
        )
        val scheduler = RuntimeTickScheduler(
            store = store,
            writer = VectorWriteService(store),
            fluctuation = constantFluctuation(),
            inferenceExecutor = DirectInferenceExecutor,
            config = RuntimeConfig.Default.copy(omega = OmegaAccumulationConfig(sWearRate = 0.0f, dissonanceWearRate = 0.0f)),
            startedAtMs = 0L,
        )

        val result = scheduler.evaluateOnce(nowMs = 10_000L)

        val shock = store.read("QQ:shock").shockState!!
        assertFalse(shock.active)
        assertEquals(true, shock.shockHeartbeatFired)
        assertContains(result.single().traceTags, TraceTag.ShockStateDecayed)
    }

    @Test
    fun `tick accumulates omega without reducing it`() = runTest {
        val store = MutableSessionStateStore()
        store.write(
            SessionStateStore.neutral("QQ:omega").copy(
                vector = BioVector.Neutral.copy(s = 0.9f, f = 0.9f),
                omega = OmegaState(0.2f),
            ),
        )
        val scheduler = RuntimeTickScheduler(
            store = store,
            writer = VectorWriteService(store),
            fluctuation = zeroFluctuation(),
            inferenceExecutor = DirectInferenceExecutor,
            config = RuntimeConfig.Default.copy(
                omega = OmegaAccumulationConfig(
                    highThreshold = 0.75f,
                    sWearRate = 0.01f,
                    dissonanceWearRate = 0.0f,
                    fearEntropyMultiplier = 2.0f,
                ),
            ),
            startedAtMs = 0L,
        )

        val result = scheduler.evaluateOnce(nowMs = 1_000L)

        assertEquals(0.22f, store.read("QQ:omega").omega.value, 0.0001f)
        assertContains(result.single().traceTags, TraceTag.OmegaAccumulated)
    }

    private fun constantFluctuation(): SineWaveFluctuationEngine =
        SineWaveFluctuationEngine(
            SineWaveFluctuationProfile(
                dimensions = List(8) {
                    SineWaveDimension(amplitude = 0.04f, frequencyHz = 0.002f, phaseRadians = 0.1f)
                },
            ),
        )

    private fun zeroFluctuation(): SineWaveFluctuationEngine =
        SineWaveFluctuationEngine(
            SineWaveFluctuationProfile(
                dimensions = List(8) {
                    SineWaveDimension(amplitude = 0.0f, frequencyHz = 0.002f, phaseRadians = 0.1f)
                },
            ),
        )
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```powershell
.\gradlew.bat :core:jvmTest --tests io.openeden.runtime.RuntimeTickSchedulerTest
```

Expected: compilation fails with unresolved reference `RuntimeTickScheduler`.

- [ ] **Step 3: Add trace tags**

Modify `TraceTag.kt`:

```kotlin
package io.openeden.trace

object TraceTag {
    const val CodebookHeuristicFallback = "codebook=HEURISTIC_FALLBACK"
    const val DiaryQueueOverflow = "diary=QUEUE_OVERFLOW"
    const val VectorWriteSerialized = "vector=WRITE_SERIALIZED"
    const val HeartbeatSource = "source=HEARTBEAT"
    const val ShockStateTransition = "shock=STATE_TRANSITION"
    const val BackgroundDrift = "source=BACKGROUND_DRIFT"
    const val ShockStateDecayed = "shock=DECAYED"
    const val OmegaAccumulated = "omega=ACCUMULATED"
    const val RuntimeTickSessionFailed = "tick=SESSION_FAILED"
}
```

- [ ] **Step 4: Add generic tick write method**

In `VectorWriteService`, add this method after `applyBackgroundDrift`:

```kotlin
    suspend fun applyRuntimeTick(
        sessionId: String,
        transform: (SessionState) -> Pair<SessionState, Set<String>>,
    ): VectorWriteResult {
        val mutex = mutexRegistry.forSession(sessionId)
        return mutex.withLock {
            val latest = store.read(sessionId)
            val (updated, traceTags) = transform(latest)
            store.write(updated)
            VectorWriteResult(
                state = updated,
                traceTags = traceTags,
            )
        }
    }
```

- [ ] **Step 5: Implement runtime tick scheduler**

Create `core/src/commonMain/kotlin/io/openeden/runtime/RuntimeTick.kt`:

```kotlin
package io.openeden.runtime

import io.openeden.trace.TraceTag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Clock

data class RuntimeTickResult(
    val sessionId: String,
    val traceTags: Set<String>,
)

class RuntimeTickScheduler(
    private val store: SessionStateStore,
    private val writer: VectorWriteService,
    private val fluctuation: SineWaveFluctuationEngine,
    private val inferenceExecutor: InferenceExecutor,
    private val config: RuntimeConfig = RuntimeConfig.Default,
    private val startedAtMs: Long = Clock.System.now().toEpochMilliseconds(),
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    suspend fun evaluateOnce(nowMs: Long = this.nowMs()): List<RuntimeTickResult> {
        val results = mutableListOf<RuntimeTickResult>()
        for (sessionId in store.sessionIds()) {
            val result = runCatching {
                val elapsed = nowMs - startedAtMs
                val tickMath = inferenceExecutor.run {
                    val latest = store.read(sessionId)
                    val driftDelta = fluctuation.deltaAt(elapsed)
                    val driftedVector = latest.vector.apply(driftDelta)
                    val decayedShock = latest.shockState?.let { ShockStateEngine.decay(it, elapsed) }
                    val omega = OmegaAccumulationEngine.accumulate(
                        omega = latest.omega,
                        vector = driftedVector,
                        elapsedMillis = elapsed,
                        config = config.omega,
                    )
                    TickMath(
                        vector = driftedVector,
                        shockState = decayedShock,
                        omega = omega,
                        shockDecayed = decayedShock != latest.shockState,
                        omegaChanged = omega != latest.omega,
                    )
                }
                writer.applyRuntimeTick(sessionId) { latest ->
                    val traceTags = buildSet {
                        add(TraceTag.BackgroundDrift)
                        if (tickMath.shockDecayed) add(TraceTag.ShockStateDecayed)
                        if (tickMath.omegaChanged) add(TraceTag.OmegaAccumulated)
                    }
                    latest.copy(
                        vector = tickMath.vector,
                        shockState = tickMath.shockState,
                        omega = tickMath.omega,
                    ) to traceTags
                }
            }.getOrElse {
                VectorWriteResult(
                    state = store.read(sessionId),
                    traceTags = setOf(TraceTag.RuntimeTickSessionFailed),
                )
            }
            results += RuntimeTickResult(sessionId, result.traceTags)
        }
        return results
    }

    fun start(scope: CoroutineScope): Job = scope.launch {
        while (isActive) {
            delay(config.tick.intervalMs)
            evaluateOnce()
        }
    }
}

private data class TickMath(
    val vector: io.openeden.bio.BioVector,
    val shockState: ShockState?,
    val omega: OmegaState,
    val shockDecayed: Boolean,
    val omegaChanged: Boolean,
)
```

- [ ] **Step 6: Remove old background drift files**

Delete:

```text
core/src/commonMain/kotlin/io/openeden/runtime/BackgroundDrift.kt
core/src/commonTest/kotlin/io/openeden/runtime/BackgroundDriftSchedulerTest.kt
```

- [ ] **Step 7: Run runtime tick tests**

Run:

```powershell
.\gradlew.bat :core:jvmTest --tests io.openeden.runtime.RuntimeTickSchedulerTest
```

Expected: all tests pass.

- [ ] **Step 8: Run all runtime tests touched so far**

Run:

```powershell
.\gradlew.bat :core:jvmTest --tests io.openeden.runtime.*
```

Expected: all runtime tests pass.

- [ ] **Step 9: Commit**

```powershell
git add core/src/commonMain/kotlin/io/openeden/runtime/RuntimeTick.kt core/src/commonMain/kotlin/io/openeden/runtime/RuntimeContracts.kt core/src/commonMain/kotlin/io/openeden/trace/TraceTag.kt core/src/commonTest/kotlin/io/openeden/runtime/RuntimeTickSchedulerTest.kt
git rm core/src/commonMain/kotlin/io/openeden/runtime/BackgroundDrift.kt core/src/commonTest/kotlin/io/openeden/runtime/BackgroundDriftSchedulerTest.kt
git commit -m "feat: replace background drift with runtime tick"
```

---

### Task 7: Server Runtime Wiring

**Files:**
- Modify: `server/src/main/kotlin/Runtime.kt`
- Modify: `docs/runtime-bootstrap.md`
- Test: `server/src/test/kotlin/ServerTest.kt`

- [ ] **Step 1: Update server runtime imports and wiring**

In `server/src/main/kotlin/Runtime.kt`, replace imports:

```kotlin
import io.openeden.runtime.BackgroundDriftScheduler
```

with:

```kotlin
import io.openeden.runtime.JvmInferenceExecutor
import io.openeden.runtime.RuntimeConfig
import io.openeden.runtime.RuntimeTickScheduler
```

Inside `configureRuntime`, after `val writer = VectorWriteService(store)`, add:

```kotlin
    val runtimeConfig = RuntimeConfig.Default.copy(owner = resolveHeartbeatOwner())
    val inferenceExecutor = JvmInferenceExecutor()
```

Update pipeline creation:

```kotlin
    val pipeline = DevelopmentMessagePipeline.create(
        personaConfig = loadDefaultPersonaConfig(),
        store = store,
        vectorWriteService = writer,
        inferenceExecutor = inferenceExecutor,
    )
```

Update heartbeat owner resolver:

```kotlin
        routeResolver = OwnerHeartbeatRouteResolver(runtimeConfig.owner),
```

Replace drift scheduler block:

```kotlin
    val driftJob = BackgroundDriftScheduler(
        store = store,
        writer = writer,
        fluctuation = SineWaveFluctuationEngine(SecureRandomSineWaveFluctuation.profile()),
    ).start(scope)
```

with:

```kotlin
    val tickJob = RuntimeTickScheduler(
        store = store,
        writer = writer,
        fluctuation = SineWaveFluctuationEngine(SecureRandomSineWaveFluctuation.profile()),
        inferenceExecutor = inferenceExecutor,
        config = runtimeConfig,
    ).start(scope)
```

Update shutdown:

```kotlin
        tickJob.cancel()
```

- [ ] **Step 2: Update runtime bootstrap docs**

Append this section to `docs/runtime-bootstrap.md`:

````markdown
## Runtime Tick

The runtime tick runs independently of user messages. It applies sine-wave physiological drift, ShockState passive decay, and Ω accumulation without incrementing `evolution_index`.

Heartbeat owner delivery is configured with:

```powershell
$env:OPENEDEN_OWNER_PLATFORM="QQ"
$env:OPENEDEN_OWNER_USER_ID="123456"
```

If owner variables are absent, heartbeat output is dropped after state write-back.
````

- [ ] **Step 3: Run server tests**

Run:

```powershell
.\gradlew.bat :server:test
```

Expected: build succeeds and all server tests pass.

- [ ] **Step 4: Commit**

```powershell
git add server/src/main/kotlin/Runtime.kt docs/runtime-bootstrap.md
git commit -m "feat: wire runtime tick into server"
```

---

### Task 8: Final Verification and Compliance Review

**Files:**
- Verify all touched files.

- [ ] **Step 1: Run full server test suite**

Run:

```powershell
.\gradlew.bat :server:test
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Check for forbidden stale names and marker text**

Run:

```powershell
rg "BackgroundDriftScheduler|TODO|TBD|placeholder|most recently active|all connected adapters" core server docs/runtime-bootstrap.md docs/superpowers/specs AGENTS.md
```

Expected:

- no `BackgroundDriftScheduler`;
- no unfinished-marker tokens;
- no undecided-marker tokens;
- no stale heartbeat adapter routing rules.

- [ ] **Step 3: Check working tree**

Run:

```powershell
git status --short --branch
```

Expected: branch is ahead of `origin/master` by the implementation commits and has no unstaged changes.

- [ ] **Step 4: Review AGENTS.md compliance**

Confirm manually:

- no persona prose added to Kotlin;
- D is computed and never stored;
- runtime math added in this phase goes through `InferenceExecutor`;
- tick writes use `VectorWriteService`;
- tick does not increment `evolution_index`;
- heartbeat remains owner-only;
- `.\gradlew.bat :server:test` passed.

- [ ] **Step 5: Push**

Run:

```powershell
git push origin master
```

Expected: push succeeds and `origin/master` advances to the final implementation commit.

---

## Self-Review Notes

Spec coverage:

- `InferenceExecutor`: Task 1 and Task 2.
- Runtime tick replacing background drift: Task 6 and Task 7.
- Ω accumulation: Task 4 and Task 6.
- ShockState passive decay: Task 6.
- Runtime config: Task 3 and Task 7.
- Bootstrap centroid provider: Task 5.
- Owner-only heartbeat preserved: Task 7 final wiring and Task 8 compliance check.
- Tests and final verification: each task includes red/green checks; Task 8 covers full verification.

Marker scan:

- The plan avoids unfinished or undecided marker text outside command examples that intentionally search for those patterns.

Type consistency:

- `InferenceExecutor`, `DirectInferenceExecutor`, and `RecordingInferenceExecutor` are defined before pipeline usage.
- `RuntimeConfig`, `TickConfig`, and `OmegaAccumulationConfig` are defined before tick usage.
- `RuntimeTickScheduler` replaces `BackgroundDriftScheduler` consistently.
