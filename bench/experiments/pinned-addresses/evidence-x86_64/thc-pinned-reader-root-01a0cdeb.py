import hashlib
import json
from pathlib import Path
import subprocess
import sys

root = Path('/home/ekmett/ai/thc-pinned-addresses-01a0cdeb')
out = root / 'bench/experiments/pinned-addresses/evidence-x86_64/reader-root'
out.mkdir(exist_ok=False)
exports = Path('/home/ekmett/ai/thc-foreign-call-signatures-01a0cdeb/build/foreign-call-signatures')
reader = root / 'compiler/test-fixtures/check-foreign-call-metadata.py'
expected = '99cb06514d5634cc3a7196c905935c48dcacaa9396e077aed5fd40680574a577'
assert hashlib.sha256(reader.read_bytes()).hexdigest() == expected
records = []
for mode, flags in [('normal', []), ('optimized', ['-O'])]:
    command = [sys.executable, *flags, str(reader),
               '--before', str(exports / 'before-pre/ForeignCallAudit.json'),
               '--before', str(exports / 'before-post/ForeignCallAudit.json'),
               str(exports / 'after-pre/ForeignCallAudit.json'),
               str(exports / 'after-post/ForeignCallAudit.json')]
    result = subprocess.run(command, cwd=root, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, text=True, check=False)
    (out / (mode + '.log')).write_text(result.stdout)
    record = {'mode': mode, 'command': command, 'exitCode': result.returncode,
              'output': mode + '.log'}
    records.append(record)
    (out / 'results.json').write_text(json.dumps(records, indent=2) + '\n')
    print(json.dumps(record), flush=True)
    print(result.stdout, flush=True)
    if result.returncode:
        raise SystemExit(result.returncode)
head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()
(out / 'source-identity.json').write_text(json.dumps({
    'source': head, 'readerSha256': expected,
    'jvmTestedSource': '3e2c124e039926c165e907b034cc80bb9197b50b',
    'distinction': 'One fixture-reader expectation correction after the frozen JVM runs; no runtime or auditor changes.',
}, indent=2) + '\n')
with (out / 'reader-source.tar.gz').open('xb') as output:
    subprocess.run(['git', 'archive', '--format=tar.gz', head, '--', str(reader.relative_to(root))],
                   cwd=root, stdout=output, check=True)
(out / 'source-ancestry.txt').write_text(subprocess.check_output(
    ['git', 'log', '--reverse', '--format=%H %s', '9a7fef75cc4ce44cb2b1031c61c92aadffb78859..' + head],
    cwd=root, text=True))
