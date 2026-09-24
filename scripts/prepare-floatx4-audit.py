#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Pinned real FloatX4 Core, independent binary32 model, and optional native oracle."""
import argparse
import hashlib
import importlib.util
import json
import math
import os
from pathlib import Path
import platform
import shutil
import struct
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'build/simd-floatx4'
FIXTURE = ROOT / 'compiler/test-fixtures/SimdFloatX4.hs'
NATIVE = ROOT / 'compiler/test-fixtures/SimdFloatX4Native.hs'
FINITE = [-33554435, -16777219, -16777217, -16777216, -16777215, -3, -2, -1,
          0, 1, 2, 3, 16777215, 16777216, 16777217, 16777219, 33554435]
PRIMITIVES = {p + 'FloatX4#' for p in ('broadcast', 'pack', 'unpack', 'plus', 'minus', 'times')}
STAGES = {'pre': 'optimized-Core-before-Tidy', 'post': 'optimized-Core-after-Tidy-before-CorePrep'}
EDGE_BITS = [0, 0x80000000, 1, 0x80000001, 0x007fffff, 0x807fffff,
             0x00800000, 0x80800000, 0x7f7fffff, 0xff7fffff, 0x7f800000,
             0xff800000, 0x7fc00000, 0x3f000000, 0xbf000000, 0x3f800000,
             0xbf800000, 0x4b800000, 0x4b800001, 3, 0x80000003]


def check(condition, message):
    if not condition:
        raise AssertionError(message)


def f32(x):
    try:
        return struct.unpack('!f', struct.pack('!f', x))[0]
    except OverflowError:
        return math.copysign(math.inf, x)


def bits(x):
    return struct.unpack('!I', struct.pack('!f', x))[0]


def from_bits(x):
    return struct.unpack('!f', struct.pack('!I', x))[0]


def classify(x):
    # Independent integer IEEE encoding checks; no floating reciprocal model.
    b = bits(x)
    if b & 0x7fffffff > 0x7f800000:
        return 0
    codes = {0: 1, 0x80000000: 2, 0x7f800000: 3, 0xff800000: 4,
             1: 5, 0x80000001: 6, 0x007fffff: 7, 0x807fffff: 8,
             0x00800000: 9, 0x80800000: 10, 0x7f7fffff: 11, 0xff7fffff: 12,
             2: 15, 0x80000002: 16, 0x4b800002: 17, 0xcb800002: 18}
    return codes.get(b, 13 if b >> 31 else 14)


def classes(values):
    return sum(classify(x) << (5*i) for i, x in enumerate(values))


def expected(name, *args):
    if name in ('plusCase', 'minusCase', 'timesCase'):
        lanes = list(map(f32, args))
        if name == 'plusCase':
            result = [f32(x + 1.0) for x in lanes]
        elif name == 'minusCase':
            result = [f32(x - 0.5) for x in lanes]
        else:
            result = [f32(x * scale) for x, scale in zip(lanes, (0.5, -2.0, 1.5, -0.25))]
        answer = 0
        for x, weight in zip(result, (7, 11, 13, 17)):
            scaled = f32(x * 4.0)
            check(math.isfinite(scaled) and -(1 << 63) <= scaled < (1 << 63), 'Unsafe float2Int corpus')
            answer ^= int(scaled) * weight
        return (answer + (1 << 63)) % (1 << 64) - (1 << 63)
    if name in ('edgePlus', 'edgeMinus', 'edgeTimes'):
        lanes = [from_bits(EDGE_BITS[n]) for n in args[:4]]
        y = from_bits(EDGE_BITS[args[4]])
        op = {'edgePlus': lambda x: x+y, 'edgeMinus': lambda x: x-y, 'edgeTimes': lambda x: x*y}[name]
        return classes([f32(op(x)) for x in lanes])
    if name == 'nonFmaCase':
        x = f32(f32(args[0] & 1) + from_bits(0x3f800001))
        product = f32(x * from_bits(0x3f7ffffe))
        return classes([f32(product - 1.0)] * 4)
    raise ValueError(name)


def entries():
    n = len(FINITE)
    finite_cases = [[a, b, FINITE[(i+3*j) % n], FINITE[(3*i+j+1) % n]]
                    for i, a in enumerate(FINITE) for j, b in enumerate(FINITE)]
    # Each lane independently visits every pair of edge operands; offsets also
    # make lanes distinct so sign/classification permutations are observable.
    edge_cases = [[a, (a+1) % 21, (a+7) % 21, (a+13) % 21, b]
                  for a in range(21) for b in range(21)]
    return [dict(name=name, arity=4, cases=finite_cases) for name in ('plusCase', 'minusCase', 'timesCase')] + [
        dict(name=name, arity=5, cases=edge_cases) for name in ('edgePlus', 'edgeMinus', 'edgeTimes')] + [
        dict(name='nonFmaCase', arity=1, cases=[[n] for n in (-2, -1, 0, 1, 2, 3)])]


def model_rows():
    return {(entry['name'], *xs): expected(entry['name'], *xs)
            for entry in entries() for xs in entry['cases']}


def parse_rows(text):
    rows = {}
    arities = {e['name']: e['arity'] for e in entries()}
    for line in text.splitlines():
        name, *fields = line.split('\t')
        check(name in arities and len(fields) == arities[name]+1, 'Wrong native row shape: '+line)
        key = (name, *map(int, fields[:-1]))
        check(key not in rows, 'Duplicate native row: '+line)
        rows[key] = int(fields[-1])
    return rows


def walk(value):
    yield value
    for child in value.values() if isinstance(value, dict) else value if isinstance(value, list) else []:
        yield from walk(child)


def rep(expression):
    return expression[-1].get('rep') if isinstance(expression, list) and expression and isinstance(expression[-1], dict) else None


def float_tuple(proof):
    return bool(proof and proof.get('aggregate') == 'unboxed-tuple' and proof.get('primReps') == ['FloatRep']*4
                and len(proof.get('components', [])) == 4 and all(
                    c.get('kind') == 'float' and c.get('primReps') == ['FloatRep'] for c in proof['components']))


def inventory(module, stage):
    check(module['boundary'] == STAGES[stage], 'Wrong Core boundary')
    vectors = [v for v in walk(module) if isinstance(v, dict) and v.get('kind') == 'vector']
    check(vectors and all(v.get('vector') == {'lanes': 4, 'element': 'FloatElemRep'}
          and v.get('primReps') == ['VecRep 4 FloatElemRep'] and 'aggregate' not in v for v in vectors),
          'Missing or inexact FloatX4 vector metadata')
    applications = [v for v in walk(module['bindings']) if isinstance(v, list) and len(v) > 2 and v[0] == 'app'
                    and isinstance(v[1], list) and v[1][:1] == ['prim'] and v[1][1] in PRIMITIVES]
    check({v[1][1] for v in applications} == PRIMITIVES, 'GHC removed a required vector operation')
    packs = [v for v in applications if v[1][1] == 'packFloatX4#']
    for call in packs:
        check(len(call[2]) == 1, 'packFloatX4# must have ONE logical tuple argument')
        check(float_tuple(rep(call[2][0])), 'Pack must retain four Float# tuple leaves')
    unpacked = [v for v in applications if v[1][1] == 'unpackFloatX4#']
    check(all(float_tuple(rep(v)) for v in unpacked), 'Unpack result is not four Float# leaves')
    bindings = {b['name']: b for b in module['bindings']}
    for entry in entries():
        lam = bindings[entry['name']]['expr']
        check(lam[0] == 'lam' and len(lam[1]) == entry['arity']
              and all(arg['rep'].get('primReps') == ['IntRep'] for arg in lam[1])
              and lam[3]['resultRep'].get('primReps') == ['IntRep'], 'Host entry ABI drift: '+entry['name'])
    frontier = bindings['vectorArgument']['expr']
    check(frontier[0] == 'lam' and frontier[1][0]['rep'].get('kind') == 'vector', 'Missing actual vector formal frontier')
    return dict(vectorProofs=len(vectors), packSites=len(packs), unpackSites=len(unpacked), primitives=sorted(PRIMITIVES))


def record(path):
    return dict(path=str(path.relative_to(ROOT)), sha256=hashlib.sha256(path.read_bytes()).hexdigest())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--export-only', action='store_true', help='Pre-Tidy/Core + model only; no code generation, native oracle or post-Tidy claim')
    args = parser.parse_args()
    ghc = os.environ.get('GHC', 'ghc')
    check(subprocess.check_output([ghc, '--numeric-version'], text=True).strip() == '9.14.1', 'Requires pinned GHC9.14.1')
    OUT.mkdir(parents=True, exist_ok=True)
    # Never leave an old native/provenance claim attached to a new incomplete run.
    for name in ('oracle.tsv', 'provenance.json'):
        (OUT / name).unlink(missing_ok=True)
    commands = []
    def run(argv, env=None):
        commands.append(dict(argv=argv, environment=env or {}))
        subprocess.run(argv, cwd=ROOT, env=dict(os.environ, **(env or {})), check=True)
    wanted = model_rows()
    (OUT / 'expected.tsv').write_text(''.join('\t'.join(map(str, (*key, answer)))+'\n' for key, answer in wanted.items()))
    run(['compiler/build.sh'])
    stages = ['pre'] if args.export_only else ['pre', 'post']
    spec = importlib.util.spec_from_file_location('floatx4_auditor', ROOT / 'scripts/audit-core.py')
    auditor = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(auditor)
    capabilities = json.loads((ROOT / 'scripts/core-capabilities.json').read_text())
    audits, structure = {}, {}
    artifacts = [OUT / 'expected.tsv']
    for stage in stages:
        module_path = OUT / f'{stage}-core/SimdFloatX4.json'
        module_path.unlink(missing_ok=True)
        options = ['-fno-code', '-fwrite-if-simplified-core'] if args.export_only else []
        if stage == 'post':
            options += ['-fplugin-opt=THC.Plugin:post-tidy']
        run(['compiler/export.sh', *options, str(FIXTURE)],
            dict(THC_CORE_OUT=str(module_path.parent), THC_GHC_OUT=str(OUT / f'{stage}-ghc'), THC_SOURCE_NOTES='true'))
        module = json.loads(module_path.read_text())
        structure[stage] = inventory(module, stage)
        audits[stage] = {name: auditor.Audit([(str(module_path), module)], capabilities).run([name])
                         for name in [e['name'] for e in entries()] + ['vectorArgument']}
        check(not audits[stage]['vectorArgument']['accepted'] and any(
              i['code'] == 'vector-boundary' and i['detail'] == 'vector formal argument'
              for i in audits[stage]['vectorArgument']['issues']), 'Vector ABI frontier was not rejected specifically')
        audit_path = OUT / f'{stage}-audit.json'
        audit_path.write_text(json.dumps(audits[stage], indent=2)+'\n')
        artifacts += [module_path, audit_path]
    native_rows = None
    if not args.export_only:
        native = OUT / 'native'
        native.mkdir(exist_ok=True)
        binary = native / 'floatx4-oracle'
        run([ghc, '--make', '-O2', '-fforce-recomp', '-dcore-lint', '-dstg-lint', '-icompiler/test-fixtures',
             '-odir', str(native), '-hidir', str(native), '-o', str(binary), str(NATIVE)])
        commands.append(dict(argv=[str(binary)], stdout=str(OUT / 'oracle.tsv')))
        output = subprocess.check_output([str(binary)], cwd=ROOT, text=True)
        (OUT / 'oracle.tsv').write_text(output)
        actual = parse_rows(output)
        check(actual == wanted, 'Native FloatX4 oracle disagrees with independent binary32 model: '+str(
            next(((k, actual.get(k), v) for k, v in wanted.items() if actual.get(k) != v), 'extra native rows')))
        native_rows = len(actual)
        artifacts += [OUT / 'oracle.tsv', binary]
    sources = [FIXTURE, NATIVE, Path(__file__).resolve(), ROOT / 'scripts/test-floatx4-model.py',
               ROOT / 'scripts/audit-core.py', ROOT / 'scripts/core-capabilities.json',
               ROOT / 'src/main/resources/thc/scalar-primop-signatures.json',
               *sorted((ROOT / 'scripts').glob('core_*.py')), *sorted((ROOT / 'compiler/THC').glob('*.hs')),
               *[ROOT / 'compiler' / name for name in ('build.sh', 'export.sh', 'toolchain.sh')]]
    positives = all(audits[s][e['name']]['accepted'] for s in stages for e in entries())
    provenance = dict(schema=1, vector='floatx4', stages=stages, nativeRows=native_rows,
        modelMatched=True if native_rows is not None else None, modelRows=len(wanted), entries=entries(),
        frontiers=[dict(name='vectorArgument', arity=1, reason='vector formal argument remains unsupported')],
        positiveAuditsAccepted=positives, audits=audits, structure=structure, commands=commands,
        sources=[record(p) for p in sources], artifacts=[record(p) for p in artifacts],
        toolchain=dict(ghcVersion='9.14.1', host=platform.node(), machine=platform.machine(), system=platform.platform(),
            ghcInfo=subprocess.check_output([ghc, '--info'], text=True)),
        claim='Native/model comparison plus exact Core metadata and static audit; no installed guest or hardware SIMD claim.'
              if native_rows is not None else 'Pre-Tidy Core and independent model only; NO native/post-Tidy validation.',
        limitations=['Arithmetic NaN payload/sign unspecified; edge results classify them.',
                     'Finite arithmetic checks use bounded float2Int signatures; exceptional cases never convert to Int.',
                     'Vector function/formal/capture/join ABI is not enabled by these local vector fixtures.'])
    (OUT / 'provenance.json').write_text(json.dumps(provenance, indent=2)+'\n')
    print(f'FloatX4 stages={stages}, native rows={native_rows}, model rows={len(wanted)}, positive strict audits={positives}')
    check(positives, 'FloatX4 strict audit rejected a positive entry; see stage audit reports (no support claim)')


if __name__ == '__main__':
    main()
