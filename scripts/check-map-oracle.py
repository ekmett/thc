#!/usr/bin/env python3
"""Generate native rows and check them against an independent histogram model."""
import json, pathlib, subprocess, sys
root = pathlib.Path(__file__).resolve().parent.parent
out = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else root / 'build/map'
out.mkdir(parents=True, exist_ok=True)
inputs = [-3, 0, 1, 2, 3, 4, 7, 15, 16, 31, 100, 1024, 4095, 4096, 4097, 10000, 65536, 100000]
rows = []
for n in inputs:
    row = subprocess.check_output([str(root / 'build/map/native/native-oracle'), 'mapAggregate', str(n)], text=True).strip()
    result = int(row.split('\t')[2])
    counts = {}
    for i in range(max(n, 0)):
        key = (i * 1103515245 + 12345) & 4095
        counts[key] = counts.get(key, 0) + 1
    for i in range(max(n, 0) // 4):
        key = (3 * i * 1103515245 + 12345) & 4095
        if key in counts:
            counts[key] += 7
    expected = (sum((key + 1) * value for key, value in counts.items())
                + sum(counts.get((i * 48271 + 17) & 8191, 0) for i in range(max(n, 0)))
                + len(counts))
    assert result == expected, (n, result, expected)
    rows.append(row)
(out / 'oracle.tsv').write_text('\n'.join(rows) + '\n')
(out / 'oracle-validation.json').write_text(json.dumps({
    'inputs': inputs, 'allNativeResultsMatchIndependentHistogram': True}, indent=2) + '\n')
print(f'Native oracle matches independent histogram on {len(rows)} inputs')
