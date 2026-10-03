#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

# Fixture rationale (127 show-word-list)
# Purpose: Check ordinary list/word rendering works through public package code.
# Produces/consumed result: Core/boot CBDs and oracle.tsv.
# Cost and overlap: Useful package smoke, not a reason for a dedicated boot/export/receipt
#   harness. Quarantine recursive native inventory; move a small rendering case into
#   shared package behavior coverage.
# Build status: QUARANTINED: excluded from the new fixture build; see docs/fixture-quarantine.log.
# Detailed file inputs/outputs: docs/fixture-inputs.log, entry 127.

"""Fresh public Show Word/list oracle and full, pinned original Show source export."""
import argparse
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
from core_package_manifest import inspect_cbd
import os
from pathlib import Path
import subprocess
import sys
from show_word_list_model import ENTRIES, SHAPES, inputs, requests, verify

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'build/show-word-list'
STAGES = {'pre': 'optimized-Core-before-Tidy', 'post': 'optimized-Core-after-Tidy-before-CorePrep'}
FIXTURES = [ROOT / 't/fixtures/compiler' / name for name in ('ShowWordListAudit.hs', 'ShowWordListAuditNative.hs')]

def check(condition, message):
    if not condition:
        raise AssertionError(message)
def inspection(path, *, sources=False):
    check(path.is_file(), 'Missing executable CBD: '+str(path))
    module = inspect_cbd(path.read_bytes())
    if sources:
        module.update(inspect_cbd(path.read_bytes(), sources=True))
    return module
def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()
def record(path):
    return dict(path=str(path.relative_to(ROOT)), sha256=digest(path))
def audit_inputs():
    return [ROOT/'bin/audit-core.py', ROOT/'bin/core-capabilities.json', *sorted((ROOT/'bin').glob('core_*.py')),
            ROOT/'src/main/resources/thc/scalar-primop-signatures.json', ROOT/'src/tools/primops/PrimopTools.hs', ROOT/'thc.cabal']
def auditor():
    spec = importlib.util.spec_from_file_location('audit_core', ROOT/'bin/audit-core.py')
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    return module, json.loads((ROOT/'bin/core-capabilities.json').read_text())
def inventory():
    audit, caps = auditor()
    source_path = OUT/'boot/core/GHC.Internal.Show.cbd'
    source = inspection(source_path, sources=True)
    check(source['ghc'] == '9.14.1' and source['boundary'] == STAGES['post'], 'Show must be genuine post-Tidy GHC9.14.1 source')
    cstring_path = OUT/'cstring/core/GHC.Internal.CString.cbd'
    cstring = inspection(cstring_path, sources=True)
    check(cstring['ghc'] == '9.14.1' and cstring['boundary'] == STAGES['post'] and cstring.get('sourceFiles') and cstring.get('sourceSpans'),
          'CString must be the complete original post-Tidy source module')
    check(source.get('sourceFiles') and source.get('sourceSpans'), 'Complete original source evidence is required')
    stages = {}
    for stage, boundary in STAGES.items():
        public_path = OUT/f'{stage}-core/ShowWordListAudit.cbd'
        public = inspection(public_path)
        check(public['ghc'] == '9.14.1' and public['boundary'] == boundary, f'{stage}: wrong public export boundary')
        paths = [public_path, source_path, cstring_path]
        stages[stage] = [str(p.relative_to(ROOT)) for p in paths]
        for entry in ENTRIES:
            report = audit.Audit([(str(public_path), public), (str(source_path), source), (str(cstring_path), cstring)], caps).run(['main:ShowWordListAudit.'+entry])
            check(report['accepted'], f'{stage}/{entry}: strict Show source closure rejected: {report["summary"]}')
            (OUT/f'{stage}-{entry}.audit.json').write_text(json.dumps(report, indent=2)+'\n')
    return stages

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check-only', action='store_true')
    args = parser.parse_args()
    manifest_path = OUT/'manifest.json'
    if not args.check_only:
        ghc = os.environ.get('GHC', 'ghc')
        check(subprocess.check_output([ghc, '--numeric-version'], text=True).strip() == '9.14.1', 'Requires pinned GHC9.14.1')
        (OUT/'native').mkdir(parents=True, exist_ok=True)
        commands = []
        def run(argv, extra_env=None):
            commands.append(dict(argv=argv, environment=extra_env or {}))
            subprocess.run(argv, cwd=ROOT, env=dict(os.environ, **(extra_env or {})), check=True)
        run(['bin/build-compiler.sh'])
        run([sys.executable, 'bin/export-boot.py', '--frontier', 'show', '--build-dir', str(OUT/'boot')])
        run([sys.executable, 'bin/export-boot.py', '--frontier', 'cstring', '--build-dir', str(OUT/'cstring')])
        for stage in STAGES:
            run(['bin/export-core.sh', *(['-fplugin-opt=THC.Plugin:post-tidy'] if stage == 'post' else []),
                 str(FIXTURES[0])],
                dict(THC_CORE_OUT=str(OUT/f'{stage}-core'), THC_GHC_OUT=str(OUT/f'{stage}-ghc'), THC_SOURCE_NOTES='true'))
        stages = inventory()
        run([ghc, '--make', '-O2', '-fforce-recomp', '-dcore-lint', '-dstg-lint', '-it/fixtures/compiler',
             '-odir', str(OUT/'native'), '-hidir', str(OUT/'native'), '-o', str(OUT/'native/show-word-list-oracle'), str(FIXTURES[1])])
        text = ''.join(f'{name}\t{x}\t{s}\t{i}\n' for name,x,s,i in requests())
        (OUT/'requests.tsv').write_text(text)
        commands.append(dict(argv=[str(OUT/'native/show-word-list-oracle')], stdin=str(OUT/'requests.tsv'), stdout=str(OUT/'oracle.tsv')))
        result = subprocess.run([str(OUT/'native/show-word-list-oracle')], input=text, text=True, capture_output=True, check=True)
        (OUT/'oracle.tsv').write_text(result.stdout)
        rows = verify(result.stdout)
        sources = [*FIXTURES, Path(__file__).resolve(), ROOT/'bin/show_word_list_model.py', ROOT/'bin/export-boot.py',
                   ROOT/'bin/build-compiler.sh', ROOT/'bin/export-core.sh', ROOT/'bin/toolchain.sh',
                   *sorted((ROOT/'src/compiler/THC').glob('*.hs')), *audit_inputs(),
                   ROOT/'nih/pinned/ghc-9.14.1/libraries/ghc-internal/src/GHC/Internal/Show.hs', ROOT/'nih/pinned/ghc-9.14.1/libraries/ghc-internal/LICENSE']
        for directory in ('boot', 'cstring'):
            boot = json.loads((OUT/directory/'boot-provenance.json').read_text())
            sources += [ROOT/item['path'] for item in boot['sources']]
        sources = list(dict.fromkeys(sources))
        artifacts = [OUT/'cstring/boot-provenance.json', OUT/'requests.tsv', OUT/'oracle.tsv', OUT/'boot/boot-provenance.json']
        artifacts += [OUT/f'{stage}-{entry}.audit.json' for stage in STAGES for entry in ENTRIES]
        artifacts += [ROOT/path for path in dict.fromkeys(path for paths in stages.values() for path in paths)]
        artifacts += [p for p in sorted((OUT/'native').rglob('*')) if p.is_file()]
        manifest_path.write_text(json.dumps(dict(schema=1, recordedAtUtc=datetime.now(timezone.utc).isoformat(), commands=commands,
            ghcInfo=subprocess.check_output([ghc, '--info'], text=True),
            wordBits=64, entries=list(ENTRIES), inputs=inputs(), listShapes=list(SHAPES), nativeRows=len(rows), stages=stages,
            sources=[record(p) for p in sources], artifacts=[record(p) for p in artifacts],
            claim='Complete pinned original Show/CString sources; public native results and independent unsigned decimal/list character model. Runtime compiled gates are separate.'), indent=2)+'\n')
    manifest = json.loads(manifest_path.read_text())
    check({str(p.relative_to(ROOT)) for p in audit_inputs()} <= {r['path'] for r in manifest['sources']}, 'Missing auditor source fingerprint')
    for item in manifest['sources'] + manifest['artifacts']:
        check(record(ROOT/item['path']) == item, 'Stale Show fixture: '+item['path'])
    rows = verify((OUT/'oracle.tsv').read_text())
    stages = inventory()
    check(stages == manifest['stages'] and len(rows) == manifest['nativeRows'], 'Stale Show inventory')
    print(f'Show Word/list: {len(rows)} native/model rows, {len(inputs())} inputs, exact decimal/list character observations, 8 strict closure audits')

if __name__ == '__main__':
    main()
