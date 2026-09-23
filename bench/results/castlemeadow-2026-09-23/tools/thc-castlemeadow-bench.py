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
for i in range(300):
 p=r/'provenance/validation.json'
 if p.exists() and json.loads(p.read_text()).get('complete'):break
 time.sleep(2)
else:raise Exception('Preflight validation did not complete; no timings launched')
for f in json.loads((r/'provenance/harness-transfer.json').read_text())['files']:
 assert hashlib.sha256((r/'sources'/f['path']).read_bytes()).hexdigest()==f['sha256']
common=['-Xms4g','-Xmx4g','-XX:+UseCompactObjectHeaders','-Dpolyglot.compiler.InliningRecursionDepth=2','-Dpolyglot.compiler.InliningExpansionBudget=12000','-Dpolyglot.compiler.InliningInliningBudget=12000']
keys=['classOwnedLayouts','typedCases','leadingCaseReturn','constructorClassIdentity','staticShapeUnchecked','boxedValueCache']
def opts(on,cache=True):return common+['-Dthc.'+k+'='+str(on and (cache or k!='boxedValueCache')).lower() for k in keys]
java=r/'toolchains/graalvm-25.3.4.1+1.1';native=r/'sources/native/build/map/native/native-oracle';base=r/'sources/baseline-v7';combined=r/'sources/combined-v1'
pairs=[('v7-vs-all-on',base,combined,opts(False),opts(True),'frozen boxed-value-cache-v7 a73ec0e731b96b63523933cafc876b5e1c64884e24a42ded1b504b60d5bb0d7d compact all runtime toggles false'),('combined-cache',combined,combined,opts(True),opts(True,False),'frozen combined-runtime-v1 3acc923ce7112d2dd015b63e6aa814a49816ada53905e4a5cdd4961b026829db all-on compact')]
state={'started':now(),'status':'running','hostStart':host(),'timedRuns':[]};(r/'results/driver.json').write_text(json.dumps(state,indent=2)+'\n')
for label,baseline,candidate,bo,co,revision in pairs:
 out=r/'results'/label
 argv=['taskset','-c','0-15','python3','-u',str(r/'sources/compare-map-runtimes.py'),str(baseline/'lib'),str(candidate/'lib'),str(native),str(combined/'map/modules-remote.txt'),str(out),'--java-home',str(java),'--baseline-commit',revision,'--candidate-source-dir',str(combined/'src/main'),'--baseline-backend','bytecode','--candidate-backend','bytecode','--baseline-source-notes','on','--candidate-source-notes','on','--jvm-warm-seconds','45','--minimum-warm-calls','30000','--native-warm-seconds','10','--process-timeout','600']
 argv += ['--baseline-jvm-option='+x for x in bo]+['--candidate-jvm-option='+x for x in co]
 record={'name':label,'argv':argv,'started':now(),'hostBefore':host()};state['timedRuns'].append(record);(r/'results/driver.json').write_text(json.dumps(state,indent=2)+'\n')
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
 record.update(finished=now(),returncode=rc,hostAfter=host());state['status']='running' if rc==0 else 'failed';(r/'results/driver.json').write_text(json.dumps(state,indent=2)+'\n')
 assert rc==0,(label,rc)
 summary=json.loads((out/'summary.json').read_text());assert json.loads((out/'validation.json').read_text())['passed'];record['summary']=summary
 print('COMPARISON_RESULT '+label+' '+json.dumps(summary),flush=True)
state['finished']=now();state['status']='complete';(r/'results/driver.json').write_text(json.dumps(state,indent=2)+'\n');print('BOTH_COMPARISONS_COMPLETE',flush=True)
