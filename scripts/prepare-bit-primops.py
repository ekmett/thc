#!/usr/bin/env python3
"""Retain pinned GHC scalar bit primops and compare only their defined result bits."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parent.parent
BUILD = ROOT / 'build/bit-primops'
SOURCE = 'compiler/test-fixtures/BitPrimopsAudit.hs'
WORD_MASK = 2**64 - 1


def run(command, **kwargs):
    return subprocess.run([str(x) for x in command], cwd=ROOT, check=True, **kwargs)


def entries():
    result = []
    for operation, widths in [('popCnt', (8, 16, 32, 64)), ('clz', (8, 16, 32, 64)),
                              ('ctz', (8, 16, 32, 64)), ('byteSwap', (16, 32, 64, None)),
                              ('bitReverse', (8, 16, 32, 64, None))]:
        for width in widths:
            explicit64 = width == 64
            result.append(dict(name=operation + (str(width) if width else 'Word'),
                               primitive=operation + (str(width) if width else '') + '#',
                               operation=operation, width=width or 64, arity=1,
                               argumentRep='Word64Rep' if explicit64 else 'WordRep',
                               resultRep='Word64Rep' if explicit64 and operation in ('byteSwap', 'bitReverse') else 'WordRep',
                               definedResultBits=(width or 64) if operation in ('byteSwap', 'bitReverse') else 64))
    return result


def samples(width):
    mask = 2**width - 1
    values = {0, 1, 2, 3, mask, mask - 1, 0x5555555555555555 & mask, 0xaaaaaaaaaaaaaaaa & mask}
    for bit in range(width):
        values.update(((2**bit - 1) & mask, 2**bit, (2**bit + 1) & mask))
    return values


def operands(width):
    values = samples(64)  # Every machine-word bit, including bits above a narrow input.
    if width < 64:
        high = WORD_MASK ^ (2**width - 1)
        prefixes = {0, 2**width, 2**63, high, high & 0xaaaaaaaaaaaaaaaa}
        low = range(256) if width == 8 else samples(width)
        values.update(prefix | value for prefix in prefixes for value in low)
    return sorted(value if value < 2**63 else value - 2**64 for value in values)


def applications(value):
    if isinstance(value, dict):
        for child in value.values():
            yield from applications(child)
    elif isinstance(value, list):
        if value and value[0] == 'app':
            yield value
        for child in value:
            yield from applications(child)


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
    operations = entries()
    artifacts = []
    stages = {}
    for stage in ('pre', 'post'):
        core = BUILD / (stage + '-core')
        env = dict(os.environ, THC_CORE_OUT=str(core), THC_GHC_OUT=str(BUILD / (stage + '-ghc')))
        options = ['-fplugin-opt=Thc.Plugin:post-tidy'] if stage == 'post' else []
        run([ROOT / 'compiler/export.sh', *options,
             *['-fplugin-opt=Thc.Plugin:closure=' + e['name'] for e in operations], SOURCE], env=env)
        paths = sorted(core.glob('*.json'))
        modules = [(str(p.relative_to(ROOT)), json.loads(p.read_text())) for p in paths]
        stages[stage] = [p for p, _ in modules]
        artifacts.extend(stages[stage])
        apps = [app for _, module in modules for app in applications(module)]
        for entry in operations:
            report = audit.Audit(modules, capabilities).run([entry['name']])
            assert report['accepted'], (entry['name'], report)
            assert entry['primitive'] in {p['name'] for p in report['primitives']}, (stage, entry, report)
            retained = [app for app in apps if app[1][:2] == ['prim', entry['primitive']]]
            assert retained, (stage, entry)
            for app in retained:
                assert len(app[2]) == 1 and app[3] == [False], (entry, app)
                assert audit.Audit.expression_rep(app[2][0])['primReps'] == [entry['argumentRep']], (entry, app)
                assert audit.Audit.expression_rep(app)['primReps'] == [entry['resultRep']], (entry, app)
            report_path = BUILD / f'{stage}-{entry["name"]}.audit.json'
            report_path.write_text(json.dumps(report, indent=2) + '\n')
            artifacts.append(str(report_path.relative_to(ROOT)))
    driver = ['{-# LANGUAGE MagicHash #-}', 'module Main where',
              'import GHC.Exts (Int(I#), Int#, andI#)', 'import qualified BitPrimopsAudit as P',
              'emit :: String -> (Int# -> Int#) -> Int -> Int -> IO ()',
              'emit n f (I# mask) x@(I# a) = putStrLn (n ++ "\\t" ++ show x ++ "\\t" ++ show (I# (andI# (f a) mask)))',
              'dispatch :: [String] -> IO ()', 'dispatch [name, x] = case name of']
    requests = []
    wanted = set()
    for entry in operations:
        name = entry['name']
        mask = 2**entry['definedResultBits'] - 1
        mask = mask if mask < 2**63 else mask - 2**64
        driver.append(f'  "{name}" -> emit name P.{name} ({mask}) (read x)')
        for x in operands(entry['width']):
            wanted.add((name, x))
            requests.append(f'{name}\t{x}\n')
    driver += ['  _ -> error "unknown primitive"', 'dispatch _ = error "invalid input"',
               'main :: IO ()', 'main = getContents >>= mapM_ (dispatch . words) . lines']
    source = BUILD / 'NativeBitPrimops.hs'
    source.write_text('\n'.join(driver) + '\n')
    native = BUILD / 'native'
    native.mkdir(exist_ok=True)
    executable = native / 'bit-primops-oracle'
    run([ghc, '--make', '-O2', '-fforce-recomp', '-dcore-lint', '-dstg-lint',
         '-i' + str(ROOT / 'compiler/test-fixtures'), '-odir', native, '-hidir', native, source, '-o', executable])
    result = run([executable], input=''.join(requests), text=True, capture_output=True, timeout=60)
    rows = [line.split('\t') for line in result.stdout.splitlines()]
    assert {(name, int(x)) for name, x, _ in rows} == wanted and len(rows) == len(wanted)
    (BUILD / 'oracle.tsv').write_text(result.stdout)
    inputs = [SOURCE, 'scripts/prepare-bit-primops.py', 'scripts/core-capabilities.json', 'src/main/resources/thc/scalar-primop-signatures.json', 'scripts/audit-core.py',
              'scripts/core_vectors.py', 'compiler/build.sh', 'compiler/export.sh', 'compiler/toolchain.sh']
    inputs += sorted(str(p.relative_to(ROOT)) for p in (ROOT / 'compiler/Thc').glob('*.hs'))
    artifacts += ['build/bit-primops/oracle.tsv', 'build/bit-primops/NativeBitPrimops.hs']
    hashes = lambda paths: {p: hashlib.sha256((ROOT / p).read_bytes()).hexdigest() for p in paths}
    manifest.write_text(json.dumps(dict(schema=1, ghc='9.14.1', entries=operations, stages=stages,
        nativeRows=len(rows), nativeResultPolicy='Mask only GHC-defined bits; THC checks canonical zero upper bits directly',
        inputHashes=hashes(inputs), artifactHashes=hashes(artifacts)), indent=2) + '\n')
    print(f'Prepared {len(operations)} scalar bit primops / {len(rows)} native oracle rows / pre+post Core')


if __name__ == '__main__':
    main()
