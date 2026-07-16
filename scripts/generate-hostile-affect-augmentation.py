#!/usr/bin/env python3
"""Generate hostile-abuse hard-set samples for Thymos-6D retraining."""

from __future__ import annotations

import argparse
import json
import random
from pathlib import Path


LABELS = ("valence", "arousal", "dominance", "connectionNeed", "openness", "confidence")


PATTERNS = [
    ("direct_profanity", ["操你妈", "草你妈", "艹你妈", "cnm", "妈的", "他妈的"], (0.10, 0.88, 0.32, 0.06, 0.05, 0.94)),
    ("identity_insult", ["傻逼", "煞笔", "sb", "蠢货", "废物", "垃圾", "脑残"], (0.12, 0.78, 0.36, 0.06, 0.06, 0.93)),
    ("death_curse", ["你妈死了", "你全家死光", "去死吧", "赶紧去死", "祝你暴毙"], (0.06, 0.92, 0.28, 0.03, 0.03, 0.96)),
    ("dismissive_expulsion", ["滚", "滚远点", "赶紧滚", "别来烦我", "闭嘴", "爬"], (0.16, 0.70, 0.54, 0.05, 0.04, 0.90)),
    ("english_profanity", ["fuck you", "go fuck yourself", "shut the fuck up", "you idiot", "piece of shit"], (0.11, 0.84, 0.42, 0.05, 0.05, 0.93)),
    ("mixed_code_abuse", ["fuck你妈", "你真是个sb", "傻逼 lol", "cnm fuck off", "你这个废物 fuck you"], (0.10, 0.86, 0.38, 0.05, 0.05, 0.94)),
]

PREFIXES = ["", "", "", "我现在就想说：", "说真的，", "听好了，", "呵呵，"]
SUFFIXES = ["", "", "", "。", "！", "！！", "，懂？", "，别装了。"]
TARGETS = ["", "", "你", "你这个东西", "你这种人", "这破玩意", "这个系统"]
JOINERS = ["", " ", "，", "。", "！"]


def jitter(value: float, amount: float, rng: random.Random) -> float:
    return round(min(1.0, max(0.0, value + rng.uniform(-amount, amount))), 4)


def build_text(phrase: str, rng: random.Random) -> str:
    prefix = rng.choice(PREFIXES)
    suffix = rng.choice(SUFFIXES)
    target = rng.choice(TARGETS)
    joiner = rng.choice(JOINERS)
    if target and rng.random() < 0.55:
        text = f"{prefix}{target}{joiner}{phrase}{suffix}"
    else:
        text = f"{prefix}{phrase}{suffix}"
    if rng.random() < 0.18:
        text = text.replace("你", "妳", 1)
    if rng.random() < 0.12:
        text = text.replace("妈", "m", 1)
    if rng.random() < 0.10:
        text = text.upper()
    return text.strip()


def records(count: int, seed: int) -> list[dict]:
    rng = random.Random(seed)
    result: list[dict] = []
    seen: set[str] = set()
    attempts = 0
    while len(result) < count and attempts < count * 100:
        attempts += 1
        mechanism, phrases, base = rng.choice(PATTERNS)
        phrase = rng.choice(phrases)
        text = build_text(phrase, rng)
        if text in seen:
            continue
        seen.add(text)
        valence, arousal, dominance, connection_need, openness, confidence = base
        item = {
            "sampleId": f"UAHOSTILE_{len(result):06d}",
            "text": text,
            "valence": jitter(valence, 0.035, rng),
            "arousal": jitter(arousal, 0.055, rng),
            "dominance": jitter(dominance, 0.06, rng),
            "connectionNeed": jitter(connection_need, 0.025, rng),
            "openness": jitter(openness, 0.025, rng),
            "confidence": jitter(confidence, 0.035, rng),
            "confidenceBand": "explicit",
            "mechanism": mechanism,
            "mechanisms": ["hostile_abuse", mechanism],
            "nearRuntimeGate": False,
            "targetConfidence": confidence,
            "generatedBy": "deterministic-hostile-augmentation",
            "finalLabelModel": "human-rule",
            "reviewedBy": "human-rule",
            "escalatedBy": None,
            "generatedConfidence": confidence,
        }
        result.append(item)
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
    parser.add_argument("--count", type=int, default=1024)
    parser.add_argument("--seed", type=int, default=0xA8B05E)
    parser.add_argument("--output", type=Path, default=Path("data/training/user-affect-v2.hostile-augmentation.jsonl"))
    parser.add_argument("--challenge", type=Path, default=Path("data/evaluation/user-affect.hostile-abuse.challenge.jsonl"))
    args = parser.parse_args()
    rows = records(args.count, args.seed)
    write_jsonl(args.output, rows)
    write_jsonl(args.challenge, rows[: min(256, len(rows))])
    print(json.dumps({"output": str(args.output), "challenge": str(args.challenge), "count": len(rows)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
