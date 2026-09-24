#!/usr/bin/env python3
"""Build a native empty-join oracle and verify exact GHC joins before/after Tidy."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'build/empty-join-input'
SOURCE = ROOT / 'compiler/test-fixtures/EmptyJoinInputAudit.hs'
NATIVE = ROOT / 'compiler/test-fixtures/EmptyJoinInputAuditNative.hs'
INPUTS = [-(1 << 63), -4097, -1, 0, 1, 4097, 3000000000, (1 << 63)-1]
PRODUCERS = ['branchCase', 'swapDepth', 'mutualDepth', 'nestedCase', 'tupleResult', 'effectCase', 'throwCase']


def walk(value):
    yield value
    for child in value.values() if isinstance(value, dict) else value if isinstance(value, list) else []:
        yield from walk(child)


def record(path):
    return dict(path=str(path.relative_to(ROOT)), sha256=hashlib.sha256(path.read_bytes()).hexdigest())


def mutual(n, x):
    n = max(n, 0)
    return x + 7 * (n // 2) + (2 if n % 2 else 0) + (13 if n % 2 else 11)


def verify():
    formulas = dict(branchCase=lambda x: x+(-24 if x <= 0 else 48),
                    swapCase=lambda x: 18 if x & 1 else -18,
                    mutualCase=lambda x: mutual(x & 31, 5),
                    nestedCase=lambda x: x+(12 if x <= 0 else 26),
                    lazyCase=lambda x: x+(4 if x <= 0 else 12), effectCase=lambda x: x & 255)
    wrap = lambda n: (n+(1 << 63)) % (1 << 64)-(1 << 63)
    expected = {(name, x): wrap(f(x)) for name, f in formulas.items() for x in INPUTS}
    expected.update({('throwCase', x): wrap(x+101) for x in (0, 1, 4097, (1 << 63)-1)})
    expected.update({('swapDepth', n): 18 if n & 1 else -18 for n in (0, 1, 20001)})
    expected.update({('mutualDepth', n): mutual(n, 5) for n in (0, 1, 20001)})
    rows = [line.split('\t') for line in (OUT/'oracle.tsv').read_text().splitlines()]
    actual = {(name, int(x)): int(y) for name, x, y in rows}
    assert len(rows) == len(actual) and actual == expected, 'Native oracle differs from independent 64-bit formulas'
    spec = importlib.util.spec_from_file_location('audit_core', ROOT/'scripts/audit-core.py')
    auditor = importlib.util.module_from_spec(spec); spec.loader.exec_module(auditor)
    cap = json.loads((ROOT/'scripts/core-capabilities.json').read_text())
    stages = []
    for stage, boundary in [('pre', 'optimized-Core-before-Tidy'), ('post', 'optimized-Core-after-Tidy-before-CorePrep')]:
        module = json.loads((OUT/f'{stage}-core/EmptyJoinInputAudit.json').read_text())
        assert module['ghc'] == '9.14.1' and module['boundary'] == boundary
        bindings = {b['name']: b for b in module['bindings']}
        joins = {}
        for name in PRODUCERS:
            definitions = [v for v in walk(bindings[name]['expr']) if isinstance(v, dict) and 'joinValueArity' in v]
            assert definitions, f'{stage}/{name}: no genuine GHC joins retained'
            for definition in definitions:
                params = definition['expr'][1][:definition['joinValueArity']]
                empties = [p for p in params if auditor.Audit.is_empty_tuple(p.get('rep'))]
                assert empties and all(p['lifted'] is False for p in empties), 'Missing exact empty join input'
                assert all(not auditor.Audit.is_tuple(p['rep']) or auditor.Audit.is_empty_tuple(p['rep']) for p in params)
            joins[name] = [dict(id=d['id'], arity=d['joinValueArity'], result=d['joinResultRep'],
                               parameters=d['expr'][1][:d['joinValueArity']]) for d in definitions]
        assert any(p['rep']['kind'] == 'void' for j in joins['effectCase'] for p in j['parameters']), 'Scalar State control erased'
        assert any(j['result'].get('aggregate') == 'unboxed-tuple' for j in joins['tupleResult'])
        assert any(v == ['prim', 'writeWord8Array#'] or isinstance(v, list) and v[:2] == ['prim', 'writeWord8Array#'] for v in walk(module))
        # These calls must remain actual join operands, not merely earlier case scrutinees.
        for owner, producer in [('effectCase', 'effectEmpty'), ('throwCase', 'checkedEmpty')]:
            ids = {j['id'] for j in joins[owner]}
            calls = [v for v in walk(bindings[owner]['expr']) if isinstance(v, list) and v[:1] == ['app']
                     and v[1][0] == 'var' and v[1][1] in ids]
            assert len(calls) == 1 and calls[0][3][0] is False
            actual = calls[0][2][0]
            assert actual[:1] == ['app'] and actual[1][:2] == ['var', bindings[producer]['id']]
            assert auditor.Audit.is_empty_tuple(auditor.Audit.expression_rep(actual))
        run_rw = [v for v in walk(bindings['effectCase']['expr']) if isinstance(v, list)
                  and v[:1] == ['app'] and v[1][0] == 'lam']
        assert len(run_rw) == 1 and len(run_rw[0][1][1]) == 1 and len(run_rw[0][2]) == 1
        assert run_rw[0][1][1][0]['rep']['kind'] == 'void', 'Expected one retained State lambda guest entry'
        lazy = [p['rep'] for j in joins['tupleResult'] for p in j['parameters'] if p['rep']['kind'] == 'data']
        assert lazy and all(p['evaluated'] is False for p in lazy), 'Lifted neighbor must remain lazy'
        report = auditor.Audit([(stage, module)], cap).run(sorted({name for name, _ in expected}))
        assert report['accepted'], report['issues']
        (OUT/f'{stage}-audit.json').write_text(json.dumps(report, indent=2)+'\n')
        stages.append(dict(stage=stage, joins=joins, reachableBindings=report['summary']['reachableBindings']))
    result = dict(rows=len(rows), stages=stages)
    (OUT/'checks.json').write_text(json.dumps(result, indent=2)+'\n')
    print(json.dumps(dict(rows=len(rows), joins={v['stage']:sum(map(len,v['joins'].values())) for v in stages})))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check-only', action='store_true')
    args = parser.parse_args()
    if args.check_only:
        provenance = json.loads((OUT/'provenance.json').read_text())
        for item in provenance['inputs'] + provenance['artifacts']:
            assert record(ROOT/item['path']) == item, f'Changed provenance input/artifact: {item["path"]}'
    else:
        OUT.mkdir(parents=True, exist_ok=True)
        ghc = os.environ.get('GHC', 'ghc')
        version = subprocess.check_output([ghc, '--numeric-version'], text=True).strip()
        assert version == '9.14.1', f'Exact GHC 9.14.1 required, got {version}'
        commands = [['compiler/build.sh']]
        subprocess.run(commands[0], cwd=ROOT, check=True)
        for stage in ('pre', 'post'):
            command = ['compiler/export.sh'] + (['-fplugin-opt=THC.Plugin:post-tidy'] if stage == 'post' else []) + [str(SOURCE.relative_to(ROOT))]
            env = dict(os.environ, THC_CORE_OUT=str(OUT/f'{stage}-core'), THC_GHC_OUT=str(OUT/f'{stage}-ghc'))
            subprocess.run(command, cwd=ROOT, env=env, check=True); commands.append(command)
        native = OUT/'native'; native.mkdir(exist_ok=True)
        command = [ghc, '--make', '-v0', '-O2', '-fforce-recomp', '-dcore-lint', '-icompiler/test-fixtures',
                   '-odir', str(native), '-hidir', str(native), '-o', str(native/'oracle'), str(NATIVE)]
        subprocess.run(command, cwd=ROOT, check=True); commands.append(command)
        (OUT/'oracle.tsv').write_text(subprocess.check_output([str(native/'oracle')], text=True))
        inputs = [SOURCE, NATIVE, Path(__file__).resolve(), ROOT/'compiler/build.sh', ROOT/'compiler/export.sh', ROOT/'compiler/toolchain.sh',
                  *sorted((ROOT/'compiler/THC').glob('*.hs')), ROOT/'scripts/audit-core.py',
                  *sorted((ROOT/'scripts').glob('core_*.py')), ROOT/'scripts/core-capabilities.json',
                  ROOT/'src/main/resources/thc/scalar-primop-signatures.json']
        verify()
        artifacts = [OUT/'checks.json', OUT/'pre-audit.json', OUT/'post-audit.json', OUT/'oracle.tsv', OUT/'pre-core/EmptyJoinInputAudit.json', OUT/'post-core/EmptyJoinInputAudit.json', native/'oracle']
        provenance = dict(ghc=version, ghcInfo=subprocess.check_output([ghc, '--info'], text=True), commands=commands,
                          inputs=list(map(record, inputs)), artifacts=list(map(record, artifacts)))
        (OUT/'provenance.json').write_text(json.dumps(provenance, indent=2)+'\n')
    if args.check_only:
        verify()


if __name__ == '__main__':
    main()
