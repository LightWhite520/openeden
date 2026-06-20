# Local Usable Runtime Design

Date: 2026-06-20
Scope: OpenEden Remaining Implementation Roadmap Stage 1.

## Goal

Make OpenEden usable from a local CLI with a real LLM provider while preserving
the existing heuristic codebook fallback and empty Memory Palace fallback.

## Compliance Checks

### Persona-as-Data

The CLI and provider adapter do not contain persona prose. They load
`persona/*.yaml` through the existing persona loader and pass prompt documents
from the runtime to the provider unchanged.

### Non-Blocking Constraint

The CLI is a suspend entry point. Provider calls use Ktor client suspend APIs.
Runtime math remains behind `InferenceExecutor`; the CLI composes existing
runtime services and does not run model, vector, mapping, tick, or heartbeat
logic inline.

### VQ-VAE Pipeline

Stage 1 keeps `HeuristicCodebookFallback` as the active quantizer. Prompt
construction still receives `QuantizationResult` from the codebook boundary and
emits `codebook=HEURISTIC_FALLBACK` when the trained VQ-VAE path is unavailable.

## Architecture

Stage 1 introduces a production-facing local runtime contract over the existing
pipeline. The contract removes development naming from first-party surfaces but
does not duplicate vector math or prompt logic.

The CLI composes:

- persona YAML loaded from a configured path;
- SQLDelight-backed session storage at a configured runtime DB path;
- shared `VectorWriteService`;
- `JvmInferenceExecutor`;
- `OpenAiResponsesLlmClient`;
- existing `DevelopmentMessagePipeline` through the production-facing wrapper.

Provider-specific HTTP details stay in the OpenAI adapter. The adapter takes a
`BuiltPrompt`, sends the system/persona/user layers as provider input, expects a
JSON object matching `LlmOutput`, and validates response shape only enough to
parse into the core model. Schema enforcement remains in `LlmOutputValidator`.

## CLI Behavior

The root application accepts local commands:

```text
openeden chat --message "..." [--user local] [--debug]
openeden state [--user local]
```

Configuration is read from environment variables:

- `OPENEDEN_LLM_PROVIDER=openai`
- `OPENEDEN_OPENAI_API_KEY`
- `OPENEDEN_OPENAI_MODEL` with a conservative default
- `OPENEDEN_PERSONA_PATH`, default `persona/default.yaml`
- `OPENEDEN_RUNTIME_DB_PATH`, default `data/runtime/openeden.db`
- `OPENEDEN_LOCAL_USER_ID`, default `local`

For local one-on-one use, the session is `CLI:<userId>`. Debug output includes
trace tags, current vector, Omega, and evolution index. Non-debug chat output
prints only the validated response text.

## Error Handling

- Missing OpenAI credentials fail before a provider request.
- Invalid provider JSON returns validator errors and does not mutate vector
  state, because mutation occurs only after `LlmOutputValidator` accepts output.
- Missing or invalid persona config fails startup with the existing loader error.
- Missing runtime DB directories are created by CLI/server composition before
  opening SQLDelight.

## Testing

Focused tests cover:

1. production-facing runtime contract maps local requests to `CLI:<userId>`;
2. local config defaults and required OpenAI credential validation;
3. OpenAI adapter builds a Responses API request from `BuiltPrompt` and parses
   JSON output text into `LlmOutput`;
4. CLI chat prints only response text by default and debug state when requested;
5. CLI state persists evolution index across store reopen.

## Acceptance Criteria

- A local user can run the CLI, send a message, and receive a validated
  response from the configured provider.
- Session vector, Omega, ShockState, and `evolution_index` persist across
  process restarts.
- Invalid LLM output does not mutate vector state.
- Heartbeat behavior remains unchanged and owner-only.
- `.\gradlew.bat :server:test` and relevant root CLI tests pass.
