"""Run an isolated live OpenEden scenario. Never reads .env or production databases."""
import argparse, pathlib, json, os, socket, subprocess, time, urllib.request, urllib.error, sqlite3, hashlib, re

def request(url, body=None, timeout=240):
    data=None if body is None else json.dumps(body,ensure_ascii=False).encode()
    req=urllib.request.Request(url,data=data,headers={'Content-Type':'application/json'})
    with urllib.request.urlopen(req,timeout=timeout) as response: return json.load(response)

def run(args):
    root=pathlib.Path(__file__).resolve().parents[1]
    experiment=pathlib.Path(args.experiment).resolve() if args.experiment else root/'build/ab-20260930'
    if args.repetition > 1 and (experiment/'abort-future-runs.json').exists():
        raise RuntimeError('Experiment stopped after a confirmed evaluator defect; see abort-future-runs.json')
    source=root if args.variant=='FIXED' else experiment/args.variant
    output=experiment/'runs'/f'{args.variant}-{args.repetition}'
    output.mkdir(parents=True,exist_ok=args.resume)
    scenario_path=pathlib.Path(args.scenario) if args.scenario else experiment/'scenario.json'
    scenario=json.loads(scenario_path.read_text(encoding='utf-8'))[:args.turn_limit]
    assert [row['turn'] for row in scenario]==list(range(1,len(scenario)+1)), 'Scenario must be contiguous'
    db=output/'runtime.db'
    initial_commits=0
    if args.seed_run:
        assert re.fullmatch(r'[AB]-[1-3]',args.seed_run), 'Seed must name a completed isolated A/B run'
        seed=experiment/'runs'/args.seed_run
        assert json.loads((seed/'collection.json').read_text(encoding='utf-8'))['status']=='COLLECTED'
        if not args.resume:
            assert not db.exists(), 'Seed destination must be new'
            with sqlite3.connect('file:'+(seed/'snapshot.db').as_posix()+'?mode=ro',uri=True) as source_db, sqlite3.connect(db) as destination_db:
                source_db.backup(destination_db)
                initial_commits=destination_db.execute('SELECT COUNT(*) FROM conversation_turns').fetchone()[0]
            (output/'seed.json').write_text(json.dumps({'sourceRun':args.seed_run,'sourceSha256':hashlib.sha256((seed/'snapshot.db').read_bytes()).hexdigest(),'initialCommittedTurns':initial_commits},indent=2),encoding='utf-8')
        else:
            initial_commits=json.loads((output/'seed.json').read_text(encoding='utf-8'))['initialCommittedTurns']
    recorded=[]
    if args.resume and (output/'turns.jsonl').exists():
        recorded=[json.loads(line) for line in (output/'turns.jsonl').read_text(encoding='utf-8').splitlines() if line.strip()]
        assert [row['scenarioTurn'] for row in recorded]==list(range(1,len(recorded)+1)), 'Noncontiguous checkpoint'
        scenario=scenario[len(recorded):]
    with socket.socket() as listener:
        listener.bind(('127.0.0.1',0)); port=listener.getsockname()[1]
    assert port!=8080
    env={k:v for k,v in os.environ.items() if not k.startswith('OPENEDEN_')}
    env.update({
        'JAVA_TOOL_OPTIONS':os.environ.get('JAVA_TOOL_OPTIONS','')+' -Djavax.net.ssl.trustStoreType=Windows-ROOT -Djavax.net.ssl.trustStore=NONE',
        'OPENEDEN_LLM_AUTH_MODE':'chatgpt','OPENEDEN_OPENAI_MODEL':'gpt-6-luna',
        'OPENEDEN_LLM_REASONING_EFFORT':'medium','OPENEDEN_SERVER_PORT':str(port),
        'OPENEDEN_RUNTIME_DB_PATH':str(db),'OPENEDEN_MODEL_SETTINGS_DIR':str(output/'model-settings'),
        'OPENEDEN_MODEL_BACKEND':'djl','OPENEDEN_LOCAL_MODEL_ARTIFACT':str(root/'data/models/local-model-artifact.json'),
        'OPENEDEN_DJL_VQVAE_MODEL_PATH':str(root/'data/models/djl/vqvae'),
        'OPENEDEN_DJL_TEXT_MODEL_PATH':str(root/'data/models/djl/text'),
        'OPENEDEN_DJL_EMOTIONAL_MODEL_PATH':str(root/'data/models/djl/emotional'),
        'OPENEDEN_DJL_AFFECT_MODEL_PATH':str(root/'data/models/thymos-6d'),
        'OPENEDEN_PERSONA_PATH':str(source/'persona/atri.yaml'),
        'OPENEDEN_VECTOR_DB_ENABLED':'false','OPENEDEN_ONEBOT_ENABLED':'false',
        'OPENEDEN_HOST_PLATFORM':'CLI','OPENEDEN_HOST_USER_ID':'quality-owner',
        'OMP_NUM_THREADS':'2','MKL_NUM_THREADS':'2',
    })
    if os.environ.get('OPENEDEN_CHATGPT_AUTH_DIR'):env['OPENEDEN_CHATGPT_AUTH_DIR']=os.environ['OPENEDEN_CHATGPT_AUTH_DIR']
    log=open(output/'server.log','a',encoding='utf-8')
    proc=None
    def start():
        p=subprocess.Popen(['F:/SDK/JDK21/bin/java.exe','-Xmx4g','-cp',str(source/'server/build/install/server/lib/*'),'io.ktor.server.netty.EngineMain'],cwd=source,env=env,stdout=log,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW)
        deadline=time.monotonic()+240
        while time.monotonic()<deadline:
            if p.poll() is not None: raise RuntimeError(f'Server exited {p.returncode}; see isolated server.log')
            try:
                request(f'http://127.0.0.1:{port}/health',timeout=2);return p
            except Exception:time.sleep(1)
        p.terminate();p.wait();raise TimeoutError('Isolated server startup timed out')
    def snapshot():
        con=sqlite3.connect(db,timeout=10);con.row_factory=sqlite3.Row
        try:
            return {table:[dict(row) for row in con.execute('SELECT * FROM '+table)] for table in ['incarnation_state','relationship_state','relationship_events','prompt_history_state']}
        finally:con.close()
    def committed():
        with sqlite3.connect(db,timeout=10) as con:
            return con.execute('SELECT COUNT(*) FROM conversation_turns').fetchone()[0]
    try:
        proc=start();print(f'{args.variant}-{args.repetition}: runtime ready port={port}',flush=True)
        accepted=initial_commits+sum(bool(r['result'].get('response')) and not r['result'].get('validationErrors') for r in recorded)
        assert committed()==accepted, 'Database and captured transcript diverged; manual recovery required'
        if args.resume:
            with open(output/'resume-checkpoints.jsonl','a',encoding='utf-8') as stream:
                stream.write(json.dumps({'afterScenarioTurn':len(recorded),'stateAfterRestart':snapshot()},ensure_ascii=False)+'\n')
        for row in scenario:
            turn=row['turn']; scope='quality-secondary' if turn==args.secondary_turn else 'quality-main'
            body={'platform':'CLI','scopeId':scope,'userId':'quality-owner','text':row['user'],'emotionConfidence':0.0}
            before=time.monotonic()
            for attempt in range(1,4):
                committed_before=committed()
                try:
                    result=request(f'http://127.0.0.1:{port}/dev/message',body)
                    break
                except (urllib.error.HTTPError,urllib.error.URLError,TimeoutError) as failure:
                    after=committed()
                    with open(output/'failures.jsonl','a',encoding='utf-8') as stream:
                        stream.write(json.dumps({'scenarioTurn':turn,'attempt':attempt,'error':str(failure),'committedBefore':committed_before,'committedAfter':after})+'\n')
                    # A lost client connection or timeout does not prove the server stopped.
                    # Only a completed HTTP error response is eligible for automatic retry.
                    if not isinstance(failure, urllib.error.HTTPError) or after!=committed_before or attempt==3:raise
                    print(f'{args.variant}-{args.repetition}: turn {turn} transport failure; no commit; retry {attempt}/2',flush=True)
                    time.sleep(attempt*2)
            record={'scenarioTurn':turn,'scopeId':scope,'user':row['user'],'result':result,'durationSeconds':time.monotonic()-before,'state':snapshot()}
            with open(output/'turns.jsonl','a',encoding='utf-8') as stream:
                stream.write(json.dumps(record,ensure_ascii=False)+'\n');stream.flush();os.fsync(stream.fileno())
            if not result.get('response') or result.get('validationErrors'):
                print(f'{args.variant}-{args.repetition}: turn {turn} rejected; recorded without retry',flush=True)
            if turn == 1 or turn % 8 == 0 or turn == len(scenario):
                print(f'{args.variant}-{args.repetition}: turn {turn}/{args.turn_limit} {record["durationSeconds"]:.1f}s evolution={result["evolutionIndex"]}',flush=True)
            if turn==104 and args.turn_limit>104:
                proc.terminate();proc.wait(timeout=30);proc=start()
                with open(output/'restart.json','w',encoding='utf-8') as stream:json.dump({'afterTurn':turn,'stateAfterRestart':snapshot()},stream,ensure_ascii=False,indent=2)
        outcome={'status':'COLLECTED','turns':len(scenario)+len(recorded),'model':'gpt-6-luna','variant':args.variant,'repetition':args.repetition,'evidence':'LIVE_RUNTIME','releaseGate':'NOT_EVALUATED','virtualClock':False,'qdrant':False}
    except Exception as error:
        outcome={'status':'FAILED','error':str(error),'variant':args.variant,'repetition':args.repetition}
        raise
    finally:
        if proc is not None and proc.poll() is None:proc.terminate();proc.wait(timeout=30)
        log.close()
        (output/'collection.json').write_text(json.dumps(outcome,indent=2),encoding='utf-8')
        if db.exists():
            with sqlite3.connect(db) as con,sqlite3.connect(output/'snapshot.db') as backup:con.backup(backup)

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--variant',choices=['A','B','FIXED'],required=True);parser.add_argument('--repetition',type=int,required=True);parser.add_argument('--turn-limit',type=int,default=128);parser.add_argument('--resume',action='store_true');parser.add_argument('--scenario');parser.add_argument('--secondary-turn',type=int,default=110)
    parser.add_argument('--experiment', help='Fresh evidence directory with frozen A/B sources and scenario.json')
    parser.add_argument('--seed-run', help='Continue a completed isolated A/B snapshot in a new test database')
    run(parser.parse_args())
