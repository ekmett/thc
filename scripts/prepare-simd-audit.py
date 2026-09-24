#!/usr/bin/env python3
"""Real GHC vector export and optional native oracle; no JVM support claim."""
import argparse
import hashlib
import json
import os
import platform
import shutil
from pathlib import Path
import subprocess
ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'build/simd'
FIXTURE = ROOT / 'compiler/test-fixtures/SimdInt64X2.hs'
NATIVE = ROOT / 'compiler/test-fixtures/SimdInt64X2Native.hs'
INPUTS = [-(1 << 63), -3000000001, -1, 0, 1, 9000000003, (1 << 63) - 1]
INPUTS32 = [-(1 << 63), -2147483649, -2147483648, -1, 0, 1, 2147483647, 2147483648, (1 << 63) - 1]

def wrap(x): return (x + (1 << 63)) % (1 << 64) - (1 << 63)
def expected(name, a, b):
    if name == 'vectorCase': x, y, p, q = a + a + 91, b + a + 91, 7, 11
    elif name == 'subtractCase': x, y, p, q = a + b - 19, b + b - 19, 13, 17
    else: x, y, p, q = (2*a if a > b else 0), (2*b if a > b else 0), 23, 31
    return wrap(wrap(x * p) ^ wrap(y * q))
def expected32(name, a, b, c, d):
    def narrow(x): return (x + (1 << 31)) % (1 << 32) - (1 << 31)
    if name == 'vectorCase': values, weights = [narrow(x+a+91) for x in (a,b,c,d)], [7,11,13,17]
    elif name == 'subtractCase': values, weights = [narrow(x+b-19) for x in (a,b,c,d)], [13,17,19,23]
    else: values, weights = [narrow(2*x) if a > b else 0 for x in (a,b,c,d)], [23,31,37,41]
    result = 0
    for x, weight in zip(values, weights): result ^= x * weight
    return wrap(result)
def walk(v):
    yield v
    for x in v.values() if isinstance(v, dict) else v if isinstance(v, list) else []: yield from walk(x)
def record(p):
    return dict(path=str(p.relative_to(ROOT)), sha256=hashlib.sha256(p.read_bytes()).hexdigest())
def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--export-only', action='store_true', help='Pre-Tidy Core through -fno-code -fwrite-if-simplified-core; explicitly no native/post-Tidy oracle')
    ap.add_argument('--vector', choices=['int64x2', 'int32x4'], default='int64x2')
    args = ap.parse_args()
    shape32 = args.vector == 'int32x4'
    module_name = 'SimdInt32X4' if shape32 else 'SimdInt64X2'
    OUT = ROOT / ('build/simd-int32x4' if shape32 else 'build/simd')
    FIXTURE = ROOT / f'compiler/test-fixtures/{module_name}.hs'
    NATIVE = ROOT / f'compiler/test-fixtures/{module_name}Native.hs'
    lanes, element = (4, 'Int32ElemRep') if shape32 else (2, 'Int64ElemRep')
    ghc = os.environ.get('GHC', 'ghc')
    assert subprocess.check_output([ghc, '--numeric-version'], text=True).strip() == '9.14.1'
    OUT.mkdir(parents=True, exist_ok=True)
    commands = []
    toolchain = dict(ghcVersion=subprocess.check_output([ghc, '--numeric-version'], text=True).strip(),
        host=platform.node(), machine=platform.machine(), system=platform.platform(),
        ghcInfo=subprocess.check_output([ghc, '--info'], text=True),
        ghcBinarySha256=hashlib.sha256(Path(shutil.which(ghc) or ghc).resolve().read_bytes()).hexdigest())
    def run(argv, env=None):
        commands.append(dict(argv=argv, env=env or {}))
        subprocess.run(argv, cwd=ROOT, env=dict(os.environ, **(env or {})), check=True)
    run(['compiler/build.sh'])
    stages = ['pre'] if args.export_only else ['pre', 'post']
    for stage in stages:
        (OUT / f'{stage}-core/{module_name}.json').unlink(missing_ok=True)
        options = ['-fno-code', '-fwrite-if-simplified-core'] if args.export_only else []
        if stage == 'post': options += ['-fplugin-opt=THC.Plugin:post-tidy']
        run(['compiler/export.sh', *options, str(FIXTURE)], dict(THC_CORE_OUT=str(OUT / f'{stage}-core'), THC_GHC_OUT=str(OUT / f'{stage}-ghc')))
        module = json.loads((OUT / f'{stage}-core/{module_name}.json').read_text())
        assert module['boundary'] == ('optimized-Core-before-Tidy' if stage == 'pre' else 'optimized-Core-after-Tidy-before-CorePrep')
        vectors = [v for v in walk(module) if isinstance(v, dict) and v.get('kind') == 'vector']
        assert vectors and all(v['vector'] == {'lanes': lanes, 'element': element} and v['primReps'] == [f'VecRep {lanes} {element}'] and 'aggregate' not in v for v in vectors)
        calls = {v[1] for v in walk(module) if isinstance(v, list) and len(v) > 1 and v[0] == 'prim'}
        assert {prefix + module_name.removeprefix('Simd') + '#' for prefix in ('pack','unpack','broadcast','plus','minus','negate')} <= calls
    rows = None
    if not args.export_only:
        native = OUT / 'native'; native.mkdir(exist_ok=True)
        run([ghc, '--make', '-O2', '-fforce-recomp', '-dcore-lint', '-icompiler/test-fixtures', '-odir', str(native), '-hidir', str(native), '-o', str(native / 'simd'), str(NATIVE)])
        commands.append(dict(argv=[str(native / 'simd')], stdout=str(OUT / 'oracle.tsv')))
        output = subprocess.check_output([str(native / 'simd')], cwd=ROOT, text=True)
        actual = {}
        for line in output.splitlines():
            name, *values = line.split('\t'); key = (name, *map(int, values[:-1]))
            assert key not in actual; actual[key] = int(values[-1])
        if shape32:
            cases = [(a,b,INPUTS32[(i+3*j)%9],INPUTS32[(3*i+j+1)%9]) for i,a in enumerate(INPUTS32) for j,b in enumerate(INPUTS32)]
            wanted = {(n,*xs): expected32(n,*xs) for n in ('vectorCase','subtractCase','branchCase') for xs in cases}
        else:
            wanted = {(n,a,b): expected(n,a,b) for n in ('vectorCase','subtractCase','branchCase') for a in INPUTS for b in INPUTS}
        assert actual == wanted
        (OUT / 'oracle.tsv').write_text(output); rows = len(actual)
    artifacts = [OUT / f'{s}-core/{module_name}.json' for s in stages]
    if rows is not None: artifacts += [OUT / 'oracle.tsv']
    (OUT / 'provenance.json').write_text(json.dumps(dict(schema=1, vector=args.vector, commands=commands, nativeRows=rows, stages=stages, toolchain=toolchain,
        sources=[record(FIXTURE), record(NATIVE), record(Path(__file__).resolve()), *[record(p) for p in sorted((ROOT / 'compiler/THC').glob('*.hs'))]], artifacts=[record(p) for p in artifacts]), indent=2)+'\n')
    print(f'SIMD export stages={stages}; native oracle rows={rows} (None means not run)')
if __name__ == '__main__': main()
