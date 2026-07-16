#!/usr/bin/env python3
"""Merge user-affect base corpus with augmentation records."""

from __future__ import annotations

import argparse
import json
from pathlib import Path


LABELS = ("valence", "arousal", "dominance", "connectionNeed", "openness", "confidence")


def load_jsonl(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def validate(row: dict, seen_ids: set[str], seen_texts: set[str]) -> bool:
    sample_id = str(row.get("sampleId", ""))
    text = str(row.get("text", "")).strip()
    if not sample_id or not text:
        raise ValueError(f"Invalid row: {row!r}")
    if sample_id in seen_ids:
        raise ValueError(f"Duplicate sampleId: {sample_id}")
    for label in LABELS:
        value = float(row[label])
        if not 0.0 <= value <= 1.0:
            raise ValueError(f"Invalid {label}={value} in {sample_id}")
    seen_ids.add(sample_id)
    if text in seen_texts:
        return False
    seen_texts.add(text)
    return True


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", type=Path, default=Path("data/training/user-affect-v2.final.unique.jsonl"))
    parser.add_argument("--augmentation", type=Path, default=Path("data/training/user-affect-v2.hostile-augmentation.jsonl"))
    parser.add_argument("--output", type=Path, default=Path("data/training/user-affect-v2.hostile-augmented.jsonl"))
    parser.add_argument("--manifest", type=Path, default=Path("data/training/user-affect-v2.hostile-augmented.manifest.json"))
    args = parser.parse_args()

    seen_ids: set[str] = set()
    seen_texts: set[str] = set()
    merged: list[dict] = []
    skipped_duplicates = 0
    for source, rows in (("base", load_jsonl(args.base)), ("augmentation", load_jsonl(args.augmentation))):
        for row in rows:
            if validate(row, seen_ids, seen_texts):
                merged.append(row)
            else:
                skipped_duplicates += 1

    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", encoding="utf-8", newline="\n") as handle:
        for row in merged:
            handle.write(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n")

    manifest = {
        "schemaVersion": 1,
        "base": str(args.base),
        "augmentation": str(args.augmentation),
        "output": str(args.output),
        "sampleCount": len(merged),
        "skippedDuplicateTexts": skipped_duplicates,
    }
    args.manifest.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(manifest, ensure_ascii=False))


if __name__ == "__main__":
    main()
