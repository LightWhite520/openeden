from __future__ import annotations

import argparse
import concurrent.futures
import json
import os
import time
import urllib.error
import urllib.request
from pathlib import Path


DIMS = ("l", "p", "e", "s", "tau", "v", "m", "f")
SCENARIOS = [
    ("M_HIGH_P_LOW_SUPPORTIVE", "strong user-tone mirroring and support, but low private emotional capture"),
    ("M_HIGH_S_LOW_CONTAINED", "active empathy and mirroring while remaining stable and contained"),
    ("M_HIGH_TAU_LOW_PRESENT", "present-moment mirroring without old-memory pull"),
    ("M_HIGH_F_LOW_SAFE", "warmly mirrors the user without fear of loss or termination"),
    ("S_LOW_P_HIGH_WARM_STABLE", "high emotional resonance with low instability"),
    ("S_LOW_F_HIGH_QUIET_DREAD", "quiet fear while behavior stays stable and organized"),
    ("S_HIGH_P_LOW_NOISY_NOT_EMOTIONAL", "high instability/noise without genuine emotional resonance"),
    ("S_HIGH_M_LOW_PRIVATE_GLITCH", "chaotic internal glitching without mirroring the user"),
]


def parse_json(content: str) -> dict:
    content = content.strip()
    if content.startswith("```"):
        content = content.strip("`").removeprefix("json").strip()
    return json.loads(content)


def validate(obj: dict, scenario_id: str) -> dict:
    items = obj.get("items")
    if not isinstance(items, list) or not items:
        raise ValueError(f"{scenario_id}: missing items")
    obj["scenario"] = scenario_id
    for index, item in enumerate(items, start=1):
        item["scenarioId"] = scenario_id
        item.setdefault("nodeId", f"NODE_MS_{scenario_id}_{index:02d}")
        vector = item.get("vector", item)
        if not all(dim in vector for dim in DIMS):
            raise ValueError(f"{scenario_id}: bad vector keys")
        item["vector"] = {dim: max(0.0, min(1.0, float(vector[dim]))) for dim in DIMS}
        if "dissonance" in json.dumps(item, ensure_ascii=False).lower():
            raise ValueError(f"{scenario_id}: contains dissonance")
    return obj


def call_api(args: argparse.Namespace, api_key: str, scenario_id: str, focus: str) -> dict:
    prompt = f"""
Generate 10 distinct OpenEden 8D vector hardcase states.

Scenario ID: {scenario_id}
Focus: {focus}

Return strict JSON only with this shape:
{{
  "scenario": "{scenario_id}",
  "items": [
    {{
      "scenarioId": "{scenario_id}",
      "nodeId": "NODE_MS_{scenario_id}_01",
      "definitionEn": "...",
      "definitionZh": "...",
      "textVariantsZh": ["...", "...", "..."],
      "vector": {{"l":0.0,"p":0.0,"e":0.0,"s":0.0,"tau":0.0,"v":0.0,"m":0.0,"f":0.0}},
      "tags": ["ms_gap", "..."],
      "rationale": "..."
    }}
  ]
}}

Requirements:
- vector keys exactly: l,p,e,s,tau,v,m,f.
- no derived dissonance.
- Chinese variants must be natural first-person or dialogue-like sentences.
- Make M and S boundaries explicit:
  * M high means mirroring/user alignment, not necessarily P high.
  * S low can coexist with P or F high if the voice is contained.
  * S high can be noise/glitch/fragmentation without warmth.
  * Do not over-raise F or tau unless the focus requires it.
"""
    payload = {
        "model": args.model,
        "messages": [
            {"role": "system", "content": "You generate valid JSON training data for an 8D affective vector model."},
            {"role": "user", "content": prompt},
        ],
        "temperature": args.temperature,
    }
    req = urllib.request.Request(
        args.endpoint.rstrip("/") + "/chat/completions",
        data=json.dumps(payload).encode("utf-8"),
        headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"},
        method="POST",
    )
    last = None
    for attempt in range(5):
        try:
            with urllib.request.urlopen(req, timeout=args.timeout) as response:
                data = json.loads(response.read().decode("utf-8"))
            return validate(parse_json(data["choices"][0]["message"]["content"]), scenario_id)
        except (urllib.error.URLError, TimeoutError, KeyError, json.JSONDecodeError, ValueError) as exc:
            last = exc
            time.sleep(1.5 * (attempt + 1))
    raise RuntimeError(f"{scenario_id}: {last}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--endpoint", default=os.environ.get("OPENEDEN_LABEL_ENDPOINT", "http://38.175.222.29:8080/v1"))
    parser.add_argument("--model", default=os.environ.get("OPENEDEN_LABEL_MODEL", "gpt-5.4-mini"))
    parser.add_argument("--output", default="data/training/codebook.ms-gap.raw.jsonl")
    parser.add_argument("--rounds", type=int, default=4)
    parser.add_argument("--concurrency", type=int, default=8)
    parser.add_argument("--temperature", type=float, default=0.7)
    parser.add_argument("--timeout", type=int, default=120)
    args = parser.parse_args()
    api_key = os.environ.get("OPENEDEN_LABEL_API_KEY")
    if not api_key:
        raise SystemExit("OPENEDEN_LABEL_API_KEY is required")
    out = Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)
    done = set()
    if out.exists():
        for line in out.read_text(encoding="utf-8").splitlines():
            try:
                done.add(json.loads(line).get("scenario"))
            except json.JSONDecodeError:
                pass
    jobs = []
    for round_index in range(1, args.rounds + 1):
        for sid, focus in SCENARIOS:
            scenario_id = f"{sid}_{round_index:02d}"
            if scenario_id not in done:
                jobs.append((scenario_id, focus))
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        futures = [pool.submit(call_api, args, api_key, sid, focus) for sid, focus in jobs]
        for future in concurrent.futures.as_completed(futures):
            obj = future.result()
            with out.open("a", encoding="utf-8") as handle:
                handle.write(json.dumps(obj, ensure_ascii=False) + "\n")
            print(f"wrote {obj['scenario']}", flush=True)


if __name__ == "__main__":
    main()
