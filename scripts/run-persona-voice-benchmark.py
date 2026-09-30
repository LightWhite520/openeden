"""Freeze and compare real full-pipeline persona runs in isolated databases.

Usage: python scripts/run-persona-voice-benchmark.py freeze build/voice-bench
       python scripts/run-persona-voice-benchmark.py run build/voice-bench baseline
       python scripts/run-persona-voice-benchmark.py run build/voice-bench candidate
Build server:installDist before freezing and again before running the candidate.
No automatic retries: failed responses and partial runs remain available for review.
"""
import argparse
import hashlib
import importlib.util
import json
import shutil
import sqlite3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('probe', ROOT / 'scripts/run-p2-probe.py')
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)
SCENARIO = ROOT / 'docs/evaluation/scenarios/persona-conversational-voice.json'
SEED = ROOT / 'build/ab-20260930-v2/runs/B-2/snapshot.db'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def freeze(output, scenario_path=SCENARIO):
    output.mkdir(parents=True, exist_ok=False)
    base = output / 'baseline-inputs'
    base.mkdir()
    paths = ['persona/atri.yaml', 'data/models/local-model-artifact.json',
             'core/src/commonMain/kotlin/io/openeden/llm/LlmOutputValidator.kt',
             'core/src/commonMain/kotlin/io/openeden/persona/PersonaOutputPolicy.kt']
    manifest = {}
    for relative in paths:
        source = ROOT / relative
        shutil.copy2(source, base / source.name)
        manifest[relative] = digest(source)
    # Freeze all installed project jars; dependency jars stay on the shared classpath.
    for name in ['core-jvm', 'server', 'protocol-jvm']:
        for jar in (ROOT / 'server/build/install/server/lib').glob(name + '-1.0.0-SNAPSHOT.jar'):
            shutil.copy2(jar, base / jar.name)
            manifest[jar.name] = digest(jar)
    shutil.copy2(SEED, output / 'seed.db')
    shutil.copy2(scenario_path, output / 'scenario.json')
    probe.write(output / 'plan.json', {
        'defaultModel': 'gpt-6-luna', 'modelSelection': 'Each arm inputs.json records the selected model',
        'reasoning': 'medium', 'manifest': manifest,
        'seedSha256': digest(SEED), 'scenarioSha256': digest(scenario_path),
        'fixtures': ['fresh', 'continued-high', 'continued-low'],
        'initialVitality': {'continued-high': 0.85, 'continued-low': 0.185785},
        'naturalDrift': True, 'automaticRetries': False, 'onebot': False,
        'criteria': ['context-specific response', 'natural character voice',
                     'appropriate energy and empathy', 'no invented shared experiences',
                     'correct facts and task completion', 'no repetitive canned phrasing'],
        'comparison': 'Paired inputs and initial snapshots; evolving states may diverge. '
                      'Model judged preferences are not human approval. Retain every failure.',
    })


def run(output, arm, model='gpt-6-luna', label=None, fixtures=None, resume=False):
    base = output / (arm + '-inputs')
    original_environment = probe.environment
    if arm == 'candidate' and not base.exists():
        base.mkdir()
        shutil.copy2(ROOT / 'persona/atri.yaml', base / 'atri.yaml')
        shutil.copy2(ROOT / 'data/models/local-model-artifact.json', base / 'local-model-artifact.json')
        for name in ['core-jvm', 'server', 'protocol-jvm']:
            for jar in (ROOT / 'server/build/install/server/lib').glob(name + '-1.0.0-SNAPSHOT.jar'):
                shutil.copy2(jar, base / jar.name)
    persona = base / 'atri.yaml'
    artifact = base / 'local-model-artifact.json'
    probe.CLASSPATH = str(base / '*') + ';' + probe.CLASSPATH

    def environment(folder):
        env = original_environment(folder)
        env['OPENEDEN_PERSONA_PATH'] = str(persona)
        env['OPENEDEN_LOCAL_MODEL_ARTIFACT'] = str(artifact)
        env['OPENEDEN_OPENAI_MODEL'] = model
        return env

    probe.environment = environment
    arm_dir = output / (label or arm)
    arm_dir.mkdir(exist_ok=resume)
    assert not (arm_dir / 'complete.json').exists(), 'This arm is already complete'
    fixtures = fixtures or ['fresh', 'continued-high', 'continued-low']
    inputs = {'model': model, 'fixtures': fixtures, 'personaSha256': digest(persona), 'artifactSha256': digest(artifact),
              'coreJarSha256': digest(base / 'core-jvm-1.0.0-SNAPSHOT.jar')}
    if (arm_dir / 'inputs.json').exists():
        previous = json.loads((arm_dir / 'inputs.json').read_text(encoding='utf-8'))
        assert all(previous.get(k, inputs[k]) == inputs[k] for k in inputs), 'Frozen inputs changed'
    else:
        probe.write(arm_dir / 'inputs.json', inputs)
    scenario = json.loads((output / 'scenario.json').read_text(encoding='utf-8'))
    for fixture in fixtures:
        folder = arm_dir / fixture
        if folder.exists():
            assert resume, 'Existing fixture requires explicit --resume'
        elif fixture == 'fresh':
            folder.mkdir()
        else:
            probe.clone(output / 'seed.db', folder, model=model)
            with sqlite3.connect(folder / 'runtime.db') as con:
                raw = con.execute('SELECT vector_json FROM incarnation_state WHERE singleton_id=1').fetchone()[0]
                vector = json.loads(raw)
                vector['v'] = 0.85 if fixture == 'continued-high' else 0.185785
                con.execute('UPDATE incarnation_state SET vector_json=? WHERE singleton_id=1', (json.dumps(vector),))
            probe.write(folder / 'fixture.json', {'originalVector': json.loads(raw), 'initialVector': vector})
        records = [json.loads(line) for line in (folder / 'turns.jsonl').read_text(encoding='utf-8').splitlines()] if (folder / 'turns.jsonl').exists() else []
        assert [row['scenario'] for row in records] == scenario[:len(records)], 'Recorded inputs differ'
        assert all(row['result'].get('response') and not row['result'].get('validationErrors') for row in records), 'Do not retry a delivered or rejected response'
        if len(records) == len(scenario):
            continue
        if records:
            # Never resend an ambiguous turn: transcript must end at the last recorded response.
            with sqlite3.connect(folder / 'runtime.db') as con:
                last = con.execute('SELECT user_text, assistant_text FROM conversation_turns ORDER BY completed_at_ms DESC LIMIT 1').fetchone()
            assert last == (records[-1]['scenario']['user'], records[-1]['result']['response']), 'Unrecorded delivery requires manual review'
        with (arm_dir / 'attempts.jsonl').open('a', encoding='utf-8') as log:
            log.write(json.dumps({'fixture': fixture, 'nextTurn': len(records) + 1, 'explicitResume': resume,
                                  'model': model, 'inputs': inputs}) + '\n')
        try:
            probe.runtime(folder, fixture, scenario[len(records):])
        except Exception as failure:
            with (arm_dir / 'failures.jsonl').open('a', encoding='utf-8') as log:
                log.write(json.dumps({'fixture': fixture, 'errorType': type(failure).__name__,
                                      'httpStatus': getattr(failure, 'code', None),
                                      'automaticRetry': False, 'details': 'See preserved server.log and runtime.db'}) + '\n')
            raise
    probe.write(arm_dir / 'complete.json', {'status': 'COLLECTED', 'turns': len(scenario) * len(fixtures)})


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['freeze', 'run'])
    parser.add_argument('output', type=Path)
    parser.add_argument('arm', nargs='?', choices=['baseline', 'candidate'])
    parser.add_argument('--model', default='gpt-6-luna')
    parser.add_argument('--label', help='New result directory name, e.g. astra')
    parser.add_argument('--fixtures', nargs='+', choices=['fresh', 'continued-high', 'continued-low'])
    parser.add_argument('--resume', action='store_true', help='Explicitly continue after resolved infrastructure failure; never replay a recorded turn')
    parser.add_argument('--scenario', type=Path, default=SCENARIO, help='Scenario file copied when freezing a new experiment')
    args = parser.parse_args()
    output = args.output.resolve()
    assert output.is_relative_to((ROOT / 'build').resolve()), 'Use an isolated build/ directory'
    if args.command == 'freeze':
        freeze(output, args.scenario)
    else:
        assert args.arm, 'run requires an arm'
        assert not args.label or Path(args.label).name == args.label, 'label must be a directory name'
        run(output, args.arm, args.model, args.label, args.fixtures, args.resume)
