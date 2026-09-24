import json
import os
from pathlib import Path
import subprocess
import sys
import time

root = Path('/home/ekmett/ai/thc-pinned-addresses-01a0cdeb')
output = root / 'bench/experiments/pinned-addresses/evidence-x86_64/python'
output.mkdir(exist_ok=False)
tests = [
    'test-pinned-addresses.py', 'test-core-md5-foreign.py',
    'test-core-managed-memory.py', 'test-prepare-managed-md5.py',
    'test-audit-core.py', 'test-core-bytearrays.py', 'test-core-arrays.py',
    'test-core-sums.py', 'test-tuple-inputs.py', 'test-address-fields.py',
]
records = []
for mode, flags in [('normal', []), ('optimized', ['-O'])]:
    for test in tests:
        command = [sys.executable, *flags, 'scripts/' + test]
        stem = mode + '-' + test.removesuffix('.py')
        started = time.time()
        result = subprocess.run(command, cwd=root, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, check=False)
        (output / (stem + '.log')).write_bytes(result.stdout)
        record = {'command': command, 'cwd': str(root), 'mode': mode,
                  'test': test, 'exitCode': result.returncode,
                  'startedEpoch': started, 'elapsedSeconds': time.time() - started,
                  'output': stem + '.log'}
        records.append(record)
        (output / 'results.json').write_text(json.dumps(records, indent=2) + '\n')
        print(json.dumps(record), flush=True)
        print(result.stdout.decode(errors='replace'), flush=True)
        if result.returncode:
            raise SystemExit(result.returncode)
