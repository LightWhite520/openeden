# User Affect Model Training Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Generate a validated Chinese user-affect corpus and train/export a local DJL-compatible six-output affect classifier.

**Architecture:** A Node corpus generator calls an OpenAI-compatible Chat Completions endpoint only with an environment-supplied key and writes restartable JSONL records. A Python trainer uses the runtime's exact hashed-character bucket features, learns six sigmoid outputs, exports TorchScript, and writes held-out metrics and a model manifest.

**Tech Stack:** Node.js fetch, Python/PyTorch, TorchScript, Kotlin/DJL regression tests, existing local-model artifact conventions.

---

### Task 1: Define and validate the affect corpus

**Files:**
- Create: `scripts/generate-user-affect-training-corpus.mjs`
- Create: `data/training/user-affect.raw.jsonl`
- Create: `data/training/user-affect.corpus-manifest.json`
- Test: `scripts/generate-user-affect-training-corpus.test.mjs`

- [ ] Add strict JSON generation for Chinese text and six `[0,1]` labels: `valence`, `arousal`, `dominance`, `connectionNeed`, `openness`, and `confidence`.
- [ ] Generate only non-diagnostic user observations; reject persona content, duplicate texts, invalid numbers, missing IDs, and invalid labels.
- [ ] Make batches idempotent by sample ID and persist each valid batch immediately so interrupted API work resumes without duplicate charges.
- [ ] Add deterministic dry-run test coverage for validation, deduplication, and resumed batches.

### Task 2: Train and export the affect model

**Files:**
- Create: `scripts/train-user-affect-model.py`
- Create: `data/models/djl/affect/model.pt`
- Create: `data/models/djl/affect/metadata.json`
- Create: `data/models/djl/affect/metrics.json`

- [ ] Use the same `char.code * 31 + index` bucket representation and input width as `DjlTextAffectAnalyzer`.
- [ ] Split data deterministically into train/validation/test partitions and train a bounded six-output MLP with mean-squared loss.
- [ ] Select the best validation checkpoint, export TorchScript, and record MAE for the five affect coordinates plus confidence in `metrics.json`.
- [ ] Fail training if the corpus is too small, labels are invalid, or test MAE exceeds the configured quality gate.

### Task 3: Wire artifact export and regression verification

**Files:**
- Modify: `scripts/export-local-artifact-djl.py`
- Modify: `docs/local-model-training.md`
- Test: `core/src/jvmTest/kotlin/io/openeden/relationship/DjlTextAffectAnalyzerTest.kt`

- [ ] Export `textAffect` from a local artifact when present.
- [ ] Document environment-only API credentials, corpus generation, training, artifact export, and runtime path configuration.
- [ ] Verify a trained TorchScript model loads through DJL and returns exactly six finite bounded values.

### Task 4: Execute and verify the training run

**Files:**
- Modify: `data/training/user-affect.raw.jsonl`
- Modify: `data/training/user-affect.corpus-manifest.json`
- Create: `data/models/djl/affect/metrics.json`

- [ ] Generate the requested corpus using the selected low-cost model with the key held only in the process environment.
- [ ] Train/export the model and inspect metrics, model metadata, corpus count, and held-out split sizes.
- [ ] Run `:core:jvmTest`, including the DJL affect smoke test, and report exact verification results without printing credentials.
