#!/usr/bin/env python3
"""Native Data.Complex/IEEE oracle and strict floating tuple result evidence."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'build/floating-tuple'
FIXTURE = ROOT / 'compiler/test-fixtures/FloatingTupleAudit.hs'
NATIVE = ROOT / 'compiler/test-fixtures/FloatingTupleAuditNative.hs'
INPUTS = [-128, -31, -7, -1, 0, 1, 7, 31, 128]
ENTRIES = ['complexFloatCase', 'complexDoubleCase', 'mixedCase', 'joinedCase', 'ieeeCase']
STAGES = {'pre': 'optimized-Core-before-Tidy', 'post': 'optimized-Core-after-Tidy-before-CorePrep'}


def check(condition, message):
    if not condition:
        raise AssertionError(message)


def record(path):
    return dict(path=str(path.relative_to(ROOT)), sha256=hashlib.sha256(path.read_bytes()).hexdigest())


def audit_inputs():
    return [ROOT / 'scripts/audit-core.py', ROOT / 'scripts/core-capabilities.json',
            *sorted((ROOT / 'scripts').glob('core_*.py')),
            ROOT / 'src/main/resources/thc/scalar-primop-signatures.json',
            ROOT / 'scripts/generate-scalar-signatures.py']


def walk(value):
    yield value
    for child in value.values() if isinstance(value, dict) else value if isinstance(value, list) else []:
        yield from walk(child)


def inventory(stage):
    module = json.loads((OUT / f'{stage}-core/FloatingTupleAudit.json').read_text())
    check(module['boundary'] == STAGES[stage], 'Wrong Core boundary')
    bindings = {b['name']: b for b in module['bindings']}
    layouts = {name: bindings[name]['expr'][3]['resultRep'] for name in
               ['$wcomplexFloat', '$wcomplexDouble', 'mixed', 'mixedForward', 'joined', 'ieeePair']}
    for name, rep in [('$wcomplexFloat', 'FloatRep'), ('$wcomplexDouble', 'DoubleRep')]:
        proof = layouts[name]
        check(proof['aggregate'] == 'unboxed-tuple' and proof['primReps'] == [rep, rep]
              and [p['primReps'] for p in proof['components']] == [[rep], [rep]], name + ': missing actual CPR tuple')
    mixed = layouts['mixed']
    check(mixed['primReps'] == ['FloatRep', 'DoubleRep', 'BoxedRep (Just Lifted)'], 'Wrong mixed physical fields')
    check(mixed['components'][0] == dict(kind='void', primReps=[], evaluated=True), 'Lost logical State#')
    nested = mixed['components'][1]
    check(nested['aggregate'] == 'unboxed-tuple' and nested['components'][1]['components'] == [], 'Lost nested empty tuple')
    check(mixed['components'][2]['evaluated'] is False, 'Lifted leaf must stay lazy')
    check(layouts['mixedForward'] == mixed, 'Forwarder changed logical shape')
    joins = [n for n in walk(bindings['joined']) if isinstance(n, dict) and 'joinValueArity' in n]
    check(joins and all(j['joinResultRep']['primReps'] == ['FloatRep', 'DoubleRep'] for j in joins), 'Missing actual floating tuple join')
    boxed = [c for c in module['constructors'] if c['name'] == ':+']
    check(boxed and all(c['kind'] == 'boxed' for c in boxed), 'Complex is a boxed datatype')
    return dict(stage=stage, layouts=layouts, tupleJoins=len(joins), boxedComplex=True)


def check_native():
    expected = {(name, x): (-2*x*x - 11*x + 3 if name.startswith('complex') else
                           20*x - 23 if name == 'mixedCase' else 6*(2*x + (-3 if x <= 0 else 5)))
                for name in ENTRIES[:-1] for x in INPUTS}
    expected.update({('ieeeCase', x): answer for x, answer in enumerate([12, 0, 20, 40, 0, 0, 3, 12])})
    rows = [line.split('\t') for line in (OUT / 'oracle.tsv').read_text().splitlines()]
    actual = {(name, int(x)): int(y) for name, x, y in rows}
    check(len(rows) == len(expected) and actual == expected, 'Native oracle disagrees with independent exact formulas')
    bits = [tuple(map(int, row.split('\t'))) for row in (OUT / 'bits.tsv').read_text().splitlines()]
    expected_bits = [(0, 0, 0x8000000000000000), (1, 0x80000000, 0), (2, 1, 1),
                     (3, 0x80000001, 0x8000000000000001), (4, 0x7f800000, 0xfff0000000000000),
                     (5, 0xff800000, 0x7ff0000000000000), (7, 0x3fc00000, 0xc004000000000000)]
    check(len(bits) == 8 and [b for b in bits if b[0] != 6] == expected_bits, 'Native IEEE bit patterns differ')
    _, f, d = bits[6]
    # NaN arithmetic is not specified to choose a particular payload/sign.
    check(f & 0x7f800000 == 0x7f800000 and f & 0x007fffff != 0 and
          d & 0x7ff0000000000000 == 0x7ff0000000000000 and d & 0x000fffffffffffff != 0, 'Expected native NaNs')
    return len(rows), len(bits)


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
            commands.append(dict(argv=argv, environment=env or {}))
            subprocess.run(argv, cwd=ROOT, env=dict(os.environ, **(env or {})), check=True)
        if not (ROOT / 'build/compiler/package.conf.d').is_dir():
            run(['compiler/build.sh'])
        run([ghc, '--make', '-O2', '-fforce-recomp', '-dcore-lint', '-dstg-lint', '-icompiler/test-fixtures',
             '-odir', str(OUT / 'native'), '-hidir', str(OUT / 'native'), '-o', str(OUT / 'native/oracle'), str(NATIVE)])
        for filename, arguments in [('oracle.tsv', []), ('bits.tsv', ['bits'])]:
            argv = [str(OUT / 'native/oracle'), *arguments]
            commands.append(dict(argv=argv, stdout=str(OUT / filename)))
            (OUT / filename).write_text(subprocess.check_output(argv, text=True))
        check_native()
        for stage in STAGES:
            run(['compiler/export.sh', *(['-fplugin-opt=Thc.Plugin:post-tidy'] if stage == 'post' else []), str(FIXTURE)],
                dict(THC_CORE_OUT=str(OUT / f'{stage}-core'), THC_GHC_OUT=str(OUT / f'{stage}-ghc'), THC_SOURCE_NOTES='true'))
            run([sys.executable, 'scripts/audit-core.py', str(OUT / f'{stage}-core/FloatingTupleAudit.json'),
                 *[part for entry in ENTRIES for part in ('--entry', entry)], '--output', str(OUT / f'{stage}-audit.json')])
            inventory(stage)
        sources = [FIXTURE, NATIVE, Path(__file__).resolve(), ROOT / 'compiler/build.sh', ROOT / 'compiler/export.sh',
                   ROOT / 'compiler/toolchain.sh', *sorted((ROOT / 'compiler/Thc').glob('*.hs')), *audit_inputs()]
        artifacts = [p for folder in ('native', 'pre-core', 'post-core') for p in sorted((OUT / folder).rglob('*')) if p.is_file()]
        artifacts += [OUT / 'oracle.tsv', OUT / 'bits.tsv', *[OUT / f'{stage}-audit.json' for stage in STAGES]]
        provenance.write_text(json.dumps(dict(schema=1, recordedAtUtc=datetime.now(timezone.utc).isoformat(),
            commands=commands, ghcInfo=subprocess.check_output([ghc, '--info'], text=True),
            sources=[record(p) for p in sources], artifacts=[record(p) for p in artifacts],
            claim='Native semantic oracle and pre/post-Tidy proofs; installed guest execution is verified separately.'), indent=2) + '\n')
    evidence = json.loads(provenance.read_text())
    check({str(p.relative_to(ROOT)) for p in audit_inputs()} <= {r['path'] for r in evidence['sources']}, 'Missing auditor inputs')
    for item in evidence['sources'] + evidence['artifacts']:
        check(record(ROOT / item['path']) == item, 'Stale floating tuple evidence: ' + item['path'])
    rows, bits = check_native()
    coverage = [inventory(stage) for stage in STAGES]
    for stage in STAGES:
        check(json.loads((OUT / f'{stage}-audit.json').read_text())['accepted'], stage + ': strict audit failed')
    (OUT / 'checks.json').write_text(json.dumps(dict(schema=1, nativeRows=rows, nativeBitRows=bits,
        coverage=coverage, provenance=record(provenance)), indent=2) + '\n')
    print(f'Floating tuples: {rows} native/model rows, {bits} IEEE bit rows, exact pre/post layouts and strict audits')


if __name__ == '__main__':
    main()
