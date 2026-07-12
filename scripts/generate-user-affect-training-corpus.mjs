#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";

const DIMENSIONS = ["valence", "arousal", "dominance", "connectionNeed", "openness", "confidence"];
const ROOT = process.cwd();
const args = parseArgs(process.argv.slice(2));
const sampleCount = positiveInt(args.samples, 8192);
const batchSize = positiveInt(args.batch, 16);
const seed = positiveInt(args.seed, 0xaffec726);
const model = args.model ?? "gpt-5.4-mini";
const endpoint = args.endpoint ?? process.env.OPENEDEN_AFFECT_LABEL_ENDPOINT;
const apiKey = args.apiKey ?? process.env.OPENEDEN_AFFECT_LABEL_API_KEY;
const rawPath = resolve(args.raw ?? "data/training/user-affect.raw.jsonl");
const manifestPath = resolve(args.manifest ?? "data/training/user-affect.corpus-manifest.json");
const dryRun = args.dryRun === true;

if (!dryRun && (!endpoint || !apiKey)) {
  throw new Error("Set OPENEDEN_AFFECT_LABEL_ENDPOINT and OPENEDEN_AFFECT_LABEL_API_KEY.");
}
ensureParent(rawPath);
ensureParent(manifestPath);

const existing = readCorpus(rawPath);
const prompts = buildPrompts(sampleCount, seed);
for (let start = 0; start < prompts.length; start += batchSize) {
  const batch = prompts.slice(start, start + batchSize).filter(({ sampleId }) => !existing.has(sampleId));
  if (batch.length === 0) continue;
  const items = dryRun ? dryRunItems(batch) : await labelWithRetries(batch);
  const validated = new Array(batch.length);
  const knownTexts = new Set([...existing.values()].map((entry) => entry.text));
  for (let index = 0; index < batch.length; index += 1) {
    let current;
    try {
      current = validateItem(items.get(batch[index].sampleId), batch[index].sampleId);
    } catch {
      current = await labelClean(batch[index], knownTexts);
    }
    if (knownTexts.has(current.text)) current = await labelClean(batch[index], knownTexts);
    validated[index] = current;
    knownTexts.add(current.text);
  }
  fs.appendFileSync(rawPath, `${validated.map(JSON.stringify).join("\n")}\n`, "utf8");
  for (const item of validated) existing.set(item.sampleId, item);
  console.log(`labeled=${existing.size}/${sampleCount}`);
}

if (existing.size < sampleCount) throw new Error(`Expected ${sampleCount} records, found ${existing.size}`);
const records = [...existing.values()]
  .filter((item) => item.sampleId.startsWith("UA_"))
  .sort((left, right) => left.sampleId.localeCompare(right.sampleId))
  .slice(0, sampleCount);
fs.writeFileSync(manifestPath, `${JSON.stringify({
  schemaVersion: 1,
  sampleCount: records.length,
  batchSize,
  seed,
  model: dryRun ? "deterministic-dry-run" : model,
  rawCorpus: path.relative(ROOT, rawPath),
  dimensions: DIMENSIONS,
  generatedAt: new Date().toISOString(),
}, null, 2)}\n`, "utf8");
console.log(`raw_corpus=${records.length}`);

async function labelWithRetries(batch, excludedTexts = []) {
  let failure;
  for (let attempt = 1; attempt <= 4; attempt += 1) {
    try {
      return await labelBatch(batch, excludedTexts);
    } catch (error) {
      failure = error;
      await sleep(750 * attempt);
    }
  }
  throw failure;
}

async function labelClean(request, knownTexts) {
  for (let attempt = 1; attempt <= 12; attempt += 1) {
    const replacement = await labelWithRetries([request], [...knownTexts]);
    try {
      const next = validateItem(replacement.get(request.sampleId), request.sampleId);
      if (!knownTexts.has(next.text)) return next;
    } catch {
      // Retry only the malformed item; completed corpus rows remain durable.
    }
  }
  throw new Error(`Could not produce a valid unique text for ${request.sampleId}`);
}

async function labelBatch(batch, excludedTexts) {
  const prompt = [
    "Generate a supervised training batch for a Chinese user-affect observation model.",
    "Return strict JSON only: {\"items\":[{\"sampleId\":\"UA_000000\",\"text\":\"简体中文用户文本\",\"valence\":0.0,\"arousal\":0.0,\"dominance\":0.0,\"connectionNeed\":0.0,\"openness\":0.0,\"confidence\":0.0}]}",
    "All six values are finite numbers in [0,1]. valence is negative-to-positive; arousal is calm/depleted-to-activated; dominance is constrained-to-in-control; connectionNeed is space-to-companionship; openness is guarded-to-disclosing; confidence is annotation reliability.",
    "The text must be a plausible first-person Chinese user message, 8 to 80 characters, with no names, phone numbers, addresses, diagnoses, persona roleplay, assistant references, or answer to the user.",
    "Use the requested coverage cues to create diverse examples, including neutral factual text, negation, sarcasm, mixed feelings, boundary requests, loneliness, ordinary gratitude, conflict, and low-information text. Labels are observations, not clinical facts. Every text in this response must be unique; do not use stock phrases.",
    excludedTexts.length > 0 ? `Do not repeat these texts: ${JSON.stringify(excludedTexts.slice(-128))}` : "",
    `Requests: ${JSON.stringify(batch)}`,
  ].join("\n");
  const response = await fetch(endpoint, {
    method: "POST",
    headers: { Authorization: `Bearer ${apiKey}`, "Content-Type": "application/json" },
    body: JSON.stringify({ model, temperature: 0.35, messages: [{ role: "user", content: prompt }] }),
  });
  const body = await response.text();
  if (!response.ok) throw new Error(`HTTP ${response.status}: ${body.slice(0, 300)}`);
  const content = extractContent(body);
  const parsed = JSON.parse(stripFence(content));
  if (!Array.isArray(parsed.items)) throw new Error("Response did not contain items");
  const items = new Map(parsed.items.map((item) => [item.sampleId, item]));
  for (const request of batch) {
    if (!items.has(request.sampleId)) throw new Error(`Response missing ${request.sampleId}`);
  }
  return items;
}

function buildPrompts(count, corpusSeed) {
  const random = mulberry32(corpusSeed);
  const cues = [
    "negative activated", "negative depleted", "positive calm", "positive activated", "neutral factual",
    "guarded and low information", "open personal disclosure", "explicit need for company", "explicit request for space",
    "mixed pride and anxiety", "sarcastic or ambiguous", "interpersonal conflict", "gratitude", "loss of control",
  ];
  return Array.from({ length: count }, (_, index) => ({
    sampleId: `UA_${String(index).padStart(6, "0")}`,
    coverage: cues[Math.floor(random() * cues.length)],
  }));
}

function validateItem(item, sampleId) {
  if (!item || item.sampleId !== sampleId) throw new Error(`Invalid or missing item ${sampleId}`);
  const text = String(item.text ?? "").trim();
  if (text.length < 8 || text.length > 80 || !/[\u4e00-\u9fff]/u.test(text)) throw new Error(`Invalid text for ${sampleId}`);
  if (/ATRI|助手|人工智能|医生|诊断|电话|地址|身份证/u.test(text)) throw new Error(`Disallowed text for ${sampleId}`);
  const labels = Object.fromEntries(DIMENSIONS.map((key) => {
    const value = Number(item[key]);
    if (!Number.isFinite(value) || value < 0 || value > 1) throw new Error(`Invalid ${key} for ${sampleId}`);
    return [key, round4(value)];
  }));
  return { sampleId, text, ...labels };
}

function readCorpus(filePath) {
  if (!fs.existsSync(filePath)) return new Map();
  return new Map(fs.readFileSync(filePath, "utf8").split(/\r?\n/u).filter(Boolean).map((line) => {
    const item = JSON.parse(line);
    return [item.sampleId, validateItem(item, item.sampleId)];
  }));
}

function dryRunItems(batch) {
  return new Map(batch.map(({ sampleId, coverage }) => [sampleId, {
    sampleId,
    text: `这是用于${coverage}覆盖校验的中文用户训练文本${sampleId}。`,
    valence: 0.5, arousal: 0.5, dominance: 0.5, connectionNeed: 0.5, openness: 0.5, confidence: 0.9,
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
function round4(value) { return Math.round(value * 10000) / 10000; }
function sleep(ms) { return new Promise((resolveSleep) => setTimeout(resolveSleep, ms)); }
function mulberry32(seed) { let value = seed >>> 0; return () => { value += 0x6d2b79f5; let next = value; next = Math.imul(next ^ (next >>> 15), next | 1); next ^= next + Math.imul(next ^ (next >>> 7), next | 61); return ((next ^ (next >>> 14)) >>> 0) / 4294967296; }; }
