"""Measure only captured P2 turns; missing provider usage remains unobservable."""
import argparse
import json
import math
from pathlib import Path
import statistics


def analyze(folder):
    records = [json.loads(line) for line in (folder / 'turns.jsonl').read_text(encoding='utf-8').splitlines()]
    contexts, neutral, caches = [], [], []
    sealed = {}
    mutations = []
    for index, record in enumerate(records):
        cache = record['result'].get('cacheMetrics') or {}
        reported = cache.get('inputTokens') is not None and cache.get('cachedInputTokens') is not None
        caches.append({'case': record['scenario']['turn'], 'phase': record['phase'],
                       'observable': reported, 'inputTokens': cache.get('inputTokens'),
                       'cachedInputTokens': cache.get('cachedInputTokens'),
                       'cacheHitRate': cache.get('cacheHitRate') if reported else None})
        for span in record['spans']:
            attrs = json.loads(span['attributes_json'])
            if span['stage'] == 'state_commit' and record['scenario']['category'] == 'neutral':
                if 'vector_delta_effective' in attrs:
                    delta = dict(pair.split('=') for pair in attrs['vector_delta_effective'].split(','))
                    assert set(delta) == {'L', 'P', 'E', 'S', 'tau', 'V', 'M', 'F'}
                    assert all(math.isfinite(float(value)) for value in delta.values())
                    neutral.append({'case': record['scenario']['turn'],
                                    'effectiveDelta': {key: float(value) for key, value in delta.items()}})
            if span['stage'] != 'retrieval' or 'history_epoch' not in attrs:
                continue
            history = set(json.loads(attrs['history_source_turn_ids']))
            lineage = json.loads(attrs['retrieved_lineage'])
            chunks = json.loads(attrs['history_sealed_chunks'])
            summary = set(json.loads(attrs['history_summary_source_turn_ids']))
            rag = {turn for memory in lineage for turn in memory['source_turn_ids']}
            overlap = len(history & rag)
            assert overlap == int(attrs['history_rag_turn_overlap'])
            assert summary <= history
            cohort = (span['session_id'], attrs['history_epoch'])
            prior = sealed.get(cohort, [])
            if chunks[:len(prior)] != prior:
                mutations.append(index)
            sealed[cohort] = chunks
            contexts.append({'case': record['scenario']['turn'], 'epoch': int(attrs['history_epoch']),
                             'overlap': overlap, 'sealedChunks': len(chunks), 'summarySources': len(summary),
                             'retrieved': len(lineage), 'underfilled': attrs['underfilled'],
                             'backfilled': int(attrs['backfilled'])})
    values = [abs(value) for row in neutral for value in row['effectiveDelta'].values()]
    assert len(contexts) == len(records), 'Missing or duplicate retrieval evidence'
    assert len(neutral) == sum(r['scenario']['category'] == 'neutral' for r in records), 'Incomplete neutral evidence'
    return {'turns': len(records), 'contexts': contexts, 'sealedMutations': mutations,
            'providerUsage': caches, 'neutralTurns': neutral,
            'neutralMedianAbsEffectiveDelta': statistics.median(values) if values else None,
            'neutralDimensionSamples': len(values),
            'accepted': sum(bool(r['result'].get('response')) and not r['result'].get('validationErrors') for r in records),
            'vqvaeTurns': sum('codebook=VQVAE_QUANTIZED' in r['result']['traceTags'] for r in records),
            'startingEvolution': records[0]['before']['incarnation_state'][0]['evolution_index'],
            'endingEvolution': records[-1]['after']['incarnation_state'][0]['evolution_index'],
            'startingRelationship': records[0]['before']['relationship_state'][0]['phase'],
            'releaseGate': 'NOT_EVALUATED'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('directory', type=Path)
    args = parser.parse_args()
    result = {name: analyze(args.directory / name) for name in ['compaction', 'golden']}
    with (args.directory / 'measured.json').open('x', encoding='utf-8') as output:
        json.dump(result, output, ensure_ascii=False, indent=2)
    print(json.dumps(result, ensure_ascii=False, indent=2))
