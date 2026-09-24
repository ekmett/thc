#!/usr/bin/env python3
"""Export exact GHC tuple arithmetic, compare native fields with unbounded integer math."""
import argparse
import ast
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
NAMES = ['quotRemInt', 'quotRemWord', 'addIntC', 'subIntC', 'plusWord2', 'timesWord2', 'addWordC', 'subWordC']
CALLS = ['addWordCall', 'subWordCall']
MODULUS = 1 << 64
MINIMUM, MAXIMUM = -(1 << 63), (1 << 63) - 1


def signed(value):
    return (value - MINIMUM) % MODULUS + MINIMUM


def mathematical(name, x, y):
    name = {'addWordCall': 'addWordC', 'subWordCall': 'subWordC'}.get(name, name)
    if name == 'quotRemInt':
        assert y != 0 and (x, y) != (MINIMUM, -1)
        quotient = (abs(x) // abs(y)) * (-1 if (x < 0) != (y < 0) else 1)
        return quotient, x - quotient * y
    if name in ('addIntC', 'subIntC'):
        result = x + y if name == 'addIntC' else x - y
        return signed(result), int(not MINIMUM <= result <= MAXIMUM)
    x, y = x % MODULUS, y % MODULUS
    if name in ('addWordC', 'subWordC'):
        result = x + y if name == 'addWordC' else x - y
        return signed(result), int(result < 0 or result >= MODULUS)
    if name == 'quotRemWord':
        assert y != 0
        return tuple(map(signed, divmod(x, y)))
    result = x + y if name == 'plusWord2' else x * y
    return signed(result >> 64), signed(result)


def inputs(names=NAMES):
    values = {MINIMUM, MINIMUM + 1, MAXIMUM - 1, MAXIMUM, -4097, -1, 0, 1, 4097}
    for bit in (1, 7, 8, 15, 16, 31, 32, 62, 63):
        values.update(signed((1 << bit) + delta) for delta in (-1, 0, 1))
        values.update(signed(-(1 << bit) + delta) for delta in (-1, 0, 1))
    rng = random.Random(9141)
    values.update(signed(rng.getrandbits(64)) for _ in range(32))
    anchors = (MINIMUM, MAXIMUM, -4097, -1, 0, 1, 4097)
    pairs = {(x, y) for x in values for y in anchors} | {(x, y) for x in anchors for y in values}
    pairs.update((x, y) for x in values for y in (x, signed(x-1), signed(x+1)))
    word_pairs = set(pairs)
    for bit in range(64):
        value = 1 << bit
        word_pairs.update((signed(x), signed(y)) for x, y in (
            (value-1, 1), (value, 1), (value, value), (value-1, value),
            (MODULUS-value, value), (MODULUS-value, value+1),
            (value-1, MODULUS-value+1), (value, value-1), (0, value), (value, 0)))
    return [(name, x, y) for name in names for x, y in sorted(word_pairs if name in ('addWordC', 'subWordC', *CALLS) else pairs)
            if not (name.startswith('quotRem') and (y == 0 or name == 'quotRemInt' and (x, y) == (MINIMUM, -1)))]


def hashes(paths):
    return {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in paths}


def verify():
    counts = {}
    for filename, names in [('oracle.tsv', NAMES), ('call-oracle.tsv', CALLS)]:
        rows = [line.split('\t') for line in (OUT / filename).read_text().splitlines()]
        actual = {(name, int(x), int(y)): (int(a), int(b)) for name, x, y, a, b in rows}
        expected = {(name, x, y): mathematical(name, x, y) for name, x, y in inputs(names)}
        assert len(rows) == len(actual) and actual == expected, 'Native oracle differs from unbounded arithmetic model'
        counts[filename] = len(rows)
    spec = importlib.util.spec_from_file_location('audit_core', ROOT / 'scripts/audit-core.py')
    auditor = importlib.util.module_from_spec(spec); spec.loader.exec_module(auditor)
    cap = json.loads((ROOT / 'scripts/core-capabilities.json').read_text())
    for stage, boundary in [('pre', 'optimized-Core-before-Tidy'), ('post', 'optimized-Core-after-Tidy-before-CorePrep')]:
        path = OUT / f'{stage}-core/TupleArithmeticAudit.json'
        module = json.loads(path.read_text())
        assert module['ghc'] == '9.14.1' and module['boundary'] == boundary
        for name in [*NAMES, *CALLS]:
            report = auditor.Audit([(str(path), module)], cap).run([name])
            assert report['accepted'], report['issues']
            primitive = {'addWordCall': 'addWordC#', 'subWordCall': 'subWordC#'}.get(name, name + '#')
            assert primitive in {p['name'] for p in report['primitives']}, f'{stage}/{name}: primitive erased'
            if name in CALLS:
                producer = name.replace('Call', 'Result')
                assert 'main:TupleArithmeticAudit.' + producer in {b['id'] for b in report['reachableBindings']}, f'{stage}/{name}: opaque mixed return disappeared'
            (OUT / f'{stage}-{name}.audit.json').write_text(json.dumps(report, indent=2) + '\n')
    print(f'Verified 8 tuple primops and 2 mixed-return calls, pre/post Tidy, native two-field rows: {counts}')


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
    ghc_info = subprocess.check_output([ghc, '--info'], text=True)
    assert dict(ast.literal_eval(ghc_info)).get('target word size') == '8', 'Tuple arithmetic requires the supported 64-bit target'
    commands = []
    def run(command, **kwargs):
        command = list(map(str, command)); commands.append(command)
        return subprocess.run(command, cwd=ROOT, check=True, **kwargs)
    for stage in ('pre', 'post'):
        run(['compiler/export.sh', *(['-fplugin-opt=THC.Plugin:post-tidy'] if stage == 'post' else []), SOURCE],
            env=dict(os.environ, THC_CORE_OUT=str(OUT / f'{stage}-core'), THC_GHC_OUT=str(OUT / f'{stage}-ghc')))
    driver = ['{-# LANGUAGE MagicHash #-}', 'module Main where',
              'import GHC.Exts (Int(I#), Int#)', 'import qualified TupleArithmeticAudit as P',
              'emit :: String -> (Int# -> Int# -> Int# -> Int#) -> Int -> Int -> IO ()',
              'emit n f x@(I# a) y@(I# b) = putStrLn (n ++ "\\t" ++ show x ++ "\\t" ++ show y ++ "\\t" ++ show (I# (f a b 0#)) ++ "\\t" ++ show (I# (f a b 1#)))',
              'dispatch :: [String] -> IO ()', 'dispatch [name, x, y] = case name of']
    driver += [f'  "{name}" -> emit name P.{name} (read x) (read y)' for name in [*NAMES, *CALLS]]
    driver += ['  _ -> error "unknown primitive"', 'dispatch _ = error "invalid input"',
               'main :: IO ()', 'main = getContents >>= mapM_ (dispatch . words) . lines']
    source = OUT / 'NativeTupleArithmetic.hs'; source.write_text('\n'.join(driver) + '\n')
    native = OUT / 'native'; native.mkdir(exist_ok=True)
    executable = native / 'tuple-arithmetic-oracle'
    run([ghc, '--make', '-O2', '-fforce-recomp', '-dcore-lint', '-dstg-lint',
         '-i' + str(ROOT / 'compiler/test-fixtures'), '-odir', native, '-hidir', native, source, '-o', executable])
    for filename, names in [('oracle.tsv', NAMES), ('call-oracle.tsv', CALLS)]:
        completed = run([executable], input=''.join(f'{n}\t{x}\t{y}\n' for n, x, y in inputs(names)), text=True, capture_output=True, timeout=60)
        (OUT / filename).write_text(completed.stdout)
    verify()
    inputs_paths = [ROOT / p for p in [SOURCE, 'scripts/prepare-tuple-arithmetic.py', 'scripts/audit-core.py',
                    'scripts/core-capabilities.json', 'src/main/resources/thc/scalar-primop-signatures.json', 'compiler/build.sh', 'compiler/export.sh', 'compiler/toolchain.sh']]
    inputs_paths += sorted((ROOT / 'compiler/THC').glob('*.hs'))
    inputs_paths += sorted((ROOT / 'scripts').glob('core_*.py'))
    artifacts = [OUT / f'{stage}-core/TupleArithmeticAudit.json' for stage in ('pre', 'post')]
    artifacts += [OUT / 'oracle.tsv', OUT / 'call-oracle.tsv', source, executable]
    artifacts += sorted(OUT.glob('*.audit.json'))
    manifest.write_text(json.dumps(dict(schema=1, ghc='9.14.1', wordBits=64, ghcInfo=ghc_info,
        entries=NAMES, mixedReturnEntries=CALLS, stages=['pre', 'post'], commands=commands,
        excludedDivisionInputs='zero divisors and quotRemInt# minBound / -1; no numeric oracle claimed',
        inputHashes=hashes(inputs_paths), artifactHashes=hashes(artifacts)), indent=2) + '\n')


if __name__ == '__main__':
    main()
