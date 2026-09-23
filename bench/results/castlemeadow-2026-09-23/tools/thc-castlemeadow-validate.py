from pathlib import Path
import os,json,hashlib,subprocess,datetime,time
r=Path('/home/ekmett/thc-benchmarks/2026-09-23-083914')
def now():return datetime.datetime.now(datetime.timezone.utc).isoformat()
def sha(p):
 h=hashlib.sha256()
 with p.open('rb') as f:
  for b in iter(lambda:f.read(1024*1024),b''):h.update(b)
 return h.hexdigest()
for i in range(300):
 if (r/'provenance/runtime-transfer-manifest.json').exists():break
 time.sleep(2)
else:raise Exception('Runtime transfer did not finish')
m=json.loads((r/'provenance/runtime-transfer-manifest.json').read_text());mapping={'boxed-value-cache-v7':'baseline-v7','combined-runtime-v1':'combined-v1'}
for local,remote in mapping.items():
 root=r/'sources'/remote
 for f in m[local]['files']:
  p=root/f['path'];assert p.stat().st_size==f['bytes'] and sha(p)==f['sha256'],p
 jars=[p for p in (root/'lib').glob('thc-*.jar')];assert len(jars)==1
 expected={'baseline-v7':'a73ec0e731b96b63523933cafc876b5e1c64884e24a42ded1b504b60d5bb0d7d','combined-v1':'3acc923ce7112d2dd015b63e6aa814a49816ada53905e4a5cdd4961b026829db'}[remote]
 assert sha(jars[0])==expected
 modules=[root/'map'/Path(line).name for line in (root/'map/modules.txt').read_text().splitlines() if line.strip()]
 assert all(p.is_file() for p in modules)
 (root/'map/modules-remote.txt').write_text('\n'.join(map(str,modules))+'\n')
for p in (r/'sources/baseline-v7/map').glob('*.json'):
 assert sha(p)==sha(r/'sources/combined-v1/map'/p.name),p
for f in json.loads((r/'provenance/harness-transfer.json').read_text())['files']:
 assert sha(r/'sources'/f['path'])==f['sha256']
with (r/'provenance/harness-tests.log').open('w') as f:
 subprocess.run(['python3',str(r/'sources/test-compare-map-runtimes.py')],stdout=f,stderr=subprocess.STDOUT,check=True)
native=r/'sources/native/build/map/native/native-oracle';inputs=[int(x.split('\t')[1]) for x in (r/'sources/baseline-v7/map/oracle.tsv').read_text().splitlines() if x.strip()];assert len(inputs)==18
rows=[]
for n in inputs:
 row=subprocess.check_output([str(native),'mapAggregate',str(n)],text=True).strip();actual=int(row.split('\t')[2]);counts={}
 for i in range(max(n,0)):
  key=(i*1103515245+12345)&4095;counts[key]=counts.get(key,0)+1
 for i in range(max(n,0)//4):
  key=(3*i*1103515245+12345)&4095
  if key in counts:counts[key]+=7
 expected=sum((k+1)*v for k,v in counts.items())+sum(counts.get((i*48271+17)&8191,0) for i in range(max(n,0)))+len(counts)
 assert actual==expected,(n,actual,expected);rows.append(row)
oracle=r/'provenance/linux-oracle.tsv';oracle.write_text('\n'.join(rows)+'\n');assert oracle.read_bytes()==(r/'sources/baseline-v7/map/oracle.tsv').read_bytes()
java=r/'toolchains/graalvm-25.3.4.1+1.1/bin/java'
common=['-Xms4g','-Xmx4g','-XX:+UseCompactObjectHeaders','-Dpolyglot.compiler.InliningRecursionDepth=2','-Dpolyglot.compiler.InliningExpansionBudget=12000','-Dpolyglot.compiler.InliningInliningBudget=12000']
keys=['classOwnedLayouts','typedCases','leadingCaseReturn','constructorClassIdentity','staticShapeUnchecked','boxedValueCache']
flags={'baseline':{k:False for k in keys},'all-on':{k:True for k in keys},'cache-off':{k:k!='boxedValueCache' for k in keys}}
result={'started':now(),'frozenFilesVerified':sum(len(x['files']) for x in m.values()),'nativeOracleRows':18,'nativeMatchesIndependentHistogram':True,'nativeMatchesFrozenMacOracle':True,'sameCoreJson':True,'harnessTestsPassed':True,'preflights':[]}
for label,settings in flags.items():
 root=r/'sources'/('baseline-v7' if label=='baseline' else 'combined-v1');out=r/'results'/('preflight-'+label);out.mkdir()
 argv=['taskset','-c','0-15',str(java),'--enable-native-access=ALL-UNNAMED','-Xss2m']+common+['-Dthc.'+k+'='+str(v).lower() for k,v in settings.items()]+['-Dthc.backend=bytecode','-Dthc.diagnosticUnsupported=true','-Dthc.sourceNotesEnabled=true','-Dthc.traceCompilation=true','-cp',str(root/'lib/*'),'thc.MapCheckKt',str(root/'map/modules-remote.txt'),str(oracle)]
 print('START PREFLIGHT '+label+' '+now(),flush=True)
 with (out/'stdout.log').open('w') as stdout,(out/'stderr.log').open('w') as stderr:p=subprocess.run(argv,stdout=stdout,stderr=stderr,timeout=300)
 data={'label':label,'argv':argv,'returncode':p.returncode,'finished':now()};(out/'command.json').write_text(json.dumps(data,indent=2)+'\n');assert p.returncode==0,(label,p.returncode)
 lines=(out/'stdout.log').read_text().splitlines();counts={phase:sum(x.startswith('VERIFIED_MAP\t'+phase+'\t') for x in lines) for phase in ['before-requested-compilation','after-requested-compilation']};assert all(x==18 for x in counts.values()),counts
 diag=json.loads(next(x.removeprefix('MAP_DIAGNOSTICS ') for x in lines if x.startswith('MAP_DIAGNOSTICS ')));assert diag['unsupportedTraps']==0 and diag['backend']=='bytecode' and diag['sourceSpanCount']>0 and diag['sourceRootCount']>0
 data.update(rows=counts,diagnostics=diag);result['preflights'].append(data);(r/'provenance/validation.json').write_text(json.dumps(result,indent=2)+'\n');print('PASS PREFLIGHT '+label+' '+now(),flush=True)
result['finished']=now();result['complete']=True;(r/'provenance/validation.json').write_text(json.dumps(result,indent=2)+'\n');print('ALL_PREFLIGHTS_PASS',flush=True)
