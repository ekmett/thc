#!/usr/bin/env python3
"""Verify compact evidence against the original local artifacts, then seal it."""
import gzip
import hashlib
import json
from pathlib import Path
import tarfile
import xml.etree.ElementTree as ET

retained = Path('bench/experiments/int32x4-bytearray/evidence-x86_64')
capture = Path('build/int32x4-bytearray-runtime-bounds-deopt')
first = Path('build/int32x4-bytearray-runtime-frozen')
def require(condition, message):
    if not condition:
        raise AssertionError(message)
def sha(data): return hashlib.sha256(data).hexdigest()
def digest(path): return sha(path.read_bytes())
def unpacked(path):
    return path.read_bytes() if path.is_file() else gzip.decompress(Path(str(path)+'.gz').read_bytes())
require(not (retained/'SHA256SUMS').exists(), 'Refusing to replace sealed manifest')
require((retained/'README.md').is_file(), 'Write the evidence scope/limitations first')
for name in ('evidence.json', 'input-provenance.json', 'runtime-snapshot.json', 'graph-cases.json', 'oracle.tsv'):
    require(sha(unpacked(retained/name)) == digest(capture/name), 'Copied graph evidence changed: '+name)
evidence = json.loads((retained/'evidence.json').read_text())
require(len(evidence['results']) == 16, 'Expected sixteen accepted graphs')
require((retained/'check.exit-status.txt').read_text().strip() == '1', 'Original new-capture reader failure missing')
require((retained/'recheck.exit-status.txt').read_text().strip() == '0', 'Offline correction did not pass')
require(evidence['checkerCorrection']['guestExecutionRepeated'] is False, 'Unexpected guest repetition')
for item in evidence['results']:
    label = f"{item['stage']}-{item['backend']}-{item['entry']}"
    folder = retained/label
    require(sha(gzip.decompress((folder/'graph.json.gz').read_bytes())) == item['parsedGraph']['sha256'], 'Graph gzip changed')
    for key, name in (('log','run.log'), ('command','run.command.txt'), ('exitStatus','run.exit-status.txt'), ('lir','final-lir.txt')):
        require(sha(unpacked(folder/name)) == item[key]['sha256'], 'Copied graph log/LIR changed')
    for key in ('parsedGraph', 'rawGraph', 'rawLir'):
        require(digest(Path(item[key]['path'])) == item[key]['sha256'], 'Original graph artifact changed')
    require((folder/'run.exit-status.txt').read_text().strip() == (folder/'parse.exit-status.txt').read_text().strip() == '0',
            'Accepted capture command failed')
frontier = json.loads((retained/'first-capture/first-capture-frontier.json').read_text())
require(len(frontier['records']) == 16, 'Expected sixteen preserved rejected graphs')
require(digest(retained/'first-capture/check.log') == frontier['originalCheck']['sha256'], 'Original failure changed')
require((retained/'first-capture/check.exit-status.txt').read_text().strip() == '1', 'Original failed status missing')
for item in frontier['records']:
    label = f"{item['stage']}-{item['backend']}-{item['entry']}"
    folder = retained/'first-capture'/label
    require(sha(gzip.decompress((folder/'graph.json.gz').read_bytes())) == item['graph']['sha256'], 'Failed graph gzip changed')
    for record in [item['graph']]+item['artifacts']:
        require(digest(Path(record['path'])) == record['sha256'], 'Original failed-capture artifact changed')
    source = Path('build/int32x4-memory-reader-checks/first-lir')/(label+'.txt')
    require(sha(unpacked(folder/'final-lir-corrected-reader.txt')) == digest(source), 'Failed final LIR view changed')
native = retained/'native'
require(unpacked(native/'expected.tsv') == unpacked(native/'oracle.tsv'), 'Native/model bytes differ')
require(sha(unpacked(native/'provenance.json')) == digest(Path('build/simd-int32x4-bytearray/provenance.json')), 'Native provenance changed')
suite_counts = {}
for label, total in (('initial',498), ('hardened',500), ('deopt-transition',501)):
    original = Path('build/int32x4-memory-validation'+('' if label == 'initial' else '-'+label))
    identities = []
    for mode in ('focused-default', 'full-default', 'full-dense'):
        folder = retained/'validation'/label/mode
        require((folder/'exit-status.txt').read_text().strip() == '0', 'Failed suite: '+str(folder))
        cases = set()
        with tarfile.open(folder/'junit.tar.gz', 'r:gz') as archive:
            members = [m for m in archive.getmembers() if m.isfile()]
            require(members, 'Empty JUnit archive')
            for member in members:
                path = Path(member.name)
                require(len(path.parts) == 1 and path.name.startswith('TEST-') and path.suffix == '.xml', 'Unexpected JUnit member')
                data = archive.extractfile(member).read()
                require(data == (original/mode/'test-results'/path.name).read_bytes(), 'Archived JUnit changed')
                suite = ET.fromstring(data)
                require(all(int(suite.get(k,0)) == 0 for k in ('failures','errors','skipped')), 'Nonpassing JUnit suite')
                for case in suite.findall('testcase'):
                    key = (case.get('classname'), case.get('name'))
                    require(key not in cases, 'Duplicate JUnit test')
                    cases.add(key)
        expected = total if mode != 'focused-default' else total-487
        require(len(cases) == expected, 'Unexpected JUnit count: '+str(folder))
        suite_counts[label+'/'+mode] = len(cases)
        if mode != 'focused-default': identities.append(cases)
    require(identities[0] == identities[1], 'Full modes checked different cases')
summary = {'acceptedCaptureCount':16, 'preservedRejectedCaptureCount':16, 'nativeRows':9666,
           'suiteCounts':suite_counts, 'rawGraphsRehashed':True, 'compactGraphContentsRehashed':True,
           'originalFailedArtifactsRehashed':True, 'junitArchivesMatchOriginals':True}
(retained/'package-verification.json').write_text(json.dumps(summary,indent=2)+'\n')
files = sorted(path for path in retained.rglob('*') if path.is_file())
manifest = ''.join(digest(path)+'  '+path.relative_to(retained).as_posix()+'\n' for path in files)
(retained/'SHA256SUMS').write_text(manifest)
print(json.dumps({'files':len(files), 'bytes':sum(p.stat().st_size for p in files),
                  'manifestSha256':digest(retained/'SHA256SUMS'), 'verification':summary},indent=2))
