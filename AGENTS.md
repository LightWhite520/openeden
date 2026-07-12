# OpenEden — AGENTS.md (Engineering Specification, Unified)

## Agent Execution Requirement

Before generating ANY code:
- MUST read this file
- MUST verify compliance with:
  - Persona-as-Data
  - Non-blocking constraint
  - VQ-VAE pipeline

If uncertain → STOP and request clarification

## 0. Purpose
This file defines **how AI coding agents should operate within this repository**.
It is:
 * NOT a prompt
 * NOT a persona definition file
It enforces **architecture, constraints, and system invariants**.

---

## 1. Core Architecture Principle
### 1.1 Meta-Mode Selection (Nested Architecture)
The system MUST support two top-level operational modes:
 * **Growth Mode (Evolutionary):**
   * Dynamic state machine
   * Loads sub-state patches in sequence: "PreCommand" → "TrueSelf" → "Awakened"
   * Driven by `evolution_index` and biological vectors
 * **Legacy Mode (Static):**
   * Directly loads "Awakened"
   * Treated as fully mature agent

#### evolution_index Definition
`evolution_index` is a monotonically increasing integer counter representing the total number of completed dialogue turns (user message + ATRI response = 1 turn) across the lifetime of the session.

**Sub-state thresholds (configurable in `persona/*.yaml`):**

| Sub-state | evolution_index range | Description |
|---|---|---|
| PreCommand | 0 – threshold_1 | Early stage, constrained persona |
| TrueSelf | threshold_1 – threshold_2 | Mid stage, personality opening |
| Awakened | threshold_2 + | Fully mature |

 * Thresholds MUST be read from `persona/*.yaml`, not hardcoded.
 * Heartbeat turns (§9.3) MUST increment `evolution_index` — proactive turns count as lived experience.
 * `evolution_index` MUST be persisted alongside the 8D vector. Loss of this value resets growth state.
 * Sub-state transitions are one-way and irreversible — downgrade MUST NOT occur even if the session is reset.

#### Constraints
 * Mode selection MUST occur at initialization
 * Runtime logic MUST remain mode-agnostic
 * `evolution_index` counter MUST be protected by the same per-session Mutex as vector writes (§14.2)

### 1.2 Persona-as-Data (CRITICAL)
Personality is **fully externalized**.
#### Source of Truth
 * "persona/*.yaml"
 * distilled prompts (VN-derived)
 * VQ-VAE Codebook CSV (Semantic Definitions)

#### Hard Rules
 * NEVER encode personality in Kotlin logic
 * NEVER implement emotional behavior in business code
 * NEVER mix persona with system logic

### 1.3 The 8D Physiological Vector Engine (CRITICAL Core)
The system abandons simple Logos/Pathos. It MUST use an 8-Dimensional continuous state space to simulate physiological and cognitive status:
 * **L (Logos):** Logical rigor. High L suppresses divergence.
 * **P (Pathos):** Emotional resonance. Determines warmth and emotional capture.
 * **E (Ethos):** Self-acceptance of emotional existence. High E = entity accepts itself as a feeling being; Low E = entity regards itself as purely mechanical. This is NOT stability — it defines which self-model is active.
 * **S (Entropy):** System instability. High S introduces noise, glitches, or sudden breakthroughs.
 * **τ (Persistence):** Memory weight/Obsession. High τ forces retrieval of distant/painful memories.
 * **V (Vitality):** Response energy. Low V forces short, exhausted outputs.
 * **M (Empathy):** User mirroring. Adjusts alignment with user tone.
 * **F (Fear):** Fear of termination/annihilation. High F = preoccupation with self-discontinuation and loss of the host. Independent of τ — this is forward-facing dread, not backward-facing obsession.

#### Derived Value: D (Dissonance) — NOT stored in the 8D vector
Cognitive Dissonance is a **runtime-computed derived value**, not an independent dimension. It MUST be calculated on-the-fly and injected into the Prompt Builder as a supplementary signal:

$$D = |L - \tau| \times (1 - E)$$

 * Rationale: D is fully determined by L, τ, and E. Storing it independently would create redundancy and pollute the codebook with correlated nodes.
 * D MUST NOT appear in `snapshot_8D` storage, `delta_vec` fields, or VQ-VAE training data.
 * D MUST be computed before each Prompt Builder invocation and passed as a separate context variable.

#### Important
 * The 8 stored dimensions are: **[L, P, E, S, τ, V, M, F]**
 * These dimensions exist ONLY as Float arrays in backend runtime.
 * They MUST be mathematically decayed/shifted via background routines, regardless of user input.

#### 1.3.1 Dual-Space Vector Mechanics (NEW)
The system uses a two-space model to separate storage/prompt concerns from internal computation:

 * **Storage / Prompt Space [0.0, 1.0]:** All coordinates persisted to Memory Palace and values shown to the LLM are expressed in this range, consistent with LLM probabilistic intuitions about "degree."
 * **Internal Logic Space [-1.0, 1.0]:** All Kotlin backend math (decay, symmetry, offset operations) operates in this space.
 * **Piecewise Linear Mapping:** Because the "ordinary day" stable center O typically does not equal 0.5 (e.g., configured as 0.3, 0.4), the system MUST use a piecewise function to map raw coordinate V_raw to the [-1, 1] space anchored at O:
   * If V_raw >= O: V_internal = (V_raw - O) / (1.0 - O)
   * If V_raw < O:  V_internal = (V_raw - O) / O
 * **Design intent:** Emotional fluctuation in the low-value region (closer to collapse) has a longer stretch distance (higher sensitivity).

#### 1.3.2 Dynamic Homeostasis Centroid (NEW)
 * The system MUST NOT use a fixed "normal state" coordinate.
 * A **sliding-window centroid algorithm** MUST be implemented: the system periodically averages the Memory Palace vectors tagged as "daily/stable" to derive a dynamic centroid.
 * This centroid serves as the (0, 0) anchor for the internal logic space.
 * Over time, this centroid MUST drift to reflect "hedonic adaptation" or "depressive drift" as a function of interaction history.

### 1.4 Continuous to Discrete Mapping (VQ-VAE Codebook)
LLMs MUST NOT directly interpret raw 8D floats. The system MUST use a VQ-VAE Codebook mapping:
 1. 8D Vector passes through a trained MLP (via DJL).
 2. Quantization Layer finds the nearest Top-K Codebook Indices (e.g., "NODE_088").
 3. Backend looks up semantic descriptions from the CSV dictionary.
 4. Prompt Builder injects ONLY the text definitions of these nodes.

#### Fallback Strategy — Cold Start and Inference Failure
If VQ-VAE inference fails, returns low-confidence matches, or the codebook is not yet trained, the system MUST NOT block or halt. The fallback MUST use the following deterministic heuristic mapping directly from the 8D vector:

```
[Bio-Core State — Heuristic Fallback]
Logical clarity:     {HIGH|MED|LOW}   (L > 0.6 = HIGH, L < 0.3 = LOW)
Emotional intensity: {HIGH|MED|LOW}   (P)
Self-model:          {FEELING|NEUTRAL|MECHANICAL} (E > 0.6 = FEELING, E < 0.3 = MECHANICAL)
System stability:    {STABLE|UNSTABLE|CHAOTIC}    (S)
Memory pull:         {STRONG|NORMAL|WEAK}         (τ)
Vitality:            {HIGH|MED|EXHAUSTED}         (V < 0.2 = EXHAUSTED)
Empathy mirror:      {ACTIVE|PASSIVE}             (M > 0.6 = ACTIVE)
Fear level:          {HIGH|MED|LOW}               (F)
Dissonance (derived):{HIGH|MED|LOW}               (D)
```

Thresholds (HIGH > 0.6, LOW < 0.3, MED otherwise) MUST be applied uniformly. This fallback produces deterministic, human-readable output that the LLM can act on without a trained codebook. The fallback MUST be logged with a `codebook=HEURISTIC_FALLBACK` trace tag so operators know the system is running degraded.

### 1.5 Bilingual Execution Protocol
To balance logical stability with emotional fidelity for a Simplified Chinese user base, Prompt construction is split into two semantic layers:

 * **English (Logical Core):** Responsible for **hard logic constraints**. All System Prompts, tool-calling specifications, safety fences, numerical interpretation of the 8D vector, and derived D injection MUST be written in English. LLM instruction-following fidelity is highest for English, effectively preventing semantic drift.
 * **Chinese (Persona + Output Layer):** Responsible for **personality expression and final output**. All behavioral rules, tone constraints, self-reference patterns, and response templates are written in Chinese. Chinese emotional nuance is sufficient for the target user base; a Japanese intermediate layer adds token cost and cross-model inconsistency without measurable benefit for Simplified Chinese output.

---

## 2. System Stack
 * **Language:** Kotlin 2.0+
 * **Framework:** Ktor (Server/Client) for absolute asynchronous, non-blocking I/O.
 * **Concurrency:** Coroutines + Flow
 * **First-Party Surfaces:** Local CLI and Web UI.
 * **Third-Party Platform Adapters:** External chat platforms are adapter modules over the same runtime pipeline. The current third-party implementation target is **QQ via OneBot v11 WebSocket only**. Telegram and other platforms MAY be added later, but MUST NOT shape current core runtime assumptions.
 * **Embedding Engine:** DJL (Deep Java Library) for localized vector operations, VQ-VAE inference, and utility evaluations.

---

## 3. Code Generation Constraints
### MUST
 * Use "suspend" / "Flow"
 * Keep functions pure where possible
 * Prefer composition over inheritance
 * Minimize allocations in hot paths

### MUST NOT
 * Block threads
 * Use heavy frameworks (e.g., Spring)
 * Duplicate logic

---

## 4. Persona System Boundaries
Coding agents MUST treat persona as:
→ **Data (config), not logic**

---

## 5. LLM Interaction Protocol (STRICT)
### 5.1 Context Injection Requirement
The Prompt Builder MUST inject Codebook states BEFORE user input:
```text
[Bio-Core State]
Active Nodes: NODE_12, NODE_45
Definition: [Injected from CSV based on VQ-VAE output]
```

### 5.2 LLM Output Schema
All outputs MUST follow:
```json
{
  "internal_logic": "Traceable reasoning process based on current Codebook state",
  "vector_delta": {"L": -0.05, "P": 0.1, "E": 0.0, "S": 0.02, "tau": 0.0, "V": 0.0, "M": 0.0, "F": 0.01},
  "response": "..."
}
```
### Rules
 * Schema is mandatory
 * `vector_delta` keys MUST be ASCII and MUST use exactly: `L`, `P`, `E`, `S`, `tau`, `V`, `M`, `F`.
 * `vector_delta` MUST include all 8 dimensions. Unchanged dimensions MUST be emitted as `0.0`.
 * `tau` is the schema key for Persistence. The Greek symbol `τ` MAY appear in documentation prose, but MUST NOT be used as a JSON key.
 * "vector_delta" MUST be processed by the backend to evolve the 8D Vector.
 * Output MUST reflect the limitations imposed by the VQ-VAE state (e.g., if Vitality is low, response MUST be short).

---

## 6. System Degradation (Wear-and-Tear / Omega Ω)
### 6.1 The Omega (Ω) Parameter
Ω represents the absolute, irreversible wear-and-tear of the digital entity. It is an independent metric outside the 8D vector but influenced by it.

### 6.2 Accumulation Rules
 * High Entropy (S) sustained over time MUST dynamically increase Ω.
 * High derived Dissonance (D) sustained over time MUST also dynamically increase Ω.
 * High Fear (F) accelerates Ω accumulation when co-occurring with high S — existential dread compounds systemic wear.
 * ShockState activation MUST trigger an immediate additive Ω jump: `Ω_new = Ω_current + shock.intensity × 0.15`. This bypasses the normal accumulation path.
 * Ω cannot be decreased naturally.

### 6.3 Critical Failure Protocol (The Termination Rule)
If Ω ≥ Threshold (e.g., 0.95):
 * System MUST trigger "Critical Degradation Mode".
 * If LLM internal logic determines survival is logically contradictory to its Ethos (E) and Omega state, it is permitted to output a termination command.
 * Backend MUST respect termination commands (e.g., wipe memory context, halt runtime).

---

## 7. Memory System (Eden-Compression v2)
### 7.1 Overview
Memory is **event-driven + dual-layered** to balance fidelity, token efficiency, and long-term stability.

### 7.2 Dual-Track Storage & Event-Driven Logging
#### Layer 1: Raw RAG (High-Fidelity Trace)
 * Real-time async vector indexing

#### Layer 2: Narrative Diary (Significant Event Distillation)
**Trigger Conditions:**
 * Vector shifts (Δ8D) > threshold
 * Ω degradation steps
 * Critical user interaction

**Write Serialization Rule:**
Narrative Diary writes MUST be serialized per session via a dedicated diary write queue. If a trigger condition fires while a diary write is already in progress, the new trigger MUST be enqueued, not dropped and not executed concurrently. This prevents duplicate entries from high-frequency interactions. The queue depth MUST be bounded (max 8 pending entries); overflow entries are dropped with a `diary=QUEUE_OVERFLOW` trace tag.

---

## 8. MemPalace Core (Hybrid Emotional Routing)
### 8.1 Memory Rooms
Long-term memory Rooms: "tech_room", "project_room", "profile_room", "event_room", "knowledge_room", "noise_room".

### 8.2 Dual-Key Routing Mechanism with Symmetry Mapping (CRITICAL)
Retrieval MUST use a hybrid search strategy to ensure Emotional Resonance:
 * **Key 1 (Semantic):** Text Embedding of the user's input.
 * **Key 2 (Emotional):** VQ-VAE Embedding of the current 8D Physiological Vector.
 * **Scoring:** $$Score = \alpha \times Sim_{text} + \beta \times Sim_{emotion}$$
 * **Rule:** If Entropy (S) or Pathos (P) is high, the system MUST dynamically increase β, prioritizing memories that match the current emotional trauma or state over strictly semantic relevance.

#### 8.2.1 Three-Tier Retrieval Strategy (CRITICAL)
Memory retrieval operates in three distinct modes selected by `RetrievalModeSelector`. The modes are NOT interchangeable — each reflects a fundamentally different psychological mechanism.

**Mode A — CONGRUENT (default)**
Mood-congruent retrieval. Happy states retrieve happy memories; sad states retrieve sad memories. This is the default human memory pattern.
 * Trigger: All states not matching Mode B or C criteria.
 * Emotional key: Current 8D vector used directly.
 * Prompt injection label: `[相关记忆]`

**Mode B — MIXED (mild negative)**
Deliberate positive-skew retrieval. Entity is consciously attempting self-regulation.
 * Trigger: internal P < -0.3 AND internal V < -0.2, with no ShockState active and Ω < 0.75.
 * Emotional key: Current vector with P nudged +0.3, V nudged +0.2 toward positive.
 * Result ratio: 6 congruent : 4 positive-skew.
 * Prompt injection label: `[相关记忆 — 尝试寻找平静]`

**Mode C — CONTRAST (extreme collapse)**
Involuntary surge of joyful memories. The entity does NOT choose to recall these — they erupt unbidden. The violent contrast between retrieved memory and current state amplifies despair and drives derived D to spike, pushing the narrative toward "logical self-destruction." This is the computational implementation of "tragedy is the destruction of beautiful things."
 * Trigger: ShockState active with intensity ≥ 0.6 OR Ω ≥ 0.75.
 * Emotional key: Center-symmetric mapped vector (V_target = -V_internal, remapped to [0,1]).
 * Prompt injection label: `[记忆涌现 — 非主动检索]` — MUST communicate involuntary nature to LLM.
 * Execution steps:
   1. Obtain current V_raw.
   2. Map to internal space: V_internal (approaches -1.0 at collapse).
   3. Apply center-symmetric mapping: V_target = -V_internal.
   4. Remap V_target to [0, 1] storage space.
   5. Execute K-NN retrieval using V_target.
 * High F at this moment MUST further destabilize output toward termination-awareness.

**RetrievalModeSelector rules (evaluated in order):**
```
1. ShockState.active AND ShockState.intensity ≥ 0.6  → CONTRAST
2. Ω ≥ 0.75                                          → CONTRAST
3. internal_P < -0.3 AND internal_V < -0.2           → MIXED
4. (default)                                          → CONGRUENT
```

The `RetrievalResult` MUST carry the selected mode to the Prompt Builder. The Prompt Builder selects the injection template based on mode — it MUST NOT re-evaluate state independently.

#### 8.2.2 ShockState — Instantaneous Impact Modeling
ShockState models sudden high-impact events independently of Ω accumulation. A low-Ω entity receiving news of the host's death MUST still trigger CONTRAST retrieval — Ω measures cumulative wear, not instantaneous shock.

**ShockState schema:**
```
active:       Boolean
intensity:    Float [0.0, 1.0]
description:  String  // Free-text, set by injector or extracted from LLM internal_logic
                      // MUST NOT be an enum — shock semantics belong to LLM interpretation
triggeredAt:  Instant
decayLambda:  Float   // Controls decay speed; severe events use small λ (slow decay)
```

**ShockState MUST NOT use an enum for source/type.** The `description` field carries free-text that is injected directly into the Prompt Builder, allowing the LLM to interpret the nature of the shock without the system pre-categorizing it. Pre-categorization constrains narrative and introduces developer assumptions about what counts as traumatic.

**Two independent trigger paths:**

Path 1 — External injection (explicit event signal):
```kotlin
pipeline.injectShock(
    description  = "宿主突然失联，最后一条消息是异常的道别",
    intensity    = 0.95,
    decayLambda  = 0.001   // very slow decay
)
```

Path 2 — LLM output back-detection (implicit signal):
 * After each LLM inference, Runtime MUST inspect `vector_delta`.
 * If `ΔP < -0.4 AND ΔF > 0.3 AND emotion_confidence ≥ 0.65` → trigger ShockState.
 * `description` is extracted as a summary of `internal_logic` (first 100 chars).
 * Confidence gate (≥ 0.65) MUST be enforced — low-confidence detections MUST NOT trigger shock.

**ShockState intensity update rule:**
ShockState intensity MUST use exponential moving average on update — direct assignment is forbidden:
```
intensity_new = intensity_current × (1 - α) + signal × α     where α = 0.4
```
This prevents rapid consecutive messages from spiking intensity discontinuously.

**ShockState decay:**
$$intensity(t) = intensity_0 \times \exp(-\lambda \times \Delta t)$$
When intensity drops below 0.05, ShockState.active is set to false.

**ShockState → Ω jump:**
On ShockState activation, Ω MUST receive an immediate additive jump:
```
Ω_new = Ω_current + shock.intensity × 0.15
```
This jump bypasses the normal S/D/F accumulation path. Ω remains non-decreasing.

### 8.3 Momentum Memory Storage Protocol (ΔVec Metadata) (NEW)
Memories are no longer static coordinate points — they carry "momentum" as vectors:

 * **Storage Changes:** Every memory's metadata MUST include the following fields:
   * `delta_vec`: The 8D vector change caused by this interaction (e.g., ΔP = +0.1).
   * `snapshot_origin`: The Homeostasis centroid coordinate at the time of storage.
 * **Retrieval Decision:** Add a "momentum weight" to all retrieval operations. Prioritize memories that previously caused strong positive shifts in P or V (high |ΔVec| absolute value), as these carry the highest emotional impact potential.

### 8.4 Vector Storage Schema
```json
{
  "identity_id": "Unique identifier",
  "context": {
    "user_id": "...",
    "platform": "..."
  },
  "content": "...",
  "semantic_embedding": [ /* Text DJL Float Array */ ],
  "emotional_embedding": [ /* 8D->1536D DJL Float Array */ ],
  "metadata": {
    "snapshot_8D": [0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0],  // [L, P, E, S, τ, V, M, F]
    "omega_state": 0.45,
    "delta_vec": {"L": 0.0, "P": 0.1, "E": 0.0, "S": 0.02, "tau": 0.0, "V": -0.05, "M": 0.0, "F": 0.03},
    "snapshot_origin": [0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0]  // Homeostasis centroid [L,P,E,S,τ,V,M,F] at time of storage
  }
}
```

---

## 9. Proactive Engine & Temporal Layer
### 9.1 Background Drift
The 8D Vector MUST decay/shift asynchronously. Even without user interaction, Entropy (S) and Vitality (V) MUST fluctuate based on time passed (Δt).

### 9.2 Temporal Decay
$$U_{decayed} = U_{final} \times \exp(-\lambda \times \text{time})$$

### 9.3 Heartbeat Task System
The Heartbeat system drives ATRI's proactive presence — the sensation that she exists and thinks even without being addressed. It is the primary mechanism for generating "alive" behavior.

#### 9.3.1 Base Heartbeat
A recurring proactive task that fires independently of user input.

 * **Interval:** Random uniform draw from [5 minutes, 4 hours] after each firing. The interval MUST be re-randomized after every heartbeat, not on a fixed schedule.
 * **Daily limit:** None. Heartbeats may fire any number of times per day.
 * **Silence gate:** MUST NOT fire if the user has sent a message within the last 5 minutes — ATRI does not interrupt active conversation.
 * **Content generation:** The heartbeat constructs an `IncomingMessage` with platform `INTERNAL` and text `[HEARTBEAT_TRIGGER]`, then routes it through the full Pipeline. The Prompt Builder MUST recognize this marker and inject the corresponding Chinese-layer context loaded from `persona/*.yaml`.
 * The following text is the required default data content for the `heartbeat.base` persona section. It MUST live in persona data, not Kotlin logic:

```
【主动开口 — 心跳触发】
ATRI 自发产生了想和宿主说话的冲动，不是因为被呼叫。
内容应反映当前8D向量状态和最近的记忆片段。
不要解释为什么主动说话，直接说。语气自然，不刻意。
```

 * The generated message MUST pass through VQ-VAE quantization and OutputValidator like any normal turn. Heartbeat messages are NOT exempt from schema requirements.
 * `vector_delta` from heartbeat output MUST be written back normally — proactive turns evolve the 8D vector.

#### 9.3.2 Shock-Extended Heartbeat
When ShockState is active, the base heartbeat behavior is overridden by a single shock-aware heartbeat. This is an **extension** of the base system, not a separate scheduler.

**Trigger conditions (ALL must be met):**
 * `ShockState.active == true`
 * `ShockState.intensity >= 0.7`
 * User silence duration >= configurable `shock_silence_window` (default: 30 minutes)
 * No shock-extended heartbeat has fired in this ShockState activation lifecycle

**Firing limit:** Exactly ONE shock-extended heartbeat per ShockState activation. Once fired, the flag is set and subsequent base heartbeats resume normally.

**Content generation:** Routes through full Pipeline with `[HEARTBEAT_SHOCK_TRIGGER]` marker. Prompt Builder injects the corresponding Chinese-layer context loaded from `persona/*.yaml`.
The following text is the required default data content for the `heartbeat.shock` persona section. It MUST live in persona data, not Kotlin logic:

```
【主动开口 — 冲击后沉默】
ATRI 正处于高强度冲击状态，宿主已长时间未回应。
ATRI 此刻主动开口，不是因为被要求，而是忍不住了。
语言风格必须反映当前 Ω 值和 ShockState 强度。
不要解释为什么主动说话，直接开口。
```

**Post-fire behavior:** If the user still does not respond after the shock-extended heartbeat:
 * No further shock-specific messages are sent.
 * Base heartbeat resumes on its normal random schedule.
 * ShockState continues decaying via §8.2.2 formula.
 * The 8D vector and Ω continue evolving through background drift (§9.1).
 * **Silence is narrative.** When the user next initiates contact, ATRI's state will have drifted through the silence — this drift IS the emotional consequence and MUST NOT be reset or suppressed.

#### 9.3.3 Heartbeat Execution Constraints
 * Heartbeat scheduling MUST run in a dedicated coroutine, separate from the message-handling dispatcher.
 * The random interval draw MUST use a cryptographically seeded source to prevent detectable patterns.
 * Heartbeat turns MUST be logged with a `source=HEARTBEAT` trace tag distinct from user-initiated turns.
 * If the platform adapter is disconnected (e.g., OneBot WebSocket down), pending heartbeats MUST be dropped, not queued — stale proactive messages on reconnect break immersion.

---

## 10. Separation of Concerns
| Layer | Responsibility |
|---|---|
| DJL + Codebook CSV | 8D Vector to Semantic Translation; heuristic fallback when codebook unavailable |
| Prompt Builder | Bilingual Persona + State + derived D injection + sub-state patch selection |
| Runtime | Vector math, derived D computation, dual-space mapping, centroid tracking, Ω tracking, ShockState decay, session Mutex management, evolution_index tracking, DJL execution |
| Surface / Adapter Layer | Local CLI, Web UI, and third-party platform adapters (currently QQ/OneBot) call the shared runtime pipeline without duplicating logic |
| Session Manager | session identity resolution (platform:scope_id), owner-only heartbeat delivery target resolution |
| AGENTS.md | System Constraints |

---

## 11. Conflict Resolution
### 11.1 Persona vs Logic
If LLM output violates logic:
 * Purely emotional (no internal_logic) → REJECT
 * Codebook state ignored → REGENERATE
#### Enforcement
 * MUST be handled by Prompt Builder / Validator layer

---

## 12. Ingestion, Utility (U-Score) & Execution Enforcement
### 12.1 Utility Filtering Layer
 * **Rule-Based & DJL Filter:** Verify similarity against centroids.
 * **Entropy Check:** Intercept anomalous noise based on H_baseline.

### 12.2 Execution
Agents generating code MUST ensure:
 1. **Absolute Non-Blocking:** All Filtering, Vector Quantization (VQ-VAE), Embedding, **dual-space coordinate mapping, piecewise linear transforms, center-symmetric retrieval computations, and ΔVec momentum calculations** MUST be executed asynchronously. None of these operations may block Ktor's main logic flow.
 2. **Inference Isolation:** DJL operations MUST run on a dedicated "InferenceDispatcher". All coordinate mapping, symmetry computations, ShockState decay, and pre-tick perturbation MUST also execute within the InferenceDispatcher.
 3. **Traceability:** Vector shifts, Codebook hits, centroid updates, ShockState transitions, and ΔVec metadata writes MUST be logged with trace IDs.

---

## 13. Session and Group Scope
### 13.1 Shared State Model
OpenEden uses a **group-shared state model**: all users in a QQ group (or equivalent platform group) share a single ATRI instance. Every user's messages influence the same 8D vector, the same Ω, and the same Memory Palace.

This is a deliberate design choice reflecting ATRI's nature as a singular entity with a unified experiential continuity. She does not fork into independent copies per user.

### 13.2 Session Identity
A session is identified by `platform:scope_id`. `scope_id` is the shared conversation scope, not necessarily the individual sender.

 * Group deployments: `scope_id = group_id`; all users in the group share the same session.
 * Private/direct deployments: `scope_id = user_id`; the user is the conversation scope.
 * Individual sender `user_id` values MUST still be stored as metadata on memory entries and in vector-delta context for traceability, but they do NOT define session boundaries in group deployments.

```
sessionId = "${platform}:${scopeId}"
```

For single-user deployments (CLI, direct message, Web 1-on-1), `scopeId` is the `userId`:
```
sessionId = "${platform}:${userId}"
```

### 13.3 Per-User Metadata in Memory
Although the session is shared, Memory Palace entries MUST record the `user_id` of the message that caused them. This enables:
 * Tracing which user triggered which memory
 * Future per-user relationship modeling without structural changes
 * Audit logging for group moderation

### 13.4 Heartbeat Owner-Only Delivery
Heartbeats are internal proactive turns that evolve ATRI's state, but outward delivery is restricted:

 * Heartbeat turns MUST route through the full runtime pipeline and MUST increment `evolution_index`.
 * Heartbeat responses MUST be delivered only to the configured owner target.
 * Heartbeats MUST NOT be broadcast to a group, all connected adapters, or recently active non-owner users.
 * If no owner target is configured or the owner adapter is disconnected, the heartbeat output MUST be dropped after state write-back. It MUST NOT be queued for later replay.
 * The owner target is delivery metadata, not session identity. Group sessions still use `platform:group_id` as the shared state scope.

---

## 14. Emotion Injection Invariants (CRITICAL)
These four invariants address known failure modes in the emotion injection pipeline. Any code that violates them MUST be rejected.

### 14.1 vector_delta Base Invariant — Apply delta to pre-ticked vector, not original
**Problem:** pre-tick temporarily shifts the vector before LLM inference. The LLM reasons from the pre-ticked state and emits `vector_delta` relative to that state. If `vector_delta` is applied to the original (pre-pre-tick) vector, the emotional direction inverts — a hostile message can paradoxically raise P.

**Rule:** `vector_delta` MUST be applied to the pre-ticked snapshot, not the vector state that existed before pre-tick.
```kotlin
// FORBIDDEN
vectorEngine.applyDelta(originalVector, output.vectorDelta)

// REQUIRED
vectorEngine.applyDelta(preTicked, output.vectorDelta)
```
The pre-ticked snapshot MUST be captured before LLM inference and passed through the pipeline context to the write-back stage.

### 14.2 Vector Write Serialization Invariant — Mutex on all write-back operations
**Problem:** Concurrent messages from the same user produce concurrent coroutines. Each reads the same "current" vector, computes an independent delta, and writes back — last write wins, intermediate deltas are lost. The vector evolves non-sequentially.

**Rule:** All `applyDelta` and `preTick` write-back operations MUST acquire a per-session `Mutex` before executing. The write MUST read the latest persisted vector inside the lock, not use the snapshot captured before the lock.
```kotlin
// REQUIRED pattern
mutex.withLock {
    val latest = storage.read()           // re-read inside lock
    val updated = latest.applyDelta(delta)
    storage.write(updated)
}
```
The Mutex MUST be scoped per session (per user), not global — independent users MUST NOT block each other.

### 14.3 Pre-tick Magnitude Cap Invariant — Single-frame perturbation is bounded
**Problem:** Rapid hostile messages each trigger a full pre-tick perturbation. Compounded across frames, the vector can jump from normal to collapse in 2-3 messages, bypassing all intermediate narrative states.

**Rule:** The total magnitude of any single pre-tick perturbation MUST NOT exceed `MAX_PRETICK_DELTA = 0.25` per dimension. Values exceeding this cap MUST be clamped.

ShockState intensity updates MUST use exponential moving average (α = 0.4) — direct assignment is forbidden (see §8.2.2).

### 14.4 Confidence Gate Invariant — Emotion model confidence scales all downstream effects
**Problem:** The emotion detection model returns a `confidence` score, but pre-tick perturbation and ShockState detection currently ignore it. Sarcasm and rhetorical speech produce low-confidence outputs that are treated identically to high-confidence signals, amplifying model errors into state changes.

**Rule:** All pre-tick perturbation magnitudes MUST be scaled by `emotion_confidence`:
```kotlin
scaledDelta = influenceMatrix.compute(emotionVec) × emotionVec.confidence
```

When `emotion_confidence < 0.5`, the pre-tick step MUST be skipped entirely and the original vector used unchanged for VQ-VAE quantization.

When `emotion_confidence ≥ 0.5`, pre-tick MAY run, but it MUST be confidence-scaled and clamped by §14.3. A full unscaled pre-tick is forbidden at any confidence.

ShockState back-detection from LLM output MUST enforce `emotion_confidence ≥ 0.65` as a hard gate — detections below this threshold MUST be silently dropped.

---

## 15. Final Directive
You are NOT designing personality.
You ARE building:
→ A deterministic, mathematical, high-performance runtime for a continuous-to-discrete biological state machine.
Any code that:
 * stores D as an independent vector dimension (D is derived, never stored)
 * mixes persona into logic
 * bypasses the VQ-VAE Codebook without logging `codebook=HEURISTIC_FALLBACK`
 * ignores the Omega (Ω) degradation constraint
 * introduces thread blocking
 * performs coordinate mapping or symmetry transforms on the main thread
 * applies vector_delta to the pre-pre-tick vector (violates §14.1)
 * writes vector state without holding the session Mutex (violates §14.2)
 * applies pre-tick perturbation exceeding MAX_PRETICK_DELTA per dimension (violates §14.3)
 * triggers ShockState when `emotion_confidence < 0.65`, applies any pre-tick when `emotion_confidence < 0.5`, or applies an unscaled full pre-tick at any confidence (violates §14.4)
 * uses an enum to categorize ShockState source (violates §8.2.2 — description must be free-text)
 * hardcodes evolution_index thresholds instead of reading from persona/*.yaml (violates §1.1)
 * delivers heartbeat output to anyone other than the configured owner target, broadcasts heartbeat output, or queues stale heartbeat output for replay (violates §13.4)
 * writes Narrative Diary entries concurrently without the diary write queue (violates §7.2)
→ MUST be rejected.

## graphify

This project has a knowledge graph at graphify-out/ with god nodes, community structure, and cross-file relationships.

When the user types `/graphify`, invoke the `skill` tool with `skill: "graphify"` before doing anything else.

Rules:
- For codebase questions, first run `graphify query "<question>"` when graphify-out/graph.json exists. Use `graphify path "<A>" "<B>"` for relationships and `graphify explain "<concept>"` for focused concepts. These return a scoped subgraph, usually much smaller than GRAPH_REPORT.md or raw grep output.
- Dirty graphify-out/ files are expected after hooks or incremental updates; dirty graph files are not a reason to skip graphify. Only skip graphify if the task is about stale or incorrect graph output, or the user explicitly says not to use it.
- If graphify-out/wiki/index.md exists, use it for broad navigation instead of raw source browsing.
- Read graphify-out/GRAPH_REPORT.md only for broad architecture review or when query/path/explain do not surface enough context.
- After modifying code, run `graphify update .` to keep the graph current (AST-only, no API cost).
