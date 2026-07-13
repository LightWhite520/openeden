# Qwen User Affect Retraining Design

## Goal

Replace the current 32-bucket character-hash affect MLP with a six-dimensional
user-affect model based on the same text encoder and precision used by the 8D
Codebook training pipeline: `Qwen/Qwen3-Embedding-0.6B` with BF16 weights.

The output contract remains:

`[valence, arousal, dominance, connectionNeed, openness, confidence]`

All values remain finite and bounded to `[0, 1]`. Existing confidence gates,
pre-tick scaling, ShockState detection, and 6D-to-8D influence mapping remain
unchanged.

## Selected Approach

Fine-tune the complete Qwen text encoder together with a compact regression
head. This matches the 8D Codebook training metadata, where the same encoder is
stored as BF16 and `freezeTextEncoder` is false. No INT8 or INT4 quantization is
introduced.

The affect head consumes the normalized 1024-dimensional sentence embedding:

```text
Qwen3-Embedding-0.6B -> 1024 -> 256 (GELU + dropout) -> 6 (sigmoid)
```

The current character-hash model remains available only as an explicit
degraded fallback artifact during migration. It is not part of the successful
Qwen inference path.

## GPU Training

Training is CUDA-only. The command must fail before loading the corpus when
CUDA is unavailable; it must never silently fall back to CPU. Training metadata
records the GPU name, CUDA version, weight dtype, seed, batch size, gradient
accumulation, and peak allocated GPU memory.

The target machine is an NVIDIA GeForce RTX 4070 Ti with 12 GB VRAM. The
default profile therefore uses:

- BF16 model weights and autocast where supported.
- Gradient checkpointing.
- A small physical batch with gradient accumulation to preserve the effective
  batch size.
- A lower encoder learning rate than the regression-head learning rate.
- Validation-based checkpoint selection and early stopping.

Out-of-memory failures are explicit. The retry profile may reduce physical
batch size while keeping the effective batch size and optimization schedule
equivalent.

## Data And Evaluation

The existing generated corpus is split deterministically by sample ID into
train, validation, and held-out test partitions. Duplicate or empty text and
out-of-range labels are rejected before training.

Model selection uses validation mean absolute error. Final reporting includes:

- Overall and per-dimension test MAE.
- Comparison against the current character-hash MLP on the identical split.
- A small Chinese challenge set covering negation, sarcasm, indirect need for
  connection, mixed affect, slang, and low-information messages.
- Confidence calibration checks, because confidence controls downstream kernel
  effects rather than serving as a cosmetic output.

The replacement is accepted only when it beats the existing model's held-out
MAE and passes runtime contract tests. Synthetic-label metrics are reported as
teacher-label agreement, not as human-level emotion accuracy.

## Runtime Architecture

The JVM runtime loads the Qwen tokenizer, BF16 text encoder, and affect head as
one affect-model bundle. Tokenization and model inference execute through the
dedicated `InferenceExecutor`; Ktor message handling must not perform model work
directly or block its dispatcher.

The analyzer boundary remains `UserAffectAnalyzer`, so the message pipeline and
kernel logic do not acquire model-specific behavior. Persona data is not added
to the model, trainer, or Kotlin runtime.

The bundle includes metadata for base model, tokenizer, maximum sequence
length, embedding dimension, output labels, precision, and training metrics.
Startup validates these fields and rejects incompatible bundles instead of
silently interpreting the wrong tensor shape.

## Failure Handling

- Missing or incompatible Qwen artifacts fail model initialization with an
  actionable error.
- A per-message inference failure follows the existing deterministic affect
  fallback and emits a degraded trace tag; it does not halt the message
  pipeline.
- Non-finite or incorrectly shaped outputs are rejected before they can reach
  pre-tick or ShockState logic.
- Predictor access remains serialized where required by DJL thread-safety, but
  the work stays inside inference isolation.

## Verification

Verification covers trainer input validation, CUDA-only enforcement, artifact
metadata, tokenizer behavior, six-dimensional output shape and bounds,
confidence gating, fallback behavior, JVM model loading, and an end-to-end
message-pipeline smoke test.

The final run also records cold-start time, warm short-message latency, and
memory usage on the i7-13700K CPU runtime. Training throughput is measured on
the RTX 4070 Ti. No completion claim is made without fresh test and benchmark
output.

## Non-Goals

- Changing the six affect dimensions.
- Changing the 8D biological vector or storing derived D.
- Modifying persona behavior.
- Re-labeling the full corpus in this pass.
- Applying integer quantization that differs from the current 8D base-model
  training setup.
