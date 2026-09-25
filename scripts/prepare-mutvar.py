#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Pinned GHC ST/STRef and exact boxed MutVar contracts, with native/model evidence."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parent.parent
BUILD = ROOT / 'build/mutvar'
SOURCE = 'compiler/test-fixtures/MutVarAudit.hs'
ENTRIES = ['stRef', 'lazyRef', 'closureRef', 'orderedRef', 'unliftedRef', 'stLoop',
           'stRefEquality', 'lazyRefEquality', 'lazyIORef', 'swapRef', 'lazySwapRef']
EQUALITY_ENTRIES = {'stRefEquality', 'lazyRefEquality'}
PRIMITIVES = {'newMutVar#', 'readMutVar#', 'writeMutVar#'}


def run(command, **kwargs):
    return subprocess.run([str(x) for x in command], cwd=ROOT, check=True, **kwargs)


def signed(n):
    return (n + 2**63) % 2**64 - 2**63


def mathematical(name, x):
    if name in EQUALITY_ENTRIES:
        score = 1 + (4 if x < 0 else 0)
        if name == 'lazyRefEquality':
            return signed(x + 17 * score)
        left, right = (x + 17, x) if x < 0 else (x, x + 17)
        return signed(left * 257 + right * 65537 + 17 * score)
    if name == 'lazyRef':
        return signed(x + 5)
    if name == 'lazyIORef':
        return signed(x + 17)
    if name == 'closureRef':
        return signed(4 * x + 11)
    if name == 'swapRef':
        return signed(x + (x + 17) * 257 + (x * 3) * 65537)
    if name == 'lazySwapRef':
        return signed(x * 258 + 7)
    if name == 'unliftedRef':
        return signed(x * 258 + 1)
    if name == 'stLoop':
        value = x
        for n in range(abs(x) % 33, 0, -1):
            value = value * 3 + n
        return signed(value)
    last = (x + 17) * 3 if name == 'stRef' else x * 3
    return signed(x + (x + 17) * 257 + last * 65537 + (x + 71) * 16777259)


def pointer_applications(value):
    if isinstance(value, list):
        if (value and value[0] == 'app' and isinstance(value[1], list)
                and value[1][:2] == ['prim', 'reallyUnsafePtrEquality#']):
            yield value
        for child in value:
            yield from pointer_applications(child)
    elif isinstance(value, dict):
        for child in value.values():
            yield from pointer_applications(child)


def main():
    BUILD.mkdir(parents=True, exist_ok=True)
    manifest = BUILD / 'manifest.json'
    manifest.unlink(missing_ok=True)
    ghc = os.environ.get('GHC', 'ghc')
    assert run([ghc, '--numeric-version'], text=True, capture_output=True).stdout.strip() == '9.14.1'
    spec = importlib.util.spec_from_file_location('core_audit', ROOT / 'scripts/audit-core.py')
    audit = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(audit)
    capabilities = json.loads((ROOT / 'scripts/core-capabilities.json').read_text())
    artifacts, stages, closures = [], {}, {}
    for stage in ('pre', 'post'):
        directory = BUILD / stage
        core = directory / 'core'
        env = dict(os.environ, THC_CORE_OUT=str(core), THC_GHC_OUT=str(directory / 'ghc'))
        options = ['-fplugin-opt=THC.Plugin:post-tidy'] if stage == 'post' else []
        run([ROOT / 'compiler/export.sh', *options,
             *['-fplugin-opt=THC.Plugin:closure=' + name for name in ENTRIES], SOURCE], env=env)
        paths = sorted(core.glob('*.json'))
        modules = [(str(p.relative_to(ROOT)), json.loads(p.read_text())) for p in paths]
        stages[stage] = [p for p, _ in modules]
        artifacts.extend(stages[stage])
        for name in ENTRIES:
            report = audit.Audit(modules, capabilities).run([name])
            report_path = directory / (name + '.audit.json')
            report_path.write_text(json.dumps(report, indent=2) + '\n')
            artifacts.append(str(report_path.relative_to(ROOT)))
            assert report['accepted'], (stage, name, report['issues'], report['missingGlobals'])
            required = ({'newMutVar#', 'readMutVar#', 'atomicSwapMutVar#'} if name in {'swapRef', 'lazySwapRef'}
                        else PRIMITIVES - ({'readMutVar#'} if name == 'lazyRefEquality' else set()))
            if name in EQUALITY_ENTRIES:
                required |= {'reallyUnsafePtrEquality#'}
            assert required <= {p['name'] for p in report['primitives']}, (stage, name, report['primitives'])
            closures[stage + '/' + name] = report['reachableBindings']
        # Public API lowering must retain the OPAQUE STRef operation used by stRef.
        assert any(b['id'].startswith('main:MutVarAudit.bump') for b in closures[stage + '/stRef'])
        for name in EQUALITY_ENTRIES:
            for helper in ('sameRef', 'writeAndScore'):
                assert any(b['id'].startswith('main:MutVarAudit.' + helper) for b in closures[stage + '/' + name])
        # GHC 9.14.1 sameMutVar# is a library specialization, not a new primop.
        # Verify the genuine Eq STRef lowering compares two unlifted references
        # and returns a full-width Int#, rather than comparing lifted wrappers.
        comparisons = [app for _, module in modules for app in pointer_applications(module)]
        assert comparisons, (stage, 'missing public STRef equality lowering')
        for app in comparisons:
            assert len(app[2]) == 2 and app[3] == [False, False], (stage, app)
            for operand in app[2]:
                assert operand[-1]['rep']['kind'] == 'object', (stage, operand)
                assert operand[-1]['rep']['primReps'] == ['BoxedRep (Just Unlifted)'], (stage, operand)
            assert app[-1]['rep']['kind'] == 'long' and app[-1]['rep']['primReps'] == ['IntRep'], (stage, app)
    driver = ['{-# LANGUAGE MagicHash #-}', 'module Main where', 'import GHC.Exts (Int(I#), Int#)',
              'import qualified MutVarAudit as P',
              'emit :: String -> (Int# -> Int#) -> Int -> IO ()',
              'emit n f x@(I# a) = putStrLn (n ++ "\\t" ++ show x ++ "\\t" ++ show (I# (f a)))',
              'dispatch :: [String] -> IO ()', 'dispatch [name, x] = case name of']
    values = sorted(set(range(-128, 129)) | {-2**63, -2**63+1, 2**63-2, 2**63-1, -4097, 4097, -10**12, 10**12})
    requests = []
    for name in ENTRIES:
        driver.append(f'  "{name}" -> emit name P.{name} (read x)')
        requests.extend(f'{name}\t{x}\n' for x in values)
    driver += ['  _ -> error "unknown entry"', 'dispatch _ = error "invalid input"',
               'main :: IO ()', 'main = getContents >>= mapM_ (dispatch . words) . lines']
    source = BUILD / 'NativeMutVar.hs'
    source.write_text('\n'.join(driver) + '\n')
    native = BUILD / 'native'
    native.mkdir(exist_ok=True)
    executable = native / 'mutvar-oracle'
    run([ghc, '--make', '-O2', '-fforce-recomp', '-dcore-lint', '-dstg-lint',
         '-i' + str(ROOT / 'compiler/test-fixtures'), '-odir', native, '-hidir', native, source, '-o', executable])
    result = run([executable], input=''.join(requests), text=True, capture_output=True, timeout=60)
    rows = [line.split('\t') for line in result.stdout.splitlines()]
    assert {(n, int(x)) for n, x, _ in rows} == {(n, x) for n in ENTRIES for x in values}
    assert len(rows) == len(ENTRIES) * len(values)
    for name, x, value in rows:
        assert int(value) == mathematical(name, int(x)), (name, x, value)
    (BUILD / 'oracle.tsv').write_text(result.stdout)
    inputs = [SOURCE, 'scripts/prepare-mutvar.py', 'scripts/core-capabilities.json', 'scripts/audit-core.py',
              'src/main/resources/thc/scalar-primop-signatures.json', 'compiler/build.sh', 'compiler/export.sh', 'compiler/toolchain.sh']
    inputs += sorted(str(p.relative_to(ROOT)) for p in (ROOT / 'scripts').glob('core_*.py'))
    inputs += sorted(str(p.relative_to(ROOT)) for p in (ROOT / 'compiler/THC').glob('*.hs'))
    artifacts += ['build/mutvar/oracle.tsv', 'build/mutvar/NativeMutVar.hs']
    hashes = lambda paths: {p: hashlib.sha256((ROOT / p).read_bytes()).hexdigest() for p in paths}
    manifest.write_text(json.dumps(dict(schema=1, ghc='9.14.1', entries=ENTRIES, stages=stages,
        nativeRows=len(rows), reachableBindings=closures, inputHashes=hashes(inputs), artifactHashes=hashes(artifacts)), indent=2) + '\n')
    print(f'Prepared {len(ENTRIES)} genuine STRef/MutVar workloads / {len(rows)} native-model rows / pre+post Core')


if __name__ == '__main__':
    main()
