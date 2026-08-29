# Companion Quality Release Rubric

The release gate consumes measured A/B evidence. It passes only when every threshold below passes, the pairwise record is auditable, and the report declares `PRODUCTION` evidence. Missing production evidence fails the gate. The deterministic long-run fixture remains a format and scenario-shape test: it does not run the production Prompt Builder, LLM, RAG, persistence, or provider-cache path, and its report always says `SYNTHETIC_FIXTURE` / `SYNTHETIC_ONLY`.

## Authenticated production evidence

A production decision cannot accept caller-supplied aggregate metrics, assembled `RunArtifacts`, `AuthenticatedABEvidence`, `PairwiseEvaluation`, signing keys, verifier implementations, signer registries, fingerprints, or a trust root supplied alongside the evidence. The only public production entry is `ProductionEvaluationReport.evaluate(ProductionEvaluationInputPaths)`. `ProductionEvaluationReport` is JVM-final and privately constructed, so callers can read but cannot implement, override, or directly assemble its authoritative values. Its input contains explicit Task 5 baseline/candidate export paths and explicit pairwise decision artifact paths. Each Task 5 run must contain `transcript.jsonl`, `bio.csv`, `relationship-events.jsonl`, `prompt-cache-manifest.jsonl`, `evaluation-report.md`, `retrieval-trace.jsonl`, `runtime-trace.jsonl`, and `evaluation-manifest.json`; each pairwise input must contain `pairwise-decisions.json` and `pairwise-evaluation-manifest.json`.

The manifests carry exact artifact file names and SHA-256 values, signer public key, signer fingerprint, and Ed25519 signatures; Task 5 manifests also carry the run/scenario/variant/repetition header. At production-gate bootstrap, trusted signer fingerprints are read once from deployment environment variable `OPENEDEN_EVALUATION_TRUSTED_SIGNER_FINGERPRINTS` and frozen for that gate's lifetime. Evaluation never reads a JVM system property, and callers cannot inject or replace the registry, verifier, keys, or fingerprints. Authentication verifies that the public-key fingerprint is in the frozen registry, each Ed25519 signature is valid, every explicit path is the exact manifest path, every hash matches, and all Task 5/runtime/pairwise files parse into the required typed observations.

All baseline and candidate repetitions must be uniquely paired, contiguous, use one scenario fingerprint, and share one trusted signer. Turn lineage across transcript, Bio, relationship events, cache, retrieval, and runtime evidence must match exactly. Provider and local cache rates are derived from per-turn token and byte-identity observations. Every gate measurement persists structured provenance containing the authenticated manifest set and relevant artifact kinds. Missing files, path swaps, malformed records, untrusted or self-issued signers, signature/hash mismatches, mixed provenance, inconsistent turn IDs, invalid cache tokens, incomplete 8D coordinates, or a forged in-memory authentication proof fail closed before a production report can pass.

## Relationship and factual quality

- Boundary false positives are exactly `0` for all negative `要不要` golden cases.
- Confession acceptance and couple status each survive unrelated turns, process restart, and scope restoration.
- Romantic reciprocity and separately measured hot-romance reciprocity are each at least `0.90`.
- Procedural wording outside operational contexts is below `0.02`.
- Candidate pairwise win rate is at least `0.70`, with no factual regression.

## Memory and context

- Recent/sealed history and RAG `source_turn_id` overlap is exactly `0` within one request.
- RAG capacity does not regress when enough unique candidates exist.
- MIXED 6:4 and CONTRAST retrieval semantics both remain intact.
- Compaction separately preserves people, commitments, unresolved issues, relationship facts, and event order.

## Bio state

- Neutral `median(abs(effective_delta))` is at most `0.02`.
- Saturation violations are `0`: without an authoritative high-intensity cause, no dimension remains at or above `0.99` for ten ordinary turns.
- Every one of `L`, `P`, `E`, `S`, `tau`, `V`, `M`, and `F` has positive, zero, and negative test paths; relief paths include both `S` and `F` decreases.
- VQ-VAE, heuristic fallback, derived-D, and Omega regression flags are all false.

## Cache and capability preservation

- When provider usage is `REPORTED`, token-weighted warm cache read rate is at least `0.85`.
- Absent or unknown provider usage stays `UNOBSERVABLE`: provider warm rate must be `null`, never zero or a fabricated success. Local byte-identical prefix rate is reported separately and cannot claim a provider hit.
- Sealed chunks remain byte-identical and ordinary turns only append new items.
- Compaction causes exactly one epoch miss and restores reuse within two turns.
- VQ-VAE, all eight dimensions, RAG, relationship context, and recent context are all preserved.

## Time and runtime

- Multi-day time sources use the authoritative virtual clock; ordinary turns without time semantics introduce no needless changing timestamp.
- Heartbeat, silence-window, and memory timestamps reproduce deterministically under virtual-time advancement.
- Failure and degradation paths remain non-blocking and produce no silent-response failures.

## Pairwise audit record

`release-gate-report.json` persists evaluator version, evaluator model, scenario fingerprint, blind-protocol version, derived candidate repetition count, candidate win rate, signer fingerprint, authenticated A/B manifest fingerprints, authenticated pairwise-manifest fingerprints, and every raw decision. Each decision contains its own ID, scenario-case ID, candidate repetition, left and right manifest fingerprints, the post-judgment candidate-slot mapping, an explicit `LEFT` / `RIGHT` / `TIE` overall winner, separate `ATRI_FIDELITY` and `COMPANION_QUALITY` dimension winners, factual-regression decision, and rationale. The left/right fingerprints and scenario-case ID must resolve to the paired authenticated manifests for that repetition. Pairwise artifacts must use the same trusted signer as their A/B evidence; missing, malformed, path-swapped, hash-mismatched, or untrusted pairwise files fail closed.

Overall-winner ruling: when both mandatory dimensions select the same winner, the overall winner must equal it. When the dimensions disagree or either dimension ties while the other does not, the overall winner must be `TIE`. Any other combination is unauditable. Candidate wins are derived from the validated overall winner and post-judgment slot mapping; winners are never inferred from variant names or exposed as candidate/baseline labels to the judge.

When provider seed control is unavailable, raw decisions must cover at least three distinct contiguous candidate repetitions. With seed control, at least one repetition is required. The measured pairwise win rate must equal the rate derived from raw decisions.

## Synthetic harness

Run format fixtures only with `-AllowSyntheticFixture`:

```powershell
.\scripts\run-relationship-evaluation.ps1 -Variant A -Runs 1 -AllowSyntheticFixture
.\scripts\run-relationship-evaluation.ps1 -Variant B -Runs 3 -AllowSyntheticFixture
```

For variant B, fewer than three runs are rejected unless `-ProviderSeedControlAvailable` is supplied. This runner exports `transcript.jsonl`, `bio.csv`, `relationship-events.jsonl`, `prompt-cache-manifest.jsonl`, `evaluation-report.md`, and `release-gate-report.json`. Synthetic cache entries remain `UNOBSERVABLE`, all production-only gate measurements remain unobservable, pairwise evidence is `null`, and `syntheticFixture` explicitly declares `nonProduction: true` and `personaFree: true`. The harness rejects injected production reports and any caller-supplied `REPORTED` provider-cache data before writing files. No synthetic path can be upgraded to `PRODUCTION` or `PASS`.

`TestOnlyTask5ExportFixture` creates signed files only. It exposes no gate, trust registry, verifier, or production report factory. Its pinned test key is not a production trust root: canonical gate tests run in a forked JVM whose `OPENEDEN_EVALUATION_TRUSTED_SIGNER_FINGERPRINTS` environment is established before gate initialization. Real release reports require independently configured signer fingerprints and actual Task 5/runtime exports from the paths under evaluation.
