#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Independent per-lane integer checks and strict fixture-proof negative controls."""
import copy
import importlib.util
import json
from pathlib import Path
import unittest
import word32x4_model as model

spec = importlib.util.spec_from_file_location('word32x4_prepare', Path(__file__).with_name('prepare-word32x4-audit.py'))
prepare = importlib.util.module_from_spec(spec); spec.loader.exec_module(prepare)


def reference(name, args):
    def unsigned(bits):
        bits &= 0xffffffff
        return bits
    if name == 'laneCase':
        operation, selected, a, b = args
        name = model.OPERATIONS[operation]
    else:
        selected = None
        a, b = args
    helper = name
    name = {'scalarHelperCase': 'plusCase', 'tupleHelperCase': 'timesCase'}.get(name, name)
    x = [a, b, a+1, b-1]
    y = [b+2, a-3, b*7+13, a*11-17]
    # Explicit machine-Int wrap is separate from the model's unbounded algebra.
    machine = lambda value: ((value+(1 << 63)) & ((1 << 64)-1))-(1 << 63)
    x, y = list(map(machine, x)), list(map(machine, y))
    values = []
    for i in range(4):
        p, q = x[i] & 0xffffffff, y[i] & 0xffffffff
        if name == 'plusCase': result = p+q
        elif name == 'minusCase': result = p-q
        elif name == 'timesCase': result = p*q
        elif name == 'packCase': result = p
        else: result = a-b+29
        values.append(unsigned(result))
    if selected is not None: return values[selected]
    score = 3*values[0]+5*values[1]+7*values[2]+11*values[3]
    return score+48 if helper == 'scalarHelperCase' else score


def result_rep():
    return dict(kind='long', primReps=['IntRep'], evaluated=False)


def tuple_rep():
    return dict(kind='unknown', primReps=['Word32Rep']*4, aggregate='unboxed-tuple',
                components=[dict(kind='long', primReps=['Word32Rep'], evaluated=True) for _ in range(4)])


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


def word_samples():
    # Both 16-bit halves are bijections, including unsigned high-bit encodings.
    # This is 65,536 distinct 32-bit words, not the entire 2^32-word domain.
    return [(half << 16) | ((half*257 ^ 0xa5a5) & 0xffff) for half in range(65536)]


class Word32X4ModelTest(unittest.TestCase):
    def test_samples_cover_both_halves_and_extreme_arithmetic(self):
        samples = word_samples()
        self.assertEqual(len(set(samples)), 65536)
        self.assertEqual({word >> 16 for word in samples}, set(range(65536)))
        self.assertEqual({word & 0xffff for word in samples}, set(range(65536)))
        for bits in samples:
            self.assertEqual(model.narrow(bits), bits)
            self.assertEqual(model.narrow(bits), model.narrow(bits+(1 << 48)))
        self.assertEqual(model.narrow(2147483647+1), 2147483648)
        self.assertEqual(model.narrow(4294967295+1), 0)
        self.assertEqual(model.narrow(0-1), 4294967295)
        self.assertEqual(model.narrow(4294967295*4294967295), 1)
        self.assertEqual(model.narrow(2147483648*2), 0)
        self.assertEqual(model.narrow(-(1 << 63)), 0)
        self.assertEqual(model.narrow((1 << 63)-1), 4294967295)

    def test_unsigned_word_samples_times_boundary_operands(self):
        # 589,824 scalar products, not all 2^64 Word32 binary operand pairs.
        for left in word_samples():
            for right in (0, 1, 2, 1073741823, 2147483647, 2147483648, 2147483649, 4294967294, 4294967295):
                self.assertEqual(model.narrow(left*right), (left*right) & 0xffffffff)
            self.assertEqual(model.result_lanes('timesCase', left, 4294967293)[0], (-left) & 0xffffffff)

    def test_all_native_expected_rows_against_separate_lane_implementation(self):
        self.assertEqual(len(model.scalar_cases()), 466)
        self.assertEqual(len(model.model_rows()), 4882)
        for entry in model.entries():
            for args in entry['cases']:
                answer = model.expected(entry['name'], *args)
                self.assertEqual(answer, reference(entry['name'], args))
                self.assertGreaterEqual(answer, 0)
                self.assertLess(answer, 1 << 37)

    def test_every_lane_observes_unsigned_high_words(self):
        for operation in range(5):
            for lane in range(4):
                observed = {model.expected('laneCase', *args) for args in model.lane_cases()
                            if args[:2] == [operation, lane]}
                self.assertIn(4294967295, observed)
                self.assertNotIn(-1, observed)

    def test_signed_negative_controls_change_only_separate_metadata_copies(self):
        proof = dict(kind='vector', primReps=['VecRep 4 Word32ElemRep'],
                     vector=dict(lanes=4, element='Word32ElemRep'))
        tuple_value = ['tuple', dict(rep=tuple_rep())]
        packed = ['app', ['prim', 'packWord32X4#'], [tuple_value], dict(rep=proof)]
        plus = ['app', ['prim', 'plusWord32X4#'], [packed, copy.deepcopy(packed)], dict(rep=copy.deepcopy(proof))]
        module = dict(bindings=[dict(name='plusCase', expr=plus)])
        saved = copy.deepcopy(module)
        for variant in ('signedLaneTuple', 'signedVectorOperand'):
            altered = prepare.signed_control(module, variant)
            self.assertEqual(module, saved)
            altered_plus = altered['bindings'][0]['expr']
            self.assertEqual(altered_plus[2][1], packed)
            if variant == 'signedLaneTuple':
                lane_proof = prepare.representation(altered_plus[2][0][2][0])
                self.assertEqual(lane_proof['primReps'], ['Int32Rep']*4)
                self.assertFalse(prepare.lane_tuple(lane_proof))
            else:
                vector_proof = prepare.representation(altered_plus[2][0])
                self.assertEqual(vector_proof['vector'], dict(lanes=4, element='Int32ElemRep'))
                self.assertEqual(vector_proof['primReps'], ['VecRep 4 Int32ElemRep'])

    def test_prepared_signed_mutations_match_the_current_auditor(self):
        spec = importlib.util.spec_from_file_location('word32x4_current_audit',
                                                     prepare.ROOT/'scripts/audit-core.py')
        auditor = importlib.util.module_from_spec(spec); spec.loader.exec_module(auditor)
        capabilities = json.loads((prepare.ROOT/'scripts/core-capabilities.json').read_text())
        provenance = json.loads((prepare.OUT/'provenance.json').read_text())
        self.assertIn(provenance['stages'], [['pre'], ['pre', 'post']])
        for stage in provenance['stages']:
            path = prepare.OUT/f'{stage}-core/SimdWord32X4.json'
            module = json.loads(path.read_text())
            self.assertTrue(auditor.Audit([(str(path), module)], capabilities).run(['plusCase'])['accepted'])
            controls = prepare.audit_signed_controls(module, path, auditor, capabilities)
            self.assertEqual(set(controls), {'signedLaneTuple', 'signedVectorOperand'})
            for name, count in [('signedLaneTuple', 10), ('signedVectorOperand', 4)]:
                report = controls[name]['report']
                self.assertFalse(report['accepted'])
                self.assertFalse(report['missingGlobals'])
                self.assertEqual(len(report['issues']), count)

    def test_each_lane_observes_complete_independent_operand_grid(self):
        cases = model.lane_cases()
        self.assertEqual(len(cases), len({tuple(args) for args in cases}))
        for operation in range(5):
            for lane in range(4):
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
            for i in range(4):
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
        for lane in range(4):
            for rep in ('Int32Rep', 'Word16Rep', 'Word8Rep', 'IntRep', 'FloatRep'):
                bad = copy.deepcopy(good)
                bad['components'][lane]['primReps'] = [rep]
                self.assertFalse(prepare.lane_tuple(bad))
        for mutation in ('short', 'long', 'flat', 'kind', 'no_aggregate'):
            bad = copy.deepcopy(good)
            if mutation == 'short': bad['components'].pop()
            elif mutation == 'long': bad['components'].append(copy.deepcopy(bad['components'][0]))
            elif mutation == 'flat': bad['primReps'][-1] = 'Int32Rep'
            elif mutation == 'kind': bad['kind'] = 'object'
            else: bad.pop('aggregate')
            self.assertFalse(prepare.lane_tuple(bad))

    def test_actual_root_proof_rejects_hidden_lambdas_and_wrong_call_boundary(self):
        for name in ('plusCase', 'scalarHelperCase', 'tupleHelperCase'):
            entry, report, module = fixture(name)
            self.assertEqual(prepare.check_guest_structure(entry, report, module)['guestCalls'], 1 if name == 'plusCase' else 2)
            for mutation in ('formal', 'hidden_lambda', 'global', 'vector_count'):
                bad, rr = copy.deepcopy(module), copy.deepcopy(report)
                if mutation == 'formal': bad['bindings'][0]['expr'][1][0]['rep']['primReps'] = ['Word32Rep']
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
                else: bad['bindings'][1]['expr'][-1]['resultRep'] = dict(kind='vector', primReps=['VecRep 4 Word32ElemRep'])
                with self.assertRaises(AssertionError): prepare.check_guest_structure(entry, report, bad)


if __name__ == '__main__':
    unittest.main()
