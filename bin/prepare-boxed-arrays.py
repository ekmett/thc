#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

# Fixture rationale (097 boxed-arrays)
# Purpose: Public STArray laziness, read snapshots and captured closures, with
#   native GHC values checked against an independent wrapping arithmetic model.
# Produces/consumed result: Four named pre/post CBD roots, audits and native rows.
# Cost and overlap: Generic thunk retention belongs to ThunkRetentionTest;
#   these cases retain the distinct public boxed-array path. Floating is separate.
# Build status: Named products owned by fixture-boxed-arrays.
# Detailed file inputs/outputs: docs/fixture-inputs.log, entry 097.

"""Genuine lifted STArray storage; only the four declared positive roots."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
from core_package_manifest import inspect_cbd

ROOT = Path(__file__).resolve().parent.parent
BUILD = ROOT / 'build/boxed-arrays'
SOURCE = 't/fixtures/compiler/BoxedArrayAudit.hs'
ENTRIES = ['boxedSTRecursive', 'boxedZero', 'boxedSnapshot', 'boxedClosure']
VALUES = sorted(set(range(-16, 17)) | {-2**63, -2**63+1, 2**63-2, 2**63-1, -4097, 4097, -2**32, 2**32})

def signed(n): return (n + 2**63) % 2**64 - 2**63

def mathematical(name, x):
    return signed({'boxedSTRecursive': 44*x+350, 'boxedZero': x,
        'boxedSnapshot': 15*x+207, 'boxedClosure': 2*x+1}[name])

def main():
    BUILD.mkdir(parents=True, exist_ok=True)
    publication = ROOT/'build/compiler/plugin.json'
    if os.environ.get('THC_PLUGIN_MANIFEST') != str(publication):
        raise RuntimeError('Use fixture-boxed-arrays with its declared plugin publication')
    ghc, ghc_pkg = os.environ.get('GHC', 'ghc'), os.environ.get('GHC_PKG', 'ghc-pkg')
    commands = []
    def run(command, env=None, **kwargs):
        commands.append(dict(argv=list(map(str,command)), environment=env or {}))
        return subprocess.run(list(map(str,command)), cwd=ROOT, env=dict(os.environ, **(env or {})), check=True, **kwargs)
    assert run([ghc,'--numeric-version'],text=True,capture_output=True).stdout.strip() == '9.14.1'
    assert run([ghc_pkg,'field','array','version','--simple-output'],text=True,capture_output=True).stdout.strip() == '0.5.8.0'
    spec = importlib.util.spec_from_file_location('boxed_array_audit', ROOT/'bin/audit-core.py')
    audit = importlib.util.module_from_spec(spec); spec.loader.exec_module(audit)
    cap = json.loads((ROOT/'bin/core-capabilities.json').read_text())
    stages, summaries, artifacts = {}, {}, []
    for stage in ('pre','post'):
        directory = BUILD/stage; core = directory/'core'
        run([ROOT/'bin/export-core.sh', *(['-fplugin-opt=THC.Plugin:post-tidy'] if stage=='post' else []),
             *['-fplugin-opt=THC.Plugin:closure='+name for name in ENTRIES], SOURCE],
            env=dict(THC_CORE_OUT=str(core),THC_GHC_OUT=str(directory/'ghc')))
        paths = [core/(name+'.cbd') for name in ('BoxedArrayAudit', 'THC.InterfaceClosure')]; modules = [(str(p.relative_to(ROOT)),inspect_cbd(p.read_bytes())) for p in paths]
        boundary = 'optimized-Core-before-Tidy' if stage=='pre' else 'optimized-Core-after-Tidy-before-CorePrep'
        assert dict(modules)[str((core/'BoxedArrayAudit.cbd').relative_to(ROOT))]['boundary'] == boundary
        stages[stage] = [p for p,_ in modules]; artifacts += paths
        for name in ENTRIES:
            report = audit.Audit(modules,cap).run(['main:BoxedArrayAudit.'+name])
            path = directory/(name+'.audit.json'); path.write_text(json.dumps(report,indent=2)+'\n'); artifacts.append(path)
            counts = {p['name']: len(p['uses']) for p in report['primitives']}
            assert report['accepted'], (stage,name,report['issues'],report['missingGlobals'])
            summaries[stage+'/'+name] = dict(summary=report['summary'],primitiveCounts=counts,missingGlobals=[m['id'] for m in report['missingGlobals']])
    driver = ['{-# LANGUAGE MagicHash #-}','module Main where','import GHC.Exts (Int(I#))',
              'import qualified BoxedArrayAudit as P',
              'call name (I# x) (I# k) = case name of']
    driver += ['  "'+name+'" -> I# (P.'+name+' x'+''+')' for name in ENTRIES]
    driver += ['  _ -> error "unknown entry"',
               'emit [name,x,k] = putStrLn (name ++ "\\t" ++ x ++ "\\t" ++ k ++ "\\t" ++ show (call name (read x) (read k)))',
               'emit _ = error "invalid input"','main = getContents >>= mapM_ (emit . words) . lines']
    source=BUILD/'NativeBoxedArray.hs'; source.write_text('\n'.join(driver)+'\n')
    native=BUILD/'native'; native.mkdir(exist_ok=True); executable=native/'boxed-array-oracle'
    run([ghc,'--make','-O2','-fforce-recomp','-dcore-lint','-dstg-lint','-i'+str(ROOT/'t/fixtures/compiler'),
         '-odir',native,'-hidir',native,source,'-o',executable])
    requests=[]; expected=[]
    for name in ENTRIES:
        for x in VALUES:
            requests.append(f'{name}\t{x}\t0\n'); expected.append(f'{name}\t{x}\t0\t{mathematical(name,x)}\n')
    result=run([executable],input=''.join(requests),text=True,capture_output=True,timeout=30)
    assert result.stdout==''.join(expected),'Native/model mismatch'
    (BUILD/'oracle.tsv').write_text(result.stdout); (BUILD/'expected.tsv').write_text(''.join(expected))
    inputs=[ROOT/SOURCE,Path(__file__).resolve(),ROOT/'bin/audit-core.py',ROOT/'bin/core-capabilities.json',
            ROOT/'src/main/resources/thc/scalar-primop-signatures.json',*sorted((ROOT/'bin').glob('core_*.py')),
            publication,Path(json.loads(publication.read_text())['sharedLibrary']),
            *[ROOT / 'bin' / n for n in ('plugin.py', 'export-core.sh', 'toolchain.sh')]]
    artifacts += [source,executable,BUILD/'oracle.tsv',BUILD/'expected.tsv']
    def hashes(paths):return {str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(set(paths))}
    receipt = BUILD/'manifest.json.tmp'
    receipt.write_text(json.dumps(dict(schema=1,ghc='9.14.1',array='0.5.8.0',wordBits=64,entries=ENTRIES,
        values=VALUES,stages=stages,audits=summaries,nativeValueRows=len(expected),
        supportedNativeRows=len(ENTRIES)*len(VALUES),allNativeValuesMatchIndependentModel=True,inputHashes=hashes(inputs),artifactHashes=hashes(artifacts),commands=commands,
        installedArray=run([ghc_pkg,'describe','array'],text=True,capture_output=True).stdout,
        ghcInfo=run([ghc,'--info'],text=True,capture_output=True).stdout,
        signatureSource='ghc-9.14.1-release/compiler/GHC/Builtin/primops.txt.pp:1542-1611',
        limitations=['Only known lifted elements; no copy/thaw/small-array/atomic operations.',
                    'Invalid raw primop domains are checked by the Java semantic controls, not executed natively.']),indent=2)+'\n')
    os.replace(receipt, BUILD/'manifest.json')
    print(f'Prepared boxed arrays: {len(expected)} native/model rows; four roots, strict pre/post checks')
if __name__=='__main__':main()
