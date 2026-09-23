#!/usr/bin/env python3
"""Prepare genuine ShortByteString and ordered managed ByteArray native coverage."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
BUILD = ROOT / 'build/bytearray'
SOURCE = 'compiler/test-fixtures/ByteArrayAudit.hs'
WORD_MASK = 2**64 - 1


def run(command, **kwargs):
    return subprocess.run([str(x) for x in command], cwd=ROOT, check=True, **kwargs)


PRIMITIVES = {'newByteArray#', 'writeWord8Array#', 'unsafeFreezeByteArray#',
              'sizeofByteArray#', 'indexWord8Array#'}
ENTRIES = ['shortBytes', 'orderedBytes']


def mathematical(name, x):
    if name == 'orderedBytes':
        return 3 + x % 256 + ((x + 17) % 256) * 257 + ((x + 2) % 256) * 65537 + ((x + 71) % 256) * 16777259
    value = 0
    n = abs(x) % 33
    for i in range(n):
        value = value * 33 + (x + i * 17) % 256
    value = (value + n) % 2**64
    return value if value < 2**63 else value - 2**64


def main():
    BUILD.mkdir(parents=True, exist_ok=True)
    manifest = BUILD / 'manifest.json'
    manifest.unlink(missing_ok=True)
    ghc = os.environ.get('GHC', 'ghc')
    assert run([ghc, '--numeric-version'], text=True, capture_output=True).stdout.strip() == '9.14.1'
    bytestring = run([os.environ.get('GHC_PKG', 'ghc-pkg'), 'field', 'bytestring', 'version', '--simple-output'],
                     text=True, capture_output=True).stdout.strip()
    assert bytestring == '0.12.2.0', bytestring
    spec = importlib.util.spec_from_file_location('core_audit', ROOT / 'scripts/audit-core.py')
    audit = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(audit)
    capabilities = json.loads((ROOT / 'scripts/core-capabilities.json').read_text())
    artifacts, sources, stages, closures = [], {}, {}, {}
    for stage in ('pre', 'post'):
        directory = BUILD / stage
        core = directory / 'core'
        env = dict(os.environ, THC_CORE_OUT=str(core), THC_GHC_OUT=str(directory / 'ghc'))
        options = ['-fplugin-opt=Thc.Plugin:post-tidy'] if stage == 'post' else []
        run([ROOT / 'compiler/export.sh', *options,
             *['-fplugin-opt=Thc.Plugin:closure=' + name for name in ENTRIES], SOURCE], env=env)
        # The installed interface omits $wlenAcc. Export the pinned original List
        # source with canonical ghc-internal identities, never a replacement body.
        run([sys.executable, ROOT / 'compiler/export-boot.py', '--frontier', 'lists', '--build-dir', directory], env=env)
        provenance = directory / 'boot-provenance.json'
        artifacts.append(str(provenance.relative_to(ROOT)))
        for source in json.loads(provenance.read_text())['sources']:
            assert hashlib.sha256((ROOT / source['path']).read_bytes()).hexdigest() == source['sha256']
            sources[source['path']] = source['sha256']
        paths = sorted(core.glob('*.json'))
        modules = [(str(p.relative_to(ROOT)), json.loads(p.read_text())) for p in paths]
        stages[stage] = [p for p, _ in modules]
        artifacts.extend(stages[stage])
        for name in ENTRIES:
            report = audit.Audit(modules, capabilities).run([name])
            report_path = directory / (name + '.audit.json')
            report_path.write_text(json.dumps(report, indent=2) + '\n')
            artifacts.append(str(report_path.relative_to(ROOT)))
            assert report['accepted'], (stage, name, report_path)
            assert PRIMITIVES <= {p['name'] for p in report['primitives']}, (stage, name, report)
            if name == 'orderedBytes':
                counts = {primitive['name']: len(primitive['uses']) for primitive in report['primitives']}
                for primitive, count in {'newByteArray#': 2, 'unsafeFreezeByteArray#': 2,
                                         'writeWord8Array#': 5, 'indexWord8Array#': 4}.items():
                    assert counts[primitive] == count, (stage, primitive, counts)
            identities = {binding['id'] for binding in report['reachableBindings']}
            if name == 'shortBytes':
                for suffix in ('Data.ByteString.Short.Internal.$wpack', 'Data.ByteString.Short.Internal.$wgo',
                               'GHC.Internal.List.$wlenAcc'):
                    assert any(identity.endswith(':' + suffix) for identity in identities), (stage, suffix, identities)
            closures[stage + '/' + name] = report['reachableBindings']
    driver = ['{-# LANGUAGE MagicHash #-}', 'module Main where', 'import GHC.Exts (Int(I#), Int#)',
              'import qualified ByteArrayAudit as P',
              'emit :: String -> (Int# -> Int#) -> Int -> IO ()',
              'emit n f x@(I# a) = putStrLn (n ++ "\\t" ++ show x ++ "\\t" ++ show (I# (f a)))',
              'dispatch :: [String] -> IO ()', 'dispatch [name, x] = case name of']
    values = sorted(set(range(-512, 513)) | {-2**63, -2**63+1, 2**63-2, 2**63-1})
    requests = []
    for name in ENTRIES:
        driver.append(f'  "{name}" -> emit name P.{name} (read x)')
        requests.extend(f'{name}\t{x}\n' for x in values)
    driver += ['  _ -> error "unknown entry"', 'dispatch _ = error "invalid input"',
               'main :: IO ()', 'main = getContents >>= mapM_ (dispatch . words) . lines']
    source = BUILD / 'NativeByteArray.hs'
    source.write_text('\n'.join(driver) + '\n')
    native = BUILD / 'native'
    native.mkdir(exist_ok=True)
    executable = native / 'bytearray-oracle'
    run([ghc, '--make', '-O2', '-fforce-recomp', '-dcore-lint', '-dstg-lint',
         '-i' + str(ROOT / 'compiler/test-fixtures'), '-odir', native, '-hidir', native, source, '-o', executable])
    result = run([executable], input=''.join(requests), text=True, capture_output=True, timeout=60)
    rows = [line.split('\t') for line in result.stdout.splitlines()]
    assert len(rows) == len(ENTRIES) * len(values)
    assert {(name, int(x)) for name, x, _ in rows} == {(name, x) for name in ENTRIES for x in values}
    for name, x, value in rows:
        assert int(value) == mathematical(name, int(x)), (name, x, value)
    (BUILD / 'oracle.tsv').write_text(result.stdout)
    inputs = [SOURCE, 'scripts/prepare-bytearray.py', 'scripts/core-capabilities.json', 'scripts/audit-core.py',
              'scripts/core_vectors.py', 'src/main/resources/thc/scalar-primop-signatures.json', 'compiler/build.sh', 'compiler/export.sh', 'compiler/toolchain.sh', 'compiler/export-boot.py']
    inputs += sorted(str(p.relative_to(ROOT)) for p in (ROOT / 'compiler/Thc').glob('*.hs'))
    artifacts += ['build/bytearray/oracle.tsv', 'build/bytearray/NativeByteArray.hs']
    hashes = lambda paths: {p: hashlib.sha256((ROOT / p).read_bytes()).hexdigest() for p in paths}
    manifest.write_text(json.dumps(dict(schema=1, ghc='9.14.1', bytestring=bytestring, entries=ENTRIES, stages=stages,
        nativeRows=len(rows), reachableBindings=closures,
        inputHashes=hashes(inputs) | sources, artifactHashes=hashes(artifacts)), indent=2) + '\n')
    print(f'Prepared {len(ENTRIES)} real ByteArray workloads / {len(rows)} native rows / pre+post Core')


if __name__ == '__main__':
    main()
