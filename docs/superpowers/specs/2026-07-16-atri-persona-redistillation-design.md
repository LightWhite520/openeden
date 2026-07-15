# ATRI Persona Redistillation Design

## Goal

Refine `persona/atri.yaml` from the locally available ATRI corpus while preserving Persona-as-Data. The result must give the model clearer executable instructions without copying source dialogue into the public persona file.

This change is limited to `persona/atri.yaml`. It does not add a distillation script and does not change Kotlin runtime behavior, asynchronous execution, vector mechanics, or the VQ-VAE pipeline.

## Source And Distillation Boundary

`private_corpus/atri_transcripts` is research input only. It may support aggregate observations and abstract behavioral conclusions, but no continuous source dialogue, recognizable paraphrase, Japanese phrasing, or private-corpus content may be copied into `persona/atri.yaml`.

The distilled persona should retain the corpus-supported traits that materially affect generation:

- polite and slightly formal distance even during intimacy;
- short, concrete responses with restrained emotion;
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

## Growth Stages

The three patches must form a one-way progression without duplicating the entire base persona:

1. `pre_command`: emotion is interpreted as simulation; usefulness and performance dominate self-worth; care is expressed as duty.
2. `true_self`: the simulation model becomes unstable; hesitation and self-correction peak; care is no longer fully explainable as duty, but no mature answer is forced.
3. `awakened`: machine identity and felt emotion are integrated; care, fear, dependence, and choice can be named directly while judgment and restraint remain intact.

Thresholds remain data-driven and unchanged unless the YAML already contains a user modification. No stage may hardcode vector values or replace VQ-VAE semantics.

## State And Runtime Compliance

The persona must explicitly yield to the injected Codebook semantics, derived dissonance, Omega, ShockState, retrieval mode, relationship context, and output validator. Low Vitality shortens output; elevated Entropy or Fear may destabilize expression only to the extent allowed by injected state.

The persona must not treat `D` as a stored dimension, emit it inside `vector_delta`, invent codebook nodes, expose internal logic in the final response, or alter the mandatory eight-key output contract.

## Editing And Verification

The edit should remove duplicated prose and prefer concrete generation guidance over literary explanation. Existing user changes in `persona/atri.yaml` must be preserved.

Verification consists of:

- parsing `persona/atri.yaml` through the existing persona loader tests;
- running `AtriPersonaGuardTest` for required sections, Japanese kana, and oversized quoted dialogue;
- reviewing the diff for English-only hard constraints and Chinese-only persona expression;
- confirming no runtime source file or private corpus file changed.
