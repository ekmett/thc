#!/usr/bin/env python3
"""Genuine public lifted STArray storage; retain rejected error/index frontiers."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parent.parent
BUILD = ROOT / 'build/boxed-arrays'
SOURCE = 'compiler/test-fixtures/BoxedArrayAudit.hs'
ENTRIES = ['boxedSTRecursive', 'boxedZero', 'boxedSnapshot', 'boxedClosure']
FRONTIERS = ['boxedLiteral', 'boxedST', 'boxedLazyRead', 'boxedChecked']
OPS = {'newArray#', 'readArray#', 'writeArray#', 'unsafeFreezeArray#', 'indexArray#'}
VALUES = sorted(set(range(-16, 17)) | {-2**63, -2**63+1, 2**63-2, 2**63-1, -4097, 4097, -2**32, 2**32})

def signed(n): return (n + 2**63) % 2**64 - 2**63

def mathematical(name, x, index=0):
    return signed({'boxedSTRecursive': 44*x+350, 'boxedST': 44*x+350,
        'boxedLiteral': 44*x+77, 'boxedLazyRead': 3*(x+9), 'boxedZero': x,
        'boxedSnapshot': 15*x+207, 'boxedClosure': 2*x+1,
        'boxedChecked': [x, x+7, 2*x, x-11][index]}[name])

def main():
    BUILD.mkdir(parents=True, exist_ok=True)
    (BUILD/'manifest.json').unlink(missing_ok=True)
    ghc, ghc_pkg = os.environ.get('GHC', 'ghc'), os.environ.get('GHC_PKG', 'ghc-pkg')
    commands = []
    def run(command, env=None, **kwargs):
        commands.append(dict(argv=list(map(str,command)), environment=env or {}))
        return subprocess.run(list(map(str,command)), cwd=ROOT, env=dict(os.environ, **(env or {})), check=True, **kwargs)
    assert run([ghc,'--numeric-version'],text=True,capture_output=True).stdout.strip() == '9.14.1'
    assert run([ghc_pkg,'field','array','version','--simple-output'],text=True,capture_output=True).stdout.strip() == '0.5.8.0'
    spec = importlib.util.spec_from_file_location('boxed_array_audit', ROOT/'scripts/audit-core.py')
    audit = importlib.util.module_from_spec(spec); spec.loader.exec_module(audit)
    cap = json.loads((ROOT/'scripts/core-capabilities.json').read_text())
    stages, summaries, artifacts = {}, {}, []
    missing = {
        'boxedST': {'ghc-internal:GHC.Internal.CString.unpackCString#','ghc-internal:GHC.Internal.Err.error'},
        'boxedLazyRead': {'ghc-internal:GHC.Internal.CString.unpackCString#','ghc-internal:GHC.Internal.Err.error'},
        'boxedLiteral': {'ghc-internal:GHC.Internal.CString.unpackCString#','ghc-internal:GHC.Internal.Err.error','ghc-internal:GHC.Internal.Arr.arrEleBottom'},
        'boxedChecked': {'ghc-internal:GHC.Internal.CString.unpackCString#','ghc-internal:GHC.Internal.Arr.arrEleBottom','ghc-internal:GHC.Internal.Ix.$w$sindexError'}}
    for stage in ('pre','post'):
        directory = BUILD/stage; core = directory/'core'
        run([ROOT/'compiler/export.sh', *(['-fplugin-opt=THC.Plugin:post-tidy'] if stage=='post' else []),
             *['-fplugin-opt=THC.Plugin:closure='+name for name in ENTRIES+FRONTIERS], SOURCE],
            env=dict(THC_CORE_OUT=str(core),THC_GHC_OUT=str(directory/'ghc')))
        paths = sorted(core.glob('*.json')); modules = [(str(p.relative_to(ROOT)),json.loads(p.read_text())) for p in paths]
        boundary = 'optimized-Core-before-Tidy' if stage=='pre' else 'optimized-Core-after-Tidy-before-CorePrep'
        assert json.loads((core/'BoxedArrayAudit.json').read_text())['boundary'] == boundary
        stages[stage] = [p for p,_ in modules]; artifacts += paths
        for name in ENTRIES+FRONTIERS:
            report = audit.Audit(modules,cap).run([name])
            path = directory/(name+'.audit.json'); path.write_text(json.dumps(report,indent=2)+'\n'); artifacts.append(path)
            counts = {p['name']: len(p['uses']) for p in report['primitives']}
            if name in ENTRIES:
                assert report['accepted'], (stage,name,report['issues'],report['missingGlobals'])
                required = OPS if name in ('boxedSTRecursive','boxedSnapshot') else {'newArray#','unsafeFreezeArray#'}
                if name=='boxedClosure': required |= {'writeArray#','indexArray#'}
                assert required <= counts.keys(), (stage,name,counts)
            else:
                assert not report['accepted'] and not report['issues'], (stage,name,report['issues'])
                assert {m['id'] for m in report['missingGlobals']} == missing[name], (stage,name,report['missingGlobals'])
            if name=='boxedSTRecursive':
                assert {n:counts[n] for n in OPS} == {'newArray#':1,'readArray#':3,'writeArray#':3,'unsafeFreezeArray#':1,'indexArray#':3}
                reachable_ids = {b['id'] for b in report['reachableBindings']}
                assert any(b['id'] in reachable_ids and b['expr'][:2] == ['var',b['id']]
                           for _,module in modules for b in module['bindings']), 'Recursive bottom vanished'
            summaries[stage+'/'+name] = dict(summary=report['summary'],primitiveCounts=counts,missingGlobals=[m['id'] for m in report['missingGlobals']])
    driver = ['{-# LANGUAGE MagicHash #-}','module Main where','import GHC.Exts (Int(I#))',
              'import Control.Exception (SomeException, evaluate, try)','import qualified BoxedArrayAudit as P',
              'call name (I# x) (I# k) = case name of']
    driver += ['  "'+name+'" -> I# (P.'+name+' x'+(' k' if name=='boxedChecked' else '')+')' for name in ENTRIES+FRONTIERS]
    driver += ['  "bottomElement" -> P.bottomElement','  _ -> error "unknown entry"',
               'emit [name,x,k] = do','  r <- try (evaluate (call name (read x) (read k))) :: IO (Either SomeException Int)',
               '  putStrLn (name ++ "\\t" ++ x ++ "\\t" ++ k ++ "\\t" ++ either (const "THREW") show r)',
               'emit _ = error "invalid input"','main = getContents >>= mapM_ (emit . words) . lines']
    source=BUILD/'NativeBoxedArray.hs'; source.write_text('\n'.join(driver)+'\n')
    native=BUILD/'native'; native.mkdir(exist_ok=True); executable=native/'boxed-array-oracle'
    run([ghc,'--make','-O2','-fforce-recomp','-dcore-lint','-dstg-lint','-i'+str(ROOT/'compiler/test-fixtures'),
         '-odir',native,'-hidir',native,source,'-o',executable])
    requests=[]; expected=[]
    for name in ENTRIES+FRONTIERS:
        for x in VALUES:
            for k in (range(4) if name=='boxedChecked' else [0]):
                requests.append(f'{name}\t{x}\t{k}\n'); expected.append(f'{name}\t{x}\t{k}\t{mathematical(name,x,k)}\n')
    for name,x,k in [('boxedChecked',17,-1),('boxedChecked',17,4),('boxedChecked',17,-2**63),('boxedChecked',17,2**63-1),('bottomElement',0,0)]:
        requests.append(f'{name}\t{x}\t{k}\n'); expected.append(f'{name}\t{x}\t{k}\tTHREW\n')
    result=run([executable],input=''.join(requests),text=True,capture_output=True,timeout=30)
    assert result.stdout==''.join(expected),'Native/model or expected exception mismatch'
    (BUILD/'oracle.tsv').write_text(result.stdout); (BUILD/'expected.tsv').write_text(''.join(expected))
    inputs=[ROOT/SOURCE,Path(__file__).resolve(),ROOT/'scripts/audit-core.py',ROOT/'scripts/core-capabilities.json',
            ROOT/'src/main/resources/thc/scalar-primop-signatures.json',*sorted((ROOT/'scripts').glob('core_*.py')),
            *sorted((ROOT/'compiler/THC').glob('*.hs')),*[ROOT/'compiler'/n for n in ('build.sh','export.sh','toolchain.sh')]]
    artifacts += [source,executable,BUILD/'oracle.tsv',BUILD/'expected.tsv']
    def hashes(paths):return {str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(set(paths))}
    (BUILD/'manifest.json').write_text(json.dumps(dict(schema=1,ghc='9.14.1',array='0.5.8.0',wordBits=64,entries=ENTRIES,
        frontiers=FRONTIERS,values=VALUES,stages=stages,audits=summaries,nativeValueRows=len(expected)-5,expectedExceptionRows=5,
        supportedNativeRows=len(ENTRIES)*len(VALUES),allNativeValuesMatchIndependentModel=True,inputHashes=hashes(inputs),artifactHashes=hashes(artifacts),commands=commands,
        installedArray=run([ghc_pkg,'describe','array'],text=True,capture_output=True).stdout,
        ghcInfo=run([ghc,'--info'],text=True,capture_output=True).stdout,
        signatureSource='ghc-9.14.1-release/compiler/GHC/Builtin/primops.txt.pp:1542-1611',
        limitations=['Only known lifted elements; no copy/thaw/small-array/atomic operations.',
                    'Explicit error/checked-index roots retain their exact missing original source definitions and must remain strict-load failures.',
                    'Native invalid indices use checked public APIs; undefined raw primop inputs are not executed natively.']),indent=2)+'\n')
    print(f'Prepared boxed arrays: {len(ENTRIES)*len(VALUES)} supported native/model rows; {len(expected)-5} total value rows; 5 expected exceptions; strict pre/post checks')
if __name__=='__main__':main()
