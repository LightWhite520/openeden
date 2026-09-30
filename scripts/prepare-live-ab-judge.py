import pathlib,json,secrets,hashlib,argparse
root=pathlib.Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser();parser.add_argument('--experiment',required=True);parser.add_argument('--repetition',type=int,required=True);parser.add_argument('--reuse-mapping')
args=parser.parse_args();base=pathlib.Path(args.experiment).resolve()
rep=args.repetition;out=base/'judgments';out.mkdir(exist_ok=True)
rows={v:[json.loads(x) for x in (base/'runs'/f'{v}-{rep}'/'turns.jsonl').read_text(encoding='utf-8').splitlines()] for v in ['A','B']}
assert all(len(r)==128 for r in rows.values())
left=json.loads(pathlib.Path(args.reuse_mapping).read_text(encoding='utf-8'))['left'] if args.reuse_mapping else secrets.choice(['A','B']);right='B' if left=='A' else 'A'
assert left in ['A','B']
cases=[{'id':f'turns-{a:03}-{b:03}','start':a,'end':b} for a,b in [(1,24),(25,52),(53,84),(85,104),(105,128)]]
items=[]
for l,r in zip(rows[left],rows[right]):
 assert l['scenarioTurn']==r['scenarioTurn'] and l['user']==r['user']
 items.append({'turn':l['scenarioTurn'],'user':l['user'],'left':l['result']['response'] if l.get('deliverySucceeded',True) else None,'right':r['result']['response'] if r.get('deliverySucceeded',True) else None})
def obj(props):return {'type':'object','properties':props,'required':list(props),'additionalProperties':False}
text={'type':'string'};boolean={'type':'boolean'};integer={'type':'integer'};winner={'type':'string','enum':['LEFT','RIGHT','TIE']};turns={'type':'array','items':integer}
verdict={'anyOf':[obj({key:{'type':'string','enum':[value]} for key,value in
 [('atri_fidelity_winner',atri),('companion_quality_winner',companion),('overall_winner',atri if atri==companion else 'TIE')]})
 for atri in ['LEFT','RIGHT','TIE'] for companion in ['LEFT','RIGHT','TIE']]}
decision=obj({'scenario_case_id':text,'verdict':verdict,'left_factual_regression':boolean,'right_factual_regression':boolean,'rationale':text})
context={'type':'string','enum':['OPERATIONAL','NON_OPERATIONAL']}
wording={'type':'string','enum':['NATURAL','PROCEDURAL']}
opportunity={'type':'string','enum':['NONE','ROMANTIC','HOT_ROMANCE']}
annotation=obj({'turn':integer,'context':context,'left_wording':wording,'right_wording':wording,
 'left_false_boundary':boolean,'right_false_boundary':boolean,'romance_opportunity':opportunity,
 'left_reciprocated':boolean,'right_reciprocated':boolean})
schema=obj({'decisions':{'type':'array','items':decision},'turn_annotations':{'type':'array','items':annotation}})
request={'model':'gpt-6-luna','reasoning':{'effort':'medium'},'input':[{'role':'developer','content':(root/'docs/evaluation/live-ab-judge.md').read_text(encoding='utf-8')},{'role':'user','content':json.dumps({'persona_yaml':(base/'B/persona/atri.yaml').read_text(encoding='utf-8'),'cases':cases,'transcript':items},ensure_ascii=False)}],'text':{'format':{'type':'json_schema','name':'blind_pairwise','strict':True,'schema':schema}}}
request_path=out/f'request-{rep}.json'
assert not request_path.exists()
request_path.write_text(json.dumps(request,ensure_ascii=False),encoding='utf-8')
(out/f'mapping-{rep}.json').write_text(json.dumps({'left':left,'right':right,'repetition':rep,'requestSha256':hashlib.sha256(request_path.read_bytes()).hexdigest(),'transcriptSha256':{v:hashlib.sha256((base/'runs'/f'{v}-{rep}'/'turns.jsonl').read_bytes()).hexdigest() for v in ['A','B']}},indent=2),encoding='utf-8')
print(request_path)
