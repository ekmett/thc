#!/usr/bin/env python3
"""Verify the recorded local one-option comparisons without Java or original work paths."""
import hashlib,importlib.util,json,statistics,sys
from pathlib import Path
import xml.etree.ElementTree as ET
sys.dont_write_bytecode=True
BASE=Path(__file__).resolve().parent
JAR='a73ec0e731b96b63523933cafc876b5e1c64884e24a42ded1b504b60d5bb0d7d'
def load(path):return json.loads(path.read_text())
def digest(path):return hashlib.sha256(path.read_bytes()).hexdigest()
m=load(BASE/'manifest.json')
for row in m['files']:
 p=BASE/row['path'];assert p.stat().st_size==row['bytes'] and digest(p)==row['sha256'],str(p)
actual={str(p.relative_to(BASE)) for p in BASE.rglob('*') if p.is_file() and p!=BASE/'manifest.json' and '__pycache__' not in p.parts}
assert actual=={r['path'] for r in m['files']}
parent=load(BASE/'validation.json');assert parent['fullMapConfigurations']==4 and parent['beforeRows']==parent['afterRows']==18 and parent['frozenHashesUnchanged']
spec=importlib.util.spec_from_file_location('recorded_harness',BASE/'tools/compare-map-runtimes.py');h=importlib.util.module_from_spec(spec);spec.loader.exec_module(h)
windows=0
for name,option in [('class','-Dthc.constructorClassIdentity='),('compact','-XX:')]:
 folder=BASE/name;c=load(folder/'run-config.json');s=load(folder/'summary.json');v=load(folder/'validation.json')
 assert v['passed'] and v['inputsUnchanged'] and v['validatedWindows']==45
 assert c['forks']==3 and c['samples']==5 and c['sampleSeconds']==2 and c['jvmWarmSeconds']==15 and c['minimumJvmWarmCalls']==12000
 assert c['backends']=={'baseline':'bytecode','candidate':'bytecode'} and c['sourceNotesModes']=={'baseline':'on','candidate':'on'}
 assert c['provenance']['runtimeJars']['baseline']==c['provenance']['runtimeJars']['candidate'] and c['moduleManifests']['baseline']==c['moduleManifests']['candidate']
 assert next(x['sha256'] for x in c['provenance']['runtimeJars']['baseline'] if x['path'].endswith('/thc-0.1-experiment.jar'))==JAR
 assert c['provenance']['harness'][0]['sha256']==digest(BASE/'tools/compare-map-runtimes.py')
 cycle=c['oracle']['cycleChecksumSigned64'];assert cycle==h.signed64(sum(c['oracle']['values']))
 assert len(c['commands'])==9 and {(a['engine'],a['fork']) for a in c['commands']}=={(e,f) for e in ['baseline','candidate','native'] for f in range(1,4)}
 for engine in ['baseline','candidate','native']:
  rows=[]
  for fork in range(1,4):
   rs=h.read_windows(folder/f'{engine}-{fork}.tsv',c['inputBase'],cycle)
   for r in rs:r['fork']=fork
   rows.extend(rs);windows+=len(rs)
   if engine!='native':
    h.validate_jvm_log(folder/f'{engine}-{fork}.log',cycle,'bytecode','on')
    cmd=next(x['argv'] for x in c['commands'] if x['engine']==engine and x['fork']==fork)
    def exact(prefix,value):assert [arg for arg in cmd if arg.startswith(prefix)]==[value],(name,engine,fork,prefix)
    exact('-Dthc.boxedValueCache=','-Dthc.boxedValueCache=false');exact('-Dthc.staticShapeUnchecked=','-Dthc.staticShapeUnchecked=false')
    enabled=engine=='candidate'
    exact('-Dthc.constructorClassIdentity=','-Dthc.constructorClassIdentity='+('true' if name=='class' and enabled else 'false'))
    assert [arg for arg in cmd if arg in ['-XX:+UseCompactObjectHeaders','-XX:-UseCompactObjectHeaders']]==['-XX:+UseCompactObjectHeaders' if name=='compact' and enabled else '-XX:-UseCompactObjectHeaders']
    for flag in ['-Dpolyglot.compiler.InliningRecursionDepth=2','-Dpolyglot.compiler.InliningExpansionBudget=12000','-Dpolyglot.compiler.InliningInliningBudget=12000']:assert flag in cmd
  assert h.engine_summary(rows)==s['engines'][engine]
 assert s['windowCount']==45 and s['ratios']['candidate/baseline']==s['engines']['candidate']['medianNsPerCall']/s['engines']['baseline']['medianNsPerCall']
 assert s['ratios']['candidate/native']==s['engines']['candidate']['medianNsPerCall']/s['engines']['native']['medianNsPerCall']
 for fork in range(1,4):
  pair=[next(x['argv'] for x in c['commands'] if x['engine']==e and x['fork']==fork) for e in ['baseline','candidate']]
  strip=lambda cmd:[a for a in cmd if not (a.startswith('-Dthc.constructorClassIdentity=') if name=='class' else a in ['-XX:+UseCompactObjectHeaders','-XX:-UseCompactObjectHeaders'])]
  assert strip(pair[0])==strip(pair[1]),(name,fork)
 power=load(folder/'power-status.json');assert len(power['samples'])==18 and power['warnings']==s['powerWarnings']
input_hashes=load(BASE/'map/input-hashes.json')
assert next(v for k,v in input_hashes.items() if k.endswith('/map/oracle.tsv'))==digest(BASE/'map/oracle.tsv')
assert next(v for k,v in input_hashes.items() if k.endswith('/lib/thc-0.1-experiment.jar'))==JAR
checks=load(BASE/'map/checks.json');assert [x['name'] for x in checks]==['default','cache','class','compact']
oracle=[x.split('\t')[1:] for x in (BASE/'map/oracle.tsv').read_text().splitlines()];assert len(oracle)==18
for check in checks:
 assert check['passed'] and check['returncode']==0 and check['beforeRows']==check['afterRows']==18
 lines=(BASE/'map'/f'{check["name"]}.log').read_text().splitlines()
 for phase in ['before-requested-compilation','after-requested-compilation']:
  actual=[s.split('\t')[2:] for s in lines if s.startswith('VERIFIED_MAP\t'+phase+'\t')];assert actual==oracle
 ds=[json.loads(s.removeprefix('MAP_DIAGNOSTICS ')) for s in lines if s.startswith('MAP_DIAGNOSTICS ')];assert ds==[check['diagnostics']]
 assert ds[0]['unsupportedTraps']==0 and ds[0]['compiledEntries']>0
 cmd=check['argv'];assert '-Dthc.boxedValueCache='+('true' if check['name']=='cache' else 'false') in cmd
 assert '-Dthc.constructorClassIdentity='+('true' if check['name']=='class' else 'false') in cmd
 assert '-Dthc.staticShapeUnchecked=false' in cmd
 assert ('-XX:+UseCompactObjectHeaders' if check['name']=='compact' else '-XX:-UseCompactObjectHeaders') in cmd
refs=load(BASE/'referenced-test-evidence.json');totals=dict.fromkeys(['tests','failures','errors','skipped'],0)
for item in refs['files']:
 p=(BASE/item['path']).resolve();assert p.stat().st_size==item['bytes'] and digest(p)==item['sha256']
 if p.suffix=='.xml':
  result=ET.parse(p).getroot()
  for key in totals:totals[key]+=int(result.get(key,0))
assert totals==dict(tests=153,failures=0,errors=0,skipped=0)
compact=load((BASE/refs['validation']).resolve());assert compact['javaToolOptions']==['-XX:+UseCompactObjectHeaders'];assert compact['sameAsFrozenV7'] and compact['sourceMainMatchesFrozenV7'] and compact['runtimeJarSha256']==JAR and compact['totals']==totals
assert windows==90
print(f'PASS: {len(m["files"])} local artifact hashes; 90 windows; twelve last-tier/no-event JVM logs; two same-JAR one-option comparisons; four 18-input pre/post Map checks; referenced 153 compact-header tests.')
