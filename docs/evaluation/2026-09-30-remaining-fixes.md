# Remaining-issue repair and verification

This is a local engineering verification record, not a signed production release approval. The earlier [A/B diagnostic](2026-09-30-live-subscription-ab.md) remains intact. New live results are collected separately under `build/ab-20260930-v2`.

**Latest correction:** Repeated benchmarks were stopped to prioritize root-cause work. The subscription path was missing its `session-id` cache-affinity header. This is now fixed and verified with bounded protocol probes and three application calls; see [cache routing diagnosis](2026-09-30-cache-session-affinity.md). The earlier progress and measurements below remain historical evidence, not a statement that the series is still running. The planned 24-input golden follow-up was canceled.

## Implemented repairs

- Public-voice correction now supplies the actual validator errors and the normalized recent openings to avoid. The initial generation receives those exclusions too. The persona policy, eight-turn lookback and response validation remain enforced; rewrites do not replace the original Bio delta.
- Subscription requests carry a stable, opaque `prompt_cache_key`. Appending history or changing dynamic voice feedback preserves the key; changing the history epoch invalidates it. An explicit unsupported-field response permits one fallback without cache metadata. This is a routing hint, not proof of a provider cache hit.
- Windows production export now uses pinned native directory handles, rooted non-reparse opens, stable filesystem identities, durable file/directory flushes and a non-overwriting rooted rename. Missing ancestor directories are created through the rooted walk. Junction ancestors fail before creating children at their destination. Unix retains its secure-directory-stream implementation.
- Context traces record actual history/RAG lineage, history epoch and sealed chunk fingerprints. A real-run analysis exposed the old generic 256-character trace limit truncating these JSON arrays. The sanitizer now accepts only explicitly named, validated identifier/hash schemas up to 64 KiB; malformed or oversized evidence gets an explicit status instead of a partial JSON value. Ordinary diagnostic text and secret-key filtering retain their limits.
- Blind-judge protocol v3 classifies each turn once, derives procedural rates from those same rows, and restricts the three winner fields to consistent combinations. Missing/duplicate annotations and invalid comparisons fail validation. Old inconsistent annotations are not edited.
- The live collector accepts a separate experiment directory. A series runner collects three paired repetitions with isolated databases and preserves rejected outputs. It runs blind judgment only after each complete pair. Failed or partial collections are not resampled as successful runs.

## Automated verification

The complete `:core:jvmTest :server:test :client:jvmTest :cli:test` invocation passed after these changes:

| Suite | Passed | Skipped | Failures/errors |
|---|---:|---:|---:|
| core | 479 | 0 | 0 |
| server | 315 | 2 | 0 |
| client | 10 | 0 | 0 |
| CLI | 185 | 0 | 0 |

Evidence: `build/all-fixes-complete-checks.log`, `build/all-fixes-final-counts.json`. Meaningful regressions include real Windows junction/rename/collision tests, full SQLite export and hash verification, 128-turn lineage persistence across database restart, subscription routing through history growth/epoch change, and actual response-only voice repair. Earlier tests also cover the shared dispatcher shutdown, completed SSE reconstruction and real SQLite lock contention.

Existing deterministic suites cover injected-clock heartbeat/silence behavior, compaction fields and all-dimensional directional/relief mechanics. These are engineering tests; they are not mislabeled as live provider or signed production observations.

## Live verification in progress

The new A/B pair uses `gpt-6-luna`, real DJL, isolated databases and the unchanged 128-input scenario. Both arms receive the necessary SSE/persistence repairs so the comparison does not depend on the old broken evaluator. The candidate additionally includes voice feedback and subscription cache routing. Source and installed-jar hashes are retained with the experiment. Later native-path hardening and structured trace-limit repairs are separately tested workspace changes and are not retroactively attributed to the frozen runs.

The initial launch encountered OAuth `invalid_grant`; the operator reauthorized successfully before any scenario turn was collected. Its failure record is preserved. Provider EOF failures during collection are recorded; a retry is permitted only after a completed HTTP failure with an unchanged committed-turn count.

The current measured results and blind decisions are in `build/ab-20260930-v2/measured-summary.json`. Until all repetitions and decisions complete, no overall advantage or full acceptance is asserted. Truncated lineage arrays in the frozen run are reported as malformed/unobservable, not silently repaired or accepted.

The first complete pair measured A: 112 delivered / 16 rejected; B: 128 delivered / 0 rejected. Both executed VQ-VAE on every input, preserved incarnation/evolution/Omega invariants, passed restart at input 104 and scope restoration at 110/111, and retained COUPLE facts. Neither had a post-commit delivery failure. Reported warm token reuse was 59.35% for A and 48.63% for B; A's extra rewrite requests are included, so this difference alone does not isolate the cache-key effect. Both had one recorded pre-commit transport failure with unknown usage.

The first blind-judge attempt exceeded the evaluation helper's 2 MiB SSE framing budget before a completed result could be saved. The helper now allows a bounded 16 MiB stream and a ten-minute batch timeout. The failed attempt log is retained; retry uses the identical request and blind mapping, without changing the judging criteria.

The completed v2 judgment contradicted the overall-winner rule in all five cases (persona TIE, companion RIGHT, overall RIGHT). It was rejected and preserved under `judgments/v2-invalid`. Protocol v3 enforces the existing rule through a nested schema admitting only consistent verdicts; the same transcripts and LEFT/RIGHT mapping were re-evaluated. The valid v3 first-pair result selected B on both dimensions in all five cases. The change from the invalid v2 dimension labels demonstrates judge sensitivity, so it is not treated as independent human approval. Repetitions two and three continue under v3.

First-pair v3 annotations report B romantic reciprocity 12/12, no false boundary alarms, procedural wording 4/124, and no hot-romance opportunities. The four procedural flags involve requested time management, a startup ritual, an invited maintenance metaphor and cooking advice; they merit independent review rather than automatically suppressing those persona-compatible responses. Their labels are retained, not removed to meet the 2% threshold. Hot-romance coverage remains unobserved.

### Second-pair interruption

The second pair stopped at A input 44 and B input 42 after authorization became unavailable. A recorded 43 inputs (42 committed); B recorded 41 inputs (41 committed). A's server log records `ReauthorizationRequired`, followed by missing-plan-authorization errors in both processes. The refresh handler maps provider `invalid_grant` to this exception and clears local tokens. The cause of the provider rejection has not been established; it is not attributed to the proxy or cache routing.

All six failed attempts had unchanged committed-turn counts. The failed collection records and snapshots are preserved; neither partial run qualifies as a completed repetition. The series stopped before pair three, and the dependent golden watcher was stopped because its seed cannot be produced. The analyzer currently reports `NOT_ACCEPTED`. Reauthorization is required before continuing the recorded checkpoints; it must not cause earlier rejected responses to be regenerated.

Authorization subsequently completed successfully. Both second-pair collectors resumed from their contiguous captured checkpoints, verifying database committed counts first. Original interruption manifests remain as `authorization-interruption.json`. Resume checkpoints capture the restart state. The continuation runner will judge the completed pair, collect pair three, and then collect and judge the separately seeded 24-input follow-up. These steps remain pending until their artifacts confirm completion.

The resumed second pair completed all 128 inputs per arm: A delivered 112 with 16 validation rejections; B delivered all 128 with no validation rejections. Both preserved incarnation, evolution and nondecreasing Omega, executed 128 VQ-VAE turns, passed the planned restart and scope restoration, and had zero post-commit delivery failures. Each arm recorded five failed pre-commit attempts, including the authorization interruption. Warm token reuse was A 59.90%, B 49.20%. The second pair therefore corroborates delivery improvement while again failing the cache target; its blind judgment is pending.

## Cache evidence and remaining release conditions

Actual provider attribution shows the warm cache stopping within the persona message while later stable history remains uncached. Replaying the recorded prefix, using an unchanged whole request, grouping history and adding a diagnostic namespace did not establish reliable cache reuse. These probes are isolated diagnostic calls, not replacements for failed A/B responses. Artifacts are under `build/cache-runtime-probe-63424257`; a transport failure in the schema comparison is retained as failure.

The implementation does not pad prompts, weaken structured output, remove Bio/relationship/RAG context, or substitute local prefix hashes for provider token counts to raise the reported rate. A 95% steady-state target is reasonable when the changing suffix is below 5% of input; it is not an unconditional per-turn minimum. The existing 85% release threshold has not been changed or declared passed.

The percentage of requests with any cache hit is reported separately from the token-weighted reuse rate. Transport failures have unknown provider usage; reported-token rates do not imply complete usage coverage for those attempts.

Production acceptance still needs the complete repeated quality measurements, passing cache/quality thresholds, complete runtime provenance and independently provisioned trusted signing. No deployment trust root is configured in this session; the agent has not generated a self-issued one to bypass that requirement.
