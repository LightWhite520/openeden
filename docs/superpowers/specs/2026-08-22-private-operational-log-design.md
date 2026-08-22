# Private Operational Log Conditioning

## Goal

Replace the normal dialogue `internal_logic` prompt semantics from an explanatory reasoning trace with a concise, persona-driven private operational log that conditions the subsequent vector delta and visible response.

## Design

- Keep the public JSON contract and field order unchanged: `internal_logic`, `vector_delta`, then `response`.
- Treat `internal_logic` as generated narrative context, not hidden chain of thought or a factual explanation of model reasoning.
- Keep hard protocol constraints in the English logical core: the log must begin with the observable event, include one exact active Codebook node identifier, avoid response-writing strategy, and remain private.
- Put voice, rhythm, and subjective interpretation entirely in persona data through a new optional `internal_logic.private_log` section.
- Make the ATRI section use an original operational-log style distilled from the private corpus: factual observation, tentative inference, contradiction, unexplained action tendency, and restrained emotional leakage through what is selected for recording.
- Keep `diary.narrative` separate. It continues to govern durable background diary memories and does not become the per-turn conditioning prompt.
- Keep the first sentence factual so the existing ShockState back-detection path can extract a useful event description from the beginning of `internal_logic`.
- Keep the active node identifier inside the log so the existing VQ-VAE grounding validator remains unchanged.
- Do not persist or expose `internal_logic` through user-facing conversation history.

## Persona Behavior

The ATRI private log should:

- use concise first-person Simplified Chinese;
- record observable events before interpretation;
- prefer tentative language such as inference, uncertainty, or inability to determine a cause;
- expose affect through contradictions, selected mundane details, and action tendencies rather than ordinary human-style emotional exposition;
- avoid recognizable source dialogue, Japanese syntax, and copied source-log passages;
- avoid prompts, policies, vectors, numerical state, response-writing strategy, and claims of access to hidden reasoning.

Personas without `internal_logic.private_log` remain valid and receive only the neutral protocol semantics from the logical core.

## Data Flow

Selected persona data and immutable starting-point patch + Bio-Core semantic definitions + retrieved memory + current event -> private operational `internal_logic` -> `vector_delta` -> user-visible `response`.

The output validator and active-node grounding validator continue to operate on the unchanged `internal_logic` string.

## Verification

- Add a persona-loader test proving the new section is optional and retained when present.
- Add prompt-builder tests proving private-log guidance is injected from persona data and the old "Traceable reasoning process" wording is absent.
- Preserve tests asserting schema order and exact active-node grounding.
- Run the core JVM test suite and relevant server tests.
