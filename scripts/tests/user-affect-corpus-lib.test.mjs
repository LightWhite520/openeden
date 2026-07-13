import assert from "node:assert/strict";
import { existsSync, mkdtempSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import test from "node:test";

import {
  auditCorpus,
  buildRequests,
  chatCompletionsUrl,
  claimUniqueText,
  countBy,
  generationPrompt,
  generationRetryModel,
  ensureUniqueFinalItem,
  needsEscalation,
  needsStandardReview,
  readDurableState,
  resolveModelPlan,
  selectRequestRange,
  sanitizeGeneratedStage,
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

test("request mechanisms are quota-balanced", () => {
  const counts = Object.values(countBy(buildRequests(50, 0xaffec726), "mechanism"));

  assert.equal(counts.length, 12);
  assert.equal(Math.max(...counts) - Math.min(...counts) <= 1, true);
});

test("request ranges preserve global sample IDs", () => {
  const all = buildRequests(100, 7);

  assert.deepEqual(selectRequestRange(all, 20, 23).map((item) => item.sampleId), [
    "UAV2_000020", "UAV2_000021", "UAV2_000022",
  ]);
  assert.throws(() => selectRequestRange(all, 23, 20), /range/i);
});

test("confidence prompt defines text observability", () => {
  const prompt = generationPrompt([buildRequests(1, 7)[0]], []);

  assert.match(prompt, /仅根据这段用户文本/);
  assert.doesNotMatch(prompt, /annotation reliability/i);
});

test("generation prompt never presents a conflicting sample ID", () => {
  const requested = buildRequests(100, 7)[59];
  const prompt = generationPrompt([requested], []);

  assert.match(prompt, /UAV2_000059/);
  assert.doesNotMatch(prompt, /UAV2_000000/);
  assert.match(prompt, /gateJustification/);
});

test("chat completions URL accepts base or full endpoint", () => {
  assert.equal(chatCompletionsUrl("https://example.test/v1"), "https://example.test/v1/chat/completions");
  assert.equal(chatCompletionsUrl("https://example.test/v1/"), "https://example.test/v1/chat/completions");
  assert.equal(chatCompletionsUrl("https://example.test/v1/chat/completions"), "https://example.test/v1/chat/completions");
});

test("text ownership permits resume and rejects another sample", () => {
  const owners = new Map();
  claimUniqueText(" 我今天真的很累。 ", "UAV2_000001", owners);
  claimUniqueText("我今天真的很累", "UAV2_000001", owners);

  assert.throws(
    () => claimUniqueText("我今天真的很累！", "UAV2_000002", owners),
    /duplicate normalized text/i,
  );
});

test("generated stage drops unfinished text conflicts", () => {
  const owners = new Map();
  claimUniqueText("重复文本内容需要被识别。", "UAV2_000001", owners);
  const stage = new Map([
    ["UAV2_000001", { sampleId: "UAV2_000001", text: "重复文本内容需要被识别。" }],
    ["UAV2_000002", { sampleId: "UAV2_000002", text: "重复文本内容需要被识别！" }],
  ]);

  const removed = sanitizeGeneratedStage(stage, new Set(["UAV2_000001"]), owners);

  assert.deepEqual(removed, ["UAV2_000002"]);
  assert.equal(stage.has("UAV2_000001"), true);
  assert.equal(stage.has("UAV2_000002"), false);
});

test("final text conflict is repaired before acceptance", async () => {
  const owners = new Map();
  claimUniqueText("这句话已经被另一个样本使用。", "UAV2_000202", owners);
  let repairCalls = 0;

  const result = await ensureUniqueFinalItem(
    { sampleId: "UAV2_000250", text: "这句话已经被另一个样本使用！" },
    owners,
    async (item) => {
      repairCalls += 1;
      return { ...item, text: "这是经过仲裁后重新写出的唯一文本。" };
    },
  );

  assert.equal(result.text, "这是经过仲裁后重新写出的唯一文本。");
  assert.equal(repairCalls, 1);
});

test("default model plan keeps escalation on gpt-5.5", () => {
  assert.deepEqual(resolveModelPlan({}), {
    generator: "gpt-5.4-mini",
    standard: "gpt-5.5",
    escalation: "gpt-5.5",
  });
});

test("repeated invalid generation escalates review tiers", () => {
  const models = { generator: "mini", standard: "standard", escalation: "standard" };

  assert.equal(generationRetryModel(1, models), "mini");
  assert.equal(generationRetryModel(4, models), "mini");
  assert.equal(generationRetryModel(5, models), "standard");
  assert.equal(generationRetryModel(8, models), "standard");
  assert.equal(generationRetryModel(9, models), "standard");
});

test("5.5 reviews low confidence and hard mechanisms", () => {
  assert.equal(needsStandardReview(request("low", ["direct_disclosure"]), item(0.42)), true);
  assert.equal(needsStandardReview(request("explicit", ["sarcasm"]), item(0.90)), true);
  assert.equal(needsStandardReview(request("explicit", ["direct_disclosure"]), item(0.90)), false);
});

test("escalation review receives cross-gate and large-disagreement cases", () => {
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

test("escalation review receives compound hard cases and repeated review failures", () => {
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
  const auditPath = path.join(directory, "audit.json");
  const result = spawnSync(process.execPath, [
    "scripts/generate-user-affect-training-corpus.mjs",
    "--samples", "10",
    "--batch", "5",
    "--dry-run",
    "--raw", path.join(directory, "raw.jsonl"),
    "--manifest", path.join(directory, "manifest.json"),
    "--audit", auditPath,
  ], { cwd: process.cwd(), encoding: "utf8" });

  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /accepted=10\/10/);
  assert.equal(existsSync(auditPath), true);
  assert.equal(existsSync(path.join(directory, "raw.generated.jsonl")), true);
  assert.equal(existsSync(path.join(directory, "raw.reviewed.jsonl")), true);
  assert.equal(existsSync(path.join(directory, "raw.escalated.jsonl")), true);
  const audit = JSON.parse(readFileSync(auditPath, "utf8"));
  assert.equal(audit.sampleCount, 10);
  assert.equal(audit.nearGateCount >= 3, true);

  const auditOnly = spawnSync(process.execPath, [
    "scripts/generate-user-affect-training-corpus.mjs",
    "--samples", "10",
    "--audit-only",
    "--raw", path.join(directory, "raw.jsonl"),
    "--manifest", path.join(directory, "manifest.json"),
    "--audit", auditPath,
  ], { cwd: process.cwd(), encoding: "utf8" });
  assert.equal(auditOnly.status, 0, auditOnly.stderr);
  assert.match(auditOnly.stdout, /audit_ok=10/);
});

test("audit rejects duplicate normalized text", () => {
  const requests = buildRequests(5, 11);
  const records = requests.map((entry, index) => recordFor(entry, `这是第${index}条用于审计的不同中文文本。`));
  records[1].text = `  ${records[0].text}  `;

  assert.throws(() => auditCorpus(records, requests), /duplicate normalized text/i);
});

test("audit rejects a missing confidence band quota", () => {
  const requests = buildRequests(10, 11);
  const records = requests.slice(0, 9).map((entry, index) => recordFor(entry, `这是第${index}条用于审计的不同中文文本。`));

  assert.throws(() => auditCorpus(records, requests), /sample count/i);
});

test("audit counts escalation provenance independently of model name", () => {
  const requests = buildRequests(10, 11);
  const records = requests.map((entry, index) => recordFor(entry, `这是第${index}条用于升级审计的不同中文文本。`));
  records[0].finalLabelModel = "gpt-5.5";
  records[0].escalatedBy = "gpt-5.5";

  const audit = auditCorpus(records, requests);

  assert.equal(audit.escalationRate, 0.1);
});

test("durable state only completes final records", () => {
  const state = readDurableState([
    { stage: "generated", sampleId: "UAV2_000000" },
    { stage: "final", sampleId: "UAV2_000001" },
  ]);

  assert.deepEqual([...state.completedIds], ["UAV2_000001"]);
  assert.equal(state.generated.has("UAV2_000000"), true);
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

function recordFor(requested, text) {
  return {
    ...item(requested.targetConfidence, { sampleId: requested.sampleId, text }),
    confidenceBand: requested.confidenceBand,
    mechanism: requested.mechanism,
    mechanisms: requested.mechanisms,
    nearRuntimeGate: requested.nearRuntimeGate,
    finalLabelModel: "gpt-5.4-mini",
  };
}
