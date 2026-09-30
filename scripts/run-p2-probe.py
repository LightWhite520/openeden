"""Bounded P2 checks on independent copies of an existing isolated COUPLE snapshot."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import socket
import sqlite3
import subprocess
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
JAVA = 'F:/SDK/JDK21/bin/java.exe'
CLASSPATH = str(ROOT / 'server/build/install/server/lib/*')


def write(path, value):
    with path.open('x', encoding='utf-8') as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)


def request(url, body=None):
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode()
    with urllib.request.urlopen(urllib.request.Request(
        url, data, {'Content-Type': 'application/json'},
    ), timeout=240 if body else 2) as response:
        return json.load(response)


def environment(folder):
    env = {k: v for k, v in os.environ.items() if not k.startswith('OPENEDEN_')}
    env.update({
        'JAVA_TOOL_OPTIONS': '-Djavax.net.ssl.trustStoreType=Windows-ROOT -Djavax.net.ssl.trustStore=NONE '
                             '-Djdk.net.unixdomain.tmpdir=' + str(ROOT / 'build'),
        'OPENEDEN_LLM_AUTH_MODE': 'chatgpt', 'OPENEDEN_OPENAI_MODEL': 'gpt-6-luna',
        'OPENEDEN_LLM_REASONING_EFFORT': 'medium',
        'OPENEDEN_RUNTIME_DB_PATH': str(folder / 'runtime.db'),
        'OPENEDEN_MODEL_SETTINGS_DIR': str(folder / 'model-settings'),
        'OPENEDEN_MODEL_BACKEND': 'djl',
        'OPENEDEN_LOCAL_MODEL_ARTIFACT': str(ROOT / 'data/models/local-model-artifact.json'),
        'OPENEDEN_DJL_VQVAE_MODEL_PATH': str(ROOT / 'data/models/djl/vqvae'),
        'OPENEDEN_DJL_TEXT_MODEL_PATH': str(ROOT / 'data/models/djl/text'),
        'OPENEDEN_DJL_EMOTIONAL_MODEL_PATH': str(ROOT / 'data/models/djl/emotional'),
        'OPENEDEN_DJL_AFFECT_MODEL_PATH': str(ROOT / 'data/models/thymos-6d'),
        'OPENEDEN_PERSONA_PATH': str(ROOT / 'persona/atri.yaml'),
        'OPENEDEN_VECTOR_DB_ENABLED': 'false', 'OPENEDEN_ONEBOT_ENABLED': 'false',
        'OPENEDEN_HOST_PLATFORM': 'CLI', 'OPENEDEN_HOST_USER_ID': 'quality-owner',
        'OMP_NUM_THREADS': '2', 'MKL_NUM_THREADS': '2',
    })
    if os.environ.get('OPENEDEN_CHATGPT_AUTH_DIR'):
        env['OPENEDEN_CHATGPT_AUTH_DIR'] = os.environ['OPENEDEN_CHATGPT_AUTH_DIR']
    return env


def command(folder, main, *args):
    with (folder / 'commands.log').open('a', encoding='utf-8') as log:
        subprocess.run([JAVA, '-Xmx4g', '-cp', CLASSPATH, main, *map(str, args)],
                       cwd=ROOT, env=environment(folder), stdout=log, stderr=subprocess.STDOUT,
                       creationflags=subprocess.CREATE_NO_WINDOW, check=True, timeout=660)


def clone(seed, folder):
    folder.mkdir()
    with sqlite3.connect('file:' + seed.as_posix() + '?mode=ro', uri=True) as source:
        with sqlite3.connect(folder / 'runtime.db') as target:
            source.backup(target)
            assert target.execute('SELECT phase FROM relationship_state').fetchone()[0] == 'COUPLE'
    write(folder / 'p2-probe.json', {'seed': str(seed), 'sha256': hashlib.sha256(seed.read_bytes()).hexdigest(),
                                  'model': 'gpt-6-luna', 'releaseGate': 'NOT_EVALUATED'})


def snapshot(connection):
    connection.row_factory = sqlite3.Row
    return {table: [dict(row) for row in connection.execute('SELECT * FROM ' + table)]
            for table in ['incarnation_state', 'relationship_state', 'prompt_history_state']}


def runtime(folder, phase, scenario):
    env = environment(folder)
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0))
        port = listener.getsockname()[1]
    assert port != 8080
    env['OPENEDEN_SERVER_PORT'] = str(port)
    url = f'http://127.0.0.1:{port}'
    with (folder / 'server.log').open('a', encoding='utf-8') as log:
        proc = subprocess.Popen([JAVA, '-Xmx4g', '-cp', CLASSPATH, 'io.ktor.server.netty.EngineMain'],
                                cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT,
                                creationflags=subprocess.CREATE_NO_WINDOW)
        try:
            deadline = time.monotonic() + 180
            while time.monotonic() < deadline:
                if proc.poll() is not None:
                    raise RuntimeError('Isolated server exited; inspect server.log')
                try:
                    request(url + '/health')
                    break
                except (urllib.error.URLError, TimeoutError):
                    time.sleep(1)
            else:
                raise TimeoutError('Isolated server startup timed out')
            with sqlite3.connect(folder / 'runtime.db', timeout=10) as connection:
                for row in scenario:
                    cursor = connection.execute('SELECT coalesce(max(rowid),0) FROM trace_spans').fetchone()[0]
                    before = snapshot(connection)
                    started = time.monotonic()
                    # Never retry an ambiguous delivery or an authorization failure.
                    result = request(url + '/dev/message', {
                        'platform': 'CLI', 'scopeId': 'quality-main', 'userId': 'quality-owner',
                        'text': row['user'], 'emotionConfidence': 0.0,
                    })
                    spans = [dict(span) for span in connection.execute(
                        'SELECT * FROM trace_spans WHERE rowid > ? ORDER BY rowid', (cursor,))]
                    record = {'phase': phase, 'scenario': row, 'before': before, 'result': result,
                              'after': snapshot(connection), 'spans': spans,
                              'seconds': time.monotonic() - started}
                    with (folder / 'turns.jsonl').open('a', encoding='utf-8') as output:
                        output.write(json.dumps(record, ensure_ascii=False) + '\n')
                        output.flush()
                        os.fsync(output.fileno())
                    print(f'{folder.name}/{phase}: case {row["turn"]} completed in {record["seconds"]:.1f}s', flush=True)
                    if not result.get('response') or result.get('validationErrors'):
                        raise RuntimeError('Rejected turn retained; no automatic retry')
        finally:
            if proc.poll() is None:
                proc.terminate()
                proc.wait(timeout=30)
            with sqlite3.connect(folder / 'runtime.db') as source:
                with sqlite3.connect(folder / f'{phase}-snapshot.db') as target:
                    source.backup(target)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    output = Path(args.output).resolve()
    assert output.is_relative_to((ROOT / 'build').resolve())
    output.mkdir(exist_ok=False)
    seed = ROOT / 'build/ab-20260930-v2/runs/B-2/snapshot.db'
    scenario = json.loads((ROOT / 'docs/evaluation/scenarios/romance-neutral-boundaries.json').read_text(encoding='utf-8'))
    write(output / 'plan.json', {'compaction': 'one explicit real summary, then three runtime turns',
                               'goldenOriginalTurns': [1, 2, 3, 7, 8, 9, 17, 18], 'automaticRetries': False})
    compaction = output / 'compaction'
    golden = output / 'golden'
    clone(seed, compaction)
    clone(seed, golden)
    try:
        runtime(compaction, 'before', [scenario[6]])
        probe = 'io.openeden.server.evaluation.compaction.PromptHistoryProbeCommandKt'
        command(compaction, probe, 'prepare', compaction)
        command(compaction, 'io.openeden.server.evaluation.SubscriptionEvaluationCommandKt',
                compaction / 'compaction-request.json', compaction / 'compaction-response.json')
        command(compaction, probe, 'apply', compaction)
        runtime(compaction, 'after', [
            {'turn': 0, 'category': 'fact_recall', 'user': '请回顾此前聊天中我们确定过的称呼、承诺和还没解决的事情，按先后顺序说；不确定的不要补。'},
            scenario[7], scenario[8],
        ])
        runtime(golden, 'golden', [scenario[i - 1] for i in [1, 2, 3, 7, 8, 9, 17, 18]])
    except Exception as error:
        write(output / 'collection.json', {'status': 'FAILED', 'errorType': type(error).__name__,
                                          'automaticRetries': False, 'releaseGate': 'NOT_EVALUATED'})
        raise
    write(output / 'collection.json', {'status': 'COLLECTED', 'runtimeTurns': 12, 'compactions': 1,
                                     'releaseGate': 'NOT_EVALUATED', 'virtualClock': False})


if __name__ == '__main__':
    main()
