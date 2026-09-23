#!/usr/bin/env python3
from pathlib import Path
import hashlib,json
R=Path(__file__).resolve().parents[1]
def read(p):return json.loads(p.read_text())
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def need(ok,msg):
 if not ok:raise ValueError(msg)
for f in read(R/'manifest.json')['files']:
 p=R/f['path'];need(p.stat().st_size==f['bytes'] and sha(p)==f['sha256'],'Changed publication file: '+str(p))
v=read(R/'validation.json');need(v['passed'] and v['status']=='complete' and v['allInputsUnchanged'],'Incomplete smoke')
local=read(R/'local-proof.json');jar='5af987892c35fedc0f170d245574ad704609e9747c7993f970991191d71a773c';need(local['releaseJar']['sha256']==jar,'Wrong release JAR')
inputs=read(R/'input-manifest.json')['files'];need([x['sha256'] for x in inputs if x['path'].endswith('/thc-0.1-experiment.jar')]==[jar],'Staged JAR mismatch')
actual={Path(x['path']).name:x['sha256'] for x in inputs if x['path'].endswith('.jar') and not x['path'].endswith('/thc-0.1-experiment.jar')}
need(actual=={x['name']:x['sha256'] for x in local['dependencyJars']},'Dependency mismatch')
core=read(R/'provenance/call-demand-structural-proof.json');need(core['passed'],'Missing Core proof')
actual={Path(x['path']).name:x['sha256'] for x in inputs if '/map/' in x['path'] and x['path'].endswith('.json')}
need(actual=={x['file']:x['on'] for x in core['modules']} and len(actual)==17,'Core changed')
need([x['sha256'] for x in inputs if x['path'].endswith('/native-oracle')]==['2657dd2c89fa63d94c68dc574a9f90128c2822f15a088c57fa3114f308685dec'],'Native binary mismatch')
oracle={int(x[1]):int(x[2]) for x in [l.split('\t') for l in (R/'linux-oracle.tsv').read_text().splitlines()]};need(len(oracle)==18,'Oracle incomplete')
need(next(x['sha256'] for x in inputs if x['path'].endswith('/linux-oracle.tsv'))==sha(R/'linux-oracle.tsv'),'Oracle hash mismatch')
seen=set();flags=['classOwnedLayouts','typedCases','leadingCaseReturn','constructorClassIdentity','staticShapeUnchecked','boxedValueCache']
for check in v['checks']:
 backend=check['backend'];on=check['callDemands'] is True;label=backend+'-'+('enabled' if on else 'property-absent');need(label not in seen,'Repeated check');seen.add(label)
 cmd=read(R/label/'command.json');need(cmd==check and cmd['returncode']==0,'Command/state mismatch')
 args=cmd['argv'];need(args[:3]==['taskset','-c','0-15'],'Wrong affinity')
 need('-Dpolyglot.compiler.InliningPolicy=Default' in args and '-Dthc.backend='+backend in args,'Wrong policy/backend')
 need([x for x in args if x.startswith('-Dthc.callDemands')]==(['-Dthc.callDemands=true'] if on else []),'Caller property not absent/true as requested')
 for f in flags:need('-Dthc.'+f+'='+str(f=='classOwnedLayouts').lower() in args,'Unexpected feature flags')
 lines=(R/label/'stdout.log').read_text().splitlines();rows=[]
 for phase in ['before-requested-compilation','after-requested-compilation']:
  phase_rows=[x.split('\t') for x in lines if x.startswith('VERIFIED_MAP\t'+phase+'\t')]
  need(len(phase_rows)==18 and {int(x[2]):int(x[3]) for x in phase_rows}==oracle,'Native preflight mismatch')
  rows+=phase_rows
 d=json.loads(next(x.removeprefix('MAP_DIAGNOSTICS ') for x in lines if x.startswith('MAP_DIAGNOSTICS ')));need(d==check['diagnostics'],'Diagnostic mismatch')
 need(d['backend']==backend and d['instrumented'] is True and d['unsupportedPolicy']=='diagnostic-traps' and d['unsupportedTraps']==0 and d['sourceNotesEnabled'] is True and d['compiledEntries']>0,'Bad diagnostics')
 need(sum(d['thunkEvaluationsByLabel'].values())==d['thunkEvaluations'],'Inconsistent thunk counters')
 if on:need(d['thunkEvaluationsByLabel']=={'lvl':3},'Enabled demand failed to remove argument thunks')
 if backend=='bytecode' and not on:need(d['thunkEvaluationsByLabel']=={'lvl':3,'argument thunk':16408},'Default did not preserve control behavior')
need(seen=={'ast-property-absent','ast-enabled','bytecode-property-absent','bytecode-enabled'},'Incomplete four-way matrix')
tests=read(R/'provenance/local-test-inventory.json');need(tests['totals']=={'tests':177,'failures':0,'errors':0,'skipped':0},'Local test inventory changed')
print('PASS: release/dependency/Core/native hashes, four exact command configurations, all144 native-oracle phase rows, zero traps, observed counters, and local177-test inventory.')
