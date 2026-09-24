#!/usr/bin/env python3
"""Fresh defined-domain GHC resize oracle and exact pre/post proof."""
import ast,hashlib,importlib.util,json,os,subprocess
from pathlib import Path
from resize_bytearray_model import ENTRIES, REQUIRED, inputs, rows, verify
ROOT=Path(__file__).resolve().parent.parent
OUT=ROOT/'build/resize-bytearrays'
SOURCE=ROOT/'compiler/test-fixtures/ResizeByteArrayAudit.hs'
def main():
    OUT.mkdir(parents=True,exist_ok=True);(OUT/'manifest.json').unlink(missing_ok=True)
    ghc=os.environ.get('GHC','ghc');pkg=os.environ.get('GHC_PKG','ghc-pkg');commands=[]
    def run(command,env=None,**kw):
        args=list(map(str,command));commands.append(dict(argv=args,environment=env or {}))
        return subprocess.run(args,cwd=ROOT,env=dict(os.environ,**(env or {})),check=True,**kw)
    assert run([ghc,'--numeric-version'],text=True,capture_output=True).stdout.strip()=='9.14.1'
    info=run([ghc,'--info'],text=True,capture_output=True).stdout
    assert dict(ast.literal_eval(info))['target word size in bits']=='64'
    run(['compiler/build.sh']);run(['python3','scripts/primop-coverage.py'])
    inventory=json.loads((ROOT/'build/primop-coverage.json').read_text())
    signatures=[p for p in inventory['primitives'] if p['name'] in set(REQUIRED.values())]
    assert {p['name']:p['valueArity'] for p in signatures}=={'resizeMutableByteArray#':3}
    spec=importlib.util.spec_from_file_location('resize_audit',ROOT/'scripts/audit-core.py');audit=importlib.util.module_from_spec(spec);spec.loader.exec_module(audit)
    caps=json.loads((ROOT/'scripts/core-capabilities.json').read_text());stages={};summaries={};artifacts=[]
    for stage in ('pre','post'):
        core=OUT/f'{stage}-core'
        run(['compiler/export.sh',*(['-fplugin-opt=Thc.Plugin:post-tidy'] if stage=='post' else []),
             *['-fplugin-opt=Thc.Plugin:closure='+n for n in ENTRIES],SOURCE],
            env=dict(THC_CORE_OUT=str(core),THC_GHC_OUT=str(OUT/f'{stage}-ghc'),THC_SOURCE_NOTES='true'))
        paths=sorted(core.glob('*.json'));modules=[(str(p.relative_to(ROOT)),json.loads(p.read_text())) for p in paths]
        stages[stage]=[p for p,_ in modules];artifacts+=paths
        fixture=dict(modules)[str((core/'ResizeByteArrayAudit.json').relative_to(ROOT))]
        assert fixture['boundary']==('optimized-Core-before-Tidy' if stage=='pre' else 'optimized-Core-after-Tidy-before-CorePrep')
        for name in ENTRIES:
            report=audit.Audit(modules,caps).run([name]);path=OUT/f'{stage}-{name}.audit.json';path.write_text(json.dumps(report,indent=2)+'\n');artifacts.append(path)
            assert report['accepted'],(stage,name,report['issues'],report['missingGlobals'])
            counts={p['name']:len(p['uses']) for p in report['primitives']}
            assert REQUIRED[name] in counts,(stage,name,counts)
            assert any(b['id'].endswith('.resizeWorker') for b in report['reachableBindings'])
            summaries[stage+'/'+name]=dict(reachable=report['reachableBindings'],primitiveCounts=counts,issues=report['issues'],missing=report['missingGlobals'])
    native=OUT/'native';native.mkdir(exist_ok=True);driver=OUT/'NativeResizeByteArrays.hs'
    driver.write_text('\n'.join(['{-# LANGUAGE MagicHash #-}','module Main where','import GHC.Exts (Int(I#))','import qualified ResizeByteArrayAudit as P',
        'call name (I# x) (I# code) = case name of',*['  "'+n+'" -> I# (P.'+n+' x code)' for n in ENTRIES],
        '  _ -> error "unknown mutable byte-array entry"','emit [name,x,code] = putStrLn (name ++ "\\t" ++ x ++ "\\t" ++ code ++ "\\t" ++ show (call name (read x) (read code)))',
        'emit _ = error "bad input"','main = getContents >>= mapM_ (emit . words) . lines'])+'\n')
    binary=native/'resize-bytearrays-oracle'
    run([ghc,'--make','-O2','-fforce-recomp','-dcore-lint','-dstg-lint','-icompiler/test-fixtures','-odir',native,'-hidir',native,driver,'-o',binary])
    result=run([binary],input=''.join(f'{n}\t{x}\t{code}\n' for n,x,code,_ in rows()),text=True,capture_output=True,timeout=60)
    verify(result.stdout);(OUT/'oracle.tsv').write_text(result.stdout)
    sources=[SOURCE,Path(__file__).resolve(),ROOT/'scripts/resize_bytearray_model.py',ROOT/'scripts/primop-coverage.py',ROOT/'scripts/audit-core.py',ROOT/'scripts/core-capabilities.json',ROOT/'src/main/resources/thc/scalar-primop-signatures.json',
             *sorted((ROOT/'scripts').glob('core_*.py')),*sorted((ROOT/'compiler/Thc').glob('*.hs')),*[ROOT/'compiler'/n for n in ('build.sh','export.sh','toolchain.sh')]]
    artifacts += [driver,binary,OUT/'oracle.tsv']
    hashes=lambda ps:{str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(set(ps))}
    (OUT/'manifest.json').write_text(json.dumps(dict(schema=1,ghc='9.14.1',wordBits=64,entries=ENTRIES,inputs=inputs(),stages=stages,audits=summaries,
        nativeRows=len(rows()),inputHashes=hashes(sources),artifactHashes=hashes(artifacts),commands=commands,ghcInfo=info,
        primopSignatures=signatures,
        contractSource='ghc-9.14.1-release/compiler/GHC/Builtin/primops.txt.pp:2041-2060',
        limitations=['Native inputs use valid sizes, initialize grown bytes, and never access old references after resize.',
                     'Invalid sizes are managed-only rejection controls. No shrinkMutableByteArray#, public Text, pinned or foreign storage support.']),indent=2)+'\n')
    print(f'Resize ByteArrays: {len(rows())} native/model rows, {len(inputs())} independent input pairs, 4 strict accepted audits')
if __name__=='__main__':main()
