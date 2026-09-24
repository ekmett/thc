#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

import hashlib,json,pathlib,subprocess,sys
out,export_root=map(pathlib.Path,sys.argv[1:])
here=pathlib.Path(__file__).resolve().parent
module=json.loads((out/'core/TupleRuntimeGraph.json').read_text())
assert module['ghc']=='9.14.1'
bindings={b['name']:b for b in module['bindings']}
expected={'pairCase':'pair','forwardedCase':'forward','outstandingCase':'forward',
          'mixedCase':'mixedForward','lazyMixedCase':'lazyMixed'}
def values(x):
    yield x
    if isinstance(x,dict):
        for v in x.values(): yield from values(v)
    elif isinstance(x,list):
        for v in x: yield from values(v)
for name,producer in expected.items():
    b=bindings[name]
    assert b['arity']==2 and b['expr'][0]=='lam',name
    assert 'main:TupleRuntimeGraph.'+producer in list(values(b['expr'])),(name,producer)
for name in ('pair','forward','mixed','mixedForward','lazyMixed'):
    b=bindings[name]
    proofs=[v for v in values(b['expr']) if isinstance(v,dict) and v.get('aggregate')=='unboxed-tuple']
    assert proofs and all('components' in p and p['components'] is not None for p in proofs),name
    result=b['expr'][-1]['resultRep']
    expected_reps=['IntRep','IntRep'] + ([] if name in ('pair','forward') else ['BoxedRep (Just Lifted)'])
    assert result['primReps']==expected_reps,(name,result)
    assert [c['primReps'] for c in result['components']]==[[r] for r in expected_reps],name
for name in ('mixed','lazyMixed'):
    assert 'main:TupleRuntimeGraph.bottomBox' in list(values(bindings[name]['expr'])),name
rows=[line.split('\t') for line in (out/'oracle.tsv').read_text().splitlines()]
assert len(rows)==55 and set(r[0] for r in rows)==set(expected)
assert all(len(r)==4 for r in rows)
files=[here/'TupleRuntimeGraph.hs',here/'TupleRuntimeGraphNative.hs',out/'core/TupleRuntimeGraph.json',out/'oracle.tsv',export_root/'compiler/Thc/Plugin.hs']
manifest={'ghc':module['ghc'],'runtimeProof':False,'nativeRows':len(rows),
          'sha256':{str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in files},
          'exporterPluginLibrarySha256':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted((export_root/'build/compiler').glob('libHSthc-core-plugin-*'))},
          'exporterCheckoutHeadAtInspection':subprocess.check_output(['git','-C',str(export_root),'rev-parse','HEAD'],text=True).strip()}
(out/'fixture-evidence.json').write_text(json.dumps(manifest,indent=2)+'\n')
print('PASS real opaque tuple fixture: 55 native rows; retained calls and tuple component metadata')
