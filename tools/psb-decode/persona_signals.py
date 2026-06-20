#!/usr/bin/env python3
"""
Extract ATRI personality SIGNALS from locally-decoded scenario JSON.

Like distill.py, this reads private_corpus/atri_decoded/*.ks.json and emits ONLY
aggregate frequencies — here, of a PREDEFINED set of linguistic/personality
markers. It never prints a raw line. Counting how often the character uses a
given marker is a "frequency-derived observation", which AGENTS.md permits; the
goal is to ground persona rules in evidence instead of assumption.

Markers are grouped by what they reveal about character:
  - self_reference : how she refers to herself (robotic vs. human framing)
  - register       : polite/formal (です/ます) vs. casual/assertive
  - self_concept   : machine/performance vocabulary (her core identity tension)
  - emotion        : affect vocabulary (warmth, fear, attachment)
  - hesitation     : pause / filler markers (her strongest surface signature)
  - address        : honorifics she attaches to others

Usage:
    python tools/psb-decode/persona_signals.py
    python tools/psb-decode/persona_signals.py --speaker ATR --json

SAFE TO COMMIT: this script and its aggregate output.
NOT SAFE TO COMMIT: the decoded JSON it reads, or any raw line text.
"""
from __future__ import annotations

import argparse
import io
import json
import re
import sys
from collections import Counter
from pathlib import Path

# Console may be GBK on Windows; force UTF-8 so JP markers print.
try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

REPO_ROOT = Path(__file__).resolve().parents[2]
DECODED_DIR = REPO_ROOT / "private_corpus" / "atri_decoded"
CJK = re.compile(r"[぀-ヿ㐀-鿿]")

# Predefined marker sets. Each is a (label, substring) — we count, never print text.
MARKERS = {
    "self_reference": ["私", "わたし", "わたくし", "ボク", "僕", "あたし", "うち", "アトリ"],
    "register_polite": ["です", "ます", "ですから", "でしょう", "ください", "ございます"],
    "register_casual": ["だよ", "だね", "だわ", "なの", "じゃん", "っす"],
    "self_concept": ["高性能", "性能", "ロボット", "機械", "機能", "故障", "システム",
                     "メモリ", "起動", "充電", "プログラム", "エラー"],
    "emotion": ["好き", "嬉し", "うれし", "寂し", "さみし", "怖", "こわ", "悲し", "かなし",
                "泣", "笑", "幸せ", "大切", "守", "ありがとう", "ごめん"],
    "hesitation": ["……", "…", "ー", "えっと", "あの", "その", "あぅ", "うぅ", "えぇ"],
    "address": ["さん", "くん", "君", "ちゃん", "先生", "様"],
}


def iter_files():
    if not DECODED_DIR.exists():
        sys.exit(f"Decoded dir not found: {DECODED_DIR}\nRun decode.ps1 first.")
    return sorted(p for p in DECODED_DIR.glob("*.ks.json") if not p.name.endswith(".resx.json"))


def voiced_lines(doc):
    for scene in doc.get("scenes", []):
        for entry in scene.get("texts", []):
            voice, best = None, ""
            stack = [entry]
            while stack:
                o = stack.pop()
                if isinstance(o, dict):
                    v = o.get("voice")
                    if isinstance(v, str) and voice is None:
                        voice = v
                    stack.extend(o.values())
                elif isinstance(o, list):
                    stack.extend(o)
                elif isinstance(o, str):
                    if CJK.search(o) and len(o) > len(best):
                        best = o
            if voice and best:
                yield voice, best


def speaker_of(voice):
    m = re.match(r"^([A-Za-z]+)_", voice)
    return m.group(1).upper() if m else "?"


def analyze(speaker):
    total = 0
    # per-category: lines containing ANY marker in the group, and per-marker line counts
    group_line_hits = Counter()
    marker_line_hits = Counter()
    for path in iter_files():
        doc = json.load(io.open(path, encoding="utf-8", errors="replace"))
        for voice, text in voiced_lines(doc):
            if speaker and speaker_of(voice) != speaker.upper():
                continue
            total += 1
            for group, subs in MARKERS.items():
                hit_group = False
                for s in subs:
                    if s in text:
                        marker_line_hits[(group, s)] += 1
                        hit_group = True
                if hit_group:
                    group_line_hits[group] += 1
    return total, group_line_hits, marker_line_hits


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--speaker", default="ATR")
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args()

    total, groups, markers = analyze(args.speaker)
    if not total:
        sys.exit("No lines matched.")

    if args.json:
        out = {
            "speaker": args.speaker.upper(),
            "lines": total,
            "group_pct": {g: round(100 * groups[g] / total, 1) for g in MARKERS},
            "marker_pct": {f"{g}:{s}": round(100 * markers[(g, s)] / total, 1)
                           for g in MARKERS for s in MARKERS[g] if markers[(g, s)]},
        }
        print(json.dumps(out, ensure_ascii=False, indent=2))
        return

    print(f"=== ATRI personality signals (speaker={args.speaker.upper()}, {total} lines) ===")
    print("(% = share of ATRI lines containing the marker; aggregate only, no text)\n")
    for g in MARKERS:
        print(f"[{g}]  group coverage: {100*groups[g]/total:.1f}% of lines")
        items = sorted(((markers[(g, s)], s) for s in MARKERS[g] if markers[(g, s)]),
                       reverse=True)
        for n, s in items:
            # print marker token (these are grammatical/common-word markers, not dialogue)
            print(f"    {100*n/total:5.1f}%  [{s}]")
        print()


if __name__ == "__main__":
    main()
