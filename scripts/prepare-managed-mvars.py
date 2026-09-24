#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
"""Genuine GHC MVar Core/contracts and deterministic native-only oracle evidence."""

import argparse
from collections import Counter
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import random
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
SOURCE = 'compiler/test-fixtures/ManagedMVarAudit.hs'
NATIVE = 'compiler/test-fixtures/ManagedMVarNative.hs'
ENTRIES = ['transitions', 'lazyPayload', 'aliasRoundTrip', 'unliftedPayload', 'closurePayload']
CONTEXT_ENTRIES = ['waitTake', 'waitRead', 'waitPut', 'makeBox']
READY_ENTRIES = ['nativeWaitTake', 'nativeWaitRead', 'nativeWaitPut']
CONCURRENT_ENTRIES = ['nativeReaders', 'nativeTakeFIFO', 'nativePutFIFO']
CONTRACTS = {
    'newMVar#': (['state'], ['state', 'mvar']),
    'takeMVar#': (['mvar', 'state'], ['state', 'boxed']),
    'putMVar#': (['mvar', 'boxed', 'state'], 'state'),
    'readMVar#': (['mvar', 'state'], ['state', 'boxed']),
    'tryTakeMVar#': (['mvar', 'state'], ['state', 'flag', 'boxed']),
    'tryPutMVar#': (['mvar', 'boxed', 'state'], ['state', 'flag']),
    'tryReadMVar#': (['mvar', 'state'], ['state', 'flag', 'boxed']),
    'isEmptyMVar#': (['mvar', 'state'], ['state', 'flag']),
}
REQUIRED = {name: set(CONTRACTS) for name in ('transitions', 'lazyPayload', 'unliftedPayload')}
REQUIRED.update({name: {'newMVar#', 'putMVar#', 'takeMVar#', 'readMVar#'}
                 for name in ('aliasRoundTrip', 'closurePayload')})
REQUIRED.update(waitTake={'takeMVar#'}, waitRead={'readMVar#'}, waitPut={'putMVar#'}, makeBox=set())
COMMAND_LABELS = ['ghc-version', 'ghc-package-db', 'ghc-info', 'plugin-build', 'pre-export', 'post-export',
                  'native-build', 'native-word-bits', 'native-ready', 'native-concurrent-N1', 'native-concurrent-N2']


def require(condition, message):
    if not condition:
        raise ValueError(message)


def signed(value):
    return (value + 2**63) % 2**64 - 2**63


def mathematical(name, value):
    if name in ('transitions', 'unliftedPayload'):
        result = value * (1 + 257 + 65537) + (value + 17) * 16777259 + 96
    elif name == 'lazyPayload':
        result = value + 30
    elif name == 'aliasRoundTrip':
        result = (value + (value < 0)) * 257 + (value + (value >= 0)) * 65537 + 28 * value
    elif name == 'closurePayload':
        result = 4 * value + 17
    elif name in ('nativeWaitTake', 'nativeWaitRead'):
        result = value
    elif name == 'nativeWaitPut':
        result = value + 17
    elif name == 'nativeReaders':
        result = (value + 31) * (1 + 17 + 257)
    elif name == 'nativeTakeFIFO':
        result = value + 1 + 17 * (value + 2) + 257 * (value + 3)
    elif name == 'nativePutFIFO':
        result = value + 17 * (value + 1) + 257 * (value + 2) + 65537 * (value + 3)
    else:
        raise ValueError('Unknown model entry: ' + name)
    return signed(result)


def primitive_apps(value):
    if isinstance(value, list):
        if len(value) >= 7 and value[0] == 'app' and isinstance(value[1], list) and value[1][:1] == ['prim']:
            if value[1][1] in CONTRACTS:
                yield value
        for child in value:
            yield from primitive_apps(child)
    elif isinstance(value, dict):
        for child in value.values():
            yield from primitive_apps(child)


def role_matches(role, proof):
    if not isinstance(proof, dict) or 'aggregate' in proof:
        return False
    kind, reps = proof.get('kind'), proof.get('primReps')
    if role == 'state':
        return kind == 'void' and reps == []
    if role == 'flag':
        return kind == 'long' and reps == ['IntRep']
    if role == 'mvar':
        return kind == 'object' and reps == ['BoxedRep (Just Unlifted)']
    return role == 'boxed' and kind in ('data', 'closure', 'object') and reps in (
        ['BoxedRep (Just Lifted)'], ['BoxedRep (Just Unlifted)'])


def expression_rep(expression):
    return expression[-1].get('rep') if isinstance(expression, list) and isinstance(expression[-1], dict) else None


def validate_application(app):
    name = app[1][1]
    arguments, result = CONTRACTS[name]
    require(len(app[2]) == len(arguments) == len(app[3]), 'Wrong primitive arity: ' + name)
    levities = set()
    for role, argument, lifted in zip(arguments, app[2], app[3]):
        proof = expression_rep(argument)
        require(role_matches(role, proof), f'{name}: bad {role} argument: {proof}')
        require(lifted == (proof['primReps'] == ['BoxedRep (Just Lifted)']), name + ': argument lifted flag mismatch')
        if role == 'boxed':
            levities.add(proof['primReps'][0])
    proof = expression_rep(app)
    if isinstance(result, list):
        require(isinstance(proof, dict) and proof.get('aggregate') == 'unboxed-tuple' and proof.get('kind') == 'unknown',
                name + ': missing logical result tuple')
        components = proof.get('components', [])
        require(len(components) == len(result), name + ': missing logical State# or payload component')
        require(proof['primReps'] == [rep for field in components for rep in field['primReps']], name + ': flattened result mismatch')
        for role, field in zip(result, components):
            require(role_matches(role, field), f'{name}: bad {role} result: {field}')
            if role == 'boxed':
                levities.add(field['primReps'][0])
    else:
        require(role_matches(result, proof), name + ': bad scalar State# result')
    return name, levities


def validate_rows(text, entries, values):
    rows = [line.split('\t') for line in text.splitlines()]
    require(all(len(row) == 3 for row in rows), 'Malformed native output')
    require(len(rows) == len(entries) * len(values), 'Wrong native row count')
    require({(name, int(value)) for name, value, _ in rows} == {(name, value) for name in entries for value in values},
            'Missing, duplicate, or unexpected native input')
    for name, value, result in rows:
        require(int(result) == mathematical(name, int(value)), f'Native/model mismatch: {name} {value} {result}')
    return rows


def input_vectors():
    random_values = random.Random(0x4D564152)
    values = sorted(set(range(-32, 33)) | {-2**63, -2**63 + 1, 2**63 - 2, 2**63 - 1, -4097, 4097, -10**12, 10**12}
                    | {signed(random_values.getrandbits(64)) for _ in range(96)})
    concurrent = sorted({-2**63, -4097, -1, 0, 1, 4097, 2**63 - 1}
                        | {signed(random_values.getrandbits(64)) for _ in range(8)})
    return values, concurrent


def source_inputs(root=ROOT):
    inputs = [SOURCE, NATIVE, 'scripts/prepare-managed-mvars.py', 'scripts/audit-core.py',
              'scripts/core-capabilities.json', 'src/main/resources/thc/scalar-primop-signatures.json']
    inputs += sorted(str(p.relative_to(root)) for p in (root / 'scripts').glob('core_*.py'))
    inputs += sorted(str(p.relative_to(root)) for p in (root / 'compiler/THC').glob('*.hs'))
    return inputs


def hash_files(paths, root=ROOT):
    return {path: hashlib.sha256((root / path).read_bytes()).hexdigest() for path in paths}


def classify_audit(report, label):
    require(not report['missingGlobals'], label + ': missing genuine source definitions')
    if not report['accepted']:
        require(report['issues'] and all(issue['code'] == 'unsupported-primitive' and issue['detail'] in CONTRACTS
                                        for issue in report['issues']), label + ': unexpected audit rejection')
    else:
        require(not report['issues'], label + ': accepted report still has issues')
    return 'accepted' if report['accepted'] else 'pending-managed-mvar-capabilities'


def expected_artifacts(build, root=ROOT):
    stages = {stage: [str((build / stage / 'core' / name).relative_to(root))
                      for name in ('ManagedMVarAudit.json', 'THC.InterfaceClosure.json')] for stage in ('pre', 'post')}
    files = [build / stage / (name + '.audit.json') for stage in stages for name in ENTRIES + CONTEXT_ENTRIES]
    files += [build / 'logs' / (label + suffix) for label in COMMAND_LABELS for suffix in ('.stdout', '.stderr', '.command.json')]
    suffix = 'dylib' if sys.platform == 'darwin' else 'so'
    files += [build / 'plugin' / ('libHSthc-core-plugin-0.1-ghc9.14.1.' + suffix),
              build / 'native/managed-mvar-oracle', build / 'oracle.tsv', build / 'context-oracle.tsv', build / 'contracts.json']
    return stages, {str(path.relative_to(root)) for path in files} | {path for paths in stages.values() for path in paths}


def check_prepared(build, root=ROOT):
    """Read-only verification: never repair, delete, compile, or run an oracle."""
    try:
        manifest = json.loads((build / 'manifest.json').read_text())
        require(manifest['schema'] == 1 and manifest['recipeVersion'] == 2 and manifest['ghc'] == '9.14.1',
                'missing/current recipe version mismatch')
        require(manifest['entries'] == manifest['entryNames'] == ENTRIES and manifest['contextEntryNames'] == CONTEXT_ENTRIES,
                'entry inventory mismatch')
        stages, artifacts = expected_artifacts(build, root)
        require(manifest['stages'] == stages, 'pre/post Core inventory mismatch')
        require(set(manifest['inputHashes']) == set(source_inputs(root)), 'input hash inventory is incomplete')
        require(set(manifest['artifactHashes']) == artifacts, 'artifact hash inventory is incomplete')
        for key, boundary in (('inputHashes', root), ('artifactHashes', build)):
            for path, expected in manifest[key].items():
                require(not Path(path).is_absolute() and (root / path).resolve().is_relative_to(boundary),
                        'hash path escapes its source/output boundary: ' + path)
                require(hash_files([path], root)[path] == expected, 'hash mismatch: ' + path)
        values, concurrent_values = input_vectors()
        require(manifest['inputs'] == values, 'deterministic input inventory mismatch')
        for filename, names, count in (('oracle.tsv', ENTRIES, 'nativeRows'), ('context-oracle.tsv', READY_ENTRIES, 'nativeContextRows')):
            rows = validate_rows((build / filename).read_text(), names, values)
            require(manifest[count] == len(rows), 'actual row count mismatch: ' + count)
        validate_rows((build / 'logs/native-ready.stdout').read_text(), ENTRIES + READY_ENTRIES, values)
        require(set(manifest['nativeConcurrent']) == {'1', '2'}, 'missing native capability-count run')
        for capabilities_count in ('1', '2'):
            record = manifest['nativeConcurrent'][capabilities_count]
            require(record['entries'] == CONCURRENT_ENTRIES and record['inputs'] == concurrent_values, 'concurrent input inventory mismatch')
            rows = validate_rows((build / ('logs/native-concurrent-N' + capabilities_count + '.stdout')).read_text(),
                                 CONCURRENT_ENTRIES, concurrent_values)
            require(record['rows'] == len(rows), 'actual concurrent row count mismatch')
        expected_status = {}
        for stage in stages:
            require(set(manifest['contextEntries'][stage]) == set(CONTEXT_ENTRIES), 'context entry metadata missing')
            for name in ENTRIES + CONTEXT_ENTRIES:
                report = json.loads((build / stage / (name + '.audit.json')).read_text())
                label = stage + '/' + name
                require(report['roots'] == ['main:ManagedMVarAudit.' + name], label + ': wrong audit root')
                expected_status[label] = classify_audit(report, label)
                require(REQUIRED[name] <= {p['name'] for p in report['primitives']}, label + ': missing primitive coverage')
                require(manifest['reachableBindings'][label] == report['reachableBindings'], label + ': reachable inventory mismatch')
        require(manifest['auditStatus'] == expected_status, 'strict audit status inventory mismatch')
        for label in COMMAND_LABELS:
            command = json.loads((build / ('logs/' + label + '.command.json')).read_text())
            require(command['exit'] == 0, 'incomplete/failed command: ' + label)
        require((build / 'logs/native-word-bits.stdout').read_text().strip() == '64', 'wrong native Int width')
        require((build / 'logs/ghc-version.stdout').read_text().strip() == '9.14.1', 'wrong compiler version')
        require(manifest['installedArtifactsHashed'] is False, 'unexpected installed-artifact hashing')
        return manifest
    except (OSError, ValueError, KeyError, TypeError) as error:
        raise ValueError(f'Stale or incomplete preparation at {build}: {error}. Outputs were not changed; choose a fresh --out directory.') from error


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, default=ROOT / 'build/managed-mvars')
    parser.add_argument('--check-only', action='store_true', help='Verify existing hashes, complete audits, and actual native/model rows without building')
    args = parser.parse_args()
    build = args.out.resolve()
    require(build.is_relative_to(ROOT), 'Output directory must be inside this checkout')
    if args.check_only or build.exists():
        manifest = check_prepared(build)
        print(f'Verified complete preparation: {manifest["nativeRows"]} native/model rows; no outputs changed')
        return
    build.mkdir(parents=True)
    artifacts = []

    def relative(path):
        return str(path.relative_to(ROOT))

    def save(path, value):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(value, indent=2) + '\n')
        artifacts.append(relative(path))

    def run(command, label, **kwargs):
        log = build / 'logs' / label
        log.parent.mkdir(parents=True, exist_ok=True)
        command = [str(part) for part in command]
        record = {'argv': command, 'cwd': str(ROOT)}
        try:
            result = subprocess.run(command, cwd=ROOT, text=True, capture_output=True, timeout=120, **kwargs)
            log.with_suffix('.stdout').write_text(result.stdout)
            log.with_suffix('.stderr').write_text(result.stderr)
            record['exit'] = result.returncode
        except subprocess.TimeoutExpired as error:
            log.with_suffix('.stdout').write_bytes(error.stdout or b'')
            log.with_suffix('.stderr').write_bytes(error.stderr or b'')
            record.update(exit=None, timeoutSeconds=120)
            save(log.with_suffix('.command.json'), record)
            raise RuntimeError('Native/compiler timeout; preserved output: ' + str(log)) from error
        save(log.with_suffix('.command.json'), record)
        artifacts.extend(relative(log.with_suffix(suffix)) for suffix in ('.stdout', '.stderr'))
        require(result.returncode == 0, 'Command failed; see ' + str(log.with_suffix('.stderr')))
        return result.stdout

    inputs = source_inputs()
    hashes = hash_files
    input_hashes = hashes(inputs)
    ghc = os.environ.get('GHC', 'ghc')
    require(run([ghc, '--numeric-version'], 'ghc-version').strip() == '9.14.1', 'Expected GHC 9.14.1')
    database = run([ghc, '--print-global-package-db'], 'ghc-package-db').strip()
    package_flags = ['-package-env', '-', '-clear-package-db', '-package-db', database]
    ghc_info = run([ghc, '--info'], 'ghc-info')
    plugin_dir = build / 'plugin'
    plugin_dir.mkdir()
    suffix = 'dylib' if sys.platform == 'darwin' else 'so'
    plugin = plugin_dir / ('libHSthc-core-plugin-0.1-ghc9.14.1.' + suffix)
    command = [ghc, '--make', '-fforce-recomp', '-O1', '-dynamic', '-shared', '-fPIC', *package_flags]
    for package in ('ghc', 'bytestring', 'directory', 'filepath', 'containers'):
        command += ['-package', package]
    command += ['-this-unit-id', 'thc-core-plugin-0.1', '-hisuf', 'dyn_hi', '-osuf', 'dyn_o',
                '-icompiler', '-odir', plugin_dir, '-hidir', plugin_dir, 'compiler/THC/Plugin.hs', '-o', plugin]
    run(command, 'plugin-build')
    artifacts.append(relative(plugin))
    spec = importlib.util.spec_from_file_location('core_audit', ROOT / 'scripts/audit-core.py')
    audit = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(audit)
    capabilities = json.loads((ROOT / 'scripts/core-capabilities.json').read_text())
    stages, closures, audit_status, application_records, context_records = {}, {}, {}, {}, {}
    for stage in ('pre', 'post'):
        directory = build / stage
        core = directory / 'core'
        options = [str(core), 'source-notes'] + (['post-tidy'] if stage == 'post' else [])
        options += ['closure=' + name for name in ENTRIES + CONTEXT_ENTRIES]
        run([ghc, '--make', '-no-link', '-O2', '-dynamic', '-fforce-recomp', '-dcore-lint', '-g', *package_flags,
             '-fplugin-library=' + str(plugin) + ';thc-core-plugin-0.1;THC.Plugin;' + json.dumps(options),
             '-icompiler/test-fixtures', '-odir', directory / 'ghc', '-hidir', directory / 'ghc', SOURCE], stage + '-export')
        paths = sorted(core.glob('*.json'))
        require(paths, 'Missing genuine Core exports')
        modules = [(relative(path), json.loads(path.read_text())) for path in paths]
        stages[stage] = [name for name, _ in modules]
        artifacts.extend(stages[stage])
        bindings = {b['id']: b for _, module in modules for b in module['bindings']}
        observed = {name: set() for name in CONTRACTS}
        for name in ENTRIES + CONTEXT_ENTRIES:
            report = audit.Audit(modules, capabilities).run([name])
            save(directory / (name + '.audit.json'), report)
            audit_status[stage + '/' + name] = classify_audit(report, stage + '/' + name)
            require(REQUIRED[name] <= {p['name'] for p in report['primitives']}, stage + '/' + name + ': missing primitive coverage')
            closures[stage + '/' + name] = report['reachableBindings']
            records = []
            for reached in report['reachableBindings']:
                for app in primitive_apps(bindings[reached['id']]['expr']):
                    primitive, levities = validate_application(app)
                    observed[primitive].update(levities)
                    records.append({'owner': reached['id'], 'primitive': primitive,
                                    'arguments': [expression_rep(arg) for arg in app[2]],
                                    'liftedArguments': app[3], 'result': expression_rep(app)})
            application_records[stage + '/' + name] = records
        both_levities = {'BoxedRep (Just Lifted)', 'BoxedRep (Just Unlifted)'}
        for name in ('takeMVar#', 'putMVar#', 'readMVar#', 'tryTakeMVar#', 'tryPutMVar#', 'tryReadMVar#'):
            require(observed[name] == both_levities, stage + '/' + name + ': incomplete boxed payload levity coverage')
        context_records[stage] = {}
        for name in CONTEXT_ENTRIES:
            candidates = [b for b in bindings.values() if b['name'] == name and b['id'].startswith('main:ManagedMVarAudit.')]
            require(len(candidates) == 1, stage + '/' + name + ': context entry not uniquely exported')
            binding = candidates[0]
            context_records[stage][name] = {key: binding[key] for key in ('id', 'name', 'type', 'arity')}

    native = build / 'native'
    native.mkdir()
    executable = native / 'managed-mvar-oracle'
    run([ghc, '--make', '-O2', '-fforce-recomp', '-dcore-lint', '-dstg-lint', '-threaded', '-rtsopts', *package_flags,
         '-icompiler/test-fixtures', '-odir', native, '-hidir', native, NATIVE, '-o', executable], 'native-build')
    artifacts.append(relative(executable))
    require(run([executable, '--word-bits'], 'native-word-bits').strip() == '64', 'Oracle requires a native 64-bit Int target')
    values, concurrent_values = input_vectors()
    requests = ''.join(f'{name}\t{value}\n' for name in ENTRIES + READY_ENTRIES for value in values)
    native_output = run([executable, '+RTS', '-N1', '-RTS'], 'native-ready', input=requests)
    rows = validate_rows(native_output, ENTRIES + READY_ENTRIES, values)
    for filename, names in (('oracle.tsv', ENTRIES), ('context-oracle.tsv', READY_ENTRIES)):
        path = build / filename
        path.write_text(''.join('\t'.join(row) + '\n' for row in rows if row[0] in names))
        artifacts.append(relative(path))
    requests = ''.join(f'{name}\t{value}\n' for name in CONCURRENT_ENTRIES for value in concurrent_values)
    concurrent = {}
    for capabilities_count in (1, 2):
        output = run([executable, '+RTS', '-N' + str(capabilities_count), '-RTS'], 'native-concurrent-N' + str(capabilities_count), input=requests)
        concurrent[str(capabilities_count)] = {'rows': len(validate_rows(output, CONCURRENT_ENTRIES, concurrent_values)),
                                               'entries': CONCURRENT_ENTRIES, 'inputs': concurrent_values}
    require(input_hashes == hashes(inputs), 'Inputs changed during native/export preparation')
    save(build / 'contracts.json', {'primitiveRoles': CONTRACTS, 'applications': application_records,
                                    'contextEntries': context_records})
    manifest = dict(schema=1, recipeVersion=2, ghc='9.14.1', ghcInfo=ghc_info, entries=ENTRIES, entryNames=ENTRIES,
                    contextEntryNames=CONTEXT_ENTRIES, contextEntries=context_records,
                    stages=stages, nativeRows=len(ENTRIES) * len(values), nativeContextRows=len(READY_ENTRIES) * len(values),
                    nativeConcurrent=concurrent, inputs=values, reachableBindings=closures, auditStatus=audit_status,
                    inputHashes=input_hashes, artifactHashes=hashes(artifacts), installedArtifactsHashed=False,
                    limits=['No runtime/auditor capability is changed.',
                            'Context entries accept managed objects/logical State# only in internal host-driven tests.',
                            'Native fork/catch/status drivers are not guest exports or evidence of guest thread support.'])
    save(build / 'manifest.json', manifest)
    check_prepared(build)
    print(json.dumps({'nativeRows': manifest['nativeRows'], 'nativeContextRows': manifest['nativeContextRows'],
                      'nativeConcurrent': concurrent, 'auditStatus': dict(Counter(audit_status.values()))}, indent=2))


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, RuntimeError) as error:
        raise SystemExit(str(error))
