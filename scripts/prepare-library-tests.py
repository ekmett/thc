#!/usr/bin/env python3
"""Export real containers workloads, preserve strict gaps, and verify native oracles."""
import argparse
from collections import Counter
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tarfile
import urllib.request
from sequence_model import ENTRIES as SEQUENCE_ENTRIES, sequence_model

ROOT = Path(__file__).resolve().parent.parent
BUILD = ROOT / 'build/libraries'
CONTAINERS_URL = 'https://hackage.haskell.org/package/containers-0.8/containers-0.8.tar.gz'
CONTAINERS_SHA = 'b1c1127ff57b6f844d0b30cea54a62c01ca146a49ed4953485be1af389a94bd8'
WORD_MASK = (1 << 64) - 1

# These are cold ghc-internal exception/state paths, not collection tuple
# results. Exact owners, details and multiplicities make new gaps fail closed.
SET_EXCEPTION = 'ghc-internal:GHC.Internal.Exception.errorCallWithCallStackException'
SET_BACKTRACE = 'ghc-internal:GHC.Internal.Exception.Backtrace.collectExceptionAnnotation1'
SET_FRONTIER_ISSUES = Counter({
    ('constructor-kind', SET_EXCEPTION, 'ghc-internal:GHC.Internal.Types.(#,#): unboxed-tuple'): 1,
    ('constructor-field-representation', SET_EXCEPTION, 'ghc-internal:GHC.Internal.Types.(#,#)[0]: None'): 1,
    ('constructor-field-representation', SET_EXCEPTION, 'ghc-internal:GHC.Internal.Types.(#,#)[1]: None'): 1,
    ('aggregate-representation', SET_EXCEPTION + '_$stoExceptionWithBacktrace', 'unboxed-tuple: unsupported component'): 6,
    ('aggregate-representation', SET_BACKTRACE, 'unboxed-tuple: unsupported component'): 7,
    ('unsupported-primitive', SET_BACKTRACE, 'readMutVar#'): 1,
})
SET_FRONTIER_MISSING = {
    'ghc-internal:GHC.Internal.Exception.$fExceptionErrorCall_$ctoException',
    'ghc-internal:GHC.Internal.Exception.Backtrace.collectExceptionAnnotationMechanismRef',
    'ghc-internal:GHC.Internal.Stack.withFrozenCallStack1',
}


def audit_structure_violations(group, audit):
    """Keep preparation and reuse checks on the same explicit coverage contract."""
    violations = []
    name = group['id']
    if audit['accepted'] != (group['execution'] == 'supported'):
        violations.append(name + ': declared support disagrees with the strict audit')
    primitives = {primitive['name'] for primitive in audit['primitives']}
    if name in ['intmap', 'intmap-primops'] and not {'clz#', 'ltWord#'} <= primitives:
        violations.append(name + ': required word primitives disappeared from reachable Core')
    if name in ['intset', 'intset-primops'] and not {'popCnt#', 'ctz#', 'leWord#'} <= primitives:
        violations.append(name + ': required IntSet word primitives disappeared from reachable Core')
    if name == 'set':
        if group['execution'] != 'frontier' or audit['accepted']:
            violations.append('set: remains an explicit strict frontier; native rows are not supported execution')
        actual = Counter((issue['code'], issue['owner'], issue['detail'] if isinstance(issue['detail'], str)
                          else json.dumps(issue['detail'], sort_keys=True)) for issue in audit['issues'])
        if actual != SET_FRONTIER_ISSUES:
            added, removed = actual - SET_FRONTIER_ISSUES, SET_FRONTIER_ISSUES - actual
            violations.append(f'set: exception/state frontier changed; review coverage (added={dict(added)}, removed={dict(removed)})')
        missing = [item['id'] for item in audit['missingGlobals']]
        if set(missing) != SET_FRONTIER_MISSING or len(missing) != len(SET_FRONTIER_MISSING):
            violations.append('set: exception/backtrace missing-definition frontier changed; review coverage')
        if audit['roots'] != ['main:THC.SetWorkload.setAggregate']:
            violations.append('set: expected workload root changed; review coverage')
        if 'reallyUnsafePtrEquality#' not in primitives:
            violations.append('set: required pointer-identity primitive disappeared from reachable Core')
    return violations


def check_existing(manifest):
    """Reaudit recorded, fingerprinted exports without rebuilding or running guest code."""
    cases = json.loads(manifest.read_text())
    spec = importlib.util.spec_from_file_location('audit_core', ROOT / 'scripts/audit-core.py')
    auditor = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(auditor)
    capabilities = json.loads((ROOT / 'scripts/core-capabilities.json').read_text())
    violations = []
    for group in cases['groups']:
        modules = []
        for filename in group['modules']:
            path = Path(filename)
            if cases['artifactHashes'].get(filename) != digest(path):
                raise RuntimeError('Stale library export: ' + filename)
            modules.append((filename, json.loads(path.read_text())))
        audit = auditor.Audit(modules, capabilities).run([entry['name'] for entry in group['entries']])
        violations.extend(audit_structure_violations(group, audit))
        print(json.dumps(dict(group=group['id'], execution=group['execution'], accepted=audit['accepted'], **audit['summary'])))
    if violations:
        raise RuntimeError('\n'.join(violations))


def signed(value):
    return ((value + (1 << 63)) & WORD_MASK) - (1 << 63)


def set_model(value):
    n = max(0, value)
    key = lambda i: ((17 * i + 11) & 63) - 32
    other_key = lambda i: ((13 * i + 7) & 63) - 24
    initial = {key(i) for i in range(n)} | {key(i // 2) for i in range(n)}
    deleted = initial - {key(3 * i) for i in range(n // 3)} - {96 + i for i in range(n // 3)}
    other = {other_key(i) for i in range(n // 2)}

    def ordered(items):
        acc = 0
        for key in sorted(items):
            acc = (acc * 33 + key + 65) & 65535
        return acc

    queried = sum((i + 1 if key(i) in deleted else -(i + 1)) +
                  (97 if 128 + i in deleted else 3) for i in range(n))
    return signed(ordered(deleted | other) + 3 * ordered(deleted & other) +
                  5 * ordered(deleted - other) + 7 * len(deleted) +
                  11 * len(deleted | other) + 13 * queried)


def primitive_model(entry, value):
    word = value & WORD_MASK
    if entry == 'countLeadingZeros':
        return 64 - word.bit_length()
    if entry == 'populationCount':
        return word.bit_count()
    if entry == 'countTrailingZeros':
        return (word & -word).bit_length() - 1 if word else 64
    inclusive_limits = {'unsignedLessEqualZero': 0, 'unsignedLessEqualMaxSigned': (1 << 63) - 1,
                        'unsignedLessEqualSignBit': 1 << 63, 'unsignedLessEqualAllOnes': WORD_MASK}
    if entry in inclusive_limits:
        return int(word <= inclusive_limits[entry])
    limits = {'unsignedLessThanZero': 0, 'unsignedLessThanMaxSigned': (1 << 63) - 1,
              'unsignedLessThanSignBit': 1 << 63, 'unsignedLessThanAllOnes': WORD_MASK}
    return int(word < limits[entry])


def intmap_model(value):
    n = max(0, value)
    key = lambda i: ((i * 37 + 11) & 127) - 64
    counts = {} if n == 0 else {-(1 << 63): 3, -1: 5, 0: 7, (1 << 63) - 1: 11}
    for i in range(n):
        k = key(i)
        counts[k] = counts.get(k, 0) + (i & 7) + 1
    for i in range(n // 4):
        k = key(3 * i)
        if k in counts:
            counts[k] += 7
    counts.pop(-(1 << 63) if n & 1 == 0 else (1 << 63) - 1, None)
    for i in range(n // 5):
        counts.pop(key(5 * i), None)
        counts.pop(2048 + i, None)
    queried = 0
    for i in range(n):
        k = ((i * 29 + 7) & 255) - 128
        queried += counts.get(k, -13) + (i + 1 if k in counts else -(i + 1))
    edges = sum(counts.get(k, -19) for k in [-(1 << 63), -1, 0, (1 << 63) - 1])
    ordered = 0
    for k in sorted(counts):
        ordered = (ordered * 33 + (k & 65535) + 3 * counts[k]) & 2147483647
    return signed(ordered + 17 * queried + 23 * edges + 31 * len(counts))


def intset_model(value):
    n = max(0, value)
    key = lambda i: ((37 * i + 11) & 1023) - 512
    other_key = lambda i: ((53 * i + 7) & 2047) - 1024
    edges = [-(1 << 63), -65, -64, -1, 0, 63, 64, (1 << 63) - 1]
    seeded = set(edges) if n else set()
    inserted = seeded | {key(i) for i in range(n)} | {key(i // 3) for i in range(n)}
    deleted = inserted - {key(3 * i) for i in range(n // 4)} - {4096 + i for i in range(n // 4)}
    other = {(-(1 << 63) if n & 1 == 0 else (1 << 63) - 1)} if n else set()
    other |= {other_key(i) for i in range(n // 2)}
    united, shared, remaining = deleted | other, deleted & other, deleted - other

    def ordered(items):
        acc = 0
        for k in sorted(items):
            acc = (acc * 33 + (k & 65535) + 1) & 2147483647
        return acc

    queried = sum(i + 1 if ((97 * i + 13) & 2047) - 1024 in deleted else -(i + 1)
                  for i in range(n))
    edge_membership = 0
    for k in edges:
        edge_membership = edge_membership * 3 + int(k in remaining)
    return signed(ordered(united) + 3 * ordered(shared) + 5 * ordered(remaining) +
                  7 * queried + 11 * len(deleted) + 13 * len(united) + 17 * len(shared) +
                  19 * len(remaining) + 23 * edge_membership)


def run(argv, **kwargs):
    print('+', ' '.join(map(str, argv)), flush=True)
    return subprocess.run(list(map(str, argv)), cwd=ROOT, check=True, **kwargs)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2) + '\n')


def require_sequence_tuple_frontier(audit):
    # Result tuples are supported on this baseline. Preserve the actual
    # unsupported positions, not the earlier blanket representation rejection.
    expected = {'unboxed-tuple argument', 'unboxed-tuple formal argument'}
    if not any(issue['code'] == 'aggregate-boundary' and issue['detail'] in expected
               for issue in audit['issues']):
        raise RuntimeError('expected aggregate frontier changed; review coverage')


def verified_containers():
    archive = ROOT / 'vendor/archives/containers-0.8.tar.gz'
    archive.parent.mkdir(parents=True, exist_ok=True)
    if not archive.exists():
        with urllib.request.urlopen(CONTAINERS_URL) as response:
            archive.write_bytes(response.read())
    if digest(archive) != CONTAINERS_SHA:
        raise RuntimeError('Pinned containers archive SHA256 mismatch')
    source_root = ROOT / 'vendor/containers-0.8'
    with tarfile.open(archive) as source:
        if not source_root.exists():
            source.extractall(ROOT / 'vendor', filter='data')
        for member in source.getmembers():
            if member.isfile() and (ROOT / 'vendor' / member.name).read_bytes() != source.extractfile(member).read():
                raise RuntimeError('Vendored source differs from pinned archive: ' + member.name)
    return source_root


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check-existing', type=Path, metavar='CASES_JSON',
                        help='Reaudit fingerprinted exported modules only; no regeneration or execution')
    args = parser.parse_args()
    if args.check_existing:
        check_existing(args.check_existing)
        return
    ghc, ghc_pkg = os.environ.get('GHC', 'ghc'), os.environ.get('GHC_PKG', 'ghc-pkg')
    if subprocess.check_output([ghc, '--numeric-version'], text=True).strip() != '9.14.1':
        raise RuntimeError('THC requires GHC 9.14.1')
    if subprocess.check_output([ghc_pkg, '--version'], text=True).strip() != 'GHC package manager version 9.14.1':
        raise RuntimeError('THC requires ghc-pkg 9.14.1')
    if subprocess.check_output([ghc_pkg, 'field', 'containers', 'version', '--simple-output'], text=True).strip() != '0.8':
        raise RuntimeError('Library source comparison requires installed containers-0.8')
    containers = verified_containers()
    BUILD.mkdir(parents=True, exist_ok=True)
    run(['compiler/build.sh'])
    run([sys.executable, 'compiler/export-boot.py', '--build-dir', BUILD / 'boot'])
    boot = [BUILD / 'boot/core' / (name + '.json') for name in
            ['GHC.Internal.CString', 'GHC.Internal.Err', 'GHC.InterfaceClosure']]
    warm = [1, 8, 64]
    cold = [-3, 0, 2, 3, 7, 15, 16, 31, 63, 65, 127, 128, 129, 255, 256, 257, 1024, 4096, 4097]
    primitive_warm = [0, 1, 7, -1, -(1 << 63), (1 << 63) - 1]
    primitive_cold = sorted({signed((1 << bit) + delta) for bit in range(64) for delta in [-1, 0, 1]} - set(primitive_warm))
    intset_primitive_cold = sorted((set(primitive_cold) |
                                   {signed(WORD_MASK ^ (1 << bit)) for bit in range(64)} |
                                   {signed(0xaaaaaaaaaaaaaaaa), 0x5555555555555555}) - set(primitive_warm))
    sequence_supported = {'sequenceBuildViews', 'sequenceDequeViews', 'sequenceAppendViews', 'sequenceLazyLength'}
    sequence_cold = sorted((set(range(18)) | {-3, -(1 << 63), (1 << 63) - 1,
        20, 21, 22, 24, 25, 26, 33, 34, 159, 160, 161, 255, 256, 257, 512, 1024}) - set(warm))
    groups = [
        dict(id='set', module='THC.SetWorkload', source='examples/THC/SetWorkload.hs',
             execution='frontier', postTidy=True, names=['setAggregate'], warm=warm, cold=cold),
        dict(id='intmap', module='THC.IntMapWorkload', source='examples/THC/IntMapWorkload.hs',
             execution='supported', postTidy=True, names=['intMapAggregate'], warm=warm, cold=cold),
        dict(id='intmap-primops', module='THC.IntMapPrimops', source='examples/THC/IntMapPrimops.hs',
             execution='supported', postTidy=False, names=['countLeadingZeros', 'unsignedLessThanZero', 'unsignedLessThanMaxSigned',
                                          'unsignedLessThanSignBit', 'unsignedLessThanAllOnes'],
             warm=primitive_warm, cold=primitive_cold),
        dict(id='intset', module='THC.IntSetWorkload', source='examples/THC/IntSetWorkload.hs',
             execution='supported', postTidy=True, names=['intSetAggregate'], warm=warm, cold=cold),
        dict(id='intset-primops', module='THC.IntSetPrimops', source='examples/THC/IntSetPrimops.hs',
             execution='supported', postTidy=False, names=['populationCount', 'countTrailingZeros',
                 'unsignedLessEqualZero', 'unsignedLessEqualMaxSigned',
                 'unsignedLessEqualSignBit', 'unsignedLessEqualAllOnes'],
             warm=primitive_warm, cold=intset_primitive_cold),
        dict(id='sequence', module='THC.SequenceWorkload', source='examples/THC/SequenceWorkload.hs',
             execution='frontier', postTidy=True, names=list(SEQUENCE_ENTRIES), warm=warm, cold=sequence_cold),
    ]
    violations = []
    for group in groups:
        output = BUILD / group['id']
        output.mkdir(parents=True, exist_ok=True)
        env = dict(os.environ, THC_CORE_OUT=str(output / 'core'), THC_GHC_OUT=str(output / 'ghc'))
        run(['compiler/export.sh', '-i' + str(containers / 'src'), '-I' + str(containers / 'include'),
             *(['-fplugin-opt=Thc.Plugin:post-tidy'] if group['postTidy'] else []),
             *['-fplugin-opt=Thc.Plugin:closure=' + name for name in group['names']], group['source']], env=env)
        closure_path = output / 'core/THC.InterfaceClosure.json'
        closure = json.loads(closure_path.read_text())
        modules = [output / 'core' / (module.split(':', 1)[1] + '.json') for module in closure['sourceModules']]
        modules += [closure_path, *boot]
        group['modules'] = list(map(str, modules))
        manifest = output / 'modules.txt'
        manifest.write_text(''.join(str(path) + '\n' for path in modules))
        audit_path = output / 'audit.json'
        command = [sys.executable, 'scripts/audit-core.py', '--module-list', str(manifest), '--output', str(audit_path)]
        for name in group['names']:
            command += ['--entry', name]
        result = subprocess.run(command, cwd=ROOT)
        if result.returncode not in [0, 1]:
            raise RuntimeError('Capability auditor failed: ' + str(result.returncode))
        audit = json.loads(audit_path.read_text())
        violations.extend(audit_structure_violations(group, audit))
        group['audit'] = str(audit_path)
        if group['id'] == 'sequence':
            # One real source export, but distinct public-API slices have distinct
            # frontiers. Acceptance is checked against an explicit expectation.
            group['entryAudits'] = {}
            for name in group['names']:
                entry_path = output / (name + '.audit.json')
                result = subprocess.run([sys.executable, 'scripts/audit-core.py', '--module-list', str(manifest),
                                         '--entry', name, '--output', str(entry_path)], cwd=ROOT)
                if result.returncode not in [0, 1]:
                    raise RuntimeError('Sequence capability auditor failed: ' + str(result.returncode))
                entry_audit = json.loads(entry_path.read_text())
                execution = 'supported' if name in sequence_supported else 'frontier'
                if entry_audit['accepted'] != (execution == 'supported'):
                    violations.append(name + ': declared support disagrees with ' + str(entry_path))
                if any(item['id'].startswith('main:') for item in entry_audit['missingGlobals']):
                    violations.append(name + ': source-library definitions must resolve at the post-Tidy boundary')
                if execution == 'frontier':
                    try:
                        require_sequence_tuple_frontier(entry_audit)
                    except RuntimeError as error:
                        violations.append(name + ': ' + str(error))
                group['entryAudits'][name] = dict(audit=str(entry_path), execution=execution)
        write_json(output / 'provenance.json', {
            'source': CONTAINERS_URL, 'sha256': CONTAINERS_SHA, 'sourcePatches': [],
            'containersUnitPolicy': 'Unmodified sources compiled with workload in the same main home unit',
            'postTidy': group['postTidy'],
            'compiler': '9.14.1', 'workload': group['source'], 'workloadSha256': digest(ROOT / group['source']),
            'sourceNotes': os.environ.get('THC_SOURCE_NOTES', 'true') == 'true',
            'modules': [{'path': str(path.relative_to(ROOT)), 'sha256': digest(path)} for path in modules],
            'initialMissingDefinitions': closure['missingDefinitions'],
            'bootExports': json.loads((BUILD / 'boot/boot-provenance.json').read_text()),
            'strictAccepted': audit['accepted'], 'execution': group['execution'],
            'entryAudits': group.get('entryAudits', {}),
        })
    native = BUILD / 'native'
    native.mkdir(exist_ok=True)
    run([ghc, '--make', '-O2', '-fforce-recomp', '-dcore-lint', '-dstg-lint',
         '-i' + str(ROOT / 'examples'), '-i' + str(containers / 'src'), '-I' + str(containers / 'include'),
         '-odir', native, '-hidir', native, 'examples/LibraryOracle.hs', '-o', native / 'library-oracle'])
    rows = []
    for group in groups:
        group['entries'] = []
        for name in group.pop('names'):
            entry = dict(name=name)
            entry.update(group.get('entryAudits', {}).get(name, {}))
            for phase in ['warm', 'cold']:
                entry[phase] = []
                for value in group[phase]:
                    row = subprocess.check_output([str(native / 'library-oracle'), name, str(value)], text=True).strip()
                    fields = row.split('\t')
                    if len(fields) != 3 or fields[:2] != [name, str(value)]:
                        raise RuntimeError('Malformed native oracle row: ' + row)
                    actual = int(fields[2])
                    model = {'setAggregate': set_model, 'intMapAggregate': intmap_model,
                             'intSetAggregate': intset_model}.get(name)
                    expected = (sequence_model(name, value) if group['id'] == 'sequence' else
                                model(value) if model else primitive_model(name, value))
                    if actual != expected:
                        raise RuntimeError(f'{name}({value}): native {actual} != independent model {expected}')
                    entry[phase].append([value, actual])
                    rows.append(row)
            group['entries'].append(entry)
        del group['warm'], group['cold']
        group.pop('entryAudits', None)
    (BUILD / 'oracle.tsv').write_text('\n'.join(rows) + '\n')
    write_json(BUILD / 'oracle-validation.json', dict(compiler='9.14.1', nativeRows=len(rows),
               allNativeResultsMatchIndependentModels=True, staticSupportViolations=violations))
    inputs = {ROOT / group['source'] for group in groups} | {
        ROOT / 'examples/LibraryOracle.hs', ROOT / 'scripts/prepare-library-tests.py',
        ROOT / 'scripts/audit-core.py', ROOT / 'scripts/core-capabilities.json',
        ROOT / 'src/main/kotlin/thc/LibraryCheck.kt', ROOT / 'compiler/build.sh',
        ROOT / 'compiler/export.sh', ROOT / 'compiler/export-boot.py', ROOT / 'compiler/toolchain.sh',
        ROOT / 'compiler/package-roots/InterfaceRoots.hs', ROOT / 'vendor/archives/containers-0.8.tar.gz',
        ROOT / 'scripts/sequence_model.py', ROOT / 'scripts/test-library-frontiers.py',
    }
    inputs.update((ROOT / 'compiler/Thc').glob('*.hs'))
    inputs.update(path for path in containers.rglob('*') if path.is_file())
    inputs.update(ROOT / item['path'] for item in
                  json.loads((BUILD / 'boot/boot-provenance.json').read_text())['sources'])
    artifacts = {BUILD / 'oracle.tsv', BUILD / 'oracle-validation.json', native / 'library-oracle'}
    for group in groups:
        artifacts.update(map(Path, group['modules']))
        artifacts.update([Path(group['audit']), BUILD / group['id'] / 'provenance.json'])
        artifacts.update(Path(entry['audit']) for entry in group['entries'] if 'audit' in entry)
    write_json(BUILD / 'cases.json', dict(schema=1, groups=groups,
               inputHashes={str(path): digest(path) for path in sorted(inputs)},
               artifactHashes={str(path): digest(path) for path in sorted(artifacts)}))
    print(f'Native GHC agrees with independent models on {len(rows)} rows')
    if violations:
        raise RuntimeError('\n'.join(violations))


if __name__ == '__main__':
    main()
