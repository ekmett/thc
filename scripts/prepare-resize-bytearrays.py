#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Fresh defined-domain GHC resize oracle and exact pre/post proof."""
import ast,hashlib,importlib.util,json,os,subprocess
from pathlib import Path
ENTRIES=('resizedBytes','resizedTwiceWrites')
REQUIRED={name:'resizeMutableByteArray#' for name in ENTRIES}
ROOT=Path(__file__).resolve().parent.parent
OUT=ROOT/'build/resize-bytearrays'
SOURCE=ROOT/'compiler/test-fixtures/ResizeByteArrayAudit.hs'
DRIVER=ROOT/'compiler/test-fixtures/ResizeByteArrayNative.hs'
INPUTS=ROOT/'compiler/test-fixtures/ByteArrayFixtureInputs.hs'
def require(condition, detail):
    if not condition: raise RuntimeError(detail)
def main():
    OUT.mkdir(parents=True,exist_ok=True);(OUT/'manifest.json').unlink(missing_ok=True)
    ghc=os.environ.get('GHC','ghc');pkg=os.environ.get('GHC_PKG','ghc-pkg');commands=[]
    def run(command,env=None,**kw):
        args=list(map(str,command));commands.append(dict(argv=args,environment=env or {}))
        return subprocess.run(args,cwd=ROOT,env=dict(os.environ,**(env or {})),check=True,**kw)
    require(run([ghc,'--numeric-version'],text=True,capture_output=True).stdout.strip()=='9.14.1','Requires GHC 9.14.1')
    info=run([ghc,'--info'],text=True,capture_output=True).stdout
    require(dict(ast.literal_eval(info))['target word size in bits']=='64','Requires 64-bit Int')
    run(['compiler/build.sh']);run(['python3','scripts/primop-coverage.py'])
    inventory=json.loads((ROOT/'build/primop-coverage.json').read_text())
    signatures=[p for p in inventory['primitives'] if p['name'] in set(REQUIRED.values())]
    require({p['name']:p['valueArity'] for p in signatures}=={'resizeMutableByteArray#':3},'Resize signature mismatch')
    spec=importlib.util.spec_from_file_location('resize_audit',ROOT/'scripts/audit-core.py');audit=importlib.util.module_from_spec(spec);spec.loader.exec_module(audit)
    caps=json.loads((ROOT/'scripts/core-capabilities.json').read_text());stages={};summaries={};artifacts=[]
    for stage in ('pre','post'):
        core=OUT/f'{stage}-core'
        run(['compiler/export.sh',*(['-fplugin-opt=THC.Plugin:post-tidy'] if stage=='post' else []),
             *['-fplugin-opt=THC.Plugin:closure='+n for n in ENTRIES],SOURCE],
            env=dict(THC_CORE_OUT=str(core),THC_GHC_OUT=str(OUT/f'{stage}-ghc'),THC_SOURCE_NOTES='true'))
        paths=sorted(core.glob('*.json'));modules=[(str(p.relative_to(ROOT)),json.loads(p.read_text())) for p in paths]
        stages[stage]=[p for p,_ in modules];artifacts+=paths
        fixture=dict(modules)[str((core/'ResizeByteArrayAudit.json').relative_to(ROOT))]
        require(fixture['boundary']==('optimized-Core-before-Tidy' if stage=='pre' else 'optimized-Core-after-Tidy-before-CorePrep'),'Wrong Core boundary')
        for name in ENTRIES:
            report=audit.Audit(modules,caps).run([name]);path=OUT/f'{stage}-{name}.audit.json';path.write_text(json.dumps(report,indent=2)+'\n');artifacts.append(path)
            require(report['accepted'],(stage,name,report['issues'],report['missingGlobals']))
            counts={p['name']:len(p['uses']) for p in report['primitives']}
            require(REQUIRED[name] in counts,(stage,name,counts))
            require(any(b['id'].endswith('.resizeWorker') for b in report['reachableBindings']),(stage,name,'Missing opaque worker'))
            summaries[stage+'/'+name]=dict(reachable=report['reachableBindings'],primitiveCounts=counts,issues=report['issues'],missing=report['missingGlobals'])
    native=OUT/'native';native.mkdir(exist_ok=True)
    binary=native/'resize-bytearrays-oracle'
    run([ghc,'--make','-O2','-fforce-recomp','-dcore-lint','-dstg-lint','-icompiler/test-fixtures','-odir',native,'-hidir',native,'-main-is','ResizeByteArrayNative.main',DRIVER,'-o',binary])
    pairs=[list(map(int,line.split('\t'))) for line in run([binary,'--inputs'],text=True,capture_output=True,timeout=60).stdout.splitlines()]
    require(pairs and all(len(pair)==2 for pair in pairs),'Malformed native input inventory')
    result=run([binary],text=True,capture_output=True,timeout=60)
    rows=[line.split('\t') for line in result.stdout.splitlines()]
    require(all(len(row)==4 for row in rows),'Malformed native rows')
    require([(row[0],int(row[1]),int(row[2])) for row in rows]==[(name,x,code) for name in ENTRIES for x,code in pairs],'Native row inventory mismatch')
    (OUT/'oracle.tsv').write_text(result.stdout)
    sources=[SOURCE,DRIVER,INPUTS,Path(__file__).resolve(),ROOT/'scripts/primop-coverage.py',ROOT/'scripts/audit-core.py',ROOT/'scripts/core-capabilities.json',ROOT/'src/main/resources/thc/scalar-primop-signatures.json',
             *sorted((ROOT/'scripts').glob('core_*.py')),*sorted((ROOT/'compiler/THC').glob('*.hs')),*[ROOT/'compiler'/n for n in ('build.sh','export.sh','toolchain.sh')]]
    artifacts += [binary,OUT/'oracle.tsv']
    hashes=lambda ps:{str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(set(ps))}
    (OUT/'manifest.json').write_text(json.dumps(dict(schema=1,ghc='9.14.1',wordBits=64,entries=ENTRIES,inputs=pairs,stages=stages,audits=summaries,
        nativeRows=len(rows),inputHashes=hashes(sources),artifactHashes=hashes(artifacts),commands=commands,ghcInfo=info,
        modelValidation='ResizeByteArrayTest: independent Kotlin model and complete ordered corpus',
        primopSignatures=signatures,
        contractSource='ghc-9.14.1-release/compiler/GHC/Builtin/primops.txt.pp:2041-2060',
        limitations=['Native inputs use valid sizes, initialize grown bytes, and never access old references after resize.',
                     'Invalid sizes are managed-only rejection controls. No shrinkMutableByteArray#, public Text, pinned or foreign storage support.']),indent=2)+'\n')
    print(f'Resize ByteArrays: {len(rows)} native rows, {len(pairs)} input pairs, 4 strict audits; model checked by ResizeByteArrayTest')
if __name__=='__main__':main()
