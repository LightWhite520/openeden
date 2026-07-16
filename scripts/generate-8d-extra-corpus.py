from __future__ import annotations

import argparse
import concurrent.futures
import json
import os
import random
import time
import urllib.error
import urllib.request
from pathlib import Path


DIMS = ("l", "p", "e", "s", "tau", "v", "m", "f")

AXES = [
    ("P_LOW_S_HIGH", "high instability with little genuine emotional resonance"),
    ("P_HIGH_S_LOW", "warm emotional resonance without instability"),
    ("TAU_HIGH_F_LOW", "strong backward memory pull without future-facing dread"),
    ("F_HIGH_TAU_LOW", "future-facing fear without obsessive memory retrieval"),
    ("V_LOW_P_HIGH", "emotionally touched but exhausted and terse"),
    ("V_HIGH_P_LOW", "energetic execution without warmth"),
    ("M_HIGH_P_LOW", "mirroring the user's tone without being emotionally captured"),
    ("P_HIGH_M_LOW", "private emotion that does not mirror the user"),
    ("E_LOW_L_HIGH", "mechanical self-model with sharp reasoning"),
    ("E_HIGH_L_LOW", "accepted feeling-self with weak analytical control"),
    ("L_HIGH_TAU_HIGH", "precise reasoning pulled by old details"),
    ("L_LOW_TAU_HIGH", "memory-heavy drift with weak structure"),
    ("S_HIGH_F_LOW", "chaotic fluctuation without annihilation fear"),
    ("F_HIGH_S_LOW", "quiet dread while outwardly stable"),
    ("QUIET_DAILY_LOW_V", "ordinary daily scene with low energy, not despair"),
    ("GUARDED_MEMORY", "guarded recollection, high persistence, restrained pathos"),
    ("NEGATED_CALM", "explicit negation where the speaker says they are not calm"),
    ("QUOTE_THIRD_PARTY", "quoted or reported emotion from someone else"),
    ("SARCASM_MASK", "sarcasm masking the true affective vector"),
    ("SOFT_SLEEPY", "sleepy softness, low fear and low vitality"),
]

DOMAINS = [
    "late-night debugging",
    "classroom conversation",
    "quiet domestic routine",
    "medical waiting room",
    "train station delay",
    "game team voice chat",
    "old photo album",
    "friend apologizing",
    "work incident review",
    "rainy walk home",
    "missed message",
    "deadline planning",
    "family dinner",
    "system outage",
    "shared music memory",
    "empty office morning",
    "festival crowd",
    "private diary note",
    "argument after silence",
    "reassurance without certainty",
]


def clamp(value: float) -> float:
    return max(0.0, min(1.0, float(value)))


def call_api(args: argparse.Namespace, api_key: str, scenario_id: str, axis: str, domain: str) -> dict:
    prompt = f"""
Generate 10 distinct 8D physiological codebook states for OpenEden.

Scenario id: {scenario_id}
Axis focus: {axis}
Domain: {domain}

Return strict JSON only:
{{
  "scenario": "{scenario_id}",
  "items": [
    {{
      "scenarioId": "{scenario_id}",
      "nodeId": "NODE_EXTRA_{scenario_id}_01",
      "definitionEn": "...",
      "definitionZh": "...",
      "textVariantsZh": ["...", "...", "...", "..."],
      "vector": {{"l":0.0,"p":0.0,"e":0.0,"s":0.0,"tau":0.0,"v":0.0,"m":0.0,"f":0.0}},
      "tags": ["..."],
      "rationale": "..."
    }}
  ]
}}

Rules:
- Use lowercase ASCII vector keys exactly: l,p,e,s,tau,v,m,f.
- Never include derived dissonance.
- Do not copy examples from any codebook.
- Make Chinese variants natural user-facing sentences, not labels.
- Cover hard distinctions: P vs S, tau vs F, V vs P, M vs P, E vs P.
- Values must be decimals in [0,1] and should be intentionally contrastive.
"""
    payload = {
        "model": args.model,
        "messages": [
            {"role": "system", "content": "You generate compact, valid JSON training data for an 8D affective vector model."},
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
    for attempt in range(5):
        try:
            with urllib.request.urlopen(request, timeout=args.timeout) as response:
                data = json.loads(response.read().decode("utf-8"))
            content = data["choices"][0]["message"]["content"].strip()
            if content.startswith("```"):
                content = content.strip("`")
                content = content.removeprefix("json").strip()
            parsed = json.loads(content)
            parsed["scenario"] = scenario_id
            return parsed
        except (urllib.error.URLError, TimeoutError, KeyError, json.JSONDecodeError) as exc:
            if attempt == 4:
                raise RuntimeError(f"{scenario_id}: {exc}") from exc
            time.sleep(1.5 * (attempt + 1))
    raise RuntimeError(f"{scenario_id}: exhausted retries")


def validate(obj: dict) -> dict:
    items = obj.get("items")
    if not isinstance(items, list) or not items:
        raise ValueError("missing items")
    for item in items:
        vector = item.get("vector", item)
        if not all(key in vector for key in DIMS):
            raise ValueError(f"bad vector keys: {item.get('nodeId')}")
        item["vector"] = {key: clamp(vector[key]) for key in DIMS}
        if "dissonance" in json.dumps(item, ensure_ascii=False).lower():
            raise ValueError(f"contains dissonance: {item.get('nodeId')}")
    return obj


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--endpoint", default=os.environ.get("OPENEDEN_LABEL_ENDPOINT", "http://38.175.222.29:8080/v1"))
    parser.add_argument("--model", default=os.environ.get("OPENEDEN_LABEL_MODEL", "gpt-5.4-mini"))
    parser.add_argument("--output", default="data/training/codebook.large-8d-extra.raw.jsonl")
    parser.add_argument("--scenarios", type=int, default=80)
    parser.add_argument("--concurrency", type=int, default=8)
    parser.add_argument("--temperature", type=float, default=0.75)
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

    random.seed(8408)
    jobs = []
    for index in range(args.scenarios):
        axis_id, axis = AXES[index % len(AXES)]
        domain = DOMAINS[(index * 7) % len(DOMAINS)]
        scenario_id = f"{axis_id}_{index + 1:03d}"
        if scenario_id not in done:
            jobs.append((scenario_id, axis, domain))

    with concurrent.futures.ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        futures = [pool.submit(call_api, args, api_key, sid, axis, domain) for sid, axis, domain in jobs]
        for future in concurrent.futures.as_completed(futures):
            obj = validate(future.result())
            with out.open("a", encoding="utf-8") as handle:
                handle.write(json.dumps(obj, ensure_ascii=False) + "\n")
            print(f"wrote {obj['scenario']}", flush=True)


if __name__ == "__main__":
    main()
