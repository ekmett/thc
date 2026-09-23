#!/usr/bin/env python3
"""Real GHC vector export and optional native oracle; no JVM support claim."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'build/simd'
FIXTURE = ROOT / 'compiler/test-fixtures/SimdInt64X2.hs'
NATIVE = ROOT / 'compiler/test-fixtures/SimdInt64X2Native.hs'
INPUTS = [-(1 << 63), -3000000001, -1, 0, 1, 9000000003, (1 << 63) - 1]

def wrap(x): return (x + (1 << 63)) % (1 << 64) - (1 << 63)
def expected(name, a, b):
    if name == 'vectorCase': x, y, p, q = a + a + 91, b + a + 91, 7, 11
    elif name == 'subtractCase': x, y, p, q = a + b - 19, b + b - 19, 13, 17
    else: x, y, p, q = (2*a if a > b else 0), (2*b if a > b else 0), 23, 31
    return wrap(wrap(x * p) ^ wrap(y * q))
def walk(v):
    yield v
    for x in v.values() if isinstance(v, dict) else v if isinstance(v, list) else []: yield from walk(x)
def record(p):
    return dict(path=str(p.relative_to(ROOT)), sha256=hashlib.sha256(p.read_bytes()).hexdigest())
def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--export-only', action='store_true', help='Pre-Tidy Core through -fno-code -fwrite-if-simplified-core; explicitly no native/post-Tidy oracle')
    args = ap.parse_args()
    ghc = os.environ.get('GHC', 'ghc')
    assert subprocess.check_output([ghc, '--numeric-version'], text=True).strip() == '9.14.1'
    OUT.mkdir(parents=True, exist_ok=True)
    commands = []
    def run(argv, env=None):
        commands.append(dict(argv=argv, env=env or {}))
        subprocess.run(argv, cwd=ROOT, env=dict(os.environ, **(env or {})), check=True)
    run(['compiler/build.sh'])
    stages = ['pre'] if args.export_only else ['pre', 'post']
    for stage in stages:
        options = ['-fno-code', '-fwrite-if-simplified-core'] if args.export_only else []
        if stage == 'post': options += ['-fplugin-opt=Thc.Plugin:post-tidy']
        run(['compiler/export.sh', *options, str(FIXTURE)], dict(THC_CORE_OUT=str(OUT / f'{stage}-core'), THC_GHC_OUT=str(OUT / f'{stage}-ghc')))
        module = json.loads((OUT / f'{stage}-core/SimdInt64X2.json').read_text())
        vectors = [v for v in walk(module) if isinstance(v, dict) and v.get('kind') == 'vector']
        assert vectors and all(v['vector'] == {'lanes': 2, 'element': 'Int64ElemRep'} and v['primReps'] == ['VecRep 2 Int64ElemRep'] and 'aggregate' not in v for v in vectors)
        calls = {v[1] for v in walk(module) if isinstance(v, list) and len(v) > 1 and v[0] == 'prim'}
        assert {'packInt64X2#', 'unpackInt64X2#', 'broadcastInt64X2#', 'plusInt64X2#', 'minusInt64X2#', 'negateInt64X2#'} <= calls
    rows = None
    if not args.export_only:
        native = OUT / 'native'; native.mkdir(exist_ok=True)
        run([ghc, '--make', '-O2', '-fforce-recomp', '-dcore-lint', '-icompiler/test-fixtures', '-odir', str(native), '-hidir', str(native), '-o', str(native / 'simd'), str(NATIVE)])
        output = subprocess.check_output([str(native / 'simd')], cwd=ROOT, text=True)
        actual = {}
        for line in output.splitlines():
            name, a, b, value = line.split('\t'); key = name, int(a), int(b)
            assert key not in actual; actual[key] = int(value)
        wanted = {(n,a,b): expected(n,a,b) for n in ('vectorCase','subtractCase','branchCase') for a in INPUTS for b in INPUTS}
        assert actual == wanted
        (OUT / 'oracle.tsv').write_text(output); rows = len(actual)
    artifacts = [OUT / f'{s}-core/SimdInt64X2.json' for s in stages]
    if rows is not None: artifacts += [OUT / 'oracle.tsv']
    (OUT / 'provenance.json').write_text(json.dumps(dict(schema=1, commands=commands, nativeRows=rows, stages=stages,
        sources=[record(FIXTURE), record(NATIVE), record(Path(__file__).resolve()), record(ROOT / 'compiler/Thc/Plugin.hs')], artifacts=[record(p) for p in artifacts]), indent=2)+'\n')
    print(f'SIMD export stages={stages}; native oracle rows={rows} (None means not run)')
if __name__ == '__main__': main()
