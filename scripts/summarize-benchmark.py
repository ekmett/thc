#!/usr/bin/env python3
"""Validate input-cycle checksums and summarize windows/forks/compiler activity."""
import csv, json, pathlib, statistics, sys
root = pathlib.Path(sys.argv[1])
rows = list(csv.DictReader((root / 'timings.tsv').open(), delimiter='\t'))
summary = []
for entry in dict.fromkeys(r['entry'] for r in rows):
    xs = [r for r in rows if r['entry'] == entry]
    cycle_checksums = set()
    for r in xs:
        count, checksum = int(r['repetitions']), int(r['checksum'])
        assert count > 0 and count % 16 == 0
        # These kernels/window sizes do not overflow signed64 checksum arithmetic.
        assert checksum % (count // 16) == 0
        cycle_checksums.add(checksum // (count // 16))
    assert len(cycle_checksums) == 1, (entry, 'checksum mismatch')
    engines = {}
    for engine in ['native-ghc', 'thc-graal']:
        by_fork = {}
        for r in xs:
            if r['engine'] == engine:
                by_fork.setdefault(r['fork'], []).append(int(r['elapsedNs']) / int(r['repetitions']))
        fork_medians = [statistics.median(v) for v in by_fork.values()]
        values = [v for vs in by_fork.values() for v in vs]
        engines[engine] = {'medianNs': statistics.median(fork_medians), 'forkMedianNs': fork_medians,
            'minWindowNs': min(values), 'maxWindowNs': max(values),
            'lastVsFirstPercent': [(v[-1] / v[0] - 1) * 100 for v in by_fork.values()]}
    events = []
    for p in sorted(root.glob(f'{entry}-*.log')):
        measuring = False
        for line in p.read_text().splitlines():
            if line.startswith('PHASE MEASURE'):
                measuring = line.endswith('BEGIN')
            elif measuring and any(event in line for event in ['opt done', 'opt start', 'opt fail', 'opt inval', 'deopt']):
                events.append({'file': p.name, 'event': line})
    result = {'entry': entry, 'cycleChecksum': cycle_checksums.pop(), 'engines': engines,
        'ratio': engines['thc-graal']['medianNs'] / engines['native-ghc']['medianNs'], 'measuredCompilationEvents': events}
    summary.append(result)
    print(f"{entry}: native {engines['native-ghc']['medianNs']:.1f} ns/call; THC {engines['thc-graal']['medianNs']:.1f} ns/call; {result['ratio']:.2f}x; {len(events)} measured compilation events; checksums matched")
(root / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
