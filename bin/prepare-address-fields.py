#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

# Fixture rationale (136 address-fields)
# Purpose: Check pointer-valued constructor fields preserve addresses across storage and
#   calls.
# Inputs: AddressFieldAudit.hs, this driver's byte model, selected GHC, exporter,
#   auditor and compact decoder; all declared in cmake/ScriptFixtures.cmake.
# Produces: pre/post CBDs and two audits, one generated native driver/executable,
#   225 oracle/model rows and manifest. Each persistent file has one CMake writer.
# Cost and overlap: Address storage in data fields, closures, partial applications
#   and tuple returns is distinct from direct address reads. Keep observable bytes,
#   lazy neighbours and carrier rejection; no GHC lambda/case/constructor predictions.
# Detailed file inputs/outputs: docs/fixture-inputs.log, entry 136.

"""Native GHC managed literal addresses in constructors and unboxed tuples."""
import core_package_manifest
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parent.parent
BUILD = ROOT / 'build/address-fields'
SOURCE = 't/fixtures/compiler/AddressFieldAudit.hs'
ENTRIES = ['natural', 'returned', 'captured', 'partial', 'twins', 'emptyLiteral', 'terminator', 'backwards', 'tupleFrontier']
VALUES = sorted(set(range(-8, 9)) | {-2**63, -2**63+1, 2**63-2, 2**63-1, -4097, 4097, -2**32, 2**32})

def signed(value): return (value + 2**63) % 2**64 - 2**63

def model(name, x):
    byte = [65, 255, 128, 0][x & 3]
    return signed({'natural': x+byte, 'returned': 4*x+2+byte,
        'captured': 4*x+5+byte, 'partial': 4*x+7+byte,
        'twins': x+630+byte, 'emptyLiteral': 4*x+11, 'terminator': 4*x+13, 'backwards': x+byte,
        'tupleFrontier': x+65}[name])

def main():
    BUILD.mkdir(parents=True, exist_ok=True)
    (BUILD/'manifest.json').unlink(missing_ok=True)
    commands = []
    def run(command, env=None, **kwargs):
        command = list(map(str, command)); commands.append(dict(argv=command, environment=env or {}))
        return subprocess.run(command, cwd=ROOT, env=dict(os.environ, **(env or {})), check=True, **kwargs)
    ghc = os.environ.get('GHC', 'ghc')
    assert run([ghc, '--numeric-version'], text=True, capture_output=True).stdout.strip() == '9.14.1'
    spec = importlib.util.spec_from_file_location('address_audit', ROOT/'bin/audit-core.py')
    audit = importlib.util.module_from_spec(spec); spec.loader.exec_module(audit)
    cap = json.loads((ROOT/'bin/core-capabilities.json').read_text())
    assert 'AddrRep' in cap['fieldRepresentations'] and 'AddrRep' in cap['aggregateFieldRepresentations']
    stages, summaries, artifacts = {}, {}, []
    for stage in ('pre', 'post'):
        directory = BUILD/stage; core = directory/'core'
        run([ROOT/'bin/export-core.sh', *(['-fplugin-opt=THC.Plugin:post-tidy'] if stage=='post' else []), SOURCE],
            env=dict(THC_CORE_OUT=str(core), THC_GHC_OUT=str(directory/'ghc')))
        path = core/'AddressFieldAudit.cbd'; module = core_package_manifest.inspect_cbd(path.read_bytes()); artifacts.append(path)
        stages[stage] = str(path.relative_to(ROOT))
        assert module['boundary'] == ('optimized-Core-before-Tidy' if stage=='pre' else 'optimized-Core-after-Tidy-before-CorePrep')
        roots = [module['unit'] + ':' + module['module'] + '.' + name for name in ENTRIES]
        report = audit.Audit([(str(path.relative_to(ROOT)), module)], cap).run(roots)
        dest = directory/'audit.json'; dest.write_text(json.dumps(report, indent=2)+'\n'); artifacts.append(dest)
        assert report['accepted'], (stage, report['issues'])
        assert not report['missingGlobals'], (stage, report['missingGlobals'])
        summaries[stage] = report['summary']
    driver = ['{-# LANGUAGE MagicHash #-}', 'module Main where', 'import GHC.Exts (Int(I#))',
        'import qualified AddressFieldAudit as P', 'call name (I# x) = case name of']
    driver += ['  "'+name+'" -> I# (P.'+name+' x)' for name in ENTRIES]
    driver += ['  _ -> error "invalid entry"', 'emit [name,x] = putStrLn (name ++ "\\t" ++ x ++ "\\t" ++ show (call name (read x)))',
        'emit _ = error "invalid input"', 'main = getContents >>= mapM_ (emit . words) . lines']
    source = BUILD/'NativeAddressFields.hs'; source.write_text('\n'.join(driver)+'\n')
    native = BUILD/'native'; native.mkdir(exist_ok=True); executable = native/'address-fields-oracle'
    run([ghc, '--make', '-O2', '-fforce-recomp', '-dcore-lint', '-dstg-lint', '-i'+str(ROOT/'t/fixtures/compiler'),
         '-odir', native, '-hidir', native, source, '-o', executable])
    requests = ''.join(f'{name}\t{x}\n' for name in ENTRIES for x in VALUES)
    expected = ''.join(f'{name}\t{x}\t{model(name,x)}\n' for name in ENTRIES for x in VALUES)
    result = run([executable], input=requests, text=True, capture_output=True, timeout=30)
    assert result.stdout == expected, 'Native/independent bounded byte-index model mismatch'
    (BUILD/'oracle.tsv').write_text(result.stdout); (BUILD/'expected.tsv').write_text(expected)
    inputs = [ROOT/SOURCE, Path(__file__).resolve(), ROOT/'bin/audit-core.py', ROOT/'bin/core-capabilities.json',
        ROOT/'src/main/resources/thc/scalar-primop-signatures.json', *sorted((ROOT/'bin').glob('core_*.py')),
        *sorted((ROOT/'src/compiler/THC').glob('*.hs')), *[ROOT / 'bin' / n for n in ('build-compiler.sh', 'export-core.sh', 'toolchain.sh')]]
    artifacts += [source, executable, BUILD/'oracle.tsv', BUILD/'expected.tsv']
    def hashes(paths): return {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(set(paths))}
    (BUILD/'manifest.json').write_text(json.dumps(dict(schema=1, ghc='9.14.1', wordBits=64,
        entries=ENTRIES, values=VALUES, stages=stages, audits=summaries, nativeRows=len(ENTRIES)*len(VALUES),
        allNativeValuesMatchIndependentModel=True, inputHashes=hashes(inputs), artifactHashes=hashes(artifacts), commands=commands,
        ghcInfo=run([ghc, '--info'], text=True, capture_output=True).stdout,
        limitations=['Managed immutable literal allocations only; no foreign pointer or raw address conversion.',
                    'This corpus covers managed literal fields and tuples, not native pointers or address sums.',
                    'Native reads stay within the original literal including its final NUL; invalid offsets are JVM-only controls.']), indent=2)+'\n')
    print(f'Prepared address fields: {len(ENTRIES)*len(VALUES)} native/model rows; 2 batched pre/post audits')

if __name__ == '__main__': main()
