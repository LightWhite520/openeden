# OpenEden Companion User And Relationship State Design

Date: 2026-07-12
Status: Approved design
Scope: User-affect observation, per-user relationship state, prompt integration,
and identity-aware memory retrieval for long-term companion behavior.

## 1. Objective

Add the missing user-understanding and long-term relationship layers required
for a long-term companion experience without changing the existing eight
dimensional `BioVector`, weakening the VQ-VAE boundary, or moving persona
behavior into Kotlin.

The completed runtime must distinguish three concepts:

```text
ATRI internal state     Existing BioVector [L, P, E, S, tau, V, M, F]
Observed user state     New UserAffectState [V, A, D, N, O, Q]
Relationship state      New RelationshipState [T, F, S, B, X]
Response expression     Derived for the current turn; not a persisted vector
```

The existing `BioVector` remains the only input to the current emotional
embedding and VQ-VAE Codebook pipeline. The new states supplement it; they do
not expand, reinterpret, or retrain it.

## 2. Architecture Decision

Use two new state ports beside the existing session state:

- `UserAffectAnalyzer` observes the current user message and returns a
  confidence-bearing `UserAffectState`.
- `RelationshipStateStore` persists slow per-user relationship state keyed by
  `(sessionId, userId)`.

Do not add user or relationship coordinates to `BioVector`, `VectorDelta`,
`snapshot_8D`, emotional embeddings, Codebook training data, homeostasis, or
Omega calculations.

Do not persist a separate AI expression vector. The current turn's response
stance is derived from Codebook semantics, observed user state, relationship
state, persona data, and safety constraints. The Prompt Builder injects these
inputs, while behavioral interpretation remains in `persona/*.yaml`.

This design preserves the current responsibility boundaries:

| Layer | Responsibility |
|---|---|
| Adapter | Resolve platform, scope, and sender identity. |
| User-affect analyzer | Infer the user's current state with confidence. |
| Runtime | Orchestrate state reads, updates, tracing, and prompt inputs. |
| Relationship reducer | Apply bounded deterministic relationship changes. |
| State stores | Persist session state and per-user relationship state. |
| Prompt Builder | Serialize semantic state without inventing behavior. |
| Persona YAML | Define tone, boundaries, and response behavior. |

## 3. User Affect State

The minimum user state is:

```kotlin
data class UserAffectState(
    val valence: Float,
    val arousal: Float,
    val dominance: Float,
    val connectionNeed: Float,
    val openness: Float,
    val confidence: Float,
)
```

All fields use `[0.0, 1.0]` storage values. Their meanings are:

- `valence`: negative to positive affect.
- `arousal`: calm or depleted to activated or agitated.
- `dominance`: powerless or constrained to in-control.
- `connectionNeed`: preference for space to desire for companionship.
- `openness`: guarded to willing to disclose.
- `confidence`: reliability of the complete observation.

Emotion labels such as sadness, anger, loneliness, and anxiety are derived
semantic descriptions, not independent coordinates.

The analyzer is a `suspend` port and runs through `InferenceExecutor`. It may be
backed by a local classifier or a structured LLM call, but the core runtime
depends only on the port. Empty input, model failure, invalid numbers, or low
confidence returns an explicit uncertain result and never blocks the turn.

The first production milestone must replace the current public-chat behavior
that supplies `emotionConfidence = 0.0`. Until an analyzer is configured, the
runtime continues with an uncertain observation and no user-driven pre-tick.

## 4. Relationship State

The minimum relationship state is:

```kotlin
data class RelationshipState(
    val sessionId: String,
    val userId: String,
    val trust: Float,
    val familiarity: Float,
    val safety: Float,
    val boundarySensitivity: Float,
    val unresolvedTension: Float,
    val evidenceCount: Long,
    val updatedAtMs: Long,
)
```

All continuous fields use `[0.0, 1.0]`. The coordinates mean:

- `trust`: accumulated evidence that interaction is reliable and respectful.
- `familiarity`: knowledge of the user's preferences and interaction habits.
- `safety`: evidence that the relationship permits open interaction without
  judgment or boundary violations.
- `boundarySensitivity`: the current need for distance and cautious wording.
- `unresolvedTension`: unrepaired misunderstanding, disappointment, or conflict.

An independent intimacy coordinate is excluded from the first release. It is
strongly correlated with trust and familiarity, is difficult to label, and can
encourage manipulative dependency behavior without adding a clear runtime
decision boundary.

Relationship updates are slow, bounded, and evidence-driven. Message count may
increase familiarity slightly but cannot directly increase trust or safety.
Negative evidence may increase `boundarySensitivity` or `unresolvedTension`
faster than positive evidence reduces them. Repair requires acknowledgment and
subsequent consistent interaction; a new session or process restart cannot
reset tension automatically.

## 5. Identity And Session Semantics

OpenEden intentionally gives a group one shared ATRI instance. The existing
`SessionState` and `BioVector` therefore remain keyed by `sessionId`.

Relationship state is different: it must be keyed by `(sessionId, userId)`.
Using only `sessionId` would make every member of a group share the same trust,
boundaries, and unresolved tension. Using only `userId` would incorrectly merge
the same identity across unrelated deployment scopes.

The state key is:

```text
sessionId = platform + ":" + scopeId
relationship key = sessionId + userId
```

Heartbeat turns have no observed user state and do not update any relationship
record. They continue to evolve only ATRI's shared state and `evolution_index`.

## 6. Turn Data Flow

For a user-initiated turn:

1. Resolve `sessionId` and `userId` at the adapter boundary.
2. Acquire the existing per-session coroutine Mutex.
3. Read the latest `SessionState` and `(sessionId, userId)` relationship state.
4. Run `UserAffectAnalyzer` through `InferenceExecutor`.
5. Convert the observation into a confidence-scaled `EmotionSignal` for the
   existing pre-tick path. Skip pre-tick below the existing confidence gate.
6. Quantize only the pre-ticked `BioVector` through the current VQ-VAE pipeline.
7. Retrieve recent conversation history and identity-aware long-term memories.
8. Build the prompt from Codebook semantics, user-affect semantics,
   relationship semantics, retrieved memories, and persona data.
9. Validate the LLM output and commit the existing 8D state as today.
10. Derive bounded relationship evidence from the validated turn and update the
    relationship record within the same session turn gate.
11. Write raw memory with the sender identity and relationship evidence summary.
12. Release the gate, publish asynchronous Diary work, and return the response.

Relationship updates occur only after valid LLM output. Rejected turns do not
silently improve or damage the relationship. Storage failure prevents the
relationship update from being reported as committed and emits structured
trace data; it does not fabricate an in-memory success.

## 7. Mapping User Affect To ATRI Pre-Tick

`UserAffectState` and `VectorDelta` are not the same domain model. A separate
`UserAffectInfluenceMapper` produces the proposed ATRI influence delta.

The mapper is deterministic and configurable. It must preserve all existing
emotion-injection invariants:

- The full delta is scaled by `UserAffectState.confidence`.
- Confidence below `0.5` skips pre-tick.
- Every coordinate is capped by `MAX_PRETICK_DELTA`.
- The LLM delta is applied to the pre-ticked snapshot.
- All state writes remain inside the per-session Mutex.
- Shock back-detection continues to require confidence of at least `0.65`.

The mapper contains mechanics, not personality. It may model facts such as high
negative valence and arousal increasing Pathos or Entropy pressure. It must not
encode persona-specific wording, attachment behavior, jealousy, reassurance
scripts, or self-reference. Those remain persona data.

## 8. Persistence

Add a dedicated table rather than extending `session_state`:

```text
relationship_state
  session_id TEXT NOT NULL
  user_id TEXT NOT NULL
  trust REAL NOT NULL
  familiarity REAL NOT NULL
  safety REAL NOT NULL
  boundary_sensitivity REAL NOT NULL
  unresolved_tension REAL NOT NULL
  evidence_count INTEGER NOT NULL
  updated_at_ms INTEGER NOT NULL
  PRIMARY KEY(session_id, user_id)
```

The core owns a `RelationshipStateStore` interface. The server module owns its
SQLDelight implementation. An in-memory implementation supports common tests.

Relationship state is not part of the VQ-VAE artifact, emotional embedding,
homeostasis centroid, or `SessionState`. Keeping it in a separate table allows
future reset, export, user correction, and retention policies without rewriting
ATRI's biological history.

User affect is normally turn-scoped and is not persisted as a mutable current
state. The raw memory for a turn may retain its semantic affect summary,
confidence, and relationship evidence for auditing and future training. It must
not store unsupported diagnoses or hidden sensitive-category claims.

## 9. Prompt Integration

Extend `PromptInput` with semantic domain objects rather than raw arbitrary
maps. The rendered system document gains two fields before user input:

```text
observed_user_state
  valence: NEGATIVE
  arousal: HIGH
  dominance: LOW
  connection_need: HIGH
  openness: MEDIUM
  confidence: MEDIUM

relationship_context
  familiarity: HIGH
  trust: MEDIUM
  safety: HIGH
  boundary_sensitivity: LOW
  unresolved_tension: LOW
```

Raw coordinates are not injected. A deterministic semantic renderer uses
uniform LOW, MEDIUM, and HIGH thresholds and explicitly renders UNKNOWN when
confidence is insufficient.

The English logical system layer defines that these values are observations,
not facts or diagnoses. It requires cautious wording at low confidence and
allows the user to correct the observation. Chinese persona YAML defines how
the current persona responds to these semantics. Kotlin must not contain
hardcoded reassurance, humor, attachment, or intimacy policies.

## 10. Memory Retrieval And Recent Context

`RetrievalRequest` must carry `userId`. Retrieval retains the shared-session
memory model but adds bounded identity affinity:

```text
score =
  alpha * semanticSimilarity +
  beta  * emotionalSimilarity +
  gamma * momentumImpact +
  identityAffinity +
  recencyAdjustment
```

Identity affinity is room-aware:

- Same-user `PROFILE_ROOM` memories receive the strongest positive boost.
- Same-user event memories receive a smaller boost.
- Shared project and knowledge memories remain session-scoped.
- Different-user memories are not globally filtered because group history is
  intentionally shared.

Every ordinary user turn receives a small bounded window of recent turns.
Keyword detection such as "刚刚" may enlarge that window but cannot be the only
condition for immediate conversational context. Current input is never included
as a previous turn. The Prompt Builder deduplicates recent and retrieved memory
IDs and enforces a single context budget.

## 11. Relationship Evidence And Safety

Relationship state changes from explicit, auditable evidence categories such
as respected preference, corrected misunderstanding, repeated consistency,
boundary request, boundary violation, conflict, and repair. Evidence categories
are runtime data, not emotional prose.

The following behaviors are forbidden regardless of relationship state:

- Guilt, jealousy, threats to leave, or punishment for user absence.
- Pressure to replace real-world relationships with OpenEden.
- Treating inferred emotion as diagnosis or undisputed fact.
- Increasing trust or intimacy merely because the user sends more messages.
- Letting relationship state change factual accuracy, privacy, tool authority,
  safety policy, or termination safeguards.

Users must eventually be able to inspect, correct, reset, or disable inferred
user and relationship state. The first milestone may expose this through an
internal API, but the store and trace model must not prevent later user-facing
controls.

## 12. Tracing And Degradation

Add structured stages and tags for:

- user-affect inference start, result, confidence, and fallback;
- user-affect to pre-tick mapping;
- relationship state load and committed update;
- identity-affinity contribution during retrieval;
- prompt semantic rendering;
- user correction or relationship reset.

Traces store bounded semantic labels and numeric confidence, not full private
messages, complete prompts, diagnoses, or unconstrained model reasoning.

Analyzer failure degrades to UNKNOWN user affect. Relationship store failure
does not fall back to a fabricated default write; the turn may continue with a
read-only neutral relationship context and an explicit degraded trace tag.
Existing VQ-VAE fallback behavior is unchanged.

## 13. Alternatives Rejected

### Expand `BioVector`

Rejected because it mixes ATRI and user domains, changes every stored vector,
invalidates existing Codebook and embedding artifacts, and violates the fixed
8D architecture.

### Persist one large companion vector

Rejected because fast user emotion, slow relationship evidence, and AI internal
state have different ownership, update rates, reset semantics, and confidence.

### Persist an AI expression vector

Rejected for the first release because existing Codebook semantics plus persona
data already control expression. A second persisted AI vector would duplicate
Pathos, Vitality, Empathy, Logos, and Entropy while creating synchronization
problems.

### Store relationship state only in Memory Palace prose

Rejected because retrieval is nondeterministic and cannot guarantee monotonic,
auditable, correctable relationship state.

## 14. Implementation Milestones

1. Add domain contracts, semantic rendering, deterministic influence mapping,
   and common tests without changing persistence.
2. Add `UserAffectAnalyzer` wiring so public chat no longer always supplies
   zero confidence; retain an explicit UNKNOWN fallback.
3. Add SQLDelight relationship persistence keyed by `(sessionId, userId)` and
   restart-continuity tests.
4. Inject user and relationship semantic contexts into the Prompt Builder while
   keeping all behavior in persona YAML.
5. Add identity-aware memory ranking and unconditional bounded recent context.
6. Add correction/reset controls, trace coverage, and end-to-end evaluation.

Each milestone must preserve the current eight-dimensional VQ-VAE path and
leave the repository buildable.

## 15. Verification

Required verification includes:

- Analyzer tests for empty input, low confidence, model failure, NaN, Infinity,
  and deterministic fallback.
- Property tests proving all influence deltas are confidence-scaled and capped.
- Regression tests proving public chat no longer hardcodes effective emotion
  confidence to zero when an analyzer is configured.
- Prompt golden tests for UNKNOWN, low-confidence, and high-confidence user
  states without raw vector leakage.
- Persona scans proving response behavior remains in YAML.
- SQLDelight migration and restart tests for relationship state.
- Group tests proving two users share one ATRI `BioVector` but retain independent
  trust, boundaries, and tension.
- Concurrency tests proving same-session turns serialize relationship updates
  while different sessions remain concurrent.
- Retrieval tests for same-user profile boosts, shared group memories, bounded
  identity affinity, and recent-context deduplication.
- Safety tests proving relationship values cannot alter privacy, authorization,
  factual constraints, or generate dependency-seeking behavior.

## 16. Completion Criteria

The design is complete when normal public chat produces a confidence-bearing
user-affect observation, the existing 8D path remains unchanged, relationship
state survives restart per `(sessionId, userId)`, and the Prompt Builder receives
semantic user and relationship context without moving persona behavior into
Kotlin.

In group deployments, two users must influence the same ATRI state while
retaining separate relationship records. Memory retrieval must preserve shared
experience while applying bounded identity-aware ranking. All new inference and
state work must remain non-blocking, traceable, confidence-gated, and compatible
with the existing VQ-VAE and per-session serialization invariants.
