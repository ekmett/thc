#!/usr/bin/env python3
"""Verify compact Float memory evidence against retained originals, then seal."""
import gzip
import hashlib
import json
from pathlib import Path
import re
import tarfile
import xml.etree.ElementTree as ET

retained = Path('bench/experiments/floatx4-bytearray/evidence-x86_64')
capture = Path('build/floatx4-bytearray-runtime-family-dispatch')

def require(condition, message):
    if not condition:
        raise AssertionError(message)

def sha(data): return hashlib.sha256(data).hexdigest()
def digest(path): return sha(path.read_bytes())
def unpacked(path):
    return path.read_bytes() if path.is_file() else gzip.decompress(Path(str(path) + '.gz').read_bytes())

require(not (retained / 'SHA256SUMS').exists(), 'Refusing to replace a sealed manifest')
require((retained / 'README.md').is_file(), 'Write evidence scope and limitations first')
for name in ('evidence.json', 'input-provenance.json', 'runtime-snapshot.json', 'graph-cases.json', 'oracle.tsv'):
    require(sha(unpacked(retained / name)) == digest(capture / name), 'Copied evidence changed: ' + name)
evidence = json.loads((retained / 'evidence.json').read_text())
require(len(evidence['results']) == 16, 'Expected sixteen corrected-runtime graphs')
correction = evidence['checkerCorrection']
require(correction is not None and correction['guestExecutionRepeated'] is False, 'Expected source-backed offline reader correction')
require((retained / 'check.exit-status.txt').read_text().strip() == '1' and
        (retained / 'recheck.exit-status.txt').read_text().strip() == '0', 'Reader failure/recheck status missing')
require('Residual private carrier/vector/payload reference: a!# long[]' in (retained / 'check.log').read_text(), 'Original metadata failure missing')
for key in ('originalFailure', 'originalExitStatus'):
    record = correction[key]
    require(digest(Path(record['path'])) == record['sha256'] == digest(retained / Path(record['path']).name), 'Original reader failure changed')
snapshot = json.loads((retained / 'runtime-snapshot.json').read_text())
corrected_sources = {r['path']: r['sha256'] for r in correction['correctedCheckerSources']}
require(len(corrected_sources) == 2 and {Path(p).name for p in corrected_sources} == {'runtime-audit.py', 'test-runtime-audit.py'}, 'Reader correction scope changed')
for group in ('sources', 'runtimeJars', 'jdkFiles'):
    for item in snapshot[group]:
        require(digest(Path(item['path'])) == corrected_sources.get(item['path'], item['sha256']), 'Corrected capture snapshot changed')
for item in correction['originalCheckerSources']:
    require(digest(retained / ('capture-' + Path(item['path']).name)) == item['sha256'], 'Original reader source changed')
require((retained / 'validation/revision.txt').read_text().strip() == evidence['sourceRevision'], 'Final suites and capture revisions differ')
products = sum(len(item['packedMemoryNode']['floatLaneProducts']) for item in evidence['results'])
stores = sum(len(item['packedMemoryNode']['packedStoreLanes']) for item in evidence['results'])
instructions = sum(len(item['physicalPackedInstructions']) for item in evidence['results'])
comparisons = sum(item['rows'] * item['compiledCorpusPasses'] for item in evidence['results'])
require((products, stores, instructions, comparisons) == (32, 32, 16, 1024), 'Graph totals changed')
for item in evidence['results']:
    label = f"{item['stage']}-{item['backend']}-{item['entry']}"
    folder = retained / label
    require(sha(unpacked(folder / 'graph.json')) == item['parsedGraph']['sha256'], 'Graph gzip changed')
    for key, name in (('log', 'run.log'), ('command', 'run.command.txt'), ('exitStatus', 'run.exit-status.txt'), ('lir', 'final-lir.txt')):
        require(sha(unpacked(folder / name)) == item[key]['sha256'], 'Copied graph artifact changed')
    for key in ('parsedGraph', 'rawGraph', 'rawLir'):
        require(digest(Path(item[key]['path'])) == item[key]['sha256'], 'Original graph artifact changed')
    require((folder / 'run.exit-status.txt').read_text().strip() == (folder / 'parse.exit-status.txt').read_text().strip() == '0',
            'Accepted capture command failed')
native = retained / 'native'
require(unpacked(native / 'expected.tsv') == unpacked(native / 'oracle.tsv'), 'Portable native/model bytes differ')
require(unpacked(native / 'snan-expected.tsv') == unpacked(native / 'snan-oracle.tsv'), 'Selected native sNaN observation differs')
native_root = Path('build/simd-floatx4-bytearray')
require(sha(unpacked(native / 'provenance.json')) == digest(native_root / 'provenance.json'), 'Native provenance changed')
for stage in ('pre', 'post'):
    require(sha(unpacked(retained / (stage + '-core.json'))) == digest(native_root / (stage + '-core/SimdFloatX4ByteArray.json')),
            'Core gzip changed')
preparation_archives = sorted(native.glob('prepare-run-*.tar.gz'))
require({p.name.removesuffix('.tar.gz') for p in preparation_archives} ==
        {p.name for p in native_root.glob('prepare-run-*') if p.is_dir()}, 'Incomplete preparation-run inventory')
require(bool(preparation_archives), 'Missing preparation-run history')
for archive_path in preparation_archives:
    original = native_root / archive_path.name.removesuffix('.tar.gz')
    copied = set()
    with tarfile.open(archive_path, 'r:gz') as archive:
        for member in archive.getmembers():
            require(member.isdir() or member.isfile(), 'Unexpected preparation archive link/type')
            if not member.isfile():
                continue
            name = Path(member.name)
            require(not name.is_absolute() and '..' not in name.parts, 'Unsafe preparation archive path')
            copied.add(name.as_posix())
            require(archive.extractfile(member).read() == (original / name).read_bytes(), 'Preparation archive bytes differ')
    require(copied == {p.relative_to(original).as_posix() for p in original.rglob('*') if p.is_file()}, 'Incomplete preparation archive')
counts, identities = {}, []
suite_modes = [('first-focused', 10), ('focused-default', 15), ('full-default', 530), ('full-dense', 530)]
suite_modes += [('before-family-dispatch/' + mode, count) for mode, count in suite_modes[1:]]
for mode, expected in suite_modes:
    if mode == 'first-focused':
        original = Path('build/floatx4-memory-focused-first-check')
    elif mode.startswith('before-family-dispatch/'):
        original = Path('build/floatx4-memory-validation') / mode.split('/')[1]
    else:
        original = Path('build/floatx4-memory-validation-family-dispatch') / mode
    folder = retained / 'validation' / mode
    require((folder / 'exit-status.txt').read_text().strip() == '0', 'Failed suite: ' + mode)
    cases, xml_names = set(), set()
    with tarfile.open(folder / 'junit.tar.gz', 'r:gz') as archive:
        for member in archive.getmembers():
            if not member.isfile():
                continue
            name = Path(member.name)
            require(len(name.parts) == 1 and name.name.startswith('TEST-') and name.suffix == '.xml', 'Unexpected JUnit member')
            data = archive.extractfile(member).read()
            require(data == (original / 'test-results' / name.name).read_bytes(), 'JUnit archive differs')
            xml_names.add(name.name)
            suite = ET.fromstring(data)
            require(all(int(suite.get(key, 0)) == 0 for key in ('failures', 'errors', 'skipped')), 'Nonpassing suite')
            require(len(suite.findall('testcase')) == int(suite.get('tests')), 'JUnit count mismatch')
            for case in suite.findall('testcase'):
                key = (case.get('classname'), case.get('name'))
                require(key not in cases and not any(case.find(tag) is not None for tag in ('failure', 'error', 'skipped')), 'Invalid JUnit case')
                cases.add(key)
    require(xml_names == {p.name for p in (original / 'test-results').glob('TEST-*.xml')}, 'Incomplete JUnit archive')
    require(len(cases) == expected, 'Unexpected JUnit count: ' + mode)
    counts[mode] = len(cases)
    if mode.split('/')[-1].startswith('full-'):
        identities.append(cases)
require(all(cases == identities[0] for cases in identities), 'Full modes/checkpoints executed different cases')
python_counts = {}
for label, folder, original, revision in (
        ('final', retained / 'python-checks', Path('build/floatx4-memory-python-final-reader'), correction['checkerRevision']),
        ('before-family-dispatch', retained / 'validation/before-family-dispatch/python-checks',
         Path('build/floatx4-memory-python'), '914ed462f0c7e5ce63a59e337957750e42eea5f8')):
    require((folder / 'source-revision.txt').read_text().strip() == revision, 'Python revision differs')
    python_counts[label] = {}
    for mode in ('normal', 'optimized'):
        statuses = list(folder.glob(mode + '-*.exit-status.txt'))
        require(len(statuses) == 11, 'Missing Python check')
        total = 0
        for status in statuses:
            require(status.read_text().strip() == '0', 'Failed Python check')
            log = folder / status.name.replace('.exit-status.txt', '.output.log')
            text = log.read_text()
            found = re.findall(r'^Ran ([0-9]+) tests? in ', text, re.MULTILINE)
            require(len(found) == 1 and re.search(r'^OK$', text, re.MULTILINE) and
                    not re.search(r'skipped|expected failures|unexpected successes', text, re.IGNORECASE), 'Nonpassing Python evidence')
            total += int(found[0])
            for path in (status, log, folder / status.name.replace('.exit-status.txt', '.command.txt')):
                require(path.read_bytes() == (original / path.name).read_bytes(), 'Copied Python evidence differs')
        require(total == (220 if label == 'final' else 217), 'Python count changed')
        python_counts[label][mode] = total
bootstrap = retained / 'validation/sandbox-bootstrap-failure'
require((bootstrap / 'exit-status.txt').read_text().strip() == '1' and
        'Could not determine a usable wildcard IP' in (bootstrap / 'output.log').read_text(), 'First sandbox failure missing')
require((retained / 'validation/first-proof-check/test-core-float-vector-memory.py').is_file(), 'First malformed-test source missing')
first = retained / 'first-runtime-capture'
require(digest(first / 'SHA256SUMS') == 'c1f1a52d1e374ab76383e5cb8ec0d226a90f642909fd264425c8b2bb3dd5a940', 'First graph-failure seal changed')
for line in (first / 'SHA256SUMS').read_text().splitlines():
    expected, name = line.split('  ', 1)
    require(digest(first / name) == expected, 'First graph-failure artifact changed: ' + name)
failure = json.loads((first / 'failure-summary.json').read_text())
require(failure['source'] == '914ed462f0c7e5ce63a59e337957750e42eea5f8' and
        len(failure['results']) == 16 and failure['guestExecutionRepeated'] is False, 'First graph-failure attribution changed')
for item in failure['results']:
    require(item['strictGraphAndLir'] == ('PASS' if '-bytecode-' in item['label'] else 'FAIL'), 'First graph-failure result changed')
    for key, compact in (('graph', 'graph.json'), ('rawLir', 'raw-lir.cfg')):
        require(sha(unpacked(first / item['label'] / compact)) == item[key]['sha256'], 'First graph-failure gzip differs')
    for key in ('graph', 'rawLir', 'rawGraph'):
        require(digest(Path(item[key]['path'])) == item[key]['sha256'], 'First raw graph-failure evidence changed')
for item in failure['originalSourceAndJarCopies']:
    require(digest(Path(item['copy']['path'])) == item['copy']['sha256'] == item['original']['sha256'], 'Original runtime copy changed')
regressions = {}
for family in ('int32x4', 'word32x4'):
    folder = retained / ('regression-' + family)
    original = Path('build/' + family + '-bytearray-float-family-regression')
    data = json.loads((folder / 'evidence.json').read_text())
    require(len(data['results']) == 16 and data['checkerCorrection'] is None, 'Integer regression campaign incomplete')
    require(data['sourceRevision'] == evidence['sourceRevision'], 'Regression runtime revision differs')
    for name in ('evidence.json', 'input-provenance.json', 'runtime-snapshot.json', 'graph-cases.json', 'oracle.tsv'):
        require(sha(unpacked(folder / name)) == digest(original / name), 'Integer regression copy differs')
    snapshot = json.loads((folder / 'runtime-snapshot.json').read_text())
    for group in ('sources', 'runtimeJars', 'jdkFiles'):
        for item in snapshot[group]:
            require(digest(Path(item['path'])) == item['sha256'], 'Integer regression runtime changed')
    for result in data['results']:
        label = f"{result['stage']}-{result['backend']}-{result['entry']}"
        for key, name in (('parsedGraph', 'graph.json'), ('lir', 'final-lir.txt'), ('log', 'run.log'),
                          ('command', 'run.command.txt'), ('exitStatus', 'run.exit-status.txt')):
            require(sha(unpacked(folder / label / name)) == result[key]['sha256'], 'Integer regression compact artifact differs')
        for key in ('parsedGraph', 'rawGraph', 'rawLir'):
            require(digest(Path(result[key]['path'])) == result[key]['sha256'], 'Integer raw capture changed')
    module = 'SimdInt32X4ByteArray' if family == 'int32x4' else 'SimdWord32X4ByteArray'
    for stage in ('pre', 'post'):
        require(sha(unpacked(folder / (stage + '-core.json'))) ==
                digest(Path('build/simd-' + family + '-bytearray') / (stage + '-core') / (module + '.json')),
                'Integer regression Core differs')
    regressions[family] = dict(captures=len(data['results']),
        installedTargetComparisons=sum(r['rows'] * r['compiledCorpusPasses'] for r in data['results']),
        physicalPackedMemoryInstructions=sum(len(r['physicalPackedInstructions']) for r in data['results']))
    require(regressions[family] == dict(captures=16, installedTargetComparisons=1152, physicalPackedMemoryInstructions=16),
            'Unexpected integer regression totals')
summary = dict(acceptedCaptureCount=16, nativeRows=6720, selectedNativeSignalingNaNRows=640,
               signalingNaNClaim='Selected pinned-host native observations only; not portable JVM corpus',
               suiteCounts=counts, floatLaneProducts=products, packedStoreLanes=stores,
               physicalPackedMemoryInstructions=instructions, installedTargetComparisons=comparisons,
               rawGraphsRehashed=True, compactGraphContentsRehashed=True, junitArchivesMatchOriginals=True,
               preparationArchivesMatchOriginals=True, preparationRunArchives=len(preparation_archives),
               firstEnvironmentAndTestConstructionFailuresPreserved=True,
               firstGenuineAstDispatchFailuresPreserved=8, firstBytecodeGraphAndLirPasses=8,
               correctedRuntime=evidence['sourceRevision'], offlineReaderCorrection=correction['checkerRevision'],
               noReaderCorrectionGuestReplay=True, integerFamilyRegressions=regressions,
               pythonTests=python_counts)
(retained / 'package-verification.json').write_text(json.dumps(summary, indent=2) + '\n')
files = sorted(path for path in retained.rglob('*') if path.is_file())
(retained / 'SHA256SUMS').write_text(''.join(digest(path) + '  ' + path.relative_to(retained).as_posix() + '\n' for path in files))
print(json.dumps(dict(files=len(files), bytes=sum(path.stat().st_size for path in files), manifestSha256=digest(retained / 'SHA256SUMS'), verification=summary), indent=2))
