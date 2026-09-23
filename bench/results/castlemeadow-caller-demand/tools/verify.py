#!/usr/bin/env python3
"""Verify this archived comparison with Python 3.10+, without JVM/network/source corpus."""
from pathlib import Path
import hashlib,json,runpy,math
R=Path(__file__).resolve().parents[1]
def read(p):return json.loads((R/p).read_text())
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def need(x,msg):
 if not x:raise ValueError(msg)
def histogram(n):
 c={}
 for i in range(max(n,0)):
  k=(i*1103515245+12345)&4095;c[k]=c.get(k,0)+1
 for i in range(max(n,0)//4):
  k=(3*i*1103515245+12345)&4095
  if k in c:c[k]+=7
 return sum((k+1)*v for k,v in c.items())+sum(c.get((i*48271+17)&8191,0) for i in range(max(n,0)))+len(c)
m=read('manifest.json');need(m['complete'],'Incomplete bundle')
a={p.relative_to(R).as_posix() for p in R.rglob('*') if p.is_file() and p.name!='manifest.json' and '__pycache__' not in p.parts};need(a=={x['path'] for x in m['files']},'Inventory differs')
for f in m['files']:
 p=R/f['path'];need(p.stat().st_size==f['bytes'] and sha(p)==f['sha256'],'Bad hash '+f['path'])
hashh='01936c494e89b2321c10a30fa2758105c2b62d93b8ca6abd1ad18c212fcf0182';need(sha(R/'tools/compare-map-runtimes.py')==hashh,'Wrong archived harness');h=runpy.run_path(str(R/'tools/compare-map-runtimes.py'))
b=R/'comparison';c=read('comparison/run-config.json');v=read('comparison/validation.json');s=read('comparison/summary.json')
need(v['passed'] and v['inputsUnchanged'] and v['validatedWindows']==45,'Comparison did not pass')
need(c['minimumJvmWarmCalls']==30000 and c['jvmWarmSeconds']==45 and c['nativeWarmSeconds']==10,'Warmup differs')
jar='4e4568c2bdb19bba7a69cf366966bdac0f987d02c30f0ee46ed42aa03abf79bf';native='2657dd2c89fa63d94c68dc574a9f90128c2822f15a088c57fa3114f308685dec'
need({Path(x['path']).name:x['sha256'] for x in c['provenance']['runtimeJars']['baseline']}=={Path(x['path']).name:x['sha256'] for x in c['provenance']['runtimeJars']['candidate']},'Not same JAR set')
need([x['sha256'] for x in c['provenance']['runtimeJars']['baseline'] if Path(x['path']).name.startswith('thc-')]==[jar],'Wrong runtime')
need(c['provenance']['nativeBinary'][0]['sha256']==native,'Wrong native');need(c['provenance']['harness'][0]['sha256']==hashh,'Harness provenance mismatch')
nb=read('provenance/native-build.json');need(nb['returncode']==0 and nb['versions']['ghc']=='9.14.1' and nb['binary']['sha256']==native and 'ELF 64-bit' in nb['binary']['file'],'Native provenance mismatch')
proof=read('provenance/call-demand-structural-proof.json');need(proof['passed'] and proof['runtimeSha256']==jar,'Structural proof/runtime mismatch')
need(proof['counts']=={'certificates':7742,'strictPositions':7686} and proof['balanceREntryStrictUnchanged']==[False]*4,'Demand certificate/entry proof mismatch')
need(len(proof['modules'])==17 and all(x['onlyCallDemandRemoved'] for x in proof['modules']),'Structural proof not complete')
for engine,mode,dirname in [('baseline','off','call-demand-control-v1'),('candidate','on','call-demand-v1')]:
 modules={Path(x['path']).name:x['sha256'] for x in c['provenance']['modules'] if '/'+dirname+'/map/' in x['path']}
 need(modules=={x['file']:x[mode] for x in proof['modules']},'Wrong Core variant')
oracle={}
for line in (R/'provenance/linux-oracle.tsv').read_text().splitlines():
 name,n,y=line.split('\t');n=int(n);y=int(y);need(name=='mapAggregate' and histogram(n)==y,'Native histogram mismatch');oracle[n]=y
need(len(oracle)==18,'Expected 18 inputs')
for kind in ['control','enabled']:
 d=R/'preflights'/kind;cmd=json.loads((d/'command.json').read_text());need(cmd['returncode']==0 and cmd['argv'][:3]==['taskset','-c','0-15'],'Preflight process failed/affinity differs');lines=(d/'stdout.log').read_text().splitlines()
 for phase in ['before-requested-compilation','after-requested-compilation']:
  got=[x.split('\t') for x in lines if x.startswith('VERIFIED_MAP\t'+phase+'\t')];need(len(got)==18 and {int(x[2]):int(x[3]) for x in got}==oracle,'Preflight mismatch')
 need('-Dpolyglot.compiler.InliningPolicy=Default' in cmd['argv'],'Wrong preflight policy')
 diag=json.loads(next(x.removeprefix('MAP_DIAGNOSTICS ') for x in lines if x.startswith('MAP_DIAGNOSTICS ')));need(diag['backend']=='bytecode' and diag['unsupportedTraps']==0,'Diagnostic trap')
 need(diag['thunkEvaluationsByLabel']==({'lvl':3,'argument thunk':16408} if kind=='control' else {'lvl':3}),'Thunk counter mismatch')
driver=read('execution/driver-caller-demand.json');need(driver['status']=='complete' and len(driver['timedRuns'])==1,'Driver incomplete');run=driver['timedRuns'][0];need(run['returncode']==0 and run['argv'][:3]==['taskset','-c','0-15'],'Timing process failed/affinity differs')
cycle=sum(int(x.split('\t')[2]) for x in (b/'oracle.tsv').read_text().splitlines());rows=[]
for spec in c['commands']:
 e=spec['engine'];f=spec['fork'];name=f'{e}-{f}';ws=h['read_windows'](b/(name+'.tsv'),10000,cycle)
 if e!='native':
  audit=h['validate_jvm_log'](b/(name+'.log'),cycle,'bytecode','on',30000,45);need(audit['diagnostics']['instrumented'] is False,'Instrumented timing')
  args=spec['argv'];need('-Dpolyglot.compiler.InliningPolicy=Default' in args,'Wrong timing policy');need(all(x in args for x in ['-Xms4g','-Xmx4g','-XX:+UseCompactObjectHeaders','-Dpolyglot.compiler.InliningRecursionDepth=2','-Dpolyglot.compiler.InliningExpansionBudget=12000','-Dpolyglot.compiler.InliningInliningBudget=12000']),'Common flags differ')
  for key in ['classOwnedLayouts','typedCases','leadingCaseReturn','constructorClassIdentity','staticShapeUnchecked','boxedValueCache']:need('-Dthc.'+key+'='+str(key=='classOwnedLayouts').lower() in args,'Feature flag differs')
  actual_modules=args[args.index('thc.ProbeKt')+1].split(','); expected_modules=[x['path'] for x in c['provenance']['modules'] if str(Path(c['moduleManifests'][e]).parent)+'/' in x['path']]; need(set(actual_modules)==set(expected_modules) and len(actual_modules)==17,'Wrong Core paths in command')
 rows += [dict(engine=e,fork=f,**x) for x in ws]
need(len(rows)==s['windowCount']==45,'Window count differs')
for e in ['baseline','candidate','native']:need(h['engine_summary']([x for x in rows if x['engine']==e])==s['engines'][e],'Recomputed median differs')
for other in ['baseline','native']:need(math.isclose(s['engines']['candidate']['medianNsPerCall']/s['engines'][other]['medianNsPerCall'],s['ratios']['candidate/'+other],rel_tol=1e-14),'Ratio differs')
print(f'PASS: {len(m["files"])} file hashes; 2×18 pre/postcompile inputs; 45 raw timing windows; same JAR/native; only callDemand metadata removed in recorded structural proof; exact Default policy and feature flags; no measured/final Truffle events.')
