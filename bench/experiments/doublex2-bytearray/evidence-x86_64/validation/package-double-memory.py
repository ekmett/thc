#!/usr/bin/env python3
"""Copy and verify the completed native/JUnit/packed-memory checkpoint."""
import gzip
import hashlib
import io
import json
from pathlib import Path
import re
import shutil
import subprocess
import tarfile
import xml.etree.ElementTree as ET

ROOT = Path.cwd()
OUT = ROOT / 'bench/experiments/doublex2-bytearray/evidence-x86_64'
VALIDATION = ROOT / 'build/double-memory-validation'
SOURCE = 'b6ba65f3ba54f937e2b88f61f04e7e4f425a1e5c'
NATIVE = ROOT / 'build/simd-doublex2-bytearray'

def check(condition, message):
    if not condition:
        raise AssertionError(message)

def sha(data):
    return hashlib.sha256(data).hexdigest()

def digest(path):
    return sha(path.read_bytes())

check(not OUT.exists(), 'Refusing to reuse evidence directory')
check((ROOT / 'build/double-memory-evidence-README.md').is_file(), 'Write exact scope first')
check('PENDING' not in (ROOT / 'build/double-memory-evidence-README.md').read_text(), 'Finish evidence claims first')
check((VALIDATION / 'revision.txt').read_text().strip() == SOURCE, 'Wrong validated source')
subprocess.run(['git', 'diff', '--exit-code', SOURCE, '--', 'src/main', 'build.gradle.kts'], check=True)
for name in ('fresh-all', 'python', 'full-default', 'full-dense', 'double-graphs',
             'signed-graphs', 'unsigned-graphs', 'float-graphs'):
    check((VALIDATION / name / 'exit-status.txt').read_text().strip() == '0', name)
OUT.mkdir(parents=True)
copies, archives = [], []

def copy(source, target, compressed=False):
    data = source.read_bytes()
    destination = OUT / target
    destination.parent.mkdir(parents=True, exist_ok=True)
    check(not destination.exists(), 'Duplicate copy target: ' + str(target))
    destination.write_bytes(gzip.compress(data, mtime=0) if compressed else data)
    decoded = gzip.decompress(destination.read_bytes()) if compressed else destination.read_bytes()
    check(decoded == data, 'Copied bytes differ: ' + str(source))
    copies.append(dict(source=str(source), path=str(target), sourceSha256=sha(data), gzip=compressed))

def archive(files, target, base):
    files = sorted(files)
    destination = OUT / target
    destination.parent.mkdir(parents=True, exist_ok=True)
    check(files and not destination.exists(), 'Missing files or duplicate archive')
    with destination.open('wb') as stream, gzip.GzipFile(fileobj=stream, mode='wb', mtime=0) as compressed:
        with tarfile.open(fileobj=compressed, mode='w') as tar:
            for file in files:
                check(file.is_file() and not file.is_symlink(), 'Only regular archive members allowed')
                data = file.read_bytes()
                info = tarfile.TarInfo(file.relative_to(base).as_posix())
                info.size = len(data); info.mode = 0o644
                tar.addfile(info, io.BytesIO(data))
    records = []
    with tarfile.open(destination, 'r:gz') as tar:
        for member in tar:
            name = Path(member.name)
            check(member.isfile() and not name.is_absolute() and '..' not in name.parts, 'Unsafe archive member')
            data = tar.extractfile(member).read()
            check(data == (base / name).read_bytes(), 'Archive bytes differ')
            records.append(dict(path=member.name, sha256=sha(data)))
    check({r['path'] for r in records} == {p.relative_to(base).as_posix() for p in files}, 'Incomplete archive')
    archives.append(dict(path=str(target), originalBase=str(base), members=records))

family_results = {}
for family, path, comparisons, lane_field, lane_count in (
    ('doublex2', 'doublex2-bytearray-runtime', 512, 'doubleLaneProducts', 16),
    ('int32x4', 'int32x4-bytearray-double-family-regression', 1152, None, None),
    ('word32x4', 'word32x4-bytearray-double-family-regression', 1152, None, None),
    ('floatx4', 'floatx4-bytearray-double-family-regression', 1024, 'floatLaneProducts', 32),
):
    capture = ROOT / 'build' / path
    target = Path('captures') / family
    evidence = json.loads((capture / 'evidence.json').read_text())
    check(evidence['sourceRevision'] == SOURCE and len(evidence['results']) == 16, 'Capture revision/count')
    check(evidence['checkerCorrection'] is None, 'Handle a real reader correction explicitly, never discard it')
    snapshot = json.loads((capture / 'runtime-snapshot.json').read_text())
    check(snapshot['sourceRevision'] == SOURCE, 'Snapshot revision changed')
    for group in ('sources', 'runtimeJars', 'jdkFiles'):
        check(snapshot[group] == evidence[group], 'Evidence/snapshot inventory differs')
        for record in snapshot[group]:
            check(digest(Path(record['path'])) == record['sha256'], 'Frozen capture input changed')
            if group == 'sources':
                relative = Path(record['path']).relative_to(ROOT).as_posix()
                committed = subprocess.check_output(['git', 'show', SOURCE + ':' + relative])
                check(sha(committed) == record['sha256'], 'Source differs from frozen Git blob')
    for key, filename in (('inputProvenance', 'input-provenance.json'),
                          ('jdkRelease', 'jdk-release.txt'), ('javaVersion', 'java-version.txt')):
        record = evidence[key]
        check(Path(record['path']) == capture / filename and digest(capture / filename) == record['sha256'],
              'Frozen capture metadata changed')
    inputs = json.loads((capture / 'input-provenance.json').read_text())
    original = inputs['originalProvenance']
    check(digest(Path(original['path'])) == original['sha256'], 'Native provenance changed')
    check(json.loads(Path(original['path']).read_text()) == inputs['core'], 'Captured native provenance differs')
    for record in inputs['core']['sources'] + inputs['core']['artifacts']:
        check(digest(ROOT / record['path']) == record['sha256'], 'Family native source/artifact changed')
    native_folder = ROOT / ('build/simd-' + family + '-bytearray')
    check((capture / 'oracle.tsv').read_bytes() == (native_folder / 'oracle.tsv').read_bytes(), 'Copied oracle changed')
    check(json.loads((capture / 'graph-cases.json').read_text()) == inputs['core']['graphEntries'], 'Copied graph cases changed')
    check((capture / 'stages.txt').read_text() == 'pre\npost\n', 'Captured stages changed')
    for name in ('evidence.json', 'runtime-snapshot.json', 'java-version.txt', 'jdk-release.txt',
                 'architecture.txt', 'stages.txt', 'capture-runtime-audit.py', 'capture-test-runtime-audit.py'):
        copy(capture / name, target / name)
    for name in ('input-provenance.json', 'oracle.tsv', 'graph-cases.json'):
        copy(capture / name, target / (name + '.gz'), True)
    for step in ('prepare', 'javac-probe', 'javac-reader', 'check'):
        check((capture / (step + '.exit-status.txt')).read_text().strip() == '0', step)
        for suffix in ('command.txt', 'log', 'exit-status.txt'):
            copy(capture / (step + '.' + suffix), target / (step + '.' + suffix))
    module = {'doublex2': 'SimdDoubleX2ByteArray', 'floatx4': 'SimdFloatX4ByteArray',
              'int32x4': 'SimdInt32X4ByteArray', 'word32x4': 'SimdWord32X4ByteArray'}[family]
    for stage in ('pre', 'post'):
        copy(ROOT / f'build/simd-{family}-bytearray/{stage}-core/{module}.json', target / (stage + '-core.json.gz'), True)
    for result in evidence['results']:
        label = f"{result['stage']}-{result['backend']}-{result['entry']}"
        for key in ('parsedGraph', 'rawGraph', 'rawLir', 'lir', 'log', 'command', 'exitStatus'):
            check(digest(Path(result[key]['path'])) == result[key]['sha256'], 'Original graph chain changed')
        for step in ('run', 'parse'):
            check((capture / label / (step + '.exit-status.txt')).read_text().strip() == '0', label + '/' + step)
            for suffix in ('command.txt', 'log', 'exit-status.txt'):
                copy(capture / label / (step + '.' + suffix), target / label / (step + '.' + suffix))
        copy(Path(result['parsedGraph']['path']), target / label / 'graph.json.gz', True)
        copy(Path(result['lir']['path']), target / label / 'final-lir.txt.gz', True)
    measured = sum(r['rows'] * r['compiledCorpusPasses'] for r in evidence['results'])
    instructions = sum(len(r['physicalPackedInstructions']) for r in evidence['results'])
    check(measured == comparisons and instructions == 16, 'Capture totals')
    summary = dict(captures=16, installedTargetComparisons=measured, physicalInstructions=instructions,
                   evidenceSha256=digest(capture / 'evidence.json'),
                   snapshotRecords={g: len(snapshot[g]) for g in ('sources', 'runtimeJars', 'jdkFiles')})
    if lane_field:
        loads = sum(len(r['packedMemoryNode'][lane_field]) for r in evidence['results'])
        stores = sum(len(r['packedMemoryNode']['packedStoreLanes']) for r in evidence['results'])
        check(loads == stores == lane_count, 'Floating lane proof totals')
        summary.update(loadLaneProducts=loads, storeLaneInputs=stores)
    family_results[family] = summary

proof = json.loads((NATIVE / 'provenance.json').read_text())
check(proof['nativeRows'] == proof['modelRows'] == 4384 and proof['modelMatched'] is True, 'Native rows')
check(proof['nativeDiagnostics']['rows'] == 576 and proof['nativeDiagnostics']['matches'] is True, 'Host-only diagnostic rows')
check((NATIVE / 'expected.tsv').read_bytes() == (NATIVE / 'oracle.tsv').read_bytes(), 'Native/model bytes')
check((NATIVE / 'snan-expected.tsv').read_bytes() == (NATIVE / 'snan-oracle.tsv').read_bytes(), 'Native diagnostic bytes')
for record in proof['sources'] + proof['artifacts']:
    check(digest(ROOT / record['path']) == record['sha256'], 'Native input changed')
for prefix in ('ghcBinary', 'ghcLauncher'):
    check(digest(Path(proof['toolchain'][prefix+'Path'])) == proof['toolchain'][prefix+'Sha256'], 'GHC changed')
for name in ('provenance.json', 'expected.tsv', 'oracle.tsv', 'snan-expected.tsv', 'snan-oracle.tsv', 'pre-audit.json', 'post-audit.json'):
    copy(NATIVE / name, Path('native') / (name + '.gz'), True)
for run in sorted(NATIVE.glob('prepare-run-*')):
    archive([p for p in run.rglob('*') if p.is_file()], Path('native') / (run.name + '.tar.gz'), run)

suite_counts, identities = {}, []
for label, source, expected in (
    ('first-focused', ROOT / 'build/double-memory-first-focused', 10),
    ('native-focused', ROOT / 'build/double-memory-native-focused/focused', 15),
    ('full-default', VALIDATION / 'full-default', 545),
    ('full-dense', VALIDATION / 'full-dense', 545),
):
    check((source / 'exit-status.txt').read_text().strip() == '0', label)
    cases = set(); xmls = sorted((source / 'test-results').glob('TEST-*.xml'))
    for file in xmls:
        suite = ET.parse(file).getroot()
        check(all(suite.get(k) == '0' for k in ('failures', 'errors', 'skipped')), 'Nonpassing JUnit suite')
        check(len(suite.findall('testcase')) == int(suite.get('tests')), 'XML count mismatch')
        for case in suite.findall('testcase'):
            key = (case.get('classname'), case.get('name'))
            check(key not in cases and not any(case.find(k) is not None for k in ('failure', 'error', 'skipped')), 'Nonpassing/duplicate JUnit case')
            cases.add(key)
    check(len(cases) == expected, 'Unexpected JUnit total')
    suite_counts[label] = dict(tests=expected, suites=len(xmls))
    if label.startswith('full-'): identities.append(cases)
    for file in source.iterdir():
        if file.is_file(): copy(file, Path('validation') / label / file.name)
    archive(xmls, Path('validation') / label / 'junit.tar.gz', source / 'test-results')
check(identities[0] == identities[1], 'Full modes ran different cases')
for folder, target in ((VALIDATION, Path('validation/stages')),
                       (ROOT / 'build/double-memory-native-focused', Path('validation/native-focused-stages')),
                       (ROOT / 'build/double-memory-first-proof-check', Path('validation/initial-python')),
                       (ROOT / 'build/double-memory-python-final', Path('python-checks'))):
    for file in sorted(folder.rglob('*')):
        if file.is_file() and 'test-results' not in file.parts:
            copy(file, target / file.relative_to(folder))
python_counts = {}
for mode in ('normal', 'optimized'):
    statuses = sorted((ROOT / 'build/double-memory-python-final').glob(mode + '-*.exit-status.txt'))
    check(len(statuses) == 14, 'Python inventory')
    total = 0
    for status in statuses:
        text = status.with_name(status.name.replace('.exit-status.txt', '.output.log')).read_text()
        check(status.read_text().strip() == '0' and re.search(r'^OK$', text, re.M) and not re.search('skipped|expected failures|unexpected successes', text, re.I), 'Nonpassing Python check')
        counts = re.findall(r'^Ran (\d+) tests? in ', text, re.M)
        check(len(counts) == 1, 'Python count')
        total += int(counts[0])
    check(total == 294, 'Python total')
    python_counts[mode] = total
for name in ('check-double-memory-focused.sh', 'check-double-memory-native-focused.sh',
             'check-double-memory-proofs.sh', 'check-double-memory-python.sh', 'validate-double-memory.sh',
             'double-memory-suite-report.py', 'double-memory-suite-report.json', 'package-double-memory.py'):
    copy(ROOT / 'build' / name, Path('validation') / name)
first = Path('/home/ekmett/ai/thc-doublex2-bytearray-graphs-01a0cdeb/build/doublex2-harness-initial-parser-failure.txt')
copy(first, Path('validation/initial-reader-test-failure-reconstruction.txt'))
review = Path('/home/ekmett/ai/handoffs/01a0cdeb-4ff9-74e2-83d4-8745c8ff0c3a/20260924T030327Z-doublex2-bytearray-input-review.md')
copy(review, Path('validation/independent-input-review.md'))
copy(ROOT / 'build/double-memory-evidence-README.md', Path('README.md'))
summary = dict(sourceRevision=SOURCE, nativeRows=4384, selectedHostOnlySnanRows=576,
               selectedNativeSourceHashes=len(proof['sources']), nativeArtifactHashes=len(proof['artifacts']),
               nativeProvenanceSha256=digest(NATIVE / 'provenance.json'), suites=suite_counts,
               compiledCallsPerMode=35072, exactCompiledGuestEntriesPerMode=104704, coldTransitionsPerMode=96,
               pythonTests=python_counts, families=family_results, copiedFiles=len(copies),
               verifiedArchives=len(archives), rawGraphsRehashed=True, archiveContentsReverified=True,
               firstReaderTestFailure='Explicit reconstruction of stale inherited test expectations; reader unchanged by correction.')
(OUT / 'package-verification.json').write_text(json.dumps(summary, indent=2) + '\n')
(OUT / 'copy-records.json').write_text(json.dumps(dict(copies=copies, archives=archives), indent=2) + '\n')
files = sorted(p for p in OUT.rglob('*') if p.is_file())
(OUT / 'SHA256SUMS').write_text(''.join(digest(p) + '  ' + p.relative_to(OUT).as_posix() + '\n' for p in files))
print(json.dumps(dict(summary=summary, coveredFiles=len(files), coveredBytes=sum(p.stat().st_size for p in files),
                     manifestSha256=digest(OUT / 'SHA256SUMS')), indent=2))
