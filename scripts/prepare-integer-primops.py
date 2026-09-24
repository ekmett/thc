#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Export the pinned GHC scalar-word primops and record defined native inputs."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parent.parent
BUILD = ROOT / 'build/integer-primops'
SOURCE = 'compiler/test-fixtures/IntegerPrimopsAudit.hs'


def run(command, **kwargs):
    return subprocess.run([str(x) for x in command], cwd=ROOT, check=True, **kwargs)


def signed(bits):
    return bits if bits < 2**63 else bits - 2**64


def samples(width):
    mask = 2**width - 1
    values = {0, 1, 2, 3, mask, mask - 1, mask // 2, mask // 2 + 1,
              0x5555555555555555 & mask, 0xaaaaaaaaaaaaaaaa & mask}
    for bit in range(width):
        values.update(((2**bit - 1) & mask, 2**bit, (2**bit + 1) & mask))
    return sorted(values)


def main():
    BUILD.mkdir(parents=True, exist_ok=True)
    manifest = BUILD / 'manifest.json'
    manifest.unlink(missing_ok=True)
    entries = [dict(name=op + 'Word', primitive=op + 'Word#', width=64, arity=2)
               for op in ('quot', 'rem', 'gt', 'ge')]
    entries += [dict(name=op + f'Word{width}', primitive=op + f'Word{width}#',
                     width=width, arity=1 if op == 'not' else 2)
                for op in ('quot', 'rem', 'eq', 'ne', 'gt', 'ge', 'and', 'or', 'xor',
                           'not', 'uncheckedShiftL', 'uncheckedShiftRL')
                for width in (8, 16, 32)]
    for selector, entry in enumerate(entries):
        entry['selector'] = selector
    core = BUILD / 'core'
    env = dict(os.environ, THC_CORE_OUT=str(core), THC_GHC_OUT=str(BUILD / 'ghc'))
    run([ROOT / 'compiler/export.sh', *['-fplugin-opt=THC.Plugin:closure=' + e['name']
                                      for e in entries], '-fplugin-opt=THC.Plugin:closure=composite',
         SOURCE], env=env)
    spec = importlib.util.spec_from_file_location('core_audit', ROOT / 'scripts/audit-core.py')
    audit = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(audit)
    paths = sorted(core.glob('*.json'))
    modules = [(str(p.relative_to(ROOT)), json.loads(p.read_text())) for p in paths]
    capabilities = json.loads((ROOT / 'scripts/core-capabilities.json').read_text())
    composite_report = audit.Audit(modules, capabilities).run(['composite'])
    assert composite_report['accepted'], composite_report
    assert composite_report['summary']['reachableBindings'] == 1, composite_report['reachableBindings']
    required = {entry['primitive'] for entry in entries}
    retained = {primitive['name'] for primitive in composite_report['primitives']}
    assert required <= retained, sorted(required - retained)
    composite_id = composite_report['roots'][0]
    assert all(any(use['owner'] == composite_id for use in primitive['uses'])
               for primitive in composite_report['primitives'] if primitive['name'] in required), composite_id
    (BUILD / 'composite.audit.json').write_text(json.dumps(composite_report, indent=2) + '\n')
    driver = ['{-# LANGUAGE MagicHash #-}', 'module Main where',
              'import GHC.Exts (Int(I#), Int#)', 'import qualified IntegerPrimopsAudit as P',
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
        values = samples(width)
        if arity == 1:
            # Exhaust every byte and sample every bit transition for wider widths.
            operands = range(256) if width == 8 else values
            pairs = [(signed(x), 0) for x in operands]
        else:
            # Every bit transition against zero, one, sign-bit and all-ones divisors;
            # include equal operands and immediate neighbors for quotient boundaries.
            anchors = {0, 1, 2, 3, 2**(width - 1) - 1, 2**(width - 1),
                       2**(width - 1) + 1, 2**width - 2, 2**width - 1}
            if name.startswith('unchecked'):
                operands = range(256) if width == 8 else values
                pairs = [(signed(x), shift) for x in operands for shift in range(width)]
            else:
                pairs = {(x, y) for x in values for y in anchors}
                pairs.update((x, y) for x in anchors for y in values)
                pairs.update((x, y) for x in values for y in (x - 1, x, x + 1)
                             if 0 <= y < 2**width)
                # GHC's division-by-zero primop inputs have no numeric oracle.
                pairs = [(signed(x), signed(y)) for x, y in sorted(pairs)
                         if y != 0 or not name.startswith(('quot', 'rem'))]
        wanted.update((name, x, y) for x, y in pairs)
        requests.extend(f'{name}\t{x}\t{y}\n' for x, y in pairs)
        argument = 'read x' if arity == 1 else '(read x, read y)'
        driver.append(f'  "{name}" -> emit{arity} name P.{name} ({argument})')
    driver += ['  _ -> error "unknown primop"', 'dispatch _ = error "invalid input"',
               'main :: IO ()', 'main = getContents >>= mapM_ (dispatch . words) . lines']
    source = BUILD / 'NativeIntegerPrimops.hs'
    source.write_text('\n'.join(driver) + '\n')
    native = BUILD / 'native'
    native.mkdir(exist_ok=True)
    executable = native / 'integer-primops-oracle'
    run([os.environ.get('GHC', 'ghc'), '--make', '-O2', '-fforce-recomp', '-dcore-lint',
         '-dstg-lint', '-i' + str(ROOT / 'compiler/test-fixtures'), '-odir', native,
         '-hidir', native, source, '-o', executable])
    completed = run([executable], input=''.join(requests), text=True, capture_output=True, timeout=60)
    rows = [line.split('\t') for line in completed.stdout.splitlines()]
    actual = {(name, int(x), int(y)) for name, x, y, _ in rows}
    assert actual == wanted and len(rows) == len(wanted)
    (BUILD / 'oracle.tsv').write_text(completed.stdout)
    inputs = [SOURCE, 'scripts/prepare-integer-primops.py', 'scripts/core-capabilities.json', 'src/main/resources/thc/scalar-primop-signatures.json',
              'scripts/audit-core.py', 'compiler/build.sh', 'compiler/export.sh', 'compiler/toolchain.sh']
    inputs += [str(p.relative_to(ROOT)) for p in (ROOT / 'compiler/THC').glob('*.hs')]
    artifacts = [str(p.relative_to(ROOT)) for p in paths] + [
        'build/integer-primops/oracle.tsv', 'build/integer-primops/composite.audit.json']
    hashes = lambda items: {p: hashlib.sha256((ROOT / p).read_bytes()).hexdigest() for p in items}
    manifest.write_text(json.dumps(dict(schema=1, entries=entries,
                                       composite=dict(name='composite', arity=3, selectorArgument=0,
                                                      selectorOrder=[entry['name'] for entry in entries]),
                                       modules=[p for p, _ in modules],
                                       inputHashes=hashes(inputs), artifactHashes=hashes(artifacts)),
                                   indent=2) + '\n')
    print(f'Prepared {len(entries)} scalar integer primops / {len(rows)} native oracle rows')


if __name__ == '__main__':
    main()
