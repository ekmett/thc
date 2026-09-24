import hashlib
import json
from pathlib import Path
import re
import tarfile
import xml.etree.ElementTree as ET

root = Path('/home/ekmett/ai/thc-pinned-addresses-01a0cdeb')
out = root / 'bench/experiments/pinned-addresses/evidence-x86_64'
calls = json.loads((root / 'build/pinned-addresses/manifest.json').read_text())['expectedGuestCallsByEntry']
checks = {'junit': {}, 'runs': {}, 'python': {}, 'nativeHashRecords': 0}
for archive in sorted((out / 'junit').glob('*.tar.gz')):
    summary = dict(tests=0, failures=0, errors=0, skipped=0, suites=0,
                   pinnedMarkers=0, pinnedCompiledRows=0, pinnedCompiledGuestEntries=0)
    with tarfile.open(archive, 'r:gz') as bundle:
        for member in bundle.getmembers():
            if not member.isfile() or not member.name.endswith('.xml'):
                continue
            suite = ET.fromstring(bundle.extractfile(member).read())
            if suite.tag != 'testsuite':
                raise ValueError(member.name)
            summary['suites'] += 1
            for key in ['tests', 'failures', 'errors', 'skipped']:
                summary[key] += int(suite.attrib[key])
            matches = re.findall(r'PinnedAddress PASS (pre|post)/(ast|bytecode)/(\w+)/inlining=(true|false) rows=(\d+)',
                                 suite.findtext('system-out', ''))
            for stage, backend, entry, inline, rows in matches:
                summary['pinnedMarkers'] += 1
                summary['pinnedCompiledRows'] += int(rows)
                summary['pinnedCompiledGuestEntries'] += int(rows) * calls[entry]
    checks['junit'][archive.name] = summary
for run in sorted((out / 'runs').iterdir()):
    checks['runs'][run.name] = json.loads((run / 'result.json').read_text())
for record in json.loads((out / 'python/results.json').read_text()):
    if record['exitCode'] != 0:
        raise ValueError(record)
    log = (out / 'python' / record['output']).read_text()
    if 'skipped=' in log or not re.search(r'\nOK\n', log):
        raise ValueError(record)
    count = int(re.search(r'Ran (\d+) tests', log).group(1))
    checks['python'][record['mode']] = checks['python'].get(record['mode'], 0) + count
assert checks['python'] == {'normal': 169, 'optimized': 169}, checks['python']
checks['nativeHashRecords'] = len(json.loads((out / 'verified-native-hashes.json').read_text()))
assert checks['nativeHashRecords'] == 76
reader = json.loads((out / 'reader-root/results.json').read_text())
assert len(reader) == 2 and all(r['exitCode'] == 0 for r in reader), reader
checks['readerModes'] = [r['mode'] for r in reader]
assert checks['readerModes'] == ['normal', 'optimized']
for name in ['focused-default-32-pass', 'focused-dense-32-pass',
             'full-default-665-pass', 'full-dense-665-pass']:
    summary = checks['junit'][name + '.tar.gz']
    assert checks['runs'][name]['returncode'] == 0, name
    assert summary['tests'] == (665 if name.startswith('full-') else 32), (name, summary)
    assert (summary['failures'], summary['errors'], summary['skipped']) == (0, 0, 0), (name, summary)
    assert (summary['pinnedMarkers'], summary['pinnedCompiledRows'], summary['pinnedCompiledGuestEntries']) == (40, 51096, 197904), (name, summary)
(out / 'checks.json').write_text(json.dumps(checks, indent=2) + '\n')
files = sorted(p for p in out.rglob('*') if p.is_file() and p.name != 'SHA256SUMS')
lines = []
for path in files:
    lines.append(hashlib.sha256(path.read_bytes()).hexdigest() + '  ' + str(path.relative_to(out)))
(out / 'SHA256SUMS').write_text('\n'.join(lines) + '\n')
print(json.dumps({'payloadFiles': len(files), 'seal': hashlib.sha256((out / 'SHA256SUMS').read_bytes()).hexdigest(),
                  'checks': checks}, indent=2))
