#!/usr/bin/env python3
"""
Extract an ORDERED, readable transcript of one or more decoded scenes — LOCAL ONLY.

This is the qualitative-research counterpart to distill.py / persona_signals.py.
Those emit aggregate stats. This emits the actual ordered dialogue (speaker-labelled)
so a human (or an agent doing local distillation) can READ how ATRI behaves in
context — what she responds to, how she deflects, how she escalates.

OUTPUT IS LOCAL RESEARCH MATERIAL ONLY. It is written under private_corpus/
(gitignored) and MUST NOT be committed, pasted into persona/*.yaml, or surfaced
as verbatim/continuous lines. Only abstract behavioral rules derived from reading
it may enter the public repo. See tools/psb-decode/README.md and AGENTS.md.

This script itself is safe to commit; it contains no game text.

Usage (from repo root):
    python tools/psb-decode/extract_scene.py b304 b205 b405
    python tools/psb-decode/extract_scene.py --all
    # writes private_corpus/atri_transcripts/<scene>.txt
"""
from __future__ import annotations

import argparse
import io
import json
import re
import sys
from pathlib import Path

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

REPO_ROOT = Path(__file__).resolve().parents[2]
DECODED_DIR = REPO_ROOT / "private_corpus" / "atri_decoded"
OUT_DIR = REPO_ROOT / "private_corpus" / "atri_transcripts"
CJK = re.compile(r"[぀-ヿ㐀-鿿]")

# Voice prefix -> readable speaker label (proxy only; refine if needed).
SPEAKER = {
    "ATR": "ATRI",
    "MIN": "Minamo",
    "RYU": "Ryuuji",
    "CAT": "Catherine",
    "RIR": "Rira",
    "YAS": "Yasunaga",
}


def ordered_lines(doc):
    """Yield (speaker, text) in scene order for every voiced text entry."""
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
                m = re.match(r"^([A-Za-z]+)_", voice)
                pre = m.group(1).upper() if m else "?"
                yield SPEAKER.get(pre, pre), best


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("scenes", nargs="*", help="scene ids, e.g. b304 b405")
    ap.add_argument("--all", action="store_true", help="extract every scene")
    args = ap.parse_args()

    if not DECODED_DIR.exists():
        sys.exit(f"Decoded dir not found: {DECODED_DIR}\nRun decode.ps1 first.")
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    if args.all:
        targets = sorted(p.name.replace(".ks.json", "") for p in DECODED_DIR.glob("*.ks.json")
                         if not p.name.endswith(".resx.json"))
    else:
        targets = args.scenes
    if not targets:
        sys.exit("Specify scene ids or --all.")

    for scene in targets:
        src = DECODED_DIR / f"{scene}.ks.json"
        if not src.exists():
            print(f"  skip (not found): {scene}")
            continue
        doc = json.load(io.open(src, encoding="utf-8", errors="replace"))
        lines = list(ordered_lines(doc))
        out = OUT_DIR / f"{scene}.txt"
        with io.open(out, "w", encoding="utf-8") as f:
            f.write(f"# scene {scene} — {len(lines)} voiced lines (LOCAL RESEARCH ONLY)\n\n")
            for spk, text in lines:
                f.write(f"{spk}: {text}\n")
        atr = sum(1 for s, _ in lines if s == "ATRI")
        print(f"  {scene}: {len(lines)} lines ({atr} ATRI) -> {out.relative_to(REPO_ROOT)}")

    print("\nLOCAL ONLY. Do not commit private_corpus/atri_transcripts/ or paste its text.")


if __name__ == "__main__":
    main()
