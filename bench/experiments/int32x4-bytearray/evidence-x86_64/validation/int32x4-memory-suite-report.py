#!/usr/bin/env python3
"""Check retained JUnit suites, native inputs and optional frozen graph runtime."""
import argparse
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET

def check(condition, message):
    if not condition:
        raise AssertionError(message)

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--validation', type=Path, required=True)
parser.add_argument('--expected-tests', type=int, required=True)
parser.add_argument('--expected-proof-tests', type=int, default=5)
parser.add_argument('--expected-native-tests', type=int, default=5)
parser.add_argument('--capture', type=Path)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
root = Path.cwd()
report = {'validation': str(args.validation), 'modes': {}}
signatures = []
for mode in ('full-default', 'full-dense'):
    folder = args.validation / mode
    check((folder / 'exit-status.txt').read_text().strip() == '0', mode + ' command failed')
    xmls = sorted((folder / 'test-results').glob('TEST-*.xml'))
    check(xmls, mode + ' has no XML evidence')
    signatures.append(set())
    tests = 0
    feature = {}
    for file in xmls:
        suite = ET.parse(file).getroot()
        check(all(int(suite.attrib.get(key, 0)) == 0 for key in ('failures', 'errors', 'skipped')),
              mode + ' nonpassing suite: ' + file.name)
        cases = suite.findall('testcase')
        check(len(cases) == int(suite.attrib['tests']), 'XML case count mismatch')
        for case in cases:
            check(not any(case.find(tag) is not None for tag in ('failure', 'error', 'skipped')), 'Nonpassing XML case')
            key = (case.attrib['classname'], case.attrib['name'])
            check(key not in signatures[-1], 'Duplicate JUnit case')
            signatures[-1].add(key)
        tests += len(cases)
        if suite.attrib['name'] in ('thc.runtime.SimdInt32ByteArrayTest', 'thc.runtime.Int32VectorMemoryProofTest',
                                   'thc.runtime.Int32VectorStorageTest'):
            feature[suite.attrib['name']] = len(cases)
    check(tests == args.expected_tests, mode + ': unexpected suite size ' + str(tests))
    check(len(feature) == 3 and feature['thc.runtime.Int32VectorMemoryProofTest'] == args.expected_proof_tests
          and feature['thc.runtime.Int32VectorStorageTest'] == 3
          and feature['thc.runtime.SimdInt32ByteArrayTest'] == args.expected_native_tests, 'Feature suite missing')
    report['modes'][mode] = {'tests': tests, 'suites': len(xmls), 'featureTests': feature,
                            'xml': [{'path': str(p), 'sha256': digest(p)} for p in xmls]}
check(signatures[0] == signatures[1], 'Default/dense executed different cases')
proof_path = root / 'build/simd-int32x4-bytearray/provenance.json'
proof = json.loads(proof_path.read_text())
check(proof['stages'] == ['pre', 'post'] and proof['nativeRows'] == proof['modelRows'] == 9666
      and proof['modelMatched'] is True, 'Native memory evidence missing')
toolchain = proof['toolchain']
for prefix in ('ghcBinary', 'ghcLauncher'):
    check(digest(Path(toolchain[prefix+'Path'])) == toolchain[prefix+'Sha256'], 'Pinned GHC tool changed: '+prefix)
for record in proof['sources'] + proof['artifacts']:
    check(digest(root / record['path']) == record['sha256'], 'Stale native input: ' + record['path'])
check((root / 'build/simd-int32x4-bytearray/expected.tsv').read_bytes() ==
      (root / 'build/simd-int32x4-bytearray/oracle.tsv').read_bytes(), 'Native/model TSV differs')
calls = sum(len(e['cases']) for e in proof['entries']) * 8
entries = sum(len(e['cases']) * proof['expectedGuestCallsByEntry'][e['name']] for e in proof['entries']) * 8
check((calls, entries) == (77328, 229536), 'Native strict execution counts changed')
report['native'] = {'provenance': {'path': str(proof_path), 'sha256': digest(proof_path)},
                    'sourceHashes': len(proof['sources']), 'artifactHashes': len(proof['artifacts']),
                    'ghcBinaryRehashed': True, 'ghcLauncherRehashed': True,
                    'rows': 9666, 'compiledCallsPerMode': calls, 'compiledEntriesPerMode': entries}
if args.capture:
    snapshot = json.loads((args.capture / 'runtime-snapshot.json').read_text())
    evidence = json.loads((args.capture / 'evidence.json').read_text())
    check(len(evidence['results']) == 16, 'Expected all sixteen selected memory captures')
    correction = evidence.get('checkerCorrection')
    corrected = {}
    if correction is not None:
        allowed = {str(root / 'bench/experiments/int32x4-bytearray' / name)
                   for name in ('runtime-audit.py', 'test-runtime-audit.py')}
        before = {r['path']: r['sha256'] for r in correction['originalCheckerSources']}
        corrected = {r['path']: r['sha256'] for r in correction['correctedCheckerSources']}
        check(set(before) == set(corrected) == allowed, 'Offline correction inventory changed')
        check(correction['guestExecutionRepeated'] is False, 'Reader correction repeated guest execution')
        originals = {r['path']: r['sha256'] for r in snapshot['sources']}
        for path, value in before.items():
            check(originals[path] == value and digest(args.capture / ('capture-'+Path(path).name)) == value,
                  'Original reader not preserved')
        for key in ('originalFailure', 'originalExitStatus'):
            record = correction[key]
            check(digest(Path(record['path'])) == record['sha256'], 'Original checker failure changed')
    for group in ('sources', 'runtimeJars', 'jdkFiles'):
        for record in snapshot[group]:
            expected = corrected.get(record['path'], record['sha256']) if group == 'sources' else record['sha256']
            check(digest(Path(record['path'])) == expected, 'Frozen runtime changed: ' + record['path'])
    report['frozenGraphRuntime'] = {'capture': str(args.capture), 'evidenceSha256': digest(args.capture / 'evidence.json'),
                                  'readerOnlyCorrection': correction,
                                  **{key: len(snapshot[key]) for key in ('sources', 'runtimeJars', 'jdkFiles')}}
check(not args.output.exists(), 'Refusing to overwrite report')
args.output.write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps({mode: {'tests': value['tests'], 'suites': value['suites']} for mode, value in report['modes'].items()}))
