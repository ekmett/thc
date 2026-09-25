#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Vector logical-shape and boundary checks, independent of SIMD execution."""
import copy
import importlib.util
import json
from pathlib import Path
import unittest
from core_vectors import VECTOR_REP, LANE_REP, TUPLE_REP, VECTOR32_REP, LANE32_REP, VECTOR_FLOAT_REP, LANE_FLOAT_REP, TUPLE_FLOAT_REP, VECTOR_DOUBLE_REP, LANE_DOUBLE_REP, TUPLE_DOUBLE_REP, signature_matches
from core_vectors import VECTOR16_REP, LANE16_REP, TUPLE16_REP, OPERATIONS, proof_error
from core_vectors import VECTOR8_REP, LANE8_REP, TUPLE8_REP
from core_vectors import VECTOR_WORD8_REP, LANE_WORD8_REP, TUPLE_WORD8_REP
from core_vectors import VECTOR_WORD32_REP, LANE_WORD32_REP, TUPLE_WORD32_REP
from core_vectors import VECTOR_WORD16_REP, LANE_WORD16_REP, TUPLE_WORD16_REP
ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('audit_core', ROOT / 'audit-core.py')
audit = importlib.util.module_from_spec(spec); spec.loader.exec_module(audit)
CAP = json.loads((ROOT / 'core-capabilities.json').read_text())
CLOSURE = dict(kind='closure', primReps=['BoxedRep (Just Lifted)'], evaluated=True)
LONG = dict(kind='long', primReps=['IntRep'], evaluated=True)
def run(module, entry='root', capability=CAP): return audit.Audit([('simd', module)], capability).run([entry])
def fixture():
    operand = ['lit', 'int64', '17', dict(rep=LANE_REP)]
    vector = ['app', ['prim', 'broadcastInt64X2#', dict(rep=CLOSURE)], [operand], [False], False, True, dict(rep=copy.deepcopy(VECTOR_REP))]
    body = ['case', vector, 'v', [['default', None, [], ['lit', 'int', '1', dict(rep=LONG)], dict(binders=[])]],
            dict(rep=LONG, binder=dict(id='v', lifted=False, rep=copy.deepcopy(VECTOR_REP)))]
    return dict(schema=1, ghc='9.14.1', bindings=[dict(id='root', name='root', lifted=True, arity=0,
        rep=CLOSURE, expr=['lam', [], body, dict(rep=CLOSURE,resultRep=LONG)])], constructors=[])
class VectorAuditTest(unittest.TestCase):
    def transport(self, module, entry='root', without=()):
        cap = dict(CAP, vectorTransport=[boundary for boundary in
            ('arguments', 'results', 'join-arguments', 'join-results', 'join-captures', 'tuple-fields', 'let-bindings')
            if boundary not in without])
        return audit.Audit([('vector-transport', module)], cap).run([entry])

    def transport_fixture(self, pap=False):
        module = fixture()
        body = module['bindings'][0]['expr'][2]
        original = body[1]
        formal = dict(id='vector-formal', lifted=False, rep=copy.deepcopy(VECTOR_REP))
        parameters = [formal] + ([dict(id='unused', lifted=False, rep=copy.deepcopy(LONG))] if pap else [])
        worker = dict(id='worker', name='worker', lifted=True, arity=len(parameters), rep=CLOSURE,
                      expr=['lam', parameters, ['var', 'vector-formal', dict(rep=copy.deepcopy(VECTOR_REP))],
                            dict(rep=CLOSURE, resultRep=copy.deepcopy(VECTOR_REP))])
        call = ['app', ['var', 'worker', dict(rep=CLOSURE)], [original], [False], False, False,
                dict(rep=copy.deepcopy(CLOSURE if pap else VECTOR_REP))]
        if pap:
            call = ['app', call, [['lit', 'int', '7', dict(rep=LONG)]], [False], False, False,
                    dict(rep=copy.deepcopy(VECTOR_REP))]
        body[1] = call
        module['bindings'].append(worker)
        return module

    def test_vector_transport_requires_explicit_capability_and_exact_shapes(self):
        for pap in (False, True):
            module = self.transport_fixture(pap)
            self.assertTrue(self.transport(module)['accepted'], self.transport(module)['issues'])
            self.assertFalse(run(module, capability=dict(CAP, vectorTransport=[]))['accepted'])
            for wrong in (VECTOR32_REP, VECTOR_WORD32_REP, TUPLE_REP):
                changed = copy.deepcopy(module)
                changed['bindings'][1]['expr'][1][0]['rep'] = copy.deepcopy(wrong)
                self.assertFalse(self.transport(changed)['accepted'])
            changed = copy.deepcopy(module)
            changed['bindings'][1]['expr'][1][0]['lifted'] = True
            self.assertFalse(self.transport(changed)['accepted'])

    def test_redundant_aggregate_vector_annotation_is_checked_without_relabelling(self):
        state = dict(kind='void', evaluated=True, primReps=[])
        proof = dict(copy.deepcopy(VECTOR_REP), kind='unknown', aggregate='unboxed-tuple',
                     components=[state, copy.deepcopy(VECTOR_REP)])
        self.assertIsNone(proof_error(proof))
        self.assertNotEqual(audit.Audit.shape(proof), audit.Audit.shape(VECTOR_REP))
        for wrong in (VECTOR32_REP['vector'], VECTOR_WORD32_REP['vector'], dict(lanes=2.0, element='Int64ElemRep')):
            self.assertIsNotNone(proof_error(dict(proof, vector=wrong)))
        cap = dict(CAP, vectorTransport=['tuple-fields'])
        checker = audit.Audit([], cap)
        checker.representation(proof, 'root', 'result')
        self.assertEqual([], checker.issues)
        bad = copy.deepcopy(proof)
        bad['components'][1] = copy.deepcopy(VECTOR32_REP)
        checker.representation(bad, 'root', 'result')
        self.assertTrue(checker.issues)

    def test_guest_transport_does_not_admit_host_arguments_or_results(self):
        module = self.transport_fixture()
        self.assertTrue(self.transport(module)['accepted'])
        report = self.transport(module, 'worker')
        self.assertEqual({('vector-boundary', '/entry', 'vector host argument'),
                          ('vector-boundary', '/entry', 'vector host result')},
                         {(i['code'], i['path'], i['detail']) for i in report['issues']})
        worker = module['bindings'][1]
        worker['expr'][2] = ['lit', 'int', '1', dict(rep=LONG)]
        worker['expr'][3]['resultRep'] = LONG
        self.assertEqual(['vector host argument'], [i['detail'] for i in self.transport(module, 'worker')['issues']])
        worker['arity'] = 0
        worker['expr'][1] = []
        worker['expr'][2] = fixture()['bindings'][0]['expr'][2][1]
        worker['expr'][3]['resultRep'] = VECTOR_REP
        self.assertEqual(['vector host result'], [i['detail'] for i in self.transport(module, 'worker')['issues']])

    def test_local_join_can_read_lexical_vector_lanes_but_residual_closure_cannot(self):
        for arity in (0, 1):
            module = fixture()
            outer = module['bindings'][0]['expr'][2]
            body = ['case', ['var', 'v', dict(rep=VECTOR_REP)], 'again',
                    [['default', None, [], ['lit', 'int', '1', dict(rep=LONG)], dict(binders=[])]],
                    dict(rep=LONG, binder=dict(id='again', lifted=False, rep=VECTOR_REP))]
            rhs = body if arity == 0 else ['lam', [dict(id='arg', lifted=False, rep=LONG)], body,
                                          dict(rep=CLOSURE, resultRep=LONG)]
            join = dict(id='join', name='join', lifted=arity != 0, rep=LONG if arity == 0 else CLOSURE,
                        joinValueArity=arity, joinResultRep=LONG, info=dict(joinArity=arity), expr=rhs)
            call = ['var', 'join', dict(rep=LONG)] if arity == 0 else [
                'app', ['var', 'join', dict(rep=CLOSURE)], [['lit', 'int', '0', dict(rep=LONG)]],
                [False], False, False, dict(rep=LONG)]
            outer[3][0][3] = ['let', False, [join], call, dict(rep=LONG)]
            report = self.transport(module)
            self.assertTrue(report['accepted'], report['issues'])
            report = self.transport(module, without=('join-captures',))
            self.assertIn('vector join capture', [i['detail'] for i in report['issues']])
            closure = ['lam', [dict(id='closure-arg', lifted=False, rep=LONG)], body,
                       dict(rep=CLOSURE, resultRep=LONG)]
            invocation = ['app', closure, [['lit', 'int', '0', dict(rep=LONG)]], [False], False, False, dict(rep=LONG)]
            if arity == 0:
                join['expr'] = invocation
            else:
                join['expr'][2] = invocation
            self.assertIn('vector capture', [i['detail'] for i in self.transport(module)['issues']])

    def test_call_transport_does_not_admit_a_heap_capture(self):
        module = fixture()
        body = module['bindings'][0]['expr'][2]
        body[3][0][3] = ['lam', [], ['var', 'v', dict(rep=copy.deepcopy(VECTOR_REP))],
                         dict(rep=CLOSURE, resultRep=copy.deepcopy(VECTOR_REP))]
        body[4]['rep'] = CLOSURE
        module['bindings'][0]['expr'][3]['resultRep'] = CLOSURE
        report = self.transport(module)
        self.assertIn(('vector-boundary', 'vector capture'), {(i['code'], i['detail']) for i in report['issues']})

    def test_all_vector_families_require_integer_json_lane_counts(self):
        for original in (VECTOR_REP, VECTOR32_REP, VECTOR16_REP, VECTOR8_REP,
                         VECTOR_WORD8_REP, VECTOR_WORD16_REP, VECTOR_WORD32_REP,
                         VECTOR_FLOAT_REP, VECTOR_DOUBLE_REP):
            self.assertIsNone(proof_error(original))
            self.assertIsNone(proof_error(json.loads(json.dumps(original))))
            lanes = original['vector']['lanes']
            for count in (float(lanes), lanes + 0.5, True, str(lanes), None):
                proof = copy.deepcopy(original)
                proof['vector']['lanes'] = count
                self.assertIsNotNone(proof_error(proof), (original, count))

    def test_int32_multiply_requires_two_exact_signed_vectors(self):
        m=fixture(); body=m['bindings'][0]['expr'][2]
        def broadcast(value):
            return ['app',['prim','broadcastInt32X4#',dict(rep=CLOSURE)],
                    [['lit','int32',str(value),dict(rep=LANE32_REP)]],[False],False,True,
                    dict(rep=copy.deepcopy(VECTOR32_REP))]
        body[1]=['app',['prim','timesInt32X4#',dict(rep=CLOSURE)],
                 [broadcast(-2147483648),broadcast(-1)],[False,False],False,True,
                 dict(rep=copy.deepcopy(VECTOR32_REP))]
        body[4]['binder']['rep']=copy.deepcopy(VECTOR32_REP)
        self.assertTrue(run(m)['accepted'])
        self.assertEqual(CAP['primitives']['timesInt32X4#'],2)
        self.assertEqual(OPERATIONS['timesInt32X4#'],([VECTOR32_REP,VECTOR32_REP],VECTOR32_REP))
        for position in range(2):
            for wrong in (VECTOR_WORD32_REP,VECTOR16_REP,VECTOR_REP):
                bad=copy.deepcopy(m)
                bad['bindings'][0]['expr'][2][1][2][position][-1]['rep']=copy.deepcopy(wrong)
                self.assertFalse(run(bad)['accepted'])
        for flag in (True,None,0,'false'):
            bad=copy.deepcopy(m); bad['bindings'][0]['expr'][2][1][3]=[False,flag]
            self.assertFalse(run(bad)['accepted'])
        bad=copy.deepcopy(m); bad['bindings'][0]['expr'][2][1][2].pop()
        self.assertFalse(run(bad)['accepted'])
        bad=copy.deepcopy(m); bad['bindings'][0]['expr'][2][1][-1]['rep']=copy.deepcopy(VECTOR_WORD32_REP)
        self.assertFalse(run(bad)['accepted'])
    def test_word32_shape_and_unsigned_identity_are_independent_of_storage(self):
        m=fixture(); body=m['bindings'][0]['expr'][2]
        body[1][1][1]='broadcastWord32X4#'
        body[1][2][0]=['lit','word32','4294967295',dict(rep=copy.deepcopy(LANE_WORD32_REP))]
        body[1][6]['rep']=copy.deepcopy(VECTOR_WORD32_REP)
        body[4]['binder']['rep']=copy.deepcopy(VECTOR_WORD32_REP)
        self.assertTrue(run(m)['accepted'])
        self.assertIsNone(proof_error(VECTOR_WORD32_REP))
        self.assertFalse(signature_matches(VECTOR_WORD32_REP,VECTOR32_REP))
        for operand in (['lit','word32','2147483648'], ['lit','word32','4294967295',dict(rep=dict(kind='unknown',primReps=None,evaluated=False))]):
            good=copy.deepcopy(m); good['bindings'][0]['expr'][2][1][2][0]=operand
            self.assertTrue(run(good)['accepted'])
        for operand in (['lit','int32','2147483647'], ['lit','int32','2147483647',dict(rep=LANE_WORD32_REP)],
                        ['lit','word32','4294967295',dict(rep=LANE32_REP)]):
            bad=copy.deepcopy(m); bad['bindings'][0]['expr'][2][1][2][0]=operand
            self.assertFalse(run(bad)['accepted'])
        for flag in (True,None,0,'false'):
            bad=copy.deepcopy(m); bad['bindings'][0]['expr'][2][1][3]=[flag]
            self.assertFalse(run(bad)['accepted'])
        for wrong in (LANE32_REP,LANE_WORD16_REP,LANE_WORD8_REP,LANE16_REP,LONG,dict(LANE_WORD32_REP,kind='unknown'),
                      dict(LANE_WORD32_REP,primReps=['WordRep'])):
            bad=copy.deepcopy(m); bad['bindings'][0]['expr'][2][1][2][0][3]['rep']=wrong
            self.assertFalse(run(bad)['accepted'],wrong)
            for index in range(4):
                lanes=copy.deepcopy(TUPLE_WORD32_REP); lanes['components'][index]=wrong
                self.assertFalse(signature_matches(TUPLE_WORD32_REP,lanes))
        for wrong in (VECTOR32_REP,VECTOR_WORD16_REP,VECTOR_WORD8_REP,VECTOR8_REP,VECTOR16_REP,TUPLE_WORD32_REP):
            bad=copy.deepcopy(m); bad['bindings'][0]['expr'][2][1][6]['rep']=copy.deepcopy(wrong)
            self.assertFalse(run(bad)['accepted'])
        self.assertEqual(OPERATIONS['packWord32X4#'],([TUPLE_WORD32_REP],VECTOR_WORD32_REP))
        self.assertEqual(OPERATIONS['unpackWord32X4#'],([VECTOR_WORD32_REP],TUPLE_WORD32_REP))
        names={name for name in OPERATIONS if 'Word32X4' in name}
        self.assertEqual(len(names),9)
        self.assertNotIn('negateWord32X4#',CAP['primitives'])
        for name in names: self.assertEqual(CAP['primitives'][name],len(OPERATIONS[name][0]))

    def test_word16_shape_and_unsigned_identity_are_independent_of_storage(self):
        m=fixture(); body=m['bindings'][0]['expr'][2]
        body[1][1][1]='broadcastWord16X8#'
        body[1][2][0]=['lit','word16','65535',dict(rep=copy.deepcopy(LANE_WORD16_REP))]
        body[1][6]['rep']=copy.deepcopy(VECTOR_WORD16_REP)
        body[4]['binder']['rep']=copy.deepcopy(VECTOR_WORD16_REP)
        self.assertTrue(run(m)['accepted'])
        self.assertIsNone(proof_error(VECTOR_WORD16_REP))
        self.assertFalse(signature_matches(VECTOR_WORD16_REP,VECTOR16_REP))
        for operand in (['lit','word16','32768'], ['lit','word16','65535',dict(rep=dict(kind='unknown',primReps=None,evaluated=False))]):
            good=copy.deepcopy(m); good['bindings'][0]['expr'][2][1][2][0]=operand
            self.assertTrue(run(good)['accepted'])
        for operand in (['lit','int16','32767'], ['lit','int16','32767',dict(rep=LANE_WORD16_REP)],
                        ['lit','word16','65535',dict(rep=LANE16_REP)]):
            bad=copy.deepcopy(m); bad['bindings'][0]['expr'][2][1][2][0]=operand
            self.assertFalse(run(bad)['accepted'])
        for flag in (True,None,0,'false'):
            bad=copy.deepcopy(m); bad['bindings'][0]['expr'][2][1][3]=[flag]
            self.assertFalse(run(bad)['accepted'])
        for wrong in (LANE16_REP,LANE_WORD8_REP,LANE32_REP,LONG,dict(LANE_WORD16_REP,kind='unknown'),
                      dict(LANE_WORD16_REP,primReps=['WordRep'])):
            bad=copy.deepcopy(m); bad['bindings'][0]['expr'][2][1][2][0][3]['rep']=wrong
            self.assertFalse(run(bad)['accepted'],wrong)
            for index in range(8):
                lanes=copy.deepcopy(TUPLE_WORD16_REP); lanes['components'][index]=wrong
                self.assertFalse(signature_matches(TUPLE_WORD16_REP,lanes))
        for wrong in (VECTOR16_REP,VECTOR_WORD8_REP,VECTOR8_REP,VECTOR32_REP,TUPLE_WORD16_REP):
            bad=copy.deepcopy(m); bad['bindings'][0]['expr'][2][1][6]['rep']=copy.deepcopy(wrong)
            self.assertFalse(run(bad)['accepted'])
        self.assertEqual(OPERATIONS['packWord16X8#'],([TUPLE_WORD16_REP],VECTOR_WORD16_REP))
        self.assertEqual(OPERATIONS['unpackWord16X8#'],([VECTOR_WORD16_REP],TUPLE_WORD16_REP))
        names={name for name in OPERATIONS if 'Word16X8' in name}
        self.assertEqual(len(names),9)
        self.assertNotIn('negateWord16X8#',CAP['primitives'])
        for name in names: self.assertEqual(CAP['primitives'][name],len(OPERATIONS[name][0]))

    def test_word8_shape_and_unsigned_identity_are_independent_of_storage(self):
        m=fixture(); body=m['bindings'][0]['expr'][2]
        body[1][1][1]='broadcastWord8X16#'
        body[1][2][0]=['lit','word8','255',dict(rep=copy.deepcopy(LANE_WORD8_REP))]
        body[1][6]['rep']=copy.deepcopy(VECTOR_WORD8_REP)
        body[4]['binder']['rep']=copy.deepcopy(VECTOR_WORD8_REP)
        self.assertTrue(run(m)['accepted'])
        self.assertIsNone(proof_error(VECTOR_WORD8_REP))
        self.assertFalse(signature_matches(VECTOR_WORD8_REP,VECTOR8_REP))
        for operand in (['lit','word8','128'], ['lit','word8','255',dict(rep=dict(kind='unknown',primReps=None,evaluated=False))]):
            good=copy.deepcopy(m); good['bindings'][0]['expr'][2][1][2][0]=operand
            self.assertTrue(run(good)['accepted'])
        for operand in (['lit','int8','127'], ['lit','int8','127',dict(rep=LANE_WORD8_REP)],
                        ['lit','word8','255',dict(rep=LANE8_REP)]):
            bad=copy.deepcopy(m); bad['bindings'][0]['expr'][2][1][2][0]=operand
            self.assertFalse(run(bad)['accepted'])
        for flag in (True,None,0,'false'):
            bad=copy.deepcopy(m); bad['bindings'][0]['expr'][2][1][3]=[flag]
            self.assertFalse(run(bad)['accepted'])
        for wrong in (LANE8_REP,LANE16_REP,LONG,dict(LANE_WORD8_REP,kind='unknown'),
                      dict(LANE_WORD8_REP,primReps=['WordRep'])):
            bad=copy.deepcopy(m); bad['bindings'][0]['expr'][2][1][2][0][3]['rep']=wrong
            self.assertFalse(run(bad)['accepted'],wrong)
            for index in range(16):
                lanes=copy.deepcopy(TUPLE_WORD8_REP); lanes['components'][index]=wrong
                self.assertFalse(signature_matches(TUPLE_WORD8_REP,lanes))
        for wrong in (VECTOR8_REP,VECTOR16_REP,VECTOR32_REP,TUPLE_WORD8_REP):
            bad=copy.deepcopy(m); bad['bindings'][0]['expr'][2][1][6]['rep']=copy.deepcopy(wrong)
            self.assertFalse(run(bad)['accepted'])
        self.assertEqual(OPERATIONS['packWord8X16#'],([TUPLE_WORD8_REP],VECTOR_WORD8_REP))
        self.assertEqual(OPERATIONS['unpackWord8X16#'],([VECTOR_WORD8_REP],TUPLE_WORD8_REP))
        names={name for name in OPERATIONS if 'Word8X16' in name}
        self.assertEqual(len(names),9)
        self.assertNotIn('negateWord8X16#',CAP['primitives'])
        for name in names: self.assertEqual(CAP['primitives'][name],len(OPERATIONS[name][0]))

    def test_int8_exact_local_shape_and_signature_contracts(self):
        m=fixture(); body=m['bindings'][0]['expr'][2]
        body[1][1][1]='broadcastInt8X16#'
        body[1][2][0]=['lit','int8','-128',dict(rep=copy.deepcopy(LANE8_REP))]
        body[1][6]['rep']=copy.deepcopy(VECTOR8_REP)
        body[4]['binder']['rep']=copy.deepcopy(VECTOR8_REP)
        self.assertTrue(run(m)['accepted'])
        for operand in (['lit','int8','127'], ['lit','int8','-128',dict(rep=dict(kind='unknown',primReps=None,evaluated=False))]):
            good=copy.deepcopy(m); good['bindings'][0]['expr'][2][1][2][0]=operand
            self.assertTrue(run(good)['accepted'])
        for flag in (True,None,0,'false'):
            bad=copy.deepcopy(m); bad['bindings'][0]['expr'][2][1][3]=[flag]
            self.assertFalse(run(bad)['accepted'],flag)
        for wrong in (dict(LANE8_REP,kind='unknown'),LANE16_REP,LANE32_REP,LONG,dict(LANE8_REP,primReps=['Word8Rep'])):
            bad=copy.deepcopy(m); bad['bindings'][0]['expr'][2][1][2][0][3]['rep']=wrong
            self.assertFalse(run(bad)['accepted'],wrong)
            lanes=copy.deepcopy(TUPLE8_REP); lanes['components'][15]=wrong
            self.assertFalse(signature_matches(TUPLE8_REP,lanes),wrong)
        for wrong in (VECTOR_REP,VECTOR16_REP,VECTOR32_REP,TUPLE8_REP,VECTOR_WORD8_REP):
            bad=copy.deepcopy(m); bad['bindings'][0]['expr'][2][1][6]['rep']=copy.deepcopy(wrong)
            self.assertFalse(run(bad)['accepted'])
        self.assertEqual(OPERATIONS['packInt8X16#'],([TUPLE8_REP],VECTOR8_REP))
        self.assertEqual(OPERATIONS['unpackInt8X16#'],([VECTOR8_REP],TUPLE8_REP))
        for name,(args,result) in OPERATIONS.items():
            if 'Int8X16' in name:
                self.assertEqual(CAP['primitives'][name],len(args))
                self.assertTrue(signature_matches(result,result))
        for lanes,element in ((8,'Int8ElemRep'),(8,'Word8ElemRep')):
            bad=copy.deepcopy(VECTOR8_REP); bad['vector']=dict(lanes=lanes,element=element)
            bad['primReps']=[f'VecRep {lanes} {element}']
            self.assertIsNotNone(proof_error(bad))
    def test_int16_exact_local_shape_and_signature_contracts(self):
        m=fixture(); body=m['bindings'][0]['expr'][2]
        body[1][1][1]='broadcastInt16X8#'
        body[1][2][0]=['lit','int16','-32768',dict(rep=copy.deepcopy(LANE16_REP))]
        body[1][6]['rep']=copy.deepcopy(VECTOR16_REP)
        body[4]['binder']['rep']=copy.deepcopy(VECTOR16_REP)
        self.assertTrue(run(m)['accepted'])
        for flag in (True, None, 0, 'false'):
            bad=copy.deepcopy(m); bad['bindings'][0]['expr'][2][1][3]=[flag]
            self.assertFalse(run(bad)['accepted'],flag)
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
        for lanes,element in ((4,'Int16ElemRep'),(4,'Word16ElemRep')):
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
    def test_real_int16_core_local_entries_and_formal_frontier(self):
        from int16x8_model import entries
        path=ROOT.parent/'build/simd-int16x8/pre-core/SimdInt16X8.json'
        if not path.exists(): self.skipTest('Int16 SIMD Core export not generated')
        m=json.loads(path.read_text())
        for entry in entries(): self.assertTrue(run(m,entry['name'])['accepted'],entry['name'])
        self.assertFalse(run(m,'vectorArgument')['accepted'])
    def test_real_int8_core_local_entries_and_formal_frontier(self):
        from int8x16_model import entries
        path=ROOT.parent/'build/simd-int8x16/pre-core/SimdInt8X16.json'
        if not path.exists(): self.skipTest('Int8 SIMD Core export not generated')
        m=json.loads(path.read_text())
        for entry in entries(): self.assertTrue(run(m,entry['name'])['accepted'],entry['name'])
        self.assertFalse(run(m,'vectorArgument')['accepted'])
    def test_real_word8_core_local_entries_and_formal_frontier(self):
        from word8x16_model import entries
        path=ROOT.parent/'build/simd-word8x16/pre-core/SimdWord8X16.json'
        if not path.exists(): self.skipTest('Word8 SIMD Core export not generated')
        m=json.loads(path.read_text())
        for entry in entries(): self.assertTrue(run(m,entry['name'])['accepted'],entry['name'])
        self.assertFalse(run(m,'vectorArgument')['accepted'])
    def test_real_word16_core_local_entries_and_formal_frontier(self):
        from word16x8_model import entries
        path=ROOT.parent/'build/simd-word16x8/pre-core/SimdWord16X8.json'
        if not path.exists(): self.skipTest('Word16 SIMD Core export not generated')
        m=json.loads(path.read_text())
        for entry in entries(): self.assertTrue(run(m,entry['name'])['accepted'],entry['name'])
        self.assertFalse(run(m,'vectorArgument')['accepted'])
    def test_real_word32_core_local_entries_and_formal_frontier(self):
        from word32x4_model import entries
        path=ROOT.parent/'build/simd-word32x4/pre-core/SimdWord32X4.json'
        if not path.exists(): self.skipTest('Word32 SIMD Core export not generated')
        m=json.loads(path.read_text())
        for entry in entries(): self.assertTrue(run(m,entry['name'])['accepted'],entry['name'])
        self.assertFalse(run(m,'vectorArgument')['accepted'])
    def test_real_int32_multiply_core_entries_and_formal_frontier(self):
        from int32x4_multiply_model import entries
        path=ROOT.parent/'build/simd-int32x4-multiply/pre-core/SimdInt32X4Multiply.json'
        if not path.exists(): self.skipTest('Int32 multiplication Core export not generated')
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
    def test_real_core_local_entries_and_vector_join_capability(self):
        path=ROOT.parent/'build/simd/pre-core/SimdInt64X2.json'
        if not path.exists(): self.skipTest('SIMD Core export not generated')
        m=json.loads(path.read_text())
        for name in ('vectorCase','subtractCase'): self.assertTrue(run(m,name)['accepted'],name)
        report = run(m, 'branchCase')
        self.assertEqual({'join-arguments', 'join-captures', 'join-results'} <= set(CAP.get('vectorTransport', [])),
                         report['accepted'], report['issues'])
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
    def test_real_int32_core_local_entries_and_vector_join_capability(self):
        path=ROOT.parent/'build/simd-int32x4/pre-core/SimdInt32X4.json'
        if not path.exists(): self.skipTest('Int32 SIMD Core export not generated')
        m=json.loads(path.read_text())
        for name in ('vectorCase','subtractCase'): self.assertTrue(run(m,name)['accepted'],name)
        report = run(m, 'branchCase')
        self.assertEqual({'join-arguments', 'join-captures', 'join-results'} <= set(CAP.get('vectorTransport', [])),
                         report['accepted'], report['issues'])
if __name__=='__main__':unittest.main()
