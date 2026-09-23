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
                    expr=['lam', [{}], ['app', ['var', helper['id']], [['var', 'a'], ['var', 's']]]])
        report = dict(roots=[root['id']], reachableBindings=[dict(id=root['id']), dict(id=helper['id'])])
        modules = [('test', dict(bindings=[root, helper]))]
        self.assertEqual(model.check_structure('moveDoubleBits', report, modules), 2)
        for mutation in ('shape', 'lane', 'saturation', 'missing', 'duplicate'):
            bad = copy.deepcopy(modules)
            rr, hh = bad[0][1]['bindings']
            if mutation == 'shape': hh['expr'][-1]['resultRep'].pop('aggregate')
            elif mutation == 'lane': hh['expr'][-1]['resultRep']['components'][1]['primReps'] = ['FloatRep']
            elif mutation == 'saturation': rr['expr'][2][2].pop()
            elif mutation == 'missing': hh['name'] = 'wrongHelper'
            else: rr['expr'].append(copy.deepcopy(rr['expr'][2]))
            with self.assertRaises(AssertionError): model.check_structure('moveDoubleBits', report, bad)


if __name__ == '__main__':
    unittest.main()
