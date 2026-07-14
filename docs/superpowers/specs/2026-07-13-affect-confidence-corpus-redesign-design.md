# Affect Confidence Corpus Redesign

## Problem

The existing 4,096-record user-affect corpus defines `confidence` as annotation
reliability. That is incompatible with the runtime contract, where confidence
means how reliably the six-dimensional affect state can be inferred from the
user's text and directly gates pre-tick and ShockState behavior.

The observed corpus confirms the failure: no sample is below `0.65`, 98.97% are
at least `0.8`, and the median is `0.95`. Training on it would make the runtime
confidence gates ineffective. The old corpus must not be mixed into the new
training set because the same field has a different meaning.

## Confidence Definition

`confidence` is the text-grounded observability of the other five affect
dimensions. It answers:

> Based only on this user message, how reliably can valence, arousal,
> dominance, connection need, and openness be inferred?

It does not represent the labeler's self-confidence, model certainty, writing
quality, or the emotional intensity of the message.

High confidence requires direct and internally consistent evidence in the
text. Low confidence covers factual or low-information messages, irony,
quotation, negated emotion words, ambiguous slang, mixed signals, and messages
whose affect depends on missing conversation context.

## Three-Tier Labeling

`gpt-5.4-mini` generates the bulk corpus under explicit target strata and
ambiguity mechanisms. `gpt-5.5` acts as a low-confidence quality adjudicator for:

- Every generated record with confidence below `0.65`.
- No high-confidence record is sent to `gpt-5.5`, regardless of mechanism or gate proximity.

`gpt-5.5` also performs escalation as a second independent adjudication pass.
That pass runs only when at least one of these conditions holds:

- Generator and `gpt-5.5` differ by more than `0.15` on any affect dimension.
- Their confidence labels fall on opposite sides of the `0.5` or `0.65` gate.
- `gpt-5.5` fails twice to produce a valid record inside the requested stratum.
- A record combines at least three hard mechanisms, such as sarcasm, negation,
  quotation, mixed affect, slang, or missing context.
- The confidence target lies within `0.02` of a runtime gate and the preceding
  review cannot justify the selected side from explicit textual evidence.

An adjudicator may rewrite the text and all six labels. A record is accepted
only after it remains inside its requested confidence stratum and passes schema,
uniqueness, and content validation. API retries are bounded and durable output
is appended only after validation.

Model names are configurable. Defaults are `gpt-5.4-mini` for generation and
`gpt-5.5` for both normal adjudication and the independent escalation pass. The
generator uses low reasoning effort, while both `gpt-5.5` passes use medium
reasoning effort. The
manifest records aggregate call counts per model, while each final record's
`escalatedBy` field makes escalation rates auditable even when both review tiers
use the same model. Credentials are read only from environment variables and
never written to corpus files, manifests, logs, or Git history.

## Corpus Shape

Generate 8,192 new records with deterministic IDs distinct from the legacy
`UA_*` set. Confidence is stratified into five equal target bands:

| Band | Range | Target share |
|---|---:|---:|
| Very low | `[0.05, 0.35)` | 20% |
| Low | `[0.35, 0.50)` | 20% |
| Gate transition | `[0.50, 0.65)` | 20% |
| Moderately reliable | `[0.65, 0.80)` | 20% |
| Explicit | `[0.80, 0.98]` | 20% |

At least 25% of all records lie within `0.05` of either runtime threshold so
the model learns the gate boundaries rather than only distant extremes.
Sampling and train/validation/test splitting are stratified by confidence band
and ambiguity mechanism.

Coverage mechanisms include direct disclosure, neutral factual content,
negation, sarcasm, quoted emotion, mixed affect, slang, boundary requests,
indirect connection need, missing-context references, and short
low-information messages. Each mechanism has a minimum quota and appears in
every split.

## Quality Controls

The generator validates:

- Exactly six finite labels in `[0, 1]`.
- Unique IDs and normalized text with no duplicate or near-duplicate entries.
- Confidence inside the requested band.
- No names, contact details, diagnoses, persona roleplay, or assistant answers.
- No overlap with the tracked challenge set or any evaluation partition.
- Manifest counts by confidence band, mechanism, generator, and adjudicator.

Near-duplicate detection uses normalized Chinese text plus embedding similarity;
embedding work remains offline and does not alter runtime architecture.

## Evaluation

Held-out reporting includes overall and per-dimension MAE plus confidence-
specific measures:

- Confidence MAE by stratum.
- Binary precision, recall, and F1 at the `0.5` pre-tick gate.
- Binary precision, recall, and F1 at the `0.65` ShockState gate.
- Calibration error across the five confidence bands.
- Results by ambiguity mechanism.

The Qwen affect model must beat the legacy model's overall held-out MAE of
`0.1385689`, but aggregate MAE alone is insufficient. Gate classification and
challenge-set results must also be recorded before runtime replacement.

## Failure Handling

Unknown model names, unavailable endpoints, malformed responses, exhausted
retries, missing adjudication, or quota shortfalls fail corpus generation. The
generator resumes safely from validated durable rows and never fills a missing
stratum with a different one. Training does not start unless a corpus audit
passes all quotas and leakage checks.

## Repository Policy

Raw generated and adjudicated JSONL remains ignored by Git. The generator,
audit tests, non-sensitive manifest, tracked challenge set, and final aggregate
metrics are committed. The legacy raw corpus remains local for comparison but
is explicitly excluded from the new Qwen training run.
