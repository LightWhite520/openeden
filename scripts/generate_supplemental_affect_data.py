#!/usr/bin/env python3
"""Generate supplemental Thymos-6D affect training records with an OpenAI-compatible API."""

from __future__ import annotations

import argparse
import concurrent.futures
import json
import os
import random
import re
import urllib.error
import urllib.request
from collections import defaultdict
from pathlib import Path


LABELS = ("valence", "arousal", "dominance", "connectionNeed", "openness", "confidence")

CATEGORIES = [
    {
        "name": "clear_threat",
        "count": 150,
        "guidance": (
            "Direct threats, coercion, intimidation, or violent warning. "
            "Affect should be clearly inferable from text alone: low valence, medium/high arousal, "
            "higher dominance than ordinary sadness, low openness, high confidence."
        ),
    },
    {
        "name": "mocking_insult",
        "count": 150,
        "guidance": (
            "Mockery, contempt, belittling, hostile teasing. Low valence and openness, "
            "low connection need, confidence high when the insult is unambiguous."
        ),
    },
    {
        "name": "affectionate_tease",
        "count": 138,
        "guidance": (
            "Affectionate teasing with words like 笨蛋, 傻瓜, 坏蛋, but context makes it loving. "
            "Valence should be neutral-to-positive, connectionNeed/openness moderate/high. "
            "Confidence may be high only when the sentence itself contains clear affection, care, or playfulness."
        ),
    },
    {
        "name": "context_dependent_tease",
        "count": 163,
        "guidance": (
            "Teasing or nickname-like utterances whose affect depends strongly on relationship context, "
            "such as isolated 小笨蛋, 傻瓜, 坏蛋, 真拿你没办法, or mild scolding without enough context. "
            "Labels should be near neutral-to-slightly-negative or slightly-positive, connectionNeed moderate, "
            "and confidence low-to-medium because the text alone is insufficient."
        ),
    },
    {
        "name": "ambiguous_short",
        "count": 100,
        "guidance": (
            "Short ambiguous utterances like 嗯, 呵呵, 随便, 行吧. Labels should be near neutral "
            "with low confidence because affect cannot be inferred reliably from text alone."
        ),
    },
    {
        "name": "sarcastic_failure",
        "count": 113,
        "guidance": (
            "Sarcasm where positive words mask negative affect, often about failure or frustration. "
            "Valence low, arousal medium, openness low, confidence medium/high when sarcasm is explicit."
        ),
    },
    {
        "name": "attachment_plea",
        "count": 113,
        "guidance": (
            "Pleading, abandonment fear, dependency, asking someone not to leave. Low valence, "
            "medium arousal, low dominance, high connectionNeed, moderate confidence."
        ),
    },
    {
        "name": "panic_fear",
        "count": 113,
        "guidance": (
            "Fear, panic, urgent anxiety, physical panic symptoms. Low valence, high arousal, "
            "low dominance, confidence high when explicit."
        ),
    },
    {
        "name": "clear_positive",
        "count": 113,
        "guidance": (
            "Clear happiness, gratitude, excitement, relief, affection. High valence, medium/high arousal, "
            "openness high, confidence high when explicit."
        ),
    },
    {
        "name": "neutral_logistics",
        "count": 88,
        "guidance": (
            "Emotionally neutral scheduling, factual updates, reminders. Valence near 0.45-0.55, "
            "arousal low, confidence medium/high because low affect is clear."
        ),
    },
    {
        "name": "rejection_boundary",
        "count": 43,
        "guidance": (
            "Refusal, boundary setting, rejection, asking not to be bothered. Low valence/openness, "
            "dominance medium, connectionNeed low except when the speaker still wants reassurance."
        ),
    },
]


SYSTEM_PROMPT = """You generate compact JSONL training data for a six-dimensional Chinese text affect model.

Return only JSON, no markdown. Generate diverse Simplified Chinese user messages.

Each record must have:
- text: a natural message, 3 to 40 Chinese characters when possible.
- valence: 0.0 negative/unpleasant to 1.0 positive/pleasant.
- arousal: 0.0 calm/low activation to 1.0 intense/urgent.
- dominance: 0.0 vulnerable/passive to 1.0 controlling/assertive.
- connectionNeed: 0.0 no bid for connection to 1.0 strong need for response/closeness.
- openness: 0.0 closed/guarded/hostile to 1.0 open/receptive/sharing.
- confidence: affect labels are inferable from text alone. This is NOT positivity. Clear threats,
  clear insults, clear panic, and clear neutral logistics can all have high confidence. Ambiguous
  short utterances and context-dependent affectionate teasing should have lower confidence.

Use numbers with two decimals. Keep all values in [0, 1]. Do not include clinical diagnosis.
"""


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=Path("data/training/user-affect-supplemental-extreme-v1.jsonl"))
    parser.add_argument("--base-url", default=os.environ.get("THYMOS_OPENAI_BASE_URL", "http://38.175.222.29:8080/v1"))
    parser.add_argument("--api-key", default=os.environ.get("THYMOS_OPENAI_API_KEY"))
    parser.add_argument("--model", default=os.environ.get("THYMOS_OPENAI_MODEL", "GPT 5.4 mini"))
    parser.add_argument("--workers", type=int, default=6)
    parser.add_argument("--target-records", type=int, default=1024)
    return parser.parse_args()


def make_tasks(batch_size: int = 32) -> list[dict]:
    tasks: list[dict] = []
    for category in CATEGORIES:
        remaining = int(category["count"])
        while remaining > 0:
            task = dict(category)
            task["count"] = min(batch_size, remaining)
            tasks.append(task)
            remaining -= int(task["count"])
    return tasks


def parse_json_object(content: str) -> dict:
    cleaned = re.sub(r"^```(?:json)?\s*|\s*```$", "", content.strip(), flags=re.IGNORECASE)
    start = cleaned.find("{")
    if start < 0:
        raise ValueError("No JSON object found in model response")
    parsed, _ = json.JSONDecoder().raw_decode(cleaned[start:])
    if not isinstance(parsed, dict):
        raise ValueError("Model response JSON root must be an object")
    return parsed


def call_api(base_url: str, api_key: str, model: str, category: dict) -> list[dict]:
    prompt = (
        f"Category: {category['name']}\n"
        f"Count: {category['count']}\n"
        f"Guidance: {category['guidance']}\n\n"
        "Return JSON in this shape exactly:\n"
        '{"records":[{"text":"...","valence":0.0,"arousal":0.0,"dominance":0.0,'
        '"connectionNeed":0.0,"openness":0.0,"confidence":0.0}]}'
    )
    body = {
        "model": model,
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": prompt},
        ],
        "temperature": 0.85,
    }
    request = urllib.request.Request(
        f"{base_url.rstrip('/')}/chat/completions",
        data=json.dumps(body).encode("utf-8"),
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"{category['name']} API request failed: {exc.code} {detail}") from exc

    content = payload["choices"][0]["message"]["content"]
    parsed = parse_json_object(content)
    records = parsed["records"]
    if len(records) != category["count"]:
        raise ValueError(f"{category['name']} expected {category['count']} records, got {len(records)}")
    return records


def clean_records(records_by_category: list[tuple[str, list[dict]]]) -> list[dict]:
    output: list[dict] = []
    seen_texts: set[str] = set()
    category_indices: defaultdict[str, int] = defaultdict(int)
    for category, records in records_by_category:
        for record in records:
            text = str(record["text"]).strip()
            if not text or text in seen_texts:
                continue
            seen_texts.add(text)
            values = {}
            for label in LABELS:
                value = round(float(record[label]), 2)
                if not 0.0 <= value <= 1.0:
                    raise ValueError(f"{category} produced invalid {label}: {value}")
                values[label] = value
            category_indices[category] += 1
            output.append(
                {
                    "sampleId": f"supplemental-extreme-v1-{category}-{category_indices[category]:03d}",
                    "text": text,
                    **values,
                    "tags": [category, "supplemental", "confidence-calibration"],
                }
            )
    return output


def main() -> None:
    args = parse_args()
    if not args.api_key:
        raise SystemExit("Set THYMOS_OPENAI_API_KEY or pass --api-key")

    tasks = make_tasks()
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as executor:
        futures = {
            executor.submit(call_api, args.base_url, args.api_key, args.model, category): category["name"]
            for category in tasks
        }
        grouped = []
        for future in concurrent.futures.as_completed(futures):
            category = futures[future]
            grouped.append((category, future.result()))

    records = clean_records(sorted(grouped, key=lambda item: item[0]))
    if args.target_records and len(records) > args.target_records:
        random.Random(0xAFFEC1024).shuffle(records)
        records = sorted(records[: args.target_records], key=lambda item: item["sampleId"])
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        "".join(json.dumps(record, ensure_ascii=False, separators=(",", ":")) + "\n" for record in records),
        encoding="utf-8",
    )
    print(json.dumps({"output": str(args.output), "records": len(records)}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
