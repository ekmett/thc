from pathlib import Path
import os,json,hashlib,subprocess,datetime,sys
r=Path('/home/ekmett/thc-benchmarks/2026-09-23-083914');runtime=r/'sources/combined-defaults-v1';incoming=r/'sources/demand-manual-changed'
def sha(p):
 h=hashlib.sha256()
 with p.open('rb') as f:
  for b in iter(lambda:f.read(1048576),b''):h.update(b)
 return h.hexdigest()
def now():return datetime.datetime.now(datetime.timezone.utc).isoformat()
m=json.loads((r/'provenance/demand-transfer-manifest.json').read_text())
for f in m['runtimeFiles']:assert sha(runtime/f['path'])==f['sha256'],f
assert sha(incoming/'08-Data.Map.Internal.json')==m['manualCore']['sha256'];assert sha(incoming/'probe.json')==m['parentProbeSha256']
probe=json.loads((incoming/'probe.json').read_text());assert probe['runtimeJarSha256']==m['runtimeJarSha256']
for p in (r/'sources/combined-v1/lib').glob('*.jar'):
 if p.name.startswith('thc-'):continue
 q=runtime/'lib'/p.name
 if not q.exists():os.link(p,q)
 assert sha(p)==sha(q)
for kind in ['original','manual']:
 out=r/'sources'/('demand-'+kind+'-map');out.mkdir(exist_ok=True)
 for f in probe['modules']:
  p=r/'sources/combined-v1/map'/f['file'];assert sha(p)==f['beforeSha256']
  if kind=='manual' and f['file']=='08-Data.Map.Internal.json':p=incoming/f['file']
  assert sha(p)==f['afterSha256' if kind=='manual' else 'beforeSha256']
  q=out/f['file']
  if not q.exists():os.link(p,q)
  assert sha(p)==sha(q)
 (out/'modules.txt').write_text('\n'.join(str(out/Path(x).name) for x in (r/'sources/combined-v1/map/modules-remote.txt').read_text().splitlines())+'\n')
a=json.loads((r/'sources/demand-original-map/08-Data.Map.Internal.json').read_text());b=json.loads((r/'sources/demand-manual-map/08-Data.Map.Internal.json').read_text());changes=[];stack=[([],a,b)]
while stack:
 path,x,y=stack.pop()
 assert type(x) is type(y),path
 if isinstance(x,dict):
  assert x.keys()==y.keys(),path
  stack.extend((path+[k],x[k],y[k]) for k in x)
 elif isinstance(x,list):
  assert len(x)==len(y),path
  stack.extend((path+[i],x[i],y[i]) for i in range(len(x)))
 elif x!=y:changes.append({'path':path,'before':x,'after':y})
assert len(changes)==2,changes
for c in changes:
 assert c['path'][-2:]==['entryStrict',3] and c['before'] is False and c['after'] is True,c
 cursor=a;owners=[]
 for token in c['path']:
  if isinstance(cursor,dict) and cursor.get('id')=='main:Data.Map.Internal.balanceR_':owners.append(cursor['id'])
  cursor=cursor[token]
 assert owners,c
(r/'provenance/demand-structural-proof.json').write_text(json.dumps({'verifiedAt':now(),'runtimeSha256':sha(runtime/'lib/thc-0.1-experiment.jar'),'changedCoreSha256':sha(incoming/'08-Data.Map.Internal.json'),'runtimeFilesVerified':len(m['runtimeFiles']),'changes':changes,'onlyTwoBooleanEntryMetadataChanges':True,'allOtherModulesByteIdentical':True},indent=2)+'\n');print('STRUCTURAL_PROOF_PASS '+json.dumps(changes),flush=True)
if '--preflight' not in sys.argv:sys.exit(0)
assert (r/'provenance/demand-bytecode-approved.json').is_file(),'Waiting for parent bytecode check confirmation'
java=r/'toolchains/graalvm-25.3.4.1+1.1/bin/java';keys=['classOwnedLayouts','typedCases','leadingCaseReturn','constructorClassIdentity','staticShapeUnchecked','boxedValueCache'];flags=['-Dthc.'+k+'='+str(k=='classOwnedLayouts').lower() for k in keys]
common=['-Xms4g','-Xmx4g','-XX:+UseCompactObjectHeaders','-Dpolyglot.compiler.InliningRecursionDepth=2','-Dpolyglot.compiler.InliningExpansionBudget=12000','-Dpolyglot.compiler.InliningInliningBudget=12000']
results=[]
for kind in ['original','manual']:
 out=r/'results'/('preflight-demand-'+kind);out.mkdir(exist_ok=False)
 argv=['taskset','-c','0-15',str(java),'--enable-native-access=ALL-UNNAMED','-Xss2m']+common+flags+['-Dthc.backend=bytecode','-Dthc.diagnosticUnsupported=true','-Dthc.sourceNotesEnabled=true','-Dthc.traceCompilation=true','-cp',str(runtime/'lib/*'),'thc.MapCheckKt',str(r/'sources'/('demand-'+kind+'-map/modules.txt')),str(r/'provenance/linux-oracle.tsv')]
 print('START DEMAND PREFLIGHT '+kind+' '+now(),flush=True)
 with (out/'stdout.log').open('w') as stdout,(out/'stderr.log').open('w') as stderr:p=subprocess.run(argv,stdout=stdout,stderr=stderr,timeout=300)
 record={'kind':kind,'argv':argv,'returncode':p.returncode};(out/'command.json').write_text(json.dumps(record,indent=2)+'\n');assert p.returncode==0
 lines=(out/'stdout.log').read_text().splitlines();counts={phase:sum(x.startswith('VERIFIED_MAP\t'+phase+'\t') for x in lines) for phase in ['before-requested-compilation','after-requested-compilation']};assert all(n==18 for n in counts.values())
 diag=json.loads(next(x.removeprefix('MAP_DIAGNOSTICS ') for x in lines if x.startswith('MAP_DIAGNOSTICS ')));assert diag['unsupportedTraps']==0
 if kind=='manual':assert diag['thunkEvaluations']==3 and diag['thunkEvaluationsByLabel']=={'lvl':3},diag
 results.append(dict(record,rows=counts,diagnostics=diag));print('PASS DEMAND PREFLIGHT '+kind+' '+now()+' '+json.dumps(diag['thunkEvaluationsByLabel']),flush=True)
(r/'provenance/demand-preflight.json').write_text(json.dumps({'passed':True,'finished':now(),'checks':results},indent=2)+'\n')
