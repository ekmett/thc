#!/usr/bin/env python3
"""Export exact GHC tuple arithmetic, compare native fields with unbounded integer math."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import random
import subprocess

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'build/tuple-arithmetic'
SOURCE = 'compiler/test-fixtures/TupleArithmeticAudit.hs'
NAMES = ['quotRemInt', 'quotRemWord', 'addIntC', 'subIntC', 'plusWord2', 'timesWord2']
MODULUS = 1 << 64
MINIMUM, MAXIMUM = -(1 << 63), (1 << 63) - 1


def signed(value):
    return (value - MINIMUM) % MODULUS + MINIMUM


def mathematical(name, x, y):
    if name == 'quotRemInt':
        assert y != 0 and (x, y) != (MINIMUM, -1)
        quotient = (abs(x) // abs(y)) * (-1 if (x < 0) != (y < 0) else 1)
        return quotient, x - quotient * y
    if name in ('addIntC', 'subIntC'):
        result = x + y if name == 'addIntC' else x - y
        return signed(result), int(not MINIMUM <= result <= MAXIMUM)
    x, y = x % MODULUS, y % MODULUS
    if name == 'quotRemWord':
        assert y != 0
        return tuple(map(signed, divmod(x, y)))
    result = x + y if name == 'plusWord2' else x * y
    return signed(result >> 64), signed(result)


def inputs():
    values = {MINIMUM, MINIMUM + 1, MAXIMUM - 1, MAXIMUM, -4097, -1, 0, 1, 4097}
    for bit in (1, 7, 8, 15, 16, 31, 32, 62, 63):
        values.update(signed((1 << bit) + delta) for delta in (-1, 0, 1))
        values.update(signed(-(1 << bit) + delta) for delta in (-1, 0, 1))
    rng = random.Random(9141)
    values.update(signed(rng.getrandbits(64)) for _ in range(32))
    anchors = (MINIMUM, MAXIMUM, -4097, -1, 0, 1, 4097)
    pairs = {(x, y) for x in values for y in anchors} | {(x, y) for x in anchors for y in values}
    pairs.update((x, y) for x in values for y in (x, signed(x-1), signed(x+1)))
    return [(name, x, y) for name in NAMES for x, y in sorted(pairs)
            if not (name.startswith('quotRem') and (y == 0 or name == 'quotRemInt' and (x, y) == (MINIMUM, -1)))]


def hashes(paths):
    return {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in paths}


def verify():
    rows = [line.split('\t') for line in (OUT / 'oracle.tsv').read_text().splitlines()]
    actual = {(name, int(x), int(y)): (int(a), int(b)) for name, x, y, a, b in rows}
    expected = {(name, x, y): mathematical(name, x, y) for name, x, y in inputs()}
    assert len(rows) == len(actual) and actual == expected, 'Native oracle differs from unbounded arithmetic model'
    spec = importlib.util.spec_from_file_location('audit_core', ROOT / 'scripts/audit-core.py')
    auditor = importlib.util.module_from_spec(spec); spec.loader.exec_module(auditor)
    cap = json.loads((ROOT / 'scripts/core-capabilities.json').read_text())
    for stage, boundary in [('pre', 'optimized-Core-before-Tidy'), ('post', 'optimized-Core-after-Tidy-before-CorePrep')]:
        path = OUT / f'{stage}-core/TupleArithmeticAudit.json'
        module = json.loads(path.read_text())
        assert module['ghc'] == '9.14.1' and module['boundary'] == boundary
        for name in NAMES:
            report = auditor.Audit([(str(path), module)], cap).run([name])
            assert report['accepted'], report['issues']
            assert name + '#' in {p['name'] for p in report['primitives']}, f'{stage}/{name}: primitive erased'
            (OUT / f'{stage}-{name}.audit.json').write_text(json.dumps(report, indent=2) + '\n')
    print(f'Verified 6 tuple primops, pre/post Tidy, {len(rows)} native two-field rows')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check-only', action='store_true')
    args = parser.parse_args()
    manifest = OUT / 'manifest.json'
    if args.check_only:
        recorded = json.loads(manifest.read_text())
        for group in ('inputHashes', 'artifactHashes'):
            assert hashes([ROOT / p for p in recorded[group]]) == recorded[group], f'Stale {group}'
        verify()
        return
    OUT.mkdir(parents=True, exist_ok=True)
    manifest.unlink(missing_ok=True)
    ghc = os.environ.get('GHC', 'ghc')
    assert subprocess.check_output([ghc, '--numeric-version'], text=True).strip() == '9.14.1'
    commands = []
    def run(command, **kwargs):
        command = list(map(str, command)); commands.append(command)
        return subprocess.run(command, cwd=ROOT, check=True, **kwargs)
    for stage in ('pre', 'post'):
        run(['compiler/export.sh', *(['-fplugin-opt=Thc.Plugin:post-tidy'] if stage == 'post' else []), SOURCE],
            env=dict(os.environ, THC_CORE_OUT=str(OUT / f'{stage}-core'), THC_GHC_OUT=str(OUT / f'{stage}-ghc')))
    driver = ['{-# LANGUAGE MagicHash #-}', 'module Main where',
              'import GHC.Exts (Int(I#), Int#)', 'import qualified TupleArithmeticAudit as P',
              'emit :: String -> (Int# -> Int# -> Int# -> Int#) -> Int -> Int -> IO ()',
              'emit n f x@(I# a) y@(I# b) = putStrLn (n ++ "\\t" ++ show x ++ "\\t" ++ show y ++ "\\t" ++ show (I# (f a b 0#)) ++ "\\t" ++ show (I# (f a b 1#)))',
              'dispatch :: [String] -> IO ()', 'dispatch [name, x, y] = case name of']
    driver += [f'  "{name}" -> emit name P.{name} (read x) (read y)' for name in NAMES]
    driver += ['  _ -> error "unknown primitive"', 'dispatch _ = error "invalid input"',
               'main :: IO ()', 'main = getContents >>= mapM_ (dispatch . words) . lines']
    source = OUT / 'NativeTupleArithmetic.hs'; source.write_text('\n'.join(driver) + '\n')
    native = OUT / 'native'; native.mkdir(exist_ok=True)
    executable = native / 'tuple-arithmetic-oracle'
    run([ghc, '--make', '-O2', '-fforce-recomp', '-dcore-lint', '-dstg-lint',
         '-i' + str(ROOT / 'compiler/test-fixtures'), '-odir', native, '-hidir', native, source, '-o', executable])
    completed = run([executable], input=''.join(f'{n}\t{x}\t{y}\n' for n, x, y in inputs()), text=True, capture_output=True, timeout=60)
    (OUT / 'oracle.tsv').write_text(completed.stdout)
    verify()
    inputs_paths = [ROOT / p for p in [SOURCE, 'scripts/prepare-tuple-arithmetic.py', 'scripts/audit-core.py',
                    'scripts/core-capabilities.json', 'src/main/resources/thc/scalar-primop-signatures.json', 'compiler/build.sh', 'compiler/export.sh', 'compiler/toolchain.sh']]
    inputs_paths += sorted((ROOT / 'compiler/Thc').glob('*.hs'))
    artifacts = [OUT / f'{stage}-core/TupleArithmeticAudit.json' for stage in ('pre', 'post')]
    artifacts += [OUT / 'oracle.tsv', source]
    manifest.write_text(json.dumps(dict(schema=1, ghc='9.14.1', ghcInfo=subprocess.check_output([ghc, '--info'], text=True),
        entries=NAMES, stages=['pre', 'post'], commands=commands,
        excludedDivisionInputs='zero divisors and quotRemInt# minBound / -1; no numeric oracle claimed',
        inputHashes=hashes(inputs_paths), artifactHashes=hashes(artifacts)), indent=2) + '\n')


if __name__ == '__main__':
    main()
