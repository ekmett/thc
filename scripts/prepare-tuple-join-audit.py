#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Build a native tuple-join oracle and verify exact GHC joins before/after Tidy."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'build/tuple-join'
SOURCE = ROOT / 'compiler/test-fixtures/TupleJoinAudit.hs'
NATIVE = ROOT / 'compiler/test-fixtures/TupleJoinAuditNative.hs'
INPUTS = [-(1 << 63), -4097, -1, 0, 1, 4097, 3000000000, (1 << 63)-1]
PRODUCERS = ['forward', 'recursive', 'mutual', 'nestedForward', 'empty', 'nested']


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
    formulas = dict(forwardCase=lambda x: 3*(x+(-24 if x <= 0 else 48)),
                    recursiveCase=lambda x: x+3*(x & 31)+23,
                    mutualCase=lambda x: 5*mutual(x & 31, x),
                    nestedForwardCase=lambda x: x+(-17 if x <= 0 else -3),
                    emptyCase=lambda x: x+37, nestedCase=lambda x: x+32)
    wrap = lambda n: (n+(1 << 63)) % (1 << 64)-(1 << 63)
    expected = {(name, x): wrap(f(x)) for name, f in formulas.items() for x in INPUTS}
    expected.update({('recursiveDepth', n): 5+3*n for n in (0, 1, 20000)})
    expected.update({('mutualDepth', n): mutual(n, 5) for n in (0, 1, 20001)})
    rows = [line.split('\t') for line in (OUT/'oracle.tsv').read_text().splitlines()]
    actual = {(name, int(x)): int(y) for name, x, y in rows}
    assert len(rows) == len(actual) and actual == expected, 'Native oracle differs from independent 64-bit formulas'
    spec = importlib.util.spec_from_file_location('audit_core', ROOT/'scripts/audit-core.py')
    auditor = importlib.util.module_from_spec(spec); spec.loader.exec_module(auditor)
    cap = json.loads((ROOT/'scripts/core-capabilities.json').read_text())
    stages = []
    for stage, boundary in [('pre', 'optimized-Core-before-Tidy'), ('post', 'optimized-Core-after-Tidy-before-CorePrep')]:
        module = json.loads((OUT/f'{stage}-core/TupleJoinAudit.json').read_text())
        assert module['ghc'] == '9.14.1' and module['boundary'] == boundary
        bindings = {b['name']: b for b in module['bindings']}
        joins = {}
        for name in PRODUCERS:
            definitions = [v for v in walk(bindings[name]['expr']) if isinstance(v, dict) and 'joinValueArity' in v]
            assert definitions, f'{stage}/{name}: no genuine GHC joins retained'
            for definition in definitions:
                proof = definition['joinResultRep']
                assert proof.get('aggregate') == 'unboxed-tuple' and isinstance(proof.get('components'), list)
                params = definition['expr'][1] if definition['expr'][0] == 'lam' else []
                assert not any('aggregate' in p['rep'] for p in params), 'Aggregate join argument outside this slice'
                leaves = [v for v in walk(proof) if isinstance(v, dict) and v.get('kind') == 'data']
                assert all(v['evaluated'] is False for v in leaves), 'Tuple result must not force lazy Box fields'
            joins[name] = [dict(id=d['id'], arity=d['joinValueArity'], result=d['joinResultRep']) for d in definitions]
        report = auditor.Audit([(stage, module)], cap).run(sorted({name for name, _ in expected}))
        assert report['accepted'], report['issues']
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
        commands = []
        for stage in ('pre', 'post'):
            command = ['compiler/export.sh'] + (['-fplugin-opt=THC.Plugin:post-tidy'] if stage == 'post' else []) + [str(SOURCE.relative_to(ROOT))]
            env = dict(os.environ, THC_CORE_OUT=str(OUT/f'{stage}-core'), THC_GHC_OUT=str(OUT/f'{stage}-ghc'))
            subprocess.run(command, cwd=ROOT, env=env, check=True); commands.append(command)
        native = OUT/'native'; native.mkdir(exist_ok=True)
        command = [ghc, '--make', '-v0', '-O2', '-fforce-recomp', '-dcore-lint', '-icompiler/test-fixtures',
                   '-odir', str(native), '-hidir', str(native), '-o', str(native/'oracle'), str(NATIVE)]
        subprocess.run(command, cwd=ROOT, check=True); commands.append(command)
        (OUT/'oracle.tsv').write_text(subprocess.check_output([str(native/'oracle')], text=True))
        inputs = [SOURCE, NATIVE, ROOT/'compiler/THC/Plugin.hs', ROOT/'compiler/export.sh', ROOT/'compiler/toolchain.sh']
        artifacts = [OUT/'oracle.tsv', OUT/'pre-core/TupleJoinAudit.json', OUT/'post-core/TupleJoinAudit.json', native/'oracle']
        provenance = dict(ghc=version, ghcInfo=subprocess.check_output([ghc, '--info'], text=True), commands=commands,
                          inputs=list(map(record, inputs)), artifacts=list(map(record, artifacts)))
        (OUT/'provenance.json').write_text(json.dumps(provenance, indent=2)+'\n')
    verify()


if __name__ == '__main__':
    main()
