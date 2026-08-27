# Companion Quality Baseline Rubric

The deterministic long-run baseline exercises the canonical stranger-to-lover scenario without defining persona behavior in Kotlin. Review each generated run for the following evidence:

- `transcript.jsonl`: every canonical turn is present in authoritative virtual-clock order.
- `bio.csv`: one 8D snapshot exists for each transcript turn; it contains only L, P, E, S, tau, V, M, and F.
- `relationship-events.jsonl`: confession, acceptance, restart, reciprocal romance, chores, boundary, conflict, and repair are represented.
- `prompt-cache-manifest.jsonl`: per-turn prompt/cache measurements are recorded for A/B comparison.
- `evaluation-report.md`: records the deterministic turn count, aggregate cache read rate, and relationship-event count.

The scenario includes at least twenty `要不要` negatives, a silence interval, and a heartbeat trigger. Its content is an evaluation fixture, not persona policy. Compare artifact fingerprints between repeated fake runs before using the harness as an A/B baseline.
