#!/usr/bin/env python3
"""Fresh shallow Array# slice semantics with an explicit public-copy frontier."""
from pathlib import Path
import hashlib, importlib.util, json, os, subprocess
ROOT=Path(__file__).resolve().parent.parent
OUT=ROOT/'build/array-slices'
SOURCE=ROOT/'compiler/test-fixtures/ArraySliceAudit.hs'
ENTRIES=['sliceSnapshots','lazySlices','closureSlices','zeroSlices','publicSlices']
FRONTIERS=['publicFreezeThaw']
OPS={'cloneArray#','freezeArray#','thawArray#'}
def signed(n):return (n+(1<<63))%(1<<64)-(1<<63)
def inputs():return sorted(set(range(-16,17))|{signed(sign*((1<<bit)+delta)) for sign in (-1,1) for bit in range(64) for delta in (-1,0,1)})
def mathematical(name,x):
    # Lists below are independent snapshots, not the runtime's copying helper.
    if name=='sliceSnapshots':
        original=[None,x+1,x+2,x+3,x+4,None]
        frozen=list(original[1:5]);original[2]=x+20
        cloned=list(frozen[1:3]);mutable=list(cloned);mutable[0]=x+30
        snapshot=list(mutable);mutable[1]=x+40
        return signed(3*frozen[1]+5*cloned[0]+7*snapshot[0]+11*snapshot[1]+13*mutable[1]+17*original[2])
    return signed({'lazySlices':8*x+116,'closureSlices':2*x+1,'zeroSlices':x,'publicSlices':15*x+207,'publicFreezeThaw':15*x+184}[name])
def rows():return [(n,x,mathematical(n,x)) for n in ENTRIES+FRONTIERS for x in inputs()]
def verify(text):
    actual=[(n,int(x),int(y)) for n,x,y in (line.split('\t') for line in text.splitlines())]
    assert actual==rows(),'Native/model mismatch, missing, duplicate or reordered array-slice rows'
    return actual
def main():
    OUT.mkdir(parents=True,exist_ok=True);(OUT/'manifest.json').unlink(missing_ok=True)
    ghc=os.environ.get('GHC','ghc');pkg=os.environ.get('GHC_PKG','ghc-pkg');commands=[]
    def run(command,env=None,**kw):
        args=list(map(str,command));commands.append(dict(argv=args,environment=env or {}))
        return subprocess.run(args,cwd=ROOT,env=dict(os.environ,**(env or {})),check=True,**kw)
    assert run([ghc,'--numeric-version'],text=True,capture_output=True).stdout.strip()=='9.14.1'
    assert run([pkg,'field','array','version','--simple-output'],text=True,capture_output=True).stdout.strip()=='0.5.8.0'
    run(['compiler/build.sh'])
    run(['python3','scripts/primop-coverage.py'])
    inventory=json.loads((ROOT/'build/primop-coverage.json').read_text())
    signatures=[p for p in inventory['primitives'] if p['name'] in OPS]
    assert {p['name']:p['valueArity'] for p in signatures}=={'cloneArray#':3,'freezeArray#':4,'thawArray#':4}
    spec=importlib.util.spec_from_file_location('array_audit',ROOT/'scripts/audit-core.py');audit=importlib.util.module_from_spec(spec);spec.loader.exec_module(audit)
    caps=json.loads((ROOT/'scripts/core-capabilities.json').read_text());stages={};summaries={};artifacts=[]
    for stage in ('pre','post'):
        core=OUT/f'{stage}-core'
        run(['compiler/export.sh',*(['-fplugin-opt=Thc.Plugin:post-tidy'] if stage=='post' else []),
             *['-fplugin-opt=Thc.Plugin:closure='+n for n in ENTRIES+FRONTIERS],SOURCE],
            env=dict(THC_CORE_OUT=str(core),THC_GHC_OUT=str(OUT/f'{stage}-ghc'),THC_SOURCE_NOTES='true'))
        paths=sorted(core.glob('*.json'));modules=[(str(p.relative_to(ROOT)),json.loads(p.read_text())) for p in paths]
        stages[stage]=[p for p,_ in modules];artifacts+=paths
        fixture=dict(modules)[str((core/'ArraySliceAudit.json').relative_to(ROOT))]
        assert fixture['boundary']==('optimized-Core-before-Tidy' if stage=='pre' else 'optimized-Core-after-Tidy-before-CorePrep')
        for name in ENTRIES+FRONTIERS:
            report=audit.Audit(modules,caps).run([name]);path=OUT/f'{stage}-{name}.audit.json';path.write_text(json.dumps(report,indent=2)+'\n');artifacts.append(path)
            counts={p['name']:len(p['uses']) for p in report['primitives']}
            if name in ENTRIES:
                assert report['accepted'],(stage,name,report['issues'],report['missingGlobals'])
                assert OPS<=counts.keys(),(name,counts)
                reachable={b['id'] for b in report['reachableBindings']}
                if name in ('lazySlices','closureSlices','publicSlices','zeroSlices'):
                    assert any(b['id'] in reachable and b['expr'][:2]==['var',b['id']] for _,m in modules for b in m['bindings']), 'Lazy bottom vanished'
            else:
                assert not report['accepted'] and not report['issues']
                assert [m['id'] for m in report['missingGlobals']]==['ghc-internal:GHC.Internal.Arr.arrEleBottom']
            summaries[stage+'/'+name]=dict(reachable=len(report['reachableBindings']),missing=[m['id'] for m in report['missingGlobals']],issues=report['issues'],primitiveCounts=counts)
    native=OUT/'native';native.mkdir(exist_ok=True);driver=OUT/'NativeArraySlices.hs'
    driver.write_text('\n'.join(['{-# LANGUAGE MagicHash #-}','module Main where','import GHC.Exts (Int(I#))','import qualified ArraySliceAudit as P',
        'call name (I# x) = case name of',*['  "'+n+'" -> I# (P.'+n+' x)' for n in ENTRIES+FRONTIERS],
        '  _ -> error "unknown array-slice entry"','emit [name,x] = putStrLn (name ++ "\\t" ++ x ++ "\\t" ++ show (call name (read x)))',
        'emit _ = error "bad input"','main = getContents >>= mapM_ (emit . words) . lines'])+'\n')
    binary=native/'array-slices-oracle'
    run([ghc,'--make','-O2','-fforce-recomp','-dcore-lint','-dstg-lint','-icompiler/test-fixtures','-odir',native,'-hidir',native,driver,'-o',binary])
    result=run([binary],input=''.join(f'{n}\t{x}\n' for n,x,_ in rows()),text=True,capture_output=True,timeout=30)
    verify(result.stdout);(OUT/'oracle.tsv').write_text(result.stdout)
    sources=[SOURCE,Path(__file__).resolve(),ROOT/'scripts/primop-coverage.py',ROOT/'scripts/audit-core.py',ROOT/'scripts/core-capabilities.json',ROOT/'src/main/resources/thc/scalar-primop-signatures.json',
             *sorted((ROOT/'scripts').glob('core_*.py')),*sorted((ROOT/'compiler/Thc').glob('*.hs')),*[ROOT/'compiler'/n for n in ('build.sh','export.sh','toolchain.sh')]]
    artifacts += [driver,binary,OUT/'oracle.tsv']
    hashes=lambda ps:{str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(set(ps))}
    (OUT/'manifest.json').write_text(json.dumps(dict(schema=1,ghc='9.14.1',array='0.5.8.0',wordBits=64,entries=ENTRIES,frontiers=FRONTIERS,inputs=inputs(),stages=stages,audits=summaries,
        nativeRows=len(rows()),supportedNativeRows=len(ENTRIES)*len(inputs()),inputHashes=hashes(sources),artifactHashes=hashes(artifacts),commands=commands,
        ghcInfo=run([ghc,'--info'],text=True,capture_output=True).stdout,installedArray=run([pkg,'describe','array'],text=True,capture_output=True).stdout,
        primopSignatures=signatures,signatureSource='ghc-9.14.1-release/compiler/GHC/Builtin/primops.txt.pp:1649-1693',
        limitations=['No native undefined range inputs; managed invalid ranges are tested separately.','Only existing known-lifted element construction/read/index support; slice storage preserves references without entering them.',
                     'Public freeze/thaw retains the actual arrEleBottom error source frontier; only the five declared strict roots count as THC execution.','No copyArray#, copyMutableArray#, cloneMutableArray#, resizing, pinned/FFI, concurrency or atomic operations.']),indent=2)+'\n')
    print(f'Array slices: {len(rows())} native/model rows, {len(ENTRIES)*len(inputs())} strict-supported rows, 10 positive and 2 exact frontier audits')
if __name__=='__main__':main()
