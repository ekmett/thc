#!/usr/bin/env python3
"""Independent per-lane integer checks and strict fixture-proof negative controls."""
import copy
import importlib.util
import json
from pathlib import Path
import unittest
import word8x16_model as model

spec = importlib.util.spec_from_file_location('word8x16_prepare', Path(__file__).with_name('prepare-word8x16-audit.py'))
prepare = importlib.util.module_from_spec(spec); spec.loader.exec_module(prepare)


def reference(name, args):
    def unsigned(bits):
        bits &= 0xff
        return bits
    if name == 'laneCase':
        operation, selected, a, b = args
        name = model.OPERATIONS[operation]
    else:
        selected = None
        a, b = args
    helper = name
    name = {'scalarHelperCase': 'plusCase', 'tupleHelperCase': 'timesCase'}.get(name, name)
    x = [a, b, a+1, b-1, a+127, b-128, a*3+7, b*5-11,
         a*7+29, b*9-31, a*11+37, b*13-41, a*15+43, b*17-47, a*19+53, b*21-59]
    y = [b+2, a-3, b*7+13, a*11-17, b+128, a-127, b*13+19, a*17-23,
         b*23+61, a*25-67, b*27+71, a*29-73, b*31+79, a*33-83, b*35+89, a*37-97]
    values = []
    for i in range(16):
        p, q = x[i] & 0xff, y[i] & 0xff
        if name == 'plusCase': result = p+q
        elif name == 'minusCase': result = p-q
        elif name == 'timesCase': result = p*q
        elif name == 'packCase': result = p
        else: result = a-b+29
        values.append(unsigned(result))
    if selected is not None: return values[selected]
    score = (3*values[0]+5*values[1]+7*values[2]+11*values[3]+13*values[4]+17*values[5]+19*values[6]+23*values[7]
             +29*values[8]+31*values[9]+37*values[10]+41*values[11]+43*values[12]+47*values[13]+53*values[14]+59*values[15])
    return score+48 if helper == 'scalarHelperCase' else score


def result_rep():
    return dict(kind='long', primReps=['IntRep'], evaluated=False)


def tuple_rep():
    return dict(kind='unknown', primReps=['Word8Rep']*16, aggregate='unboxed-tuple',
                components=[dict(kind='long', primReps=['Word8Rep'], evaluated=True) for _ in range(16)])


def fixture(name):
    formal = lambda ident: dict(id=ident, rep=dict(kind='long', primReps=['IntRep'], evaluated=True))
    entry = dict(name=name, arity=2)
    root = dict(id='root', name=name, expr=['lam', [formal('a'), formal('b')],
                ['lit', 'int', '0'], dict(resultRep=result_rep())])
    bindings = [root]
    if name in model.HELPERS:
        helper = dict(id='helper', name=model.HELPERS[name],
                      expr=['lam', [formal('x'), formal('y')], ['lit', 'int', '0'],
                            dict(resultRep=tuple_rep() if name == 'tupleHelperCase' else result_rep())])
        call = ['app', ['var', 'helper'], [['var', 'a'], ['var', 'b']], [False, False], False, False]
        root['expr'][2] = ['case', call, {}, [['default', None, [], ['lit', 'int', '0']]]]
        bindings.append(helper)
    report = dict(roots=['root'], reachableBindings=[dict(id=b['id']) for b in bindings],
                  primitives=[dict(name=p, uses=[{}]*n) for p, n in prepare.VECTOR_COUNTS[name].items()])
    return entry, report, dict(bindings=bindings)


class Word8X16ModelTest(unittest.TestCase):
    def test_every_narrow_encoding_and_extreme_arithmetic(self):
        for bits in range(256):
            self.assertEqual(model.narrow(bits), bits)
            self.assertEqual(model.narrow(bits), model.narrow(bits+(1 << 48)))
        self.assertEqual(model.narrow(127+1), 128)
        self.assertEqual(model.narrow(255+1), 0)
        self.assertEqual(model.narrow(0-1), 255)
        self.assertEqual(model.narrow(255*255), 1)
        self.assertEqual(model.narrow(128*2), 0)

    def test_exhaustive_unsigned_byte_products(self):
        for left in range(256):
            for right in range(256):
                answer = model.result_lanes('timesCase', left, right-2)[0]
                self.assertEqual(answer, (left*right) & 255)

    def test_all_native_expected_rows_against_separate_lane_implementation(self):
        self.assertEqual(len(model.scalar_cases()), 176)
        self.assertEqual(len(model.model_rows()), 7712)
        for entry in model.entries():
            for args in entry['cases']:
                answer = model.expected(entry['name'], *args)
                self.assertEqual(answer, reference(entry['name'], args))
                self.assertGreaterEqual(answer, 0)
                self.assertLess(answer, 1 << 17)

    def test_every_lane_observes_unsigned_high_bytes(self):
        for operation in range(5):
            for lane in range(16):
                observed = {model.expected('laneCase', *args) for args in model.lane_cases()
                            if args[:2] == [operation, lane]}
                self.assertIn(255, observed)
                self.assertNotIn(-1, observed)

    def test_signed_negative_controls_change_only_separate_metadata_copies(self):
        proof = dict(kind='vector', primReps=['VecRep 16 Word8ElemRep'],
                     vector=dict(lanes=16, element='Word8ElemRep'))
        tuple_value = ['tuple', dict(rep=tuple_rep())]
        packed = ['app', ['prim', 'packWord8X16#'], [tuple_value], dict(rep=proof)]
        plus = ['app', ['prim', 'plusWord8X16#'], [packed, copy.deepcopy(packed)], dict(rep=copy.deepcopy(proof))]
        module = dict(bindings=[dict(name='plusCase', expr=plus)])
        saved = copy.deepcopy(module)
        for variant in ('signedLaneTuple', 'signedVectorOperand'):
            altered = prepare.signed_control(module, variant)
            self.assertEqual(module, saved)
            altered_plus = altered['bindings'][0]['expr']
            self.assertEqual(altered_plus[2][1], packed)
            if variant == 'signedLaneTuple':
                lane_proof = prepare.representation(altered_plus[2][0][2][0])
                self.assertEqual(lane_proof['primReps'], ['Int8Rep']*16)
                self.assertFalse(prepare.lane_tuple(lane_proof))
            else:
                vector_proof = prepare.representation(altered_plus[2][0])
                self.assertEqual(vector_proof['vector'], dict(lanes=16, element='Int8ElemRep'))
                self.assertEqual(vector_proof['primReps'], ['VecRep 16 Int8ElemRep'])

    def test_prepared_signed_mutations_match_the_current_auditor(self):
        # Exercise the actual auditor, not only the metadata mutation helper.
        # Sum-aware auditing retains these tuple failures under aggregate wording.
        spec = importlib.util.spec_from_file_location('word8x16_current_audit',
                                                     prepare.ROOT/'scripts/audit-core.py')
        auditor = importlib.util.module_from_spec(spec); spec.loader.exec_module(auditor)
        capabilities = json.loads((prepare.ROOT/'scripts/core-capabilities.json').read_text())
        provenance = json.loads((prepare.OUT/'provenance.json').read_text())
        self.assertIn(provenance['stages'], [['pre'], ['pre', 'post']])
        for stage in provenance['stages']:
            path = prepare.OUT/f'{stage}-core/SimdWord8X16.json'
            module = json.loads(path.read_text())
            self.assertTrue(auditor.Audit([(str(path), module)], capabilities).run(['plusCase'])['accepted'])
            controls = prepare.audit_signed_controls(module, path, auditor, capabilities)
            self.assertEqual(set(controls), {'signedLaneTuple', 'signedVectorOperand'})
            for name, count in [('signedLaneTuple', 34), ('signedVectorOperand', 4)]:
                report = controls[name]['report']
                self.assertFalse(report['accepted'])
                self.assertFalse(report['missingGlobals'])
                self.assertEqual(len(report['issues']), count)

    def test_each_lane_observes_complete_independent_operand_grid(self):
        cases = model.lane_cases()
        self.assertEqual(len(cases), len({tuple(args) for args in cases}))
        for operation in range(5):
            for lane in range(16):
                rows = [args for args in cases if args[:2] == [operation, lane]]
                self.assertEqual(len(rows), 81)
                if operation == 4:
                    self.assertEqual({model.expected('laneCase', *args) for args in rows}, set(model.EDGE))
                else:
                    self.assertEqual({tuple(values[lane] for values in model.operands(*args[2:])) for args in rows},
                                     {(a, b) for a in model.EDGE for b in model.EDGE})

    def test_lane_order_and_modulo_input_narrowing_are_observable(self):
        for name in model.OPERATIONS[:-1]:
            lanes = model.result_lanes(name, 12345, -6789)
            self.assertGreater(len(set(lanes)), 1)
            score = sum(w*x for w, x in zip(model.WEIGHTS, lanes))
            for i in range(16):
                for j in range(i):
                    if lanes[i] == lanes[j]: continue
                    swapped = lanes[:]; swapped[i], swapped[j] = swapped[j], swapped[i]
                    self.assertNotEqual(score, sum(w*x for w, x in zip(model.WEIGHTS, swapped)))
        for name in model.OPERATIONS:
            self.assertEqual(model.expected(name, 123, -456), model.expected(name, 123+(1 << 48), -456-(1 << 32)))

    def test_rows_require_known_arity_unique_keys_and_complete_comparison(self):
        rows = model.model_rows()
        text = ''.join('\t'.join(map(str, (*key, answer)))+'\n' for key, answer in rows.items())
        self.assertEqual(model.parse_rows(text), rows)
        for bad in (text+text.splitlines()[0]+'\n', 'unknown\t0\t0\n', 'plusCase\t0\t0\n'):
            with self.assertRaises(AssertionError): model.parse_rows(bad)
        self.assertNotEqual(model.parse_rows('\n'.join(text.splitlines()[1:])), rows)

    def test_tuple_proof_checks_every_lane_and_exact_width(self):
        good = tuple_rep()
        self.assertTrue(prepare.lane_tuple(good))
        for lane in range(16):
            for rep in ('Int8Rep', 'Int16Rep', 'Int32Rep', 'IntRep', 'FloatRep'):
                bad = copy.deepcopy(good)
                bad['components'][lane]['primReps'] = [rep]
                self.assertFalse(prepare.lane_tuple(bad))
        for mutation in ('short', 'long', 'flat', 'kind', 'no_aggregate'):
            bad = copy.deepcopy(good)
            if mutation == 'short': bad['components'].pop()
            elif mutation == 'long': bad['components'].append(copy.deepcopy(bad['components'][0]))
            elif mutation == 'flat': bad['primReps'][-1] = 'Int8Rep'
            elif mutation == 'kind': bad['kind'] = 'object'
            else: bad.pop('aggregate')
            self.assertFalse(prepare.lane_tuple(bad))

    def test_actual_root_proof_rejects_hidden_lambdas_and_wrong_call_boundary(self):
        for name in ('plusCase', 'scalarHelperCase', 'tupleHelperCase'):
            entry, report, module = fixture(name)
            self.assertEqual(prepare.check_guest_structure(entry, report, module)['guestCalls'], 1 if name == 'plusCase' else 2)
            for mutation in ('formal', 'hidden_lambda', 'global', 'vector_count'):
                bad, rr = copy.deepcopy(module), copy.deepcopy(report)
                if mutation == 'formal': bad['bindings'][0]['expr'][1][0]['rep']['primReps'] = ['Word8Rep']
                elif mutation == 'hidden_lambda':
                    bad['bindings'][0]['expr'][2] = ['let', False, [dict(expr=['lam', [], ['lit', 'int', '0']])], ['lit', 'int', '0']]
                elif mutation == 'global': rr['reachableBindings'].append(dict(id='extra')); bad['bindings'].append(dict(id='extra', name='extra'))
                else: rr['primitives'][0]['uses'].pop()
                with self.assertRaises(AssertionError): prepare.check_guest_structure(entry, rr, bad)
            if name == 'plusCase': continue
            for mutation in ('arity', 'conditional', 'argument', 'helper_result'):
                bad = copy.deepcopy(module); body = bad['bindings'][0]['expr'][2]
                if mutation == 'arity': body[1][2].pop()
                elif mutation == 'conditional': body[3].append(copy.deepcopy(body[3][0]))
                elif mutation == 'argument': body[1][2][0] = ['lit', 'int', '0']
                else: bad['bindings'][1]['expr'][-1]['resultRep'] = dict(kind='vector', primReps=['VecRep 16 Word8ElemRep'])
                with self.assertRaises(AssertionError): prepare.check_guest_structure(entry, report, bad)


if __name__ == '__main__':
    unittest.main()
