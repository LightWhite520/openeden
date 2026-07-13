import assert from "node:assert/strict";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import test from "node:test";

import {
  buildRequests,
  countBy,
  generationPrompt,
  needsEscalation,
  needsStandardReview,
  validateItem,
} from "../user-affect-corpus-lib.mjs";

test("8192 requests cover five confidence bands", () => {
  const requests = buildRequests(8192, 0xaffec726);

  assert.equal(requests.length, 8192);
  assert.deepEqual(countBy(requests, "confidenceBand"), {
    very_low: 1639,
    low: 1639,
    gate_transition: 1638,
    moderate: 1638,
    explicit: 1638,
  });
  assert.ok(requests.filter((item) => item.nearRuntimeGate).length >= 2048);
});

test("confidence prompt defines text observability", () => {
  const prompt = generationPrompt([buildRequests(1, 7)[0]], []);

  assert.match(prompt, /仅根据这段用户文本/);
  assert.doesNotMatch(prompt, /annotation reliability/i);
});

test("5.5 reviews low confidence and hard mechanisms", () => {
  assert.equal(needsStandardReview(request("low", ["direct_disclosure"]), item(0.42)), true);
  assert.equal(needsStandardReview(request("explicit", ["sarcasm"]), item(0.90)), true);
  assert.equal(needsStandardReview(request("explicit", ["direct_disclosure"]), item(0.90)), false);
});

test("5.6-sol receives cross-gate and large-disagreement cases", () => {
  assert.equal(needsEscalation(item(0.49), item(0.52), request("low", ["sarcasm"])), true);
  assert.equal(needsEscalation(item(0.42), item(0.44), request("low", ["direct_disclosure"])), false);
  assert.equal(
    needsEscalation(
      item(0.70, { valence: 0.20 }),
      item(0.70, { valence: 0.36 }),
      request("moderate", ["mixed_affect"]),
    ),
    true,
  );
});

test("5.6-sol receives compound hard cases and repeated review failures", () => {
  assert.equal(needsEscalation(item(0.55), item(0.56), request("gate_transition", ["sarcasm", "negation", "missing_context"])), true);
  assert.equal(needsEscalation(item(0.55), item(0.56), request("gate_transition", ["direct_disclosure"]), 2), true);
});

test("validation rejects confidence outside requested band", () => {
  const requested = request("low", ["low_information"]);
  assert.throws(() => validateItem(item(0.65), requested), /confidence band/i);
  assert.equal(validateItem(item(0.42), requested).confidence, 0.42);
});

test("dry-run CLI routes generated request-item pairs", () => {
  const directory = mkdtempSync(path.join(tmpdir(), "openeden-affect-corpus-"));
  const result = spawnSync(process.execPath, [
    "scripts/generate-user-affect-training-corpus.mjs",
    "--samples", "10",
    "--batch", "5",
    "--dry-run",
    "--raw", path.join(directory, "raw.jsonl"),
    "--manifest", path.join(directory, "manifest.json"),
  ], { cwd: process.cwd(), encoding: "utf8" });

  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /accepted=10\/10/);
});

function request(confidenceBand, mechanisms) {
  const ranges = {
    very_low: [0.05, 0.35],
    low: [0.35, 0.50],
    gate_transition: [0.50, 0.65],
    moderate: [0.65, 0.80],
    explicit: [0.80, 0.98],
  };
  return {
    sampleId: "UAV2_000000",
    confidenceBand,
    confidenceRange: ranges[confidenceBand],
    mechanism: mechanisms[0],
    mechanisms,
    targetConfidence: (ranges[confidenceBand][0] + ranges[confidenceBand][1]) / 2,
    nearRuntimeGate: false,
    auditHighConfidence: false,
  };
}

function item(confidence, overrides = {}) {
  return {
    sampleId: "UAV2_000000",
    text: "我现在说不清楚自己到底是什么感觉。",
    valence: 0.5,
    arousal: 0.5,
    dominance: 0.5,
    connectionNeed: 0.5,
    openness: 0.5,
    confidence,
    ...overrides,
  };
}
