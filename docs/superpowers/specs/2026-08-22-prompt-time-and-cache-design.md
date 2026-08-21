# System Time And Prompt Cache Design

## Goal

Inject the current system time into every development turn while preserving a stable prompt prefix so provider prompt caching can reuse the logical rules and persona data.

## Design

The pipeline formats `nowMs` as `yyyy-MM-dd HH:mm` in `Asia/Shanghai` and passes the result into `PromptInput`. The prompt builder keeps the English logical contract and Chinese persona data in stable prompt layers, while moving Bio-Core state, runtime state, relationship state, memories, and the formatted time into a separate dynamic context layer. The time field is rendered last in that dynamic context so it cannot invalidate an earlier stable prefix.

`BuiltPrompt` gains `contextText` between `personaText` and `userText`. Existing callers remain source-compatible through a default empty value. The OpenAI Responses client sends four input messages in this order: stable system, stable developer persona, dynamic developer context, and user input. Diary prompts use an empty context layer.

## Constraints

- Time is prompt context data, not persona logic.
- Formatting is deterministic and testable from an injected clock.
- Static prompt field order is fixed.
- Dynamic context field order is fixed; current time is the final field.
- No provider-specific cache key is added, avoiding incompatibility with relay implementations.

## Verification

- Prompt builder tests assert static fields are separated from dynamic context and time is last.
- Pipeline tests assert the injected `nowMs` is formatted in the configured timezone.
- OpenAI client tests assert four input layers and preserve existing structured-output behavior.
- Core common and JVM test suites are run before completion.
