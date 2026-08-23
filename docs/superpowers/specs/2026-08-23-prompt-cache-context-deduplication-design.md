# Prompt Cache Context Deduplication Design

## Goal

Maximize the practical prompt-cache read rate without reducing response quality. OpenEden must retain the VQ-VAE Codebook signal, complete 8D-derived runtime context, the existing RAG result capacity, and the existing near-term conversation context. Cache improvements must come from deterministic ordering, immutable chunking, source-aware deduplication, compact serialization, stable cache routing, and observability rather than deleting useful context.

This design extends, rather than replaces, the existing OpenAI prompt-caching, system-time ordering, relay-compatibility, and server-owned transcript designs.

## Evidence And Current Limitations

The retained production sample contained 16 QQ requests:

- 12 of 16 requests reported a cache read;
- 107,520 of 180,038 input tokens were reported as cached, a token-weighted rate of 59.72%;
- successful reads reused exactly 8,960 tokens;
- four requests missed despite the same stable prefix and key;
- the provider reported no cache-write tokens.

The repeated 8,960-token boundary shows that the system and persona prefix is reusable, while the combined dynamic context message prevents reuse from extending through conversation history. The unexplained misses also show that relay routing or retention remains an independent limit; prompt ordering alone cannot guarantee every read.

The current context path has a separate correctness problem:

- `recent_turns` is loaded from `MemoryStore.recent`, not `TranscriptStore`;
- SQL recent-memory selection is not an authoritative conversation-turn query;
- prompt deduplication compares only memory IDs;
- RAW and NARRATIVE entries describing the same event have unrelated IDs;
- reimported or regenerated records can carry different IDs for the same turn;
- future embedding-model migrations can expose multiple representations of one logical memory.

The retained production prompts did not contain an exact string duplicated across `recent_turns` and `memories`, but the current ID-only rule cannot prevent logical duplication. The design must correct the capability, not depend on the sample failing to reproduce it.

## Hard Invariants

- Persona remains data in `persona/*.yaml`; no personality behavior moves into Kotlin.
- Codebook semantic definitions and derived D remain before the current user input.
- VQ-VAE inference, heuristic fallback, 8D state, Omega, ShockState, relationship state, and user-affect context remain available to every normal generation.
- The existing near-term history policy remains the minimum: two recent turns normally and four when immediate-history reference language is detected.
- The existing total retrieved-context capacity is not reduced. Removing a duplicate must trigger ranked backfill rather than reduce the final context count.
- Retrieval mode semantics, including MIXED quotas and CONTRAST emotional targeting, remain intact.
- No semantic-similarity threshold may silently remove a memory from prompt context in the first implementation.
- Prompt assembly, transcript access, retrieval, hashing, and compaction remain suspend-based and non-blocking. DJL and vector work remain on `InferenceDispatcher` as required by `AGENTS.md`.
- Cache metadata never changes runtime state or model-visible personality.

## Selected Architecture

The selected approach combines three mechanisms:

1. `TranscriptStore` becomes the authoritative source for immediate conversation turns.
2. Memory lineage is the authoritative cross-layer deduplication key.
3. Conservative normalized-content hashes provide defense in depth for legacy or imported rows without lineage.

Semantic similarity and MMR may be recorded for diagnostics or used to diversify only within the RAG candidate set after explicit evaluation. They are not authoritative overlap filters because a false positive could remove a related but independently valuable memory.

## Context Model

Prompt context is split by mutability rather than by feature ownership:

```text
stable system contract and output schema
stable persona and immutable starting-point patch
stable session anchor
frozen history summary, when present
sealed immutable transcript chunks
---------------- longest reusable prefix ----------------
current Codebook definitions and derived D
current runtime, affect, and relationship state
deduplicated dynamic RAG memories
mutable recent transcript tail
heartbeat context, when present
system time
current user input
```

The Codebook remains a mandatory runtime constraint before user input. Moving immutable history ahead of it does not weaken that requirement; it prevents a changing Codebook node from invalidating all reusable history tokens.

The model-visible session anchor contains only values immutable for the session lifecycle, such as persona mode and selected starting point. The opaque session cache identity is request metadata and must not be injected into model-visible text. `evolution_index`, vectors, Omega, ShockState, user affect, and relationship values are dynamic and must not enter the anchor.

## Authoritative Recent Turns

`TranscriptStore` gains a session-scoped recent-turn query. It returns completed, validated conversation turns ordered by `(completed_at, turn_id)`. It must not reuse the public incarnation-wide pagination API because prompt context must preserve the `platform:scopeId` session boundary.

`recent_turns` is rendered from these transcript records, not from Memory Palace rows. Consequently:

- diary narratives cannot become immediate dialogue accidentally;
- embedding rows cannot multiply one conversation turn;
- memory retention and embedding-model migration cannot change conversational chronology;
- each injected turn has an authoritative `turn_id` for overlap checks.

The existing reference-sensitive policy remains: use the last two turns normally and the last four when the current input explicitly refers to immediate prior dialogue. A larger immutable-history budget may add context above this minimum, but compaction must never reduce the minimum tail.

## Memory Lineage

Memory metadata gains compact source lineage:

```text
source_turn_ids: ordered set of authoritative transcript turn IDs
source_memory_ids: ordered set of direct source memory IDs
content_fingerprint: versioned SHA-256 of canonical visible content
lineage_version: serialization and normalization version
```

Rules:

- A RAW memory written for a completed dialogue turn carries exactly that `turn_id`.
- A NARRATIVE diary carries the union of source turn IDs and direct source memory IDs represented by its diary task.
- Derived or migrated memories preserve lineage from their source rows.
- Legacy rows may have empty lineage and rely on the fingerprint fallback.
- Lineage describes data provenance only. It must not encode emotional or persona behavior.

Lineage lists are bounded and deterministically ordered. When a narrative spans more IDs than the metadata limit, it stores an explicit compact turn range plus a digest of the full ordered set; overlap checks must remain exact for the represented range and must not guess from the digest.

## Deduplication And Backfill

Deduplication occurs after utility filtering and mode-aware ranking has produced an oversized candidate pool, but before the final prompt budget is selected.

For target memory capacity `K`:

1. Retrieve and rank at least `3 * K` candidates, bounded by available eligible rows.
2. Build the exclusion lineage from the frozen summary, sealed transcript chunks, and mutable recent tail included in the same request.
3. Exclude a candidate when its `source_turn_ids` or represented source range intersects the exclusion lineage.
4. Exclude a candidate when its direct source memory is already represented by another selected item.
5. For rows without decisive lineage, compare the versioned normalized-content fingerprint.
6. Select the highest-ranked remaining candidates until `K` is reached.
7. If filtering exhausts the first pool, continue through deeper ranked candidates before declaring underfill.

Normalization is intentionally conservative: normalize line endings, Unicode normalization form, and insignificant boundary whitespace while preserving case, punctuation, speaker labels, and internal whitespace. This avoids false equivalence in Chinese dialogue.

For MIXED mode, overfetch and backfill are performed per retrieval lane. The 6:4 congruent-to-positive-skew intent is retained whenever enough unique candidates exist. Existing lane fallback applies only after a lane is genuinely exhausted. CONTRAST continues ranking against the center-symmetric emotional target.

If fewer than `K` unique eligible memories exist in storage, the prompt injects all unique candidates and emits an explicit underfill trace. It must not reinsert a known duplicate merely to satisfy a numeric count.

## Immutable Transcript Chunks

A conventional `takeLast(N)` string is not partially cache-stable: each new turn removes content at the front and appends content at the back, changing the whole serialized message. OpenEden instead seals transcript context into immutable chunks.

Example:

```text
chunk A: turns 1-16, sealed and never rewritten
chunk B: turns 17-32, sealed and never rewritten
tail:    turns 33-36, mutable until sealed
```

Each sealed chunk has:

- session ID and cache epoch;
- first and last turn IDs;
- deterministic ordered turn IDs;
- deterministic compact serialization;
- token count measured with the configured model tokenizer or a documented conservative estimate;
- content fingerprint and serializer version.

The LLM client sends sealed chunks as separate stable input items in chronological order. It must not concatenate them back into one changing `contextText` item. The mutable tail is a separate item after all dynamic RAG and runtime state, so tail changes cannot invalidate sealed chunks.

Chunks are sealed by a token budget, with a turn-count ceiling as a safety bound. Turn count alone is insufficient because dialogue lengths vary. Once sealed, a chunk is immutable even if a later serializer version is deployed; the new serializer starts a new cache epoch.

## Compaction And Cache Epochs

Prompt history has a bounded token budget. When sealed chunks would exceed it, the oldest eligible chunks are compacted into a frozen history summary. Compaction is an occasional epoch transition, not a per-turn rewrite.

An epoch transition:

1. reads the exact source chunks and their turn lineage;
2. creates one immutable summary using a versioned summarization contract;
3. persists the summary, source lineage, fingerprint, and generation metadata;
4. verifies that the required recent tail remains unmodified;
5. atomically activates the new summary and increments `cache_epoch`;
6. starts new sealed chunks after the summary.

The first request in a new epoch is expected to miss beyond the stable system/persona prefix. Later requests reuse the new epoch prefix. Raw transcript and memories remain persisted and retrievable; compaction affects prompt serialization only.

Summary generation must preserve named entities, commitments, unresolved questions, relationship-relevant facts, and event chronology. A failed summary leaves the previous epoch active and does not block the current reply; the request falls back to the existing bounded recent-tail and RAG behavior with a trace tag.

## Compact Deterministic Serialization

Cacheable sections use one canonical serializer with fixed field ordering, fixed headers, fixed escaping, and no timestamps or request-local counters. Empty optional fields are omitted consistently. Equivalent data must produce byte-identical UTF-8 text.

Dynamic state also uses fixed ordering and compact numeric formatting, but it is not made stale to improve cache statistics. Current values are always injected. `system_time` remains last among dynamic fields.

Serializer version changes are explicit cache-epoch boundaries. Accidental whitespace or map-iteration changes must be caught by golden tests.

## Cache Routing And Provider Behavior

Exact prefix identity remains the correctness basis for cache reuse. `prompt_cache_key` is routing metadata, not a substitute for identical prompt bytes and not proof of a hit.

The cache key becomes session-affine for dialogue generation and is derived from non-secret fingerprints of:

- provider policy and model;
- stable system/persona/schema revision;
- opaque session cache identity;
- dialogue cache namespace.

The key remains stable across cache epochs and serializer revisions so an epoch transition can still route toward the reusable system/persona prefix. Exact bytes, not the key, distinguish old and new epoch branches. This favors reuse along the active conversation, where sealed transcript chunks are unique to the session, and prevents unrelated sessions from creating excessive competing prefixes under one routing key. Diary generation and other non-dialogue prompts retain separate cache namespaces.

Provider behavior remains as specified by the relay-compatible caching design:

- official `api.openai.com` in `AUTO` may receive the supported explicit breakpoint shape;
- custom providers in `AUTO` receive the stable key and applicable cache options but no breakpoint;
- `EXPLICIT` remains an operator assertion of full provider support;
- `DISABLED` sends no cache metadata;
- no automatic retry is added after a cache-shape failure.

The production relay's occasional miss with an identical prefix must be treated as routing or retention variance. Prompt construction can maximize eligibility but cannot claim control over provider eviction.

Official OpenAI guidance requires exact prefix matches and recommends placing static content before dynamic content. The current reference is <https://developers.openai.com/api/docs/guides/prompt-caching>. Provider-specific fields remain capability-gated because an OpenAI-compatible relay is not assumed to implement the complete official request schema.

## Observability

Every request records content-free diagnostics:

- stable-prefix, summary, sealed-chunk, tail, and full-input fingerprints;
- cache key fingerprint and cache epoch;
- token counts for each prompt segment;
- provider-reported input, cached, and cache-write tokens;
- candidate count, lineage exclusions, fingerprint exclusions, backfill depth, and final unique count;
- retrieval mode and MIXED lane counts;
- compaction success, failure, and epoch transition;
- provider capability branch and breakpoint presence.

New trace tags include equivalent values for:

```text
context_dedup=LINEAGE
context_dedup=FINGERPRINT
context_dedup=UNDERFILLED
history_chunk=SEALED
history_compaction=COMPLETED
history_compaction=FALLBACK
```

Logs must not contain full prompts, memory bodies, user text, persona text, API keys, or authorization headers.

Primary cache metrics are token-weighted read rate and the length of the longest reused prefix. Request hit rate remains secondary because one small hit and one large hit are not equivalent. Metrics must also show total input tokens and final unique context counts so a misleading improvement caused by context removal is visible.

## Failure Handling

- Transcript recent-read failure falls back to the current memory-backed recent path for that request and emits a degraded-source trace; it does not block generation.
- Lineage persistence failure does not discard the memory write. The row is stored with an empty lineage and a content fingerprint, then traced as degraded.
- Candidate underfill injects every unique eligible result and reports the deficit.
- Chunk persistence or sealing failure keeps turns in the mutable tail and continues generation.
- Compaction failure keeps the previous epoch active.
- Cache telemetry absence is represented as provider metrics unavailable, not as a miss or zero write.
- Provider 5xx behavior is handled by the existing LLM error path; cache optimization introduces no duplicate-generation retry.

## Migration

Existing transcript turns are already authoritative for completed public dialogue. Migration adds session-scoped recent querying and builds new chunks prospectively.

Existing memory rows receive fingerprints lazily or in a bounded background migration. Lineage is backfilled only where a source relationship can be proven from persisted identifiers and exact canonical content. The migration must not infer lineage from semantic similarity.

Sessions begin at cache epoch zero. Existing prompts continue through the current bounded recent-memory path until enough authoritative transcript data is available. No production prompt, memory, or transcript content is deleted by this migration.

## Testing Strategy

### Deduplication

- The same memory ID in recent and RAG is injected once and RAG backfills to capacity.
- Different RAW and NARRATIVE IDs with overlapping `source_turn_ids` are injected once.
- Reimported content with different IDs and no lineage is caught by the conservative fingerprint.
- Related memories without lineage or exact fingerprints are both retained.
- MIXED retrieval preserves lane quotas while backfilling.
- CONTRAST retrieval keeps center-symmetric ranking after filtering.
- A genuinely exhausted store reports underfill without reintroducing duplicates.

### Transcript And Chunking

- Recent prompt turns come from the correct `session_id`, not the whole incarnation.
- Normal and immediate-reference inputs retain at least two and four recent turns respectively.
- A sealed chunk is byte-identical across subsequent turns.
- Appending to the tail does not change earlier input items.
- Chunk boundaries are deterministic under the same token budget.
- Serializer changes create a new epoch rather than mutate old chunks.
- Compaction preserves source lineage and leaves the required recent tail intact.

### Prompt Ordering

- Stable system, persona, session anchor, summary, and sealed chunks precede all dynamic values.
- Codebook definitions, derived D, current 8D-related state, RAG, recent tail, time, and user input remain present.
- Codebook state remains before current user input.
- System time is the last dynamic field.
- Golden payload tests reject whitespace, map-order, or input-item regressions.

### Provider And Metrics

- Official and relay request branches retain their existing breakpoint behavior.
- Identical sessions produce identical dialogue cache keys across ordinary turns and cache epochs.
- A session, model, stable contract revision, or cache namespace change produces a distinct dialogue cache key.
- Missing provider cache metrics remain distinguishable from zero cached tokens.
- Production-style traces can compute request hit rate, token-weighted hit rate, longest reused prefix, dedup replacements, and context cardinality.

### Quality Gate

Replay a fixed corpus through the old and new context assemblers. The new assembler must demonstrate:

- identical current Codebook and dynamic-state content;
- no decrease in required recent-turn count;
- no decrease in target RAG capacity when enough unique memories exist;
- no loss of unique baseline memory content except entries proven to overlap injected transcript lineage or exact canonical content;
- no regression in schema validation or grounding checks.

Cache success is accepted only when these assertions pass. Cached-token improvement without the quality gate is a failed result.

## Rollout

1. Add diagnostics for segment fingerprints, context cardinality, and duplicate causes without changing prompt output.
2. Add memory lineage and authoritative session-scoped transcript reads behind a feature flag.
3. Enable lineage deduplication with overfetch and backfill; compare shadow output against the current assembler.
4. Enable deterministic sealed chunks and session-affine keys for one test session.
5. Enable compaction only after chunk stability and quality-gate evidence is collected.
6. Roll out broadly while monitoring token-weighted cache reads, full input size, provider misses, dedup replacement rate, and underfill rate.

Rollback disables the new assembler and returns to the current prompt shape. It does not delete lineage, chunks, summaries, transcripts, or memories.

## Acceptance Criteria

- VQ-VAE, Codebook semantics, derived D, current runtime state, RAG, and required recent context remain present.
- RAG overlap is resolved by authoritative lineage and conservative exact fingerprints, with ranked backfill to the existing capacity.
- `recent_turns` is sourced from session-scoped transcript records and cannot contain diary rows or embedding duplicates.
- Sealed history is byte-stable across turns and appears before dynamic state.
- Compaction occurs only at explicit epoch boundaries and never rewrites an active sealed prefix.
- Relay-compatible request behavior remains unchanged and produces no breakpoint-related regression.
- Telemetry distinguishes provider-unreported metrics from misses and proves that cache improvement was not obtained by reducing useful context.
- Persona-as-Data, non-blocking execution, VQ-VAE isolation, retrieval-mode semantics, and session boundaries remain intact.
