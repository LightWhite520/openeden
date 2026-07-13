#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";

import {
  auditCorpus,
  buildRequests,
  chatCompletionsUrl,
  DIMENSIONS,
  generationPrompt,
  needsEscalation,
  needsStandardReview,
  validateItem,
} from "./user-affect-corpus-lib.mjs";

const ROOT = process.cwd();
const args = parseArgs(process.argv.slice(2));
const sampleCount = positiveInt(args.samples, 8192);
const batchSize = positiveInt(args.batch, 16);
const seed = positiveInt(args.seed, 0xaffec726);
const generatorModel = args.generatorModel ?? args.model ?? "gpt-5.4-mini";
const standardModel = args.standardModel ?? "gpt-5.5";
const escalationModel = args.escalationModel ?? "gpt-5.6-sol";
const endpoint = args.endpoint ?? process.env.OPENEDEN_AFFECT_LABEL_ENDPOINT;
const apiKey = args.apiKey ?? process.env.OPENEDEN_AFFECT_LABEL_API_KEY;
const rawPath = resolve(args.raw ?? "data/training/user-affect-v2.raw.jsonl");
const manifestPath = resolve(args.manifest ?? "data/training/user-affect-v2.corpus-manifest.json");
const auditPath = resolve(args.audit ?? "data/training/user-affect-v2.audit.json");
const generatedPath = resolve(args.generated ?? stagePath(rawPath, "generated"));
const reviewedPath = resolve(args.reviewed ?? stagePath(rawPath, "reviewed"));
const escalatedPath = resolve(args.escalated ?? stagePath(rawPath, "escalated"));
const dryRun = args.dryRun === true;
const auditOnly = args.auditOnly === true;

if (!dryRun && !auditOnly && (!endpoint || !apiKey)) {
  throw new Error("Set OPENEDEN_AFFECT_LABEL_ENDPOINT and OPENEDEN_AFFECT_LABEL_API_KEY.");
}
ensureParent(rawPath);
ensureParent(manifestPath);
ensureParent(auditPath);
ensureParent(generatedPath);
ensureParent(reviewedPath);
ensureParent(escalatedPath);

const prompts = buildRequests(sampleCount, seed);
const requestById = new Map(prompts.map((request) => [request.sampleId, request]));
const existing = readCorpus(rawPath, requestById);
if (auditOnly) {
  const records = [...existing.values()].sort((left, right) => left.sampleId.localeCompare(right.sampleId));
  const audit = auditCorpus(records, prompts);
  fs.writeFileSync(auditPath, `${JSON.stringify(audit, null, 2)}\n`, "utf8");
  console.log(`audit_ok=${records.length}`);
  process.exit(0);
}
const generatedStage = readStageFile(generatedPath, requestById, false);
const reviewedStage = readStageFile(reviewedPath, requestById, true);
const escalatedStage = readStageFile(escalatedPath, requestById, true);
const knownTexts = new Set(
  [...existing.values(), ...generatedStage.values()].map((entry) => entry.text),
);
for (let start = 0; start < prompts.length; start += batchSize) {
  const batch = prompts.slice(start, start + batchSize).filter(({ sampleId }) => !existing.has(sampleId));
  if (batch.length === 0) continue;
  const generatedItems = new Map(batch
    .filter(({ sampleId }) => generatedStage.has(sampleId))
    .map(({ sampleId }) => [sampleId, generatedStage.get(sampleId)]));
  const generationMissing = batch.filter(({ sampleId }) => !generatedItems.has(sampleId));
  const newlyGenerated = generationMissing.length === 0
    ? new Map()
    : dryRun
      ? dryRunItems(generationMissing)
      : await completeWithRetries(generatorModel, generationMissing, (requests) => generationPrompt(requests, [...knownTexts]));
  for (const [sampleId, item] of newlyGenerated) generatedItems.set(sampleId, item);
  const generated = [];
  for (const request of batch) {
    const item = await validUniqueGeneration(request, generatedItems.get(request.sampleId), knownTexts);
    generated.push({ request, item });
    if (!generatedStage.has(request.sampleId)) {
      appendStage(generatedPath, item);
      generatedStage.set(request.sampleId, item);
    }
  }

  const standardCandidates = generated.filter(({ request, item }) => needsStandardReview(request, item));
  const standardResults = new Map(standardCandidates
    .filter(({ request }) => reviewedStage.has(request.sampleId))
    .map(({ request }) => [request.sampleId, reviewedStage.get(request.sampleId)]));
  const standardMissing = standardCandidates.filter(({ request }) => !standardResults.has(request.sampleId));
  const newStandardResults = standardMissing.length === 0
    ? new Map()
    : dryRun
      ? new Map(standardMissing.map(({ request, item }) => [request.sampleId, { ...item, gateJustification: "dry-run textual evidence" }]))
      : await reviewCandidates(standardModel, standardMissing, "standard");
  for (const [sampleId, item] of newStandardResults) standardResults.set(sampleId, item);
  const reviewed = generated.map(({ request, item }) => {
    const candidate = standardResults.get(request.sampleId);
    if (!candidate) return { request, generated: item, reviewed: item, failedReviews: 0 };
    try {
      const valid = validateItem(candidate, request, { requireGateJustification: true });
      if (!reviewedStage.has(request.sampleId)) {
        appendStage(reviewedPath, valid);
        reviewedStage.set(request.sampleId, valid);
      }
      return {
        request,
        generated: item,
        reviewed: valid,
        failedReviews: 0,
      };
    } catch {
      return { request, generated: item, reviewed: item, failedReviews: 2 };
    }
  });

  const escalationCandidates = reviewed.filter(({ request, generated: first, reviewed: second, failedReviews }) =>
    needsEscalation(first, second, request, failedReviews));
  const escalationResults = new Map(escalationCandidates
    .filter(({ request }) => escalatedStage.has(request.sampleId))
    .map(({ request }) => [request.sampleId, escalatedStage.get(request.sampleId)]));
  const escalationMissing = escalationCandidates.filter(({ request }) => !escalationResults.has(request.sampleId));
  const newEscalationResults = escalationMissing.length === 0
    ? new Map()
    : dryRun
      ? new Map(escalationMissing.map(({ request, reviewed: item }) => [request.sampleId, { ...item, gateJustification: "dry-run escalation evidence" }]))
      : await reviewCandidates(escalationModel, escalationMissing.map(({ request, reviewed: item }) => ({ request, item })), "escalation");
  for (const { request } of escalationMissing) {
    const raw = newEscalationResults.get(request.sampleId);
    if (!raw) continue;
    const valid = validateItem(raw, request, { requireGateJustification: true });
    appendStage(escalatedPath, valid);
    escalatedStage.set(request.sampleId, valid);
    escalationResults.set(request.sampleId, valid);
  }

  const finalized = reviewed.map(({ request, generated: first, reviewed: second }) => {
    const escalated = escalationResults.get(request.sampleId);
    const finalItem = escalated
      ? validateItem(escalated, request, { requireGateJustification: true })
      : second;
    const finalLabelModel = escalated
      ? escalationModel
      : standardResults.has(request.sampleId) ? standardModel : generatorModel;
    return {
      ...finalItem,
      confidenceBand: request.confidenceBand,
      mechanism: request.mechanism,
      mechanisms: request.mechanisms,
      nearRuntimeGate: request.nearRuntimeGate,
      targetConfidence: request.targetConfidence,
      generatedBy: generatorModel,
      finalLabelModel,
      reviewedBy: standardResults.has(request.sampleId) ? standardModel : null,
      escalatedBy: escalated ? escalationModel : null,
      generatedConfidence: first.confidence,
    };
  });
  fs.appendFileSync(rawPath, `${finalized.map(JSON.stringify).join("\n")}\n`, "utf8");
  for (const item of finalized) {
    existing.set(item.sampleId, item);
    knownTexts.add(item.text);
  }
  console.log(`accepted=${existing.size}/${sampleCount}`);
}

if (existing.size < sampleCount) throw new Error(`Expected ${sampleCount} records, found ${existing.size}`);
const records = [...existing.values()]
  .filter((item) => item.sampleId.startsWith("UAV2_"))
  .sort((left, right) => left.sampleId.localeCompare(right.sampleId))
  .slice(0, sampleCount);
const audit = auditCorpus(records, prompts);
fs.writeFileSync(auditPath, `${JSON.stringify(audit, null, 2)}\n`, "utf8");
fs.writeFileSync(manifestPath, `${JSON.stringify({
  schemaVersion: 2,
  sampleCount: records.length,
  batchSize,
  seed,
  models: dryRun ? { generator: "deterministic-dry-run", standard: "deterministic-dry-run", escalation: "deterministic-dry-run" } : {
    generator: generatorModel,
    standard: standardModel,
    escalation: escalationModel,
  },
  rawCorpus: path.relative(ROOT, rawPath),
  dimensions: DIMENSIONS,
  generatedAt: new Date().toISOString(),
}, null, 2)}\n`, "utf8");
console.log(`raw_corpus=${records.length}`);
console.log(`audit=${path.relative(ROOT, auditPath)}`);

async function completeWithRetries(modelName, requests, promptFactory) {
  let failure;
  for (let attempt = 1; attempt <= 4; attempt += 1) {
    try {
      return await completeBatch(modelName, requests, promptFactory(requests));
    } catch (error) {
      failure = error;
      await sleep(750 * attempt);
    }
  }
  throw failure;
}

async function validUniqueGeneration(request, initial, knownTexts) {
  let candidate = initial;
  for (let attempt = 1; attempt <= 12; attempt += 1) {
    try {
      const next = validateItem(candidate, request);
      if (!knownTexts.has(next.text)) return next;
    } catch {
      // Retry only the malformed item; completed corpus rows remain durable.
    }
    if (dryRun) throw new Error(`Dry-run generated an invalid item ${request.sampleId}`);
    const replacement = await completeWithRetries(
      generatorModel,
      [request],
      (requests) => generationPrompt(requests, [...knownTexts]),
    );
    candidate = replacement.get(request.sampleId);
  }
  throw new Error(`Could not produce a valid unique text for ${request.sampleId}`);
}

async function reviewCandidates(modelName, candidates, tier) {
  if (candidates.length === 0) return new Map();
  return completeWithRetries(
    modelName,
    candidates.map(({ request }) => request),
    () => reviewPrompt(candidates, tier),
  );
}

async function completeBatch(modelName, requests, prompt) {
  const response = await fetch(chatCompletionsUrl(endpoint), {
    method: "POST",
    headers: { Authorization: `Bearer ${apiKey}`, "Content-Type": "application/json" },
    body: JSON.stringify({ model: modelName, temperature: 0.25, messages: [{ role: "user", content: prompt }] }),
  });
  const body = await response.text();
  if (!response.ok) throw new Error(`HTTP ${response.status}: ${body.slice(0, 300)}`);
  const content = extractContent(body);
  const parsed = JSON.parse(stripFence(content));
  if (!Array.isArray(parsed.items)) throw new Error("Response did not contain items");
  const items = new Map(parsed.items.map((item) => [item.sampleId, item]));
  for (const request of requests) {
    if (!items.has(request.sampleId)) throw new Error(`Response missing ${request.sampleId}`);
  }
  return items;
}

function reviewPrompt(candidates, tier) {
  return [
    `你是中文用户情绪六维标注的${tier === "escalation" ? "最高级仲裁者" : "质量复核者"}。只返回严格 JSON：{\"items\":[...]}`,
    "confidence 只表示：仅根据该用户文本，另外五个情绪维度能否被可靠推断。它不是标注者自信，也不是情绪强度。",
    "可以重写文本和全部标签，但必须保留 sampleId，严格落在 request.confidenceRange，文本为 8 到 80 字自然简体中文。",
    "若 confidence 距离 0.5 或 0.65 不超过 0.05，必须提供 gateJustification，简述文本证据为何位于门槛这一侧。",
    `待复核项目：${JSON.stringify(candidates.map(({ request, item }) => ({ request, candidate: item })))}`,
  ].join("\n");
}

function readCorpus(filePath, requests) {
  if (!fs.existsSync(filePath)) return new Map();
  return new Map(fs.readFileSync(filePath, "utf8").split(/\r?\n/u).filter(Boolean).map((line) => {
    const item = JSON.parse(line);
    const request = requests.get(item.sampleId);
    if (!request) throw new Error(`Unknown sample in existing corpus: ${item.sampleId}`);
    return [item.sampleId, { ...item, ...validateItem(item, request) }];
  }));
}

function readStageFile(filePath, requests, requireGateJustification) {
  if (!fs.existsSync(filePath)) return new Map();
  return new Map(fs.readFileSync(filePath, "utf8").split(/\r?\n/u).filter(Boolean).map((line) => {
    const item = JSON.parse(line);
    const request = requests.get(item.sampleId);
    if (!request) throw new Error(`Unknown sample in stage file: ${item.sampleId}`);
    return [item.sampleId, validateItem(item, request, { requireGateJustification })];
  }));
}

function appendStage(filePath, item) {
  fs.appendFileSync(filePath, `${JSON.stringify(item)}\n`, "utf8");
}

function dryRunItems(batch) {
  return new Map(batch.map(({ sampleId, mechanism, targetConfidence }) => [sampleId, {
    sampleId,
    text: `这是用于${mechanism}覆盖校验的中文用户训练文本${sampleId}。`,
    valence: 0.5, arousal: 0.5, dominance: 0.5, connectionNeed: 0.5, openness: 0.5, confidence: targetConfidence,
  }]));
}

function extractContent(body) {
  try {
    const parsed = JSON.parse(body);
    return parsed.choices?.[0]?.message?.content ?? parsed.output_text ?? body;
  } catch {
    return body;
  }
}
function stripFence(value) { return String(value).trim().replace(/^```(?:json)?\s*/iu, "").replace(/\s*```$/u, ""); }
function parseArgs(argv) {
  const result = {};
  for (let index = 0; index < argv.length; index += 1) {
    const token = argv[index];
    if (!token.startsWith("--")) throw new Error(`Unexpected argument: ${token}`);
    const key = token.slice(2).replace(/-([a-z])/gu, (_, letter) => letter.toUpperCase());
    const next = argv[index + 1];
    if (!next || next.startsWith("--")) result[key] = true;
    else { result[key] = next; index += 1; }
  }
  return result;
}
function positiveInt(value, fallback) { const parsed = value == null ? fallback : Number.parseInt(value, 10); if (!Number.isInteger(parsed) || parsed <= 0) throw new Error("Expected a positive integer"); return parsed; }
function resolve(file) { return path.resolve(ROOT, file); }
function ensureParent(file) { fs.mkdirSync(path.dirname(file), { recursive: true }); }
function stagePath(filePath, stage) {
  return filePath.endsWith(".raw.jsonl")
    ? filePath.replace(/\.raw\.jsonl$/u, `.${stage}.jsonl`)
    : filePath.replace(/\.jsonl$/u, `.${stage}.jsonl`);
}
function sleep(ms) { return new Promise((resolveSleep) => setTimeout(resolveSleep, ms)); }
