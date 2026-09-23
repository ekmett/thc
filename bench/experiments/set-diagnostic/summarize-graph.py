#!/usr/bin/env python3
"""Attribute tuple field traffic in the actual minViewSure caller graph."""
import collections,hashlib,json,pathlib,re,sys
out=pathlib.Path(sys.argv[1])
indices=list(out.glob('parsed*/index.json'));assert len(indices)==1,indices
index=json.loads(indices[0].read_text());base=indices[0].parent
pre=next(g for g in reversed(index['graphs']) if 'Before phase HighTierLowering' in g['name'])
low=next(g for g in reversed(index['graphs']) if 'After low tier' in g['name'])
def read(g):return json.loads((base/g['file']).read_text())
p,l=read(pre),read(low);nodes={n['id']:n for n in p['nodes']}
def methods(n):
    return [re.sub(r'\(truffle:[^)]*\)','',s).split(' [bci:')[0]
            for s in n['properties'].get('nodeSourcePosition','').splitlines()]
accesses=[];carrier_classes=set()
for n in p['nodes']:
    field=n['properties'].get('field','')
    if not isinstance(field,str) or '.handoff__' not in field:continue
    carrier_classes.add(field.rsplit('.',1)[0]);stack=methods(n)
    if any('TupleShape.finish(' in m for m in stack):kind='outer-root-completion'
    elif any('TupleShape.consume(' in m for m in stack):kind='residual-callee-consumption'
    else:raise AssertionError(('Unattributed tuple field',n['id'],stack))
    value_edges=[e for e in p['edges'] if e['to']==n['id'] and e['label']=='value']
    values=[{'node':e['from'],'class':nodes[e['from']]['nodeClass'],'stamp':nodes[e['from']]['properties'].get('stamp')} for e in value_edges]
    accesses.append({'node':n['id'],'class':n['nodeClass'],'field':field,'purpose':kind,
                     'valueInputs':values,'methods':stack[:10]})
allocations=collections.Counter(n['properties'].get('stamp') for n in p['nodes'] if n['nodeClass'].endswith('AllocatedObjectNode'))
assert not any(c in stamp for stamp in allocations for c in carrier_classes)
assert not any('LocalJoin' in stamp for stamp in allocations)
calls=collections.Counter(n['properties'].get('targetMethod') for n in p['nodes'] if n['nodeClass'].endswith('MethodCallTargetNode'))
assert not any('LocalJoin' in m for m in calls)
physical_frame_accesses=[n['id'] for n in l['nodes'] if n['nodeClass'].endswith(('ReadNode','WriteNode'))
                        and any(s in str(n['properties'].get('location','')) for s in ('FrameWithoutBoxing','VirtualFrame'))]
assert not physical_frame_accesses
completion=[a for a in accesses if a['purpose']=='outer-root-completion']
assert len(completion)==2 and all(len(a['valueInputs'])==1 and a['valueInputs'][0]['class'].endswith('ValuePhiNode') for a in completion)
assert len({a['valueInputs'][0]['node'] for a in completion})==2
guards=collections.Counter(n['properties']['location'] for n in l['nodes'] if n['nodeClass'].endswith('ReadNode')
                           and 'thc.runtime.LocalJoin' in str(n['properties'].get('location','')))
log=(out/'run.log').read_text()
assert 'PASS_DIAGNOSTIC_SET_JOIN_GRAPH backend=ast nativeRows=22 helper=main:Data.Set.Internal.$wgo1 valid=true unsupportedTraps=0 strictSupported=false' in log
execution=out/'run.log' if 'GRAPH_COMPILED_ENTRIES=' in log else out/'execution-check.log'
entered=re.findall(r'^GRAPH_COMPILED_ENTRIES=(\d+)$',execution.read_text(),re.M)
assert len(entered)==1 and int(entered[0])>0,'No evidence of executing the compiled helper'
files=[out/'run.log',*sorted((out/'graphs').glob('*'))]
result={'backend':'ast','entry':'main:Data.Set.Internal.$wgo1','source':'minViewSure.go',
        'diagnosticOnly':True,'nativeRows':22,'compiledHelperValidAfterReplay':True,
        'compiledEntryEvidence':{'entries':int(entered[0]),'log':str(execution),'sha256':hashlib.sha256(execution.read_bytes()).hexdigest()},
        'phases':[{'name':g['name'],'nodes':g['nodes'],'nodeClassCounts':g['nodeClassCounts']} for g in (pre,low)],
        'allocatedObjectsByStamp':dict(allocations),'callTargets':dict(calls),'tupleFieldAccesses':accesses,
        'physicalVirtualFrameSlotAccesses':physical_frame_accesses,'remainingLocalJoinGuardReads':dict(guards),
        'sha256':{str(f):hashlib.sha256(f.read_bytes()).hexdigest() for f in files}}
if (out/'run-inputs.json').exists():result['inputs']=json.loads((out/'run-inputs.json').read_text())
if (out/'execution-check-inputs.json').exists():result['compiledEntryEvidence']['inputs']=json.loads((out/'execution-check-inputs.json').read_text())
(out/'graph-evidence.json').write_text(json.dumps(result,indent=2)+'\n')
print('PASS minViewSure graph: scalar SSA join results, outer completion and residual consumption traffic retained')
