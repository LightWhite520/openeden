import math
import unittest
from unittest import mock

import torch

from scripts.user_affect_training import LABELS, TrainingConfig, require_cuda, split_records, validate_records


def record(sample_id: str) -> dict:
    return {
        "sampleId": sample_id,
        "text": sample_id,
        **{label: 0.5 for label in LABELS},
    }


class TrainingDataTest(unittest.TestCase):
    def test_split_is_deterministic_and_disjoint(self):
        records = [record(f"sample-{index:04d}") for index in range(100)]
        first = split_records(records, seed=7)
        second = split_records(list(reversed(records)), seed=7)
        self.assertEqual([r["sampleId"] for r in first.train], [r["sampleId"] for r in second.train])
        ids = [{r["sampleId"] for r in part} for part in (first.train, first.validation, first.test)]
        self.assertFalse(ids[0] & ids[1] or ids[0] & ids[2] or ids[1] & ids[2])

    def test_rejects_duplicate_sample_id(self):
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            validate_records([record("same"), record("same")], minimum_samples=2)

    def test_rejects_non_finite_label(self):
        item = record("bad")
        item["valence"] = math.nan
        with self.assertRaisesRegex(ValueError, "Invalid labels"):
            validate_records([item], minimum_samples=1)

    def test_default_config_matches_codebook_base_model(self):
        config = TrainingConfig()
        self.assertEqual("Qwen/Qwen3-Embedding-0.6B", config.base_model)
        self.assertEqual(torch.bfloat16, config.weight_dtype)
        self.assertFalse(config.freeze_text_encoder)

    def test_require_cuda_rejects_unavailable_device(self):
        with mock.patch("torch.cuda.is_available", return_value=False):
            with self.assertRaisesRegex(RuntimeError, "CUDA is required"):
                require_cuda()


if __name__ == "__main__":
    unittest.main()
