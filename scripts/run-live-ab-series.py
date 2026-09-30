"""Collect three paired live repetitions, then blind-judge each completed pair.

Can attach to an already running first pair. Existing completed results are never
overwritten; failed runs stop the series rather than resampling model responses.
"""
import argparse
import concurrent.futures
import json
import os
import pathlib
import subprocess
import sys
import time


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--experiment', required=True)
    parser.add_argument('--attach-first-pair', action='store_true')
    args = parser.parse_args()
    root = pathlib.Path(__file__).resolve().parents[1]
    base = pathlib.Path(args.experiment).resolve()
    env = dict(os.environ)
    env['JAVA_TOOL_OPTIONS'] = env.get('JAVA_TOOL_OPTIONS', '') + ' -Djavax.net.ssl.trustStoreType=Windows-ROOT -Djavax.net.ssl.trustStore=NONE'

    def collect(arm, rep):
        folder = base / 'runs' / f'{arm}-{rep}'
        if args.attach_first_pair and rep == 1:
            deadline = time.monotonic() + 4 * 3600
            while not (folder / 'collection.json').exists():
                if time.monotonic() > deadline:
                    raise TimeoutError(f'{arm}-{rep}: collector did not finish within four hours')
                time.sleep(10)
        elif not folder.exists():
            with (base / f'{arm}-{rep}-collector.log').open('x', encoding='utf-8') as log:
                subprocess.run([sys.executable, str(root / 'scripts/run-live-ab.py'), '--experiment', str(base),
                    '--variant', arm, '--repetition', str(rep)], cwd=root, stdout=log, stderr=subprocess.STDOUT, check=True)
        outcome = json.loads((folder / 'collection.json').read_text(encoding='utf-8'))
        if outcome.get('status') != 'COLLECTED' or outcome.get('turns') != 128:
            raise RuntimeError(f'{arm}-{rep}: incomplete collection; inspect preserved evidence')

    for rep in range(1, 4):
        print(f'Pair {rep}: collecting or waiting for existing collectors', flush=True)
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            jobs = [pool.submit(collect, arm, rep) for arm in ['A', 'B']]
            for job in jobs:
                job.result()
        request = base / 'judgments' / f'request-{rep}.json'
        response = request.with_name(f'response-{rep}.json')
        if not request.exists():
            subprocess.run([sys.executable, str(root / 'scripts/prepare-live-ab-judge.py'),
                '--experiment', str(base), '--repetition', str(rep)], cwd=root, check=True)
        if not response.exists():
            subprocess.run(['F:/SDK/JDK21/bin/java.exe', '-cp', str(root / 'server/build/install/server/lib/*'),
                'io.openeden.server.evaluation.SubscriptionEvaluationCommandKt', str(request), str(response)],
                cwd=root, env=env, check=True)
        subprocess.run([sys.executable, str(root / 'scripts/analyze-live-ab.py'), '--experiment', str(base)],
            cwd=root, check=True)
        print(f'Pair {rep}: measured and judged; see measured-summary.json', flush=True)


if __name__ == '__main__':
    main()
