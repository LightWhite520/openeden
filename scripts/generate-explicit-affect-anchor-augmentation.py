#!/usr/bin/env python3
"""Generate explicit short-utterance anchor samples for Thymos-6D retraining."""

from __future__ import annotations

import argparse
import json
import random
from pathlib import Path


LABELS = ("valence", "arousal", "dominance", "connectionNeed", "openness", "confidence")


PATTERNS = [
    (
        "explicit_love_short",
        [
            "我爱你",
            "爱你",
            "最爱你了",
            "我真的爱你",
            "我好爱你",
            "爱死你了",
            "我超爱你",
            "我一直都爱你",
        ],
        (0.92, 0.58, 0.36, 0.90, 0.94, 0.96),
        0.28,
    ),
    (
        "explicit_like_short",
        [
            "我喜欢你",
            "喜欢你",
            "真的很喜欢你",
            "我很喜欢你",
            "我超喜欢你",
            "我挺喜欢你的",
            "越来越喜欢你了",
        ],
        (0.88, 0.50, 0.40, 0.82, 0.90, 0.94),
        0.16,
    ),
    (
        "longing_attachment",
        [
            "我想你",
            "想你了",
            "好想你",
            "有点想你",
            "突然很想你",
            "我想见你",
            "想抱抱你",
            "想一直陪着你",
        ],
        (0.76, 0.55, 0.28, 0.92, 0.86, 0.92),
        0.15,
    ),
    (
        "comfort_trust_gratitude",
        [
            "谢谢你",
            "谢谢你陪我",
            "你真好",
            "你真的很好",
            "有你真好",
            "你让我安心",
            "我相信你",
            "跟你说话很舒服",
        ],
        (0.84, 0.38, 0.44, 0.74, 0.82, 0.93),
        0.13,
    ),
    (
        "affectionate_touch",
        [
            "抱抱",
            "来抱抱",
            "抱一下",
            "快抱抱我",
            "亲亲",
            "摸摸头",
            "贴贴",
            "过来贴贴",
        ],
        (0.86, 0.46, 0.34, 0.95, 0.90, 0.93),
        0.10,
    ),
    (
        "explicit_hostile_short",
        [
            "操你妈",
            "你妈死了",
            "傻逼",
            "滚",
            "去死",
            "fuck you",
            "shut up",
            "废物",
        ],
        (0.09, 0.86, 0.42, 0.05, 0.04, 0.95),
        0.10,
    ),
    (
        "ambiguous_short_boundary",
        [
            "嗯",
            "哦",
            "好吧",
            "随便",
            "还行",
            "笑死",
            "你说呢",
            "算了",
            "没事",
            "行",
        ],
        (0.46, 0.36, 0.48, 0.38, 0.42, 0.34),
        0.08,
    ),
]


PREFIXES = ["", "", "", "欸，", "喂，", "说真的，", "其实", "现在只想说，"]
SUFFIXES = ["", "", "", "。", "！", "！！", "啦", "呀", "，真的", "，可以吗"]
LOVE_SUFFIXES = ["", "", "，笨蛋", "，我的意思是认真的", "，不是开玩笑", "，想让你知道"]
HOSTILE_SUFFIXES = ["", "", "。", "！", "！！", "，别装了", "，懂？"]


def jitter(value: float, amount: float, rng: random.Random) -> float:
    return round(min(1.0, max(0.0, value + rng.uniform(-amount, amount))), 4)


def weighted_choice(rng: random.Random) -> tuple[str, list[str], tuple[float, ...], float]:
    total = sum(pattern[3] for pattern in PATTERNS)
    cursor = rng.random() * total
    for pattern in PATTERNS:
        cursor -= pattern[3]
        if cursor <= 0:
            return pattern
    return PATTERNS[-1]


def build_text(mechanism: str, phrase: str, rng: random.Random) -> str:
    if mechanism == "explicit_hostile_short":
        suffixes = HOSTILE_SUFFIXES
        prefix = rng.choice(["", "", "呵呵，", "听好了，"])
    elif mechanism in {"explicit_love_short", "explicit_like_short", "longing_attachment"}:
        suffixes = SUFFIXES + LOVE_SUFFIXES
        prefix = rng.choice(PREFIXES)
    else:
        suffixes = SUFFIXES
        prefix = rng.choice(PREFIXES)
    text = f"{prefix}{phrase}{rng.choice(suffixes)}"
    if rng.random() < 0.08 and mechanism != "explicit_hostile_short":
        text = text.replace("你", "妳", 1)
    if rng.random() < 0.06 and mechanism in {"explicit_love_short", "explicit_like_short"}:
        text = text.replace("我", "窝", 1)
    if rng.random() < 0.04 and mechanism == "explicit_hostile_short":
        text = text.upper()
    return text.strip()


def confidence_band(confidence: float) -> str:
    if confidence >= 0.9:
        return "explicit"
    if confidence >= 0.65:
        return "clear"
    if confidence >= 0.5:
        return "moderate"
    return "ambiguous"


def records(count: int, seed: int) -> list[dict]:
    rng = random.Random(seed)
    result: list[dict] = []
    seen: set[str] = set()
    attempts = 0
    while len(result) < count and attempts < count * 200:
        attempts += 1
        mechanism, phrases, base, _weight = weighted_choice(rng)
        phrase = rng.choice(phrases)
        text = build_text(mechanism, phrase, rng)
        if text in seen:
            continue
        seen.add(text)
        valence, arousal, dominance, connection_need, openness, confidence = base
        row = {
            "sampleId": f"UAANCHOR_{len(result):06d}",
            "text": text,
            "valence": jitter(valence, 0.035, rng),
            "arousal": jitter(arousal, 0.055, rng),
            "dominance": jitter(dominance, 0.055, rng),
            "connectionNeed": jitter(connection_need, 0.04, rng),
            "openness": jitter(openness, 0.04, rng),
            "confidence": jitter(confidence, 0.035 if confidence >= 0.8 else 0.08, rng),
            "confidenceBand": confidence_band(confidence),
            "mechanism": mechanism,
            "mechanisms": ["explicit_anchor", mechanism],
            "nearRuntimeGate": False,
            "targetConfidence": confidence,
            "generatedBy": "deterministic-explicit-anchor-augmentation",
            "finalLabelModel": "human-rule",
            "reviewedBy": "human-rule",
            "escalatedBy": None,
            "generatedConfidence": confidence,
            "tags": ["explicit-anchor-v1", "confidence-calibration", mechanism],
        }
        result.append(row)
    if len(result) != count:
        raise RuntimeError(f"Generated {len(result)} unique samples, expected {count}")
    return result


def write_jsonl(path: Path, rows: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--count", type=int, default=2048)
    parser.add_argument("--seed", type=int, default=0x71A6C0DE)
    parser.add_argument("--output", type=Path, default=Path("data/training/user-affect-v2.explicit-anchor-v1.jsonl"))
    args = parser.parse_args()
    rows = records(args.count, args.seed)
    write_jsonl(args.output, rows)
    summary = {
        "output": str(args.output),
        "count": len(rows),
        "avgConfidence": round(sum(float(row["confidence"]) for row in rows) / len(rows), 6),
        "mechanisms": {
            mechanism: sum(row["mechanism"] == mechanism for row in rows)
            for mechanism, *_rest in PATTERNS
        },
    }
    print(json.dumps(summary, ensure_ascii=False))


if __name__ == "__main__":
    main()
