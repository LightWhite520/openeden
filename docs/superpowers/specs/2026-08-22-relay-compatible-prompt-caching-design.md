# Relay-Compatible Prompt Caching Design

## Goal

Make OpenEden's `auto` prompt-caching behavior compatible with the custom Responses API provider used by Codex while preserving verified cache reads.

## Evidence

The production relay accepts `prompt_cache_key`, `prompt_cache_options.mode = "explicit"`, and structured `input_text`. It returns HTTP 502 only when an `input_text` block contains `prompt_cache_breakpoint`. Repeated synthetic prefixes without the breakpoint produced a verified cache read of 8,960 out of 9,998 input tokens.

## Request Policy

`OpenAiPromptCachingMode` retains its existing public values:

- `AUTO` sends a stable cache key for every provider. For GPT-5.6 or later it also sends explicit cache options. It sends a cache breakpoint only when the base URL identifies the official `api.openai.com` host.
- `EXPLICIT` sends the cache key, explicit cache options, and breakpoint for operators who explicitly declare that their provider supports the complete request shape.
- `DISABLED` sends no cache key, cache options, or breakpoint.

Official-host detection parses the base URL and compares the normalized host exactly with `api.openai.com`. Prefix or suffix string matching is forbidden because lookalike hosts must not gain official-provider behavior.

## Data Flow

The client resolves cache options and breakpoint use independently. The existing stable key remains derived from the model, stable system text, stable persona text, and output schema. Dynamic runtime state, memories, and user input remain after the stable prefix.

No automatic retry is added. Retrying a completed or partially streamed inference could duplicate generation and cost.

## Scope And Invariants

This change affects only OpenAI request serialization and its tests. It does not alter persona data, prompt ordering, runtime state, non-blocking execution, or the VQ-VAE pipeline.

## Verification

Client request tests will cover custom-provider `AUTO`, official-provider `AUTO`, custom-provider `EXPLICIT`, and `DISABLED`. The custom-provider regression test must fail against the current implementation because it currently emits `prompt_cache_breakpoint`. The focused JVM suite and server tests must pass before deployment. Production verification will confirm service health and a successful request without breakpoint-related 502 responses.
