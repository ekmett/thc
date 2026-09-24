#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Native-input production SIMD graph audit; model-only results cannot pass."""
import hashlib
import json
from pathlib import Path
import re
import shutil
import sys
mode, root, out = sys.argv[1], Path(sys.argv[2]), Path(sys.argv[3])
vector = sys.argv[4] if len(sys.argv) > 4 else 'int64x2'
assert vector in ('int64x2', 'int32x4')
shape32 = vector == 'int32x4'
core_dir = root / ('build/simd-int32x4' if shape32 else 'build/simd')
rows, arity = (81, 4) if shape32 else (49, 2)
def digest(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def record(path): return dict(path=str(path), sha256=digest(path))
def runtime_snapshot():
    sources=[record(root/'build.gradle.kts')] + [record(p) for p in sorted((root/'src/main').rglob('*')) if p.is_file()]
    sources += [record(p) for p in sorted((root/'bench/experiments/simd-foundation').glob('*')) if p.is_file() and p.suffix in ('.java', '.sh', '.py')]
    return dict(sources=sources, runtimeJars=[record(p) for p in sorted((root/'build/install/thc/lib').glob('*.jar'))])
if mode == 'prepare':
    assert not list(out.glob('*/graphs/*.cfg')), 'Choose a fresh output directory for source-hashed graph evidence'
    core = json.loads((core_dir / 'provenance.json').read_text())
    native_dir = core_dir if core['nativeRows'] is not None else root / ('bench/experiments/simd-foundation/evidence-int32x4-x86_64/native' if shape32 else 'bench/experiments/simd-foundation/evidence-x86_64/native')
    native = json.loads((native_dir / 'provenance.json').read_text())
    assert native['nativeRows'] == rows * 3
    # Native GHC does not load THC's exporter plugin; validate its compiled Haskell
    # inputs. The current exporter has independent hashed Core provenance below.
    for source in native['sources']:
        if source['path'].startswith('compiler/test-fixtures/'):
            assert digest(root / source['path']) == source['sha256'], 'Native fixture source mismatch: ' + source['path']
    oracle = native_dir / 'oracle.tsv'
    proof = next(a for a in native['artifacts'] if a['path'].endswith('/oracle.tsv'))
    assert digest(oracle) == proof['sha256'], 'Native oracle hash mismatch'
    for artifact in core['artifacts']:
        assert digest(root / artifact['path']) == artifact['sha256'], 'Core artifact hash mismatch'
    for source in core['sources']:
        if source['path'].startswith(('compiler/test-fixtures/', 'compiler/Thc/')):
            assert digest(root / source['path']) == source['sha256'], 'Core source mismatch: ' + source['path']
    (out / 'runtime-snapshot.json').write_text(json.dumps(runtime_snapshot(), indent=2)+'\n')
    shutil.copyfile(oracle, out / 'oracle.tsv')
    (out / 'stages.txt').write_text(''.join(stage+'\n' for stage in core['stages']))
    (out / 'input-provenance.json').write_text(json.dumps(dict(core=core, native=native,
        nativeProvenance=record(native_dir/'provenance.json'), oracle=record(oracle)), indent=2)+'\n')
    sys.exit(0)
assert mode == 'check'
snapshot=json.loads((out/'runtime-snapshot.json').read_text())
assert runtime_snapshot() == snapshot, 'Runtime sources/jars changed during graph capture'
arch = (out / 'architecture.txt').read_text().strip()
results = []
for stage in (out / 'stages.txt').read_text().splitlines():
 for backend in ('ast', 'bytecode'):
  for entry in ('vectorCase','subtractCase'):
    path=out/f'{stage}-{backend}-{entry}'
    log=(path/'run.log').read_text()
    assert f'oracleOrigin=native oracleRows={rows} arity={arity} validAfterExecution=true' in log, log
    graphs=json.loads((path/'parsed/index.json').read_text())['graphs']
    graph=next(g for g in graphs if 'Before phase HighTierLowering' in g['name'])
    for name in graph['nodeClassCounts']:
        assert not any(bad in name for bad in ('NewArrayNode','NewInstanceNode','CommitAllocationNode','InvokeNode','InvokeWithExceptionNode','LoadFieldNode','StoreFieldNode')), name
    cfg,=(path/'graphs').glob('*.cfg')
    text=cfg.read_text();start=text.rindex('  name "After FinalCodeAnalysisStage"');end=text.index('end_cfg',start)+len('end_cfg')
    lir=text[start:end]
    if arch in ('arm64','aarch64'):
        assert re.search(r'v\d+\|V128_'+('DWORD' if shape32 else 'QWORD')+' = '+('ADD' if entry=='vectorCase' else 'SUB'),lir), lir
    else:
        assert arch in ('x86_64','amd64'),arch
        assert re.search(r'V?P'+('ADD' if entry=='vectorCase' else 'SUB')+('D' if shape32 else 'Q'),lir),lir
    (path/'final-lir.txt').write_text('\n'.join(line.rstrip() for line in lir.splitlines())+'\n')
    results.append(dict(stage=stage,backend=backend,entry=entry,rows=rows,installedEntryValidAfterExecution=True,
        vectorAllocations=False,vectorFieldTraffic=False,physicalPackedInstructions=True,hostLongResultBox=True,
        highTierGraph=graph,lirNormalization='trailing whitespace removed',log=record(path/'run.log'),lir=record(path/'final-lir.txt')))
(out/'evidence.json').write_text(json.dumps(dict(schema=1,vector=vector,architecture=arch,results=results,
    **snapshot,inputProvenance=record(out/'input-provenance.json'),jdk=record(out/'jdk-release.txt'),
    claim='Actual exported Core on both THC backends with native GHC rows and physical packed instructions; not a vector function calling convention.'),indent=2)+'\n')
print(f'Actual Core SIMD verified: {arch}, {len(results)} compiled entry checks, native GHC input rows, no vector carrier/array allocation or field traffic.')
