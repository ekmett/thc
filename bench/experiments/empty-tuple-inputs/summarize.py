#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Check selected production graph snapshots and retain compact, hash-bound evidence."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
CASES = [(stage, backend, mode, entry) for stage in ('pre', 'post')
         for backend in ('ast', 'bytecode') for mode in ('inline', 'residual')
         for entry in ('beforeCase', 'scalarControl', 'betweenInputs')]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def short(node):
    return node['nodeClass'].split('.')[-1]


def validate_record(record):
    assert digest(Path(record['path'])) == record['sha256'], 'Changed input: ' + record['path']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    parser.add_argument('--capture', type=Path)
    args = parser.parse_args()
    output = args.output.resolve()
    launch = json.loads((output / 'launch.json').read_text())
    for path, sha in launch['inputSha256'].items():
        assert digest(Path(path)) == sha, 'Changed launch input: ' + path
    results, excerpts = [], []
    for stage, backend, mode, entry in CASES:
        label = '-'.join((stage, backend, mode, entry))
        path = output / label
        capture = json.loads((path / 'capture.json').read_text())
        inputs = json.loads((path / 'launch.json').read_text())
        assert inputs['sourceCommit'] == launch['runtimeSourceCommit'], 'Mixed source revisions'
        for key in ('sources', 'runtimeJars'):
            for record in inputs[key]:
                validate_record(record)
        for key in ('module', 'oracle', 'nativeProvenance', 'nativeChecks', 'jdkRelease'):
            validate_record(inputs[key])
        for key in ('graph', 'cfg', 'log', 'lir'):
            validate_record(capture[key])
        assert (capture['entry'], capture['backend'], capture['mode'], capture['handoff']) == (entry, backend, mode, 'false')
        rows = [r for r in Path(inputs['oracle']['path']).read_text().splitlines() if r.split('\t')[0] == entry]
        arity = 2 if entry == 'betweenInputs' else 1
        assert len(rows) == (7 if arity == 2 else 8)
        expected = f'PASS entry={entry} backend={backend} mode={mode} nativeRows={len(rows)} arity={arity} validAfterEveryRow=true'
        passes = [r for r in Path(capture['log']['path']).read_text().splitlines() if r.startswith('PASS ')]
        assert passes == [expected], 'Native/installed-code check mismatch: ' + label
        phases = capture['phases']
        assert len(phases) == 2 and 'Before phase HighTierLowering' in phases[0]['name'] and 'After low tier' in phases[1]['name']
        expected_group = 'TruffleIR.Tier2.' + capture['target']['root'].replace(' ', '_') + '()'
        high, low = [json.loads((path / 'parsed' / p['file']).read_text()) for p in phases]
        assert all(g['group'] == expected_group for g in (high, low)), 'Wrong compiled target'
        counts = Counter(short(n) for n in high['nodes'])
        read_write = Counter(str(n['properties']['locationIdentity']) for n in low['nodes']
                             if short(n) in ('ReadNode', 'WriteNode'))
        calls = [n['properties']['targetMethod'] for n in high['nodes'] if short(n) in ('InvokeNode', 'InvokeWithExceptionNode')]
        packet_lengths = []
        nodes = {n['id']: n for n in high['nodes']}
        for node in high['nodes']:
            if short(node) == 'AllocatedObjectNode':
                virtual = [nodes[e['from']] for e in high['edges'] if e['to'] == node['id'] and e['label'] == 'virtualObject']
                assert len(virtual) == 1 and short(virtual[0]) == 'VirtualArrayNode'
                assert virtual[0]['properties']['componentType'] == 'java.lang.Object'
                packet_lengths.append(virtual[0]['properties']['length'])
        if mode == 'inline':
            assert not calls and not packet_lengths
            assert not any(counts[k] for k in ('CommitAllocationNode', 'NewInstanceNode', 'NewArrayNode'))
            assert counts['BoxNode$AllocatingBoxNode'] == 1, 'Only the scalar host Long box may remain'
            assert not any('thc.runtime.' in name or name == 'Array: boolean' for name in read_write), 'Guest layout/carrier memory survived'
            arithmetic = [n for n in high['nodes'] if short(n) in ('AddNode', 'LeftShiftNode')]
            assert arithmetic and all(n['properties']['stamp'].startswith('i64') for n in arithmetic)
            low_calls = [n['properties']['targetMethod'] for n in low['nodes'] if short(n) in ('InvokeNode', 'InvokeWithExceptionNode')]
            assert all('HotSpotThreadLocalHandshake.doHandshake' in call for call in low_calls)
        else:
            assert calls == ['OptimizedCallTarget.callBoundary'], calls
            assert counts['CommitAllocationNode'] == 1
            assert packet_lengths == [arity + 1], 'Residual packet must contain only header plus scalar payloads'
        cfg = Path(capture['cfg']['path']).read_text()
        start = cfg.rindex('  name "After FinalCodeAnalysisStage"')
        end = cfg.index('end_cfg', start) + len('end_cfg')
        lir = '\n'.join(line.rstrip() for line in cfg[start:end].splitlines()) + '\n'
        assert Path(capture['lir']['path']).read_text() == lir, 'Final LIR differs from raw CFG'
        if mode == 'inline':
            excerpt = [line.strip() for line in lir.splitlines() if 'instruction ' in line and
                       (re.search(r'= ADD .*r\d+\|QWORD', line) or 'RETURN' in line)]
            assert excerpt, 'Expected physical scalar arithmetic/return in final LIR'
            excerpts += [f'=== {label}: After FinalCodeAnalysisStage ===', *excerpt, '']
        results.append(dict(stage=stage, backend=backend, mode=mode, entry=entry, nativeRows=len(rows),
                            hostArity=arity, validAfterEveryRow=True, highNodes=len(high['nodes']), lowNodes=len(low['nodes']),
                            highNodeCounts=dict(sorted(counts.items())), lowMemoryLocations=dict(sorted(read_write.items())),
                            guestCalls=calls, committedObjectPacketLengths=packet_lengths,
                            moduleSha256=inputs['module']['sha256'], oracleSha256=inputs['oracle']['sha256'],
                            rawGraph=capture['graph'], rawCFG=capture['cfg'], log=capture['log'], finalLIR=capture['lir'],
                            highSnapshotSha256=digest(path / 'parsed' / phases[0]['file']),
                            lowSnapshotSha256=digest(path / 'parsed' / phases[1]['file']), check=expected))
        print(label, 'PASS', 'high/low', len(high['nodes']), len(low['nodes']), 'packet', packet_lengths)
    if args.capture:
        args.capture.mkdir(parents=True, exist_ok=True)
        native = ROOT / 'build/empty-tuple-input'
        evidence = dict(schema=1, scope='Actual exported Core on production THC; fixed correctness rows, no timing',
                        launch=launch, nativeProvenance=json.loads((native / 'provenance.json').read_text()), results=results,
                        limitations=['Graph instrumentation is disabled; this harness checks native values and active target validity, not compiled-entry counters',
                                     'Instrumented JVM tests separately require per-row compiled-entry increments',
                                     'Incoming host Object[] arguments and final Long boxing remain',
                                     'Residual calls use the regular Object[] ABI, not a multi-register machine-call ABI',
                                     'Virtual frame/deoptimization metadata may remain without runtime materialization',
                                     'Scalar control uses a non-tail call; empty beforeCase uses the existing tail-call protocol',
                                     'AArch64 register allocation may spill; no timing/performance claim'])
        (args.capture / 'evidence.json').write_text(json.dumps(evidence, indent=2) + '\n')
        (args.capture / 'lir-excerpts.txt').write_text('\n'.join(excerpts))
        for name in ('oracle.tsv', 'oracle-pairs.tsv'):
            (args.capture / name).write_bytes((native / name).read_bytes())
        print('Captured', len(results), 'verified controls')


if __name__ == '__main__':
    main()
