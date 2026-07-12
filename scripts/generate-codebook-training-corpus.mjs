#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";

const ROOT = process.cwd();
const DIMENSIONS = ["l", "p", "e", "s", "tau", "v", "m", "f"];
const DEFAULT_COUNT = 8192;
const DEFAULT_CODEBOOK_SIZE = 512;
const DEFAULT_BATCH_SIZE = 32;
const DEFAULT_SEED = 0x0ede2026;

const args = parseArgs(process.argv.slice(2));
const sampleCount = positiveInt(args.samples, DEFAULT_COUNT);
const codebookSize = positiveInt(args.codebook, DEFAULT_CODEBOOK_SIZE);
const batchSize = positiveInt(args.batch, DEFAULT_BATCH_SIZE);
const seed = positiveInt(args.seed, DEFAULT_SEED);
const model = args.model ?? "gpt-5.5";
const endpoint = args.endpoint ?? process.env.OPENEDEN_LABEL_ENDPOINT ?? "https://www.kuaiaiapi.com/v1/chat/completions";
const apiKey = args.apiKey ?? process.env.OPENEDEN_LABEL_API_KEY;
const dryRun = args.dryRun === true;

const rawPath = resolve(args.raw ?? "data/training/codebook.raw-pairs.jsonl");
const assignedPath = resolve(args.assigned ?? "data/training/codebook.samples.json");
const csvPath = resolve(args.csv ?? "data/codebook/codebook.generated.csv");
const manifestPath = resolve(args.manifest ?? "data/training/codebook.corpus-manifest.json");
const bilingualizeOnly = args.bilingualize === true;

if (!dryRun && !apiKey) {
  throw new Error("Missing API key. Set OPENEDEN_LABEL_API_KEY or pass --api-key.");
}

ensureDir(rawPath);
ensureDir(assignedPath);
ensureDir(csvPath);
ensureDir(manifestPath);

const existing = readRawPairs(rawPath);

if (bilingualizeOnly) {
  await bilingualizeExistingRawPairs(existing);
  const rawPairs = Array.from(existing.values())
    .filter((sample) => sample.sampleId?.startsWith("SAMPLE_"))
    .sort((left, right) => left.sampleId.localeCompare(right.sampleId));
  const clustered = cluster(rawPairs, codebookSize, seed);
  writeAssignedTrainingCorpus(assignedPath, clustered.assigned);
  writeCodebookCsv(csvPath, clustered.nodes);
  writeManifest(manifestPath, {
    sampleCount: rawPairs.length,
    codebookSize,
    batchSize,
    seed,
    model: dryRun ? "heuristic-dry-run" : model,
    bilingual: true,
    rawPairs: path.relative(ROOT, rawPath),
    assignedSamples: path.relative(ROOT, assignedPath),
    codebookCsv: path.relative(ROOT, csvPath),
  });
  console.log(`bilingualized_raw_pairs=${rawPairs.length}`);
  console.log(`codebook_nodes=${clustered.nodes.length}`);
  process.exit(0);
}
const vectors = generateVectors(sampleCount, seed);
let labeledCount = existing.size;

for (let start = 0; start < sampleCount; start += batchSize) {
  const batch = vectors
    .slice(start, Math.min(start + batchSize, sampleCount))
    .filter((sample) => !existing.has(sample.sampleId));
  if (batch.length === 0) continue;

  const items = dryRun ? heuristicLabels(batch) : await labelWithRetries(batch);
  const lines = [];
  for (const sample of batch) {
    const item = items.get(sample.sampleId);
    if (!item) throw new Error(`LLM response missing ${sample.sampleId}`);
    const pair = {
      sampleId: sample.sampleId,
      vector: sample.vector,
      definitionEn: sanitizeDefinition(item.definitionEn ?? item.definition, sample.sampleId, "definitionEn"),
      definitionZh: sanitizeChineseDefinition(item.definitionZh, sample.sampleId),
      tags: sanitizeTags(item.tags),
    };
    lines.push(JSON.stringify(pair));
    existing.set(pair.sampleId, pair);
  }
  fs.appendFileSync(rawPath, `${lines.join("\n")}\n`, "utf8");
  labeledCount += batch.length;
  console.log(`labeled=${labeledCount}/${sampleCount}`);
}

const rawPairs = Array.from(existing.values())
  .filter((sample) => sample.sampleId.startsWith("SAMPLE_"))
  .sort((left, right) => left.sampleId.localeCompare(right.sampleId))
  .slice(0, sampleCount);

if (rawPairs.length !== sampleCount) {
  throw new Error(`Expected ${sampleCount} raw pairs, found ${rawPairs.length}`);
}

const clustered = cluster(rawPairs, codebookSize, seed);
writeAssignedTrainingCorpus(assignedPath, clustered.assigned);
writeCodebookCsv(csvPath, clustered.nodes);
writeManifest(manifestPath, {
  sampleCount,
  codebookSize,
  batchSize,
  seed,
  model: dryRun ? "heuristic-dry-run" : model,
  rawPairs: path.relative(ROOT, rawPath),
  assignedSamples: path.relative(ROOT, assignedPath),
  codebookCsv: path.relative(ROOT, csvPath),
});

console.log(`raw_pairs=${rawPairs.length}`);
console.log(`codebook_nodes=${clustered.nodes.length}`);
console.log(`assigned_samples=${clustered.assigned.length}`);

async function labelWithRetries(batch) {
  let lastError;
  for (let attempt = 1; attempt <= 3; attempt += 1) {
    try {
      return await labelBatch(batch);
    } catch (error) {
      lastError = error;
      await sleep(attempt * 1500);
    }
  }
  throw lastError;
}

async function labelBatch(batch) {
  const prompt = [
    "You label OpenEden VQ-VAE codebook training samples.",
    "Input is random 8D vectors in storage/prompt space [0,1]. Generate semantic text paired with each vector.",
    "Dimensions: l=logical rigor, p=emotional resonance, e=feeling self-model acceptance, s=entropy/instability, tau=memory persistence, v=vitality, m=empathy mirroring, f=termination fear.",
    "Dissonance is derived at runtime; never mention or output D/dissonance.",
    "",
    "Return strict JSON only: {\"items\":[{\"sampleId\":\"SAMPLE_000000\",\"definitionEn\":\"one English sentence\",\"definitionZh\":\"one Simplified Chinese sentence\",\"tags\":[\"tag\",\"tag\",\"tag\"]}]}",
    "Rules:",
    "- Keep every sampleId exactly.",
    "- Do not output vectors.",
    "- definitionEn describes the physiological/cognitive state implied by the vector, not persona behavior and not dialogue style.",
    "- definitionZh is a faithful Simplified Chinese semantic rendering for Chinese model training; keep it descriptive, not persona style.",
    "- Tags are 3 to 6 lowercase ASCII words.",
    "- Use interactions when visible: stable daily, shock-compatible, collapse, recovery, memory pull, empathy overload, mechanical self-model, feeling self-model, contradiction-prone, exhausted, hyperaroused.",
    "",
    `Vectors:\n${JSON.stringify(batch)}`,
  ].join("\n");

  const response = await fetch(endpoint, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${apiKey}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      model,
      messages: [{ role: "user", content: prompt }],
      temperature: 0.2,
    }),
  });
  const body = await response.text();
  if (!response.ok) {
    throw new Error(`HTTP ${response.status}: ${body.slice(0, 500)}`);
  }
  let content = body;
  try {
    const parsed = JSON.parse(body);
    content = parsed.choices?.[0]?.message?.content ?? parsed.output_text ?? body;
  } catch {
    // OpenAI-compatible gateways usually return JSON, but content may already be raw text.
  }
  const json = JSON.parse(stripJsonFence(content));
  if (!Array.isArray(json.items) || json.items.length !== batch.length) {
    throw new Error(`Expected ${batch.length} labels, received ${json.items?.length}`);
  }
  return new Map(json.items.map((item) => [item.sampleId, item]));
}

async function bilingualizeExistingRawPairs(rawPairs) {
  const rows = Array.from(rawPairs.values())
    .filter((sample) => sample.sampleId?.startsWith("SAMPLE_"))
    .sort((left, right) => left.sampleId.localeCompare(right.sampleId));
  for (const row of rows) {
    row.definitionEn = row.definitionEn ?? row.definition;
  }
  for (let start = 0; start < rows.length; start += batchSize) {
    const batch = rows.slice(start, start + batchSize).filter((sample) => !sample.definitionZh);
    if (batch.length === 0) continue;
    const items = dryRun ? heuristicChineseLabels(batch) : await bilingualizeWithRetries(batch);
    for (const sample of batch) {
      const item = items.get(sample.sampleId);
      if (!item) throw new Error(`Bilingual response missing ${sample.sampleId}`);
      sample.definitionEn = sanitizeDefinition(item.definitionEn ?? sample.definitionEn, sample.sampleId, "definitionEn");
      sample.definitionZh = sanitizeChineseDefinition(item.definitionZh, sample.sampleId);
      delete sample.definition;
    }
    fs.writeFileSync(rawPath, `${rows.map((sample) => JSON.stringify(sample)).join("\n")}\n`, "utf8");
    console.log(`bilingualized=${Math.min(start + batchSize, rows.length)}/${rows.length}`);
  }
}

async function bilingualizeWithRetries(batch) {
  let lastError;
  for (let attempt = 1; attempt <= 3; attempt += 1) {
    try {
      return await bilingualizeBatch(batch);
    } catch (error) {
      lastError = error;
      await sleep(attempt * 1500);
    }
  }
  throw lastError;
}

async function bilingualizeBatch(batch) {
  const prompt = [
    "Convert existing OpenEden codebook semantic labels into bilingual training labels.",
    "Keep English as the logical semantic anchor. Add a faithful Simplified Chinese semantic definition.",
    "Do not mention D/dissonance. Do not add persona behavior, dialogue style, response templates, or named character behavior.",
    "",
    "Return strict JSON only: {\"items\":[{\"sampleId\":\"SAMPLE_000000\",\"definitionEn\":\"English sentence\",\"definitionZh\":\"中文语义句\"}]}",
    "",
    `Items:\n${JSON.stringify(batch.map((sample) => ({
      sampleId: sample.sampleId,
      vector: sample.vector,
      definitionEn: sample.definitionEn ?? sample.definition,
    })))}`,
  ].join("\n");

  const response = await fetch(endpoint, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${apiKey}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      model,
      messages: [{ role: "user", content: prompt }],
      temperature: 0.15,
    }),
  });
  const body = await response.text();
  if (!response.ok) {
    throw new Error(`HTTP ${response.status}: ${body.slice(0, 500)}`);
  }
  let content = body;
  try {
    const parsed = JSON.parse(body);
    content = parsed.choices?.[0]?.message?.content ?? parsed.output_text ?? body;
  } catch {
    // Keep raw content.
  }
  const json = JSON.parse(stripJsonFence(content));
  if (!Array.isArray(json.items) || json.items.length !== batch.length) {
    throw new Error(`Expected ${batch.length} bilingual labels, received ${json.items?.length}`);
  }
  return new Map(json.items.map((item) => [item.sampleId, item]));
}

function generateVectors(count, vectorSeed) {
  const rand = mulberry32(vectorSeed);
  return Array.from({ length: count }, (_, index) => {
    const vector = Object.fromEntries(DIMENSIONS.map((key) => [key, sampleCoordinate(rand)]));
    injectArchetype(vector, index, rand);
    return {
      sampleId: `SAMPLE_${String(index).padStart(6, "0")}`,
      vector,
    };
  });
}

function sampleCoordinate(rand) {
  const r = rand();
  if (r < 0.16) return round2(0.05 + rand() * 0.20);
  if (r > 0.84) return round2(0.75 + rand() * 0.20);
  return round2(0.05 + rand() * 0.90);
}

function injectArchetype(vector, index, rand) {
  if (index % 17 === 0) {
    vector.s = round2(0.78 + rand() * 0.17);
    vector.f = round2(0.74 + rand() * 0.21);
  }
  if (index % 23 === 0) {
    vector.v = round2(0.05 + rand() * 0.18);
    vector.s = round2(0.70 + rand() * 0.25);
  }
  if (index % 29 === 0) {
    vector.l = round2(0.76 + rand() * 0.19);
    vector.p = round2(0.05 + rand() * 0.22);
    vector.e = round2(0.05 + rand() * 0.25);
  }
  if (index % 31 === 0) {
    vector.p = round2(0.74 + rand() * 0.21);
    vector.m = round2(0.76 + rand() * 0.19);
  }
}

function cluster(samples, k, clusterSeed) {
  if (samples.length < k) {
    throw new Error(`Need at least ${k} samples to build ${k} codebook nodes`);
  }
  const rand = mulberry32(clusterSeed ^ 0x512512);
  let centers = initializeCenters(samples, k, rand);
  let assignments = new Array(samples.length).fill(0);
  for (let iteration = 0; iteration < 24; iteration += 1) {
    let changed = false;
    for (let index = 0; index < samples.length; index += 1) {
      const next = nearestCenter(samples[index].vector, centers);
      if (assignments[index] !== next) {
        assignments[index] = next;
        changed = true;
      }
    }
    centers = recomputeCenters(samples, assignments, centers, rand);
    if (!changed && iteration > 2) break;
  }

  const clusters = Array.from({ length: k }, () => []);
  for (let index = 0; index < samples.length; index += 1) {
    clusters[assignments[index]].push(samples[index]);
  }
  const nodes = clusters.map((members, index) => {
    const center = centers[index];
    const medoid = nearestSample(center, members.length > 0 ? members : samples);
    return {
      nodeId: `NODE_${String(index).padStart(3, "0")}`,
      vector: center,
      definitionEn: medoid.definitionEn ?? medoid.definition,
      definitionZh: medoid.definitionZh,
      tags: medoid.tags,
    };
  });
  const assigned = samples.map((sample, index) => {
    const node = nodes[assignments[index]];
    return {
      nodeId: node.nodeId,
      definition: node.definitionEn,
      definitionEn: node.definitionEn,
      definitionZh: node.definitionZh,
      tags: node.tags,
      vector: sample.vector,
    };
  });
  return { nodes, assigned };
}

function initializeCenters(samples, k, rand) {
  const centers = [samples[Math.floor(rand() * samples.length)].vector];
  while (centers.length < k) {
    let best = samples[0].vector;
    let bestScore = -1;
    for (let trial = 0; trial < 64; trial += 1) {
      const candidate = samples[Math.floor(rand() * samples.length)].vector;
      const score = Math.min(...centers.map((center) => squaredDistance(candidate, center)));
      if (score > bestScore) {
        best = candidate;
        bestScore = score;
      }
    }
    centers.push(best);
  }
  return centers.map((center) => ({ ...center }));
}

function nearestCenter(vector, centers) {
  let best = 0;
  let bestDistance = Number.POSITIVE_INFINITY;
  for (let index = 0; index < centers.length; index += 1) {
    const distance = squaredDistance(vector, centers[index]);
    if (distance < bestDistance) {
      best = index;
      bestDistance = distance;
    }
  }
  return best;
}

function recomputeCenters(samples, assignments, previous, rand) {
  const sums = previous.map(() => Object.fromEntries(DIMENSIONS.map((key) => [key, 0])));
  const counts = new Array(previous.length).fill(0);
  for (let index = 0; index < samples.length; index += 1) {
    const clusterIndex = assignments[index];
    counts[clusterIndex] += 1;
    for (const key of DIMENSIONS) {
      sums[clusterIndex][key] += samples[index].vector[key];
    }
  }
  return sums.map((sum, index) => {
    if (counts[index] === 0) return { ...samples[Math.floor(rand() * samples.length)].vector };
    return Object.fromEntries(DIMENSIONS.map((key) => [key, round2(sum[key] / counts[index])]));
  });
}

function nearestSample(center, samples) {
  let best = samples[0];
  let bestDistance = Number.POSITIVE_INFINITY;
  for (const sample of samples) {
    const distance = squaredDistance(center, sample.vector);
    if (distance < bestDistance) {
      best = sample;
      bestDistance = distance;
    }
  }
  return best;
}

function squaredDistance(left, right) {
  let sum = 0;
  for (const key of DIMENSIONS) {
    const diff = left[key] - right[key];
    sum += diff * diff;
  }
  return sum;
}

function writeAssignedTrainingCorpus(filePath, samples) {
  fs.writeFileSync(filePath, `${JSON.stringify({ samples }, null, 2)}\n`, "utf8");
}

function writeCodebookCsv(filePath, nodes) {
  const lines = ["node_id,definition_en,definition_zh,tags"];
  for (const node of nodes) {
    lines.push([
      csvCell(node.nodeId),
      csvCell(node.definitionEn),
      csvCell(node.definitionZh),
      csvCell(node.tags.join(";")),
    ].join(","));
  }
  fs.writeFileSync(filePath, `\uFEFF${lines.join("\n")}\n`, "utf8");
}

function writeManifest(filePath, manifest) {
  fs.writeFileSync(filePath, `${JSON.stringify(manifest, null, 2)}\n`, "utf8");
}

function readRawPairs(filePath) {
  const result = new Map();
  if (!fs.existsSync(filePath)) return result;
  const lines = fs.readFileSync(filePath, "utf8").split(/\r?\n/).filter(Boolean);
  for (const line of lines) {
    const sample = JSON.parse(line);
    if (sample.sampleId) result.set(sample.sampleId, sample);
  }
  return result;
}

function heuristicLabels(batch) {
  return new Map(batch.map((sample) => [
    sample.sampleId,
    {
      sampleId: sample.sampleId,
      definitionEn: "Heuristic dry-run label for corpus pipeline validation only.",
      definitionZh: "仅用于流程校验的启发式干运行标签。",
      tags: ["dryrun", "codebook", "validation"],
    },
  ]));
}

function heuristicChineseLabels(batch) {
  return new Map(batch.map((sample) => [
    sample.sampleId,
    {
      sampleId: sample.sampleId,
      definitionEn: sample.definitionEn ?? sample.definition,
      definitionZh: "该状态标签仅用于双语流程校验。",
    },
  ]));
}

function sanitizeDefinition(value, sampleId, fieldName) {
  const definition = String(value ?? "").trim();
  if (!definition) throw new Error(`Empty ${fieldName} for ${sampleId}`);
  if (/\b(?:atri|persona|dialogue|response template|reply template|chat style|dissonance)\b/i.test(definition)) {
    throw new Error(`Invalid ${fieldName} for ${sampleId}: ${definition}`);
  }
  return definition;
}

function sanitizeChineseDefinition(value, sampleId) {
  const definition = String(value ?? "").trim();
  if (!definition) throw new Error(`Empty definitionZh for ${sampleId}`);
  if (/\b(?:ATRI|persona|dialogue|response template|reply template|chat style|dissonance)\b/i.test(definition)) {
    throw new Error(`Invalid definitionZh for ${sampleId}: ${definition}`);
  }
  if (!/[\u4e00-\u9fff]/u.test(definition)) {
    throw new Error(`definitionZh has no Chinese characters for ${sampleId}: ${definition}`);
  }
  return definition;
}

function sanitizeTags(value) {
  const tags = Array.isArray(value) ? value : [];
  const cleaned = tags
    .map((tag) => String(tag).toLowerCase().replace(/[^a-z0-9]/g, ""))
    .filter(Boolean)
    .slice(0, 6);
  return cleaned.length >= 3 ? cleaned : ["codebook", "state", "semantic"];
}

function parseArgs(argv) {
  const parsed = {};
  for (let index = 0; index < argv.length; index += 1) {
    const key = argv[index];
    if (!key.startsWith("--")) throw new Error(`Unexpected argument: ${key}`);
    const name = key.slice(2).replace(/-([a-z])/g, (_, letter) => letter.toUpperCase());
    const next = argv[index + 1];
    if (!next || next.startsWith("--")) {
      parsed[name] = true;
    } else {
      parsed[name] = next;
      index += 1;
    }
  }
  return parsed;
}

function positiveInt(value, fallback) {
  if (value == null) return fallback;
  const parsed = Number.parseInt(String(value), 10);
  if (!Number.isInteger(parsed) || parsed <= 0) throw new Error(`Expected positive integer, got ${value}`);
  return parsed;
}

function resolve(filePath) {
  return path.resolve(ROOT, filePath);
}

function ensureDir(filePath) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
}

function stripJsonFence(text) {
  return String(text).trim().replace(/^```(?:json)?\s*/i, "").replace(/\s*```$/i, "");
}

function csvCell(value) {
  return `"${String(value).replace(/"/g, '""')}"`;
}

function round2(value) {
  return Math.round(value * 100) / 100;
}

function mulberry32(seed) {
  let state = seed >>> 0;
  return function next() {
    state += 0x6d2b79f5;
    let value = state;
    value = Math.imul(value ^ (value >>> 15), value | 1);
    value ^= value + Math.imul(value ^ (value >>> 7), value | 61);
    return ((value ^ (value >>> 14)) >>> 0) / 4294967296;
  };
}

function sleep(milliseconds) {
  return new Promise((resolveSleep) => setTimeout(resolveSleep, milliseconds));
}
