from __future__ import annotations

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPTS = Path(__file__).resolve().parents[1]


def load_script(module_name: str, filename: str):
    spec = importlib.util.spec_from_file_location(module_name, SCRIPTS / filename)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Cannot load {filename}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[module_name] = module
    spec.loader.exec_module(module)
    return module


pad = load_script("pad_8d_codebook", "pad-8d-codebook-corpus-to-power2.py")
normalize = load_script("normalize_8d_codebook", "normalize-8d-codebook-corpus.py")
prepare_large = load_script("prepare_8d_large_corpus", "prepare-8d-large-corpus.py")
select_hardcase = load_script("select_8d_hardcase_corpus", "select-8d-hardcase-corpus.py")
merge_vmf = load_script("merge_8d_vmf_gap_corpus", "merge-8d-vmf-gap-corpus.py")
merge_ms = load_script("merge_8d_ms_gap_corpus", "merge-8d-ms-gap-corpus.py")
train_base = load_script("train_codebook_base_model", "train-codebook-base-model.py")
replace_boundary = load_script("replace_8d_boundary_nodes", "replace-8d-boundary-nodes.py")
rewrite_runtime = load_script("rewrite_8d_runtime_definitions", "rewrite-8d-runtime-definitions.py")


class PaddingNodeNormalizationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.vector = {
            "l": 0.84,
            "p": 0.18,
            "e": 0.78,
            "s": 0.21,
            "tau": 0.41,
            "v": 0.76,
            "m": 0.81,
            "f": 0.17,
        }

    def test_padding_node_uses_semantic_id_and_definitions(self) -> None:
        sample = pad.padding_sample(self.vector, 7)

        self.assertEqual(
            sample["nodeId"],
            "NODE_VMF_BOUNDARY_P_LOW_TAU_MID_V_HIGH_F_LOW_007",
        )
        self.assertNotIn("power-of-two", sample["definitionEn"].lower())
        self.assertNotIn("supplemental", sample["definitionEn"].lower())
        self.assertNotIn("整数次幂", sample["definitionZh"])
        self.assertNotRegex(sample["definitionZh"], r"\b(?:low|mid|high)\b")
        self.assertNotRegex(sample["definitionEn"], r"[\r\n]")
        self.assertNotRegex(sample["definitionZh"], r"[\r\n]")
        self.assertEqual(
            sample["tags"],
            ["vmf_boundary", "vmf_gap", "hardcase_8d", "definition"],
        )

    def test_legacy_padding_node_is_migrated_without_vector_changes(self) -> None:
        data = {
            "samples": [
                {
                    "nodeId": "NODE_POWER2_8D_PAD_007",
                    "definition": "legacy",
                    "definitionEn": "legacy",
                    "definitionZh": "旧定义",
                    "tags": ["power2_padding"],
                    "vector": dict(self.vector),
                }
            ]
        }

        migrated = pad.canonicalize_padding_nodes(data)

        self.assertEqual(migrated, 1)
        self.assertEqual(data["samples"][0]["vector"], self.vector)
        self.assertEqual(
            data["samples"][0]["nodeId"],
            "NODE_VMF_BOUNDARY_P_LOW_TAU_MID_V_HIGH_F_LOW_007",
        )

    def test_generated_boundary_nodes_have_unique_semantics(self) -> None:
        definitions = {
            pad.definition(pad.generated_vector(index))
            for index in range(101)
        }

        self.assertEqual(len(definitions), 101)

    def test_report_counts_canonical_boundary_samples_as_extras(self) -> None:
        data = {"samples": [pad.padding_sample(self.vector, 7)]}

        report = pad.report_for(data, "codebook.selected-hardcases-8d-vmf-balanced.json")

        self.assertEqual(report["extraSamples"], 4033)

    def test_canonical_boundary_node_is_refreshed_idempotently(self) -> None:
        sample = pad.padding_sample(self.vector, 7)
        sample["definitionEn"] = "stale"
        data = {"samples": [sample]}

        migrated = pad.canonicalize_padding_nodes(data)

        self.assertEqual(migrated, 1)
        self.assertNotEqual(data["samples"][0]["definitionEn"], "stale")
        self.assertEqual(data["samples"][0]["nodeId"], sample["nodeId"])


class CorpusTextNormalizationTest(unittest.TestCase):
    def test_definition_whitespace_is_collapsed_to_one_line(self) -> None:
        self.assertEqual(
            normalize.normalize_text('  “你确定吗？”\r\n\t“确定，再检查。”  '),
            '“你确定吗？” “确定，再检查。”',
        )

    def test_runtime_definition_is_not_first_person_example(self) -> None:
        self.assertEqual(
            normalize.normalize_runtime_definition("我会先顺着你的感受回应你，不催、不逼。"),
            "说话者会先顺着用户的感受回应用户，不催、不逼。",
        )

    def test_runtime_definition_audit_rejects_dialogue_and_scenery(self) -> None:
        dirty = {
            "nodeId": "NODE_DIRTY",
            "definitionEn": "He waits in the classroom and says he is fine.",
            "definitionZh": "他站在教室门口说：“我没事。”",
        }
        clean = {
            "nodeId": "NODE_CLEAN",
            "definitionEn": "A high-fear state with stable surface control and low memory pull.",
            "definitionZh": "该状态表现为表层稳定但恐惧持续偏高，记忆牵引较弱，核心张力来自对即将中断的前向担忧。",
        }

        self.assertFalse(rewrite_runtime.is_clean_definition(dirty))
        self.assertTrue(rewrite_runtime.is_clean_definition(clean))

    def test_template_rewrite_produces_formal_state_definition(self) -> None:
        sample = {
            "nodeId": "NODE_TEMPLATE",
            "definitionEn": "He says he is fine while panic leaks through.",
            "definitionZh": "“我没事。”他一边说一边后退。",
            "tags": ["fear", "panic"],
            "vector": {
                "l": 0.3,
                "p": 0.8,
                "e": 0.4,
                "s": 0.7,
                "tau": 0.2,
                "v": 0.3,
                "m": 0.5,
                "f": 0.9,
            },
        }

        rewritten = rewrite_runtime.template_rewrite(sample)

        self.assertTrue(rewrite_runtime.is_clean_definition(rewritten))
        self.assertTrue(rewritten["definitionZh"].startswith("该状态"))
        self.assertNotIn("他", rewritten["definitionZh"])


class LargeCorpusPreparationTest(unittest.TestCase):
    def test_variants_are_training_text_not_runtime_definition(self) -> None:
        raw = {
            "nodeId": "NODE_BIG_NEGATION_CALM_BUT_PANIC_03",
            "scenarioId": "hardcase",
            "definitionEn": (
                "The speaker repeats that everything is fine while their actions become "
                "increasingly erratic. The text should feel like a mask slipping in real time."
            ),
            "definitionZh": (
                "说话者反复说“一切都好”，但动作越来越失常，像面具正在当场松脱。"
                "文本要呈现出假装平稳的表层和逐步失控的内层。"
            ),
            "textVariantsZh": [
                "“放心，我还稳得住。”她一边说一边后退半步，鞋跟在地上轻轻一滑。",
            ],
            "tags": ["hardcase"],
            "vector": {
                "l": 0.28,
                "p": 0.76,
                "e": 0.42,
                "s": 0.83,
                "tau": 0.62,
                "v": 0.31,
                "m": 0.57,
                "f": 0.79,
            },
        }
        with tempfile.TemporaryDirectory() as temp_dir:
            raw_path = Path(temp_dir) / "raw.jsonl"
            raw_path.write_text(f"{prepare_large.json.dumps(raw, ensure_ascii=False)}\n", encoding="utf-8")

            samples = prepare_large.build_samples([raw_path])

        self.assertEqual(len(samples), 2)
        for sample in samples:
            self.assertEqual(sample["definitionEn"], raw["definitionEn"])
            self.assertEqual(sample["definitionZh"], raw["definitionZh"])
            self.assertNotEqual(sample["definitionZh"], raw["textVariantsZh"][0])
        self.assertEqual(samples[0]["trainingTextZh"], raw["definitionZh"])
        self.assertEqual(samples[1]["trainingTextZh"], raw["textVariantsZh"][0])

    def test_hardcase_selector_scores_training_text_when_available(self) -> None:
        sample = {
            "definitionEn": "Canonical English definition.",
            "definitionZh": "规范中文定义。",
            "trainingTextZh": "这是一条用于训练的中文样例。",
        }

        self.assertEqual(
            select_hardcase.text_of(sample),
            "EN: Canonical English definition.\nZH: 这是一条用于训练的中文样例。",
        )

    def test_gap_merge_preserves_canonical_definition_and_separates_training_text(self) -> None:
        raw = {
            "scenario": "VMF_GAP",
            "items": [
                {
                    "nodeId": "NODE_VMF_GAP_CASE",
                    "definitionEn": "Canonical gap description.",
                    "definitionZh": "规范缺口描述。",
                    "textVariantsZh": ["这是训练样例。"],
                    "tags": ["vmf_gap"],
                    "vector": {
                        "l": 0.2,
                        "p": 0.8,
                        "e": 0.4,
                        "s": 0.7,
                        "tau": 0.5,
                        "v": 0.3,
                        "m": 0.6,
                        "f": 0.9,
                    },
                }
            ],
        }
        with tempfile.TemporaryDirectory() as temp_dir:
            raw_path = Path(temp_dir) / "raw.jsonl"
            raw_path.write_text(f"{prepare_large.json.dumps(raw, ensure_ascii=False)}\n", encoding="utf-8")

            vmf_samples = merge_vmf.expand_raw(raw_path)
            ms_samples = merge_ms.expand_raw(raw_path)

        for samples in (vmf_samples, ms_samples):
            self.assertEqual(samples[0]["definitionZh"], "规范缺口描述。")
            self.assertEqual(samples[1]["definitionZh"], "规范缺口描述。")
            self.assertEqual(samples[1]["trainingTextZh"], "这是训练样例。")

    def test_base_training_loader_prefers_training_text_for_model_text(self) -> None:
        corpus = {
            "samples": [
                {
                    "nodeId": "NODE_TRAINING_TEXT_CASE",
                    "definition": "Canonical English definition.",
                    "definitionEn": "Canonical English definition.",
                    "definitionZh": "规范中文定义。",
                    "trainingTextZh": "这是训练样例。",
                    "vector": {
                        "l": 0.2,
                        "p": 0.8,
                        "e": 0.4,
                        "s": 0.7,
                        "tau": 0.5,
                        "v": 0.3,
                        "m": 0.6,
                        "f": 0.9,
                    },
                }
            ]
        }
        with tempfile.TemporaryDirectory() as temp_dir:
            corpus_path = Path(temp_dir) / "corpus.json"
            corpus_path.write_text(prepare_large.json.dumps(corpus, ensure_ascii=False), encoding="utf-8")

            loaded = train_base.load_samples(corpus_path)

        self.assertEqual(loaded[0].text, "EN: Canonical English definition.\nZH: 这是训练样例。")

    def test_boundary_nodes_are_replaced_by_real_context_nodes(self) -> None:
        target = {
            "samples": [
                {
                    "nodeId": "NODE_REAL_EXISTING",
                    "definition": "Existing real state.",
                    "definitionEn": "Existing real state.",
                    "definitionZh": "已有真实状态。",
                    "tags": ["real"],
                    "vector": self.vector_for_test(0.2),
                },
                {
                    "nodeId": "NODE_VMF_BOUNDARY_P_LOW_TAU_LOW_V_LOW_F_LOW_001",
                    "definition": "Boundary filler.",
                    "definitionEn": "Boundary filler.",
                    "definitionZh": "坐标边界补位。",
                    "tags": ["vmf_boundary"],
                    "vector": self.vector_for_test(0.3),
                },
            ],
        }
        source = {
            "samples": [
                {
                    "nodeId": "NODE_MS_REAL_CONTEXT_01",
                    "definition": "Real context state.",
                    "definitionEn": "Real context state.",
                    "definitionZh": "真实语境状态。",
                    "tags": ["ms_gap"],
                    "vector": self.vector_for_test(0.7),
                },
            ],
        }

        report = replace_boundary.replace_boundary_nodes(target, source)

        node_ids = {sample["nodeId"] for sample in target["samples"]}
        self.assertEqual(report["removedBoundaryNodes"], 1)
        self.assertEqual(report["addedReplacementNodes"], 1)
        self.assertNotIn("NODE_VMF_BOUNDARY_P_LOW_TAU_LOW_V_LOW_F_LOW_001", node_ids)
        self.assertIn("NODE_MS_REAL_CONTEXT_01", node_ids)

    @staticmethod
    def vector_for_test(value: float) -> dict[str, float]:
        return {dim: value for dim in ("l", "p", "e", "s", "tau", "v", "m", "f")}


if __name__ == "__main__":
    unittest.main()
