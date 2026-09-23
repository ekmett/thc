#!/usr/bin/env python3
"""Audit the standalone mechanism probe, never production THC tuple lowering."""
import hashlib
import json
from pathlib import Path
import sys

out = Path(sys.argv[1])
index = json.loads((out / 'parsed-consumer/index.json').read_text())
phases = {}
for suffix in ('Before phase HighTierLowering', 'After low tier'):
    matching = [g for g in index['graphs'] if g['name'].endswith(suffix)]
    assert len(matching) == 1, (suffix, len(matching))
    summary = matching[0]
    graph = json.loads((out / 'parsed-consumer' / summary['file']).read_text())
    counts = {k.rsplit('.', 1)[-1]: v for k, v in summary['nodeClassCounts'].items()}
    for kind in counts:
        assert not any(word in kind for word in ('Allocation', 'Allocated', 'NewInstance', 'NewArray', 'Store', 'Write')), kind
    for node in graph['nodes']:
        kind, properties = node['nodeClass'], node['properties']
        if 'Invoke' in kind:
            assert properties.get('targetMethod') == 'Direct#HotSpotThreadLocalHandshake.doHandshake', properties
        if 'ReadNode' in kind:
            assert properties.get('location') in ('Array: Object', 'java.lang.Long.value', 'java.lang.Boolean.value', 'JavaThread::_jvmci_reserved0'), properties
    assert counts.get('AddNode', 0) >= 1 and counts.get('XorNode', 0) >= 2 and counts.get('MulNode', 0) >= 1, counts
    phases[suffix] = {'nodes': summary['nodes'], 'counts': counts}

cfgs = list((out / 'graphs').glob('*[[]MixedTupleProbe.Consumer@*.cfg'))
assert len(cfgs) == 1, cfgs
cfg = cfgs[0].read_text()
marker = '  name "After FinalCodeAnalysisStage"'
assert marker in cfg
lir = cfg[cfg.index(marker):].split('\nbegin_cfg', 1)[0]
lines = [line.strip() for line in lir.splitlines() if 'instruction ' in line]
assert any('RETURN' in line and 'additionalReturns: []' in line for line in lines)
assert any(' = MUL ' in line for line in lines)
assert any(' = XOR ' in line for line in lines)
assert not any('ALLOC' in line or 'NEW_INSTANCE' in line for line in lines)
(out / 'final-lir-excerpt.txt').write_text('\n'.join(lines) + '\n')
checks = {}
for name in ('directive-inline', 'directive-residual', 'mixed-inline', 'mixed-residual', 'virtual-escape'):
    log = (out / (name + '.log')).read_text()
    passed = [line for line in log.splitlines() if line.startswith('PASS')]
    assert passed, name
    if name == 'mixed-inline': assert 'actualMaterializedConsumes=1' in log
    if name == 'mixed-residual': assert 'actualMaterializedConsumes=0' in log
    if name == 'directive-inline': assert 'unnormalized-root-invalidated=true' in log
    checks[name] = [line.split(': com.oracle.truffle.api.OptimizationFailedException', 1)[0] for line in passed]

here = Path(__file__).resolve().parent
sources = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(here.glob('*Probe.java'))}
evidence = {'scope': 'Independent contract probe; not production THC runtime graph evidence',
            'javaVersion': (out / 'java-version.txt').read_text().splitlines(),
            'sourceSha256': sources, 'checks': checks, 'phases': phases,
            'machineReturn': 'One Object result, additionalReturns=[]; scalarized caller values may spill'}
(out / 'evidence.json').write_text(json.dumps(evidence, indent=2) + '\n')
