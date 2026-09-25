#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
"""Export and check native synchronous catch, raise and masking-state behavior."""

import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import random
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
SOURCE = 'compiler/test-fixtures/SynchronousExceptionsAudit.hs'
NATIVE = 'compiler/test-fixtures/SynchronousExceptionsNative.hs'
ENTRIES = ['preciseCatch', 'erasedNestedCatch', 'actionHeadCatch', 'ignoredBottomPayload', 'nestedRethrow',
           'unusedHandler', 'lazyResultBoundary', 'restoreAndRethrow', 'handlerMaskState',
           'maskNested', 'maskRethrowRestore', 'noDuplicateProbe']
FRONTIER = set()
REQUIRED = {
    'preciseCatch': {'catch#', 'raiseIO#'},
    'erasedNestedCatch': {'catch#', 'raiseIO#'},
    'actionHeadCatch': {'catch#', 'raise#'},
    'ignoredBottomPayload': {'catch#', 'raiseIO#'},
    'nestedRethrow': {'catch#', 'raiseIO#'},
    'unusedHandler': {'catch#', 'raise#'},
    'lazyResultBoundary': {'catch#', 'raise#'},
    'restoreAndRethrow': {'catch#', 'raiseIO#', 'newMVar#', 'putMVar#', 'takeMVar#', 'readMVar#', 'tryPutMVar#'},
    'handlerMaskState': {'catch#', 'raiseIO#', 'getMaskingState#', 'unmaskAsyncExceptions#'},
    'maskNested': {'maskAsyncExceptions#', 'maskUninterruptible#', 'getMaskingState#'},
    'maskRethrowRestore': {'maskUninterruptible#', 'catch#', 'raiseIO#', 'getMaskingState#'},
    'noDuplicateProbe': {'noDuplicate#'},
}
COMMAND_LABELS = ('ghc-version', 'python-version', 'cabal-plugin-build', 'plugin-metadata',
                  'pre-export', 'post-export', 'native-build', 'native-word-bits', 'native-oracle')


def require(condition, message):
    if not condition:
        raise ValueError(message)


def signed(value):
    return (value + 2**63) % 2**64 - 2**63


def input_vectors():
    rng = random.Random(0x4341544348)
    return sorted(set(range(-32, 33)) | {-2**63, -2**63 + 1, 2**63 - 2, 2**63 - 1, -4097, 4097, -10**12, 10**12}
                  | {signed(rng.getrandbits(64)) for _ in range(96)})


def source_inputs(root=ROOT):
    paths = [SOURCE, NATIVE, 'scripts/prepare-synchronous-exceptions.py', 'scripts/audit-core.py',
             'scripts/core-capabilities.json', 'src/main/resources/thc/scalar-primop-signatures.json',
             'thc.cabal', 'cabal.project', 'compiler/build.sh', 'compiler/plugin.py', 'compiler/toolchain.sh']
    paths += sorted(str(path.relative_to(root)) for path in (root / 'compiler/THC').glob('*.hs'))
    paths += sorted(str(path.relative_to(root)) for path in (root / 'scripts').glob('core_*.py'))
    return paths


def hash_files(paths, root=ROOT):
    result = {}
    for name in paths:
        path = (root / name).resolve()
        require(path.is_relative_to(root), 'Hash input outside checkout: ' + name)
        result[name] = hashlib.sha256(path.read_bytes()).hexdigest()
    return result


def mathematical(name, value):
    if name == 'preciseCatch':
        result = value + 17
    elif name == 'erasedNestedCatch':
        result = value + 43
    elif name == 'actionHeadCatch':
        result = value + 19
    elif name == 'ignoredBottomPayload':
        result = value + 23
    elif name == 'nestedRethrow':
        result = (value + 29) * 3
    elif name == 'unusedHandler':
        result = value + 31
    elif name == 'lazyResultBoundary':
        result = value + 37
    elif name == 'restoreAndRethrow':
        result = value * 257 + value + 41
    elif name == 'handlerMaskState':
        result = 34  # Unmasked=0, handler MaskedInterruptible=2, restored=0.
    elif name == 'maskNested':
        result = value + 212  # Base-3 tags: 2, 1, 2, 1, 2, 0.
    elif name == 'maskRethrowRestore':
        result = value + 7  # Inner uninterruptible=1; outer catch handler=2; restored=0.
    elif name == 'noDuplicateProbe':
        result = value + 5
    else:
        raise ValueError('Unknown oracle entry: ' + name)
    return signed(result)


def validate_rows(text, inputs):
    rows = [line.split('\t') for line in text.splitlines()]
    require(all(len(row) == 3 for row in rows), 'Malformed native row')
    require(len(rows) == len(ENTRIES) * len(inputs), 'Native row count mismatch')
    require({(name, int(value)) for name, value, _ in rows} == {(name, value) for name in ENTRIES for value in inputs},
            'Missing, duplicate or unknown native input')
    for name, value, result in rows:
        require(int(result) == mathematical(name, int(value)), f'Native/model mismatch: {name}({value}) = {result}')
    return rows


def applications(value):
    if isinstance(value, list):
        if len(value) >= 7 and value[0] == 'app' and isinstance(value[1], list) and value[1][:1] == ['prim']:
            if value[1][1] in {'catch#', 'raiseIO#', 'raise#', 'maskAsyncExceptions#',
                               'maskUninterruptible#', 'unmaskAsyncExceptions#', 'noDuplicate#'}:
                yield value
        for child in value:
            yield from applications(child)
    elif isinstance(value, dict):
        for child in value.values():
            yield from applications(child)


def nested_erased_actions(value):
    if isinstance(value, list):
        if len(value) >= 7 and value[0] == 'app' and isinstance(value[1], list) and value[1][:1] == ['lam']:
            if any(app[1][1] == 'raiseIO#' for app in applications(value[1])):
                yield value
        for child in value:
            yield from nested_erased_actions(child)
    elif isinstance(value, dict):
        for child in value.values():
            yield from nested_erased_actions(child)


def representation(expression):
    return expression[-1].get('rep') if isinstance(expression, list) and expression and isinstance(expression[-1], dict) else None


def validate_contract(app):
    """Only the observed lifted exception/boxed result fixture slice, not the full primop type."""
    primitive, args, flags = app[1][1], app[2], app[3]
    roles = {'catch#': ['closure', 'closure', 'state'], 'raiseIO#': ['boxed', 'state'], 'raise#': ['boxed'],
             'maskAsyncExceptions#': ['closure', 'state'], 'maskUninterruptible#': ['closure', 'state'],
             'unmaskAsyncExceptions#': ['closure', 'state'], 'noDuplicate#': ['state']}[primitive]
    require(isinstance(args, list) and isinstance(flags, list), primitive + ': missing arguments or flags')
    require(len(args) == len(roles) and len(flags) == len(roles), primitive + ': wrong logical arity')
    def state(proof):
        return isinstance(proof, dict) and 'aggregate' not in proof and proof.get('kind') == 'void' and proof.get('primReps') == []
    def boxed(proof):
        return isinstance(proof, dict) and 'aggregate' not in proof and proof.get('kind') in ('object', 'data', 'closure') \
            and proof.get('primReps') == ['BoxedRep (Just Lifted)']
    for role, arg, flag in zip(roles, args, flags):
        proof = representation(arg)
        require(type(flag) is bool and flag == (role != 'state'), primitive + ': malformed lifted flag')
        require(state(proof) if role == 'state' else boxed(proof), primitive + ': bad ' + role + ' argument')
        if role == 'closure':
            require(proof['kind'] == 'closure', primitive + ': expected closure argument')
    result = representation(app)
    if primitive in {'catch#', 'raiseIO#', 'maskAsyncExceptions#', 'maskUninterruptible#',
                     'unmaskAsyncExceptions#'}:
        require(isinstance(result, dict) and result.get('aggregate') == 'unboxed-tuple' and result.get('kind') == 'unknown',
                primitive + ': exact tuple required')
        fields = result.get('components')
        require(isinstance(fields, list) and len(fields) == 2 and state(fields[0]) and boxed(fields[1]),
                primitive + ': exact logical State#/boxed result required')
        require(result.get('primReps') == ['BoxedRep (Just Lifted)'], primitive + ': wrong flattened tuple')
    elif primitive == 'noDuplicate#':
        require(state(result), 'noDuplicate#: exact State# result required')
    # raise# may produce the action/handler function or a lazy boxed result.
    else:
        require(boxed(result), 'raise#: expected lifted boxed result')
    return {'primitive': primitive, 'arguments': [representation(arg) for arg in args],
            'liftedArguments': flags, 'result': result}


def classify_audit(report, name):
    require(not report['missingGlobals'], name + ': missing globals')
    require(all(issue['code'] == 'unsupported-primitive' and issue['detail'] in FRONTIER for issue in report['issues']),
            name + ': unexpected strict audit issue')
    require(REQUIRED[name] <= {entry['name'] for entry in report['primitives']}, name + ': required primitive was not retained')
    require(report['accepted'] == (not report['issues']), name + ': inconsistent audit status')
    require(report['accepted'], name + ': unexpected capability frontier')
    return {'accepted': report['accepted'],
            'unsupportedPrimitives': sorted({issue['detail'] for issue in report['issues']}),
            'reachableBindings': len(report['reachableBindings'])}


def check_prepared(build, root=ROOT):
    """Read-only provenance/oracle check; never rebuild or repair an old preparation."""
    manifest = json.loads((build / 'manifest.json').read_text())
    require(manifest.get('schema') == 1 and manifest.get('recipeVersion') == 1, 'Unknown preparation schema')
    require(manifest.get('ghc') == '9.14.1' and manifest.get('wordBits') == 64, 'Wrong native toolchain/word size')
    require(manifest.get('entries') == ENTRIES and manifest.get('inputs') == input_vectors(), 'Incomplete native input inventory')
    require(manifest.get('installedArtifactsHashed') is False, 'Installed-artifact hashing is not permitted')
    require(manifest.get('inputHashes') == hash_files(source_inputs(root), root), 'Source hashes changed or incomplete')
    stages = {stage: sorted(str(path.relative_to(root)) for path in (build / stage / 'core').glob('*.json'))
              for stage in ('pre', 'post')}
    require(all(stages.values()) and manifest.get('stages') == stages, 'Core artifact inventory changed or incomplete')
    required = {str((build / name).relative_to(root)) for name in ('contracts.json', 'oracle.tsv', 'native/synchronous-exception-oracle')}
    for label in COMMAND_LABELS:
        for suffix in ('.stdout', '.stderr', '.command.json'):
            required.add(str((build / 'logs' / (label + suffix)).relative_to(root)))
        command = json.loads((build / 'logs' / (label + '.command.json')).read_text())
        require(command.get('exit') == 0, 'Unsuccessful retained command: ' + label)
    statuses = {}
    for stage, paths in stages.items():
        required.update(paths)
        for name in ENTRIES:
            path = build / stage / (name + '.audit.json')
            required.add(str(path.relative_to(root)))
            statuses[stage + '/' + name] = classify_audit(json.loads(path.read_text()), name)
    require(manifest.get('auditStatus') == statuses, 'Audit status inventory changed or incomplete')
    plugin = json.loads((root / 'build/compiler/plugin.json').read_text())
    require(manifest.get('plugin') == plugin, 'Plugin metadata changed')
    required.add('build/compiler/plugin.json')
    for field in ('sharedLibrary', 'cabalSharedLibrary'):
        path = Path(plugin[field]).resolve()
        require(path.is_relative_to(root), 'Plugin artifact outside checkout')
        required.add(str(path.relative_to(root)))
    hashes = manifest.get('artifactHashes', {})
    require(set(hashes) == required and hashes == hash_files(required, root), 'Artifact hashes changed or incomplete')
    require((build / 'logs/native-word-bits.stdout').read_text().strip() == '64', 'Wrong native word size')
    require((build / 'logs/ghc-version.stdout').read_text().strip() == '9.14.1', 'Wrong GHC version')
    output = (build / 'oracle.tsv').read_text()
    require(output == (build / 'logs/native-oracle.stdout').read_text(), 'Oracle differs from retained execution')
    rows = validate_rows(output, input_vectors())
    require(manifest.get('nativeRows') == len(rows), 'Wrong native row count')
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, default=ROOT / 'build/synchronous-exceptions')
    parser.add_argument('--check-only', action='store_true', help='Verify existing provenance/results without executing tools')
    args = parser.parse_args()
    build = args.out.resolve()
    require(build.is_relative_to(ROOT), 'Choose an output directory inside this checkout')
    if args.check_only:
        manifest = check_prepared(build)
        print(json.dumps({'checked': str(build), 'nativeRows': manifest['nativeRows']}))
        return
    require(not build.exists(), 'Choose a fresh output directory; use --check-only to verify retained results')
    build.mkdir(parents=True)
    artifacts = []
    def relative(path):
        return str(path.relative_to(ROOT))
    def save(path, data):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(data, indent=2) + '\n')
        artifacts.append(relative(path))
    def run(command, label, **kwargs):
        command = [str(part) for part in command]
        log = build / 'logs' / label
        log.parent.mkdir(parents=True, exist_ok=True)
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
            raise ValueError('Timed out; retained output at ' + str(log)) from error
        save(log.with_suffix('.command.json'), record)
        artifacts.extend(relative(log.with_suffix(suffix)) for suffix in ('.stdout', '.stderr'))
        require(result.returncode == 0, 'Command failed; retained stderr at ' + str(log.with_suffix('.stderr')))
        return result.stdout

    inputs = source_inputs()
    input_hashes = hash_files(inputs)
    ghc = os.environ.get('GHC', 'ghc')
    require(run([ghc, '--numeric-version'], 'ghc-version').strip() == '9.14.1', 'Expected pinned GHC 9.14.1')
    run([sys.executable, '--version'], 'python-version')
    run(['compiler/build.sh'], 'cabal-plugin-build')
    plugin = json.loads(run([sys.executable, 'compiler/plugin.py'], 'plugin-metadata'))
    require(plugin.get('schema') == 1 and plugin.get('unitId'), 'Invalid Cabal plugin identity')
    for field in ('sharedLibrary', 'cabalSharedLibrary'):
        path = Path(plugin[field]).resolve()
        require(path.is_relative_to(ROOT), 'Plugin artifact outside this checkout')
        artifacts.append(relative(path))
    artifacts.append('build/compiler/plugin.json')
    # Use Cabal's registered unit and package DB, never invent a plugin package identity.
    flags = ['-package-env', '-', '-package-db', plugin['packageDb'], '-plugin-package-id', plugin['unitId'],
             '-fplugin=THC.Plugin']
    spec = importlib.util.spec_from_file_location('core_audit', ROOT / 'scripts/audit-core.py')
    audit = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(audit)
    capability = json.loads((ROOT / 'scripts/core-capabilities.json').read_text())
    stages, statuses, contracts = {}, {}, {}
    for stage in ('pre', 'post'):
        directory = build / stage
        core = directory / 'core'
        options = [str(core), 'source-notes'] + (['post-tidy'] if stage == 'post' else [])
        options += ['closure=' + name for name in ENTRIES]
        run([ghc, '--make', '-no-link', '-O2', '-dynamic', '-fforce-recomp', '-dcore-lint', '-g', *flags,
             *['-fplugin-opt=THC.Plugin:' + option for option in options], '-icompiler/test-fixtures',
             '-odir', directory / 'ghc', '-hidir', directory / 'ghc', SOURCE], stage + '-export')
        paths = sorted(core.glob('*.json'))
        require(paths, 'Missing genuine Core export')
        stages[stage] = [relative(path) for path in paths]
        artifacts.extend(stages[stage])
        modules = [(relative(path), json.loads(path.read_text())) for path in paths]
        bindings = {binding['id']: binding for _, module in modules for binding in module['bindings']}
        for name in ENTRIES:
            report = audit.Audit(modules, capability).run([name])
            save(directory / (name + '.audit.json'), report)
            statuses[stage + '/' + name] = classify_audit(report, name)
            observed = []
            for binding in report['reachableBindings']:
                observed += [dict(owner=binding['id'], **validate_contract(app))
                             for app in applications(bindings[binding['id']]['expr'])]
            if stage == 'post' and name == 'erasedNestedCatch':
                expression = bindings['main:SynchronousExceptionsAudit.erasedNestedCatch']['expr']
                require(any(representation(app) == {'kind': 'data', 'primReps': ['BoxedRep (Just Lifted)'],
                                                    'evaluated': False}
                            for app in nested_erased_actions(expression)),
                        'noinline erasure lost its exact root or nested raiseIO# certificate')
            contracts[stage + '/' + name] = observed

    native = build / 'native'
    native.mkdir()
    executable = native / 'synchronous-exception-oracle'
    run([ghc, '--make', '-O2', '-fforce-recomp', '-dcore-lint', '-dstg-lint', '-package-env', '-',
         '-icompiler/test-fixtures', '-odir', native, '-hidir', native, NATIVE, '-o', executable], 'native-build')
    artifacts.append(relative(executable))
    require(run([executable, '--word-bits'], 'native-word-bits').strip() == '64', 'Native oracle requires 64-bit Int#')
    values = input_vectors()
    requests = ''.join(f'{name}\t{value}\n' for name in ENTRIES for value in values)
    output = run([executable], 'native-oracle', input=requests)
    rows = validate_rows(output, values)
    oracle = build / 'oracle.tsv'
    oracle.write_text(output)
    artifacts.append(relative(oracle))
    save(build / 'contracts.json', contracts)
    require(input_hashes == hash_files(inputs), 'Sources changed during preparation')
    save(build / 'manifest.json', dict(schema=1, recipeVersion=1, ghc='9.14.1', wordBits=64, plugin=plugin, entries=ENTRIES, inputs=values,
        nativeRows=len(rows), stages=stages, auditStatus=statuses, inputHashes=input_hashes,
        artifactHashes=hash_files(artifacts), installedArtifactsHashed=False,
        limits=['Observed exception payloads/results are lifted boxed values, not every RuntimeRep.',
                'Masking-state observations do not imply asynchronous exception delivery or throwTo support.',
                'noDuplicate# relies on exclusive thunk ownership; THC does not clone an active guest stack.',
                'Synchronous MVar restoration is not asynchronous-exception-safe bracket or Handle IO.']))
    check_prepared(build)
    print(json.dumps({'nativeRows': len(rows), 'stages': list(stages), 'auditStatus': statuses}, indent=2))


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError) as error:
        raise SystemExit(str(error))
