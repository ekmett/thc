#!/usr/bin/env python3
"""Prepared graph/exact allocation capture. Run only in an explicitly granted CPU lane."""
from pathlib import Path
import argparse,datetime,hashlib,json,os,re,subprocess,sys,time
from graph_roots import latest_roots
p=argparse.ArgumentParser(description=__doc__);p.add_argument('mode',choices=['graph','profile']);p.add_argument('frozen',type=Path);p.add_argument('out',type=Path);p.add_argument('--java-home',type=Path,required=True);p.add_argument('--backend',choices=['ast','bytecode'],default='ast');p.add_argument('--depth',type=int,default=2);p.add_argument('--budget',type=int,default=12000);p.add_argument('--oracle',type=Path,required=True);a=p.parse_args()
root=next(p for p in Path(__file__).resolve().parents if(p/'settings.gradle.kts').exists()); f=a.frozen.resolve();out=a.out.resolve();assert not out.exists(),'Output must be new';out.mkdir(parents=True)
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
frozen=json.loads((f/'manifest.json').read_text())
for row in frozen['files']:assert sha(f/row['path'])==row['sha256'],row['path']
jars=sorted((f/'lib').glob('*.jar'));modules=[(f/'map'/s).resolve()for s in(f/'map/modules.txt').read_text().splitlines()if s];assert jars and modules
oracleRows=[line.split('\t')for line in a.oracle.read_text().splitlines()];assert len(oracleRows)==16 and all(x[:2]==['mapAggregate',str(10000+i)]for i,x in enumerate(oracleRows));cycle=sum(int(x[2])for x in oracleRows);signed=lambda x:(x+(1<<63))%(1<<64)-(1<<63)
helper=root/'work/perf-sprint-v2';java=a.java_home/'bin/java';cp=os.pathsep.join(map(str,jars));classes=out/'classes';classes.mkdir()
files=[*jars,*modules,f/'manifest.json',f/'map/modules.txt',a.oracle.resolve(),a.java_home/'release',Path(__file__).resolve(),Path(__file__).with_name('graph_roots.py'),helper/'GraphInspect.java',helper/'MapProfile.java',root/'tools/audit-call-packets.py',helper/'summarize-graphs.py',root/'work/typed-graph-review/compare.py']
config={'recordedUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'scope':'Diagnostic graphs/allocation, not throughput; no graph/JFR timing may be used as benchmark results.','mode':a.mode,'backend':a.backend,'depth':a.depth,'expansionBudget':a.budget,'inliningBudget':a.budget,'frozenManifest':frozen,'inputs':[{'path':str(x),'sha256':sha(x)}for x in files],'commands':[]}
def save(): (out/'capture-config.json').write_text(json.dumps(config,indent=2)+'\n')
def run(cmd,name,timeout=900):
    record={'argv':list(map(str,cmd)),'stdout':name+'.out','stderr':name+'.err'};config['commands'].append(record);save();started=time.monotonic()
    with(out/(name+'.out')).open('w')as stdout,(out/(name+'.err')).open('w')as stderr:
        try:r=subprocess.run(record['argv'],cwd=root,stdout=stdout,stderr=stderr,timeout=timeout);record['exitCode']=r.returncode
        except subprocess.TimeoutExpired:record['timedOut']=True;record['elapsedSeconds']=time.monotonic()-started;save();raise
    record['elapsedSeconds']=time.monotonic()-started;save();assert r.returncode==0,(name,r.returncode)
    return out/(name+'.out'),out/(name+'.err')
flags=[str(java),'--enable-native-access=ALL-UNNAMED','-Xss2m',f'-Dpolyglot.compiler.InliningRecursionDepth={a.depth}',f'-Dpolyglot.compiler.InliningExpansionBudget={a.budget}',f'-Dpolyglot.compiler.InliningInliningBudget={a.budget}','-Dthc.traceCompilation=true','-Dthc.minimumWarmCalls=12000',f'-Dthc.backend={a.backend}','-Dthc.diagnosticUnsupported=true','-Dthc.sourceNotesEnabled=true']
if a.mode=='graph':
    stdout,stderr=run(flags+['-Djdk.graal.Dump=Truffle:1','-Djdk.graal.PrintGraph=File','-Djdk.graal.PrintGraphWithSchedule=true','-Djdk.graal.PrintBackendCFG=false','-Djdk.graal.TrackNodeSourcePosition=true',f'-Djdk.graal.DumpPath={out}','-cp',cp,'thc.ProbeKt',','.join(map(str,modules)),'mapAggregate','--steady','15','0.01','1','10000'],'run')
else:
    run([a.java_home/'bin/javac','-cp',cp,'-d',classes,helper/'MapProfile.java'],'javac-profile')
    stdout,stderr=run(flags+['-XX:FlightRecorderOptions=stackdepth=128','-cp',str(classes)+os.pathsep+cp,'MapProfile',f/'map/modules.txt',a.oracle.resolve(),out,a.backend],'run')
# Conventional names consumed by existing readers; exact original stdout/stderr retained too.
(out/'run.tsv').write_bytes(stdout.read_bytes());(out/'run.log').write_bytes(stderr.read_bytes());log=stderr.read_text();diag=json.loads(next(s.split('=',1)[1]for s in log.splitlines()if s.startswith('diagnostics=')))
assert diag['backend']==a.backend and diag['instrumented']is False and diag['unsupportedTraps']==0 and diag['unsupportedPolicy']=='diagnostic-traps' and diag['sourceNotesEnabled']is True and diag['sourceRootCount']>0 and diag['sourceSpanCount']>0
assert 'PHASE VERIFY END guestLastTierInstalled=true'in log
warm=re.search(r'PHASE WARM END calls=(\d+)(?: elapsedNs=(\d+))? checksum=(-?\d+)',log);assert warm and int(warm[1])>=12000 and int(warm[1])%256==0 and int(warm[3])==signed(cycle*(int(warm[1])//16))
if a.mode=='graph':assert int(warm[2])>=15_000_000_000
active=False;events=[]
for line in log.splitlines():
    if line.startswith(('PHASE MEASURE','PHASE ALLOCATION','PHASE JFR','PHASE VERIFY')):active=' BEGIN'in line
    elif active and re.search(r'\bopt\s+(?:done|start|fail|inval\w*|deopt|queued|unqueued)\b|\bdeopt(?:imization)?\b',line,re.I):events.append(line)
assert not events,events
validation={'diagnostics':diag,'warmCalls':int(warm[1]),'forbiddenCompilationEvents':events,'installedCode':True}
if a.mode=='profile':
    samples=[]
    for i,line in enumerate(stdout.read_text().splitlines(),1):
        sample,calls,base,checksum,allocated=map(int,line.split('\t'));assert(sample,calls,base)==(i,256,10000)and checksum==signed(cycle*16)
        samples.append({'bytes':allocated,'calls':calls,'bytesPerCall':allocated/calls})
    assert len(samples)==3;validation['allocationSamples']=samples
else:
    fields=stdout.read_text().strip().split('\t');assert len(fields)==6 and fields[0]=='mapAggregate';sample,calls,base,checksum,ns=map(int,fields[1:]);assert sample==1 and calls>0 and calls%256==0 and base==10000 and checksum==signed(cycle*(calls//16)) and ns>=10_000_000
(out/'validation.json').write_text(json.dumps(validation,indent=2)+'\n')
if a.mode=='graph':
    rows=latest_roots(out/'run.log');assert rows
    exports=['--add-modules','jdk.graal.compiler','--add-exports','jdk.graal.compiler/jdk.graal.compiler.graphio.parsing=ALL-UNNAMED','--add-exports','jdk.graal.compiler/jdk.graal.compiler.graphio.parsing.model=ALL-UNNAMED']
    run([a.java_home/'bin/javac',*exports,'-d',classes,helper/'GraphInspect.java'],'javac-graph-reader')
    for row in rows:
        graphs=[p for p in out.glob('*.bgv')if p.name.startswith(f"TruffleHotSpotCompilation-{row['compilationId']}[")];assert len(graphs)==1,(row,graphs)
        graph=graphs[0];row['bgv']={'path':graph.name,'sha256':sha(graph),'bytes':graph.stat().st_size}
        run([java,'-XX:-UseJVMCICompiler','-Xmx4g',*exports,'-cp',classes,'GraphInspect',graph,out/('parsed-'+graph.stem),'(?i)After PE Tier|After Inline|Before phase HighTierLowering|After mid tier'],'parse-'+str(row['compilationId']))
    (out/'root-identities.json').write_text(json.dumps({'scope':'Latest successful compilation by within-run root ID; cross-run identities use name plus source; all original BGVs retained.','roots':rows},indent=2)+'\n')
    run([sys.executable,root/'tools/audit-call-packets.py',out,'--output',out/'packet-audit.json'],'packet-audit')
    run([sys.executable,helper/'summarize-graphs.py',out],'graph-summary')
    summary=json.loads((out/'summary.json').read_text());bycid={r['compilationId']:r for r in rows}
    for r in summary['roots']:r['identity']=bycid[r['compilationId']]
    (out/'summary.json').write_text(json.dumps(summary,indent=2)+'\n')
for x in config['inputs']:assert sha(Path(x['path']))==x['sha256'],x['path']
config['outputs']=[{'path':str(p.relative_to(out)),'sha256':sha(p),'bytes':p.stat().st_size}for p in sorted(out.rglob('*'))if p.is_file()and p.name!='capture-config.json'and classes not in p.parents];save()
print('Validated',a.mode,a.backend,a.depth,a.budget,'at',out)
