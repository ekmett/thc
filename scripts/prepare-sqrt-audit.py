#!/usr/bin/env python3
"""Native public sqrt oracle, exact rational rounding model, and pre/post Core."""
import argparse
from fractions import Fraction
import hashlib
import json
import math
import os
from pathlib import Path
import random
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'build/sqrt'
FIXTURE = ROOT / 'compiler/test-fixtures/SqrtAudit.hs'
NATIVE = ROOT / 'compiler/test-fixtures/SqrtAuditNative.hs'
ENTRIES = ['sqrtFloat', 'sqrtDouble', 'floatCase', 'doubleCase']
INTEGER_INPUTS = [0, 1, 2, 3, 4, 15, 16, 17, 81, 65535, 65536, 1 << 20, 1 << 24]


def check(condition, detail):
    if not condition:
        raise AssertionError(detail)


def record(path):
    return dict(path=str(path.relative_to(ROOT)), sha256=hashlib.sha256(path.read_bytes()).hexdigest())


def format_fields(width):
    return (23, 8, 127) if width == 32 else (52, 11, 1023)


def exact_value(bits, width):
    """Positive finite IEEE value, without host floating-point arithmetic."""
    fraction_bits, exponent_bits, bias = format_fields(width)
    fraction = bits & ((1 << fraction_bits) - 1)
    exponent = (bits >> fraction_bits) & ((1 << exponent_bits) - 1)
    coefficient = fraction if exponent == 0 else (1 << fraction_bits) | fraction
    shift = max(exponent, 1) - bias - fraction_bits
    return Fraction(coefficient << max(shift, 0), 1 << max(-shift, 0))


def sqrt_bits(bits, width):
    """Round the exact square root by rational midpoint comparisons, ties to even.

    Binary search finds the adjacent representable outputs using squared values;
    comparing the squared midpoint avoids approximate sqrt in the model itself.
    None denotes NaN: arithmetic payload and sign are deliberately unspecified.
    """
    fraction_bits, exponent_bits, _ = format_fields(width)
    sign = 1 << (width - 1)
    infinity = ((1 << exponent_bits) - 1) << fraction_bits
    magnitude = bits & (sign - 1)
    if magnitude == 0:
        return bits
    if bits & sign or magnitude > infinity:
        return None
    if magnitude == infinity:
        return infinity
    value = exact_value(bits, width)
    low, high = 0, infinity - 1
    while low < high:
        mid = (low + high + 1) // 2
        candidate = exact_value(mid, width)
        if candidate * candidate <= value:
            low = mid
        else:
            high = mid - 1
    lower = exact_value(low, width)
    if lower * lower == value:
        return low
    midpoint = (lower + exact_value(low + 1, width)) / 2
    square = midpoint * midpoint
    return low if value < square or value == square and low % 2 == 0 else low + 1


def bit_inputs(width):
    fraction, exponent, bias = format_fields(width)
    sign = 1 << (width - 1)
    infinity = ((1 << exponent) - 1) << fraction
    one = bias << fraction
    four = (bias + 2) << fraction
    values = [0, sign, 1, 2, 3, (1 << fraction) - 1, 1 << fraction, (1 << fraction) + 1,
              one - 1, one, one + 1, four - 1, four, four + 1, infinity - 1, infinity,
              infinity | (1 << (fraction - 1)), infinity | 1,
              infinity | (1 << (fraction - 1)) | 12345]
    values += [value | sign for value in values[2:]]
    rng = random.Random(0x53515254 + width)
    values += [rng.randrange(infinity) for _ in range(128)]
    values += [sign | rng.randrange(infinity) for _ in range(32)]
    return list(dict.fromkeys(values))


def input_rows():
    return [(name, bits) for name, width in [('sqrtFloat', 32), ('sqrtDouble', 64)] for bits in bit_inputs(width)] + \
           [(name, value) for name in ['floatCase', 'doubleCase'] for value in INTEGER_INPUTS]


def check_native():
    rows = [line.split('\t') for line in (OUT / 'oracle.tsv').read_text().splitlines()]
    check([(name, int(bits)) for name, bits, _ in rows] == input_rows(), 'Native input coverage differs')
    nan_rows = 0
    for name, bits, actual in rows:
        bits, actual = int(bits), int(actual)
        if name in ('floatCase', 'doubleCase'):
            check(actual == math.isqrt(bits), f'Native integer consumer differs: {name}/{bits}')
            continue
        width = 32 if name == 'sqrtFloat' else 64
        expected = sqrt_bits(bits, width)
        if expected is None:
            fraction, exponent, _ = format_fields(width)
            check(actual & (((1 << exponent) - 1) << fraction) == ((1 << exponent) - 1) << fraction
                  and actual & ((1 << fraction) - 1) != 0, f'Expected native NaN: {name}/{bits}')
            nan_rows += 1
        else:
            check(actual == expected, f'Native exact sqrt rounding differs: {name}/{bits}: {actual} != {expected}')
    return dict(nativeRows=len(rows), nanClassificationRows=nan_rows,
                exactBitRows=len(rows) - nan_rows - 2 * len(INTEGER_INPUTS), integerRows=2 * len(INTEGER_INPUTS))


def walk(value):
    yield value
    for child in value.values() if isinstance(value, dict) else value if isinstance(value, list) else []:
        yield from walk(child)


def audit_inputs():
    return [ROOT / 'scripts/audit-core.py', ROOT / 'scripts/core-capabilities.json',
            *sorted((ROOT / 'scripts').glob('core_*.py')), ROOT / 'src/main/resources/thc/scalar-primop-signatures.json',
            ROOT / 'scripts/generate-scalar-signatures.py']


def inventory(stage):
    module = json.loads((OUT / f'{stage}-core/SqrtAudit.json').read_text())
    expected_boundary = 'optimized-Core-before-Tidy' if stage == 'pre' else 'optimized-Core-after-Tidy-before-CorePrep'
    check(module['boundary'] == expected_boundary, 'Wrong export boundary')
    bindings = {b['name']: b for b in module['bindings']}
    for name, kind, register in [('sqrtFloat', 'float', 'FloatRep'), ('sqrtDouble', 'double', 'DoubleRep')]:
        lam = bindings[name]['expr']
        proof = dict(kind=kind, evaluated=True, primReps=[register])
        result = lam[3]['resultRep']
        check(lam[0] == 'lam' and len(lam[1]) == 1 and lam[1][0]['rep'] == proof
              and result['kind'] == kind and result['primReps'] == [register],
              name + ': scalar signature changed')
        primitives = [n[1] for n in walk(lam[2]) if isinstance(n, list) and n and n[0] == 'prim']
        check(primitives == [name + '#'], name + ': public sqrt did not lower to its exact primop')
    audit = json.loads((OUT / f'{stage}-audit.json').read_text())
    check(audit['accepted'], stage + ': strict audit rejected a sqrt entry')
    return dict(stage=stage, audit=audit['summary'], primitives=['sqrtFloat#', 'sqrtDouble#'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check-only', action='store_true')
    args = parser.parse_args()
    provenance = OUT / 'provenance.json'
    if not args.check_only:
        ghc = os.environ.get('GHC', 'ghc')
        check(subprocess.check_output([ghc, '--numeric-version'], text=True).strip() == '9.14.1', 'Requires GHC9.14.1')
        (OUT / 'native').mkdir(parents=True, exist_ok=True)
        commands = []
        def run(argv, env=None):
            commands.append(dict(argv=list(map(str, argv)), environment=env or {}))
            subprocess.run(argv, cwd=ROOT, env=dict(os.environ, **(env or {})), check=True)
        (OUT / 'inputs.tsv').write_text(''.join(f'{name}\t{bits}\n' for name, bits in input_rows()))
        run([ghc, '--make', '-O2', '-fforce-recomp', '-dcore-lint', '-dstg-lint', '-icompiler/test-fixtures',
             '-odir', OUT / 'native', '-hidir', OUT / 'native', '-o', OUT / 'native/oracle', NATIVE])
        argv = [str(OUT / 'native/oracle'), str(OUT / 'inputs.tsv')]
        commands.append(dict(argv=argv, stdout=str(OUT / 'oracle.tsv')))
        rows = subprocess.check_output(argv, text=True)
        (OUT / 'oracle.tsv').write_text(rows)
        (OUT / 'integer-oracle.tsv').write_text('\n'.join(row for row in rows.splitlines() if 'Case\t' in row) + '\n')
        check_native()
        for stage in ['pre', 'post']:
            run(['compiler/export.sh', *(['-fplugin-opt=Thc.Plugin:post-tidy'] if stage == 'post' else []), str(FIXTURE)],
                dict(THC_CORE_OUT=str(OUT / f'{stage}-core'), THC_GHC_OUT=str(OUT / f'{stage}-ghc'), THC_SOURCE_NOTES='true'))
            run([sys.executable, 'scripts/audit-core.py', str(OUT / f'{stage}-core/SqrtAudit.json'),
                 *[part for entry in ENTRIES for part in ('--entry', entry)], '--output', str(OUT / f'{stage}-audit.json')])
            inventory(stage)
        sources = [FIXTURE, NATIVE, Path(__file__).resolve(), ROOT / 'compiler/build.sh', ROOT / 'compiler/export.sh',
                   ROOT / 'compiler/toolchain.sh', *sorted((ROOT / 'compiler/Thc').glob('*.hs')), *audit_inputs()]
        artifacts = [p for folder in ['native', 'pre-core', 'post-core'] for p in sorted((OUT / folder).rglob('*')) if p.is_file()]
        artifacts += [OUT / name for name in ['inputs.tsv', 'oracle.tsv', 'integer-oracle.tsv', 'pre-audit.json', 'post-audit.json']]
        provenance.write_text(json.dumps(dict(schema=1, ghcInfo=subprocess.check_output([ghc, '--info'], text=True),
            sources=[record(p) for p in sources], artifacts=[record(p) for p in artifacts], commands=commands,
            model='Exact rational squared midpoint comparisons; nearest, ties to even. Arithmetic NaNs compare by classification.'), indent=2) + '\n')
    evidence = json.loads(provenance.read_text())
    check({str(p.relative_to(ROOT)) for p in audit_inputs()} <= {r['path'] for r in evidence['sources']}, 'Missing auditor inputs')
    for item in evidence['sources'] + evidence['artifacts']:
        check(record(ROOT / item['path']) == item, 'Stale sqrt evidence: ' + item['path'])
    checks = dict(**check_native(), coverage=[inventory(stage) for stage in ['pre', 'post']], provenance=record(provenance))
    (OUT / 'checks.json').write_text(json.dumps(checks, indent=2) + '\n')
    print('Sqrt native/model:', json.dumps(checks, sort_keys=True))


if __name__ == '__main__':
    main()
