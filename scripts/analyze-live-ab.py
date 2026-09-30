"""Summarize measured live A/B artifacts without promoting missing release evidence to PASS."""
import pathlib,json,sqlite3,statistics,hashlib,csv,datetime,argparse
ROOT=pathlib.Path(__file__).resolve().parents[1]
BASE=ROOT/'build/ab-20260930'
DIMS=['L','P','E','S','tau','V','M','F']
def read_rows(path):return [json.loads(line) for line in path.read_text(encoding='utf-8').splitlines() if line.strip()] if path.exists() else []
def run_metrics(folder):
    rows=read_rows(folder/'turns.jsonl');metrics={'turns':len(rows)}
    if not rows:return metrics
    metrics['validationFailures']=sum(bool(r['result']['validationErrors']) or not r['result']['response'] for r in rows)
    metrics['vqvaeTurns']=sum('codebook=VQVAE_QUANTIZED' in r['result']['traceTags'] for r in rows)
    accepted=0;continuous=True
    for r in rows:
        accepted+=int(bool(r['result'].get('response')) and not r['result'].get('validationErrors'))
        continuous &= r['result']['evolutionIndex']==accepted
    metrics['acceptedTurns']=accepted
    metrics['evolutionContinuous']=continuous
    states=[r['state']['incarnation_state'][0] for r in rows]
    metrics['sameIncarnation']=len({s['active_incarnation_id'] for s in states})==1
    metrics['omegaMonotonic']=all(b['omega']>=a['omega'] for a,b in zip(states,states[1:]))
    durations=[r['durationSeconds'] for r in rows if r['durationSeconds'] is not None]
    metrics['durationMedianSeconds']=statistics.median(durations) if durations else None
    metrics['postCommitDeliveryFailures']=sum(r.get('deliverySucceeded') is False for r in rows)
    metrics['databaseRecoveredTurns']=[r['scenarioTurn'] for r in rows if r.get('recoveredFromDatabase')]
    metrics['retrievalModes']={m:sum(r['result']['retrievalMode']==m for r in rows) for m in sorted({r['result']['retrievalMode'] for r in rows})}
    metrics['finalVector']=dict(zip(DIMS,rows[-1]['result']['updatedVector']))
    metrics['finalRelationship']=rows[-1]['state']['relationship_state']
    metrics['scopeRestoreVerified']=None
    if len(rows)>=111:
        secondary,restored=rows[109:111]
        metrics['scopeRestoreVerified']=secondary['scopeId']=='quality-secondary' and restored['scopeId']=='quality-main' and metrics['sameIncarnation']
    saturated=[]
    for index,dimension in enumerate(DIMS):
        start=None
        for i,r in enumerate(rows+[None]):
            high=r is not None and r['result']['updatedVector'][index]>=.99
            if high and start is None:start=i
            if not high and start is not None:
                if i-start>=10:saturated.append({'dimension':dimension,'fromTurn':rows[start]['scenarioTurn'],'toTurn':rows[i-1]['scenarioTurn'],'length':i-start})
                start=None
    metrics['sustainedSaturationRuns']=saturated
    cache=[r['result'].get('cacheMetrics') for r in rows[1:]]
    reported=[c for c in cache if c and c.get('inputTokens') is not None and c.get('cachedInputTokens') is not None]
    metrics['cacheReportedWarmTurns']=len(reported)
    metrics['cacheUnobservableWarmTurns']=len(cache)-len(reported)
    total=sum(c['inputTokens'] for c in reported)
    metrics['weightedWarmCacheReadRate']=sum(c['cachedInputTokens'] for c in reported)/total if total else None
    metrics['transportFailures']=len(read_rows(folder/'failures.jsonl'))+int((folder/'initial-failure.json').exists())
    metrics['unobservableTransportAttempts']=metrics['transportFailures']
    metrics['providerCacheCoverageComplete']=metrics['cacheUnobservableWarmTurns']==0 and metrics['unobservableTransportAttempts']==0
    metrics['restartVerified']=None
    if (folder/'restart.json').exists():
        after=json.loads((folder/'restart.json').read_text(encoding='utf-8'))['stateAfterRestart']
        before=next(r for r in rows if r['scenarioTurn']==104)['state']
        metrics['restartVerified']=before['relationship_state']==after['relationship_state'] and before['incarnation_state'][0]['active_incarnation_id']==after['incarnation_state'][0]['active_incarnation_id'] and after['incarnation_state'][0]['evolution_index']==before['incarnation_state'][0]['evolution_index']
    db=folder/'runtime.db'
    if db.exists():
        with sqlite3.connect('file:'+db.as_posix()+'?mode=ro',uri=True) as con:
            spans=[dict(zip(['turnId','stage','tags','attributes','sessionId'],r)) for r in con.execute('SELECT turn_id,stage,tags_json,attributes_json,session_id FROM trace_spans ORDER BY rowid')]
            effective=[];prefixes=[]
            contexts=[];sealed_by_scope={};sealed_errors=[];context_errors=[]
            for span in spans:
                attrs=json.loads(span['attributes'])
                if span['stage']=='state_commit' and 'vector_delta_effective' in attrs:
                    effective.extend(abs(float(pair.split('=')[1])) for pair in attrs['vector_delta_effective'].split(','))
                if span['stage']=='prompt_manifest':prefixes.append({k:v for k,v in attrs.items() if k in ['system_contract_fingerprint','persona_fingerprint','incarnation_anchor_fingerprint']})
                if span['stage']=='retrieval' and 'history_rag_turn_overlap' in attrs:
                    contexts.append(attrs)
                    cohort=(span['sessionId'],attrs['history_epoch'])
                    try:
                        chunks=json.loads(attrs['history_sealed_chunks'])
                        assert isinstance(chunks,list)
                        for key in ['history_source_turn_ids','history_summary_source_turn_ids','retrieved_lineage']:
                            assert isinstance(json.loads(attrs[key]),list)
                    except (ValueError,AssertionError,KeyError):
                        context_errors.append(span['turnId'])
                        continue
                    previous=sealed_by_scope.get(cohort,[])
                    if chunks[:len(previous)]!=previous:sealed_errors.append(span['turnId'])
                    sealed_by_scope[cohort]=chunks
            metrics['allTurnAllDimensionMedianAbsEffectiveDelta']=statistics.median(effective) if effective else None
            metrics['staticPrefixStable']=all(p==prefixes[0] for p in prefixes) if prefixes else None
            metrics['contextEvidenceTurns']=len(contexts)
            metrics['historyRagOverlapCount']=sum(int(c['history_rag_turn_overlap']) for c in contexts) if contexts else None
            metrics['contextEvidenceMalformedTurns']=context_errors
            metrics['sealedChunkStabilityObserved']=bool(sealed_by_scope) and any(sealed_by_scope.values()) and not context_errors
            metrics['sealedChunkMutations']=sealed_errors if contexts else None
            metrics['retrievalUnderfilledTurns']=sum(c['underfilled']=='true' for c in contexts) if contexts else None
            metrics['retrievalExcludedByTurnLineage']=sum(int(c['excluded_by_turn_lineage']) for c in contexts) if contexts else None
            metrics['observedHistoryEpochs']=sorted({int(c['history_epoch']) for c in contexts})
    with open(folder/'bio.csv','w',encoding='utf-8',newline='') as out:
        writer=csv.writer(out);writer.writerow(['turn',*DIMS,'omega','evolution_index'])
        for row,state in zip(rows,states):writer.writerow([row['scenarioTurn'],*row['result']['updatedVector'],state['omega'],row['result']['evolutionIndex']])
    return metrics

def judgments():
    all_decisions=[];scores=[]
    for rep in range(1,4):
        path=BASE/'judgments'/f'response-{rep}.json'
        if not path.exists():continue
        envelope=json.loads(path.read_text(encoding='utf-8'))
        text=envelope.get('output_text') or ''.join(c.get('text','') for m in envelope.get('output',[]) for c in m.get('content',[]) if c.get('type')=='output_text')
        verdict=json.loads(text);mapping=json.loads((path.parent/f'mapping-{rep}.json').read_text(encoding='utf-8'))
        if 'turn_annotations' in verdict:
            annotations=verdict['turn_annotations']
            assert [a['turn'] for a in annotations]==list(range(1,129)), 'Missing, duplicate or unordered annotations'
            assert all(a['context'] in ['OPERATIONAL','NON_OPERATIONAL'] and a['romance_opportunity'] in ['NONE','ROMANTIC','HOT_ROMANCE'] for a in annotations)
            verdict['non_operational_turns']=[a['turn'] for a in annotations if a['context']=='NON_OPERATIONAL']
            for side in ['left','right']:
                assert all(a[side+'_wording'] in ['NATURAL','PROCEDURAL'] and type(a[side+'_false_boundary']) is bool and type(a[side+'_reciprocated']) is bool for a in annotations)
                verdict[side+'_procedural_turns']=[a['turn'] for a in annotations if a['context']=='NON_OPERATIONAL' and a[side+'_wording']=='PROCEDURAL']
                verdict[side+'_false_boundary_turns']=[a['turn'] for a in annotations if a[side+'_false_boundary']]
            verdict['romance_opportunities']=[{'turn':a['turn'],'hot_romance':a['romance_opportunity']=='HOT_ROMANCE',
                'left_reciprocated':a['left_reciprocated'],'right_reciprocated':a['right_reciprocated']} for a in annotations if a['romance_opportunity']!='NONE']
        assert envelope.get('model')=='gpt-6-luna' and envelope.get('status')=='completed'
        assert hashlib.sha256((path.parent/f'request-{rep}.json').read_bytes()).hexdigest()==mapping['requestSha256']
        for arm in ['A','B']:
            assert hashlib.sha256((BASE/'runs'/f'{arm}-{rep}'/'turns.jsonl').read_bytes()).hexdigest()==mapping['transcriptSha256'][arm]
        candidate='LEFT' if mapping['left']=='B' else 'RIGHT'
        assert len(verdict['decisions'])==5
        assert {d['scenario_case_id'] for d in verdict['decisions']}=={'turns-001-024','turns-025-052','turns-053-084','turns-085-104','turns-105-128'}
        for key in ['non_operational_turns','left_procedural_turns','right_procedural_turns','left_false_boundary_turns','right_false_boundary_turns']:
            values=verdict[key]
            assert len(values)==len(set(values)) and all(type(v) is int and 1<=v<=128 for v in values)
        opportunities=verdict['romance_opportunities']
        assert len({o['turn'] for o in opportunities})==len(opportunities)
        assert all(type(o['turn']) is int and 1<=o['turn']<=128 for o in opportunities)
        rates={}
        for arm,slot in [(mapping['left'],'left'),(mapping['right'],'right')]:
            inconsistent=sorted(set(verdict[slot+'_procedural_turns'])-set(verdict['non_operational_turns']))
            hot=[o for o in opportunities if o['hot_romance']]
            denominator=len(verdict['non_operational_turns'])
            rates[arm]={'nonOperationalTurns':denominator,'proceduralTurns':len(verdict[slot+'_procedural_turns']),
                'proceduralRate':len(verdict[slot+'_procedural_turns'])/denominator if denominator and not inconsistent else None,
                'proceduralAnnotationErrors':inconsistent,
                'falseBoundaryTurns':verdict[slot+'_false_boundary_turns'],
                'romanceOpportunities':len(opportunities),'reciprocated':sum(o[slot+'_reciprocated'] for o in opportunities),
                'romanceRate':sum(o[slot+'_reciprocated'] for o in opportunities)/len(opportunities) if opportunities else None,
                'hotOpportunities':len(hot),'hotReciprocated':sum(o[slot+'_reciprocated'] for o in hot),
                'hotRomanceRate':sum(o[slot+'_reciprocated'] for o in hot)/len(hot) if hot else None}
        for d in verdict['decisions']:
            if 'verdict' in d:
                winners=d.pop('verdict')
                assert set(winners)=={'atri_fidelity_winner','companion_quality_winner','overall_winner'}
                assert all(w in ['LEFT','RIGHT','TIE'] for w in winners.values())
                d.update(winners)
            expected=d['atri_fidelity_winner'] if d['atri_fidelity_winner']==d['companion_quality_winner'] else 'TIE'
            assert d['overall_winner']==expected
            d.update(repetition=rep,candidateSlot=candidate,candidateWin=d['overall_winner']==candidate,candidateFactualRegression=d[candidate.lower()+'_factual_regression'])
            all_decisions.append(d)
        scores.append({'repetition':rep,'candidateSlot':candidate,'judgeModel':envelope.get('model'),'rates':rates,'result':verdict})
    return {'decisions':all_decisions,'candidateWinRate':sum(d['candidateWin'] for d in all_decisions)/len(all_decisions) if all_decisions else None,'scores':scores}

def main():
    metrics={f'{arm}-{rep}':run_metrics(BASE/'runs'/f'{arm}-{rep}') for arm in ['A','B'] for rep in range(1,4) if (BASE/'runs'/f'{arm}-{rep}').exists()}
    comparison=judgments()
    abort=BASE/'abort-future-runs.json'
    incomplete_pairs=[rep for rep in range(1,4) if any(metrics.get(f'{arm}-{rep}',{}).get('turns')!=128 for arm in ['A','B'])]
    missing=['independently configured trusted signed manifests','authoritative virtual-time heartbeat and silence checks',
        'separate neutral-turn labels and all 8D directional/relief cases']
    if incomplete_pairs:missing.append('three complete paired repetitions')
    if any(not r.get('sealedChunkStabilityObserved') or r.get('contextEvidenceMalformedTurns') or len(r.get('observedHistoryEpochs',[]))<2 for r in metrics.values()):
        missing.append('complete RAG lineage and observed compaction preservation')
    summary={'evidenceKind':'LIVE_RUNTIME','model':'gpt-6-luna','releaseDecision':'NOT_ACCEPTED','runs':metrics,'pairwise':comparison,
        'experimentStop':json.loads(abort.read_text(encoding='utf-8')) if abort.exists() else None,
        'missingReleaseEvidence':missing}
    (BASE/'measured-summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({'runs':{k:v['turns'] for k,v in metrics.items()},'pairwiseDecisions':len(comparison['decisions']),'releaseDecision':summary['releaseDecision']}))
if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--experiment');args=parser.parse_args()
    if args.experiment:BASE=pathlib.Path(args.experiment).resolve()
    main()
