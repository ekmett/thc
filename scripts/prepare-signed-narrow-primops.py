#!/usr/bin/env python3
"""Export actual GHC signed narrow primops and record their defined native inputs."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parent.parent
BUILD = ROOT / 'build/signed-narrow-primops'
SOURCE = 'compiler/test-fixtures/SignedNarrowPrimopsAudit.hs'
OPERATIONS = ('negate', 'plus', 'sub', 'times', 'quot', 'rem', 'eq', 'ne', 'lt', 'le', 'gt', 'ge')


def run(command, **kwargs):
    return subprocess.run([str(x) for x in command], cwd=ROOT, check=True, **kwargs)


def narrow(value, width):
    half = 2**(width - 1)
    return (value + half) % (2 * half) - half


def samples(width):
    half = 2**(width - 1)
    values = {-half, -half + 1, -1, 0, 1, half - 2, half - 1}
    for bit in range(width - 1):
        for delta in (-1, 0, 1):
            values.update((narrow(2**bit + delta, width), narrow(-2**bit + delta, width)))
    # Dynamic host values exercise truncation before the narrow operation.
    values.update((-2**63, 2**63 - 1, -half - 1, half, half + 1,
                   -2**width - 1, -2**width, 2**width, 2**width + 1))
    return sorted(values)


def operands(operation, width):
    values = samples(width)
    half = 2**(width - 1)
    anchors = (-half, -half + 1, -3, -1, 0, 1, 3, half - 2, half - 1)
    if operation == 'negate':
        return [(x, 0) for x in sorted(set(values) | (set(range(-128, 128)) if width == 8 else set()))]
    pairs = {(x, y) for x in values for y in anchors}
    pairs.update((x, y) for x in anchors for y in values)
    pairs.update((x, y) for x in values for y in (x - 1, x, x + 1) if -2**63 <= y < 2**63)
    if operation in ('quot', 'rem'):
        # Zero divisors and signed division overflow have no portable numeric oracle.
        pairs = {(x, y) for x, y in pairs if narrow(y, width) != 0
                 and (narrow(x, width), narrow(y, width)) != (-half, -1)}
    return sorted(pairs)


def main():
    BUILD.mkdir(parents=True, exist_ok=True)
    manifest = BUILD / 'manifest.json'
    manifest.unlink(missing_ok=True)
    entries = [dict(name=f'{op}Int{width}', primitive=f'{op}Int{width}#', width=width,
                    arity=1 if op == 'negate' else 2)
               for op in OPERATIONS for width in (8, 16, 32)]
    core = BUILD / 'core'
    env = dict(os.environ, THC_CORE_OUT=str(core), THC_GHC_OUT=str(BUILD / 'ghc'))
    run([ROOT / 'compiler/export.sh', *['-fplugin-opt=Thc.Plugin:closure=' + e['name']
                                      for e in entries], SOURCE], env=env)
    spec = importlib.util.spec_from_file_location('core_audit', ROOT / 'scripts/audit-core.py')
    audit = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(audit)
    paths = sorted(core.glob('*.json'))
    modules = [(str(p.relative_to(ROOT)), json.loads(p.read_text())) for p in paths]
    capabilities = json.loads((ROOT / 'scripts/core-capabilities.json').read_text())
    driver = ['{-# LANGUAGE MagicHash #-}', 'module Main where',
              'import GHC.Exts (Int(I#), Int#)', 'import qualified SignedNarrowPrimopsAudit as P',
              'emit1 :: String -> (Int# -> Int#) -> Int -> IO ()',
              'emit1 n f x@(I# a) = putStrLn (n ++ "\\t" ++ show x ++ "\\t0\\t" ++ show (I# (f a)))',
              'emit2 :: String -> (Int# -> Int# -> Int#) -> (Int, Int) -> IO ()',
              'emit2 n f (x@(I# a), y@(I# b)) = putStrLn (n ++ "\\t" ++ show x ++ "\\t" ++ show y ++ "\\t" ++ show (I# (f a b)))',
              'dispatch :: [String] -> IO ()', 'dispatch [name, x, y] = case name of']
    wanted = set()
    requests = []
    for entry in entries:
        name, width, arity = entry['name'], entry['width'], entry['arity']
        report = audit.Audit(modules, capabilities).run([name])
        assert report['accepted'], (name, report)
        actual = {p['name'] for p in report['primitives']}
        assert entry['primitive'] in actual, (name, actual)
        (BUILD / (name + '.audit.json')).write_text(json.dumps(report, indent=2) + '\n')
        pairs = operands(name.split('Int')[0], width)
        wanted.update((name, x, y) for x, y in pairs)
        requests.extend(f'{name}\t{x}\t{y}\n' for x, y in pairs)
        argument = 'read x' if arity == 1 else '(read x, read y)'
        driver.append(f'  "{name}" -> emit{arity} name P.{name} ({argument})')
    driver += ['  _ -> error "unknown primop"', 'dispatch _ = error "invalid input"',
               'main :: IO ()', 'main = getContents >>= mapM_ (dispatch . words) . lines']
    source = BUILD / 'NativeSignedNarrowPrimops.hs'
    source.write_text('\n'.join(driver) + '\n')
    native = BUILD / 'native'
    native.mkdir(exist_ok=True)
    executable = native / 'signed-narrow-primops-oracle'
    run([os.environ.get('GHC', 'ghc'), '--make', '-O2', '-fforce-recomp', '-dcore-lint',
         '-dstg-lint', '-i' + str(ROOT / 'compiler/test-fixtures'), '-odir', native,
         '-hidir', native, source, '-o', executable])
    completed = run([executable], input=''.join(requests), text=True, capture_output=True, timeout=60)
    rows = [line.split('\t') for line in completed.stdout.splitlines()]
    actual = {(name, int(x), int(y)) for name, x, y, _ in rows}
    assert actual == wanted and len(rows) == len(wanted)
    (BUILD / 'oracle.tsv').write_text(completed.stdout)
    inputs = [SOURCE, 'scripts/prepare-signed-narrow-primops.py', 'scripts/core-capabilities.json', 'src/main/resources/thc/scalar-primop-signatures.json',
              'scripts/audit-core.py', 'compiler/build.sh', 'compiler/export.sh', 'compiler/toolchain.sh']
    inputs += [str(p.relative_to(ROOT)) for p in (ROOT / 'compiler/Thc').glob('*.hs')]
    artifacts = [str(p.relative_to(ROOT)) for p in paths] + ['build/signed-narrow-primops/oracle.tsv']
    hashes = lambda items: {p: hashlib.sha256((ROOT / p).read_bytes()).hexdigest() for p in items}
    manifest.write_text(json.dumps(dict(schema=1, entries=entries, modules=[p for p, _ in modules],
                                       excludedDivisionInputs=['zero narrowed divisor', 'narrow minBound / -1'],
                                       inputHashes=hashes(inputs), artifactHashes=hashes(artifacts)),
                                   indent=2) + '\n')
    print(f'Prepared {len(entries)} signed narrow primops / {len(rows)} native oracle rows')


if __name__ == '__main__':
    main()
