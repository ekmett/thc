from pathlib import Path
import collections,hashlib,json
r=Path('/home/ekmett/thc-benchmarks/2026-09-23-083914/diagnostics/one-step-force-agnostic')
records=[]
for side in ['control','candidate']:
 d=r/('graph-'+side);ident=json.loads((d/'root-identities.json').read_text())['roots'][0];summary=json.loads((d/'summary.json').read_text())['roots'][0];parsed=d/('parsed-'+Path(ident['bgv']['path']).stem);index=json.loads((parsed/'index.json').read_text());phases=[]
 for row in index['graphs']:
  if not row.get('file') or not ('Before phase HighTierLowering' in row['name'] or 'After mid tier' in row['name']):continue
  p=parsed/row['file'];g=json.loads(p.read_text());ns={n['id']:n for n in g['nodes']};inc=collections.defaultdict(list);out=collections.defaultdict(list)
  for e in g['edges']:inc[e['to']].append(e);out[e['from']].append(e)
  def compact(i):
   n=ns[i];source=n['properties'].get('nodeSourcePosition','');first=source.splitlines()[0].split('(truffle:')[0] if source else None
   return {'id':i,'nodeClass':n['nodeClass'],'block':n.get('block'),'properties':{k:v for k,v in n['properties'].items() if k in ['field','stamp','checkedStamp','name','value','keys']},'sourceFirstFrame':first}
  loops=[n for n in ns.values() if n['nodeClass'].endswith('LoopBeginNode') and n['properties'].get('nodeSourcePosition','').startswith('thc.runtime.Force.execute(')]
  loopRecords=[]
  for n in loops:
   phis=[e['to'] for e in out[n['id']] if ns[e['to']]['nodeClass'].endswith('ValuePhiNode')]
   loopRecords.append({'loop':compact(n['id']),'phis':[{'node':compact(i),'values':[{'edge':e,'node':compact(e['from'])} for e in inc[i] if e['label']=='values']} for i in phis], 'backedges':[{'edge':e,'node':compact(e['from'])} for e in inc[n['id']] if ns[e['from']]['nodeClass'].endswith('LoopEndNode')]})
  materialized=[n for n in ns.values() if n['nodeClass'].endswith(('AllocatedObjectNode','NewInstanceNode')) and n['properties'].get('stamp')=='a!# thc.runtime.Thunk']
  thunkRecords=[{'node':compact(n['id']),'uses':[{'edge':e,'node':compact(e['to'])} for e in out[n['id']]]} for n in materialized]
  phases.append({'name':g['name'],'parsedGraph':{'path':str(p),'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'bytes':p.stat().st_size},'nodes':len(ns),'allLoopBeginCount':sum(n['nodeClass'].endswith('LoopBeginNode') for n in ns.values()),'forceLoopCount':len(loops),'forceLoops':loopRecords,'thunkMaterializationCount':len(materialized),'materializedThunks':thunkRecords})
  del ns,g,inc,out
 alloc=json.loads((r/('profile-'+side)/'validation.json').read_text())['allocationSamples']
 records.append({'side':side,'identity':ident,'summary':{k:summary[k] for k in ['peNodes','beforeHighNodes','afterMidNodes','guestCalls','constantGuestTargets','lateArrays','lateLongArrayPackets','allResidualReferenceArrayPackets','lateLongBoxes','materializedClasses','frontierStateCounts']},'exactAllocation':alloc,'phases':phases})
proof={'scope':'Matched actual latest Map worker, explicit Agnostic, same release Core and caller demand absent. Force loop identification requires Force.execute as first source frame. Static materialization/call counts are not dynamic counts.','variants':records}
(r/'force-graph-audit.json').write_text(json.dumps(proof,indent=2)+'\n')
for v in records:print(v['side'],v['summary'],[(x['name'],x['forceLoopCount'],x['thunkMaterializationCount']) for x in v['phases']])
