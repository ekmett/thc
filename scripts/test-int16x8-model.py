#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Independent per-lane integer checks and strict fixture-proof negative controls."""
import copy
import importlib.util
from pathlib import Path
import unittest
import int16x8_model as model

spec = importlib.util.spec_from_file_location('int16x8_prepare', Path(__file__).with_name('prepare-int16x8-audit.py'))
prepare = importlib.util.module_from_spec(spec); spec.loader.exec_module(prepare)


def reference(name, args):
    def signed(bits):
        bits &= 0xffff
        return (bits ^ 0x8000)-0x8000
    if name == 'laneCase':
        operation, selected, a, b = args
        name = model.OPERATIONS[operation]
    else:
        selected = None
        a, b = args
    helper = name
    name = {'scalarHelperCase': 'plusCase', 'tupleHelperCase': 'timesCase'}.get(name, name)
    x = [a, b, a+1, b-1, a+32767, b-32768, a*3+7, b*5-11]
    y = [b+2, a-3, b*7+13, a*11-17, b+32768, a-32767, b*13+19, a*17-23]
    values = []
    for i in range(8):
        p, q = x[i] & 0xffff, y[i] & 0xffff
        if name == 'plusCase': result = p+q
        elif name == 'minusCase': result = p-q
        elif name == 'timesCase': result = p*q
        elif name == 'negateCase': result = -p
        elif name == 'packCase': result = p
        else: result = a-b+29
        values.append(signed(result))
    if selected is not None: return values[selected]
    score = 3*values[0]+5*values[1]+7*values[2]+11*values[3]+13*values[4]+17*values[5]+19*values[6]+23*values[7]
    return score+48 if helper == 'scalarHelperCase' else score


def result_rep():
    return dict(kind='long', primReps=['IntRep'], evaluated=False)


def tuple_rep():
    return dict(kind='unknown', primReps=['Int16Rep']*8, aggregate='unboxed-tuple',
                components=[dict(kind='long', primReps=['Int16Rep'], evaluated=True) for _ in range(8)])


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


class Int16X8ModelTest(unittest.TestCase):
    def test_every_narrow_encoding_and_extreme_arithmetic(self):
        for bits in range(65536):
            self.assertEqual(model.narrow(bits), (bits ^ 0x8000)-0x8000)
            self.assertEqual(model.narrow(bits), model.narrow(bits+(1 << 48)))
        self.assertEqual(model.narrow(32767+1), -32768)
        self.assertEqual(model.narrow(-32768-1), 32767)
        self.assertEqual(model.narrow(-(-32768)), -32768)
        self.assertEqual(model.narrow(32767*32767), 1)
        self.assertEqual(model.narrow(-32768*-1), -32768)

    def test_all_native_expected_rows_against_separate_lane_implementation(self):
        self.assertEqual(len(model.model_rows()), 6032)
        for entry in model.entries():
            for args in entry['cases']:
                answer = model.expected(entry['name'], *args)
                self.assertEqual(answer, reference(entry['name'], args))
                self.assertLess(abs(answer), 1 << 22)

    def test_each_lane_observes_complete_independent_operand_grid(self):
        cases = model.lane_cases()
        self.assertEqual(len(cases), len({tuple(args) for args in cases}))
        for operation in range(6):
            for lane in range(8):
                rows = [args for args in cases if args[:2] == [operation, lane]]
                self.assertEqual(len(rows), 81)
                if operation == 5:
                    self.assertEqual({model.expected('laneCase', *args) for args in rows}, set(model.EDGE))
                else:
                    self.assertEqual({tuple(values[lane] for values in model.operands(*args[2:])) for args in rows},
                                     {(a, b) for a in model.EDGE for b in model.EDGE})

    def test_lane_order_and_modulo_input_narrowing_are_observable(self):
        for name in model.OPERATIONS[:-1]:
            lanes = model.result_lanes(name, 12345, -6789)
            self.assertGreater(len(set(lanes)), 1)
            score = sum(w*x for w, x in zip(model.WEIGHTS, lanes))
            for i in range(8):
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
        for lane in range(8):
            for rep in ('Word16Rep', 'Int32Rep', 'IntRep', 'FloatRep'):
                bad = copy.deepcopy(good)
                bad['components'][lane]['primReps'] = [rep]
                self.assertFalse(prepare.lane_tuple(bad))
        for mutation in ('short', 'long', 'flat', 'kind', 'no_aggregate'):
            bad = copy.deepcopy(good)
            if mutation == 'short': bad['components'].pop()
            elif mutation == 'long': bad['components'].append(copy.deepcopy(bad['components'][0]))
            elif mutation == 'flat': bad['primReps'][-1] = 'Word16Rep'
            elif mutation == 'kind': bad['kind'] = 'object'
            else: bad.pop('aggregate')
            self.assertFalse(prepare.lane_tuple(bad))

    def test_actual_root_proof_rejects_hidden_lambdas_and_wrong_call_boundary(self):
        for name in ('plusCase', 'scalarHelperCase', 'tupleHelperCase'):
            entry, report, module = fixture(name)
            self.assertEqual(prepare.check_guest_structure(entry, report, module)['guestCalls'], 1 if name == 'plusCase' else 2)
            for mutation in ('formal', 'hidden_lambda', 'global', 'vector_count'):
                bad, rr = copy.deepcopy(module), copy.deepcopy(report)
                if mutation == 'formal': bad['bindings'][0]['expr'][1][0]['rep']['primReps'] = ['Int16Rep']
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
                else: bad['bindings'][1]['expr'][-1]['resultRep'] = dict(kind='vector', primReps=['VecRep 8 Int16ElemRep'])
                with self.assertRaises(AssertionError): prepare.check_guest_structure(entry, report, bad)


if __name__ == '__main__':
    unittest.main()
