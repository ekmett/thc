#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Fail-closed graph gate for actual exported DoubleX2 Core and native input rows."""
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys

mode, root, out = sys.argv[1], Path(sys.argv[2]), Path(sys.argv[3])
core_dir = root / 'build/simd-doublex2'
entries = {'plusCase': 'ADD', 'minusCase': 'SUB', 'timesCase': 'MUL'}

def digest(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def record(path): return dict(path=str(path), sha256=digest(path))
def snapshot():
    sources = [root/'build.gradle.kts', root/'tools/GraphInspect.java']
    sources += [p for p in sorted((root/'src/main').rglob('*')) if p.is_file()]
    sources += [p for p in sorted((root/'bench/experiments/doublex2-foundation').iterdir()) if p.suffix in ('.py', '.sh', '.java')]
    jars = sorted((root/'build/install/thc/lib').glob('*.jar'))
    assert jars, 'Build installDist first'
    return dict(sourceRevision=subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip(),
        sources=[record(p) for p in sources], runtimeJars=[record(p) for p in jars])

if mode == 'prepare':
    assert not list(out.glob('*/graphs/*')), 'Use a fresh graph output directory'
    proof = json.loads((core_dir/'provenance.json').read_text())
    assert proof['vector'] == 'doublex2' and proof['positiveAuditsAccepted'] is True
    assert proof['nativeRows'] and proof['modelMatched'] is True, 'Actual native oracle required; export-only is not native evidence'
    for item in proof['sources'] + proof['artifacts']:
        assert digest(root/item['path']) == item['sha256'], 'Stale input: '+item['path']
    cases = {e['name']: (e['arity'], len(e['cases'])) for e in proof['entries']}
    assert all(cases[e][0] == 2 and cases[e][1] > 0 for e in entries)
    (out/'input-provenance.json').write_text(json.dumps(dict(core=proof,
        originalProvenance=record(core_dir/'provenance.json'), cases=cases), indent=2)+'\n')
    (out/'runtime-snapshot.json').write_text(json.dumps(snapshot(), indent=2)+'\n')
    (out/'stages.txt').write_text(''.join(s+'\n' for s in proof['stages']))
    shutil.copyfile(core_dir/'oracle.tsv', out/'oracle.tsv')
    sys.exit(0)

assert mode == 'check'
initial = json.loads((out/'runtime-snapshot.json').read_text())
current = snapshot()
# Keep the historical launch revision; a later evidence-only commit does not change code.
assert all(current[key] == initial[key] for key in ('sources', 'runtimeJars')), 'Runtime source/JAR changed during capture'
inputs = json.loads((out/'input-provenance.json').read_text())
assert digest(Path(inputs['originalProvenance']['path'])) == inputs['originalProvenance']['sha256'], 'Core provenance changed during capture'
for item in inputs['core']['sources'] + inputs['core']['artifacts']:
    assert digest(root/item['path']) == item['sha256'], 'Core source/artifact changed during capture: '+item['path']
stages = (out/'stages.txt').read_text().splitlines()
assert stages and stages == inputs['core']['stages'] and len(stages) == len(set(stages)), 'Stage selection changed during capture'
assert set(stages) <= {'pre', 'post'}
oracle_hash = next(a['sha256'] for a in inputs['core']['artifacts'] if a['path'].endswith('/oracle.tsv'))
assert digest(out/'oracle.tsv') == oracle_hash
arch = (out/'architecture.txt').read_text().strip()
results = []
for stage in stages:
    for backend in ('ast', 'bytecode'):
        for entry, operation in entries.items():
            path = out/f'{stage}-{backend}-{entry}'
            rows = inputs['cases'][entry][1]
            log = (path/'run.log').read_text()
            assert f'PASS entry={entry} backend={backend} mode=inline oracleOrigin=native oracleRows={rows} arity=2 validAfterExecution=true' in log, log
            target, = [json.loads(line.removeprefix('GRAPH_TARGET=')) for line in log.splitlines() if line.startswith('GRAPH_TARGET=')]
            assert target['entry'] == entry and target['backend'] == backend
            bgv, = (path/'graphs').glob('*.bgv')
            graphs = json.loads((path/'parsed/index.json').read_text())['graphs']
            graph, = [g for g in graphs if 'Before phase HighTierLowering' in g['name']]
            assert graph['group'] == 'TruffleIR.Tier2.'+target['root'].replace(' ', '_')+'()', graph['group']
            for name in graph['nodeClassCounts']:
                assert not any(bad in name for bad in ('NewArrayNode', 'NewInstanceNode', 'CommitAllocationNode',
                    'InvokeNode', 'InvokeWithExceptionNode', 'LoadFieldNode', 'StoreFieldNode', 'StoreIndexedNode')), name
            detailed = json.loads((path/'parsed'/graph['file']).read_text())
            allocated_boxes = 0
            for node in detailed['nodes']:
                name, props = node['nodeClass'], node['properties']
                if 'UnboxNode' in name:
                    assert props.get('boxingKind') == 'JavaKind.Long', 'Intermediate floating unbox'
                elif 'BoxNode' in name:
                    assert props.get('stamp') == 'a!# java.lang.Long', 'Intermediate floating box'
                    allocated_boxes += 'AllocatingBoxNode' in name
                if 'LoadIndexedNode' in name:
                    assert props.get('elementKind') == 'JavaKind.Object', 'Residual vector payload load'
            assert allocated_boxes == 1, 'Only the public Long result box is expected'
            cfg, = (path/'graphs').glob('*.cfg')
            text = cfg.read_text()
            compilation_roots = set(re.findall(r'  method "TruffleHotSpotCompilation-\d+\[(.*?)\]"', text))
            assert compilation_roots == {target['root']}, compilation_roots
            start = text.rindex('  name "After FinalCodeAnalysisStage"')
            end = text.index('end_cfg', start)+len('end_cfg')
            lir = text[start:end]
            if arch in ('x86_64', 'amd64'):
                assert re.search(r'\bV?'+operation+r'PD\b', lir), f'No packed {operation}: {path}'
            else:
                assert arch in ('arm64', 'aarch64'), arch
                assert re.search(r'v\d+\|V128_DOUBLE = F?'+operation+r'\b', lir), f'No packed {operation}: {path}'
            assert not re.search(r'\b(?:V?F(?:MADD|MSUB|NMADD|NMSUB)\w*|FMLA|FMLS)\b', lir), 'Unexpected fused arithmetic'
            (path/'final-lir.txt').write_text('\n'.join(line.rstrip() for line in lir.splitlines())+'\n')
            results.append(dict(stage=stage, backend=backend, entry=entry, rows=rows,
                installedEntryValidAfterExecution=True, physicalPackedInstruction=operation,
                vectorAllocations=False, vectorFieldTraffic=False, intermediateLaneBoxes=False,
                hostLongResultBox=True, highTierGraph=graph, rawGraph=record(bgv), rawLir=record(cfg),
                log=record(path/'run.log'), lir=record(path/'final-lir.txt')))
(out/'evidence.json').write_text(json.dumps(dict(schema=1, vector='doublex2', architecture=arch,
    results=results, **initial, inputProvenance=record(out/'input-provenance.json'), jdk=record(out/'jdk-release.txt'),
    claim='Actual exported Core and native rows on both backends; packed Double arithmetic with temporary allocations removed. Not a vector function ABI or a throughput claim.',
    compiledEntryCounter='Not instrumented here; per-input compiled-entry deltas are separately required by SimdFloatVectorTest.'), indent=2)+'\n')
print(f'DoubleX2 actual-Core graph gate passed: {arch}, {len(results)} records')
