#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Run the installed GHC fingerprintData source through native GHC and THC.

The sole source dependency absent from installed interface unfoldings is the
specialized Storable.peek worker. Compile the unmodified GHC 9.14.1 source in
a private same-unit interface overlay; never alter the installed package.
This is an executed IO () proof, not a general Fingerprint/IO-result ABI.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import urllib.request


ROOT = Path(__file__).resolve().parent.parent
BUILD = ROOT / 'build/fingerprint-data'
SOURCE = ROOT / 'compiler/test-fixtures/FingerprintDataRun.hs'
GHC_TAG = 'ghc-9.14.1-release'
SOURCE_URL = ('https://raw.githubusercontent.com/ghc/ghc/' + GHC_TAG +
              '/libraries/ghc-internal/src/GHC/Internal/Foreign/Storable.hs')
SOURCE_SHA256 = 'dda27f3c55cda6fbce4d44c127b6b26f5f18aa1e8b7ade6510e51cd4eec9e9cd'
WORKER = 'ghc-internal:GHC.Internal.Foreign.Storable.$fStorableFingerprint_$s$wpeekW64'
CALLS = {'__hsbase_MD5Init', '__hsbase_MD5Update', '__hsbase_MD5Final'}
EXPECTED = '0xd6963f7d28e17f72'
WRONG = '0xd6963f7d28e17f73'


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def run(command, *, environment=None, output=None, check=True):
    result = subprocess.run([str(part) for part in command], cwd=ROOT,
                            env=dict(os.environ, **(environment or {})),
                            text=True, capture_output=True, timeout=120)
    if output:
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(result.stdout + result.stderr)
    if check and result.returncode:
        raise RuntimeError(f'{command[0]} failed ({result.returncode}):\n{result.stderr[-2000:]}')
    return result


def pinned_source():
    source = BUILD / 'original/GHC/Internal/Foreign/Storable.hs'
    source.parent.mkdir(parents=True, exist_ok=True)
    if not source.exists():
        with urllib.request.urlopen(SOURCE_URL, timeout=30) as response:
            source.write_bytes(response.read())
    require(hashlib.sha256(source.read_bytes()).hexdigest() == SOURCE_SHA256,
            'Original GHC Storable.hs SHA256 mismatch')
    return source


def ghc_plugin():
    suffix = 'dylib' if sys.platform == 'darwin' else 'so'
    library = ROOT / f'build/compiler/libHSthc-core-plugin-0.1-ghc9.14.1.{suffix}'
    require(library.is_file(), 'Build the GHC plugin with compiler/build.sh first')
    return library


def export_storable(stage, source, installed, plugin):
    stage_dir = BUILD / stage
    overlay = stage_dir / 'boot-ghc'
    core = stage_dir / 'storable-core'
    core.mkdir(parents=True, exist_ok=True)
    for interface in installed.rglob('*.dyn_hi'):
        target = overlay / interface.relative_to(installed).with_suffix('.hi')
        target.parent.mkdir(parents=True, exist_ok=True)
        target.unlink(missing_ok=True)
        target.symlink_to(interface)
    (overlay / 'GHC/Internal/Foreign/Storable.hi').unlink(missing_ok=True)
    options = [str(core)] + (['post-tidy'] if stage == 'post' else [])
    command = [os.environ.get('GHC', 'ghc'), '-c', '-dynamic', '-fforce-recomp',
               '-this-unit-id', 'ghc-internal', '-package', 'ghc-internal',
               '-odir', overlay, '-hidir', overlay, '-O2', '-dcore-lint',
               '-fplugin-library=' + str(plugin) + ';thc-core-plugin-0.1;THC.Plugin;' +
               json.dumps(options), source]
    run(command, output=stage_dir / 'storable-export.log')
    result = core / 'GHC.Internal.Foreign.Storable.json'
    require(result.is_file(), f'{stage}: missing original Storable export')
    require(any(binding['id'] == WORKER for binding in json.loads(result.read_text())['bindings']),
            f'{stage}: original specialized peek worker changed')
    return result


def export_wrapper(stage, source):
    stage_dir = BUILD / stage
    core = stage_dir / 'core'
    core.mkdir(parents=True, exist_ok=True)
    options = ['-fplugin-opt=THC.Plugin:post-tidy'] if stage.startswith('post') else []
    run([ROOT / 'compiler/export.sh', *options, '-package', 'ghc-internal',
         '-fplugin-opt=THC.Plugin:closure=main', source],
        environment={'THC_CORE_OUT': str(core), 'THC_GHC_OUT': str(stage_dir / 'ghc')},
        output=stage_dir / 'wrapper-export.log')
    modules = [core / 'FingerprintDataRun.json', core / 'THC.InterfaceClosure.json']
    require(all(module.is_file() for module in modules), f'{stage}: missing wrapper or interface Core')
    return modules


def audit(stage, modules):
    path = BUILD / stage / 'audit.json'
    result = run(['python3', ROOT / 'scripts/audit-core.py', '--entry',
                  'main:FingerprintDataRun.main', '--io-main', *modules])
    path.write_text(result.stdout)
    report = json.loads(result.stdout)
    require(report['accepted'] and not report['missingGlobals'] and not report['issues'],
            f'{stage}: strict Core audit rejected the original caller')
    reached = {binding['id'] for binding in report['reachableBindings']}
    require(WORKER in reached and 'ghc-internal:GHC.Internal.Fingerprint.$wfingerprintData' in reached,
            f'{stage}: original GHC fingerprintData/Storable path changed')
    calls = [call['symbol'] for call in report['foreignCalls']]
    require(set(calls) == CALLS and len(calls) == len(CALLS),
            f'{stage}: original MD5 C call set changed: {calls}')
    return report


def native(source, label, expect_success):
    directory = BUILD / 'native' / label
    directory.mkdir(parents=True, exist_ok=True)
    binary = directory / 'fingerprint-data'
    run([os.environ.get('GHC', 'ghc'), '--make', '-O2', '-fforce-recomp',
         '-dcore-lint', '-main-is', 'FingerprintDataRun.main',
         '-outputdir', directory, '-o', binary, source], output=directory / 'build.log')
    result = run([binary], check=False, output=directory / 'execution.log')
    require((result.returncode == 0 and result.stdout == '') if expect_success else
            (result.returncode != 0 and '<<loop>>' in result.stderr),
            f'{label}: native GHC did not observe the expected fingerprint comparison')
    return result.returncode


def guest(stage, modules, label, expect_success):
    binary = ROOT / 'build/install/thc/bin/thc'
    require(binary.is_file(), 'Build the runtime with scripts/gradle.sh installDist first')
    outcomes = {}
    for backend in ('ast', 'bytecode'):
        result = run([binary, '--run-io', ','.join(map(str, modules)),
                      'main:FingerprintDataRun.main'],
                     environment={'THC_BACKEND': backend}, check=False,
                     output=BUILD / stage / f'{label}-{backend}.log')
        if expect_success:
            require(result.returncode == 0 and result.stdout == '',
                    f'{stage}/{backend}: original fingerprintData execution failed')
            diagnostics = json.loads(result.stderr.splitlines()[-1])
            require(diagnostics['unsupportedTraps'] == 0,
                    f'{stage}/{backend}: unsupported runtime trap')
        else:
            require(result.returncode != 0 and 'Blackhole: cyclic thunk' in result.stderr,
                    f'{stage}/{backend}: mismatch failed for a reason other than recursive bottom')
        outcomes[backend] = result.returncode
    return outcomes


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--export-only', action='store_true',
                        help='stop after native and strict pre/post Core proofs')
    args = parser.parse_args()
    require(run([os.environ.get('GHC', 'ghc'), '--numeric-version']).stdout.strip() == '9.14.1',
            'Requires GHC 9.14.1')
    ghc_pkg = os.environ.get('GHC_PKG', 'ghc-pkg')
    installed = Path(run([ghc_pkg, 'field', 'ghc-internal', 'import-dirs',
                          '--simple-output']).stdout.strip()).resolve()
    require(installed.is_dir(), 'Missing installed ghc-internal interfaces')
    original = pinned_source()
    plugin = ghc_plugin()
    fixture = SOURCE.read_text()
    require(fixture.count(EXPECTED) == 1 and WRONG not in fixture and
            'mismatch = mismatch' in fixture, 'Fingerprint fixture comparison changed')
    negative = BUILD / 'negative/source/FingerprintDataRun.hs'
    negative.parent.mkdir(parents=True, exist_ok=True)
    negative.write_text(fixture.replace(EXPECTED, WRONG))
    native(SOURCE, 'positive', True)
    native(negative, 'negative', False)
    # The installed interface refers to a post-Tidy specialized worker, so
    # supply the original post-Tidy Storable body at both wrapper boundaries.
    storable = export_storable('post', original, installed, plugin)
    stages = {}
    for stage in ('pre', 'post'):
        positive = [*export_wrapper(stage, SOURCE), storable]
        report = audit(stage, positive)
        negative_modules = [*export_wrapper(stage + '-negative', negative), storable]
        audit(stage + '-negative', negative_modules)
        stages[stage] = {'reachableBindings': len(report['reachableBindings']),
                         'foreignCalls': sorted(CALLS)}
        if not args.export_only:
            stages[stage]['positive'] = guest(stage, positive, 'positive', True)
            stages[stage]['negative'] = guest(stage, negative_modules, 'negative', False)
    (BUILD / 'manifest.json').write_text(json.dumps({
        'schema': 1, 'ghcTag': GHC_TAG, 'sourcePatches': [],
        'originalSource': {'url': SOURCE_URL, 'sha256': SOURCE_SHA256},
        'fixture': str(SOURCE.relative_to(ROOT)), 'stages': stages,
        'claim': 'executed original fingerprintData IO () caller; no guest compilation claim',
    }, indent=2) + '\n')
    print('Original fingerprintData: native GHC and strict pre/post Core passed' +
          ('' if args.export_only else '; AST and bytecode passed positive/negative execution'))


if __name__ == '__main__':
    main()
