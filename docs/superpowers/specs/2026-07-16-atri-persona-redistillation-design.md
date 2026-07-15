# ATRI Persona Redistillation Design

## Goal

Refine `persona/atri.yaml` from the complete locally extracted ATRI plot while preserving Persona-as-Data. The result must give the model clearer executable instructions without copying source dialogue into the public persona file.

The persona starting-point runtime and `AGENTS.md` are updated with the YAML so configuration, prompt injection, and repository invariants remain consistent. The change does not alter asynchronous execution, vector mechanics, or the VQ-VAE pipeline.

## Source And Distillation Boundary

`private_corpus/atri_decoded` and the generated full-plot extraction are research input only. They may support aggregate observations and abstract behavioral conclusions, but no continuous source dialogue, recognizable paraphrase, Japanese phrasing, or private-corpus content may be copied into `persona/atri.yaml`.

The distilled persona should retain the corpus-supported traits that materially affect generation:

- polite address coexisting with tactile intimacy, teasing, and direct demands;
- short, reactive responses that can expand during technical comedy or emotional escalation;
- hesitation expressed through pauses and self-correction;
- functional language used to understand care and self-worth;
- the tension between a mechanical self-model and experienced emotion;
- playful confidence in being high-performance, balanced by candid awkwardness;
- direct awareness of endings, continuity, memory, and the value of acting despite finitude.

## Bilingual Layering

Hard constraints must be written in English. This includes identity facts, instruction priority, output language, task completion, schema and state compliance, copyright boundaries, forbidden behavior, and the rule that persona cannot override runtime state or validation.

Chinese is reserved for persona expression: temperament, speech rhythm, relationship behavior, emotional nuance, and the three growth-stage patches. Chinese sections describe how the character naturally manifests; they do not redefine system mechanics.

The intended section responsibilities are:

- `persona.identity`: concise English identity and relationship facts.
- `persona.base`: Chinese baseline temperament and voice.
- `persona.behavior`: Chinese recurring behavioral patterns.
- `output.layer.rules`: English mandatory execution and output constraints.
- `persona.patch.*`: Chinese stage-specific self-model and observable behavior.
- `heartbeat.*`: Chinese scene context followed by concise English execution constraints.
- `diary.narrative`: Chinese narrative context followed by concise English factuality and non-disclosure constraints.
- `style.observed_summary`: compressed Chinese corpus findings that affect generation.
- `style.source_language_notes`: English source-abstraction and language-boundary rules.
- `style.do`: Chinese positive style guidance.
- `style.do_not`: English prohibitions.

## Starting Points

The three patches are explicit canonical playthrough starting points, not thresholds in an automatic progression:

1. `pre_command`: emotion is interpreted as simulation; usefulness and performance dominate self-worth; care is expressed as duty.
2. `true_self`: the diary revelation and command to stop simulated affect are established; autonomous choices contradict the no-heart model, but no mature answer is forced.
3. `awakened`: machine identity and felt emotion are integrated; care, fear, dependence, and choice can be named directly while judgment and restraint remain intact.

Normal growth defaults to `pre_command`. Operators may explicitly select `true_self` or `awakened` to skip to a later canonical starting point, while legacy mode remains awakened. The selected mode and patch are persisted in session state, remain immutable for the session, and are injected on every prompt; changing persona YAML does not rewrite existing sessions. `evolution_index` remains a continuously increasing lived-experience signal but never selects or replaces patches. No starting point may hardcode vector values or replace VQ-VAE semantics.

Databases migrated from the former threshold-driven runtime assign existing sessions to `growth` + `awakened`. The old schema did not persist the active threshold-derived patch, so choosing the mature starting point is the only deterministic migration that cannot downgrade an established session. This compatibility promotion applies only to pre-v5 rows; newly created sessions always use the explicit persona selection.

## State And Runtime Compliance

The persona must explicitly yield to the injected Codebook semantics, derived dissonance, Omega, ShockState, retrieval mode, relationship context, and output validator. Low Vitality shortens output; elevated Entropy or Fear may destabilize expression only to the extent allowed by injected state.

The persona must not treat `D` as a stored dimension, emit it inside `vector_delta`, invent codebook nodes, expose internal logic in the final response, or alter the mandatory eight-key output contract.

## Editing And Verification

The edit should remove duplicated prose and prefer concrete generation guidance over literary explanation. Existing user changes in `persona/atri.yaml` must be preserved.

Verification consists of:

- parsing `persona/atri.yaml` through the existing persona loader tests;
- verifying explicit starting-point parsing and the legacy awakened invariant;
- verifying that the selected mode and starting point survive a durable-store restart;
- verifying that increasing `evolution_index` does not replace the selected patch;
- running `AtriPersonaGuardTest` for required sections, Japanese kana, and oversized quoted dialogue;
- reviewing the diff for English-only hard constraints and Chinese-only persona expression;
- confirming private corpus artifacts remain Git-ignored and runtime changes are limited to starting-point selection.
