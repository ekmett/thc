#!/usr/bin/env python3
"""Export pinned GHC scalar floating Core and check native IEEE edge expectations."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import struct
import subprocess

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'build/floating'
FIXTURE = ROOT / 'compiler/test-fixtures/FloatingAudit.hs'
NATIVE = ROOT / 'compiler/test-fixtures/FloatingAuditNative.hs'
GHC = os.environ.get('GHC', 'ghc')
PRIMITIVES = ['plusFloat#', 'minusFloat#', 'timesFloat#', 'divideFloat#', 'negateFloat#',
              '+##', '-##', '*##', '/##', 'negateDouble#',
              'eqFloat#', 'neFloat#', 'ltFloat#', 'leFloat#', 'gtFloat#', 'geFloat#',
              '==##', '/=##', '<##', '<=##', '>##', '>=##',
              'int2Float#', 'int2Double#', 'float2Int#', 'double2Int#', 'float2Double#', 'double2Float#']


def f32(value):
    return struct.unpack('!f', struct.pack('!f', value))[0]


def expected(name, n):
    x = f32(n)
    if name == 'floatArithmetic':
        return int(f32(f32(f32(x * f32(2.5)) - f32(-x)) / f32(3.5)))
    if name == 'doubleArithmetic':
        x = float(n)
        return int((x * 2.5 - (-x)) / 3.5)
    if name == 'floatRounding':
        return int(f32(x + f32(1.0)))
    if name == 'doubleRounding':
        return int(float(n) + 1.0)
    if name in ('floatDoubleConversion', 'doubleFloatConversion'):
        return int(x)
    if name in ('floatComparisons', 'doubleComparisons'):
        # NaN is unordered (only /=), opposite infinities/subnormals ordered,
        # and both signs of zero compare equal under IEEE arithmetic equality.
        return [2, 50, 14, 41, 41, 50, 14, 50 if n > 0 else 14][n & 7]
    if name in ('floatSignedZero', 'doubleSignedZero'):
        return int(n >= 0)
    if name == 'floatingFields':
        return int(x) + int((float(n) + 0.5) * 2.0)
    if name == 'floatingTupleFrontier':
        return int(x) + n
    if name == 'floatingCaptures':
        return int(f32(x + f32(3))) + int(float(n) - 3.0)
    if name == 'floatingLoop':
        return int((n & 31) * 0.5) + int((n & 31) * 0.25)
    if name == 'floatingJoinSwap':
        x, xx, y, yy = 1.0, 2.0, 3.0, 4.0
        for _ in range(n & 7):
            x, xx, y, yy = f32(xx + 0.5), x, yy + 0.25, y
        return n + int(f32(x + f32(xx * 3.0))) + int(y + yy * 5.0)
    raise AssertionError(name)


def walk(value):
    yield value
    for child in value.values() if isinstance(value, dict) else value if isinstance(value, list) else []:
        yield from walk(child)


def record(path):
    return dict(path=str(path.relative_to(ROOT)), sha256=hashlib.sha256(path.read_bytes()).hexdigest())


def main():
    assert subprocess.check_output([GHC, '--numeric-version'], text=True).strip() == '9.14.1'
    OUT.mkdir(parents=True, exist_ok=True)
    native = OUT / 'native'
    native.mkdir(exist_ok=True)
    subprocess.run([GHC, '--make', '-O2', '-fforce-recomp', '-dcore-lint', '-dstg-lint',
                    '-icompiler/test-fixtures', '-odir', str(native), '-hidir', str(native),
                    str(NATIVE), '-o', str(native / 'floating-oracle')], cwd=ROOT, check=True)
    rows = subprocess.check_output([str(native / 'floating-oracle')], cwd=ROOT, text=True)
    entries = set()
    for line in rows.splitlines():
        name, arg, answer = line.split('\t')
        assert int(answer) == expected(name, int(arg)), (name, arg, answer, expected(name, int(arg)))
        entries.add(name)
    assert len(entries) == 15 and len(rows.splitlines()) == 441
    (OUT / 'oracle.tsv').write_text(rows)
    subprocess.run(['compiler/export.sh', str(FIXTURE)], cwd=ROOT, check=True,
                   env=dict(os.environ, THC_CORE_OUT=str(OUT / 'core'), THC_GHC_OUT=str(OUT / 'ghc')))
    module_path = OUT / 'core/FloatingAudit.json'
    module = json.loads(module_path.read_text())
    primitives = {node[1] for node in walk(module['bindings']) if isinstance(node, list) and node and node[0] == 'prim'}
    assert set(PRIMITIVES) <= primitives, sorted(set(PRIMITIVES) - primitives)
    bindings = {b['name']: b for b in module['bindings']}
    for name, kind in [('floatWorker', 'float'), ('doubleWorker', 'double')]:
        lam = bindings[name]['expr']
        assert lam[0] == 'lam' and all(arg['rep']['kind'] == kind for arg in lam[1])
        assert lam[3]['resultRep']['kind'] == kind
    constructor = next(c for c in module['constructors'] if c['name'] == 'FloatingBox')
    assert constructor['fieldReps'] == [['FloatRep'], ['DoubleRep']]
    nested = [n for n in walk(bindings['floatingCaptures']['expr'][2])
              if isinstance(n, list) and n and n[0] == 'lam']
    capture_kinds = {n[2]['rep']['kind'] for lam in nested for n in walk(lam[2])
                     if isinstance(n, list) and len(n) > 2 and n[0] == 'var'
                     and n[1] not in {arg['id'] for arg in lam[1]}}
    assert {'float', 'double'} <= capture_kinds, 'GHC removed the floating captures'
    joins = [n for n in walk(bindings['floatingJoinSwap']['expr'])
             if isinstance(n, dict) and n.get('info', {}).get('joinArity') and 'expr' in n]
    assert joins, 'GHC removed the floating join'
    join_formals = [[arg['rep']['kind'] for arg in join['expr'][1]] for join in joins]
    assert any(kinds.count('float') == 2 and kinds.count('double') == 2 for kinds in join_formals), join_formals
    # Link the exact reachable closure of each real entry using the same strict auditor.
    spec = importlib.util.spec_from_file_location('audit_core', ROOT / 'scripts/audit-core.py')
    auditor = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(auditor)
    capabilities = json.loads((ROOT / 'scripts/core-capabilities.json').read_text())
    audits = {}
    for entry in sorted(entries):
        report = auditor.Audit([(str(module_path), module)], capabilities).run([entry])
        assert report['accepted'], (entry, report['issues'], report['missingGlobals'])
        audits[entry] = report['summary']
    (OUT / 'checks.json').write_text(json.dumps(dict(nativeRows=len(rows.splitlines()),
        primitives=PRIMITIVES, entries=sorted(entries), audit=audits, floatingCaptureKinds=sorted(capture_kinds),
        floatingJoinFormals=join_formals,
        ordinaryCprResult=dict(entry='floatingTupleFrontier', summary=audits['floatingTupleFrontier']),
        conversionDomain='Finite representable Int results only; non-finite/out-of-range conversions excluded',
        artifacts=[record(module_path), record(OUT / 'oracle.tsv'), record(native / 'floating-oracle')],
        toolchain=dict(ghc=GHC, version='9.14.1', nativeFlags=['-O2', '-fforce-recomp', '-dcore-lint', '-dstg-lint']),
        sources=[record(FIXTURE), record(NATIVE), record(Path(__file__).resolve()),
                 record(ROOT / 'scripts/audit-core.py'), record(ROOT / 'scripts/core-capabilities.json'),
                 record(ROOT / 'src/main/resources/thc/scalar-primop-signatures.json'),
                 *[record(p) for p in sorted((ROOT / 'compiler/THC').glob('*.hs'))]]), indent=2) + '\n')
    print(f'Floating audit: {len(rows.splitlines())} native rows, {len(PRIMITIVES)} primops, {len(entries)} strict entries')


if __name__ == '__main__':
    main()
