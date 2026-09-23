#!/usr/bin/env python3
"""Independent exact models, bit-domain controls and strict preparation gates."""
import copy
from fractions import Fraction
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('double_arrays', Path(__file__).with_name('prepare-double-arrays.py'))
model = importlib.util.module_from_spec(spec)
spec.loader.exec_module(model)


def state_root(body):
    void_rep = dict(primReps=[], kind='void', evaluated=True)
    formal = dict(id='test.state', type='State# RealWorld', rep=copy.deepcopy(void_rep),
                  lifted=False, coercion=False)
    return ['lam', [{}], ['app', ['lam', [formal], body, {}],
                          [['void', dict(rep=void_rep)]], [False], False, False, {}], {}]


class DoubleArrayModelTest(unittest.TestCase):
    def test_public_models_against_rational_cell_updates(self):
        for raw in model.inputs():
            x = Fraction((raw & 65535)-32768)
            accum = [x]*8
            for index, value in [(-3, Fraction(13, 4)), (0, Fraction(11, 2)),
                                 (-3, -2), (4, x/2)]:
                accum[index+3] += value
            st = [x]*8
            st[3] = st[0]+Fraction(1, 4)
            st[7] = 3*st[3]-x+Fraction(1, 2)
            for name, cells in [('unboxedDoubleAccum', accum), ('unboxedDoubleST', st)]:
                answer = 4*(7*cells[0]+11*cells[3]+13*cells[7])
                self.assertEqual(answer.denominator, 1)
                self.assertEqual(model.mathematical(name, raw), answer)
                self.assertLess(abs(answer), 1 << 24)

    def test_public_extreme_bounded_seeds(self):
        for raw in (0, 65535, -(1 << 63), (1 << 63)-1):
            x = (raw & 65535)-32768
            self.assertIn(x, (-32768, 32767))
            self.assertEqual(model.mathematical('unboxedDoubleAccum', raw), 150*x+277)
            self.assertEqual(model.mathematical('unboxedDoubleST', raw), 176*x+76)

    def test_movement_domain_and_raw_identity(self):
        values = model.inputs()
        self.assertEqual(values, sorted(set(values)))
        magnitudes = (0, 1, 2, 3, 0xfffffffffffff, 0x10000000000000,
                      0x7fefffffffffffff, 0x7ff0000000000000,
                      0x7ff8000000000000, 0x7ff8000000001234, 0x7fffffffffffffff)
        self.assertTrue({model.signed(x | sign) for x in magnitudes for sign in (0, 1 << 63)} <= set(values))
        for x in values:
            self.assertFalse(model.signaling_nan(x))
            for name in model.HELPERS:
                self.assertEqual(model.mathematical(name, x), x)
        for bit in range(64):
            for delta in (-1, 0, 1):
                x = model.signed((1 << bit)+delta)
                if not model.signaling_nan(x):
                    self.assertIn(x, values)
        for bits in (0x7ff0000000000001, 0x7ff7ffffffffffff, 0xfff0000000001234):
            self.assertTrue(model.signaling_nan(bits))
            for name in model.HELPERS:
                with self.assertRaises(AssertionError): model.mathematical(name, model.signed(bits))

    def test_rows_are_complete_unique_and_known(self):
        values = model.inputs()
        rows = ''.join(f'{name}\t{x}\t{model.mathematical(name,x)}\n' for name in model.ENTRIES for x in values)
        self.assertEqual(len(model.parse_rows(rows, values)), len(values)*4)
        for bad in (rows+rows.splitlines()[0]+'\n', '\n'.join(rows.splitlines()[1:]), rows+'unknown\t0\t0\n'):
            with self.assertRaises(AssertionError): model.parse_rows(bad, values)

    def test_strict_gate_and_exact_movement_counts(self):
        for name, counts in model.EXACT_MOVEMENT.items():
            report = dict(accepted=True, primitives=[dict(name=n, uses=[{}]*v) for n, v in counts.items()])
            model.check_report(name, report)
            for mutation in ('rejected', 'missing', 'extra', 'count'):
                bad = copy.deepcopy(report)
                if mutation == 'rejected': bad['accepted'] = False
                elif mutation == 'missing': bad['primitives'].pop()
                elif mutation == 'extra': bad['primitives'].append(dict(name='+##', uses=[{}]))
                else: bad['primitives'][0]['uses'].pop()
                with self.assertRaises(AssertionError): model.check_report(name, bad)

    def test_structure_requires_genuine_residual_tuple_and_saturated_call(self):
        result = dict(primReps=['DoubleRep'], kind='unknown', evaluated=False, aggregate='unboxed-tuple',
                      components=[dict(primReps=[], kind='void', evaluated=True),
                                  dict(primReps=['DoubleRep'], kind='double', evaluated=True)])
        helper = dict(id='test.readDoubleSlot', name='readDoubleSlot', arity=2,
                      expr=['lam', [{}, {}], ['app', ['prim', 'readDoubleArray#'], []], dict(resultRep=result)])
        root = dict(id='test.moveDoubleBits', name='moveDoubleBits',
                    expr=state_root(['app', ['var', helper['id']], [['var', 'a'], ['var', 's']]]))
        report = dict(roots=[root['id']], reachableBindings=[dict(id=root['id']), dict(id=helper['id'])])
        modules = [('test', dict(bindings=[root, helper]))]
        self.assertEqual(model.check_structure('moveDoubleBits', report, modules), 3)
        for mutation in ('shape', 'lane', 'saturation', 'missing', 'duplicate'):
            bad = copy.deepcopy(modules)
            rr, hh = bad[0][1]['bindings']
            if mutation == 'shape': hh['expr'][-1]['resultRep'].pop('aggregate')
            elif mutation == 'lane': hh['expr'][-1]['resultRep']['components'][1]['primReps'] = ['FloatRep']
            elif mutation == 'saturation': rr['expr'][2][1][2][2].pop()
            elif mutation == 'missing': hh['name'] = 'wrongHelper'
            else: rr['expr'].append(copy.deepcopy(rr['expr'][2]))
            with self.assertRaises(AssertionError): model.check_structure('moveDoubleBits', report, bad)

    def test_structure_counts_exactly_one_immediate_zero_slot_state_lambda(self):
        root = dict(id='test.unboxedDoubleST', name='unboxedDoubleST',
                    expr=state_root(['lit', 'int', '0']))
        report = dict(roots=[root['id']], reachableBindings=[dict(id=root['id'])])
        modules = [('test', dict(bindings=[root]))]
        self.assertEqual(model.check_structure('unboxedDoubleST', report, modules), 2)
        for mutation in ('missing', 'conditional', 'extra', 'formal_count', 'non_state',
                         'nonzero_formal', 'lifted', 'coercion', 'arg_count', 'nonvoid_arg',
                         'nonzero_arg', 'flags'):
            with self.subTest(mutation=mutation):
                bad = copy.deepcopy(modules)
                expr = bad[0][1]['bindings'][0]['expr']
                call = expr[2]
                local = call[1]
                if mutation == 'missing': expr[2] = local[2]
                elif mutation == 'conditional': expr[2] = ['case', ['lit', 'int', '0'], 'v',
                                                          [['default', None, [], call]]]
                elif mutation == 'extra': local[2] = ['lam', [], local[2]]
                elif mutation == 'formal_count': local[1].append(copy.deepcopy(local[1][0]))
                elif mutation == 'non_state': local[1][0]['type'] = 'Proxy# RealWorld'
                elif mutation == 'nonzero_formal': local[1][0]['rep']['primReps'] = ['IntRep']
                elif mutation == 'lifted': local[1][0]['lifted'] = True
                elif mutation == 'coercion': local[1][0]['coercion'] = True
                elif mutation == 'arg_count': call[2].clear()
                elif mutation == 'nonvoid_arg': call[2][0][0] = 'var'
                elif mutation == 'nonzero_arg': call[2][0][-1]['rep']['primReps'] = ['IntRep']
                else: call[3] = [True]
                with self.assertRaises(AssertionError): model.check_structure('unboxedDoubleST', report, bad)

    def test_structure_rejects_lambda_hidden_in_let_binding_dictionary(self):
        leaf = ['lit', 'int', '0']
        hidden = dict(id='test.local', name='local', expr=['lam', [{}], leaf])
        root = dict(id='test.unboxedDoubleST', name='unboxedDoubleST',
                    expr=state_root(['let', False, [hidden], leaf]))
        report = dict(roots=[root['id']], reachableBindings=[dict(id=root['id'])])
        modules = [('test', dict(bindings=[root]))]
        with self.assertRaisesRegex(AssertionError, 'unexpected additional local lambda'):
            model.check_structure('unboxedDoubleST', report, modules)

    def test_only_complete_proven_join_prefix_is_exempt_not_its_body(self):
        leaf = ['lit', 'int', '0']
        result = dict(primReps=['IntRep'], kind='long', evaluated=False)
        join = dict(id='test.join', name='join', joinValueArity=1, joinResultRep=result,
                    info=dict(joinArity=1),
                    expr=['lam', [{}], leaf, dict(resultRep=copy.deepcopy(result))])
        root = dict(id='test.unboxedDoubleST', name='unboxedDoubleST',
                    expr=state_root(['let', False, [join], ['app', ['var', 'test.join'], [leaf]]]))
        report = dict(roots=[root['id']], reachableBindings=[dict(id=root['id'])])
        modules = [('test', dict(bindings=[root]))]
        self.assertEqual(model.check_structure('unboxedDoubleST', report, modules), 2)
        for mutation in ('diagnostic_only', 'partial', 'oversized', 'zero', 'boolean',
                         'fractional', 'missing_result', 'empty_result', 'wrong_result', 'nested_lambda'):
            with self.subTest(mutation=mutation):
                bad = copy.deepcopy(modules)
                binding = bad[0][1]['bindings'][0]['expr'][2][1][2][2][0]
                if mutation == 'diagnostic_only': binding.pop('joinValueArity')
                elif mutation == 'partial': binding['expr'][1].append({})
                elif mutation == 'oversized': binding['joinValueArity'] = 2
                elif mutation == 'zero': binding['joinValueArity'] = 0
                elif mutation == 'boolean': binding['joinValueArity'] = True
                elif mutation == 'fractional': binding['joinValueArity'] = 1.5
                elif mutation == 'missing_result': binding.pop('joinResultRep')
                elif mutation == 'empty_result':
                    binding['joinResultRep'] = {}
                    binding['expr'][-1]['resultRep'] = {}
                elif mutation == 'wrong_result': binding['joinResultRep']['primReps'] = ['DoubleRep']
                else: binding['expr'][2] = ['lam', [{}], leaf]
                with self.assertRaises(AssertionError): model.check_structure('unboxedDoubleST', report, bad)


if __name__ == '__main__':
    unittest.main()
