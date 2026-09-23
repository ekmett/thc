from pathlib import Path
import datetime, hashlib, json, os, subprocess, threading
R=Path('/home/ekmett/thc-benchmarks/2026-09-23-083914');C=R/'sources/one-step-force-v1';B=R/'sources/release-main-5af98789';O=R/'results/one-step-force-default'
def now():return datetime.datetime.now(datetime.timezone.utc).isoformat()
def sha(p):
 h=hashlib.sha256()
 with p.open('rb') as f:
  for b in iter(lambda:f.read(1048576),b''):h.update(b)
 return h.hexdigest()
def read(p):return json.loads(p.read_text())
def write(p,v):p.write_text(json.dumps(v,indent=2)+'\n')
assert not O.exists()
manifest=read(C/'manifest.json')
for f in manifest['files']:assert sha(C/f['path'])==f['sha256'],f
assert read(C/'test-validation.json')['totals']=={'tests':179,'failures':0,'errors':0,'skipped':0}
assert sha(C/'lib/thc-0.1-experiment.jar')=='c2c2d12e10beb636c830f45c77f7b8998fbb98109af7a74263d64835695b52d2'
assert sha(B/'lib/thc-0.1-experiment.jar')=='5af987892c35fedc0f170d245574ad704609e9747c7993f970991191d71a773c'
diff=read(C/'jar-entry-diff.json');assert [x['entry'] for x in diff['changedEntries']]==['thc/runtime/Force.class']
assert read(R/'results/one-step-force-agnostic/validation.json')['passed']
assert read(R/'results/driver-one-step-force.json')['status']=='complete'
for f in read(B/'local-proof.json')['dependencyJars']:
 p=B/'lib'/f['name'];assert sha(p)==sha(C/'lib'/f['name'])==f['sha256']
proof=read(R/'provenance/call-demand-structural-proof.json')
for f in proof['modules']:
 assert sha(B/'map'/f['file'])==sha(C/'map'/f['file'])==f['on']
native=R/'sources/native/build/map/native/native-oracle';java=R/'toolchains/graalvm-25.3.4.1+1.1';oracle=R/'provenance/linux-oracle.tsv';harness=R/'sources/compare-map-runtimes.py'
assert sha(native)=='2657dd2c89fa63d94c68dc574a9f90128c2822f15a088c57fa3114f308685dec'
assert sha(harness)=='01936c494e89b2321c10a30fa2758105c2b62d93b8ca6abd1ad18c212fcf0182'
env=os.environ.copy();removed=[k for k in ['JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS'] if k in env]
for k in removed:env.pop(k)
flags=['-Xms4g','-Xmx4g','-XX:+UseCompactObjectHeaders','-Dpolyglot.compiler.InliningPolicy=Default','-Dpolyglot.compiler.InliningRecursionDepth=2','-Dpolyglot.compiler.InliningExpansionBudget=12000','-Dpolyglot.compiler.InliningInliningBudget=12000']
flags+=['-Dthc.'+k+'='+str(k=='classOwnedLayouts').lower() for k in ['classOwnedLayouts','typedCases','leadingCaseReturn','constructorClassIdentity','staticShapeUnchecked','boxedValueCache']]
expected={int(x.split('\t')[1]):int(x.split('\t')[2]) for x in oracle.read_text().splitlines()};assert len(expected)==18
checks=[]
for side,root in [('control',B),('candidate',C)]:
 for backend in ['ast','bytecode']:
  out=R/'results'/('preflight-one-step-force-default-'+side+'-'+backend);out.mkdir(exist_ok=False)
  argv=['taskset','-c','0-15',str(java/'bin/java'),'--enable-native-access=ALL-UNNAMED','-Xss2m',*flags,'-Dthc.backend='+backend,'-Dthc.diagnosticUnsupported=true','-Dthc.sourceNotesEnabled=true','-Dthc.traceCompilation=true','-cp',str(root/'lib/*'),'thc.MapCheckKt',str(root/'map/modules.txt'),str(oracle)]
  record={'side':side,'backend':backend,'startedUtc':now(),'argv':argv};print('START_FORCE_PREFLIGHT',side,backend,now(),flush=True)
  with (out/'stdout.log').open('w') as stdout,(out/'stderr.log').open('w') as stderr:p=subprocess.run(argv,stdout=stdout,stderr=stderr,env=env,timeout=300)
  record.update(returncode=p.returncode,finishedUtc=now());write(out/'command.json',record);assert p.returncode==0,record
  lines=(out/'stdout.log').read_text().splitlines()
  for phase,key in [('before-requested-compilation','beforeRows'),('after-requested-compilation','afterRows')]:
   rows=[x.split('\t') for x in lines if x.startswith('VERIFIED_MAP\t'+phase+'\t')];assert len(rows)==18 and {int(x[2]):int(x[3]) for x in rows}==expected;record[key]=len(rows)
  diag=json.loads(next(x.removeprefix('MAP_DIAGNOSTICS ') for x in lines if x.startswith('MAP_DIAGNOSTICS ')));assert diag['backend']==backend and diag['unsupportedTraps']==0 and diag['thunkEvaluationsByLabel']=={'lvl':3,'argument thunk':16408}
  record['diagnostics']=diag;checks.append(record);write(out/'command.json',record);print('PASS_FORCE_PREFLIGHT',side,backend,now(),flush=True)
write(R/'provenance/one-step-force-default-preflights.json',{'passed':True,'checks':checks,'removedJavaInjectionEnvironmentKeys':removed})
runtimeFiles=[*sorted((B/'lib').glob('*.jar')),*sorted((C/'lib').glob('*.jar')),*sorted((B/'map').glob('*')),*sorted((C/'map').glob('*')),native,harness]
captured=[{'path':str(p),'sha256':sha(p)} for p in runtimeFiles]
write(R/'provenance/one-step-force-default-runtime-inputs.json',{'capturedUtc':now(),'files':captured,'originalCandidateManifestSha256':sha(C/'manifest.json'),'callerDemandProperty':'absent','inliningPolicy':'Default'})
def host():
 d={'utc':now(),'load':Path('/proc/loadavg').read_text().strip(),'cpuStat':Path('/proc/stat').read_text(),'affinity':sorted(os.sched_getaffinity(0)),'governors':{},'frequencyKHz':{},'temperaturesMilliC':{}}
 for cpu in range(24):
  p=Path('/sys/devices/system/cpu')/('cpu'+str(cpu))/'cpufreq'
  for name,key in [('scaling_governor','governors'),('scaling_cur_freq','frequencyKHz')]:
   try:d[key][str(cpu)]=(p/name).read_text().strip()
   except OSError:pass
 for hw in Path('/sys/class/hwmon').glob('hwmon*'):
  try:name=(hw/'name').read_text().strip()
  except OSError:continue
  if name not in ['coretemp','k10temp']:continue
  for p in hw.glob('temp*_input'):
   label=p.with_name(p.name.replace('_input','_label'))
   try:d['temperaturesMilliC'][name+':'+(label.read_text().strip() if label.exists() else p.stem)]=int(p.read_text())
   except (OSError,ValueError):pass
 return d
argv=['taskset','-c','0-15','python3','-u',str(harness),str(B/'lib'),str(C/'lib'),str(native),str(C/'map/modules.txt'),str(O),'--java-home',str(java),'--baseline-commit','2844d48 release5af98789; Force loop control','--candidate-source-dir',str(C/'src/main'),'--baseline-modules-manifest',str(B/'map/modules.txt'),'--baseline-backend','bytecode','--candidate-backend','bytecode','--baseline-source-notes','on','--candidate-source-notes','on','--jvm-warm-seconds','45','--minimum-warm-calls','30000','--native-warm-seconds','10','--process-timeout','600']
argv+=['--baseline-jvm-option='+x for x in flags]+['--candidate-jvm-option='+x for x in flags]
state={'startedUtc':now(),'status':'running','argv':argv,'scope':'Matched one-step Force comparison. Caller demands absent; all other runtime class entries byte-identical.','hostBefore':host()};stateFile=R/'results/driver-one-step-force-default.json';write(stateFile,state)
stop=threading.Event()
def monitor():
 os.sched_setaffinity(0,{16})
 with (R/'results/one-step-force-default-host-samples.jsonl').open('w') as f:
  while not stop.is_set():f.write(json.dumps(host())+'\n');f.flush();stop.wait(5)
t=threading.Thread(target=monitor);t.start()
try:
 print('START_FORCE_COMPARISON',now(),flush=True)
 with (R/'results/one-step-force-default-driver.log').open('w') as log:
  p=subprocess.Popen(argv,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,env=env)
  for line in p.stdout:log.write(line);log.flush();print(line,end='',flush=True)
  rc=p.wait()
finally:stop.set();t.join()
state.update(returncode=rc,finishedUtc=now(),hostAfter=host());write(stateFile,state);assert rc==0,rc
for f in captured:assert sha(Path(f['path']))==f['sha256'],f
assert read(O/'validation.json')['passed'];state.update(status='complete',allInputsUnchanged=True,summary=read(O/'summary.json'));write(stateFile,state)
print('FORCE_COMPARISON_COMPLETE',json.dumps(state['summary']),flush=True)
