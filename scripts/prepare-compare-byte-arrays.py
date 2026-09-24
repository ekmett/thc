#!/usr/bin/env python3
"""Fresh public ShortByteString comparison plus sign-only native range evidence."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
BUILD = ROOT / 'build/compare-byte-arrays'
SOURCE = 'compiler/test-fixtures/CompareByteArraysAudit.hs'
ENTRIES = ['shortCompare', 'shortPrefix', 'shortSuffix', 'rangeCompare', 'aliasCompare']


def model(name, raw):
    k, x = raw & 4095, raw & 255
    if name == 'shortCompare':
        a = [x, 0, 128, 255]
        b = [[x, 0, 128, 255], [x, 0, 128], [x, 0, 128, 254], [x, 0, 129, 0], []][k % 5]
    elif name in ('shortPrefix', 'shortSuffix'):
        a = [x, 0, 128] if name == 'shortPrefix' else [128, x]
        b = ([[], [x, 0, 128, 255], [x, 0, 129, 255]] if name == 'shortPrefix'
             else [[], [0, 255, 128, x], [0, 255, 129, x]])[k % 3]
        return int(len(a) <= len(b) and (b[:len(a)] if name == 'shortPrefix' else b[-len(a):]) == a)
    else:
        assert name in ('rangeCompare', 'aliasCompare')
        a = [x, 0, 127, 128, 255, 17, 0, 255]
        b = a if name == 'aliasCompare' else [255, 0, 127, 128, x, 17, 255, 0]
        start, end = k % 9, k // 9 % 9
        count = min(k // 81 % 9, 8-start, 8-end)
        a, b = a[start:start+count], b[end:end+count]
    return (a > b) - (a < b)


def inputs(name):
    # All 9^3 small range selectors, plus 64-bit endpoints and byte transitions.
    ordinary = set(range(729)) if name in ('rangeCompare', 'aliasCompare') else set(range(-256, 257))
    return sorted(ordinary | {-(1 << 63), -(1 << 63)+1, (1 << 63)-2, (1 << 63)-1, -4097, 4097})


def run(command, **kwargs):
    return subprocess.run([str(x) for x in command], cwd=ROOT, check=True, **kwargs)


def main():
    BUILD.mkdir(parents=True, exist_ok=True)
    (BUILD/'manifest.json').unlink(missing_ok=True)
    ghc, ghc_pkg = os.environ.get('GHC', 'ghc'), os.environ.get('GHC_PKG', 'ghc-pkg')
    assert run([ghc, '--numeric-version'], text=True, capture_output=True).stdout.strip() == '9.14.1'
    assert run([ghc_pkg, 'field', 'bytestring', 'version', '--simple-output'], text=True, capture_output=True).stdout.strip() == '0.12.2.0'
    spec = importlib.util.spec_from_file_location('core_audit', ROOT/'scripts/audit-core.py')
    audit = importlib.util.module_from_spec(spec); spec.loader.exec_module(audit)
    capability = json.loads((ROOT/'scripts/core-capabilities.json').read_text())
    stages, artifacts, source_hashes, summaries = {}, [], {}, {}
    for stage in ('pre', 'post'):
        directory = BUILD/stage
        env = dict(os.environ, THC_CORE_OUT=str(directory/'core'), THC_GHC_OUT=str(directory/'ghc'))
        options = ['-fplugin-opt=THC.Plugin:post-tidy'] if stage == 'post' else []
        run([ROOT/'compiler/export.sh', *options,
             *['-fplugin-opt=THC.Plugin:closure='+name for name in ENTRIES], SOURCE], env=env)
        # Original pinned List bodies supply the omitted $wlenAcc unfolding.
        run([sys.executable, ROOT/'compiler/export-boot.py', '--frontier', 'lists', '--build-dir', directory], env=env)
        provenance = directory/'boot-provenance.json'
        artifacts.append(str(provenance.relative_to(ROOT)))
        for item in json.loads(provenance.read_text())['sources']:
            assert hashlib.sha256((ROOT/item['path']).read_bytes()).hexdigest() == item['sha256']
            source_hashes[item['path']] = item['sha256']
        paths = sorted((directory/'core').glob('*.json'))
        stages[stage] = [str(p.relative_to(ROOT)) for p in paths]
        artifacts += stages[stage]
        modules = [(str(p.relative_to(ROOT)), json.loads(p.read_text())) for p in paths]
        for name in ENTRIES:
            report = audit.Audit(modules, capability).run([name])
            assert report['accepted'], (stage, name, report['issues'], report['missingGlobals'])
            assert 'compareByteArrays#' in {p['name'] for p in report['primitives']}, (stage, name)
            assert any(b['id'].endswith('Data.ByteString.Short.Internal.$wpack') for b in report['reachableBindings'])
            path = directory/(name+'.audit.json'); path.write_text(json.dumps(report, indent=2)+'\n')
            artifacts.append(str(path.relative_to(ROOT))); summaries[stage+'/'+name] = report['summary']
    driver = ['{-# LANGUAGE MagicHash #-}', 'module Main where', 'import GHC.Exts',
              'import qualified CompareByteArraysAudit as P',
              'emit :: String -> (Int# -> Int#) -> Int -> IO ()',
              'emit n f x@(I# a) = putStrLn (n ++ "\\t" ++ show x ++ "\\t" ++ show (I# (f a)))',
              'dispatch :: [String] -> IO ()', 'dispatch [name,x] = case name of']
    for name in ENTRIES: driver.append(f'  "{name}" -> emit name P.{name} (read x)')
    driver += ['  _ -> error "entry"', 'dispatch _ = error "input"',
               'main = getContents >>= mapM_ (dispatch . words) . lines']
    native_source = BUILD/'NativeCompareByteArrays.hs'; native_source.write_text('\n'.join(driver)+'\n')
    native = BUILD/'native'; native.mkdir(exist_ok=True)
    executable = native/'compare-byte-arrays-oracle'
    run([ghc, '--make', '-O2', '-fforce-recomp', '-dcore-lint', '-dstg-lint', '-i'+str(ROOT/'compiler/test-fixtures'),
         '-odir', native, '-hidir', native, native_source, '-o', executable])
    requests = [(name,x) for name in ENTRIES for x in inputs(name)]
    result = run([executable], input=''.join(f'{name}\t{x}\n' for name,x in requests), text=True, capture_output=True)
    rows = [row.split('\t') for row in result.stdout.splitlines()]
    assert len(rows) == len(requests) and [(n,int(x)) for n,x,_ in rows] == requests
    for name,x,value in rows: assert int(value) == model(name,int(x)), (name,x,value)
    (BUILD/'oracle.tsv').write_text(result.stdout)
    artifacts += [str(p.relative_to(ROOT)) for p in (native_source, executable, BUILD/'oracle.tsv')]
    sources = [SOURCE, 'scripts/prepare-compare-byte-arrays.py', 'scripts/core-capabilities.json', 'scripts/audit-core.py',
               'compiler/export.sh', 'compiler/export-boot.py', 'compiler/build.sh', 'compiler/toolchain.sh',
               'src/main/resources/thc/scalar-primop-signatures.json']
    sources += sorted(str(p.relative_to(ROOT)) for p in (ROOT/'scripts').glob('core_*.py'))
    sources += sorted(str(p.relative_to(ROOT)) for p in (ROOT/'compiler/THC').glob('*.hs'))
    hashes = lambda paths: {p: hashlib.sha256((ROOT/p).read_bytes()).hexdigest() for p in paths}
    manifest = dict(schema=1, ghc='9.14.1', bytestring='0.12.2.0', resultContract='sign only', entries=ENTRIES,
                    inputsByEntry={n:inputs(n) for n in ENTRIES}, stages=stages, audits=summaries,
                    nativeRows=len(rows), inputHashes=hashes(sources)|source_hashes, artifactHashes=hashes(artifacts))
    (BUILD/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    print(f'Prepared {len(rows)} sign-normalized native/model rows, {len(ENTRIES)} public/range entries, pre+post strict audits')


if __name__ == '__main__': main()
