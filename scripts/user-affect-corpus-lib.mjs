export const DIMENSIONS = Object.freeze([
  "valence",
  "arousal",
  "dominance",
  "connectionNeed",
  "openness",
  "confidence",
]);

export const CONFIDENCE_BANDS = Object.freeze([
  { id: "very_low", min: 0.05, max: 0.35 },
  { id: "low", min: 0.35, max: 0.50 },
  { id: "gate_transition", min: 0.50, max: 0.65 },
  { id: "moderate", min: 0.65, max: 0.80 },
  { id: "explicit", min: 0.80, max: 0.98, inclusiveMax: true },
]);

export const MECHANISMS = Object.freeze([
  "direct_disclosure",
  "neutral_factual",
  "negation",
  "sarcasm",
  "quoted_emotion",
  "mixed_affect",
  "slang",
  "boundary_request",
  "indirect_connection_need",
  "missing_context",
  "low_information",
  "ordinary_social",
]);

const HARD_MECHANISMS = new Set([
  "negation",
  "sarcasm",
  "quoted_emotion",
  "mixed_affect",
  "slang",
  "missing_context",
  "low_information",
]);

export function buildRequests(count, seed) {
  if (!Number.isInteger(count) || count <= 0) throw new Error("count must be a positive integer");
  const random = mulberry32(seed);
  const nearGateTarget = Math.ceil(count * 0.25);
  let eligibleOrdinal = 0;
  return Array.from({ length: count }, (_, index) => {
    const band = CONFIDENCE_BANDS[index % CONFIDENCE_BANDS.length];
    const eligible = band.id === "low" || band.id === "gate_transition" || band.id === "moderate";
    const nearRuntimeGate = eligible && eligibleOrdinal++ < nearGateTarget;
    const targetConfidence = nearRuntimeGate
      ? nearGateConfidence(band.id, index)
      : band.min + (band.max - band.min) * (0.15 + random() * 0.70);
    const mechanism = MECHANISMS[(index + (seed >>> 0)) % MECHANISMS.length];
    return {
      sampleId: `UAV2_${String(index).padStart(6, "0")}`,
      confidenceBand: band.id,
      confidenceRange: [band.min, band.max],
      mechanism,
      mechanisms: compoundMechanisms(mechanism, index),
      nearRuntimeGate,
      targetConfidence: round4(targetConfidence),
      auditHighConfidence: band.id === "explicit" && index % 10 === 4,
    };
  });
}

export function selectRequestRange(requests, start, end) {
  if (!Number.isInteger(start) || !Number.isInteger(end) || start < 0 || end < start || end > requests.length) {
    throw new Error(`Invalid request range [${start}, ${end}) for ${requests.length} requests`);
  }
  return requests.slice(start, end);
}

export function countBy(items, key) {
  const counts = {};
  for (const item of items) counts[item[key]] = (counts[item[key]] ?? 0) + 1;
  return counts;
}

export function chatCompletionsUrl(endpoint) {
  const normalized = String(endpoint).trim().replace(/\/+$/u, "");
  if (!normalized) throw new Error("Affect labeling endpoint must not be blank");
  return normalized.endsWith("/chat/completions") ? normalized : `${normalized}/chat/completions`;
}

export function claimUniqueText(text, sampleId, owners) {
  const normalized = normalizeAffectText(text);
  const owner = owners.get(normalized);
  if (owner && owner !== sampleId) throw new Error(`Duplicate normalized text: ${sampleId} conflicts with ${owner}`);
  owners.set(normalized, sampleId);
}

export function generationRetryModel(attempt, models) {
  if (attempt <= 4) return models.generator;
  if (attempt <= 8) return models.standard;
  return models.escalation;
}

export function generationPrompt(batch, excludedTexts) {
  const responseSkeleton = {
    items: batch.map(({ sampleId }) => ({
      sampleId,
      text: "在这里生成对应的简体中文用户文本",
      valence: 0.0,
      arousal: 0.0,
      dominance: 0.0,
      connectionNeed: 0.0,
      openness: 0.0,
      confidence: 0.0,
    })),
  };
  return [
    "生成一批用于中文用户情绪观测模型的监督样本。只返回严格 JSON。",
    `必须原样保留下列全部 sampleId，并按此骨架填充：${JSON.stringify(responseSkeleton)}`,
    "confidence 表示：仅根据这段用户文本，能否可靠推断另外五个情绪维度。它不是标注者对自己答案的信心，也不是情绪强度。",
    "直接且内部一致的情绪表达可高 confidence；事实陈述、信息不足、反讽、引用、否定情绪词、混合信号和依赖上下文的表达应低 confidence。",
    "所有六个值必须是 [0,1] 内有限数。文本必须是 8 到 80 个字符的自然简体中文第一人称用户消息。",
    "不得包含姓名、电话、地址、诊断、人格扮演、助手回复或对标签的解释。每条文本必须唯一。",
    excludedTexts.length > 0 ? `不得重复这些文本：${JSON.stringify(excludedTexts.slice(-128))}` : "",
    `逐条严格遵守 confidenceRange、targetConfidence、mechanism 和 nearRuntimeGate：${JSON.stringify(batch)}`,
  ].filter(Boolean).join("\n");
}

export function needsStandardReview(request, generated) {
  return generated.confidence < 0.65
    || distanceToGate(generated.confidence) <= 0.05
    || (request.mechanisms ?? [request.mechanism]).some((value) => HARD_MECHANISMS.has(value))
    || request.auditHighConfidence === true;
}

export function needsEscalation(generated, reviewed, request, failedReviews = 0) {
  const mechanisms = request.mechanisms ?? [request.mechanism];
  return crossesGate(generated.confidence, reviewed.confidence)
    || DIMENSIONS.some((key) => Math.abs(generated[key] - reviewed[key]) > 0.15)
    || failedReviews >= 2
    || mechanisms.length >= 3
    || (distanceToGate(request.targetConfidence) <= 0.02 && !reviewed.gateJustification);
}

export function validateItem(item, request, { requireGateJustification = false } = {}) {
  if (!item || item.sampleId !== request.sampleId) throw new Error(`Invalid or missing item ${request.sampleId}`);
  const text = String(item.text ?? "").trim();
  if (text.length < 8 || text.length > 80 || !/[\u4e00-\u9fff]/u.test(text)) {
    throw new Error(`Invalid text for ${request.sampleId}`);
  }
  if (/ATRI|助手|人工智能|医生|诊断|电话|地址|身份证/u.test(text)) {
    throw new Error(`Disallowed text for ${request.sampleId}`);
  }
  const labels = Object.fromEntries(DIMENSIONS.map((key) => {
    const value = Number(item[key]);
    if (!Number.isFinite(value) || value < 0 || value > 1) throw new Error(`Invalid ${key} for ${request.sampleId}`);
    return [key, round4(value)];
  }));
  const [minimum, maximum] = request.confidenceRange;
  const upperInclusive = request.confidenceBand === "explicit";
  if (labels.confidence < minimum || (upperInclusive ? labels.confidence > maximum : labels.confidence >= maximum)) {
    throw new Error(`Item ${request.sampleId} is outside requested confidence band ${request.confidenceBand}`);
  }
  if (requireGateJustification && distanceToGate(labels.confidence) <= 0.05 && !String(item.gateJustification ?? "").trim()) {
    throw new Error(`Missing gate justification for ${request.sampleId}`);
  }
  return {
    sampleId: request.sampleId,
    text,
    ...labels,
    gateJustification: String(item.gateJustification ?? "").trim() || undefined,
  };
}

export function auditCorpus(records, requests, { challengeTexts = new Set() } = {}) {
  if (records.length !== requests.length) {
    throw new Error(`Corpus sample count ${records.length} does not match expected ${requests.length}`);
  }
  const expectedByBand = countBy(requests, "confidenceBand");
  const actualByBand = countBy(records, "confidenceBand");
  if (JSON.stringify(actualByBand) !== JSON.stringify(expectedByBand)) {
    throw new Error(`Corpus confidence band quota mismatch: ${JSON.stringify(actualByBand)}`);
  }
  const ids = new Set();
  const normalizedTexts = new Set();
  for (const record of records) {
    if (ids.has(record.sampleId)) throw new Error(`Duplicate sample ID: ${record.sampleId}`);
    ids.add(record.sampleId);
    const normalized = normalizeAffectText(record.text);
    if (normalizedTexts.has(normalized)) throw new Error(`Duplicate normalized text: ${record.sampleId}`);
    if (challengeTexts.has(normalized)) throw new Error(`Challenge-set leakage: ${record.sampleId}`);
    normalizedTexts.add(normalized);
  }
  const minimumNearGate = Math.ceil(records.length * 0.25);
  const nearGateCount = records.filter((record) => distanceToGate(record.confidence) <= 0.05).length;
  if (nearGateCount < minimumNearGate) {
    throw new Error(`Near-gate quota ${nearGateCount} is below ${minimumNearGate}`);
  }
  const mechanisms = countBy(records, "mechanism");
  const minimumMechanismCount = Math.floor(records.length / MECHANISMS.length / 2);
  for (const mechanism of MECHANISMS) {
    if ((mechanisms[mechanism] ?? 0) < minimumMechanismCount) {
      throw new Error(`Mechanism quota is low for ${mechanism}`);
    }
  }
  const modelCalls = countBy(records, "finalLabelModel");
  const escalatedCount = records.filter((record) => record.finalLabelModel === "gpt-5.6-sol").length;
  return {
    schemaVersion: 2,
    sampleCount: records.length,
    confidenceBands: actualByBand,
    mechanisms,
    nearGateCount,
    modelCalls,
    escalationRate: records.length === 0 ? 0 : escalatedCount / records.length,
  };
}

export function readDurableState(records) {
  const state = {
    generated: new Map(),
    reviewed: new Map(),
    escalated: new Map(),
    final: new Map(),
    completedIds: new Set(),
  };
  for (const record of records) {
    const sampleId = record.sampleId ?? record.item?.sampleId;
    if (!sampleId || !(record.stage in state) || !(state[record.stage] instanceof Map)) continue;
    state[record.stage].set(sampleId, record.item ?? record);
    if (record.stage === "final") state.completedIds.add(sampleId);
  }
  return state;
}

function nearGateConfidence(bandId, index) {
  const offset = (index % 41) / 1000;
  if (bandId === "low") return 0.499 - offset;
  if (bandId === "moderate") return 0.651 + offset;
  return index % 2 === 0 ? 0.501 + offset : 0.649 - offset;
}

function compoundMechanisms(primary, index) {
  if (index % 40 !== 0) return [primary];
  const values = [primary, "negation", "missing_context", "sarcasm"];
  return [...new Set(values)].slice(0, 3);
}

function distanceToGate(value) {
  return Math.min(Math.abs(value - 0.5), Math.abs(value - 0.65));
}

function crossesGate(left, right) {
  return [0.5, 0.65].some((gate) => (left < gate && right >= gate) || (right < gate && left >= gate));
}

export function normalizeAffectText(value) {
  return String(value).normalize("NFKC").toLowerCase().replace(/[\s\p{P}\p{S}]+/gu, "");
}

function round4(value) {
  return Math.round(value * 10000) / 10000;
}

function mulberry32(seed) {
  let value = seed >>> 0;
  return () => {
    value += 0x6d2b79f5;
    let next = value;
    next = Math.imul(next ^ (next >>> 15), next | 1);
    next ^= next + Math.imul(next ^ (next >>> 7), next | 61);
    return ((next ^ (next >>> 14)) >>> 0) / 4294967296;
  };
}
