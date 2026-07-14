#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";

import {
  chatCompletionBody,
  chatCompletionsUrl,
  claimUniqueText,
  generationPrompt,
  validateItem,
} from "./user-affect-corpus-lib.mjs";

const ROOT = process.cwd();
const inputPath = resolve(process.argv[2] ?? "data/training/user-affect-v2.final.jsonl");
const outputPath = resolve(process.argv[3] ?? "data/training/user-affect-v2.final.unique.jsonl");
const usagePath = resolve(process.argv[4] ?? "data/training/user-affect-v2.duplicate-repair.usage.jsonl");
const endpoint = process.env.OPENEDEN_AFFECT_LABEL_ENDPOINT;
const apiKey = process.env.OPENEDEN_AFFECT_LABEL_API_KEY;
const batchSize = 4;
const records = readJsonl(inputPath);
const requests = new Map(buildRequests(records.length));
const owners = new Map();
const replacements = new Set();
const output = [];

for (const record of records) {
  const normalized = normalize(record.text);
  if (!owners.has(normalized)) {
    owners.set(normalized, record.sampleId);
    output.push(record);
  } else {
    replacements.add(record.sampleId);
  }
}

if (replacements.size === 0) {
  fs.copyFileSync(inputPath, outputPath);
  console.log("duplicate_repairs=0");
  process.exit(0);
}
if (!endpoint || !apiKey) throw new Error("Set OPENEDEN_AFFECT_LABEL_ENDPOINT and OPENEDEN_AFFECT_LABEL_API_KEY.");

const byId = new Map(records.map((record) => [record.sampleId, record]));
let usageTotals = { promptTokens: 0, completionTokens: 0, totalTokens: 0 };
const repairIds = [...replacements];
for (let start = 0; start < repairIds.length; start += batchSize) {
  const ids = repairIds.slice(start, start + batchSize);
  const batch = ids.map((sampleId) => ({
    request: requestFor(sampleId, records.length),
    item: byId.get(sampleId),
  }));
  const repaired = await completeWithRetries(batch);
  for (const { request, item } of batch) {
    const candidate = validateItem(repaired.get(request.sampleId), request, { requireGateJustification: true });
    claimUniqueText(candidate.text, request.sampleId, owners);
    output.push({ ...item, ...candidate, duplicateRepairedBy: "gpt-5.5" });
  }
  console.log(`repaired=${Math.min(start + ids.length, repairIds.length)}/${repairIds.length}`);
}

output.sort((left, right) => left.sampleId.localeCompare(right.sampleId));
fs.mkdirSync(path.dirname(outputPath), { recursive: true });
fs.writeFileSync(outputPath, `${output.map(JSON.stringify).join("\n")}\n`, "utf8");
console.log(`duplicate_repairs=${replacements.size}`);
console.log(`usage_tokens=${usageTotals.totalTokens}`);

async function completeWithRetries(batch) {
  let lastError;
  for (let attempt = 0; attempt < 4; attempt += 1) {
    try {
      const response = await fetch(chatCompletionsUrl(endpoint), {
        method: "POST",
        headers: { Authorization: `Bearer ${apiKey}`, "Content-Type": "application/json" },
        body: JSON.stringify(chatCompletionBody("gpt-5.5", generationPrompt(batch.map(({ request }) => request), [...owners.keys()].slice(-128)))),
      });
      const body = await response.text();
      if (!response.ok) throw new Error(`HTTP ${response.status}: ${body.slice(0, 300)}`);
      const envelope = JSON.parse(body);
      if (!envelope.usage?.total_tokens) throw new Error("Repair response did not report usage.total_tokens");
      usageTotals = addUsage(usageTotals, envelope.usage);
      fs.appendFileSync(usagePath, `${JSON.stringify({ model: "gpt-5.5", usage: envelope.usage })}\n`, "utf8");
      const parsed = JSON.parse(stripFence(envelope.choices?.[0]?.message?.content ?? envelope.output_text ?? body));
      const items = new Map((parsed.items ?? []).map((item) => [item.sampleId, item.text ? item : { ...item, text: item.message }]));
      for (const { request } of batch) if (!items.has(request.sampleId)) throw new Error(`Response missing ${request.sampleId}`);
      for (const { request } of batch) validateItem(items.get(request.sampleId), request, { requireGateJustification: true });
      return items;
    } catch (error) {
      lastError = error;
    }
  }
  if (batch.length > 1) {
    const midpoint = Math.ceil(batch.length / 2);
    const left = await completeWithRetries(batch.slice(0, midpoint));
    const right = await completeWithRetries(batch.slice(midpoint));
    return new Map([...left, ...right]);
  }
  throw lastError;
}

function requestFor(sampleId, count) {
  const index = Number.parseInt(sampleId.slice(-6), 10);
  const band = ["very_low", "low", "gate_transition", "moderate", "explicit"][index % 5];
  const ranges = { very_low: [0.05, 0.35], low: [0.35, 0.5], gate_transition: [0.5, 0.65], moderate: [0.65, 0.8], explicit: [0.8, 0.98] };
  const original = byId.get(sampleId);
  return {
    sampleId,
    confidenceBand: original.confidenceBand ?? band,
    confidenceRange: ranges[original.confidenceBand ?? band],
    mechanism: original.mechanism,
    mechanisms: original.mechanisms,
    nearRuntimeGate: original.nearRuntimeGate,
    targetConfidence: original.targetConfidence,
    auditHighConfidence: false,
  };
}

function readJsonl(file) { return fs.readFileSync(file, "utf8").split(/\r?\n/).filter(Boolean).map(JSON.parse); }
function normalize(value) { return String(value).normalize("NFKC").toLowerCase().replace(/[\s\p{P}\p{S}]+/gu, ""); }
function stripFence(value) { return String(value).trim().replace(/^```(?:json)?\s*/iu, "").replace(/\s*```$/u, ""); }
function addUsage(totals, usage) {
  return { promptTokens: totals.promptTokens + Number(usage.prompt_tokens ?? 0), completionTokens: totals.completionTokens + Number(usage.completion_tokens ?? 0), totalTokens: totals.totalTokens + Number(usage.total_tokens ?? 0) };
}
function buildRequests(count) { return Array.from({ length: count }, (_, index) => ({ sampleId: `UAV2_${String(index).padStart(6, "0")}` })); }
function resolve(file) { return path.resolve(ROOT, file); }
