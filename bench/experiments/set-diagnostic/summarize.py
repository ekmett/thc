#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Fail closed on incomplete diagnostic runs and write compact reproducible evidence."""
import collections,hashlib,json,pathlib,sys
out=pathlib.Path(sys.argv[1])
inventory=json.loads((out/'inventory.json').read_text())
audit=json.loads((out/'current-audit.json').read_text())
assert not audit['accepted']
issue_counts=dict(sorted(collections.Counter(x['code'] for x in audit['issues']).items()))
assert issue_counts.get('aggregate-boundary',0)==0,issue_counts
backends={}
for backend in ('ast','bytecode'):
    path=out/f'{backend}.log';lines=path.read_text().splitlines()
    def item(prefix):
        found=[s[len(prefix):] for s in lines if s.startswith(prefix)]
        assert len(found)==1,(prefix,len(found))
        return found[0]
    final=json.loads(item('FINAL_DIAGNOSTICS='))
    assert final['unsupportedPolicy']=='diagnostic-traps'
    assert final['unsupportedTraps']==final['blackholes']==0
    assert final['localJoinTransfers']>0 and final['compiledEntries']>0
    assert final['deferredUnsupported']
    assert item('PASS_DIAGNOSTIC_SET ')==f'backend={backend} nativeRows=22 warmValid=true postColdValid=true unsupportedTraps=0 strictSupported=false'
    phases=collections.defaultdict(list)
    for s in lines:
        if s.startswith('VERIFIED_DIAGNOSTIC_SET\t'):
            _,phase,n,value=s.split('\t');phases[phase].append([int(n),int(value)])
    assert {k:len(v) for k,v in phases.items()}=={'interpreted':22,'compiled-warm':3,'after-compilation-cold':19,'train-all':44,'post-cold-compiled':22}
    assert phases['interpreted']==phases['post-cold-compiled']
    assert phases['train-all']==phases['interpreted']*2
    calls=json.loads(item('INTERPRETED_CALLED_EXPORTED_TARGETS='))
    calls={c['id']:c['calls'] for c in calls}
    required={k:calls[k] for k in ('main:Data.Set.Internal.$wgo','main:Data.Set.Internal.$wgo1','main:Data.Set.Internal.glue')}
    assert all(n>0 for n in required.values())
    backends[backend]={'strictRejection':item(f'EXPECTED_STRICT_REJECTION\t{backend}\t'),
        'phaseRows':{k:len(v) for k,v in phases.items()},'nativeRows':phases['interpreted'],
        'warmEntryValid':True,'postColdEntryValid':True,'actualExportedHelperCalls':required,
        'finalDiagnostics':final,'validationToolDrift':json.loads(item('REUSED_EXPORTS_CURRENT_VALIDATION_TOOL_DRIFT=')),
        'logSha256':hashlib.sha256(path.read_bytes()).hexdigest()}
assert backends['ast']['nativeRows']==backends['bytecode']['nativeRows']
result={'status':'diagnostic-execution-only','strictSupported':False,'inventory':inventory,
        'currentIssueCounts':issue_counts,'missingGlobals':audit['missingGlobals'],
        'inputs':json.loads((out/'run-inputs.json').read_text()),
        'javaVersion':(out/'java-version.txt').read_text(),'backends':backends}
(out/'evidence.json').write_text(json.dumps(result,indent=2)+'\n')
print('PASS diagnostic Set: 22 native rows per backend, compiled entries valid, zero traps; strict support remains false')
