#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Pinned GHC family proofs and saturated constructor-to-tag native/model checks."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parent.parent
BUILD = ROOT / 'build/data-to-tag'
SOURCE = ROOT / 'compiler/test-fixtures/DataToTagAudit.hs'
ENTRIES = ['smallCase', 'largeCase', 'lazyCase', 'pairCase', 'unliftedCase', 'forceCase', 'wrappedCase']
FRONTIERS = ['unknownFamily', 'closureFamily', 'newtypeFamily', 'wrongSmallFamily', 'barePrimitive']
VALUES = sorted(set(range(-18, 19)) | {-2**63, -2**63+1, 2**63-2, 2**63-1, -4097, 4097, -2**32, 2**32})

def model(name, x):
    remainder = x % 3 if x >= 0 else -(abs(x) % 3)
    tag = {'smallCase': 0 if remainder == 0 else 1 if remainder == 1 else 2,
           'wrappedCase': 0 if remainder == 0 else 1 if remainder == 1 else 2,
           'largeCase': (x & 15) % 9, 'lazyCase': 1, 'pairCase': 0,
           'unliftedCase': x & 1, 'forceCase': 0}[name]
    return ((x + tag + 1 + 2**63) % 2**64) - 2**63

def walk(value):
    yield value
    for child in value.values() if isinstance(value, dict) else value if isinstance(value, list) else []:
        yield from walk(child)

def main():
    BUILD.mkdir(parents=True, exist_ok=True)
    (BUILD / 'manifest.json').unlink(missing_ok=True)
    ghc = os.environ.get('GHC', 'ghc')
    commands = []
    def run(command, env=None, **kw):
        command = list(map(str, command)); commands.append(dict(argv=command, environment=env or {}))
        return subprocess.run(command, cwd=ROOT, env=dict(os.environ, **(env or {})), check=True, **kw)
    assert run([ghc, '--numeric-version'], text=True, capture_output=True).stdout.strip() == '9.14.1'
    query = r'''mapM_ (\op -> let (_,_,_,arity,_) = GHC.Builtin.PrimOps.primOpSig op; name = GHC.Types.Name.Occurrence.occNameString (GHC.Builtin.PrimOps.primOpOcc op) in if name `elem` ["dataToTagSmall#","dataToTagLarge#"] then putStrLn (name ++ "\t" ++ show arity ++ "\t" ++ unwords (words (GHC.Utils.Outputable.showSDocUnsafe (GHC.Utils.Outputable.ppr (GHC.Builtin.PrimOps.primOpType op))))) else pure ()) GHC.Builtin.PrimOps.allThePrimOps'''
    signatures = [line.split('\t', 2) for line in run([ghc, '-ignore-dot-ghci', '-package', 'ghc', '-e', query], text=True, capture_output=True).stdout.splitlines()]
    assert sorted((name, arity) for name, arity, _ in signatures) == [('dataToTagLarge#', '1'), ('dataToTagSmall#', '1')]
    spec=importlib.util.spec_from_file_location('data_tag_audit',ROOT/'scripts/audit-core.py')
    audit=importlib.util.module_from_spec(spec);spec.loader.exec_module(audit)
    cap=json.loads((ROOT/'scripts/core-capabilities.json').read_text())
    artifacts=[]; stages={}; summaries={}; inventories={}
    for stage in ('pre','post'):
        out=BUILD/stage
        run([ROOT/'compiler/export.sh', *(['-fplugin-opt=THC.Plugin:post-tidy'] if stage=='post' else []), SOURCE],
            env=dict(THC_CORE_OUT=str(out/'core'), THC_GHC_OUT=str(out/'ghc')))
        paths=sorted((out/'core').glob('*.json'))
        modules=[(str(p.relative_to(ROOT)),json.loads(p.read_text())) for p in paths]
        artifacts+=paths;stages[stage]=[p for p,_ in modules]
        module=next(m for _,m in modules if m['module']=='DataToTagAudit')
        assert module['boundary']==('optimized-Core-before-Tidy' if stage=='pre' else 'optimized-Core-after-Tidy-before-CorePrep')
        uses=[e for e in walk(module['bindings']) if isinstance(e,list) and e[:1]==['app']
              and e[1][:1]==['prim'] and e[1][1] in ('dataToTagSmall#','dataToTagLarge#')]
        described=[e for e in uses if 'dataToTagFamily' in e[-1]]
        shapes=sorted((e[1][1],len(e[-1]['dataToTagFamily']['constructors']),e[-1]['dataToTagFamily']['smallFamily']) for e in described)
        assert shapes==[('dataToTagLarge#',9,False),('dataToTagSmall#',1,True),('dataToTagSmall#',2,True),
                        ('dataToTagSmall#',3,True),('dataToTagSmall#',3,True),('dataToTagSmall#',9,False)], shapes
        assert len(uses)-len(described)==3, 'Unknown/newtype/closure must lack family proof'
        assert all(e[-1]['dataToTagFamily']['smallFamilyLimit']==7 for e in described)
        inventories[stage]=dict(primitiveApplications=len(uses), concreteFamilies=shapes, missingFamilies=3)
        for name in ENTRIES+FRONTIERS:
            report=audit.Audit(modules,cap).run([name])
            path=out/(name+'.audit.json');path.write_text(json.dumps(report,indent=2)+'\n');artifacts.append(path)
            assert not report['missingGlobals'], (stage,name,report['missingGlobals'])
            assert report['accepted']==(name in ENTRIES), (stage,name,report['issues'])
            if name in ENTRIES:
                assert any(p['name'].startswith('dataToTag') for p in report['primitives']), (stage,name,'primitive erased')
            summaries[stage+'/'+name]=dict(summary=report['summary'],issues=report['issues'])
    driver=['{-# LANGUAGE MagicHash #-}','module Main where','import GHC.Exts (Int(I#))',
            'import Control.Exception (SomeException, evaluate, try)','import qualified DataToTagAudit as P',
            'call name (I# x) = case name of']
    driver += ['  "'+name+'" -> I# (P.'+name+' x)' for name in ENTRIES]
    driver += ['  _ -> error "unknown entry"', 'emit [name,x] = do',
               '  r <- try (evaluate (call name (read x))) :: IO (Either SomeException Int)',
               '  putStrLn (name ++ "\\t" ++ x ++ "\\t" ++ either (const "THREW") show r)',
               'emit _ = error "invalid request"','main = getContents >>= mapM_ (emit . words) . lines']
    source=BUILD/'NativeDataToTag.hs';source.write_text('\n'.join(driver)+'\n')
    native=BUILD/'native';native.mkdir(exist_ok=True);executable=native/'data-to-tag-oracle'
    run([ghc,'--make','-O2','-fforce-recomp','-dcore-lint','-dstg-lint','-i'+str(SOURCE.parent),
         '-odir',native,'-hidir',native,source,'-o',executable])
    inputs=[(name,x) for name in ENTRIES for x in VALUES if name!='forceCase' or x>=0]
    requests=''.join(f'{name}\t{x}\n' for name,x in inputs)
    expected=''.join(f'{name}\t{x}\t{model(name,x)}\n' for name,x in inputs)
    result=run([executable],input=requests,text=True,capture_output=True,timeout=30)
    assert result.stdout==expected,'Native/model mismatch'
    (BUILD/'oracle.tsv').write_text(result.stdout);(BUILD/'expected.tsv').write_text(expected)
    # Bottom is defined Haskell behavior, unlike malformed raw primitive calls.
    failure=run([executable],input='forceCase\t-1\n',text=True,capture_output=True,timeout=10)
    assert failure.stdout=='forceCase\t-1\tTHREW\n',failure.stdout
    (BUILD/'exceptions.tsv').write_text(failure.stdout)
    artifacts += [source,executable,BUILD/'oracle.tsv',BUILD/'expected.tsv',BUILD/'exceptions.tsv']
    sources=[SOURCE,Path(__file__),ROOT/'scripts/audit-core.py',ROOT/'scripts/core-capabilities.json',
             ROOT/'src/main/resources/thc/scalar-primop-signatures.json',*sorted((ROOT/'scripts').glob('core_*.py')),
             *sorted((ROOT/'compiler/THC').glob('*.hs')),*[ROOT/'compiler'/n for n in ('build.sh','export.sh','toolchain.sh')]]
    def hashes(paths):return {str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(set(paths))}
    manifest=dict(schema=1,ghc='9.14.1',entries=ENTRIES,frontiers=FRONTIERS,stages=stages,audits=summaries,
        inventories=inventories,nativeRows=len(inputs),nativeExceptionRows=1,allNativeResultsMatchIndependentModel=True,
        inputHashes=hashes(sources),artifactHashes=hashes(artifacts),commands=commands,
        signatures={name:dict(valueArity=int(arity),ghcType=ty) for name,arity,ty in signatures},
        ghcInfo=run([ghc,'--info'],text=True,capture_output=True).stdout,
        primarySource='ghc-9.14.1-release/compiler/GHC/Tc/Instance/Class.hs: Note [DataToTag overview], DTT1-3, DTW4-7',
        limitations=['Only saturated concrete algebraic families on pinned 64-bit targets.',
                    'Wrong-size, newtype, closure, unknown-family and bare-primitive frontiers are never run natively.',
                    'Descriptor consistency is checked; nominal Core provenance is not cryptographic authentication.'])
    (BUILD/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    print(f'Prepared dataToTag: {len(inputs)} native/model rows, one forced-bottom exception, 14 accepted + 10 rejected strict audits')

if __name__=='__main__': main()
