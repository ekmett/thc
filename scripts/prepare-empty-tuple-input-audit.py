#!/usr/bin/env python3
"""Export exact empty-tuple input boundaries and verify native wraparound results."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'build/empty-tuple-input'
FIXTURE = ROOT / 'compiler/test-fixtures/EmptyTupleInputAudit.hs'
NATIVE = ROOT / 'compiler/test-fixtures/EmptyTupleInputAuditNative.hs'
INPUTS = [-(1 << 63), -4097, -1, 0, 1, 4097, 3000000000, (1 << 63) - 1]
FORMALS = {'before': [0], 'beforeBias': [1], 'between': [1], 'after': [2], 'identityEmpty': [0],
           'used': [0], 'emptyOnly': [0, 1], 'lazyInput': [0], 'pair': [0], 'self': [0],
           'mutualA': [0], 'mutualB': [1]}
STAGES = {'pre': 'optimized-Core-before-Tidy', 'post': 'optimized-Core-after-Tidy-before-CorePrep'}


def check(condition, message):
    if not condition:
        raise AssertionError(message)


def record(path):
    path = Path(path)
    return dict(path=str(path.relative_to(ROOT)) if path.is_relative_to(ROOT) else str(path),
                sha256=hashlib.sha256(path.read_bytes()).hexdigest())


def output(argv):
    return subprocess.check_output(argv, cwd=ROOT, text=True).strip()


def walk(value):
    yield value
    for child in value.values() if isinstance(value, dict) else value if isinstance(value, list) else []:
        yield from walk(child)


def empty(rep):
    return isinstance(rep, dict) and rep.get('aggregate') == 'unboxed-tuple' and rep.get('kind') == 'unknown' and rep.get('components') == [] and rep.get('primReps') == []


def inventory(stage):
    module = json.loads((OUT / f'{stage}-core/EmptyTupleInputAudit.json').read_text())
    check(module['ghc'] == '9.14.1' and module['boundary'] == STAGES[stage], 'Wrong native export boundary')
    bindings = {b['name']: b for b in module['bindings']}
    for name, positions in FORMALS.items():
        expression = bindings[name]['expr']
        check(expression[0] == 'lam', f'{stage}/{name}: lambda disappeared')
        actual = [i for i, binder in enumerate(expression[1]) if empty(binder.get('rep'))]
        check(actual == positions, f'{stage}/{name}: empty logical argument positions changed: {actual}')
        check(all(expression[1][i]['lifted'] is False for i in positions), 'Empty formal became lifted')
    apps = [node for node in walk(module) if isinstance(node, list) and node and node[0] == 'app']
    def calls(source, target):
        nodes = apps if source is None else [n for n in walk(bindings[source]['expr']) if isinstance(n, list) and n and n[0] == 'app']
        return [a for a in nodes if a[1][:2] == ['var', bindings[target]['id']]]
    for target, prefix in [('before', 1), ('emptyOnly', 2), ('between', 2)]:
        check(any(len(a[2]) == prefix for a in calls(None, target)), f'{stage}: real PAP prefix lost for {target}')
    arity = len(bindings['opaqueFunction']['expr'][1])
    over = calls('overCase', 'opaqueFunction')
    check(arity == 1 and any(len(a[2]) == 3 for a in over), f'{stage}: actual overapplication disappeared')
    for source, target in [('scalarControl', 'scalarBefore'), ('used', 'identityEmpty'), ('self', 'self'),
                           ('mutualA', 'mutualB'), ('mutualB', 'mutualA'), ('betweenInputs', 'between')]:
        check(calls(source, target), f'{stage}: retained {source}->{target} call disappeared')
    effect = bindings['effectEmpty']['expr'][2]
    check(effect[0] == 'case' and calls('effectEmpty', 'failureCheck'), 'Empty producer must demand the throwing scalar call')
    check(empty(bindings['identityEmpty']['expr'][3]['resultRep']), 'Used empty alias lost its exact result proof')
    return dict(stage=stage, boundary=module['boundary'], emptyFormalPositions=FORMALS,
                actualPapPrefixes={'before': 1, 'emptyOnly': 2, 'between': 2},
                overapplication=dict(formalArity=arity, actualArities=[len(a[2]) for a in over]),
                scalarDispatchControl=True, emptyEffectDemand=True)


def wrap(x):
    return (x + (1 << 63)) % (1 << 64) - (1 << 63)


def mutual(n, x):
    return x + 7 * (n // 2) + (2 if n % 2 else 0) + (11 if n % 2 else 7)


def expected_rows():
    formulas = {'scalarControl': lambda x: 3*x+7, 'beforeCase': lambda x: 3*x+7,
                'betweenCase': lambda x: 8*x+1, 'afterCase': lambda x: -6*x-21,
                'usedCase': lambda x: x+19, 'papEmptyCase': lambda x: 3*x+7,
                'papTwoEmptyCase': lambda x: x+29, 'papMixedCase': lambda x: 8*x+16,
                'overCase': lambda x: 4*x+7, 'lazyCase': lambda x: x+31,
                'pairCase': lambda x: 8*x-46, 'selfCase': lambda x: x+3*(x & 31),
                'mutualCase': lambda x: mutual(x & 31, x)}
    rows = {(name, x): wrap(f(x)) for name, f in formulas.items() for x in INPUTS}
    for n in [0, 1, 20001]:
        rows['selfDepth', n] = wrap(5+3*n)
        rows['mutualDepth', n] = wrap(mutual(n, 5))
    for name in ['effectCase', 'effectPapCase']:
        for x in [0, 1, 4097, (1 << 63)-1]:
            rows[name, x] = wrap(3*x+7)
    return rows


def verify_oracle():
    expected = expected_rows()
    actual = {}
    for line in (OUT / 'oracle.tsv').read_text().splitlines():
        name, x, result = line.split('\t')
        key = name, int(x)
        check(key not in actual, f'Duplicate native row {key}')
        actual[key] = int(result)
    check(actual == expected, 'Native one-input oracle disagrees with independent wraparound formulas')
    inputs = [(-(1 << 63), (1 << 63)-1), ((1 << 63)-1, -(1 << 63)), (0, 0), (0, 1), (1, 0), (-7, 19), (4097, -3000000000)]
    pairs = []
    for line in (OUT / 'oracle-pairs.tsv').read_text().splitlines():
        name, x, y, result = line.split('\t')
        check(name == 'betweenInputs', 'Unexpected two-input entry')
        pairs.append((int(x), int(y), int(result)))
    check(pairs == [(x, y, wrap(3*x+5*y+11)) for x, y in inputs], 'Native independent-input oracle mismatch')
    return dict(rows=len(actual), pairRows=len(pairs), entries=list(dict.fromkeys(k[0] for k in expected)),
                pairEntry='betweenInputs', arithmetic='signed 64-bit wraparound',
                exceptions='Negative effectCase/effectPapCase paths are exported for JVM rejection tests; native oracle rows use nonnegative inputs.')


def prepare():
    ghc, pkg = os.environ.get('GHC', 'ghc'), os.environ.get('GHC_PKG', 'ghc-pkg')
    check(output([ghc, '--numeric-version']) == '9.14.1', 'Requires GHC 9.14.1')
    OUT.mkdir(parents=True, exist_ok=True)
    native = OUT / 'native'
    native.mkdir(exist_ok=True)
    commands = []
    def run(argv, env=None):
        commands.append(dict(argv=argv, cwd=str(ROOT), environment=env or {}))
        subprocess.run(argv, cwd=ROOT, env=dict(os.environ, **(env or {})), check=True)
    run(['compiler/build.sh'])
    run([ghc, '--make', '-O2', '-fforce-recomp', '-dcore-lint', '-icompiler/test-fixtures',
         '-odir', str(native), '-hidir', str(native), '-o', str(native / 'empty-tuple-input'), str(NATIVE)])
    for file, flags in [('oracle.tsv', []), ('oracle-pairs.tsv', ['--pairs'])]:
        argv = [str(native / 'empty-tuple-input'), *flags]
        commands.append(dict(argv=argv, stdout=str(OUT / file)))
        (OUT / file).write_text(output(argv) + '\n')
    entries = list(dict.fromkeys(name for name, _ in expected_rows())) + ['betweenInputs']
    for stage in STAGES:
        run(['compiler/export.sh', *(['-fplugin-opt=THC.Plugin:post-tidy'] if stage == 'post' else []), str(FIXTURE)],
            dict(THC_CORE_OUT=str(OUT / f'{stage}-core'), THC_GHC_OUT=str(OUT / f'{stage}-ghc'), THC_SOURCE_NOTES='true'))
        run(['python3', 'scripts/audit-core.py', str(OUT / f'{stage}-core/EmptyTupleInputAudit.json'),
             *[part for entry in entries for part in ['--entry', entry]], '--output', str(OUT / f'{stage}-audit.json')])
        check(json.loads((OUT / f'{stage}-audit.json').read_text())['accepted'] is True, f'{stage}: strict audit rejected')
    sources = [FIXTURE, NATIVE, Path(__file__).resolve(), ROOT / 'compiler/build.sh', ROOT / 'compiler/export.sh',
               ROOT / 'compiler/toolchain.sh', *sorted((ROOT / 'compiler/THC').glob('*.hs')),
               ROOT / 'scripts/audit-core.py', ROOT / 'scripts/core-capabilities.json', ROOT / 'scripts/core_vectors.py',
               ROOT / 'src/main/resources/thc/scalar-primop-signatures.json']
    artifacts = [p for directory in ('native', 'pre-core', 'post-core') for p in sorted((OUT / directory).rglob('*')) if p.is_file()]
    artifacts += [OUT / name for name in ['oracle.tsv', 'oracle-pairs.tsv', 'pre-audit.json', 'post-audit.json']]
    return dict(schema=1, recordedAtUtc=datetime.now(timezone.utc).isoformat(), commands=commands,
                sources=[record(p) for p in sources], artifacts=[record(p) for p in artifacts],
                toolchain=dict(ghc=record(Path(shutil.which(ghc) or ghc).resolve()), ghcPkg=record(Path(shutil.which(pkg) or pkg).resolve()),
                               info=output([ghc, '--info']), packages=output([pkg, 'list', '--simple-output'])),
                claim='Fresh native semantics and exact pre/post Core shape; compiled runtime execution is tested separately.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check-only', action='store_true')
    args = parser.parse_args()
    path = OUT / 'provenance.json'
    provenance = json.loads(path.read_text()) if args.check_only else prepare()
    for item in provenance['sources'] + provenance['artifacts'] + [provenance['toolchain']['ghc'], provenance['toolchain']['ghcPkg']]:
        check(record(ROOT / item['path']) == item, 'Stale empty-input evidence: ' + item['path'])
    coverage, oracle = [inventory(stage) for stage in STAGES], verify_oracle()
    if not args.check_only:
        path.write_text(json.dumps(provenance, indent=2) + '\n')
    (OUT / 'checks.json').write_text(json.dumps(dict(schema=1, coverage=coverage, nativeOracle=oracle, provenance=record(path)), indent=2) + '\n')
    print(f'Exact-empty input fixture: {oracle["rows"]} native rows + {oracle["pairRows"]} independent-input rows; both stages strict accepted')


if __name__ == '__main__':
    main()
