#!/usr/bin/env python3
"""Capture one actual empty-input production root; fixed correctness rows, no timing."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

ROOT=Path(__file__).resolve().parents[3]
HERE=Path(__file__).resolve().parent
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('module',type=Path)
p.add_argument('oracle',type=Path)
p.add_argument('entry')
p.add_argument('backend',choices=['ast','bytecode'])
p.add_argument('mode',choices=['inline','residual'])
p.add_argument('output',type=Path)
p.add_argument('--handoff',choices=['false','true'],default='false')
a=p.parse_args()
out=a.output.resolve();out.mkdir(parents=True,exist_ok=False)
a.module=a.module.resolve();a.oracle=a.oracle.resolve()
java=Path(os.environ['JAVA_HOME']).resolve()
classes=out/'classes';classes.mkdir()
def digest(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def record(path):return {'path':str(path),'sha256':digest(path)}
proof_path=ROOT/'build/empty-tuple-input/provenance.json'
checks_path=ROOT/'build/empty-tuple-input/checks.json'
proof=json.loads(proof_path.read_text());checks=json.loads(checks_path.read_text())
assert digest(proof_path)==checks['provenance']['sha256']
for item in proof['sources']+proof['artifacts']+list(proof['toolchain'][k] for k in ('ghc','ghcPkg')):
 assert digest(ROOT/item['path'])==item['sha256'],'Stale native provenance: '+item['path']
artifacts={(ROOT/item['path']).resolve() for item in proof['artifacts']}
assert a.module in artifacts and a.oracle in artifacts,'Inputs must belong to the checked native fixture'
for stage in ('pre','post'):
 assert json.loads((ROOT/f'build/empty-tuple-input/{stage}-audit.json').read_text())['accepted'] is True

def snapshot():
 sources=[p for p in sorted((ROOT/'src/main').rglob('*')) if p.is_file()]
 sources += [ROOT/'build.gradle.kts',ROOT/'tools/GraphInspect.java',HERE/'EmptyInputGraphProbe.java',Path(__file__).resolve()]
 jars=sorted((ROOT/'build/install/thc/lib').glob('*.jar'))
 assert jars,'Build installDist from the reviewed immutable runtime before capture'
 return {'sources':[record(p) for p in sources], 'runtimeJars':[record(p) for p in jars],
         'module':record(a.module),'oracle':record(a.oracle),'nativeProvenance':record(proof_path),'nativeChecks':record(checks_path),'jdkRelease':record(java/'release')}
initial=snapshot()
(out/'launch.json').write_text(json.dumps({'sourceCommit':subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),**initial},indent=2)+'\n')
cp=str(classes)+':'+str(ROOT/'build/install/thc/lib/*')
exports=['--add-modules','jdk.graal.compiler','--add-exports','jdk.graal.compiler/jdk.graal.compiler.graphio.parsing=ALL-UNNAMED',
         '--add-exports','jdk.graal.compiler/jdk.graal.compiler.graphio.parsing.model=ALL-UNNAMED']
commands=[]
def run(argv,stdout=None):
 commands.append([str(x) for x in argv]);subprocess.run(argv,cwd=ROOT,stdout=stdout,stderr=subprocess.STDOUT if stdout else None,check=True)
run([java/'bin/javac','-cp',cp,'-d',classes,HERE/'EmptyInputGraphProbe.java'])
run([java/'bin/javac',*exports,'-d',classes,ROOT/'tools/GraphInspect.java'])
with (out/'run.log').open('w') as log:
 run([java/'bin/java','--add-modules=jdk.incubator.vector','--enable-native-access=ALL-UNNAMED','-XX:+UseCompactObjectHeaders',
      '-Dthc.handoffSlabs='+a.handoff,'-Djdk.graal.Dump=Truffle:2','-Djdk.graal.PrintGraph=File','-Djdk.graal.PrintGraphWithSchedule=true','-Djdk.graal.PrintBackendCFG=true',
      '-Djdk.graal.DumpPath='+str(out/'graphs'),'-cp',cp,'EmptyInputGraphProbe',a.module,a.oracle,a.entry,a.backend,a.mode],log)
log=(out/'run.log').read_text()
assert f'PASS entry={a.entry} backend={a.backend} mode={a.mode}' in log and 'validAfterEveryRow=true' in log
roots=[json.loads(x.removeprefix('GRAPH_TARGET=')) for x in log.splitlines() if x.startswith('GRAPH_TARGET=')]
assert len(roots)==1
# Background/threshold compilation is disabled and the selected active entry is compiled last.
graphs=list((out/'graphs').glob('*.bgv'));assert graphs
selected=max(graphs,key=lambda p:int(re.search(r'TruffleHotSpotCompilation-(\d+)',p.name).group(1)))
run([java/'bin/java','-XX:-UseJVMCICompiler',*exports,'-cp',str(classes),'GraphInspect',selected,out/'parsed',
     '(Before phase HighTierLowering|After low tier)'])
assert snapshot()==initial,'Source, runtime, inputs or JDK changed during capture'
index=json.loads((out/'parsed/index.json').read_text())
phases=[g for g in index['graphs'] if 'file' in g]
assert len(phases)==2
assert all(g['group']=='TruffleIR.Tier2.'+roots[0]['root'].replace(' ','_')+'()' for g in phases),phases
phases=[{k:g[k] for k in ('file','name','group','nodes','edges','nodeClassCounts')} for g in phases]
cfg=selected.with_suffix('.cfg');text=cfg.read_text();marker='  name "After FinalCodeAnalysisStage"'
start=text.rindex(marker);end=text.index('end_cfg',start)+len('end_cfg')
(out/'final-lir.txt').write_text('\n'.join(line.rstrip() for line in text[start:end].splitlines())+'\n')
(out/'capture.json').write_text(json.dumps({'entry':a.entry,'backend':a.backend,'mode':a.mode,'handoff':a.handoff,'target':roots[0],
    'graph':record(selected),'cfg':record(cfg),'log':record(out/'run.log'),'lir':record(out/'final-lir.txt'),
    'phases':phases,'commands':commands,'claim':'Correct native rows and active installed entry remains valid after each row; graph shape requires separate review.'},indent=2)+'\n')
print('Captured',a.entry,a.backend,a.mode,selected.name)
