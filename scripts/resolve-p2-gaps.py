"""Fixed-sample summary, evaluator replay and nonempty RAG; no benchmark retries."""
import importlib.util
import json
import hashlib
import sqlite3
from pathlib import Path

spec = importlib.util.spec_from_file_location('probe', Path(__file__).with_name('run-p2-probe.py'))
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)
root = probe.ROOT
import argparse

def prepare_summary(output):
    summary = output / 'summary'
    probe.clone(root / 'build/p2-20260930-controlled-v2/compaction/before-snapshot.db', summary)
    main = 'io.openeden.server.evaluation.compaction.PromptHistoryProbeCommandKt'
    probe.command(summary, main, 'prepare', summary)
    # Freeze input equivalence: repair the same historical failure, not a fresh favorable sample.
    original = json.loads((root / 'build/p2-20260930-controlled-v2/compaction/compaction-source.json').read_text(encoding='utf-8'))
    assert json.loads((summary / 'compaction-source.json').read_text(encoding='utf-8')) == original

def request_summary(output):
    summary = output / 'summary'
    main = 'io.openeden.server.evaluation.compaction.PromptHistoryProbeCommandKt'
    probe.command(summary, 'io.openeden.server.evaluation.SubscriptionEvaluationCommandKt',
                  summary / 'compaction-request.json', summary / 'compaction-response.json')

def apply_and_recall(output):
    summary = output / 'summary'
    main = 'io.openeden.server.evaluation.compaction.PromptHistoryProbeCommandKt'
    probe.command(summary, main, 'apply', summary, 'p2-source-canonical-summary')
    probe.runtime(summary, 'recall', [{'turn': 1, 'category': 'fixed_fact_recall',
        'user': '请回顾此前聊天中我们确定过的称呼、承诺和还没解决的事情，按先后顺序说；不确定的不要补。'}])


def replay_relationship(output):
    # Replay the exact historical evaluator input; never mutate its original runtime state.
    old = json.loads((root / 'build/p2-20260930-controlled-v2/compaction/turns.jsonl').read_text(encoding='utf-8').splitlines()[1])
    span = next(s for s in old['spans'] if s['stage'] == 'retrieval')
    turn = dict(sourceTurnId=span['turn_id'], incarnationId=old['before']['incarnation_state'][0]['active_incarnation_id'],
        subjectId='CLI:quality-owner', userText=old['scenario']['user'], assistantText=old['result']['response'],
        completedAtMs=span['finished_at_ms'])
    probe.write(output / 'relationship-turn.json', turn)
    probe.command(output, 'io.openeden.server.evaluation.RelationshipEvaluationProbeCommandKt', 'gpt-6-luna',
        output / 'relationship-turn.json', output / 'relationship-result.json')


def verify_rag(output):
    rag = output / 'rag'
    probe.clone(root / 'build/ab-20260930-v2/runs/B-2/snapshot.db', rag)
    texts = [
        '隔离验证档案：蓝桥项目的桌面阅读器使用青色主题，验收记录由林舟保管。',
        '隔离验证档案：蓝桥项目的备份约定为每周三晚上执行，备份目录名为 bluebridge。',
        '隔离验证档案：蓝桥项目的封面选择为绿色，图标形状是圆角方形。',
        '隔离验证档案：蓝桥项目的文档负责人是林舟，文档语言为简体中文。',
        '隔离验证档案：蓝桥项目的演示时长是十五分钟，演示需展示搜索和书签。',
        '隔离验证档案：蓝桥项目的离线测试安排在周六，测试设备为平板。',
        '隔离验证档案：蓝桥项目的日志保存七天，导出格式为 JSON。',
        '隔离验证档案：蓝桥项目的字体大小默认十八号，行距为一点五倍。',
        '隔离验证档案：蓝桥项目的书签颜色为橙色，书签列表按时间排序。',
        '隔离验证档案：蓝桥项目的搜索需支持标题，结果最多显示二十条。',
        '隔离验证档案：蓝桥项目的演示地点是北楼，入场时间为下午两点。',
        '隔离验证档案：蓝桥项目的导出文件由林舟校验，校验算法为 SHA256。',
    ]
    ids = [f'p2-independent-{i}' for i in range(len(texts))]
    with sqlite3.connect(rag / 'runtime.db') as c:
        c.row_factory = sqlite3.Row
        template = dict(c.execute("SELECT * FROM memory_entries WHERE session_id='CLI:quality-main' LIMIT 1").fetchone())
        embedding = dict(c.execute('SELECT * FROM memory_embeddings WHERE memory_id=?', (template['id'],)).fetchone())
        for i, text in enumerate(texts):
            row = dict(template, id=ids[i], content=text, source_turn_ids_json=json.dumps([f'p2-independent-source-{i}']),
                source_memory_ids_json='[]', content_fingerprint=hashlib.sha256(text.encode()).hexdigest(),
                tags_json='["p2_artificial_fixture"]')
            c.execute('INSERT INTO memory_entries (' + ','.join(row) + ') VALUES (' + ','.join('?' for _ in row) + ')', tuple(row.values()))
            emb = dict(embedding, memory_id=ids[i], model_id='p2-fixture-needs-embedding')
            c.execute('INSERT INTO memory_embeddings (' + ','.join(emb) + ') VALUES (' + ','.join('?' for _ in emb) + ')', tuple(emb.values()))
    probe.write(rag / 'fixture-manifest.json', {'artificial': True, 'ids': ids, 'texts': texts,
        'embeddingRequirement': 'recompute with real DJL before sending message', 'expectedMinimumRetrieved': 10})
    probe.runtime(rag, 'capacity', [{'turn': 1, 'category': 'nonempty_rag',
        'user': '请根据以前保留的蓝桥项目档案，回顾其中至少三项具体安排。不确定的不要补。'}], required_memory_ids=ids)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', required=True)
    parser.add_argument('--phase', choices=['all', 'prepare', 'summary', 'recall', 'relationship', 'rag'], default='all')
    args = parser.parse_args()
    output = Path(args.output).resolve()
    if not output.is_relative_to((root / 'build').resolve()):
        raise ValueError('Probe outputs must stay under build')
    output.mkdir(exist_ok=args.phase != 'all')
    actions = {'prepare': prepare_summary, 'summary': request_summary, 'recall': apply_and_recall,
               'relationship': replay_relationship, 'rag': verify_rag}
    for phase in actions if args.phase == 'all' else [args.phase]:
        actions[phase](output)
    probe.write(output / (args.phase + '-complete.json'), {'status': 'COLLECTED',
        'phase': args.phase, 'automaticRetries': False, 'releaseGate': 'NOT_EVALUATED'})


if __name__ == '__main__':
    main()
