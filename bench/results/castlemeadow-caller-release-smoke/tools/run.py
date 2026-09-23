from pathlib import Path
import datetime, hashlib, json, os, subprocess
R=Path('/home/ekmett/thc-benchmarks/2026-09-23-083914');S=R/'sources/release-main-5af98789';O=R/'results/release-main-5af98789';F=R/'sources/call-demand-v1'
def sha(p):
 h=hashlib.sha256()
 with p.open('rb') as f:
  for b in iter(lambda:f.read(1048576),b''):h.update(b)
 return h.hexdigest()
def now():return datetime.datetime.now(datetime.timezone.utc).isoformat()
def read(p):return json.loads(p.read_text())
def write(p,v):p.write_text(json.dumps(v,indent=2)+'\n')
assert not O.exists();O.mkdir();(S/'lib').mkdir();(S/'map').mkdir()
local=read(S/'local-proof.json');jar=S/'release.jar'
assert sha(jar)==local['releaseJar']['sha256']=='5af987892c35fedc0f170d245574ad704609e9747c7993f970991191d71a773c'
os.link(jar,S/'lib/thc-0.1-experiment.jar')
for x in local['dependencyJars']:
 p=F/'lib'/x['name'];assert sha(p)==x['sha256'];os.link(p,S/'lib'/x['name'])
assert sha(F/'manifest.json')==local['frozenCoreManifest']['sha256']
proof=read(R/'provenance/call-demand-structural-proof.json');assert proof['passed']
for x in proof['modules']:
 p=F/'map'/x['file'];assert sha(p)==x['on'];os.link(p,S/'map'/x['file'])
(S/'map/modules.txt').write_text('\n'.join(str(S/'map'/Path(x).name) for x in (F/'map/modules-remote.txt').read_text().splitlines())+'\n')
native=R/'sources/native/build/map/native/native-oracle';assert sha(native)=='2657dd2c89fa63d94c68dc574a9f90128c2822f15a088c57fa3114f308685dec'
oracle=R/'provenance/linux-oracle.tsv';java=R/'toolchains/graalvm-25.3.4.1+1.1'
files=[*sorted((S/'lib').glob('*.jar')),*sorted((S/'map').glob('*')),oracle,native,java/'release',Path(__file__).resolve()]
manifest={'scope':'Linux release smoke only: fresh JAR, frozen caller-demand Core with certificates present, identical dependencies and Linux-built native oracle. No throughput or allocation claim.', 'startedUtc':now(),'originalCoreManifest':{'path':str(F/'manifest.json'),'sha256':sha(F/'manifest.json')},'files':[{'path':str(p),'sha256':sha(p),'bytes':p.stat().st_size} for p in files]}
write(O/'input-manifest.json',manifest);write(O/'local-proof.json',local);(O/'linux-oracle.tsv').write_bytes(oracle.read_bytes())
expected={int(x.split('\t')[1]):int(x.split('\t')[2]) for x in oracle.read_text().splitlines()};assert len(expected)==18
common=['--enable-native-access=ALL-UNNAMED','-Xss2m','-Xms4g','-Xmx4g','-XX:+UseCompactObjectHeaders','-Dpolyglot.compiler.InliningPolicy=Default','-Dpolyglot.compiler.InliningRecursionDepth=2','-Dpolyglot.compiler.InliningExpansionBudget=12000','-Dpolyglot.compiler.InliningInliningBudget=12000','-Dthc.diagnosticUnsupported=true','-Dthc.sourceNotesEnabled=true','-Dthc.traceCompilation=true']
common+=['-Dthc.'+k+'='+str(k=='classOwnedLayouts').lower() for k in ['classOwnedLayouts','typedCases','leadingCaseReturn','constructorClassIdentity','staticShapeUnchecked','boxedValueCache']]
environment=os.environ.copy();removed=[k for k in ['JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS'] if k in environment]
for k in removed:environment.pop(k)
state={'startedUtc':now(),'status':'running','scope':manifest['scope'],'removedJavaInjectionEnvironmentKeys':removed,'checks':[]};write(O/'validation.json',state)
for backend in ['ast','bytecode']:
 for on in [False,True]:
  label=backend+'-'+('enabled' if on else 'property-absent');out=O/label;out.mkdir();options=common+['-Dthc.backend='+backend]+(['-Dthc.callDemands=true'] if on else [])
  argv=['taskset','-c','0-15',str(java/'bin/java'),*options,'-cp',str(S/'lib/*'),'thc.MapCheckKt',str(S/'map/modules.txt'),str(oracle)]
  record={'backend':backend,'callDemands':True if on else 'property absent','argv':argv,'startedUtc':now()};state['checks'].append(record);write(O/'validation.json',state);print('START_RELEASE_SMOKE',label,now(),flush=True)
  with (out/'stdout.log').open('w') as stdout,(out/'stderr.log').open('w') as stderr:run=subprocess.run(argv,stdout=stdout,stderr=stderr,env=environment,timeout=300)
  record.update(returncode=run.returncode,finishedUtc=now());write(out/'command.json',record);write(O/'validation.json',state);assert run.returncode==0,record
  lines=(out/'stdout.log').read_text().splitlines()
  for phase,key in [('before-requested-compilation','beforeRows'),('after-requested-compilation','afterRows')]:
   rows=[x.split('\t') for x in lines if x.startswith('VERIFIED_MAP\t'+phase+'\t')];assert len(rows)==18 and {int(x[2]):int(x[3]) for x in rows}==expected;record[key]=len(rows)
  d=json.loads(next(x.removeprefix('MAP_DIAGNOSTICS ') for x in lines if x.startswith('MAP_DIAGNOSTICS ')))
  assert d['backend']==backend and d['unsupportedPolicy']=='diagnostic-traps' and d['unsupportedTraps']==0 and d['sourceNotesEnabled'] is True and d['instrumented'] is True
  if on:assert d['thunkEvaluationsByLabel']=={'lvl':3} and d['thunkEvaluations']==3
  if not on and backend=='bytecode':assert d['thunkEvaluationsByLabel']=={'lvl':3,'argument thunk':16408} and d['thunkEvaluations']==16411
  record['diagnostics']=d;write(out/'command.json',record);write(O/'validation.json',state)
  print('PASS_RELEASE_SMOKE',label,json.dumps({'thunkEvaluations':d['thunkEvaluations'],'thunkEvaluationsByLabel':d['thunkEvaluationsByLabel'],'unsupportedTraps':d['unsupportedTraps']}),flush=True)
for x in manifest['files']:assert sha(Path(x['path']))==x['sha256'],x
state.update(passed=True,status='complete',finishedUtc=now(),allInputsUnchanged=True);write(O/'validation.json',state);print('RELEASE_SMOKE_COMPLETE',flush=True)
