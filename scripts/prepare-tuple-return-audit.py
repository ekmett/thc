#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Native tuple-return oracle and genuine pre/post-Tidy exports, without THC claims."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'build/tuple-return'
FIXTURE = ROOT / 'compiler/test-fixtures/TupleReturnAudit.hs'
NATIVE = ROOT / 'compiler/test-fixtures/TupleReturnAuditNative.hs'
INPUTS = [-(1 << 63), -4097, -1, 0, 1, 4097, 3000000000, (1 << 63) - 1]
PRODUCERS = ['empty', 'emptyForward', 'singleInt', 'singleIntForward', 'singleBox',
             'singleClosure', 'nested', 'unliftedBoxedLeaf', 'selfTail', 'mutualA',
             'mutualB', 'pair', 'nonTailTuple', 'prefixed', 'applyTuple']
STAGES = {'pre': 'optimized-Core-before-Tidy', 'post': 'optimized-Core-after-Tidy-before-CorePrep'}


def check(condition, message):
    if not condition:
        raise AssertionError(message)


def output(argv):
    return subprocess.check_output(argv, cwd=ROOT, text=True).strip()


def record(path):
    path = Path(path)
    label = str(path.relative_to(ROOT)) if path.is_relative_to(ROOT) else str(path)
    return dict(path=label, sha256=hashlib.sha256(path.read_bytes()).hexdigest())


def walk(value):
    yield value
    for child in value.values() if isinstance(value, dict) else value if isinstance(value, list) else []:
        yield from walk(child)


def nodes(value, tag):
    return [n for n in walk(value) if isinstance(n, list) and n and n[0] == tag]


def inventory(stage):
    module = json.loads((OUT / f'{stage}-core/TupleReturnAudit.json').read_text())
    check(module['ghc'] == '9.14.1' and module['boundary'] == STAGES[stage], 'Wrong GHC/export boundary')
    bindings = {b['name']: b for b in module['bindings']}
    layouts = {}
    for name in PRODUCERS:
        expr = bindings[name]['expr']
        check(expr[0] == 'lam', f'{stage}/{name}: lost function boundary')
        proof = expr[3]['resultRep']
        check(proof.get('aggregate') == 'unboxed-tuple' and isinstance(proof.get('components'), list),
              f'{stage}/{name}: lost exact tuple result layout')
        check(all('aggregate' not in arg['rep'] for arg in expr[1]), f'{name}: aggregate formal outside this slice')
        layouts[name] = proof
    check(layouts['empty']['components'] == [] and layouts['empty']['primReps'] == [], 'Empty tuple became scalar void')
    check(len(layouts['singleInt']['components']) == 1 and layouts['singleInt']['primReps'] == ['IntRep'],
          'Singleton tuple became scalar Int#')
    for name, kind in (('singleBox', 'data'), ('singleClosure', 'closure')):
        child, = layouts[name]['components']
        check(child == dict(kind=kind, evaluated=False, primReps=['BoxedRep (Just Lifted)']),
              f'{name}: lazy lifted payload proof changed')
    nested = layouts['nested']['components']
    check(len(nested) == 2 and len(nested[0]['components']) == 2 and
          nested[1]['components'][0]['components'] == [], 'Nested nonzero/empty logical components flattened')
    boxed = layouts['unliftedBoxedLeaf']['components'][0]
    check(boxed == dict(kind='data', evaluated=True, primReps=['BoxedRep (Just Unlifted)']),
          'Unlifted boxed product leaf became an unboxed aggregate')
    product = next(c for c in module['constructors'] if c['name'] == 'UnliftedProduct')
    check(product['kind'] == 'boxed' and product['fieldTypes'][1]['evaluated'] is False,
          'Unlifted boxed outer WHNF must not force its lifted Box payload')

    def calls(source, target):
        return [n for n in nodes(bindings[source]['expr'], 'app')
                if n[1][0] == 'var' and n[1][1] == bindings[target]['id']]

    for source, target in (('emptyForward', 'empty'), ('singleIntForward', 'singleInt'),
                           ('selfTail', 'selfTail'), ('mutualA', 'mutualB'), ('mutualB', 'mutualA')):
        check(calls(source, target), f'{stage}/{source}: opaque/tail call to {target} disappeared')
    first = bindings['nonTailTuple']['expr'][2]
    check(first[0] == 'case' and first[1][0] == 'app' and first[1][1][1] == bindings['pair']['id'],
          'First non-tail tuple call disappeared')
    first_alt = first[3][0]
    second = first_alt[3]
    check(second[0] == 'case' and second[1][0] == 'app' and second[1][1][1] == bindings['singleInt']['id'],
          'Second non-tail tuple call disappeared')
    uses = {n[1] for n in nodes(second[3][0][3], 'var')}
    check(set(first_alt[2]) <= uses, 'First result must remain live across the second call')
    prefixed_arity = len(bindings['prefixed']['expr'][1])
    pap = calls('papCase', 'prefixed')
    check(prefixed_arity == 2 and any(len(n[2]) == 1 for n in pap), 'Native scalar-prefix PAP was optimized away')
    opaque_arity = len(bindings['opaqueFunction']['expr'][1])
    over = calls('overapplicationCase', 'opaqueFunction')
    overapplied = any(len(n[2]) > opaque_arity for n in over)
    # Report the actual Core outcome; never label an exact application as overapplication.
    check(over, 'Opaque function-returning call disappeared')
    return dict(stage=stage, boundary=module['boundary'], producerLayouts=layouts,
                scalarPrefixPapRetained=True, opaqueFunctionArity=opaque_arity,
                opaqueFunctionCallArities=[len(n[2]) for n in over], overapplicationRetained=overapplied)


def wrap(x):
    return (x + (1 << 63)) % (1 << 64) - (1 << 63)


def mutual(n, acc):
    n = max(n, 0)
    acc += 7 * (n // 2) + (2 if n % 2 else 0)
    return 10 * acc + 7 * (13 if n % 2 else 11)


def oracle():
    formulas = {
        'emptyCase': lambda x: x + 17,
        'singleIntCase': lambda x: 3*x + 732,
        'singleBoxCase': lambda x: x - 5 if x <= 0 else 7*x + 217,
        'singleClosureCase': lambda x: x + 9 if x <= 0 else 4*x - 51,
        'nestedCase': lambda x: 63*x - 20,
        'unliftedBoxedLeafCase': lambda x: 8*x + 26,
        'selfTailCase': lambda x: 12*x + 36*(x & 31) + 49,
        'mutualTailCase': lambda x: mutual(x & 31, x),
        'nonTailCase': lambda x: 99*x - 3124,
        'papCase': lambda x: 14*x - 102,
        'overapplicationCase': lambda x: 14*x - 102,
    }
    expected = {(name, x): wrap(formula(x)) for name, formula in formulas.items() for x in INPUTS}
    expected.update({('selfTailDepth', n): wrap(12*(5 + 3*n) + 49) for n in (0, 1, 20000)})
    expected.update({('mutualTailDepth', n): wrap(mutual(n, 5)) for n in (0, 1, 20001)})
    actual = {}
    for line in (OUT / 'oracle.tsv').read_text().splitlines():
        name, x, value = line.split('\t')
        key = name, int(x)
        check(key not in actual, f'Duplicate native row {key}')
        actual[key] = int(value)
    check(actual == expected, f'Native oracle disagrees with independent 64-bit formulas: '
          f'{[(k, actual.get(k), v) for k, v in expected.items() if actual.get(k) != v][:5]}')
    return dict(rows=len(actual), entries=list(formulas) + ['selfTailDepth', 'mutualTailDepth'],
                inputs=INPUTS, deepInputs={'selfTailDepth': [0, 1, 20000], 'mutualTailDepth': [0, 1, 20001]},
                ordinaryRecursionDepth='input low 5 bits, at most 31', arithmetic='signed 64-bit wraparound')


def prepare():
    ghc = os.environ.get('GHC', 'ghc')
    pkg = os.environ.get('GHC_PKG', 'ghc-pkg')
    check(output([ghc, '--numeric-version']) == '9.14.1', 'Requires GHC 9.14.1')
    check(output([pkg, '--version']) == 'GHC package manager version 9.14.1', 'Requires ghc-pkg 9.14.1')
    OUT.mkdir(parents=True, exist_ok=True)
    native = OUT / 'native'
    native.mkdir(exist_ok=True)
    commands = []

    def run(argv, env=None):
        commands.append(dict(argv=argv, cwd=str(ROOT), environment=env or {}))
        subprocess.run(argv, cwd=ROOT, env=dict(os.environ, **(env or {})), check=True)

    run(['compiler/build.sh'])
    run([ghc, '--make', '-O2', '-fforce-recomp', '-dcore-lint', '-icompiler/test-fixtures',
         '-odir', str(native), '-hidir', str(native), '-o', str(native / 'tuple-return'), str(NATIVE)])
    commands.append(dict(argv=[str(native / 'tuple-return')], stdout=str(OUT / 'oracle.tsv')))
    (OUT / 'oracle.tsv').write_text(output([str(native / 'tuple-return')]) + '\n')
    for stage in STAGES:
        run(['compiler/export.sh', *(['-fplugin-opt=THC.Plugin:post-tidy'] if stage == 'post' else []), str(FIXTURE)],
            dict(THC_CORE_OUT=str(OUT / f'{stage}-core'), THC_GHC_OUT=str(OUT / f'{stage}-ghc'), THC_SOURCE_NOTES='true'))
    sources = [FIXTURE, NATIVE, Path(__file__).resolve(), ROOT / 'compiler/build.sh',
               ROOT / 'compiler/export.sh', ROOT / 'compiler/toolchain.sh', *sorted((ROOT / 'compiler/THC').glob('*.hs')),
               ROOT / 'thc.cabal', ROOT / 'cabal.project']
    artifacts = [p for d in ('native', 'pre-core', 'pre-ghc', 'post-core', 'post-ghc')
                 for p in sorted((OUT / d).rglob('*')) if p.is_file()]
    plugin_manifest = ROOT / 'build/compiler/plugin.json'
    plugin = json.loads(plugin_manifest.read_text())
    check(plugin['schema'] == 1 and plugin['unitId'] and plugin['sharedLibrary'], 'Invalid plugin manifest')
    artifacts += [OUT / 'oracle.tsv', plugin_manifest, Path(plugin['sharedLibrary'])]
    return dict(schema=1, recordedAtUtc=datetime.now(timezone.utc).isoformat(), commands=commands,
                toolchain=dict(ghc=record(Path(shutil.which(ghc) or ghc).resolve()), ghcPkg=record(Path(shutil.which(pkg) or pkg).resolve()),
                               ghcInfo=output([ghc, '--info']), libdir=output([ghc, '--print-libdir']),
                               packages={p: output([pkg, 'describe', p]) for p in
                                         ('ghc', 'base', 'ghc-internal', 'ghc-prim', 'bytestring', 'containers', 'directory', 'filepath')},
                               environment={k: os.environ[k] for k in ('GHC', 'GHC_PKG', 'GHC_ENVIRONMENT', 'GHC_PACKAGE_PATH', 'GHCRTS') if k in os.environ}),
                sources=[record(p) for p in sources], artifacts=[record(p) for p in artifacts],
                claim='Native semantic oracle and exact Core structure only; THC execution and register allocation require separate runtime tests and graphs.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check-only', action='store_true')
    args = parser.parse_args()
    path = OUT / 'provenance.json'
    provenance = json.loads(path.read_text()) if args.check_only else prepare()
    for item in provenance['sources'] + provenance['artifacts'] + [provenance['toolchain']['ghc'], provenance['toolchain']['ghcPkg']]:
        check(record(ROOT / item['path']) == item, 'Stale fixture evidence: ' + item['path'])
    coverage = [inventory(stage) for stage in STAGES]
    native = oracle()
    if not args.check_only:
        path.write_text(json.dumps(provenance, indent=2) + '\n')
    (OUT / 'checks.json').write_text(json.dumps(dict(schema=1, coverage=coverage, nativeOracle=native,
                                                  provenance=record(path)), indent=2) + '\n')
    print(f'Tuple return fixture: {native["rows"]} verified native rows, {len(PRODUCERS)} producers in both stages; '
          f'PAP retained; overapplication retained: {[c["overapplicationRetained"] for c in coverage]}')


if __name__ == '__main__':
    main()
