#!/usr/bin/env python3
"""Vector logical-shape and boundary checks, independent of SIMD execution."""
import copy
import importlib.util
import json
from pathlib import Path
import unittest
from core_vectors import VECTOR_REP, LANE_REP, TUPLE_REP, VECTOR32_REP, LANE32_REP, VECTOR_FLOAT_REP, LANE_FLOAT_REP, TUPLE_FLOAT_REP, VECTOR_DOUBLE_REP, LANE_DOUBLE_REP, TUPLE_DOUBLE_REP, signature_matches
from core_vectors import VECTOR16_REP, LANE16_REP, TUPLE16_REP, OPERATIONS, proof_error
ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('audit_core', ROOT / 'audit-core.py')
audit = importlib.util.module_from_spec(spec); spec.loader.exec_module(audit)
CAP = json.loads((ROOT / 'core-capabilities.json').read_text())
CLOSURE = dict(kind='closure', primReps=['BoxedRep (Just Lifted)'], evaluated=True)
LONG = dict(kind='long', primReps=['IntRep'], evaluated=True)
def run(module, entry='root'): return audit.Audit([('simd', module)], CAP).run([entry])
def fixture():
    operand = ['lit', 'int64', '17', dict(rep=LANE_REP)]
    vector = ['app', ['prim', 'broadcastInt64X2#', dict(rep=CLOSURE)], [operand], [False], False, True, dict(rep=copy.deepcopy(VECTOR_REP))]
    body = ['case', vector, 'v', [['default', None, [], ['lit', 'int', '1', dict(rep=LONG)], dict(binders=[])]],
            dict(rep=LONG, binder=dict(id='v', lifted=False, rep=copy.deepcopy(VECTOR_REP)))]
    return dict(schema=1, ghc='9.14.1', bindings=[dict(id='root', name='root', lifted=True, arity=0,
        rep=CLOSURE, expr=['lam', [], body, dict(rep=CLOSURE,resultRep=LONG)])], constructors=[])
class VectorAuditTest(unittest.TestCase):
    def test_int16_exact_local_shape_and_signature_contracts(self):
        m=fixture(); body=m['bindings'][0]['expr'][2]
        body[1][1][1]='broadcastInt16X8#'
        body[1][2][0]=['lit','int16','-32768',dict(rep=copy.deepcopy(LANE16_REP))]
        body[1][6]['rep']=copy.deepcopy(VECTOR16_REP)
        body[4]['binder']['rep']=copy.deepcopy(VECTOR16_REP)
        self.assertTrue(run(m)['accepted'])
        for wrong in (dict(LANE16_REP,kind='unknown'), LANE32_REP, LONG,
                      dict(LANE16_REP,primReps=['Word16Rep'])):
            bad=copy.deepcopy(m); bad['bindings'][0]['expr'][2][1][2][0][3]['rep']=wrong
            self.assertFalse(run(bad)['accepted'],wrong)
            lanes=copy.deepcopy(TUPLE16_REP); lanes['components'][7]=wrong
            self.assertFalse(signature_matches(TUPLE16_REP,lanes),wrong)
        for wrong in (VECTOR_REP,VECTOR32_REP,TUPLE16_REP):
            bad=copy.deepcopy(m); bad['bindings'][0]['expr'][2][1][6]['rep']=copy.deepcopy(wrong)
            self.assertFalse(run(bad)['accepted'])
        self.assertEqual(OPERATIONS['packInt16X8#'],([TUPLE16_REP],VECTOR16_REP))
        self.assertEqual(OPERATIONS['unpackInt16X8#'],([VECTOR16_REP],TUPLE16_REP))
        for name,(args,result) in OPERATIONS.items():
            if 'Int16X8' in name:
                self.assertEqual(CAP['primitives'][name],len(args))
                self.assertTrue(signature_matches(result,result))
        for lanes,element in ((4,'Int16ElemRep'),(8,'Word16ElemRep')):
            bad=copy.deepcopy(VECTOR16_REP); bad['vector']=dict(lanes=lanes,element=element)
            bad['primReps']=[f'VecRep {lanes} {element}']
            self.assertIsNotNone(proof_error(bad))
    def test_double_local_shape_requires_exact_binary64_lanes(self):
        m=fixture(); body=m['bindings'][0]['expr'][2]
        body[1][1][1]='broadcastDoubleX2#'
        body[1][2][0]=['lit','double','1.0000000000000002',dict(rep=copy.deepcopy(LANE_DOUBLE_REP))]
        body[1][6]['rep']=copy.deepcopy(VECTOR_DOUBLE_REP)
        body[4]['binder']['rep']=copy.deepcopy(VECTOR_DOUBLE_REP)
        self.assertTrue(run(m)['accepted'])
        for kind in ('unknown', 'float', 'long'):
            bad=copy.deepcopy(m)
            bad['bindings'][0]['expr'][2][1][2][0]=['lit','float','1.0',dict(rep=dict(LANE_DOUBLE_REP,kind=kind))]
            self.assertFalse(run(bad)['accepted'],kind)
        for wrong in (VECTOR32_REP, VECTOR_REP, VECTOR_FLOAT_REP, TUPLE_DOUBLE_REP):
            bad=copy.deepcopy(m); bad['bindings'][0]['expr'][2][1][6]['rep']=copy.deepcopy(wrong)
            self.assertFalse(run(bad)['accepted'])
        self.assertTrue(signature_matches(TUPLE_DOUBLE_REP,TUPLE_DOUBLE_REP))
        for wrong in (dict(LANE_DOUBLE_REP,kind='unknown'), LANE_FLOAT_REP,
                      dict(kind='unknown',aggregate='unboxed-tuple',primReps=['DoubleRep'],components=[LANE_DOUBLE_REP])):
            bad=copy.deepcopy(TUPLE_DOUBLE_REP); bad['components'][0]=wrong
            self.assertFalse(signature_matches(TUPLE_DOUBLE_REP,bad))
        bad=copy.deepcopy(m); bad['bindings'][0]['expr'][2][1][2]=[]
        self.assertFalse(run(bad)['accepted'])
    def test_real_double_core_local_entries_and_formal_frontier(self):
        from doublex2_model import entries
        path=ROOT.parent/'build/simd-doublex2/pre-core/SimdDoubleX2.json'
        if not path.exists(): self.skipTest('Double SIMD Core export not generated')
        m=json.loads(path.read_text())
        for entry in entries(): self.assertTrue(run(m,entry['name'])['accepted'],entry['name'])
        self.assertFalse(run(m,'vectorArgument')['accepted'])
    def test_float_local_shape_requires_concrete_float_lanes(self):
        m=fixture(); body=m['bindings'][0]['expr'][2]
        body[1][1][1]='broadcastFloatX4#'
        body[1][2][0]=['lit','float','1.0',dict(rep=copy.deepcopy(LANE_FLOAT_REP))]
        body[1][6]['rep']=copy.deepcopy(VECTOR_FLOAT_REP)
        body[4]['binder']['rep']=copy.deepcopy(VECTOR_FLOAT_REP)
        self.assertTrue(run(m)['accepted'])
        for kind in ('unknown', 'double', 'long'):
            bad=copy.deepcopy(m)
            bad['bindings'][0]['expr'][2][1][2][0]=['lit','double','1.0',dict(rep=dict(LANE_FLOAT_REP,kind=kind))]
            self.assertFalse(run(bad)['accepted'],kind)
        for wrong in (VECTOR32_REP, VECTOR_REP, TUPLE_FLOAT_REP):
            bad=copy.deepcopy(m); bad['bindings'][0]['expr'][2][1][6]['rep']=copy.deepcopy(wrong)
            self.assertFalse(run(bad)['accepted'])
        self.assertTrue(signature_matches(TUPLE_FLOAT_REP, TUPLE_FLOAT_REP))
        bad=copy.deepcopy(TUPLE_FLOAT_REP); bad['components'][0]=dict(LANE_FLOAT_REP,kind='unknown')
        self.assertFalse(signature_matches(TUPLE_FLOAT_REP,bad))
    def test_real_float_core_local_entries_and_formal_frontier(self):
        path=ROOT.parent/'build/simd-floatx4/pre-core/SimdFloatX4.json'
        if not path.exists(): self.skipTest('Float SIMD Core export not generated')
        m=json.loads(path.read_text())
        for name in ('plusCase','minusCase','timesCase','edgePlus','edgeMinus','edgeTimes','nonFmaCase'):
            self.assertTrue(run(m,name)['accepted'], name)
        self.assertFalse(run(m,'vectorArgument')['accepted'])
    def test_exact_local_vector_is_accepted(self): self.assertTrue(run(fixture())['accepted'])
    def test_missing_or_wrong_shape_is_rejected(self):
        for mutation in ('missing', 'lane-count', 'physical', 'boxed', 'tuple'):
            m=fixture(); r=m['bindings'][0]['expr'][2][1][6]['rep']
            if mutation=='missing': del r['vector']
            elif mutation=='lane-count': r['vector']['lanes']=4
            elif mutation=='physical': r['primReps']=['Int64Rep','Int64Rep']
            elif mutation=='boxed': r['kind']='object'
            else: r.clear(); r.update(copy.deepcopy(TUPLE_REP))
            self.assertFalse(run(m)['accepted'], mutation)
    def test_vector_boundary_rejected(self):
        m=fixture(); m['bindings'][0]['expr'][3]['resultRep']=copy.deepcopy(VECTOR_REP)
        self.assertIn('vector-boundary',{i['code'] for i in run(m)['issues']})
    def test_real_core_two_local_entries_and_join_frontier(self):
        path=ROOT.parent/'build/simd/pre-core/SimdInt64X2.json'
        if not path.exists(): self.skipTest('SIMD Core export not generated')
        m=json.loads(path.read_text())
        for name in ('vectorCase','subtractCase'): self.assertTrue(run(m,name)['accepted'],name)
        self.assertFalse(run(m,'branchCase')['accepted'])
    def test_int32_exact_local_shape_and_cross_width_rejection(self):
        m=fixture(); body=m['bindings'][0]['expr'][2]
        body[1][1][1]='broadcastInt32X4#'
        body[1][2][0]=['app',['prim','intToInt32#',dict(rep=CLOSURE)],
            [['lit','int','2147483648',dict(rep=LONG)]],[False],False,True,dict(rep=LANE32_REP)]
        body[1][6]['rep']=copy.deepcopy(VECTOR32_REP)
        body[4]['binder']['rep']=copy.deepcopy(VECTOR32_REP)
        self.assertTrue(run(m)['accepted'])
        body[1][6]['rep']=copy.deepcopy(VECTOR_REP)
        self.assertFalse(run(m)['accepted'])
    def test_real_int32_core_local_entries_and_join_frontier(self):
        path=ROOT.parent/'build/simd-int32x4/pre-core/SimdInt32X4.json'
        if not path.exists(): self.skipTest('Int32 SIMD Core export not generated')
        m=json.loads(path.read_text())
        for name in ('vectorCase','subtractCase'): self.assertTrue(run(m,name)['accepted'],name)
        self.assertFalse(run(m,'branchCase')['accepted'])
if __name__=='__main__':unittest.main()
