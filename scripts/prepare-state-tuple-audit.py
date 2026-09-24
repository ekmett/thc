#!/usr/bin/env python3
"""Native State# tuple oracle and exact pre/post-Tidy proof checks."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'build/state-tuple'
FIXTURE = ROOT / 'compiler/test-fixtures/StateTupleAudit.hs'
NATIVE = ROOT / 'compiler/test-fixtures/StateTupleAuditNative.hs'
INPUTS = [-(1 << 63), -4097, -1, 0, 1, 4097, (1 << 63) - 1]
STAGES = {'pre': 'optimized-Core-before-Tidy', 'post': 'optimized-Core-after-Tidy-before-CorePrep'}
ENTRIES = ['pairCase', 'lazyCase', 'captureCase', 'effectCase']


def audit_inputs():
    paths = [ROOT / 'scripts/audit-core.py', ROOT / 'scripts/core-capabilities.json',
             *sorted((ROOT / 'scripts').glob('core_*.py'))]
    # The shared table is an auditor input once that foundation is present.
    paths += [p for p in (ROOT / 'src/main/resources/thc/scalar-primop-signatures.json',
                          ROOT / 'scripts/generate-scalar-signatures.py') if p.exists()]
    return paths


def check(condition, message):
    if not condition:
        raise AssertionError(message)


def record(path):
    return dict(path=str(path.relative_to(ROOT)), sha256=hashlib.sha256(path.read_bytes()).hexdigest())


def walk(value):
    yield value
    for child in value.values() if isinstance(value, dict) else value if isinstance(value, list) else []:
        yield from walk(child)


def inventory(stage):
    module = json.loads((OUT / f'{stage}-core/StateTupleAudit.json').read_text())
    check(module['ghc'] == '9.14.1' and module['boundary'] == STAGES[stage], 'Wrong Core boundary')
    bindings = {b['name']: b for b in module['bindings']}
    tuple_ids = {c['id'] for c in module['constructors'] if c['kind'] == 'unboxed-tuple'}
    layouts = {name: bindings[name]['expr'][3]['resultRep'] for name in ('pair', 'zero', 'lazyPair', 'effectPair', 'byteShape')}
    void = dict(kind='void', primReps=[], evaluated=True)
    for name, proof in layouts.items():
        check(proof.get('aggregate') == 'unboxed-tuple' and proof['components'][0] == void, f'{name}: missing logical State#')
    check(layouts['pair']['primReps'] == ['IntRep'], 'State# must add no physical register')
    zero = layouts['zero']
    check(zero['primReps'] == [] and len(zero['components']) == 3 and
          zero['components'][1].get('aggregate') == 'unboxed-tuple' and zero['components'][1]['components'] == [] and
          zero['components'][2] == void, 'State# and nested empty tuple must retain distinct logical fields')
    check(layouts['lazyPair']['components'][1] == dict(kind='data', primReps=['BoxedRep (Just Lifted)'], evaluated=False),
          'Lifted payload became strict')
    check(layouts['byteShape']['components'][1] == dict(kind='object', primReps=['BoxedRep (Just Unlifted)'], evaluated=True),
          'ByteArray# must remain one unlifted reference')
    for name in ('pairCase', 'lazyCase', 'captureCase'):
        nodes = list(walk(bindings[name]['expr']))
        cases = [n for n in nodes if isinstance(n, list) and n and n[0] == 'case' and
                 any(alt[0] == 'data' and alt[1] in tuple_ids for alt in n[3])]
        check(cases, f'{name}: expected tuple case disappeared')
        check(all(n[-1]['binder']['rep'].get('aggregate') == 'unboxed-tuple' for n in cases),
              f'{name}: runRW# rewrite lost native tuple binder proof')
        check(any(isinstance(n, list) and n and n[0] == 'void' for n in nodes), f'{name}: missing lowered realWorld#')
    effect = bindings['effectPair']['expr'][2]
    check(any(isinstance(n, list) and n[:2] == ['var', bindings['failState']['id']] for n in walk(effect)),
          'State-producing effect must remain an operand')
    return dict(stage=stage, layouts=layouts, runRWProofsRetained=True, stateEffectRetained=True)


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
        run(['compiler/build.sh'])
        run([ghc, '--make', '-O2', '-fforce-recomp', '-dcore-lint', '-icompiler/test-fixtures',
             '-odir', str(OUT / 'native'), '-hidir', str(OUT / 'native'), '-o', str(OUT / 'native/state-tuple'), str(NATIVE)])
        commands.append(dict(argv=[str(OUT / 'native/state-tuple')], stdout=str(OUT / 'oracle.tsv')))
        (OUT / 'oracle.tsv').write_text(subprocess.check_output([str(OUT / 'native/state-tuple')], text=True))
        for stage in STAGES:
            run(['compiler/export.sh', *(['-fplugin-opt=THC.Plugin:post-tidy'] if stage == 'post' else []), str(FIXTURE)],
                dict(THC_CORE_OUT=str(OUT / f'{stage}-core'), THC_GHC_OUT=str(OUT / f'{stage}-ghc'), THC_SOURCE_NOTES='true'))
            run([sys.executable, 'scripts/audit-core.py', str(OUT / f'{stage}-core/StateTupleAudit.json'),
                 *[part for entry in ENTRIES for part in ('--entry', entry)], '--output', str(OUT / f'{stage}-audit.json')])
        sources = [FIXTURE, NATIVE, Path(__file__).resolve(), ROOT / 'compiler/build.sh', ROOT / 'compiler/export.sh',
                   ROOT / 'compiler/toolchain.sh', *sorted((ROOT / 'compiler/THC').glob('*.hs')), *audit_inputs()]
        artifacts = [p for folder in ('native', 'pre-core', 'post-core') for p in sorted((OUT / folder).rglob('*')) if p.is_file()]
        artifacts += [OUT / 'oracle.tsv', *[OUT / f'{stage}-audit.json' for stage in STAGES]]
        provenance.write_text(json.dumps(dict(schema=1, recordedAtUtc=datetime.now(timezone.utc).isoformat(),
            commands=commands, ghcInfo=subprocess.check_output([ghc, '--info'], text=True),
            sources=[record(p) for p in sources], artifacts=[record(p) for p in artifacts],
            claim='Genuine native semantic oracle and GHC pre/post-Tidy representation checks; runtime compilation is tested separately.'), indent=2) + '\n')
    evidence = json.loads(provenance.read_text())
    check({str(p.relative_to(ROOT)) for p in audit_inputs()} <= {r['path'] for r in evidence['sources']},
          'State# evidence does not record every current audit input')
    for item in evidence['sources'] + evidence['artifacts']:
        check(record(ROOT / item['path']) == item, 'Stale State# fixture evidence: ' + item['path'])
    rows = [line.split('\t') for line in (OUT / 'oracle.tsv').read_text().splitlines()]
    wrap = lambda x: (x + (1 << 63)) % (1 << 64) - (1 << 63)
    expected = {(name, x): wrap(x + add) for name, add in [('pairCase', 21), ('lazyCase', 0), ('captureCase', 14)] for x in INPUTS}
    actual = {(name, int(x)): int(y) for name, x, y in rows}
    check(len(rows) == len(expected) and actual == expected, 'Native oracle disagrees with independent wraparound formulas')
    coverage = [inventory(stage) for stage in STAGES]
    for stage in STAGES:
        check(json.loads((OUT / f'{stage}-audit.json').read_text())['accepted'], f'{stage}: strict audit failed')
    (OUT / 'checks.json').write_text(json.dumps(dict(schema=1, nativeRows=len(rows), coverage=coverage, provenance=record(provenance)), indent=2) + '\n')
    print(f'State# tuples: {len(rows)} verified native rows; zero-width logical fields and runRW# proofs preserved at both stages')


if __name__ == '__main__':
    main()
