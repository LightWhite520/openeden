# OpenAI Prompt Caching Design

## Goal

Increase reuse of OpenEden's stable system and persona prefix on OpenAI Responses API requests without moving dynamic Bio-Core, memory, time, or user content ahead of the cache boundary.

## Request Shape

Normal requests retain their semantic order:

1. stable system instructions;
2. stable immutable-starting-point persona;
3. dynamic VQ-VAE/runtime/memory context;
4. current user input.

For GPT-5.6 and later, the persona `input_text` block carries `prompt_cache_breakpoint: {"mode":"explicit"}` and the request uses `prompt_cache_options.mode = "explicit"`. This prevents the changing context and user message from producing paid cache writes. Earlier models retain implicit caching and receive only a stable cache key.

## Cache Identity

The client derives `prompt_cache_key` from a SHA-256 fingerprint of the model, stable system text, stable persona text, and structured-output schema. Requests with the same rendered stable prefix therefore share routing without exposing persona content or session identifiers. `disabled` mode removes all cache fields; `explicit` mode supports compatible aliases that automatic model detection cannot identify.

## Compatibility

Server configuration exposes `openeden.llm.promptCachingMode` with `auto`, `explicit`, and `disabled`. `auto` is the default. It selects explicit-only caching for GPT-5.6 or later and implicit caching for older model names. Existing custom base URLs can disable the fields if their OpenAI-compatible implementation rejects them.

## Observability

`LlmCacheMetrics` records `cache_write_tokens` in addition to input and cached tokens. Aggregation and trace attributes retain token-weighted cache-read rate and expose write-token totals, ordinary uncached tokens, and the number of requests with a cache read. The API DTO exposes the same fields.

## Constraints

- Persona content remains in `persona/*.yaml`; no personality behavior enters Kotlin.
- VQ-VAE state and derived D remain in dynamic context after the breakpoint.
- Request execution remains suspend-based and non-blocking; no new blocking I/O is introduced.
- Cache metadata never changes runtime state, vector math, or output validation.

## Verification

JVM client tests inspect buffered and streaming request JSON, model-mode behavior, stable key behavior, and parsing of read/write token usage. Common tests cover metric validation and aggregation. Server tests cover configuration parsing where practical, followed by the relevant Gradle test suites.
