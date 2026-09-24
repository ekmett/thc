#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Verify real GHC fingerprintData exports the three closed MD5 FCallIds.

This is a source/descriptor proof, not executable public Fingerprint support:
the entire IO result and Storable closure still have unsupported boundaries.
"""
import hashlib
import json
import os
from pathlib import Path
import subprocess

import core_md5_foreign

ROOT = Path(__file__).resolve().parent.parent
BUILD = ROOT / 'build/managed-md5-core'
SOURCE = ROOT / 'compiler/test-fixtures/ManagedMd5Audit.hs'


def main():
    BUILD.mkdir(parents=True, exist_ok=True)
    expected = set(core_md5_foreign.OPERATIONS)
    stages = {}
    for stage in ('pre', 'post'):
        directory = BUILD / stage
        core = directory / 'core'
        environment = os.environ.copy()
        environment.update(THC_CORE_OUT=str(core), THC_GHC_OUT=str(directory / 'ghc'))
        options = ['-fplugin-opt=THC.Plugin:post-tidy'] if stage == 'post' else []
        subprocess.run([str(ROOT / 'compiler/export.sh'), *options,
                        '-fplugin-opt=THC.Plugin:closure=probe', str(SOURCE)],
                       cwd=ROOT, env=environment, check=True)
        modules = [core / 'ManagedMd5Audit.json', core / 'THC.InterfaceClosure.json']
        report = directory / 'audit.json'
        with report.open('w') as output:
            audit = subprocess.run(['python3', str(ROOT / 'scripts/audit-core.py'), '--entry', 'probe',
                                    *(str(path) for path in modules)], cwd=ROOT, stdout=output)
        if audit.returncode != 1:
            raise ValueError(f'{stage}: expected the explicit unsupported public entry frontier')
        result = json.loads(report.read_text())
        symbols = [call['symbol'] for call in result['foreignCalls']]
        if set(symbols) != expected or len(symbols) != len(expected):
            raise ValueError(f'{stage}: original GHC MD5 FCallId set differs: {symbols}')
        if any(issue['code'] != 'aggregate-boundary' or issue['path'] != '/entry'
               for issue in result['issues']):
            raise ValueError(f'{stage}: MD5 source has unexpected unsupported Core: {result["issues"]}')
        if not result['missingGlobals']:
            raise ValueError(f'{stage}: source frontier changed; review public Fingerprint closure')
        stages[stage] = dict(symbols=symbols, accepted=result['accepted'],
                             missingGlobals=[item['id'] for item in result['missingGlobals']],
                             issues=result['issues'], sha256={str(path.relative_to(ROOT)):
                                 hashlib.sha256(path.read_bytes()).hexdigest() for path in (*modules, report)})
    (BUILD / 'manifest.json').write_text(json.dumps(dict(schema=1, source=str(SOURCE.relative_to(ROOT)),
                                                        stages=stages), indent=2) + '\n')
    print('Original GHC MD5 descriptor proof: pre/post all three closed symbols; full entry remains unsupported')


if __name__ == '__main__':
    main()
