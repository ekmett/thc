#!/usr/bin/env python3
"""Verify the self-contained Linux evidence bundle; no JVM or network required."""
from pathlib import Path
import hashlib,json,runpy,sys
R=Path(__file__).resolve().parents[1]
def read(p):return json.loads((R/p).read_text())
def sha(p):
 h=hashlib.sha256()
 with p.open('rb') as f:
  for b in iter(lambda:f.read(1024*1024),b''):h.update(b)
 return h.hexdigest()
def require(ok,why):
 if not ok:raise ValueError(why)
def hist(n):
 counts={}
 for i in range(max(n,0)):
  k=(i*1103515245+12345)&4095;counts[k]=counts.get(k,0)+1
 for i in range(max(n,0)//4):
  k=(3*i*1103515245+12345)&4095
  if k in counts:counts[k]+=7
 return sum((k+1)*v for k,v in counts.items())+sum(counts.get((i*48271+17)&8191,0) for i in range(max(n,0)))+len(counts)
manifest=read('manifest.json');require(manifest['complete'] is True,'Evidence bundle is not complete')
actual={p.relative_to(R).as_posix() for p in R.rglob('*') if p.is_file() and p.name!='manifest.json' and '__pycache__' not in p.parts}
expected={x['path'] for x in manifest['files']};require(actual==expected,'File inventory differs')
for f in manifest['files']:
 p=R/f['path'];require(p.stat().st_size==f['bytes'] and sha(p)==f['sha256'],'Bad file: '+f['path'])
harness=R/'tools/compare-map-runtimes.py';require(sha(harness)=='01936c494e89b2321c10a30fa2758105c2b62d93b8ca6abd1ad18c212fcf0182','Wrong archived harness')
h=runpy.run_path(str(harness));keys=['classOwnedLayouts','typedCases','leadingCaseReturn','constructorClassIdentity','staticShapeUnchecked','boxedValueCache']
basejar='a73ec0e731b96b63523933cafc876b5e1c64884e24a42ded1b504b60d5bb0d7d';newjar='3acc923ce7112d2dd015b63e6aa814a49816ada53905e4a5cdd4961b026829db';nativehash='2657dd2c89fa63d94c68dc574a9f90128c2822f15a088c57fa3114f308685dec'
transfer=read('provenance/runtime-transfer-manifest.json')
modulehashes={}
for k in ['boxed-value-cache-v7','combined-runtime-v1']:
 modulehashes[k]={Path(x['path']).name:x['sha256'] for x in transfer[k]['files'] if x['path'].startswith('map/') and x['path'].endswith('.json')}
require(len(modulehashes['boxed-value-cache-v7'])==17 and modulehashes['boxed-value-cache-v7']==modulehashes['combined-runtime-v1'],'Frozen Core differs')
nb=read('provenance/native-build.json');require(nb['returncode']==0 and nb['versions']['ghc']=='9.14.1' and nb['binary']['sha256']==nativehash and 'ELF 64-bit' in nb['binary']['file'],'Linux native build proof differs')
require(read('provenance/ghc-signature.json')['verified'] is True,'GHC checksum signature not verified')
require(read('provenance/graal-download-public.json')['checksumVerified'] is True,'Graal download checksum not verified')
release=read('provenance/graal-install.json')['release'];require(release['GRAALVM_VERSION'].strip('"')=='25.3.4.1' and release['JAVA_VERSION'].strip('"').startswith('25.') and release['OS_ARCH'].strip('"')=='x86_64','Wrong Graal release')
fulloracle={}
for row in (R/'provenance/linux-oracle.tsv').read_text().splitlines():
 name,n,y=row.split('\t');n=int(n);y=int(y);require(name=='mapAggregate' and hist(n)==y,'Native histogram mismatch');fulloracle[n]=y
require(len(fulloracle)==18,'Expected18 native oracle inputs')
for label in ['baseline','all-on','cache-off','owned-only']:
 out=R/'preflights'/('preflight-'+label);cmd=json.loads((out/'command.json').read_text());require(cmd['returncode']==0 and cmd['argv'][:3]==['taskset','-c','0-15'],'Preflight failed or wrong affinity')
 lines=(out/'stdout.log').read_text().splitlines()
 for phase in ['before-requested-compilation','after-requested-compilation']:
  got={}
  for line in lines:
   if line.startswith('VERIFIED_MAP\t'+phase+'\t'):
    _,_,n,y=line.split('\t');require(int(n) not in got,'Duplicate preflight row');got[int(n)]=int(y)
  require(got==fulloracle,'Preflight rows differ: '+label+' '+phase)
 diag=json.loads(next(x.removeprefix('MAP_DIAGNOSTICS ') for x in lines if x.startswith('MAP_DIAGNOSTICS ')))
 require(diag['unsupportedTraps']==0 and diag['backend']=='bytecode' and diag['sourceSpanCount']>0 and diag['sourceRootCount']>0,'Preflight diagnostics failed')
drivers=[read('execution/driver-first-two.json'),read('execution/driver-third.json')]
require(all(x['status']=='complete' for x in drivers),'Execution driver incomplete')
runs=[x for d in drivers for x in d['timedRuns']]
require({x['name'] for x in runs}=={'v7-vs-all-on','combined-cache','owned-only-vs-experiments'} and len(runs)==3,'Wrong execution run set')
require(all(x['returncode']==0 and x['argv'][:3]==['taskset','-c','0-15'] for x in runs),'Wrong timing affinity or failed process')
comparisons=['v7-vs-all-on','combined-cache','owned-only-vs-experiments'];total=0
for label in comparisons:
 b=R/'comparisons'/label;c=json.loads((b/'run-config.json').read_text());v=json.loads((b/'validation.json').read_text());s=json.loads((b/'summary.json').read_text())
 require(v['passed'] and v['validatedWindows']==45 and v['inputsUnchanged'],'Comparison failed: '+label)
 require(c['minimumJvmWarmCalls']==30000 and c['jvmWarmSeconds']==45 and c['nativeWarmSeconds']==10,'Wrong warmup')
 require(c['provenance']['nativeBinary'][0]['sha256']==nativehash,'Wrong native binary')
 for engine in ['baseline','candidate']:
  want=basejar if label=='v7-vs-all-on' and engine=='baseline' else newjar
  jar=[x for x in c['provenance']['runtimeJars'][engine] if Path(x['path']).name.startswith('thc-')]
  require(len(jar)==1 and jar[0]['sha256']==want,'Wrong runtime JAR')
 for module in c['provenance']['modules']:require(modulehashes['combined-runtime-v1'][Path(module['path']).name]==module['sha256'],'Wrong Core input')
 cycle=sum(int(x.split('\t')[2]) for x in (b/'oracle.tsv').read_text().splitlines());rows=[]
 for spec in c['commands']:
  e=spec['engine'];f=spec['fork'];name=f'{e}-{f}';windows=h['read_windows'](b/(name+'.tsv'),10000,cycle)
  if e!='native':
   a=h['validate_jvm_log'](b/(name+'.log'),cycle,'bytecode','on',30000,45);require(a['diagnostics']['instrumented'] is False,'Instrumented timing')
   args=spec['argv'];require(all(x in args for x in ['-Xms4g','-Xmx4g','-XX:+UseCompactObjectHeaders','-Dpolyglot.compiler.InliningRecursionDepth=2','-Dpolyglot.compiler.InliningExpansionBudget=12000','-Dpolyglot.compiler.InliningInliningBudget=12000']),'Common flags changed')
   for key in keys:
    value=(e=='candidate') if label=='v7-vs-all-on' else ((e=='baseline' or key!='boxedValueCache') if label=='combined-cache' else (key=='classOwnedLayouts' or (e=='candidate' and key!='boxedValueCache')))
    require('-Dthc.'+key+'='+str(value).lower() in args,'Flag vector differs')
  rows += [dict(engine=e,fork=f,**x) for x in windows]
 for e in ['baseline','candidate','native']:require(h['engine_summary']([x for x in rows if x['engine']==e])==s['engines'][e],'Medians differ')
 require(s['windowCount']==len(rows)==45,'Wrong window count');total+=len(rows)
 print(label+':45 windows verified; candidate/baseline='+str(s['ratios']['candidate/baseline'])+'; candidate/native='+str(s['ratios']['candidate/native']))
print(f'PASS:{len(manifest["files"])} file hashes;4×18 pre/postcompile rows;{total} raw timing windows;exact runtime/Core/native/flags;zero measured/final Truffle events.')
