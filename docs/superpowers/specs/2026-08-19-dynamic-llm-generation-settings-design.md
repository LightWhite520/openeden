# Dynamic LLM Generation Settings Design

## Goal

Derive supported Responses API generation settings from the same pre-ticked 8D state used for quantization and prompt construction. The backend owns the policy; deployment configuration provides only bounds and optional provider limits.

The implementation remains compatible with OpenAI-compatible `/responses` endpoints, including DeepSeek. Provider-specific ignored fields are not detected or compensated for at runtime.

## Scope

This change adds:

- per-turn temperature derived from internal Entropy and Logos;
- per-turn Responses API text verbosity derived from internal Vitality;
- optional static `max_output_tokens` as a provider safety limit;
- configuration for temperature bounds and the optional token limit;
- trace attributes for the resolved generation settings;
- request serialization for both buffered and streaming calls;
- documentation for OpenAI-compatible and DeepSeek endpoint configuration.

This change does not add:

- persona behavior in Kotlin;
- dynamic `top_p`, `reasoning.effort`, presence penalty, or frequency penalty;
- provider capability detection or provider-specific retries;
- automatic disabling of DeepSeek thinking mode;
- dynamic token limits based on Vitality;
- changes to VQ-VAE quantization, heuristic fallback, retrieval, vector write-back, or heartbeat routing.

## State Source And Execution Boundary

Generation settings use the pre-ticked vector, not the original persisted vector. This keeps provider sampling aligned with the state seen by VQ-VAE and the Prompt Builder.

The existing inference block maps the pre-ticked storage-space vector `[0, 1]` into internal space `[-1, 1]` using the current dynamic homeostasis centroid. Generation policy evaluation runs in the same `InferenceDispatcher` block as coordinate mapping and quantization.

The inference result carries both the existing dissonance, quantization, and retrieval mode and a new immutable `LlmGenerationSettings` value. No provider settings are stored in session state.

## Dynamic Policy

Let `S_internal`, `L_internal`, and `V_internal` be centroid-relative coordinates in `[-1, 1]`.

The divergence factor is:

```text
divergence = clamp01((S_internal - L_internal + 2) / 4)
```

Temperature is linearly interpolated within configured bounds:

```text
temperature = temperature_min
    + (temperature_max - temperature_min) * divergence
```

Default bounds are:

```text
temperature_min = 0.2
temperature_max = 1.0
```

This produces the minimum at low Entropy and high Logos, the midpoint at the dynamic centroid, and the maximum at high Entropy and low Logos.

Vitality selects Responses API `text.verbosity`:

```text
V_internal <= -0.35  -> low
V_internal >=  0.35  -> high
otherwise            -> medium
```

The thresholds are constants of the provider-neutral generation policy, not persona data. They control response length capability rather than personality expression.

`top_p` is omitted because OpenAI and DeepSeek recommend changing either temperature or `top_p`, not both. `presence_penalty` and `frequency_penalty` are omitted because they are not current Responses API generation controls and are deprecated by DeepSeek.

## Token Limit And Reasoning

`max_output_tokens` remains an optional static configuration value. It is not derived from Vitality because Responses API output limits include both visible output and reasoning tokens; a low dynamic limit can produce an incomplete response before any user-visible text exists.

`reasoning.effort` remains the existing static model configuration. The generation policy does not switch reasoning effort between turns.

## LLM Boundary

`LlmClient.complete` and `StreamingLlmClient.stream` accept `LlmGenerationSettings` alongside `BuiltPrompt`. The settings type lives in common code and contains:

- `temperature: Float`;
- `verbosity: LlmVerbosity` with `LOW`, `MEDIUM`, and `HIGH`;
- `maxOutputTokens: Int?`.

Dialogue and heartbeat turns pass dynamically resolved settings. Diary generation has no live pre-ticked conversational state and uses a deliberate static value supplied by runtime composition: the midpoint of the configured temperature range, `MEDIUM` verbosity, and the same optional configured `maxOutputTokens` limit.

The Responses API adapter serializes:

```json
{
  "temperature": 0.6,
  "max_output_tokens": 32000,
  "text": {
    "format": { "type": "json_schema" },
    "verbosity": "medium"
  }
}
```

`max_output_tokens` is omitted when unset. Streaming and buffered calls use the same request builder so their settings cannot diverge.

## OpenAI-Compatible Providers

The adapter continues to call `${baseUrl}/responses`. Existing model, API key, base URL, and reasoning-effort configuration remains authoritative.

OpenAI applies temperature and text verbosity according to model support. DeepSeek's Responses API accepts the same request shape. DeepSeek documents that temperature has no effect in thinking mode and verbosity is accepted but has no effect. OpenEden does not detect, suppress, retry, or compensate for those provider semantics; the configured provider determines the effective behavior.

DeepSeek users configure its API key, model, and `https://api.deepseek.com` base URL through the existing OpenAI-compatible endpoint settings. Documentation may call out this use explicitly without introducing a provider enum.

## Configuration And Validation

Server configuration adds:

- `openeden.llm.temperatureMin`, environment key `OPENEDEN_LLM_TEMPERATURE_MIN`, default `0.2`;
- `openeden.llm.temperatureMax`, environment key `OPENEDEN_LLM_TEMPERATURE_MAX`, default `1.0`;
- `openeden.llm.maxOutputTokens`, environment key `OPENEDEN_LLM_MAX_OUTPUT_TOKENS`, optional.

Startup validation requires:

- both temperature bounds are finite and within `[0.0, 2.0]`;
- `temperatureMin <= temperatureMax`;
- `maxOutputTokens`, when present, is positive.

Invalid configuration fails startup before any provider request.

## Traceability

The pipeline appends a generation-policy trace span before LLM inference with:

- `temperature`;
- `verbosity`;
- `max_output_tokens` when configured;
- the internal `S`, `L`, and `V` values used by the policy.

The trace contains no prompt text, persona data, API key, internal reasoning, or raw memory content.

## Error Handling

Policy math clamps its normalized factor and generated temperature even after validated configuration, preventing floating-point drift from escaping provider bounds.

Provider rejection remains a normal typed LLM request failure. The client does not retry without settings because doing so would make the same turn nondeterministically use a different generation policy.

Streaming behavior continues to require a terminal Responses API completion event. Existing output validation remains authoritative for the structured result.

## Testing

Focused tests cover:

- policy endpoints, centroid midpoint, clamping, and Vitality thresholds;
- startup parsing and rejection of invalid bounds or token limits;
- buffered request serialization of temperature, verbosity, and optional token limit;
- omission of `max_output_tokens` when unset;
- identical settings in streaming requests;
- pipeline use of the pre-ticked vector and dynamic centroid;
- trace attributes without prompt or persona leakage;
- diary calls using explicit static settings;
- unchanged VQ-VAE quantization and heartbeat traversal through the full pipeline.

## Architectural Compliance

- Persona-as-Data: no persona wording, scene classification, or emotional behavior is added to Kotlin.
- Non-blocking: coordinate mapping and policy evaluation run inside `InferenceDispatcher`; Ktor request I/O remains suspend/Flow based.
- VQ-VAE: quantization and heuristic fallback remain mandatory and use the same pre-ticked vector as generation policy evaluation.
- 8D invariants: D remains derived, vector deltas still apply to the pre-ticked snapshot, and no vector write path changes.
