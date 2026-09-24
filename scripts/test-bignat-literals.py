#!/usr/bin/env python3
"""Fail-closed BigNat literal contracts and independent byte/limb observations."""
import copy
import importlib.util
import json
from pathlib import Path
import unittest
from bignat_literal_model import VALUES, ENTRIES, requests, expected, verify, size
ROOT=Path(__file__).resolve().parent.parent
spec=importlib.util.spec_from_file_location('audit_core',ROOT/'scripts/audit-core.py');audit=importlib.util.module_from_spec(spec);spec.loader.exec_module(audit)
CAP=json.loads((ROOT/'scripts/core-capabilities.json').read_text())
EXACT=dict(kind='object',primReps=['BoxedRep (Just Unlifted)'],evaluated=True)
def fixture(value='1',proof=EXACT,alternative=False):
    literal=['lit','bignat',value,*([dict(rep=copy.deepcopy(proof))] if proof is not None else [])]
    body=literal if not alternative else ['case',['lit','int','0'],'scrutinee',[
        ['lit',['bignat',value],[],['lit','int','1']],['default',None,[],['lit','int','0']]]]
    return dict(schema=1,ghc='9.14.1',constructors=[],bindings=[dict(id='root',name='root',arity=1,lifted=True,
        expr=['lam',[dict(id='x',lifted=False)],body])])
def report(module):return audit.Audit([('literal.json',module)],CAP).run(['root'])
class BigNatContracts(unittest.TestCase):
    def test_canonical_values_and_intrinsic_exact_proof(self):
        for value in ('0','1',str((1<<255)+(1<<128)+3)):
            for proof in (EXACT,None,dict(kind='unknown',primReps=None,evaluated=False)):
                result=report(fixture(value,proof));self.assertTrue(result['accepted'],result['issues'])
                self.assertEqual(EXACT,audit.Audit.expression_rep(fixture(value,proof)['bindings'][0]['expr'][2]))
    def test_noncanonical_or_negative_values(self):
        for value in ('','-1','+1','00','01',' 1','1 ','1.0','0x10','١'):
            result=report(fixture(value));self.assertIn('invalid-literal-value',{i['code'] for i in result['issues']},value)
    def test_forged_scalar_aggregate_and_vector_proofs(self):
        bad=[dict(kind=k,primReps=[r],evaluated=True) for k,r in [('long','IntRep'),('long','WordRep'),('object','BoxedRep (Just Lifted)'),('object','BoxedRep Nothing'),('unknown','BoxedRep (Just Unlifted)'),('data','BoxedRep (Just Unlifted)'),('closure','BoxedRep (Just Unlifted)')]]
        bad += [dict(kind='void',primReps=[],evaluated=True),dict(kind='unknown',primReps=[],evaluated=True,aggregate='unboxed-tuple',components=[]),
                dict(kind='unknown',primReps=['WordRep'],evaluated=True,aggregate='unboxed-sum',tagSlot=0,alternativeSlots=[[],[]],alternatives=[dict(kind='void',primReps=[],evaluated=True)]*2),
                dict(kind='vector',primReps=['VecRep 2 Int64ElemRep'],evaluated=True,vector=dict(lanes=2,element='Int64ElemRep'))]
        for proof in bad:self.assertFalse(report(fixture(proof=proof))['accepted'],proof)
    def test_direct_primitive_uses_the_intrinsic_literal_proof(self):
        positives=(EXACT,None,dict(kind='unknown',primReps=None,evaluated=False))
        negatives=[dict(EXACT,kind=k) for k in ('data','closure','unknown')]
        negatives += [dict(EXACT,primReps=[r]) for r in ('BoxedRep (Just Lifted)','BoxedRep Nothing','IntRep','WordRep')]
        for proof in (*positives,*negatives):
            module=fixture('18446744073709551616',proof)
            lam=module['bindings'][0]['expr'];literal=lam[2]
            lam[2]=['app',['prim','sizeofByteArray#'],[literal],[False],False,False,
                    dict(rep=dict(kind='long',primReps=['IntRep'],evaluated=True))]
            self.assertEqual(proof in positives,report(module)['accepted'],proof)
    def test_bignat_alternatives_remain_invalid_ghc_core(self):
        result=report(fixture(alternative=True));self.assertIn('alternative-kind',{i['code'] for i in result['issues']})
    def test_every_model_byte_reconstructs_the_magnitude(self):
        import sys
        for seed,value in enumerate(VALUES):
            n=abs(value);raw=bytes(expected('magnitudeByte',seed,i) for i in range(size(n)))
            reconstructed=sum(int.from_bytes(raw[i:i+8],sys.byteorder) << (8*i) for i in range(0,len(raw),8))
            self.assertEqual(n,reconstructed)
            self.assertEqual(-1,expected('magnitudeByte',seed,-1));self.assertEqual(-1,expected('magnitudeByte',seed,len(raw)))
        self.assertEqual(0,size(0));self.assertEqual(len(list(requests())),len(set(requests())))
    def test_prepared_genuine_core_and_native_model(self):
        manifest=ROOT/'build/bignat-literals/manifest.json'
        if not manifest.is_file():self.skipTest('Run prepare-bignat-literals.py')
        m=json.loads(manifest.read_text());self.assertEqual(len(verify((manifest.parent/'oracle.tsv').read_text())),m['nativeRows'])
        for stage in ('pre','post'):
            for name in ENTRIES:self.assertTrue(json.loads((manifest.parent/f'{stage}-{name}.audit.json').read_text())['accepted'])
            for name in ('integerAddFrontier','naturalAddFrontier'):self.assertFalse(json.loads((manifest.parent/f'{stage}-{name}.audit.json').read_text())['accepted'])
if __name__=='__main__':unittest.main()
