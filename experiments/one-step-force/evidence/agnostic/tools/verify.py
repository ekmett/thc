#!/usr/bin/env python3
"""Verify this archived comparison with Python 3.10+, without JVM/network/source corpus."""
from pathlib import Path
import hashlib,json,runpy,math,xml.etree.ElementTree as ET
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
jars={'baseline':'5af987892c35fedc0f170d245574ad704609e9747c7993f970991191d71a773c','candidate':'c2c2d12e10beb636c830f45c77f7b8998fbb98109af7a74263d64835695b52d2'};native='2657dd2c89fa63d94c68dc574a9f90128c2822f15a088c57fa3114f308685dec'
for engine,jar in jars.items():need([x['sha256'] for x in c['provenance']['runtimeJars'][engine] if Path(x['path']).name.startswith('thc-')]==[jar],'Wrong runtime')
need({Path(x['path']).name:x['sha256'] for x in c['provenance']['runtimeJars']['baseline'] if not Path(x['path']).name.startswith('thc-')}=={Path(x['path']).name:x['sha256'] for x in c['provenance']['runtimeJars']['candidate'] if not Path(x['path']).name.startswith('thc-')},'Dependency mismatch')
need(c['provenance']['nativeBinary'][0]['sha256']==native,'Wrong native');need(c['provenance']['harness'][0]['sha256']==hashh,'Harness mismatch')
nb=read('provenance/native-build.json');need(nb['returncode']==0 and nb['versions']['ghc']=='9.14.1' and nb['binary']['sha256']==native,'Native provenance mismatch')
proof=read('provenance/call-demand-structural-proof.json');need(proof['passed'] and len(proof['modules'])==17,'Missing Core proof')
for engine in ['baseline','candidate']:
 modules={Path(x['path']).name:x['sha256'] for x in c['provenance']['modules'] if str(Path(c['moduleManifests'][engine]).parent)+'/' in x['path']}
 need(modules=={x['file']:x['on'] for x in proof['modules']},'Wrong Core input')
diff=read('provenance/jar-entry-diff.json');need(diff['controlSha256']==jars['baseline'] and diff['candidateSha256']==jars['candidate'] and [x['entry'] for x in diff['changedEntries']]==['thc/runtime/Force.class'],'Changes beyond Force')
need(read('provenance/test-validation.json')['totals']=={'tests':179,'failures':0,'errors':0,'skipped':0},'Test suite failed')

tv=read('provenance/test-validation.json');totals=dict(tests=0,failures=0,errors=0,skipped=0)
for row in tv['files']:
 p=R/'provenance/test-results'/Path(row['path']).name;need(sha(p)==row['sha256'],'Test XML hash differs');tree=ET.parse(p).getroot()
 for key in totals:totals[key]+=int(tree.attrib.get(key,0))
need(totals==tv['totals'],'Test XML totals differ')
policy=read('provenance/policy.json')['name'];need(policy in ['Agnostic','Default'],'Unknown policy')
oracle={}
for line in (R/'provenance/linux-oracle.tsv').read_text().splitlines():
 name,n,y=line.split('\t');n=int(n);y=int(y);need(name=='mapAggregate' and histogram(n)==y,'Native histogram mismatch');oracle[n]=y
need(len(oracle)==18,'Expected 18 inputs')
for kind in ['control-ast','control-bytecode','candidate-ast','candidate-bytecode']:
 d=R/'preflights'/kind;cmd=json.loads((d/'command.json').read_text());need(cmd['returncode']==0 and cmd['argv'][:3]==['taskset','-c','0-15'],'Preflight failed/affinity differs');lines=(d/'stdout.log').read_text().splitlines()
 for phase in ['before-requested-compilation','after-requested-compilation']:
  got=[x.split('\t') for x in lines if x.startswith('VERIFIED_MAP\t'+phase+'\t')];need(len(got)==18 and {int(x[2]):int(x[3]) for x in got}==oracle,'Preflight mismatch')
 need('-Dpolyglot.compiler.InliningPolicy='+policy in cmd['argv'],'Wrong preflight policy');need(not any(x.startswith('-Dthc.callDemands') for x in cmd['argv']),'Caller demand property must be absent')
 diag=json.loads(next(x.removeprefix('MAP_DIAGNOSTICS ') for x in lines if x.startswith('MAP_DIAGNOSTICS ')));need(diag['backend']==kind.split('-')[1] and diag['unsupportedTraps']==0,'Backend/trap mismatch')
 need(diag['thunkEvaluationsByLabel']=={'lvl':3,'argument thunk':16408},'Thunk counter mismatch')
driver=read('execution/driver.json');need(driver['status']=='complete' and driver['returncode']==0 and driver['allInputsUnchanged'] and driver['argv'][:3]==['taskset','-c','0-15'],'Driver failed')
cycle=sum(int(x.split('\t')[2]) for x in (b/'oracle.tsv').read_text().splitlines());rows=[]
for spec in c['commands']:
 e=spec['engine'];f=spec['fork'];name=f'{e}-{f}';ws=h['read_windows'](b/(name+'.tsv'),10000,cycle)
 if e!='native':
  audit=h['validate_jvm_log'](b/(name+'.log'),cycle,'bytecode','on',30000,45);need(audit['diagnostics']['instrumented'] is False,'Instrumented timing')
  args=spec['argv'];need('-Dpolyglot.compiler.InliningPolicy='+policy in args,'Wrong timing policy');need(not any(x.startswith('-Dthc.callDemands') for x in args),'Caller demands must remain absent');need(all(x in args for x in ['-Xms4g','-Xmx4g','-XX:+UseCompactObjectHeaders','-Dpolyglot.compiler.InliningRecursionDepth=2','-Dpolyglot.compiler.InliningExpansionBudget=12000','-Dpolyglot.compiler.InliningInliningBudget=12000']),'Common flags differ')
  for key in ['classOwnedLayouts','typedCases','leadingCaseReturn','constructorClassIdentity','staticShapeUnchecked','boxedValueCache']:need('-Dthc.'+key+'='+str(key=='classOwnedLayouts').lower() in args,'Feature flag differs')
  actual_modules=args[args.index('thc.ProbeKt')+1].split(','); expected_modules=[x['path'] for x in c['provenance']['modules'] if str(Path(c['moduleManifests'][e]).parent)+'/' in x['path']]; need(set(actual_modules)==set(expected_modules) and len(actual_modules)==17,'Wrong Core paths in command')
 rows += [dict(engine=e,fork=f,**x) for x in ws]
need(len(rows)==s['windowCount']==45,'Window count differs')
for e in ['baseline','candidate','native']:need(h['engine_summary']([x for x in rows if x['engine']==e])==s['engines'][e],'Recomputed median differs')
for other in ['baseline','native']:need(math.isclose(s['engines']['candidate']['medianNsPerCall']/s['engines'][other]['medianNsPerCall'],s['ratios']['candidate/'+other],rel_tol=1e-14),'Ratio differs')
print(f'PASS: {len(m["files"])} hashes; 4x18 pre/postcompile inputs; all45 raw windows; onlyForce class change; sameCore/native/dependencies; caller property absent; exact {policy} flags; no measurement/final Truffle events.')
