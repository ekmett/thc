#!/usr/bin/env python3
"""Serial diagnostic screening, not a three-fork claim. Never changes runtime defaults."""
from pathlib import Path
import argparse,hashlib,itertools,json,os,re,statistics,subprocess,time,datetime
p=argparse.ArgumentParser(description=__doc__);p.add_argument('frozen',type=Path);p.add_argument('out',type=Path);p.add_argument('--java-home',type=Path,default=os.environ.get('JAVA_HOME'));p.add_argument('--backend',choices=['ast','bytecode'],default='ast');p.add_argument('--depths',default='2,3,4');p.add_argument('--budgets',default='12000,24000,48000');p.add_argument('--oracle',type=Path,default=Path('bench/results/source-notes/ast-dispatch-fix/oracle.tsv'));p.add_argument('--jvm-option',action='append',default=[]);args=p.parse_args()
assert args.java_home and args.java_home.is_dir();f=args.frozen.resolve();out=args.out.resolve();assert not out.exists()or not any(out.iterdir());out.mkdir(parents=True,exist_ok=True)
java=args.java_home/'bin/java';jars=sorted((f/'lib').glob('*.jar'));assert jars;modules=[(f/'map'/s).resolve()for s in (f/'map/modules.txt').read_text().splitlines()if s];assert modules
oracleRows=[line.split('\t')for line in args.oracle.read_text().splitlines()];assert len(oracleRows)==16;assert all(len(a)==3 and a[:2]==['mapAggregate',str(10000+i)] for i,a in enumerate(oracleRows));oracle=[int(a[2])for a in oracleRows];cycle=sum(oracle)
signed=lambda x:(x+(1<<63))%(1<<64)-(1<<63)
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
inputs=[*jars,*modules,f/'manifest.json',f/'map/modules.txt',args.oracle.resolve(),Path(__file__).resolve(),Path(__file__).with_name('RuntimeIntrospection.java').resolve(),args.java_home/'release']
config={'scope':'One process/configuration screening only. Rotating 3-fork benchmark still required before accepting a winner. Both budgets move together.','recordedUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'backend':args.backend,'inputs':[{'path':str(x),'sha256':sha(x)}for x in inputs],'jvmOptions':args.jvm_option,'optionsPrefix':'polyglot.compiler.','warmCalls':12000,'warmSeconds':15,'samples':3,'sampleSeconds':2,'commands':[]};(out/'config.json').write_text(json.dumps(config,indent=2)+'\n')
# Compile/load a separate option probe, with no guest workload, before any screen.
source=Path(__file__).with_name('RuntimeIntrospection.java');classes=out/'classes';classes.mkdir()
subprocess.run([str(args.java_home/'bin/javac'),'-cp',os.pathsep.join(map(str,jars)),'-d',str(classes),str(source)],check=True)
probe=subprocess.run([str(java),'-XX:-UseJVMCICompiler','-cp',str(classes)+os.pathsep+os.pathsep.join(map(str,jars)),'RuntimeIntrospection'],capture_output=True,text=True,check=True);(out/'option-descriptors.txt').write_text(probe.stdout+probe.stderr)
event=re.compile(r'\bopt\s+(?:done|start|fail|inval\w*|deopt|queued|unqueued)\b|\bdeopt(?:imization)?\b',re.I)
rows=[]
for depth,budget in itertools.product(map(int,args.depths.split(',')),map(int,args.budgets.split(','))):
 name=f'd{depth}-b{budget}';cmd=[str(java),*args.jvm_option,'--enable-native-access=ALL-UNNAMED','-Xss2m','-Dthc.traceCompilation=true','-Dthc.minimumWarmCalls=12000','-Dthc.diagnosticUnsupported=true','-Dthc.sourceNotesEnabled=true',f'-Dthc.backend={args.backend}',f'-Dpolyglot.compiler.InliningRecursionDepth={depth}',f'-Dpolyglot.compiler.InliningExpansionBudget={budget}',f'-Dpolyglot.compiler.InliningInliningBudget={budget}','-cp',os.pathsep.join(map(str,jars)),'thc.ProbeKt',','.join(map(str,modules)),'mapAggregate','--steady','15','2','3','10000']
 config['commands'].append({'name':name,'argv':cmd});(out/'config.json').write_text(json.dumps(config,indent=2)+'\n');print('Start',name,flush=True)
 started=time.monotonic();timeout=False;returncode=None
 with (out/(name+'.tsv')).open('w')as stdout,(out/(name+'.log')).open('w')as stderr:
  try:returncode=subprocess.run(cmd,stdout=stdout,stderr=stderr,timeout=240).returncode
  except subprocess.TimeoutExpired:timeout=True
 elapsed=time.monotonic()-started
 errors=[];log=(out/(name+'.log')).read_text();active=None;bad=[];sizes={};warm=None;diag=None;verify=False;phases=[]
 for line in log.splitlines():
  if line.startswith('PHASE '):phases.append('PHASE WARM END' if line.startswith('PHASE WARM END') else 'PHASE VERIFY END' if line.startswith('PHASE VERIFY END') else line)
  if line.startswith('PHASE MEASURE')or line.startswith('PHASE VERIFY'):active='active'if line.endswith('BEGIN')else None
  elif active and event.search(line):bad.append(line)
  m=re.search(r'PHASE WARM END calls=(\d+) elapsedNs=(\d+) checksum=(-?\d+)',line)
  if m:warm=list(map(int,m.groups()))
  m=re.search(r'opt done.*?\bid=(\d+)\s+(.*?)\s+\|.*?CodeSize\s+(\d+)',line)
  if m:
   ir=re.search(r'\|IR\s+(\d+)/\s*(\d+)',line);sizes[m[1]]={'name':m[2],'bytes':int(m[3]),'truffleIRNodes':int(ir[1]) if ir else None,'loweredIRNodes':int(ir[2]) if ir else None}
  if line.startswith('diagnostics='):
   try:diag=json.loads(line.split('=',1)[1])
   except (ValueError,TypeError):errors.append('malformed diagnostics')
  if line=='PHASE VERIFY END guestLastTierInstalled=true':verify=True
 expected=['PHASE WARM BEGIN','PHASE WARM END']+[f'PHASE MEASURE {sample} {edge}'for sample in range(1,4)for edge in ['BEGIN','END']]+['PHASE VERIFY BEGIN','PHASE VERIFY END']
 if phases!=expected:errors.append('phase sequence invalid')
 if returncode!=0:errors.append('timeout' if timeout else 'nonzero exit')
 if bad:errors.append('Truffle activity during measured or final verification window')
 if not warm or warm[0]<12000 or warm[1]<15_000_000_000 or warm[0]%256 or warm[2]!=signed(cycle*(warm[0]//16)):errors.append('warmup invalid')
 if not verify:errors.append('installed-code verification missing')
 if not diag or diag.get('instrumented')is not False or diag.get('backend')!=args.backend or diag.get('unsupportedTraps')!=0 or diag.get('sourceNotesEnabled')is not True or diag.get('sourceRootCount',0)<=0 or diag.get('sourceSpanCount',0)<=0 or diag.get('unsupportedPolicy')!='diagnostic-traps':errors.append('diagnostics invalid')
 windows=[]
 for i,line in enumerate((out/(name+'.tsv')).read_text().splitlines(),1):
  a=line.split('\t')
  if len(a)!=6:errors.append('malformed measurement row');continue
  entry,sample,calls,base,checksum,ns=a
  try:sample,calls,base,checksum,ns=map(int,[sample,calls,base,checksum,ns])
  except ValueError:errors.append('non-numeric measurement row');continue
  if entry!='mapAggregate'or sample!=i or base!=10000 or calls<=0 or calls%256 or ns<2_000_000_000 or checksum!=signed(cycle*(calls//16)):errors.append('window checksum/duration invalid')
  if calls>0:windows.append(ns/calls)
 if len(windows)!=3:errors.append('wrong window count')
 compilerEvents=[line for line in log.splitlines() if event.search(line)]
 compilerFailures=[line for line in compilerEvents if re.search(r'opt\s+(?:fail|inval\w*|deopt)',line)]
 row={'exitCode':returncode,'timedOut':timeout,'elapsedProcessSeconds':elapsed,'compilerEventCount':len(compilerEvents),'compilerFailureOrInvalidationEvents':compilerFailures,'name':name,'depth':depth,'expansionBudget':budget,'inliningBudget':budget,'valid':not errors,'errors':errors,'warm':warm,'windowNsPerCall':windows,'medianNsPerCall':statistics.median(windows)if windows else None,'latestCodeByRootId':sizes,'latestGuestCodeBytes':sum(v['bytes']for v in sizes.values()if not v['name'].startswith('org.graalvm.polyglot.')),'badEvents':bad};rows.append(row);(out/'summary.json').write_text(json.dumps({'scope':config['scope'],'runs':rows},indent=2)+'\n');print(name,row['valid'],row['medianNsPerCall'],row['latestGuestCodeBytes'],flush=True)
 for item in config['inputs']:assert sha(Path(item['path']))==item['sha256'],'Frozen input changed'
