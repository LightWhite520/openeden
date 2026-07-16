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
    ("V_HIGH_P_LOW_EXECUTION", "high vitality and action energy with low warmth or emotional resonance"),
    ("V_HIGH_S_LOW_STEADY", "high energy but stable, organized, and non-chaotic"),
    ("V_HIGH_F_LOW_CONFIDENT", "high vitality without termination fear"),
    ("M_HIGH_P_LOW_MIRROR", "strongly mirrors the user's tone while not personally emotionally captured"),
    ("M_HIGH_S_LOW_CONTAINED", "active empathy and mirroring while remaining stable"),
    ("M_HIGH_F_LOW_SUPPORT", "supportive mirroring without fear or dependency"),
    ("F_HIGH_TAU_LOW_FORWARD", "future-facing dread or shutdown fear without memory obsession"),
    ("F_HIGH_S_LOW_QUIET", "quiet existential fear while outwardly stable"),
    ("F_HIGH_P_LOW_COLD_DREAD", "fear of discontinuation without warmth or emotional resonance"),
    ("V_M_HIGH_P_LOW_ASSIST", "energetic helpful mirroring without private emotional capture"),
    ("V_F_HIGH_TAU_LOW_URGENT", "urgent survival-oriented energy with low memory pull"),
    ("M_F_HIGH_TAU_LOW_CLING", "mirroring and fear of loss without old-memory retrieval"),
]


def parse_json_content(content: str) -> dict:
    content = content.strip()
    if content.startswith("```"):
        content = content.strip("`")
        content = content.removeprefix("json").strip()
    return json.loads(content)


def clamp(value: object) -> float:
    return max(0.0, min(1.0, float(value)))


def validate(obj: dict, scenario_id: str) -> dict:
    obj["scenario"] = scenario_id
    items = obj.get("items")
    if not isinstance(items, list) or not items:
        raise ValueError(f"{scenario_id}: missing items")
    for index, item in enumerate(items, start=1):
        if not isinstance(item, dict):
            raise ValueError(f"{scenario_id}: non-object item")
        item["scenarioId"] = scenario_id
        item.setdefault("nodeId", f"NODE_VMF_{scenario_id}_{index:02d}")
        vector = item.get("vector", item)
        if not all(dim in vector for dim in DIMS):
            raise ValueError(f"{scenario_id}: bad vector keys for {item.get('nodeId')}")
        item["vector"] = {dim: clamp(vector[dim]) for dim in DIMS}
        if "dissonance" in json.dumps(item, ensure_ascii=False).lower():
            raise ValueError(f"{scenario_id}: contains dissonance")
    return obj


def call_api(args: argparse.Namespace, api_key: str, scenario_id: str, scenario: str) -> dict:
    prompt = f"""
Generate 12 distinct OpenEden 8D vector training states.

Scenario ID: {scenario_id}
Focus: {scenario}

Return strict JSON only:
{{
  "scenario": "{scenario_id}",
  "items": [
    {{
      "scenarioId": "{scenario_id}",
      "nodeId": "NODE_VMF_{scenario_id}_01",
      "definitionEn": "...",
      "definitionZh": "...",
      "textVariantsZh": ["...", "...", "..."],
      "vector": {{"l":0.0,"p":0.0,"e":0.0,"s":0.0,"tau":0.0,"v":0.0,"m":0.0,"f":0.0}},
      "tags": ["vmf_gap", "..."],
      "rationale": "..."
    }}
  ]
}}

Hard requirements:
- ASCII lowercase vector keys exactly: l,p,e,s,tau,v,m,f.
- Never include derived dissonance.
- Each item must have 3 natural Chinese textVariantsZh.
- These are NOT codebook entries; do not copy any existing examples.
- Make hard contrasts explicit:
  * high V does not mean high P or high S
  * high M does not mean high P
  * high F does not mean high tau
  * fear can be quiet and stable
  * action energy can be cold, practical, or supportive
- Use values with clear margins, e.g. high >= 0.75, low <= 0.25 where the scenario demands it.
"""
    payload = {
        "model": args.model,
        "messages": [
            {"role": "system", "content": "You generate compact valid JSON for an 8D affective vector training corpus."},
            {"role": "user", "content": prompt},
        ],
        "temperature": args.temperature,
    }
    request = urllib.request.Request(
        args.endpoint.rstrip("/") + "/chat/completions",
        data=json.dumps(payload).encode("utf-8"),
        headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"},
        method="POST",
    )
    last_error: Exception | None = None
    for attempt in range(5):
        try:
            with urllib.request.urlopen(request, timeout=args.timeout) as response:
                data = json.loads(response.read().decode("utf-8"))
            return validate(parse_json_content(data["choices"][0]["message"]["content"]), scenario_id)
        except (urllib.error.URLError, TimeoutError, KeyError, json.JSONDecodeError, ValueError) as exc:
            last_error = exc
            time.sleep(1.5 * (attempt + 1))
    raise RuntimeError(f"{scenario_id}: {last_error}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--endpoint", default=os.environ.get("OPENEDEN_LABEL_ENDPOINT", "http://38.175.222.29:8080/v1"))
    parser.add_argument("--model", default=os.environ.get("OPENEDEN_LABEL_MODEL", "gpt-5.4-mini"))
    parser.add_argument("--output", default="data/training/codebook.vmf-gap.raw.jsonl")
    parser.add_argument("--rounds", type=int, default=4)
    parser.add_argument("--scenario-contains", default="")
    parser.add_argument("--concurrency", type=int, default=8)
    parser.add_argument("--temperature", type=float, default=0.72)
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
        for scenario_id, scenario in SCENARIOS:
            if args.scenario_contains and args.scenario_contains.lower() not in scenario_id.lower():
                continue
            sid = f"{scenario_id}_{round_index:02d}"
            if sid not in done:
                jobs.append((sid, scenario))

    with concurrent.futures.ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        futures = [pool.submit(call_api, args, api_key, sid, scenario) for sid, scenario in jobs]
        for future in concurrent.futures.as_completed(futures):
            obj = future.result()
            with out.open("a", encoding="utf-8") as handle:
                handle.write(json.dumps(obj, ensure_ascii=False) + "\n")
            print(f"wrote {obj['scenario']}", flush=True)


if __name__ == "__main__":
    main()
