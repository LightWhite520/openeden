# Companion Quality Baseline Rubric

The deterministic long-run fixture exercises scenario shape and export formatting without defining persona behavior in Kotlin. It does not run the production Prompt Builder, LLM, RAG, persistence, or cache path and must not be used as an A/B quality result. Run it only with `-AllowSyntheticFixture`.

- `transcript.jsonl`: every canonical turn is present in authoritative virtual-clock order.
- `bio.csv`: one 8D snapshot exists for each transcript turn; it contains only L, P, E, S, tau, V, M, and F.
- `relationship-events.jsonl`: confession, acceptance, restart, reciprocal romance, chores, boundary, conflict, and repair are represented.
- `prompt-cache-manifest.jsonl`: synthetic turns are explicitly marked `UNOBSERVABLE`; no cache hit rate is manufactured.
- `evaluation-report.md`: records the deterministic turn count, aggregate cache read rate, and relationship-event count.

The scenario includes at least twenty `要不要` negatives, a silence interval, and a heartbeat trigger. Its content is an evaluation fixture, not persona policy. Fingerprint comparisons validate deterministic fixture exports only; production A/B acceptance requires the real pipeline-backed runner introduced by the release-gate work.
