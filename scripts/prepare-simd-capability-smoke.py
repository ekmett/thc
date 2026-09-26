#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Prepare bounded SIMD composites against native Haskell scalar lane arithmetic.

The default native oracle contains no vectors and needs no AVX512. Enable the
additional native vector comparison only on a host supporting the selected ISA.
"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'build/simd-capability-smoke'
GENERATED = ROOT / 'build/generated/simd/fixtures'
FIXTURE = GENERATED / 'GeneratedSimdSmoke.hs'


def module(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    loaded = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(loaded)
    return loaded


def record(path):
    return dict(path=str(path.relative_to(ROOT)), sha256=hashlib.sha256(path.read_bytes()).hexdigest())


def inputs(generator):
    def signed(value):
        return (value + (1 << 63)) % (1 << 64) - (1 << 63)
    groups = generator.smoke_groups(generator.families())
    owners = {index: name for name, indices in groups.items()
              for index in indices}
    entries = generator.smoke_entries(generator.families())
    for index in (index for indices in groups.values() for index in indices):
        family, operation = entries[index]
        rep = family['laneRep']
        if rep in ('FloatRep', 'DoubleRep'):
            one, two, sign = ((0x3f800000, 0x40000000, 1 << 31) if rep == 'FloatRep'
                             else (0x3ff0000000000000, 0x4000000000000000, 1 << 63))
            pairs = ((one, two), (one | sign, two), (sign, one))
            if operation in ('min', 'max'):
                pairs = ((one, two), (two, one), (one, one), (one | sign, two),
                         (two, one | sign), (sign, one), (one, sign), (1, 3), (3, 1))
            if operation == 'insert':
                nan = (0x7fc01234 if rep == 'FloatRep' else 0x7ff8000000001234)
                pairs = ((one, two), (one, sign), (sign, one), (one, one | sign),
                         (one, nan), (nan, one), (1, 3))
        else:
            width = family['bits'] // family['lanes']
            pairs = ((7, -3), (-1, 2), ((1 << (width - 1)) - 1, 2),
                     (-(1 << (width - 1)), -1), ((1 << width) - 1, 1))
            if operation == 'insert':
                pairs = ((7, -3), (-1, 2), (7, (1 << (width - 1)) - 1),
                         (7, -(1 << (width - 1))), (7, (1 << width) - 1))
        if operation == 'insert':
            for inserted in range(family['lanes']):
                for lane in range(family['lanes']):
                    for left, right in pairs if inserted == lane else pairs[:1]:
                        yield owners[index], index * 4096 + inserted * 64 + lane, signed(left - lane * 104729), signed(right)
            continue
        # Every lane is observed once; first/last lanes also get wrap and sign edges.
        for lane in range(family['lanes']):
            for left, right in pairs if lane in (0, family['lanes'] - 1) else pairs[:1]:
                a = signed(left if operation == 'broadcast' else left - lane * 104729)
                b = signed(right + lane * 7919)
                yield owners[index], index * 4096 + lane, a, b


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--native-vector', action='store_true')
    parser.add_argument('--ghc-option', action='append', default=[])
    args = parser.parse_args()
    ghc = os.environ.get('GHC', 'ghc')
    if subprocess.check_output([ghc, '--numeric-version'], text=True).strip() != '9.14.1':
        raise RuntimeError('Local SIMD capability smoke requires GHC 9.14.1')
    generator = module(ROOT / 'scripts/generate-simd-families.py', 'simd_generator')
    contracts = generator.contracts(generator.families())
    capabilities = json.loads((ROOT / 'scripts/core-capabilities.json').read_text())
    if {name: capabilities['primitives'].get(name) for name in contracts} != {
            name: contract['arity'] for name, contract in contracts.items()}:
        raise RuntimeError('Canonical SIMD capability differs from the pinned contracts')
    subprocess.run(['python3', 'scripts/generate-simd-families.py', '--check', '--verify-ghc'], cwd=ROOT, check=True)
    OUT.mkdir(parents=True, exist_ok=True)
    core_dir = OUT / 'pre-core'
    subprocess.run(['compiler/export.sh', '-fno-code', '-fwrite-if-simplified-core', str(FIXTURE)],
                   cwd=ROOT, check=True, env=dict(os.environ, THC_CORE_OUT=str(core_dir),
                                                  THC_GHC_OUT=str(OUT / 'ghc'), THC_SOURCE_NOTES='true'))
    core = core_dir / 'GeneratedSimdSmoke.json'
    exported = json.loads(core.read_text())
    auditor = module(ROOT / 'scripts/audit-core.py', 'simd_auditor')
    groups = generator.smoke_groups(generator.families())
    audits = {name: auditor.Audit([(str(core), exported)], capabilities).run([name]) for name in groups}
    used = {p['name'] for audit in audits.values() for p in audit['primitives']}
    for name, audit in audits.items():
        if not audit['accepted'] or audit['missingGlobals'] or audit['issues']:
            raise RuntimeError(f'{name}: Composite SIMD audit failed: {audit["issues"]}')
    if not set(contracts) <= used:
        raise RuntimeError(f'Composite SIMD operations disappeared: {set(contracts) - used}')
    audits_path = OUT / 'audits.json'
    audits_path.write_text(json.dumps(audits, indent=2) + '\n')
    requests = ''.join('\t'.join(map(str, row)) + '\n' for row in inputs(generator))
    native = OUT / 'native'
    native.mkdir(exist_ok=True)
    binary = native / 'simd-smoke-oracle'
    suffix = 'Vector' if args.native_vector else 'Scalar'
    subprocess.run([ghc, '--make', '-O2', '-fforce-recomp', '-dcore-lint', '-dstg-lint', *args.ghc_option,
                    '-i' + str(GENERATED), '-odir', str(native), '-hidir', str(native), '-o', str(binary),
                    str(GENERATED / f'GeneratedSimdSmoke{suffix}Native.hs')], cwd=ROOT, check=True)
    actual = subprocess.run([binary], input=requests, text=True, capture_output=True, check=True, timeout=60).stdout
    if len(actual.splitlines()) != len(requests.splitlines()):
        raise RuntimeError('Native scalar SIMD oracle row count differs')
    cases = OUT / 'cases.tsv'
    cases.write_text(actual)
    sources = [ROOT / 'scripts' / name for name in (
        'core-capabilities.json', 'simd-families.json', 'generate-simd-families.py',
        'prepare-simd-capability-smoke.py', 'audit-core.py')]
    sources += sorted((ROOT / 'scripts').glob('core_*.py'))
    sources += sorted((ROOT / 'compiler/THC').glob('*.hs'))
    sources += [ROOT / 'compiler' / name for name in ('build.sh', 'export.sh', 'toolchain.sh')]
    sources += sorted(GENERATED.glob('GeneratedSimdSmoke*.hs'))
    sources += [ROOT / 'src/main/resources/thc/scalar-primop-signatures.json']
    manifest = dict(schema=1, scope='local SIMD with exact lanes; no vector ABI or hardware-SIMD guarantee',
                    ghcVersion='9.14.1', rows=len(actual.splitlines()), names=list(groups),
                    nativeOracle='scalar-and-vector' if args.native_vector else 'scalar',
                    ghcOptions=args.ghc_option, operations=sorted(contracts),
                    selectors={str(index): operation + family['name'] + '#'
                               for index, (family, operation) in enumerate(generator.smoke_entries(generator.families()))},
                    floatingExtrema='java-math', nativeFloatingExtrema='finite-without-mixed-zero-ties',
                    inputs=[record(path) for path in sources],
                    artifacts=[record(path) for path in (core, audits_path, cases, binary)])
    (OUT / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    print(f'Local SIMD smoke: {len(contracts)} operations, {manifest["rows"]} native {manifest["nativeOracle"]} rows')


if __name__ == '__main__':
    main()
