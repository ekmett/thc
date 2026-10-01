#!/usr/bin/env python3
"""Native-input production SIMD graph audit; model-only results cannot pass."""
import hashlib
import json
from pathlib import Path
import re
import shutil
import sys
mode, root, out = sys.argv[1], Path(sys.argv[2]), Path(sys.argv[3])
def digest(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def record(path): return dict(path=str(path), sha256=digest(path))
if mode == 'prepare':
    assert not list(out.glob('*/graphs/*.cfg')), 'Choose a fresh output directory for source-hashed graph evidence'
    core = json.loads((root / 'build/simd/provenance.json').read_text())
    native_dir = root / 'build/simd' if core['nativeRows'] is not None else root / 'bench/experiments/simd-foundation/evidence-x86_64/native'
    native = json.loads((native_dir / 'provenance.json').read_text())
    assert native['nativeRows'] == 147
    for source in native['sources']:
        if source['path'].startswith('compiler/'):
            assert digest(root / source['path']) == source['sha256'], 'Native fixture/exporter source mismatch: ' + source['path']
    oracle = native_dir / 'oracle.tsv'
    proof = next(a for a in native['artifacts'] if a['path'].endswith('/oracle.tsv'))
    assert digest(oracle) == proof['sha256'], 'Native oracle hash mismatch'
    for artifact in core['artifacts']:
        assert digest(root / artifact['path']) == artifact['sha256'], 'Core artifact hash mismatch'
    shutil.copyfile(oracle, out / 'oracle.tsv')
    (out / 'stages.txt').write_text(''.join(stage+'\n' for stage in core['stages']))
    (out / 'input-provenance.json').write_text(json.dumps(dict(core=core, native=native,
        nativeProvenance=record(native_dir/'provenance.json'), oracle=record(oracle)), indent=2)+'\n')
    sys.exit(0)
assert mode == 'check'
arch = (out / 'architecture.txt').read_text().strip()
results = []
for stage in (out / 'stages.txt').read_text().splitlines():
 for backend in ('ast', 'bytecode'):
  for entry in ('vectorCase','subtractCase'):
    path=out/f'{stage}-{backend}-{entry}'
    log=(path/'run.log').read_text()
    assert 'oracleOrigin=native oracleRows=49 arity=2 validAfterExecution=true' in log, log
    graphs=json.loads((path/'parsed/index.json').read_text())['graphs']
    graph=next(g for g in graphs if 'Before phase HighTierLowering' in g['name'])
    for name in graph['nodeClassCounts']:
        assert not any(bad in name for bad in ('NewArrayNode','NewInstanceNode','CommitAllocationNode','InvokeNode','InvokeWithExceptionNode','LoadFieldNode','StoreFieldNode')), name
    cfg,=(path/'graphs').glob('*.cfg')
    text=cfg.read_text();start=text.rindex('  name "After FinalCodeAnalysisStage"');end=text.index('end_cfg',start)+len('end_cfg')
    lir=text[start:end]
    if arch in ('arm64','aarch64'):
        assert re.search(r'v\d+\|V128_QWORD = '+('ADD' if entry=='vectorCase' else 'SUB'),lir), lir
    else:
        assert arch in ('x86_64','amd64'),arch
        assert re.search(r'V?P'+('ADDQ' if entry=='vectorCase' else 'SUBQ'),lir),lir
    (path/'final-lir.txt').write_text('\n'.join(line.rstrip() for line in lir.splitlines())+'\n')
    results.append(dict(stage=stage,backend=backend,entry=entry,rows=49,installedEntryValidAfterExecution=True,
        vectorAllocations=False,vectorFieldTraffic=False,physicalPackedInstructions=True,hostLongResultBox=True,
        highTierGraph=graph,lirNormalization='trailing whitespace removed',log=record(path/'run.log'),lir=record(path/'final-lir.txt')))
sources=[record(root/'build.gradle.kts')] + [record(p) for p in sorted((root/'src/main').rglob('*')) if p.is_file()]
sources += [record(p) for p in sorted((root/'bench/experiments/simd-foundation').glob('*')) if p.is_file() and p.suffix in ('.java', '.sh', '.py')]
(out/'evidence.json').write_text(json.dumps(dict(schema=1,architecture=arch,results=results,
    sources=sources,inputProvenance=record(out/'input-provenance.json'),jdk=record(out/'jdk-release.txt'),
    runtimeJars=[record(p) for p in sorted((root/'build/install/thc/lib').glob('*.jar'))],
    claim='Actual exported Core on both THC backends with native GHC rows and physical packed instructions; not a vector function calling convention.'),indent=2)+'\n')
print(f'Actual Core SIMD verified: {arch}, {len(results)} compiled entry checks, native GHC input rows, no vector carrier/array allocation or field traffic.')
