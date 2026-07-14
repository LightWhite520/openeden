# Affect Confidence Corpus Generation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Generate and audit 8,192 new Chinese user-affect records whose confidence labels match the runtime's text-observability gates.

**Architecture:** A testable JavaScript library creates deterministic confidence strata and coverage requests, validates model responses, generates with `gpt-5.4-mini`, and routes a bounded high-value review subset through `gpt-5.5`. Gate-near samples are always reviewed; hard mechanisms are deterministically sampled to keep review cost near 25-35%. The CLI appends validated stage records durably, records provider usage, stops at a token budget, resumes by sample ID, then writes a non-sensitive manifest and audit report. No model training or JVM runtime changes are in scope.

**Tech Stack:** Node.js ESM, built-in `node:test`, OpenAI-compatible chat-completions HTTP endpoint, JSONL.

---

### Task 1: Extract Deterministic Corpus Planning

**Files:**
- Create: `scripts/user-affect-corpus-lib.mjs`
- Create: `scripts/tests/user-affect-corpus-lib.test.mjs`
- Modify: `scripts/generate-user-affect-training-corpus.mjs`

- [ ] **Step 1: Write failing tests for quotas and confidence semantics**

```javascript
test("8192 requests cover five confidence bands", () => {
  const requests = buildRequests(8192, 0xaffec726);
  assert.equal(requests.length, 8192);
  assert.deepEqual(countBy(requests, "confidenceBand"), {
    very_low: 1639, low: 1639, gate_transition: 1638,
    moderate: 1638, explicit: 1638,
  });
  assert.ok(requests.filter((item) => item.nearRuntimeGate).length >= 2048);
});

test("confidence prompt defines text observability", () => {
  const prompt = generationPrompt([buildRequests(1, 7)[0]], []);
  assert.match(prompt, /仅根据这段用户文本/);
  assert.doesNotMatch(prompt, /annotation reliability/i);
});
```

- [ ] **Step 2: Run tests and verify module-not-found failure**

Run: `node --test scripts/tests/user-affect-corpus-lib.test.mjs`

Expected: FAIL because `user-affect-corpus-lib.mjs` does not exist.

- [ ] **Step 3: Implement deterministic requests**

```javascript
export const CONFIDENCE_BANDS = Object.freeze([
  { id: "very_low", min: 0.05, max: 0.35 },
  { id: "low", min: 0.35, max: 0.50 },
  { id: "gate_transition", min: 0.50, max: 0.65 },
  { id: "moderate", min: 0.65, max: 0.80 },
  { id: "explicit", min: 0.80, max: 0.98, inclusiveMax: true },
]);

export function buildRequests(count, seed) {
  return Array.from({ length: count }, (_, index) => {
    const band = CONFIDENCE_BANDS[index % CONFIDENCE_BANDS.length];
    return {
      sampleId: `UAV2_${String(index).padStart(6, "0")}`,
      confidenceBand: band.id,
      confidenceRange: [band.min, band.max],
      mechanism: MECHANISMS[(index + seededOffset(seed, index)) % MECHANISMS.length],
      nearRuntimeGate: index < Math.ceil(count * 0.25),
    };
  });
}
```

- [ ] **Step 4: Make the CLI import library functions**

Keep argument parsing and network orchestration in the CLI. Move request construction, prompt construction, validation, routing decisions, and audit aggregation to the library.

- [ ] **Step 5: Run tests**

Run: `node --test scripts/tests/user-affect-corpus-lib.test.mjs`

Expected: quota and semantics tests PASS.

- [ ] **Step 6: Commit**

```powershell
git add scripts/user-affect-corpus-lib.mjs scripts/tests/user-affect-corpus-lib.test.mjs scripts/generate-user-affect-training-corpus.mjs
git commit -m "refactor: plan balanced affect corpus"
```

### Task 2: Implement Validation and Three-Tier Routing

**Files:**
- Modify: `scripts/user-affect-corpus-lib.mjs`
- Modify: `scripts/tests/user-affect-corpus-lib.test.mjs`
- Modify: `scripts/generate-user-affect-training-corpus.mjs`

- [ ] **Step 1: Write failing routing tests**

```javascript
test("5.5 reviews low confidence and hard mechanisms", () => {
  assert.equal(needsStandardReview(request("low", "direct_disclosure"), item(0.42)), true);
  assert.equal(needsStandardReview(request("explicit", "sarcasm"), item(0.90)), true);
  assert.equal(needsStandardReview(request("explicit", "direct_disclosure"), item(0.90)), false);
});

test("independent adjudication only receives escalation cases", () => {
  assert.equal(needsEscalation(item(0.49), item(0.52), request("low", "sarcasm")), true);
  assert.equal(needsEscalation(item(0.42), item(0.44), request("low", "direct_disclosure")), false);
  assert.equal(needsEscalation(item(0.70, { valence: 0.2 }), item(0.70, { valence: 0.36 }), request("moderate", "mixed")), true);
});
```

- [ ] **Step 2: Run tests and verify missing-function failure**

Run: `node --test scripts/tests/user-affect-corpus-lib.test.mjs`

Expected: FAIL because review routing is absent.

- [ ] **Step 3: Implement standard-review and escalation rules**

```javascript
export function needsStandardReview(request, generated) {
  return generated.confidence < 0.65
    || distanceToGate(generated.confidence) <= 0.05
    || HARD_MECHANISMS.has(request.mechanism)
    || request.auditHighConfidence;
}

export function needsEscalation(generated, reviewed, request, failedReviews = 0) {
  return crossesGate(generated.confidence, reviewed.confidence)
    || DIMENSIONS.some((key) => Math.abs(generated[key] - reviewed[key]) > 0.15)
    || failedReviews >= 2
    || request.mechanisms?.length >= 3
    || (distanceToGate(request.targetConfidence) <= 0.02 && !reviewed.gateJustification);
}
```

- [ ] **Step 4: Enforce requested confidence bands and schema**

`validateItem(item, request)` requires matching ID, Simplified Chinese text,
8-80 characters, six finite labels in `[0,1]`, confidence inside the requested
band, no disallowed personal data or roleplay, and a non-empty gate
justification for reviewed near-gate records. Invalid responses are retried at
the same tier and never silently moved to another stratum.

- [ ] **Step 5: Implement tiered API orchestration**

```javascript
const generated = await completeBatch(generatorModel, generationPrompt(batch, excludedTexts));
const reviewed = await reviewWithModel(standardModel, generated.filter(needsStandardReview));
const escalations = reviewed.filter(({ request, generated, reviewed, failures }) =>
  needsEscalation(generated, reviewed, request, failures));
const finalEscalations = await reviewWithModel(escalationModel, escalations);
```

Defaults are `gpt-5.4-mini` for generation and `gpt-5.5` for both review tiers;
the generator uses low reasoning effort and both review tiers use medium.
CLI flags may override model names. API keys remain environment-only. Every
non-dry-run response must report `usage.total_tokens`; the default cumulative
budget is 5,000,000 tokens and generation stops when it is exceeded.

- [ ] **Step 6: Run tests**

Run: `node --test scripts/tests/user-affect-corpus-lib.test.mjs`

Expected: all validation and routing tests PASS.

- [ ] **Step 7: Commit**

```powershell
git add scripts/user-affect-corpus-lib.mjs scripts/tests/user-affect-corpus-lib.test.mjs scripts/generate-user-affect-training-corpus.mjs
git commit -m "feat: add tiered affect corpus adjudication"
```

### Task 3: Add Durable Resume and Corpus Audit

**Files:**
- Modify: `scripts/user-affect-corpus-lib.mjs`
- Modify: `scripts/tests/user-affect-corpus-lib.test.mjs`
- Modify: `scripts/generate-user-affect-training-corpus.mjs`
- Modify: `.gitignore`

- [ ] **Step 1: Write failing audit tests**

```javascript
test("audit rejects missing bands and duplicate normalized text", () => {
  assert.throws(() => auditCorpus([item(0.2), item(0.2)]), /duplicate/i);
  assert.throws(() => auditCorpus(oneBandOnly()), /confidence band quota/i);
});

test("resume keeps only fully adjudicated records", () => {
  const state = readDurableState([stage("generated"), stage("final")]);
  assert.deepEqual([...state.completedIds], ["UAV2_000001"]);
});
```

- [ ] **Step 2: Run tests and verify failure**

Run: `node --test scripts/tests/user-affect-corpus-lib.test.mjs`

Expected: FAIL because audit and durable-stage parsing are absent.

- [ ] **Step 3: Implement durable stage files**

Append generation, 5.5 review, independent 5.5 escalation, and final acceptance to
separate ignored JSONL files. On resume, a sample is complete only when its
final record exists and validates against its deterministic request.

- [ ] **Step 4: Implement audit output**

```javascript
export function auditCorpus(records, requests) {
  return {
    schemaVersion: 2,
    sampleCount: records.length,
    confidenceBands: countConfidenceBands(records),
    mechanisms: countBy(records, "mechanism"),
    nearGateCount: records.filter((item) => distanceToGate(item.confidence) <= 0.05).length,
    modelCalls: countBy(records, "finalLabelModel"),
    escalationRate: records.filter((item) => item.escalatedBy != null).length / records.length,
  };
}
```

Audit fails unless sample count is 8,192, exact band quotas are met, at least
2,048 records are near a runtime gate, mechanism minimums are met, texts and
IDs are unique, and no challenge-set text is present.

- [ ] **Step 5: Ignore raw and intermediate corpora**

```gitignore
data/training/user-affect-v2.*.jsonl
data/training/user-affect-v2.raw.jsonl
```

- [ ] **Step 6: Run tests and dry-run audit**

Run: `node --test scripts/tests/user-affect-corpus-lib.test.mjs`

Run: `node scripts/generate-user-affect-training-corpus.mjs --samples 50 --dry-run --raw build/user-affect-v2.raw.jsonl --manifest build/user-affect-v2.manifest.json --audit build/user-affect-v2.audit.json`

Expected: tests PASS and dry-run produces a valid 50-record development audit using scaled quotas.

- [ ] **Step 7: Commit**

```powershell
git add scripts/user-affect-corpus-lib.mjs scripts/tests/user-affect-corpus-lib.test.mjs scripts/generate-user-affect-training-corpus.mjs .gitignore
git commit -m "feat: audit and resume affect corpus generation"
```

### Task 4: Generate and Verify the 8,192-Record Corpus

**Files:**
- Create locally: `data/training/user-affect-v2.raw.jsonl`
- Create locally: `data/training/user-affect-v2.generated.jsonl`
- Create locally: `data/training/user-affect-v2.reviewed.jsonl`
- Create locally: `data/training/user-affect-v2.escalated.jsonl`
- Create: `data/training/user-affect-v2.corpus-manifest.json`
- Create: `data/training/user-affect-v2.audit.json`

- [ ] **Step 1: Verify credentials are available without printing them**

Run: `if (-not $env:OPENEDEN_AFFECT_LABEL_ENDPOINT -or -not $env:OPENEDEN_AFFECT_LABEL_API_KEY) { throw 'Affect labeling environment is not configured' }`

Expected: exit code 0 and no credential value in output.

- [ ] **Step 2: Generate with tiered labeling**

Run: `node scripts/generate-user-affect-training-corpus.mjs --samples 8192 --batch 16 --generator-model gpt-5.4-mini --standard-model gpt-5.5 --escalation-model gpt-5.5 --max-total-tokens 5000000 --raw data/training/user-affect-v2.raw.jsonl --manifest data/training/user-affect-v2.corpus-manifest.json --audit data/training/user-affect-v2.audit.json`

Expected: generation resumes safely after transient failures and reaches `accepted=8192/8192`.

- [ ] **Step 3: Run the independent audit command**

Run: `node scripts/generate-user-affect-training-corpus.mjs --audit-only --samples 8192 --raw data/training/user-affect-v2.raw.jsonl --manifest data/training/user-affect-v2.corpus-manifest.json --audit data/training/user-affect-v2.audit.json`

Expected: exact confidence quotas, at least 2,048 near-gate samples, no duplicates, no leakage, and aggregate model-call counts. The command exits nonzero on any violation.

- [ ] **Step 4: Run all generator tests and repository whitespace checks**

Run: `node --test scripts/tests/user-affect-corpus-lib.test.mjs`

Run: `git diff --check`

Expected: tests PASS and no whitespace errors.

- [ ] **Step 5: Commit only reproducible code and non-sensitive summaries**

```powershell
git add scripts/user-affect-corpus-lib.mjs scripts/tests/user-affect-corpus-lib.test.mjs scripts/generate-user-affect-training-corpus.mjs .gitignore data/training/user-affect-v2.corpus-manifest.json data/training/user-affect-v2.audit.json
git commit -m "data: generate balanced user affect corpus manifest"
```

Do not add raw, generated, reviewed, or escalated JSONL files to Git. Do not run
the Qwen trainer, export a model, or modify the JVM runtime in this scope.
