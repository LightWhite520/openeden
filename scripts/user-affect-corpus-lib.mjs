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
    return {
      sampleId: `UAV2_${String(index).padStart(6, "0")}`,
      confidenceBand: band.id,
      confidenceRange: [band.min, band.max],
      mechanism: MECHANISMS[Math.floor(random() * MECHANISMS.length)],
      nearRuntimeGate,
      targetConfidence: round4(targetConfidence),
      auditHighConfidence: band.id === "explicit" && index % 10 === 4,
    };
  });
}

export function countBy(items, key) {
  const counts = {};
  for (const item of items) counts[item[key]] = (counts[item[key]] ?? 0) + 1;
  return counts;
}

export function generationPrompt(batch, excludedTexts) {
  return [
    "生成一批用于中文用户情绪观测模型的监督样本。只返回严格 JSON。",
    "格式：{\"items\":[{\"sampleId\":\"UAV2_000000\",\"text\":\"简体中文用户文本\",\"valence\":0.0,\"arousal\":0.0,\"dominance\":0.0,\"connectionNeed\":0.0,\"openness\":0.0,\"confidence\":0.0}]}",
    "confidence 表示：仅根据这段用户文本，能否可靠推断另外五个情绪维度。它不是标注者对自己答案的信心，也不是情绪强度。",
    "直接且内部一致的情绪表达可高 confidence；事实陈述、信息不足、反讽、引用、否定情绪词、混合信号和依赖上下文的表达应低 confidence。",
    "所有六个值必须是 [0,1] 内有限数。文本必须是 8 到 80 个字符的自然简体中文第一人称用户消息。",
    "不得包含姓名、电话、地址、诊断、人格扮演、助手回复或对标签的解释。每条文本必须唯一。",
    excludedTexts.length > 0 ? `不得重复这些文本：${JSON.stringify(excludedTexts.slice(-128))}` : "",
    `逐条严格遵守 confidenceRange、targetConfidence、mechanism 和 nearRuntimeGate：${JSON.stringify(batch)}`,
  ].filter(Boolean).join("\n");
}

function nearGateConfidence(bandId, index) {
  const offset = (index % 41) / 1000;
  if (bandId === "low") return 0.499 - offset;
  if (bandId === "moderate") return 0.651 + offset;
  return index % 2 === 0 ? 0.501 + offset : 0.649 - offset;
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
