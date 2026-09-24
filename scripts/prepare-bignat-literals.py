#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Fresh real Integer/Natural conversion and BigNat literal layout coverage."""
import argparse
import ast
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import os
import re
from collections import Counter
from pathlib import Path
import subprocess
import sys
from bignat_literal_model import ENTRIES, VALUES, SEEDS, requests, verify
ROOT=Path(__file__).resolve().parent.parent
OUT=ROOT/'build/bignat-literals'
STAGES={'pre':'optimized-Core-before-Tidy','post':'optimized-Core-after-Tidy-before-CorePrep'}
FRONTIERS=('integerAddFrontier','naturalAddFrontier')
MODULES=('BigNat','Integer','Natural')
WORKERS={'integerRoundTrip':'Integer.integerToInt#','integerLiteral':'Integer.integerToInt#',
         'naturalRoundTrip':'Natural.naturalToWord#','naturalLiteral':'Natural.naturalToWord#',
         **{name:'Integer.integerToBigNatSign#' for name in ENTRIES if name.startswith('magnitude')}}
def check(value,message):
    if not value: raise AssertionError(message)
def digest(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def record(path): return dict(path=str(path.relative_to(ROOT)),sha256=digest(path))
def audit_inputs():
    return [ROOT/'scripts/audit-core.py',ROOT/'scripts/core-capabilities.json',*sorted((ROOT/'scripts').glob('core_*.py')),
            ROOT/'src/main/resources/thc/scalar-primop-signatures.json',ROOT/'scripts/generate-scalar-signatures.py']
def nodes(value):
    if isinstance(value,list):
        yield value
        for child in value: yield from nodes(child)
    elif isinstance(value,dict):
        for child in value.values(): yield from nodes(child)
def inventory():
    spec=importlib.util.spec_from_file_location('audit_core',ROOT/'scripts/audit-core.py');audit=importlib.util.module_from_spec(spec);spec.loader.exec_module(audit)
    caps=json.loads((ROOT/'scripts/core-capabilities.json').read_text())
    original=[(OUT/f'boot/core/GHC.Internal.Bignum.{name}.json') for name in MODULES]
    modules=[json.loads(p.read_text()) for p in original]
    for name,module in zip(MODULES,modules):
        check(module['ghc']=='9.14.1' and module['boundary']==STAGES['post'] and module.get('sourceCore') and module.get('sourceSpans'), 'Complete original source evidence required: '+name)
    source_ids={b['id'] for m in modules for b in m['bindings']}
    stages={};coverage={}
    for stage,boundary in STAGES.items():
        public_path=OUT/f'{stage}-core/BigNatLiteralAudit.json';public=json.loads(public_path.read_text())
        closure=json.loads((OUT/f'{stage}-core/THC.InterfaceClosure.json').read_text())
        check(public['ghc']=='9.14.1' and public['boundary']==boundary,'Wrong public export boundary')
        check({b['id'] for b in closure['bindings']}<=source_ids,'Original source must replace the complete redundant interface closure')
        paths=[public_path,*original];stages[stage]=[str(p.relative_to(ROOT)) for p in paths]
        loaded=list(zip(map(str,paths),[public,*modules]));reports={}
        bindings={b['id']:b for _,m in loaded for b in m['bindings']}
        for entry in (*ENTRIES,*FRONTIERS):
            report=audit.Audit(loaded,caps).run(['main:BigNatLiteralAudit.'+entry])
            if entry in ENTRIES:
                check(report['accepted'],f'{stage}/{entry}: {report["summary"]} {report["issues"][:3]} {report["missingGlobals"][:3]}')
                ids={b['id'] for b in report['reachableBindings']}
                worker='ghc-internal:GHC.Internal.Bignum.'+WORKERS[entry]
                check(worker in ids,entry+': exact original worker disappeared')
                code=[n for identity in ids for n in nodes(bindings[identity]['expr'])]
                if entry in ('integerRoundTrip','naturalRoundTrip'):
                    identity='main:BigNatLiteralAudit.'+('integerIdentity' if entry.startswith('integer') else 'naturalIdentity')
                    constructor='ghc-internal:GHC.Internal.Bignum.'+('Integer.IS' if entry.startswith('integer') else 'Natural.NS')
                    check(any(n[:2]==['var',identity] for n in code),'Opaque roundtrip disappeared')
                    check(any(n[:2]==['con',constructor] for n in code),'Real small constructor disappeared')
                else:
                    choice='main:BigNatLiteralAudit.'+('naturalChoice' if entry.startswith('natural') else 'integerChoice')
                    check(choice in ids and any(n[:2]==['var',choice] for n in code),'Opaque literal choice disappeared')
                    wanted={'Natural.NB'} if entry.startswith('natural') else {'Integer.IP','Integer.IN'}
                    check(all(any(n[:2]==['con','ghc-internal:GHC.Internal.Bignum.'+c] for n in code) for c in wanted),'Signed/magnitude constructors disappeared')
                    literals=[n for n in code if len(n)>2 and n[:2]==['lit','bignat']]
                    check(literals,'Real BigNat literals disappeared')
                    for literal in literals: check(literal[3]['rep']==dict(kind='object',evaluated=True,primReps=['BoxedRep (Just Unlifted)']), 'BigNat exact intrinsic proof changed')
            else:
                # Keep the complete installed GMP arithmetic closure unsupported.
                check(not report['accepted'],'Arithmetic frontier unexpectedly accepted')
                # The strict foreign-call audit now reports each unsupported GMP
                # target as well as the unresolved worker global. Keep both
                # independent frontiers exact; accepting extra calls here would
                # make this negative control weaker.
                calls={'__gmpn_add':2,'__gmpn_add_1':1}
                if entry=='integerAddFrontier': calls.update(__gmpn_cmp=1,__gmpn_sub=1)
                wanted_issues=Counter({('unsupported-primitive','shrinkMutableByteArray#'):
                                       7 if entry=='integerAddFrontier' else 5})
                wanted_issues.update({('foreign-call',"Unsupported foreign target '"+symbol+"'"):count
                                      for symbol,count in calls.items()})
                check(Counter((issue['code'],issue['detail']) for issue in report['issues'])==wanted_issues,
                      'Changed exact arithmetic primitive/foreign-call frontier: '+str(report['summary']))
                check(len(report['missingGlobals'])==(6 if entry=='integerAddFrontier' else 3),
                      'Arithmetic missing-global frontier changed: '+str(report['summary']))
                symbols=[re.search(r'__ffi_static_ccall_unsafe ghc-internal:([^ ]+)', m['id']).group(1)
                         if '__ffi_static_ccall_unsafe ghc-internal:' in m['id'] else m['id'] for m in report['missingGlobals']]
                wanted=['__gmpn_add','__gmpn_add','__gmpn_add_1']
                if entry=='integerAddFrontier': wanted += ['__gmpn_cmp','__gmpn_sub','ghc-internal:GHC.Internal.Prim.Exception.raiseUnderflow']
                check(Counter(symbols)==Counter(wanted),'Missing installed GMP/exception identities changed')
            (OUT/f'{stage}-{entry}.audit.json').write_text(json.dumps(report,indent=2)+'\n')
            reports[entry]=dict(accepted=report['accepted'],reachable=len(report['reachableBindings']),issues=len(report['issues']),missing=len(report['missingGlobals']))
        missing=audit.Audit([(str(public_path),public),('installed-interface',closure)],caps).run(['main:BigNatLiteralAudit.'+e for e in ENTRIES])
        check(not missing['accepted'] and not missing['issues'] and len(missing['missingGlobals'])==3,'Original worker frontier changed')
        check({m['id'] for m in missing['missingGlobals']}=={'ghc-internal:GHC.Internal.Bignum.'+w for w in ('BigNat.bigNatZero','Integer.integerToInt#','Natural.naturalToWord#')},'Original worker identities changed')
        (OUT/f'{stage}-missing-source.audit.json').write_text(json.dumps(missing,indent=2)+'\n')
        coverage[stage]=reports
    return stages,coverage,{name:len(m['bindings']) for name,m in zip(MODULES,modules)}
def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--check-only',action='store_true');args=parser.parse_args()
    manifest_path=OUT/'manifest.json'
    if not args.check_only:
        ghc=os.environ.get('GHC','ghc');ghc_pkg=os.environ.get('GHC_PKG','ghc-pkg')
        check(subprocess.check_output([ghc,'--numeric-version'],text=True).strip()=='9.14.1','Pinned GHC9.14.1 required')
        info=subprocess.check_output([ghc,'--info'],text=True)
        check(dict(ast.literal_eval(info)).get('target word size')=='8','BigNat preparation requires the supported 64-bit GHC target')
        check(dict(ast.literal_eval(info)).get('target word big endian')==('YES' if sys.byteorder=='big' else 'NO'),'GHC target/native byte order mismatch')
        (OUT/'native').mkdir(parents=True,exist_ok=True);commands=[]
        def run(argv,extra=None):
            commands.append(dict(argv=argv,environment=extra or {}));subprocess.run(argv,cwd=ROOT,env=dict(os.environ,**(extra or {})),check=True)
        run(['compiler/build.sh'])
        run([sys.executable,'compiler/export-boot.py','--frontier','bignum','--build-dir',str(OUT/'boot')])
        for stage in STAGES:
            run(['compiler/export.sh',*(['-fplugin-opt=THC.Plugin:post-tidy'] if stage=='post' else []),
                 *['-fplugin-opt=THC.Plugin:closure='+e for e in (*ENTRIES,*FRONTIERS)],'compiler/test-fixtures/BigNatLiteralAudit.hs'],
                dict(THC_CORE_OUT=str(OUT/f'{stage}-core'),THC_GHC_OUT=str(OUT/f'{stage}-ghc'),THC_SOURCE_NOTES='true'))
        stages,coverage,counts=inventory()
        run([ghc,'--make','-O2','-fforce-recomp','-dcore-lint','-dstg-lint','-icompiler/test-fixtures','-odir',str(OUT/'native'),'-hidir',str(OUT/'native'),'-o',str(OUT/'native/bignat-literal-oracle'),'compiler/test-fixtures/BigNatLiteralAuditNative.hs'])
        text=''.join(f'{name}\t{x}\t{i}\n' for name,x,i in requests());(OUT/'requests.tsv').write_text(text)
        commands.append(dict(argv=[str(OUT/'native/bignat-literal-oracle')],stdin='build/bignat-literals/requests.tsv',stdout='build/bignat-literals/oracle.tsv'))
        output=subprocess.run([str(OUT/'native/bignat-literal-oracle')],input=text,text=True,capture_output=True,check=True).stdout
        (OUT/'oracle.tsv').write_text(output);rows=verify(output)
        boot=json.loads((OUT/'boot/boot-provenance.json').read_text())
        sources=[*sorted((ROOT/'compiler/test-fixtures').glob('BigNatLiteralAudit*.hs')),Path(__file__).resolve(),ROOT/'scripts/bignat_literal_model.py',
                 ROOT/'compiler/export-boot.py',ROOT/'compiler/build.sh',ROOT/'compiler/export.sh',ROOT/'compiler/toolchain.sh',
                 *sorted((ROOT/'compiler/THC').glob('*.hs')),*audit_inputs(),*[ROOT/r['path'] for r in boot['sources']]]
        artifacts=[OUT/'boot/boot-provenance.json',OUT/'requests.tsv',OUT/'oracle.tsv',*sorted(OUT.glob('*.audit.json'))]
        artifacts += [p for directory in ('pre-core','post-core','boot/core','native') for p in sorted((OUT/directory).rglob('*')) if p.is_file()]
        manifest_path.write_text(json.dumps(dict(schema=1,recordedAtUtc=datetime.now(timezone.utc).isoformat(),commands=commands,ghcInfo=info,
            wordBits=64,byteOrder=sys.byteorder,entries=list(ENTRIES),frontiers=list(FRONTIERS),values=list(map(str,VALUES)),seeds=list(SEEDS),nativeRows=len(rows),
            stages=stages,coverage=coverage,sourceBindings=counts,
            sources=[record(p) for p in dict.fromkeys(sources)],artifacts=[record(p) for p in artifacts],
            claim='Fresh native conversion/complete limb and byte observations; complete original BigNat/Integer/Natural source, no arithmetic or foreign substitution. Guest compiled execution is tested separately.'),indent=2)+'\n')
    manifest=json.loads(manifest_path.read_text())
    for item in manifest['sources']+manifest['artifacts']: check(record(ROOT/item['path'])==item,'Stale BigNat preparation: '+item['path'])
    check({str(p.relative_to(ROOT)) for p in audit_inputs()} <= {r['path'] for r in manifest['sources']},'Missing auditor fingerprint')
    rows=verify((OUT/'oracle.tsv').read_text());stages,coverage,counts=inventory()
    check(stages==manifest['stages'] and coverage==manifest['coverage'] and counts==manifest['sourceBindings'] and len(rows)==manifest['nativeRows'],'Stale BigNat inventory')
    print(f'BigNat: {len(rows)} native/model rows, 16 strict audits, 4 arithmetic and 2 missing-source frontiers; complete original sources')
if __name__=='__main__': main()
