#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Fresh native GHC evidence for the bounded typed sum result/case slice."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'build/sum-result'
INPUTS = [-(1 << 63), -4097, -7, -3, -1, 0, 1, 7, 4097, (1 << 63)-1]
ENTRIES = ['forwardCase', 'outstandingCase', 'pairedCase', 'lazyLeafCase', 'mixedCase',
           'singletonBoxCase', 'selfCase', 'mutualCase', 'effectStateCase', 'effectEmptyCase', 'throwCase']
PAIRS = [(-(1 << 63),(1 << 63)-1),((1 << 63)-1,-(1 << 63)),(-7,11),(11,-7),(0,0),(17,17),(3000000000,-3000000000)]

def signed(n): return ((n + (1 << 63)) % (1 << 64)) - (1 << 63)
def produced(x): return x if x < 0 else signed(x+1)
def pair_model(x,y): return signed(3*x+7*y) if x < y else signed(11*y+13*signed(x+1))
def model(name,x):
    if name == 'forwardCase':
        y=signed(x+1)
        return signed(y-3 if y < 0 else y+8)
    if name == 'outstandingCase':
        a,b = signed(x+1),signed(x+4)
        return signed((3 if a < 0 else 5)*produced(a)+(7 if b < 0 else 11)*produced(b))
    if name == 'pairedCase': return pair_model(x,signed(x+2))
    if name == 'lazyLeafCase': return 17 if x < 0 else signed(x+9)
    if name == 'mixedCase': return signed(x+(2 if x < 0 else 3))
    if name == 'singletonBoxCase': return 29 if x < 0 else signed(x+31)
    if name == 'selfCase': return produced(signed(x+5))
    if name == 'mutualCase': return produced(signed(x+4))
    if name == 'throwCase': return 'throws' if x < 0 else produced(x)
    if name == 'effectStateCase': return 'throws' if x < 0 else signed(x+21)
    if name == 'effectEmptyCase': return 'throws' if x < 0 else signed(x+23)
    raise ValueError(name)

def record(path):
    path=Path(path)
    return dict(path=str(path.relative_to(ROOT)) if path.is_relative_to(ROOT) else str(path),
                sha256=hashlib.sha256(path.read_bytes()).hexdigest())
def require(condition,message):
    if not condition: raise AssertionError(message)
def walk(value):
    yield value
    for child in value.values() if isinstance(value,dict) else value if isinstance(value,list) else []:
        yield from walk(child)
def main():
    parser=argparse.ArgumentParser(description=__doc__); parser.add_argument('--check-existing',action='store_true'); args=parser.parse_args()
    if args.check_existing:
        provenance=json.loads((OUT/'provenance.json').read_text())
        for item in provenance['sources']+provenance['artifacts']+[provenance['toolchain']['ghc'],provenance['toolchain']['ghcPkg']]:
            require(record(ROOT/item['path'])==item,'Stale sum result input: '+item['path'])
        print('Sum result evidence hashes verified'); return
    OUT.mkdir(parents=True,exist_ok=True); (OUT/'provenance.json').unlink(missing_ok=True)
    ghc=os.environ.get('GHC','ghc'); pkg=os.environ.get('GHC_PKG','ghc-pkg'); commands=[]
    def run(argv,extra=None):
        commands.append(dict(argv=list(map(str,argv)),environment=extra or {}))
        process=subprocess.run(argv,cwd=ROOT,env=dict(os.environ,**(extra or {})),text=True,capture_output=True)
        require(process.returncode==0, f'Command failed: {argv}\n{process.stdout}\n{process.stderr}')
        return process.stdout
    require(run([ghc,'--numeric-version']).strip()=='9.14.1','Requires pinned GHC9.14.1')
    run(['compiler/build.sh'])
    spec=importlib.util.spec_from_file_location('sum_result_audit',ROOT/'scripts/audit-core.py')
    audit=importlib.util.module_from_spec(spec); spec.loader.exec_module(audit)
    capabilities=json.loads((ROOT/'scripts/core-capabilities.json').read_text())
    inventories=[]
    for stage in ('pre','post'):
        flags=['-fplugin-opt=THC.Plugin:post-tidy'] if stage=='post' else []
        run(['compiler/export.sh',*flags,'compiler/test-fixtures/SumResultAudit.hs'],
            dict(THC_CORE_OUT=str(OUT/f'{stage}-core'),THC_GHC_OUT=str(OUT/f'{stage}-ghc'),THC_SOURCE_NOTES='true'))
        path=OUT/f'{stage}-core/SumResultAudit.json'; module=json.loads(path.read_text())
        require(module['ghc']=='9.14.1' and module['schema']==1 and module['boundary']==
                ('optimized-Core-before-Tidy' if stage=='pre' else 'optimized-Core-after-Tidy-before-CorePrep'),'Wrong export boundary')
        bindings={b['name']:b for b in module['bindings']}
        producers=['produce','forward','paired','lazyLeaf','mixed','selfSum','mutualA','mutualB','effectState','effectEmpty','singletonBox','throwSum']
        shapes={name:bindings[name]['expr'][3]['resultRep'] for name in producers}
        require(all(p.get('aggregate')=='unboxed-sum' and len(p['alternatives'])==2 for p in shapes.values()),'Real sum producers disappeared')
        singleton=shapes['singletonBox']['alternatives'][0]
        require(singleton.get('aggregate')=='unboxed-tuple' and len(singleton['components'])==1 and
                singleton['primReps']==['BoxedRep (Just Lifted)'] and singleton['components'][0]['evaluated'] is False,
                'Singleton tuple must preserve its unlifted boundary and lazy reference leaf')
        paired=shapes['paired']
        require(paired['primReps']==['WordRep','WordRep','WordRep'] and paired['alternativeSlots']==[[1,2],[1,2]],'Pair payload projection changed')
        # A genuine recursive bottom remains reachable as a lazy alternative; no Core rewriting.
        require(any(b['expr'][0]=='var' and b['expr'][1]==b['id'] for b in module['bindings']), 'Lazy bottom control disappeared')
        report=audit.Audit([(str(path),module)],capabilities).run(ENTRIES+['pairedInputs'])
        require(report['accepted'],f'{stage}: strict sum result audit failed: {report}')
        (OUT/f'{stage}-audit.json').write_text(json.dumps(report,indent=2)+'\n')
        inventories.append(dict(stage=stage,producerShapes=shapes,strictRoots=ENTRIES+['pairedInputs']))
    native=OUT/'native'; native.mkdir(exist_ok=True); binary=native/'oracle'
    run([ghc,'--make','-O2','-fforce-recomp','-dcore-lint','-dstg-lint','-icompiler/test-fixtures',
         '-odir',str(native),'-hidir',str(native),'compiler/test-fixtures/SumResultAuditNative.hs','-o',str(binary)])
    oracle=run([str(binary)]); paired=run([str(binary),'--pairs'])
    require(oracle==''.join(f'{n}\t{x}\t{model(n,x)}\n' for n in ENTRIES for x in INPUTS),'Native unary result differs from independent wrap model')
    require(paired==''.join(f'pairedInputs\t{x}\t{y}\t{pair_model(x,y)}\n' for x,y in PAIRS),'Native pair result differs from independent wrap model')
    (OUT/'oracle.tsv').write_text(oracle); (OUT/'oracle-pairs.tsv').write_text(paired)
    (OUT/'checks.json').write_text(json.dumps(dict(nativeRows=110,independentPairRows=7,coverage=inventories),indent=2)+'\n')
    sources=[ROOT/'compiler/test-fixtures/SumResultAudit.hs',ROOT/'compiler/test-fixtures/SumResultAuditNative.hs',Path(__file__).resolve(),
             ROOT/'scripts/audit-core.py',ROOT/'scripts/core-capabilities.json',ROOT/'src/main/resources/thc/scalar-primop-signatures.json',
             ROOT/'scripts/generate-scalar-signatures.py',*sorted((ROOT/'scripts').glob('core_*.py')),*sorted((ROOT/'compiler/THC').glob('*.hs')),
             *[ROOT/'compiler'/name for name in ('build.sh','export.sh','toolchain.sh')],
             ROOT/'thc.cabal', ROOT/'cabal.project']
    plugin_manifest=ROOT/'build/compiler/plugin.json'
    plugin=json.loads(plugin_manifest.read_text())
    require(plugin['schema']==1 and plugin['unitId'] and plugin['sharedLibrary'], 'Invalid plugin manifest')
    artifacts=[p for p in sorted(OUT.rglob('*')) if p.is_file() and p.name!='provenance.json']
    artifacts += [plugin_manifest, Path(plugin['sharedLibrary'])]
    provenance=dict(schema=1,nativeRows=110,independentPairRows=7,sources=[record(p) for p in sources],artifacts=[record(p) for p in artifacts],commands=commands,
        toolchain=dict(ghc=record(Path(shutil.which(ghc) or ghc).resolve()),ghcPkg=record(Path(shutil.which(pkg) or pkg).resolve()),
            info=run([ghc,'--info']),packages={p:run([pkg,'describe',p]) for p in ('ghc','base','ghc-internal','ghc-prim')}))
    (OUT/'provenance.json').write_text(json.dumps(provenance,indent=2)+'\n')
    print('Prepared 110 native sum rows, 7 independent pairs and both strict Core stages')
if __name__=='__main__': main()
