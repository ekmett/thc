#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Export and strictly audit local SIMD with a small scalar-entry JVM corpus."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess

from simd_family_model import FAMILIES, entries, result, signed

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'build/simd-capability-smoke'
FIXTURE = ROOT / 'build/generated/simd/fixtures/GeneratedSimdFamilies.hs'
NAMES = ('timesWord64X2', 'timesWord32X8', 'timesInt32X8', 'timesInt32X16',
         'floatX8Composite', 'doubleX4Composite')


def module(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    loaded = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(loaded)
    return loaded


def record(path):
    return dict(path=str(path.relative_to(ROOT)), sha256=hashlib.sha256(path.read_bytes()).hexdigest())


def rows():
    families = {family['name']: family for family in FAMILIES}
    for name in NAMES:
        family_name = name.removesuffix('Composite').removeprefix('times')
        family_name = family_name[0].upper() + family_name[1:]
        family = families[family_name]
        lanes = (0, family['lanes'] - 1)
        if family.get('composite'):
            operations = [op for op in family['operations'] if op not in ('pack', 'unpack')]
            one, two = ((0x3f800000, 0x40000000) if family['laneRep'] == 'FloatRep'
                        else (0x3ff0000000000000, 0x4000000000000000))
            for index, operation in enumerate(operations):
                for lane in lanes:
                    a = signed(one if operation == 'broadcast' else one - lane * 104729)
                    b = signed(two + lane * 7919)
                    yield name, index * family['lanes'] + lane, a, b, result(family, operation, lane, a, b)
        else:
            for lane in lanes:
                for left, right in ((7, -3), (-1, 2), (2147483647, 2)):
                    a, b = signed(left - lane * 104729), signed(right + lane * 7919)
                    yield name, lane, a, b, result(family, 'times', lane, a, b)


def main():
    ghc = os.environ.get('GHC', 'ghc')
    if subprocess.check_output([ghc, '--numeric-version'], text=True).strip() != '9.14.1':
        raise RuntimeError('Local SIMD capability smoke requires GHC 9.14.1')
    generator = module(ROOT / 'scripts/generate-simd-families.py', 'simd_generator')
    contracts = generator.contracts(generator.families())
    capabilities = json.loads((ROOT / 'scripts/core-capabilities.json').read_text())
    if {name: capabilities['primitives'].get(name) for name in contracts} != {
            name: contract['arity'] for name, contract in contracts.items()}:
        raise RuntimeError('Canonical SIMD primitive capability differs from the pinned contracts')
    shapes = {(item['lanes'], item['element']) for item in capabilities['vectorRepresentations']}
    if any((family['lanes'], family['element']) not in shapes for family in generator.families()):
        raise RuntimeError('Canonical SIMD vector representation is missing')
    selected = {name for name, _, _ in entries()}
    if not set(NAMES) <= selected:
        raise RuntimeError('A scalar-entry SIMD smoke fixture disappeared')
    subprocess.run(['python3', 'scripts/generate-simd-families.py', '--check', '--verify-ghc'],
                   cwd=ROOT, check=True)
    OUT.mkdir(parents=True, exist_ok=True)
    core_dir = OUT / 'pre-core'
    subprocess.run(['compiler/export.sh', '-fno-code', '-fwrite-if-simplified-core', str(FIXTURE)],
                   cwd=ROOT, check=True, env=dict(os.environ, THC_CORE_OUT=str(core_dir),
                                                  THC_GHC_OUT=str(OUT / 'ghc'), THC_SOURCE_NOTES='true'))
    core = core_dir / 'GeneratedSimdFamilies.json'
    exported = json.loads(core.read_text())
    auditor = module(ROOT / 'scripts/audit-core.py', 'simd_auditor')
    preparation = module(ROOT / 'scripts/prepare-simd-families.py', 'simd_preparation')
    audits = {name: auditor.Audit([(str(core), exported)], capabilities).run([name])
              for name, _, _ in entries()}
    structure = preparation.structure(exported, audits, set(contracts))
    audits_path = OUT / 'audits.json'
    audits_path.write_text(json.dumps(audits, indent=2) + '\n')
    selected_rows = list(rows())
    cases = OUT / 'cases.tsv'
    cases.write_text(''.join('\t'.join(map(str, row)) + '\n' for row in selected_rows))
    sources = [ROOT / 'scripts' / name for name in (
        'core-capabilities.json', 'simd-families.json', 'generate-simd-families.py',
        'simd_family_model.py', 'prepare-simd-families.py', 'prepare-simd-capability-smoke.py',
        'audit-core.py')]
    sources += sorted((ROOT / 'scripts').glob('core_*.py'))
    sources += sorted((ROOT / 'compiler/THC').glob('*.hs'))
    sources += [ROOT / 'compiler' / name for name in ('build.sh', 'export.sh', 'toolchain.sh')]
    sources += [FIXTURE, ROOT / 'src/main/resources/thc/scalar-primop-signatures.json']
    manifest = dict(schema=1, scope='canonical local SIMD; scalar-entry finite smoke, no vector ABI',
                    ghcVersion='9.14.1', rows=len(selected_rows), names=list(NAMES),
                    structure=structure, inputs=[record(path) for path in sources],
                    artifacts=[record(path) for path in (core, audits_path, cases)])
    (OUT / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    print(f'Local SIMD smoke: {len(contracts)} canonical operations, {len(selected_rows)} finite rows')


if __name__ == '__main__':
    main()
