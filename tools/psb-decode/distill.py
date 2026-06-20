#!/usr/bin/env python3
"""
Distill ATRI-specific style statistics from locally-decoded scenario JSON.

Reads private_corpus/atri_decoded/*.ks.json (produced by decode.ps1) and emits
ONLY aggregate statistics about how ATRI speaks. It NEVER prints or writes a
verbatim line of dialogue. The output is the kind of frequency-derived,
non-verbatim observation that AGENTS.md permits in the public repo.

Speaker attribution: KirikiriZ stores a voice filename with each spoken text
entry. ATRI's voice files are prefixed `ATR_`. We attribute a text line to a
speaker by the voice prefix on the same entry. Lines with no voice are skipped
(they are narration or system text, not character speech).

Usage (from repo root, normal python 3):
    python tools/psb-decode/distill.py
    python tools/psb-decode/distill.py --speaker ATR --json   # machine-readable

SAFE TO COMMIT: this script and its statistical output.
NOT SAFE TO COMMIT: the decoded JSON it reads, or any raw line text.
"""
from __future__ import annotations

import argparse
import io
import json
import re
import statistics
import sys
from collections import Counter
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
DECODED_DIR = REPO_ROOT / "private_corpus" / "atri_decoded"

CJK = re.compile(r"[぀-ヿ㐀-鿿]")          # kana + CJK ideographs
KANA = re.compile(r"[぀-ヿ]")
SENTENCE_SPLIT = re.compile(r"[。！？\n]")
# Japanese sentence-final punctuation / markers we care about for rhythm.
ENDERS = {
    "ellipsis": re.compile(r"(……|…|・・・)"),
    "question": re.compile(r"[？?]"),
    "exclaim": re.compile(r"[！!]"),
    "longvowel": re.compile(r"ー"),          # drawn-out sounds (hesitation/affect)
    "dash": re.compile(r"[―—\-]{2,}"),
}


def iter_decoded_files():
    if not DECODED_DIR.exists():
        sys.exit(f"Decoded dir not found: {DECODED_DIR}\nRun tools/psb-decode/decode.ps1 first.")
    # Skip *.resx.json (resource sidecars) — scenario text is in *.ks.json.
    return sorted(p for p in DECODED_DIR.glob("*.ks.json") if not p.name.endswith(".resx.json"))


def extract_voiced_lines(doc):
    """Yield (voice_token, text) for every entry that has both a voice and text.

    Robust to structure: we walk each `texts` entry, collect the first `voice`
    string found within it, and the longest CJK-bearing string found within it
    (the display line). This avoids hardcoding fragile index paths.
    """
    for scene in doc.get("scenes", []):
        for entry in scene.get("texts", []):
            voice = None
            best_text = ""
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
                    if CJK.search(o) and len(o) > len(best_text):
                        best_text = o
            if voice and best_text:
                yield voice, best_text


def speaker_of(voice: str) -> str:
    m = re.match(r"^([A-Za-z]+)_", voice)
    return m.group(1).upper() if m else "?"


def analyze(speaker: str):
    lengths = []
    rate = Counter()
    ender_lines = Counter()
    total = 0
    speaker_tally = Counter()
    line_final_char = Counter()

    for path in iter_decoded_files():
        doc = json.load(io.open(path, encoding="utf-8", errors="replace"))
        for voice, text in extract_voiced_lines(doc):
            spk = speaker_of(voice)
            speaker_tally[spk] += 1
            if speaker and spk != speaker.upper():
                continue
            total += 1
            # length in characters (JP has no word boundaries; char count is the honest metric)
            lengths.append(len(text))
            for name, pat in ENDERS.items():
                if pat.search(text):
                    ender_lines[name] += 1
            # sentence count within the line
            sents = [s for s in SENTENCE_SPLIT.split(text) if s.strip()]
            rate["sentences"] += len(sents)
            # final visible char class (rhythm signature), bucketed — never the char itself if it's text
            stripped = text.rstrip()
            if stripped:
                fc = stripped[-1]
                bucket = (
                    "question" if fc in "？?" else
                    "exclaim" if fc in "！!" else
                    "ellipsis" if fc in "…・" else
                    "period" if fc in "。." else
                    "other"
                )
                line_final_char[bucket] += 1

    return {
        "speaker": speaker.upper() if speaker else "ALL",
        "lines": total,
        "speaker_distribution": dict(speaker_tally.most_common()),
        "char_length": {
            "median": statistics.median(lengths) if lengths else 0,
            "mean": round(statistics.mean(lengths), 1) if lengths else 0,
            "p25": _pctl(lengths, 25),
            "p75": _pctl(lengths, 75),
            "max": max(lengths) if lengths else 0,
        },
        "marker_rate_pct": {
            name: round(100 * ender_lines[name] / total, 1) if total else 0
            for name in ENDERS
        },
        "line_ending_pct": {
            k: round(100 * v / total, 1) if total else 0
            for k, v in line_final_char.most_common()
        },
        "sentences_per_line": round(rate["sentences"] / total, 2) if total else 0,
    }


def _pctl(xs, p):
    if not xs:
        return 0
    s = sorted(xs)
    k = (len(s) - 1) * p / 100
    f = int(k)
    return s[f] if f + 1 >= len(s) else round(s[f] + (s[f + 1] - s[f]) * (k - f), 1)


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--speaker", default="ATR",
                    help="Voice prefix to filter (ATR=ATRI). Use '' for all speakers.")
    ap.add_argument("--json", action="store_true", help="Emit machine-readable JSON.")
    args = ap.parse_args()

    result = analyze(args.speaker)

    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return

    r = result
    print(f"=== ATRI style distillation (speaker={r['speaker']}) ===")
    print(f"attributed voiced lines: {r['lines']}")
    print(f"speaker distribution   : {r['speaker_distribution']}")
    cl = r["char_length"]
    print(f"line length (chars)    : median={cl['median']} mean={cl['mean']} "
          f"p25={cl['p25']} p75={cl['p75']} max={cl['max']}")
    print(f"sentences per line     : {r['sentences_per_line']}")
    print(f"marker rate (% lines)  : {r['marker_rate_pct']}")
    print(f"line-ending (% lines)  : {r['line_ending_pct']}")
    print()
    print("These are aggregate statistics only. No verbatim dialogue is emitted.")


if __name__ == "__main__":
    main()
