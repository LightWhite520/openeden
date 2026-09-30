import copy
import csv
import importlib.util
import io
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('rewrite', ROOT / 'scripts/rewrite-8d-runtime-definitions.py')
rewrite = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rewrite)


class RuntimeDefinitionTests(unittest.TestCase):
    def test_vitality_and_instability_limits_survive_logical_clarity(self):
        vector = dict(l=0.9, p=0.7, e=0.7, s=0.1, tau=0.4, v=0.8, m=0.7, f=0.1)
        self.assertNotIn('restrained', rewrite.en_output_tendency(vector))
        self.assertIn('clear and coherent', rewrite.en_output_tendency(vector))
        self.assertIn('low-energy', rewrite.en_output_tendency(dict(vector, v=0.2)))
        self.assertIn('short', rewrite.en_output_tendency(dict(vector, v=0.2, s=0.8)))
        self.assertIn('unstable', rewrite.en_output_tendency(dict(vector, s=0.8, p=0.2)))

    def test_committed_artifact_matches_corpus_and_refresh_is_idempotent(self):
        corpus = json.loads((ROOT / 'data/training/codebook.selected-vmf-runtime-artifact.json').read_text(encoding='utf-8'))
        before = copy.deepcopy(corpus)
        self.assertEqual(0, rewrite.rewrite_output_tendencies(corpus)['rewritten'])
        self.assertEqual(before, corpus)
        artifact = json.loads((ROOT / 'data/models/local-model-artifact.json').read_text(encoding='utf-8'))
        original = copy.deepcopy(artifact)
        rewrite.sync_artifact_definitions(artifact, corpus)
        self.assertEqual(original, artifact)
        nodes = {sample['nodeId']: sample for sample in corpus['samples']}
        rows = list(csv.DictReader(io.StringIO(artifact['codebookCsv'])))
        self.assertEqual(len(nodes), len(rows))
        for row in rows:
            self.assertEqual(nodes[row['node_id']]['definitionEn'], row['definition_en'])
            self.assertEqual(nodes[row['node_id']]['definitionZh'], row['definition_zh'])

    def test_refresh_preserves_training_payload_and_model_parameters(self):
        sample = dict(nodeId='NODE_TEST', vector=dict(l=.8, p=.4, e=.4, s=.1, tau=.4, v=.7, m=.4, f=.1),
                      definition='This state shows clarity. Output tends toward clear, restrained, structurally stable wording.',
                      definitionEn='This state shows clarity. Output tends toward clear, restrained, structurally stable wording.',
                      definitionZh='该状态表现为逻辑清晰。输出倾向为清晰、克制、结构稳定。',
                      tags=['original'], trainingTextEn='unchanged training input')
        before = copy.deepcopy(sample)
        corpus = {'samples': [sample]}
        rewrite.rewrite_output_tendencies(corpus)
        for key in ['nodeId', 'vector', 'tags', 'trainingTextEn']:
            self.assertEqual(before[key], sample[key])
        artifact = {'codebookCsv': 'node_id,definition_en,definition_zh,tags\nNODE_TEST,old,old,original\n',
                    'vqVae': {'weights': [0.123456789, 0.5]}, 'schemaVersion': 1}
        original = copy.deepcopy(artifact)
        rewrite.sync_artifact_definitions(artifact, corpus)
        self.assertEqual(original['vqVae'], artifact['vqVae'])
        self.assertEqual(original['schemaVersion'], artifact['schemaVersion'])
        self.assertEqual('original', next(csv.DictReader(io.StringIO(artifact['codebookCsv'])))['tags'])
        with self.assertRaises(ValueError):
            rewrite.sync_artifact_definitions(artifact, {'samples': []})


if __name__ == '__main__':
    unittest.main()
