#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Fresh GHC values and strict pre/post proof for mutable byte fills/copies."""
import ast,hashlib,importlib.util,json,os,subprocess
from pathlib import Path
from mutable_bytearray_model import ENTRIES, REQUIRED, inputs, rows, verify
ROOT=Path(__file__).resolve().parent.parent
OUT=ROOT/'build/mutable-bytearrays'
SOURCE=ROOT/'compiler/test-fixtures/MutableByteArrayAudit.hs'
def main():
    OUT.mkdir(parents=True,exist_ok=True);(OUT/'manifest.json').unlink(missing_ok=True)
    ghc=os.environ.get('GHC','ghc');pkg=os.environ.get('GHC_PKG','ghc-pkg');commands=[]
    def run(command,env=None,**kw):
        args=list(map(str,command));commands.append(dict(argv=args,environment=env or {}))
        return subprocess.run(args,cwd=ROOT,env=dict(os.environ,**(env or {})),check=True,**kw)
    assert run([ghc,'--numeric-version'],text=True,capture_output=True).stdout.strip()=='9.14.1'
    info=run([ghc,'--info'],text=True,capture_output=True).stdout
    assert dict(ast.literal_eval(info))['target word size in bits']=='64'
    assert run([pkg,'field','bytestring','version','--simple-output'],text=True,capture_output=True).stdout.strip()=='0.12.2.0'
    run(['compiler/build.sh']);run(['python3','scripts/primop-coverage.py'])
    inventory=json.loads((ROOT/'build/primop-coverage.json').read_text())
    signatures=[p for p in inventory['primitives'] if p['name'] in set(REQUIRED.values())]
    assert {p['name']:p['valueArity'] for p in signatures}=={'setByteArray#':5,'copyMutableByteArray#':6,'copyMutableByteArrayNonOverlapping#':6}
    spec=importlib.util.spec_from_file_location('mutable_audit',ROOT/'scripts/audit-core.py');audit=importlib.util.module_from_spec(spec);spec.loader.exec_module(audit)
    caps=json.loads((ROOT/'scripts/core-capabilities.json').read_text());stages={};summaries={};artifacts=[]
    for stage in ('pre','post'):
        core=OUT/f'{stage}-core'
        run(['compiler/export.sh',*(['-fplugin-opt=THC.Plugin:post-tidy'] if stage=='post' else []),
             *['-fplugin-opt=THC.Plugin:closure='+n for n in ENTRIES],SOURCE],
            env=dict(THC_CORE_OUT=str(core),THC_GHC_OUT=str(OUT/f'{stage}-ghc'),THC_SOURCE_NOTES='true'))
        paths=sorted(core.glob('*.json'));modules=[(str(p.relative_to(ROOT)),json.loads(p.read_text())) for p in paths]
        stages[stage]=[p for p,_ in modules];artifacts+=paths
        fixture=dict(modules)[str((core/'MutableByteArrayAudit.json').relative_to(ROOT))]
        assert fixture['boundary']==('optimized-Core-before-Tidy' if stage=='pre' else 'optimized-Core-after-Tidy-before-CorePrep')
        for name in ENTRIES:
            report=audit.Audit(modules,caps).run([name]);path=OUT/f'{stage}-{name}.audit.json';path.write_text(json.dumps(report,indent=2)+'\n');artifacts.append(path)
            assert report['accepted'],(stage,name,report['issues'],report['missingGlobals'])
            counts={p['name']:len(p['uses']) for p in report['primitives']}
            assert REQUIRED[name] in counts,(stage,name,counts)
            if name=='publicReplicate':
                assert any(b['id'].endswith(':Data.ByteString.Short.Internal.empty') for b in report['reachableBindings'])
            summaries[stage+'/'+name]=dict(reachable=report['reachableBindings'],primitiveCounts=counts,issues=report['issues'],missing=report['missingGlobals'])
    native=OUT/'native';native.mkdir(exist_ok=True);driver=OUT/'NativeMutableByteArrays.hs'
    driver.write_text('\n'.join(['{-# LANGUAGE MagicHash #-}','module Main where','import GHC.Exts (Int(I#))','import qualified MutableByteArrayAudit as P',
        'call name (I# x) (I# code) = case name of',*['  "'+n+'" -> I# (P.'+n+' x code)' for n in ENTRIES],
        '  _ -> error "unknown mutable byte-array entry"','emit [name,x,code] = putStrLn (name ++ "\\t" ++ x ++ "\\t" ++ code ++ "\\t" ++ show (call name (read x) (read code)))',
        'emit _ = error "bad input"','main = getContents >>= mapM_ (emit . words) . lines'])+'\n')
    binary=native/'mutable-bytearrays-oracle'
    run([ghc,'--make','-O2','-fforce-recomp','-dcore-lint','-dstg-lint','-icompiler/test-fixtures','-odir',native,'-hidir',native,driver,'-o',binary])
    result=run([binary],input=''.join(f'{n}\t{x}\t{code}\n' for n,x,code,_ in rows()),text=True,capture_output=True,timeout=60)
    verify(result.stdout);(OUT/'oracle.tsv').write_text(result.stdout)
    sources=[SOURCE,Path(__file__).resolve(),ROOT/'scripts/mutable_bytearray_model.py',ROOT/'scripts/primop-coverage.py',ROOT/'scripts/audit-core.py',ROOT/'scripts/core-capabilities.json',ROOT/'src/main/resources/thc/scalar-primop-signatures.json',
             *sorted((ROOT/'scripts').glob('core_*.py')),*sorted((ROOT/'compiler/THC').glob('*.hs')),*[ROOT/'compiler'/n for n in ('build.sh','export.sh','toolchain.sh')]]
    artifacts += [driver,binary,OUT/'oracle.tsv']
    hashes=lambda ps:{str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(set(ps))}
    (OUT/'manifest.json').write_text(json.dumps(dict(schema=1,ghc='9.14.1',bytestring='0.12.2.0',wordBits=64,entries=ENTRIES,inputs=inputs(),stages=stages,audits=summaries,
        nativeRows=len(rows()),inputHashes=hashes(sources),artifactHashes=hashes(artifacts),commands=commands,ghcInfo=info,
        installedBytestring=run([pkg,'describe','bytestring'],text=True,capture_output=True).stdout,primopSignatures=signatures,
        contractSource='ghc-9.14.1-release/compiler/GHC/Builtin/primops.txt.pp:2133-2155,2231-2238',
        loweringSource='ghc-9.14.1-release/compiler/GHC/StgToCmm/Prim.hs:2705-2728,2808-2819',
        limitations=['Native rows use only contained ranges and obey each overlap precondition; managed rejection controls are separate.',
                     'No resize, pinned storage, raw pointers, Addr#, concurrency or atomic operations.']),indent=2)+'\n')
    print(f'Mutable ByteArrays: {len(rows())} native/model rows, {len(inputs())} independent input pairs, 12 strict accepted audits')
if __name__=='__main__':main()
