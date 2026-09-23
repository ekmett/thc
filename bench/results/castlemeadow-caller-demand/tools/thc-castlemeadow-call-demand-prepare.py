from pathlib import Path
import json,hashlib,os,datetime
r=Path('/home/ekmett/thc-benchmarks/2026-09-23-083914')
def sha(p):
 h=hashlib.sha256()
 with p.open('rb') as f:
  for b in iter(lambda:f.read(1048576),b''):h.update(b)
 return h.hexdigest()
m=json.loads((r/'provenance/call-demand-transfer-manifest.json').read_text());counts={'certificates':0,'strictPositions':0}
for label in ['call-demand-v1','call-demand-control-v1']:
 root=r/'sources'/label
 for f in m[label]['files']:assert sha(root/f['path'])==f['sha256'],f
 (root/'lib').mkdir(exist_ok=True)
 for p in (r/'sources/combined-defaults-v1/lib').glob('*.jar'):
  if p.name.startswith('thc-'):continue
  q=root/'lib'/p.name
  if not q.exists():os.link(p,q)
  assert sha(p)==sha(q)
 if label=='call-demand-control-v1':
  src=r/'sources/call-demand-v1/lib/thc-0.1-experiment.jar';q=root/'lib/thc-0.1-experiment.jar'
  if not q.exists():os.link(src,q)
 assert sha(root/'lib/thc-0.1-experiment.jar')=='4e4568c2bdb19bba7a69cf366966bdac0f987d02c30f0ee46ed42aa03abf79bf'
 (root/'map/modules-remote.txt').write_text('\n'.join(str(root/'map'/Path(x).name) for x in (root/'map/modules.txt').read_text().splitlines())+'\n')
for n in ['core-equivalence.json','map-checks.json']:assert sha(r/'provenance'/('call-demand-'+n))==m[n]['sha256']
checks=json.loads((r/'provenance/call-demand-map-checks.json').read_text());assert checks['passed'] and len(checks['checks'])==4
for x in checks['checks']:assert x['returncode']==0 and x['beforeRows']==x['afterRows']==18 and x['diagnostics']['unsupportedTraps']==0
on=r/'sources/call-demand-v1';off=r/'sources/call-demand-control-v1';onmanifest=json.loads((on/'manifest.json').read_text());moduleproof=[]
for mod in onmanifest['modules']:
 p=on/'map'/mod['file'];q=off/'map'/mod['file'];assert sha(p)==mod['withSha256'] and sha(q)==mod['withoutSha256'];a=json.loads(p.read_text());b=json.loads(q.read_text());stack=[a]
 while stack:
  x=stack.pop()
  if isinstance(x,dict):
   if 'callDemand' in x:
    cert=x.pop('callDemand');counts['certificates']+=1;counts['strictPositions']+=sum(v is True for v in cert['strictArgs'])
   stack.extend(x.values())
  elif isinstance(x,list):stack.extend(x)
 assert a==b,mod['file'];moduleproof.append({'file':mod['file'],'on':sha(p),'off':sha(q),'onlyCallDemandRemoved':True})
assert counts=={'certificates':7742,'strictPositions':7686},counts
for label in ['call-demand-v1','call-demand-control-v1']:
 x=json.loads((r/'sources'/label/'map/08-Data.Map.Internal.json').read_text());bind=next(b for b in x['bindings'] if b['id']=='main:Data.Map.Internal.balanceR_');assert bind['entryStrict']==[False]*4 and bind['expr'][3]['entryStrict']==[False]*4
proof={'passed':True,'verifiedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'runtimeSha256':sha(on/'lib/thc-0.1-experiment.jar'),'counts':counts,'balanceREntryStrictUnchanged':[False]*4,'modules':moduleproof,'nativeReusedFromMatchingUnchangedSource':True}
(r/'provenance/call-demand-structural-proof.json').write_text(json.dumps(proof,indent=2)+'\n');print('CALL_DEMAND_STRUCTURE_PASS '+json.dumps(counts),flush=True)
