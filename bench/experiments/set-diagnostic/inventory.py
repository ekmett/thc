#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Reaudit existing Set exports without regenerating them or changing their native oracle."""
import hashlib,importlib.util,json,pathlib,sys
cases_path,out=map(pathlib.Path,sys.argv[1:]); out.mkdir(parents=True,exist_ok=True)
root=pathlib.Path(__file__).resolve().parents[3]
cases=json.loads(cases_path.read_text());group=next(g for g in cases['groups'] if g['id']=='set')
for name,expected in cases['artifactHashes'].items():
    assert hashlib.sha256(pathlib.Path(name).read_bytes()).hexdigest()==expected,name
modules=[(p,json.loads(pathlib.Path(p).read_text())) for p in group['modules']]
spec=importlib.util.spec_from_file_location('set_current_core_audit',root/'scripts/audit-core.py')
audit=importlib.util.module_from_spec(spec);spec.loader.exec_module(audit)
report=audit.Audit(modules,json.loads((root/'scripts/core-capabilities.json').read_text())).run(['setAggregate'])
(out/'current-audit.json').write_text(json.dumps(report,indent=2)+'\n')
reachable={b['id'] for b in report['reachableBindings']}; tuples=[]
def values(x):
    yield x
    if isinstance(x,dict):
        for v in x.values():yield from values(v)
    elif isinstance(x,list):
        for v in x:yield from values(v)
for path,module in modules:
    for binding in module['bindings']:
        for local in values(binding.get('expr')):
            if not isinstance(local,dict) or local.get('info',{}).get('joinArity') is None:continue
            expr=local.get('expr',[])
            result=expr[-1].get('resultRep',{}) if expr and isinstance(expr[-1],dict) else {}
            if result.get('aggregate')!='unboxed-tuple':continue
            notes=[s for s in values(binding['expr']) if isinstance(s,str) and any(n in s for n in ('minViewSure','maxViewSure'))]
            tuples.append({'owner':binding['id'],'join':local['id'],'arity':local['info']['joinArity'],
                'primReps':result['primReps'],'reachableFromSetAggregate':binding['id'] in reachable,
                'module':path,'sourceNote':notes[0] if notes else binding.get('source')})
selected=[j for j in tuples if j['reachableFromSetAggregate']]
assert len(selected)==2 and {j['owner'] for j in selected}=={'main:Data.Set.Internal.$wgo','main:Data.Set.Internal.$wgo1'},selected
assert not report['accepted'],'Strict Set unexpectedly accepted; review changed frontier'
used=[cases_path,*map(pathlib.Path,group['modules']),cases_path.parent/'oracle.tsv',root/'scripts/audit-core.py',root/'scripts/core-capabilities.json']
result={'root':'main:THC.SetWorkload.setAggregate','strictAccepted':False,'currentAuditSummary':report['summary'],
        'fullModuleTupleJoinCount':len(tuples),'reachableTupleJoinCount':len(selected),'tupleJoins':tuples,
        'sha256':{str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in used}}
(out/'inventory.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps({'fullModuleTupleJoins':len(tuples),'reachableTupleJoins':len(selected),'strictAccepted':False}))
