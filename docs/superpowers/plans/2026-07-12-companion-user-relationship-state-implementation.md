# Companion User And Relationship State Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement confidence-bearing 6D user-affect observation, per-user relationship state, semantic prompt integration, identity-aware retrieval, and non-blocking public-chat wiring while preserving the existing 8D VQ-VAE pipeline.

**Architecture:** Add `UserAffectAnalyzer` and `RelationshipStateStore` beside the existing runtime contracts. User affect remains turn-scoped as `[valence, arousal, dominance, connectionNeed, openness, confidence]`; relationship state is persisted by `(sessionId, userId)`. The runtime maps affect through a configurable deterministic influence matrix into the existing confidence-gated pre-tick delta, renders only semantic labels into prompts, and applies bounded relationship evidence only after validated LLM turns.

**Tech Stack:** Kotlin Multiplatform, coroutines/Flow, Ktor, SQLDelight, kotlinx.serialization, existing `InferenceExecutor`, `LocalMlp`, and common/JVM test suites.

---

### Task 1: Add affect and relationship domain contracts

**Files:**
- Create: `core/src/commonMain/kotlin/io/openeden/relationship/UserAffectContracts.kt`
- Create: `core/src/commonMain/kotlin/io/openeden/relationship/RelationshipContracts.kt`
- Test: `core/src/commonTest/kotlin/io/openeden/relationship/UserAffectContractsTest.kt`
- Test: `core/src/commonTest/kotlin/io/openeden/relationship/RelationshipContractsTest.kt`

- [ ] Define `UserAffectState`, `UncertainUserAffect`, `UserAffectAnalyzer`, `RelationshipState`, `RelationshipEvidence`, `RelationshipStateStore`, and key construction using exact six affect and five relationship fields from the approved spec.
- [ ] Enforce finite `[0, 1]` continuous values, nonnegative evidence counts, and immutable copy/update helpers.
- [ ] Add tests for clamping/validation, uncertain defaults, and `(sessionId, userId)` key separation.
- [ ] Run `./gradlew :core:allTests` and confirm the new common tests pass.

### Task 2: Implement deterministic user-affect fallback and influence mapping

**Files:**
- Create: `core/src/commonMain/kotlin/io/openeden/relationship/DeterministicUserAffectAnalyzer.kt`
- Create: `core/src/commonMain/kotlin/io/openeden/relationship/UserAffectInfluenceMapper.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/runtime/RuntimeContracts.kt`
- Test: `core/src/commonTest/kotlin/io/openeden/relationship/DeterministicUserAffectAnalyzerTest.kt`
- Test: `core/src/commonTest/kotlin/io/openeden/relationship/UserAffectInfluenceMapperTest.kt`

- [ ] Implement deterministic Chinese token scoring for valence, arousal, dominance, connection need, and openness; return UNKNOWN for blank input and finite low-confidence output for unsupported text.
- [ ] Ensure fallback never emits diagnoses, hidden sensitive categories, NaN, or Infinity.
- [ ] Implement a configuration-only six-to-eight-dimensional influence matrix with neutral defaults and confidence scaling before `PreTickEngine`.
- [ ] Add property-style tests covering confidence `0`, `0.49`, `0.5`, `1.0`, negative/large inputs, and the per-dimension `MAX_PRETICK_DELTA` cap.

### Task 3: Add local DJL-compatible text-affect model support

**Files:**
- Modify: `core/src/commonMain/kotlin/io/openeden/memory/LocalEmbeddingModels.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/model/LocalModelArtifact.kt`
- Modify: `trainer/src/main/kotlin/io/openeden/trainer/LocalCodebookTrainer.kt`
- Modify: `core/src/jvmMain/kotlin/io/openeden/model/LocalModelArtifactLoader.kt` only if artifact compatibility requires it
- Test: `core/src/commonTest/kotlin/io/openeden/memory/LocalEmbeddingModelsTest.kt`
- Test: `core/src/jvmTest/kotlin/io/openeden/model/LocalModelArtifactLoaderTest.kt`

- [ ] Add a serializable `LocalTextAffectSpec` whose MLP consumes the existing text feature vector and returns five affect coordinates plus confidence.
- [ ] Implement `LocalTextAffectAnalyzer` using the existing local MLP path and `suspend` APIs; sanitize model output and fall back to UNKNOWN on inference failure.
- [ ] Preserve deserialization of existing artifacts by making the new model field optional and selecting deterministic fallback when absent.
- [ ] Extend the trainer artifact output with an identity/neutral text-affect model until trained weights are supplied, without changing VQ-VAE or emotional embedding dimensions.
- [ ] Test valid inference, invalid output, missing artifact field, and model exception degradation.

### Task 4: Add in-memory relationship persistence and runtime orchestration

**Files:**
- Modify: `core/src/commonMain/kotlin/io/openeden/runtime/MessagePipeline.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/runtime/RuntimeContracts.kt`
- Create or modify: `core/src/commonMain/kotlin/io/openeden/relationship/InMemoryRelationshipStateStore.kt`
- Test: `core/src/commonTest/kotlin/io/openeden/runtime/MessagePipelineTest.kt`
- Test: `core/src/commonTest/kotlin/io/openeden/runtime/TurnCoordinatorConcurrencyTest.kt`

- [ ] Add analyzer and relationship-store dependencies to `DevelopmentMessagePipeline` with neutral defaults.
- [ ] For user turns, analyze text inside `InferenceExecutor`, map the result into `EmotionSignal`, and use it for pre-tick; heartbeat turns use UNKNOWN and never update relationship state.
- [ ] Load relationship state by `(sessionId, userId)` inside the existing session gate, apply evidence only after valid LLM output, and commit relationship updates under the same per-session serialization boundary.
- [ ] Keep request compatibility for explicit test overrides while ensuring configured analyzers replace the public-chat zero-confidence behavior.
- [ ] Add tests proving analyzer use, rejected-turn neutrality, heartbeat neutrality, same-session serialization, and independent users sharing one `BioVector` but not relationship values.

### Task 5: Persist relationship state with SQLDelight

**Files:**
- Modify: `server/src/main/sqldelight/io/openeden/server/db/Relationship.sq`
- Modify: `server/src/main/kotlin/db/SqlDelightRelationshipStateStore.kt`
- Modify: `server/src/main/kotlin/Runtime.kt`
- Modify: `server/src/main/kotlin/Routing.kt`
- Test: `server/src/test/kotlin/SqlDelightRelationshipStateStoreTest.kt`
- Test: `server/src/test/kotlin/ServerApiTest.kt`

- [ ] Add the `relationship_state` table with primary key `(session_id, user_id)` and all five bounded coordinates plus evidence count and timestamp.
- [ ] Implement read/create, bounded update, reset, and explicit user correction operations through the core store interface.
- [ ] Wire the server runtime to the SQLDelight store and preserve restart continuity.
- [ ] Add migration/restart/group-scope tests and verify storage failures produce degraded trace state without fabricated writes.

### Task 6: Integrate semantic prompt context and persona data

**Files:**
- Modify: `core/src/commonMain/kotlin/io/openeden/prompt/PromptContracts.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/prompt/OpenEdenPromptBuilder.kt`
- Modify: `persona/atri.yaml`
- Modify: `persona/default.yaml`
- Test: `core/src/commonTest/kotlin/io/openeden/prompt/DefaultPromptBuilderTest.kt`

- [ ] Extend `PromptInput` with typed affect and relationship objects.
- [ ] Render only LOW/MEDIUM/HIGH/UNKNOWN semantic labels before user input; never expose raw coordinates or private message contents in trace labels.
- [ ] Add English logical instructions that observations are uncertain and correctable, while keeping all response behavior in YAML.
- [ ] Add golden tests for UNKNOWN, low-confidence, high-confidence, no raw-vector leakage, and persona-file ownership of behavioral rules.

### Task 7: Add identity-aware retrieval and bounded recent context

**Files:**
- Modify: `core/src/commonMain/kotlin/io/openeden/memory/MemoryContracts.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/memory/MemoryPalace.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/runtime/MessagePipeline.kt`
- Modify: `server/src/main/kotlin/db/SqlDelightMemoryRepository.kt`
- Test: `core/src/commonTest/kotlin/io/openeden/memory/InMemoryMemoryPalaceTest.kt`
- Test: `core/src/commonTest/kotlin/io/openeden/memory/RetrievalModeSelectorTest.kt`
- Test: `core/src/commonTest/kotlin/io/openeden/runtime/MessagePipelineTest.kt`

- [ ] Carry `userId` through `RetrievalRequest` and apply bounded same-user profile/event affinity plus recency adjustment without filtering shared group memories.
- [ ] Keep semantic, emotional, and momentum score components bounded and trace their contribution without logging raw content.
- [ ] Supply a bounded recent-turn window on every ordinary user turn, enlarge it only for the configured immediate-context marker, and deduplicate recent/retrieved IDs.
- [ ] Test same-user profile boost, shared project visibility, bounded identity contribution, current-input exclusion, and context deduplication.

### Task 8: Trace, safety, and end-to-end verification

**Files:**
- Modify: `core/src/commonMain/kotlin/io/openeden/trace/TraceContracts.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/runtime/MessagePipeline.kt`
- Modify: `server/src/main/kotlin/Routing.kt`
- Test: `core/src/commonTest/kotlin/io/openeden/runtime/RuntimeInvariantTest.kt`
- Test: `core/src/commonTest/kotlin/io/openeden/runtime/RuntimePipelineTest.kt`
- Test: `server/src/test/kotlin/ServerTest.kt`

- [ ] Add affect inference, fallback, influence mapping, relationship load/update, identity affinity, semantic rendering, correction, and reset trace tags.
- [ ] Verify non-blocking boundaries by asserting all analyzer/model/mapping/retrieval calls cross `InferenceExecutor`.
- [ ] Add safety tests proving relationship values cannot alter privacy, authorization, factual constraints, termination safeguards, or generate dependency-seeking behavior.
- [ ] Run `./gradlew allTests`, inspect failures against the approved design, and run the server test suite separately.
- [ ] Run a final repository scan for `BioVector` dimension changes, raw affect coordinate prompt leakage, hardcoded persona behavior, and zero-confidence public-chat wiring.

### Task 9: Final verification and handoff

**Files:**
- Modify: `docs/superpowers/plans/2026-07-12-companion-user-relationship-state-implementation.md`

- [ ] Mark only evidenced steps complete after test output confirms them.
- [ ] Run `git diff --check` and review the final diff for unrelated worktree changes.
- [ ] Report implemented files, test commands/results, and any remaining model-training limitation without claiming unverified behavior.
