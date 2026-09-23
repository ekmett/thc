#!/usr/bin/env python3
"""Fail unless the installed Truffle entry uses actual 128-bit SIMD after allocation."""
import hashlib
import json
import pathlib
import re
import sys
out = pathlib.Path(sys.argv[1])
assert 'PASS 49 input pairs, exact compiled target valid' in (out / 'probe.log').read_text()
graphs = json.loads((out / 'parsed/index.json').read_text())['graphs']
# The final high-tier graph must contain arithmetic vectors, with no vector box or payload array.
graph = next(g for g in graphs if 'Before phase HighTierLowering' in g['name'])
counts = graph['nodeClassCounts']
for name in counts:
    assert not any(bad in name for bad in ('NewArrayNode', 'NewInstanceNode', 'CommitAllocationNode', 'InvokeNode', 'InvokeWithExceptionNode')), name
cfg, = (out / 'graphs').glob('*.cfg')
text = cfg.read_text()
start = text.rindex('  name "After FinalCodeAnalysisStage"')
end = text.index('end_cfg', start) + len('end_cfg')
lir = text[start:end]
arch = (out / 'architecture.txt').read_text().strip()
if arch in ('arm64', 'aarch64'):
    assert re.search(r'v\d+\|V128_QWORD = ADD', lir)
    assert re.search(r'v\d+\|V128_QWORD = XOR', lir)
    assert re.search(r'v\d+\|V128_QWORD = LSL', lir)
else:
    assert arch in ('x86_64', 'amd64'), arch
    assert re.search(r'(VPADDQ|PADDQ)', lir), 'Missing packed 64-bit add'
    assert re.search(r'(VPXOR|PXOR)', lir), 'Missing packed XOR'
    assert re.search(r'(VPSLLQ|PSLLQ)', lir), 'Missing packed shift'
(out / 'final-lir.txt').write_text(lir + '\n')
here = pathlib.Path(__file__).resolve().parent
record = lambda p: dict(path=str(p), sha256=hashlib.sha256(p.read_bytes()).hexdigest())
report = dict(schema=1, architecture=arch, rows=49, installedEntryValidAfterExecution=True,
              primitiveVectorInstructions=True, vectorAllocationAtHighTier=False,
              hostLongResultBox=True, highTierGraph=graph,
              sources=[record(here / p) for p in ('VectorApiProbe.java', 'run.sh', 'audit.py')],
              artifacts=[record(out / p) for p in ('probe.log', 'java-version.txt', 'jdk-release.txt', 'final-lir.txt')],
              claim='Pinned Truffle/Vector API mechanism only; THC Core execution requires a separate runtime test.')
(out / 'evidence.json').write_text(json.dumps(report, indent=2) + '\n')
print(f'SIMD mechanism verified: {arch}, 49 native-long formulas, installed entry valid, physical vector registers.')
