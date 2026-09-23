from pathlib import Path
import os,json,subprocess,datetime,hashlib,time,threading
r=Path('/home/ekmett/thc-benchmarks/2026-09-23-083914')
def now():return datetime.datetime.now(datetime.timezone.utc).isoformat()
def host():
 out={'utc':now(),'load':Path('/proc/loadavg').read_text().strip(),'cpuStat':Path('/proc/stat').read_text(),'affinity':sorted(os.sched_getaffinity(0)),'governors':{},'frequencyKHz':{},'temperaturesMilliC':{}}
 for cpu in range(24):
  p=Path('/sys/devices/system/cpu')/('cpu'+str(cpu))/'cpufreq'
  for name,key in [('scaling_governor','governors'),('scaling_cur_freq','frequencyKHz')]:
   try:out[key][str(cpu)]=(p/name).read_text().strip()
   except OSError:pass
 for hw in Path('/sys/class/hwmon').glob('hwmon*'):
  try:name=(hw/'name').read_text().strip()
  except OSError:continue
  if name not in ['coretemp','k10temp']:continue
  for p in hw.glob('temp*_input'):
   label=p.with_name(p.name.replace('_input','_label'))
   try:out['temperaturesMilliC'][name+':'+(label.read_text().strip() if label.exists() else p.stem)]=int(p.read_text())
   except (OSError,ValueError):pass
 return out
assert json.loads((r/'provenance/call-demand-structural-proof.json').read_text())['passed']
for f in json.loads((r/'provenance/harness-transfer.json').read_text())['files']:
 assert hashlib.sha256((r/'sources'/f['path']).read_bytes()).hexdigest()==f['sha256']
common=['-Xms4g','-Xmx4g','-XX:+UseCompactObjectHeaders','-Dpolyglot.compiler.InliningRecursionDepth=2','-Dpolyglot.compiler.InliningExpansionBudget=12000','-Dpolyglot.compiler.InliningInliningBudget=12000']
keys=['classOwnedLayouts','typedCases','leadingCaseReturn','constructorClassIdentity','staticShapeUnchecked','boxedValueCache']
def opts(on,cache=True):return common+['-Dthc.'+k+'='+str(on and (cache or k!='boxedValueCache')).lower() for k in keys]
java=r/'toolchains/graalvm-25.3.4.1+1.1';native=r/'sources/native/build/map/native/native-oracle';base=r/'sources/baseline-v7';combined=r/'sources/combined-v1'
runtime=r/'sources/call-demand-v1'
control=r/'sources/call-demand-control-v1'
owned=common+['-Dthc.'+k+'='+str(k=='classOwnedLayouts').lower() for k in keys]
owned += ['-Dpolyglot.compiler.InliningPolicy=Default']
pairs=[('caller-demand-default',control,runtime,owned,owned,'same call-demand-v1 4e4568c2bdb19bba7a69cf366966bdac0f987d02c30f0ee46ed42aa03abf79bf; callDemand certificates removed')]
oracle={int(line.split('\t')[1]):int(line.split('\t')[2]) for line in (r/'provenance/linux-oracle.tsv').read_text().splitlines()}
preflights=[]
for kind,root in [('control',control),('enabled',runtime)]:
 out=r/'results'/('preflight-caller-demand-'+kind);out.mkdir(exist_ok=False)
 argv=['taskset','-c','0-15',str(java/'bin/java'),'--enable-native-access=ALL-UNNAMED','-Xss2m']+owned+['-Dthc.backend=bytecode','-Dthc.diagnosticUnsupported=true','-Dthc.sourceNotesEnabled=true','-Dthc.traceCompilation=true','-cp',str(root/'lib/*'),'thc.MapCheckKt',str(root/'map/modules-remote.txt'),str(r/'provenance/linux-oracle.tsv')]
 print('START CALLER DEMAND PREFLIGHT '+kind+' '+now(),flush=True)
 with (out/'stdout.log').open('w') as stdout,(out/'stderr.log').open('w') as stderr:p=subprocess.run(argv,stdout=stdout,stderr=stderr,timeout=300)
 record={'kind':kind,'argv':argv,'returncode':p.returncode};(out/'command.json').write_text(json.dumps(record,indent=2)+'\n');assert p.returncode==0,record
 lines=(out/'stdout.log').read_text().splitlines()
 for phase in ['before-requested-compilation','after-requested-compilation']:
  rows=[line.split('\t') for line in lines if line.startswith('VERIFIED_MAP\t'+phase+'\t')];assert len(rows)==18 and {int(x[2]):int(x[3]) for x in rows}==oracle
 diag=json.loads(next(x.removeprefix('MAP_DIAGNOSTICS ') for x in lines if x.startswith('MAP_DIAGNOSTICS ')));assert diag['unsupportedTraps']==0 and diag['thunkEvaluations']==(16411 if kind=='control' else 3)
 record['diagnostics']=diag;preflights.append(record);print('PASS CALLER DEMAND PREFLIGHT '+kind+' '+now(),flush=True)
(r/'provenance/caller-demand-preflight.json').write_text(json.dumps({'passed':True,'finished':now(),'checks':preflights},indent=2)+'\n')
state={'started':now(),'status':'running','hostStart':host(),'timedRuns':[]};(r/'results/driver-caller-demand.json').write_text(json.dumps(state,indent=2)+'\n')
for label,baseline,candidate,bo,co,revision in pairs:
 out=r/'results'/label
 argv=['taskset','-c','0-15','python3','-u',str(r/'sources/compare-map-runtimes.py'),str(baseline/'lib'),str(candidate/'lib'),str(native),str(runtime/'map/modules-remote.txt'),str(out),'--java-home',str(java),'--baseline-commit',revision,'--candidate-source-dir',str(runtime/'src/main'),'--baseline-backend','bytecode','--candidate-backend','bytecode','--baseline-source-notes','on','--candidate-source-notes','on','--jvm-warm-seconds','45','--minimum-warm-calls','30000','--native-warm-seconds','10','--process-timeout','600']
 argv += ['--baseline-modules-manifest',str(control/'map/modules-remote.txt')]
 argv += ['--baseline-jvm-option='+x for x in bo]+['--candidate-jvm-option='+x for x in co]
 record={'name':label,'argv':argv,'started':now(),'hostBefore':host()};state['timedRuns'].append(record);(r/'results/driver-caller-demand.json').write_text(json.dumps(state,indent=2)+'\n')
 stop=threading.Event()
 def monitor():
  os.sched_setaffinity(0,{16})
  with (r/'results'/(label+'-host-samples.jsonl')).open('w') as f:
   while not stop.is_set():
    f.write(json.dumps(host())+'\n');f.flush();stop.wait(5)
 t=threading.Thread(target=monitor);t.start()
 try:
  print('START COMPARISON '+label+' '+now(),flush=True)
  with (r/'results'/(label+'-driver.log')).open('w') as log:
   p=subprocess.Popen(argv,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
   for line in p.stdout:log.write(line);log.flush();print(line,end='',flush=True)
   rc=p.wait()
 finally:stop.set();t.join()
 record.update(finished=now(),returncode=rc,hostAfter=host());state['status']='running' if rc==0 else 'failed';(r/'results/driver-caller-demand.json').write_text(json.dumps(state,indent=2)+'\n')
 assert rc==0,(label,rc)
 summary=json.loads((out/'summary.json').read_text());assert json.loads((out/'validation.json').read_text())['passed'];record['summary']=summary
 print('COMPARISON_RESULT '+label+' '+json.dumps(summary),flush=True)
state['finished']=now();state['status']='complete';(r/'results/driver-caller-demand.json').write_text(json.dumps(state,indent=2)+'\n');print('CALLER_DEMAND_COMPARISON_COMPLETE',flush=True)
