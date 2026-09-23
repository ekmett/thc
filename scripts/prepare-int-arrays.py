#!/usr/bin/env python3
"""Real public unboxed Int arrays and ordered typed/byte-alias native evidence."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
BUILD = ROOT / 'build/int-arrays'
MASK = (1 << 64) - 1
PATTERN = 0x55aa55aa55aa55aa
INT_PRIMITIVES = {'readIntArray#', 'writeIntArray#', 'indexIntArray#'}
STORAGE = {'newByteArray#', 'unsafeFreezeByteArray#'}
GROUPS = [dict(source='examples/THC/UnboxedArrays.hs', module='THC.UnboxedArrays',
               entries=['unboxedAccum', 'unboxedST', 'unboxedEmpty']),
          dict(source='compiler/test-fixtures/IntArrayAudit.hs', module='IntArrayAudit',
               entries=['orderedInts', 'aliasIntBytes'])]
ENTRIES = [name for group in GROUPS for name in group['entries']]
REQUIRED = {name: INT_PRIMITIVES | STORAGE for name in ENTRIES}
REQUIRED['unboxedEmpty'] = set()
REQUIRED['orderedInts'] |= {'sizeofByteArray#'}
REQUIRED['aliasIntBytes'] |= {'writeWord8Array#', 'indexWord8Array#'}
BOUNDARIES = {'pre': 'optimized-Core-before-Tidy', 'post': 'optimized-Core-after-Tidy-before-CorePrep'}


def check(condition, message):
    if not condition:
        raise AssertionError(message)


def signed(x):
    return ((x + (1 << 63)) & MASK) - (1 << 63)


def inputs():
    values = set(range(-16, 17)) | {-(1 << 63), -(1 << 63)+1, (1 << 63)-2, (1 << 63)-1}
    values |= {signed(sign*((1 << bit)+delta)) for bit in range(64) for delta in (-1, 0, 1) for sign in (-1, 1)}
    values |= {signed(x) for x in (0x5555555555555555, 0xaaaaaaaaaaaaaaaa, PATTERN,
                                  0xaa55aa55aa55aa55, 0x0123456789abcdef, 0xfedcba9876543210)}
    return sorted(values)


def mathematical(name, x, byte_order=sys.byteorder):
    if name == 'unboxedAccum':
        # Cells -3, 0, 4 become x+1, x+5, 2*x respectively.
        return signed(44*x + 62)
    if name == 'unboxedST':
        # Read-after-write: cells -3, 0, 4 are x, x+7, 2*x+21.
        return signed(44*x + 350)
    if name == 'unboxedEmpty':
        return signed(x + 7)
    if name == 'orderedInts':
        before, after = signed(x+1), signed(x+17)
        aa, bb = [x, after, signed(x ^ PATTERN)], [signed(x+71)]
        return signed(3*before + 5*after + 7*aa[0] + 11*aa[1] + 13*aa[2] + 17*bb[0] + 32)
    if name == 'aliasIntBytes':
        check(byte_order in ('little', 'big'), 'Unknown byte order')
        storage = bytearray((x & MASK).to_bytes(8, byte_order) + ((x ^ PATTERN) & MASK).to_bytes(8, byte_order))
        storage[7], storage[8] = (x+101) & 255, (x+37) & 255
        a, b = [int.from_bytes(storage[i:i+8], byte_order, signed=True) for i in (0, 8)]
        return signed(3*a + 5*b + 7*a + 11*b + 17*storage[0] + 19*storage[7] + 23*storage[8] + 29*storage[15])
    raise ValueError(name)


def parse_rows(text, values):
    expected = {(name, x) for name in ENTRIES for x in values}
    rows = {}
    for line in text.splitlines():
        parts = line.split('\t')
        check(len(parts) == 3, 'Malformed native row: '+line)
        name, x, answer = parts
        key = (name, int(x))
        check(key in expected and key not in rows, 'Unknown or duplicate native row: '+line)
        rows[key] = int(answer)
    check(rows.keys() == expected, 'Missing native rows')
    return rows


def check_report(name, report):
    check(report['accepted'], name+': strict capability audit failed')
    counts = {p['name']: len(p['uses']) for p in report['primitives']}
    check(REQUIRED[name] <= counts.keys(), name+': required reachable primitive disappeared')
    # These are actual state-threaded use sites, not hand-written proof fixtures.
    exact = {'orderedInts': {'newByteArray#': 2, 'readIntArray#': 2, 'writeIntArray#': 5,
                            'unsafeFreezeByteArray#': 2, 'indexIntArray#': 4},
             'aliasIntBytes': {'newByteArray#': 1, 'writeIntArray#': 2, 'writeWord8Array#': 2,
                              'readIntArray#': 2, 'unsafeFreezeByteArray#': 1,
                              'indexIntArray#': 2, 'indexWord8Array#': 4}}
    for primitive, count in exact.get(name, {}).items():
        check(counts.get(primitive) == count, f'{name}: {primitive} use count changed: {counts.get(primitive)} != {count}')
    return counts


def hashes(paths):
    return {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(set(paths))}


def main():
    BUILD.mkdir(parents=True, exist_ok=True)
    manifest = BUILD / 'manifest.json'
    manifest.unlink(missing_ok=True)
    ghc, ghc_pkg = os.environ.get('GHC', 'ghc'), os.environ.get('GHC_PKG', 'ghc-pkg')
    commands = []
    def run(command, env=None, **kwargs):
        command = [str(x) for x in command]
        commands.append(dict(argv=command, environment=env or {}))
        return subprocess.run(command, cwd=ROOT, env=dict(os.environ, **(env or {})), check=True, **kwargs)
    check(run([ghc, '--numeric-version'], text=True, capture_output=True).stdout.strip() == '9.14.1', 'Requires GHC9.14.1')
    version = run([ghc_pkg, 'field', 'array', 'version', '--simple-output'], text=True, capture_output=True).stdout.strip()
    check(version == '0.5.8.0', 'Requires installed array0.5.8.0')
    spec = importlib.util.spec_from_file_location('int_array_auditor', ROOT / 'scripts/audit-core.py')
    audit = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(audit)
    capabilities = json.loads((ROOT / 'scripts/core-capabilities.json').read_text())
    artifacts, stages, closures, primitive_counts = [], {}, {}, {}
    for stage, boundary in BOUNDARIES.items():
        paths = []
        for index, group in enumerate(GROUPS):
            directory = BUILD / stage / str(index)
            core = directory / 'core'
            options = ['-fplugin-opt=Thc.Plugin:post-tidy'] if stage == 'post' else []
            run([ROOT / 'compiler/export.sh', *options,
                 *['-fplugin-opt=Thc.Plugin:closure='+name for name in group['entries']], group['source']],
                env=dict(THC_CORE_OUT=str(core), THC_GHC_OUT=str(directory / 'ghc'), THC_SOURCE_NOTES='true'))
            module_path = core / (group['module']+'.json')
            check(json.loads(module_path.read_text())['boundary'] == boundary, 'Wrong Core boundary')
            paths += [module_path, core / 'THC.InterfaceClosure.json']
        modules = [(str(p.relative_to(ROOT)), json.loads(p.read_text())) for p in paths]
        stages[stage] = [p for p, _ in modules]
        artifacts += paths
        for name in ENTRIES:
            report = audit.Audit(modules, capabilities).run([name])
            path = BUILD / stage / (name+'.audit.json')
            path.write_text(json.dumps(report, indent=2)+'\n')
            artifacts.append(path)
            primitive_counts[stage+'/'+name] = check_report(name, report)
            closures[stage+'/'+name] = report['reachableBindings']
    driver = ['{-# LANGUAGE MagicHash #-}', 'module Main where', 'import GHC.Exts (Int(I#), Int#)',
              'import Data.Bits (finiteBitSize)', 'import qualified THC.UnboxedArrays as U',
              'import qualified IntArrayAudit as P',
              'emit :: String -> (Int# -> Int#) -> Int -> IO ()',
              'emit n f x@(I# a) = putStrLn (n ++ "\\t" ++ show x ++ "\\t" ++ show (I# (f a)))',
              'dispatch :: [String] -> IO ()', 'dispatch [name, x] = case name of']
    for group in GROUPS:
        for name in group['entries']:
            prefix = 'U.' if group['module'] == 'THC.UnboxedArrays' else 'P.'
            driver.append(f'  "{name}" -> emit name {prefix}{name} (read x)')
    driver += ['  _ -> error "unknown entry"', 'dispatch _ = error "invalid input"',
               'main :: IO ()', 'main = if finiteBitSize (0 :: Int) /= 64 then error "Requires 64-bit Int"',
               '       else getContents >>= mapM_ (dispatch . words) . lines']
    source = BUILD / 'NativeIntArray.hs'
    source.write_text('\n'.join(driver)+'\n')
    native = BUILD / 'native'
    native.mkdir(exist_ok=True)
    binary = native / 'int-array-oracle'
    run([ghc, '--make', '-O2', '-fforce-recomp', '-dcore-lint', '-dstg-lint',
         '-i'+str(ROOT/'examples'), '-i'+str(ROOT/'compiler/test-fixtures'),
         '-odir', native, '-hidir', native, source, '-o', binary])
    values = inputs()
    requests = ''.join(f'{name}\t{x}\n' for name in ENTRIES for x in values)
    result = run([binary], input=requests, text=True, capture_output=True, timeout=60)
    actual = parse_rows(result.stdout, values)
    wanted = {(name, x): mathematical(name, x) for name in ENTRIES for x in values}
    check(actual == wanted, 'Native/model mismatch: '+str(next(
        ((k, actual[k], v) for k, v in wanted.items() if actual[k] != v), None)))
    (BUILD/'oracle.tsv').write_text(result.stdout)
    (BUILD/'expected.tsv').write_text(''.join(f'{name}\t{x}\t{answer}\n' for (name,x),answer in wanted.items()))
    inputs_to_hash = [ROOT/g['source'] for g in GROUPS] + [Path(__file__).resolve(),
        ROOT/'scripts/test-int-array-model.py', ROOT/'scripts/core-capabilities.json', ROOT/'scripts/audit-core.py',
        ROOT/'src/main/resources/thc/scalar-primop-signatures.json',
        *sorted((ROOT/'scripts').glob('core_*.py')), *sorted((ROOT/'compiler/Thc').glob('*.hs')),
        *[ROOT/'compiler'/n for n in ('build.sh','export.sh','toolchain.sh')]]
    artifacts += [BUILD/'oracle.tsv', BUILD/'expected.tsv', source, binary]
    manifest.write_text(json.dumps(dict(schema=1, ghc='9.14.1', array=version, wordBits=64, byteOrder=sys.byteorder,
        entries=ENTRIES, inputs=values, stages=stages, nativeRows=len(actual),
        allNativeResultsMatchIndependentModels=True,
        requiredPrimitivesByEntry={n:sorted(v) for n,v in REQUIRED.items()},
        primitiveCounts=primitive_counts, reachableBindings=closures, commands=commands,
        installedArray=run([ghc_pkg,'describe','array'],text=True,capture_output=True).stdout,
        ghcInfo=run([ghc,'--info'],text=True,capture_output=True).stdout,
        inputHashes=hashes(inputs_to_hash), artifactHashes=hashes(artifacts),
        claim='Native/model, exact original library/interface Core and all-branch strict audits; guest/compiled execution checked separately.',
        limitations=['Public nonempty indices/bounds are constants; dynamic checked-index error paths are not claimed.',
                    'The public empty root may optimize away; no retained allocation claim is made for it.',
                    'Machine Int width is 64 bits; mixed Int/byte storage follows recorded native byte order.']),indent=2)+'\n')
    print(f'Prepared {len(ENTRIES)} Int array entries / {len(actual)} native-model rows / strict pre+post Core')


if __name__ == '__main__':
    main()
