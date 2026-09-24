#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Inspect real production graphs. Long host-result boxing is reported, not hidden."""
import hashlib,json,pathlib,re,sys
out=pathlib.Path(sys.argv[1]); mode=sys.argv[2]
logs=(out/'run.log').read_text(); assert 'validAfterEveryRow=true' in logs,logs[-2000:]
indexes=list(out.glob('parsed-*/index.json'))
assert indexes,'No parsed graph'
# Background and threshold compilation are disabled by this harness; residual
# callees compile first, then the selected scalar entry is compiled last.
def compilation_id(p): return int(re.search(r'TruffleHotSpotCompilation-(\d+)',str(p))[1])
p=max(indexes,key=compilation_id); index=json.loads(p.read_text()); phases={}
observed=json.loads(next(line.split('=',1)[1] for line in logs.splitlines() if line.startswith('GRAPH_TARGET=')))
assert observed['root'] in str(index), 'Graph does not identify the active guest entry'
for suffix in ('Before phase HighTierLowering','After low tier'):
    candidates=[g for g in index['graphs'] if g['name'].endswith(suffix)]
    assert len(candidates)==1,(suffix,len(candidates))
    g=candidates[0]; full=json.loads((p.parent/g['file']).read_text())
    counts={k.rsplit('.',1)[-1]:v for k,v in g['nodeClassCounts'].items()}
    memory=[]; calls=[]; boxes=[]
    for n in full['nodes']:
        kind=n['nodeClass'].rsplit('.',1)[-1]; props=n['properties']
        if any(t in kind for t in ('ReadNode','WriteNode','LoadFieldNode','StoreFieldNode')):
            memory.append({'kind':kind,'location':props.get('location'),'field':props.get('field')})
        if 'Invoke' in kind: calls.append(props.get('targetMethod'))
        if 'Alloc' in kind or 'NewInstance' in kind or 'NewArray' in kind:
            boxes.append({'kind':kind,'type':props.get('type'),'stamp':props.get('stamp')})
    if mode=='inline':
        assert all(k not in counts for k in ('CommitAllocationNode','AllocatedObjectNode','NewInstanceNode','NewArrayNode','StoreFieldNode','StoreIndexedNode','EnsureVirtualizedNode')),counts
        assert all(call=='Direct#HotSpotThreadLocalHandshake.doHandshake' for call in calls),calls
        allowed={'Array: Object','java.lang.Long.value','JavaThread::_jvmci_reserved0','TlabTop','TlabEnd','INIT_LOCATION','MarkWord'}
        assert all(m['location'] in allowed for m in memory),memory
        if suffix.startswith('Before'):
            allocations=[n for n in full['nodes'] if n['nodeClass'].endswith('BoxNode$AllocatingBoxNode')]
            assert 1 <= len(allocations) <= counts.get('ReturnNode',0), counts
            nodes={n['id']:n for n in full['nodes']}
            for box in allocations:
                assert box['properties']['stamp']=='a!# java.lang.Long', box
                uses=[e for e in full['edges'] if e['from']==box['id'] and e['type']=='Value']
                assert uses and all(e['label']=='result' and nodes[e['to']]['nodeClass'].endswith('.ReturnNode') for e in uses),uses
    if mode=='residual' and suffix=='After low tier':
        assert 'Direct#OptimizedCallTarget.callBoundary' in calls,calls
        assert any('handoff__' in str(m['location']) for m in memory),memory
    phases[suffix]={'nodes':g['nodes'],'counts':counts,'memoryOperations':memory,'invokes':calls,'allocationRelatedNodes':boxes}
source=pathlib.Path(index['source']); cfg=source.with_suffix('.cfg').read_text()
marker='  name "After FinalCodeAnalysisStage"'; assert marker in cfg
lir=cfg[cfg.index(marker):].split('\nbegin_cfg',1)[0]
instructions=[line.strip() for line in lir.splitlines() if 'instruction ' in line]
assert any('RETURN' in s and 'additionalReturns: []' in s for s in instructions)
(out/'final-lir.txt').write_text('\n'.join(instructions)+'\n')
evidence={'scope':'Actual exported Haskell through production THC runtime; fixed inputs, no timing',
          'checks':[x for x in logs.splitlines() if x.startswith('PASS')], 'mode':mode,
          'entryGraph':source.name,'bgvSha256':hashlib.sha256(source.read_bytes()).hexdigest(),
          'cfgSha256':hashlib.sha256(source.with_suffix('.cfg').read_bytes()).hexdigest(),
          'phases':phases,'limitation':'Final scalar Long host-result box remains; Object call ABI has no additional returns'}
(out/'graph-evidence.json').write_text(json.dumps(evidence,indent=2)+'\n')
print('PASS actual runtime graph:',out, {k:v['nodes'] for k,v in phases.items()})
