#!/usr/bin/env python3
"""Verify compact Word32 memory evidence against originals before sealing."""
import gzip
import hashlib
import json
from pathlib import Path
import tarfile
import xml.etree.ElementTree as ET

retained = Path('bench/experiments/word32x4-bytearray/evidence-x86_64')
capture = Path('build/word32x4-bytearray-runtime')
def require(condition, message):
    if not condition:
        raise AssertionError(message)
def sha(data): return hashlib.sha256(data).hexdigest()
def digest(path): return sha(path.read_bytes())
def unpacked(path):
    return path.read_bytes() if path.is_file() else gzip.decompress(Path(str(path)+'.gz').read_bytes())
require(not (retained/'SHA256SUMS').exists(), 'Refusing to replace sealed manifest')
require((retained/'README.md').is_file(), 'Write evidence scope and limitations first')
for name in ('evidence.json', 'input-provenance.json', 'runtime-snapshot.json', 'graph-cases.json', 'oracle.tsv'):
    require(sha(unpacked(retained/name)) == digest(capture/name), 'Copied evidence changed: '+name)
evidence = json.loads((retained/'evidence.json').read_text())
require(len(evidence['results']) == 16 and evidence['checkerCorrection'] is None, 'Expected sixteen first-pass graphs')
require((retained/'check.exit-status.txt').read_text().strip() == '0', 'Graph check failed')
extensions = sum(len(item['packedMemoryNode']['unsignedLaneExtensions']) for item in evidence['results'])
stores = sum(len(item['packedMemoryNode']['packedStoreLanes']) for item in evidence['results'])
instructions = sum(len(item['physicalPackedInstructions']) for item in evidence['results'])
comparisons = sum(item['rows'] * item['compiledCorpusPasses'] for item in evidence['results'])
require((extensions, stores, instructions, comparisons) == (32,32,16,1152), 'Packed graph/corpus totals changed')
for item in evidence['results']:
    label = f"{item['stage']}-{item['backend']}-{item['entry']}"
    folder = retained/label
    require(sha(unpacked(folder/'graph.json')) == item['parsedGraph']['sha256'], 'Graph gzip changed')
    for key, name in (('log','run.log'), ('command','run.command.txt'), ('exitStatus','run.exit-status.txt'), ('lir','final-lir.txt')):
        require(sha(unpacked(folder/name)) == item[key]['sha256'], 'Copied graph log/LIR changed')
    for key in ('parsedGraph', 'rawGraph', 'rawLir'):
        require(digest(Path(item[key]['path'])) == item[key]['sha256'], 'Original graph artifact changed')
    require((folder/'run.exit-status.txt').read_text().strip() == (folder/'parse.exit-status.txt').read_text().strip() == '0',
            'Accepted capture command failed')
native = retained/'native'
require(unpacked(native/'expected.tsv') == unpacked(native/'oracle.tsv'), 'Native/model bytes differ')
require(sha(unpacked(native/'provenance.json')) == digest(Path('build/simd-word32x4-bytearray/provenance.json')),
        'Native provenance changed')
for original in Path('build/simd-word32x4-bytearray').glob('prepare-run-*/provenance.json'):
    require(sha(unpacked(native/original.parent.name/'provenance.json')) == digest(original),
            'Retained preparation snapshot changed: '+original.parent.name)
suite_counts, signatures = {}, []
for mode, expected in (('direct-proof-green',3), ('focused-default',14), ('full-default',515), ('full-dense',515), ('direct-proof-red',2),
                       ('first/direct-proof-green',2), ('first/focused-default',14), ('first/full-default',515), ('first/full-dense',515)):
    original = Path('build/word32x4-memory-validation-canonical')/mode
    if mode.startswith('first/'):
        original = Path('build/word32x4-memory-validation')/mode.split('/',1)[1]
    red = mode == 'direct-proof-red'
    if red: original = Path('build/word32x4-memory-direct-proof-red')
    folder = retained/'validation'/mode
    require((folder/'exit-status.txt').read_text().strip() == ('1' if red else '0'), 'Unexpected suite status: '+mode)
    cases, failures = set(), []
    with tarfile.open(folder/'junit.tar.gz', 'r:gz') as archive:
        members = [member for member in archive.getmembers() if member.isfile()]
        require(members, 'Empty JUnit archive')
        for member in members:
            path = Path(member.name)
            require(len(path.parts) == 1 and path.name.startswith('TEST-') and path.suffix == '.xml', 'Unexpected JUnit member')
            data = archive.extractfile(member).read()
            require(data == (original/'test-results'/path.name).read_bytes(), 'Archived JUnit changed')
            suite = ET.fromstring(data)
            require(all(int(suite.get(key,0)) == 0 for key in ('errors','skipped')), 'Suite errors/skips')
            require(int(suite.get('failures',0)) == (1 if red else 0), 'Unexpected failures')
            require(len(suite.findall('testcase')) == int(suite.get('tests')), 'JUnit count mismatch')
            for case in suite.findall('testcase'):
                key = (case.get('classname'), case.get('name'))
                require(key not in cases, 'Duplicate JUnit case')
                cases.add(key)
                failures.extend(f.get('message','') for f in case.findall('failure'))
    require(len(cases) == expected, 'Unexpected JUnit count: '+mode)
    if red:
        require(len(failures) == 2 and all('direct lanes=4.0' in message and 'nothing was thrown' in message for message in failures),
                'Preserved red failures are not the exact direct-proof regression')
    suite_counts[mode] = len(cases)
    if mode in ('full-default','full-dense','first/full-default','first/full-dense'): signatures.append(cases)
require(signatures[0] == signatures[1], 'Full modes executed different cases')
require(signatures[2] == signatures[3] == signatures[0], 'First and final full modes executed different cases')
summary = dict(acceptedCaptureCount=16, nativeRows=9666, suiteCounts=suite_counts,
               unsignedLaneExtensions=extensions, packedStoreLanes=stores,
               physicalPackedMemoryInstructions=instructions, installedTargetComparisons=comparisons,
               rawGraphsRehashed=True, compactGraphContentsRehashed=True, junitArchivesMatchOriginals=True,
               directProofRedFailuresPreserved=True)
(retained/'package-verification.json').write_text(json.dumps(summary,indent=2)+'\n')
files = sorted(path for path in retained.rglob('*') if path.is_file())
manifest = ''.join(digest(path)+'  '+path.relative_to(retained).as_posix()+'\n' for path in files)
(retained/'SHA256SUMS').write_text(manifest)
print(json.dumps(dict(files=len(files),bytes=sum(path.stat().st_size for path in files),
                     manifestSha256=digest(retained/'SHA256SUMS'),verification=summary),indent=2))
