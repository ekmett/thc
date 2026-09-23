#!/usr/bin/env python3
"""Small independent edge controls for the FloatX4 preparation model."""
import importlib.util
import copy
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('floatx4_model', Path(__file__).with_name('prepare-floatx4-audit.py'))
model = importlib.util.module_from_spec(spec)
spec.loader.exec_module(model)


class FloatX4ModelTest(unittest.TestCase):
    def test_round_to_even_and_subnormal_underflow(self):
        self.assertEqual(model.f32(16777216.0+1.0), 16777216.0)
        self.assertEqual(model.f32(16777216.0+3.0), 16777220.0)
        self.assertEqual(model.bits(model.f32(model.from_bits(1)*0.5)), 0)
        self.assertEqual(model.bits(model.f32(model.from_bits(0x80000001)*0.5)), 0x80000000)
        self.assertEqual(model.bits(model.f32(model.from_bits(3)*0.5)), 2)

    def test_ieee_and_lane_order(self):
        self.assertEqual(model.classes([0.0, -0.0, float('inf'), float('nan')]), 0x0321)
        self.assertNotEqual(model.classes([0.0, -0.0, float('inf'), float('nan')]),
                            model.classes([-0.0, 0.0, float('inf'), float('nan')]))
        self.assertEqual(model.classify(model.f32(float('inf') + float('-inf'))), 0)
        self.assertEqual(model.classify(model.f32(0.0 * float('inf'))), 0)
        self.assertEqual(model.classify(model.f32(model.from_bits(0x7f7fffFF) * 2)), 3)

    def test_non_fma(self):
        self.assertEqual(model.expected('nonFmaCase', 0), 0x1111)
        self.assertEqual(model.classes([-2.0**-46]*4), 0xdddd)
        self.assertNotEqual(model.expected('nonFmaCase', 0), model.classes([-2.0**-46]*4))

    def test_finite_signatures(self):
        self.assertEqual(model.expected('plusCase', 0, 0, 0, 0), 7*4 ^ 11*4 ^ 13*4 ^ 17*4)
        self.assertEqual(model.expected('minusCase', 0, 0, 0, 0), 7*-2 ^ 11*-2 ^ 13*-2 ^ 17*-2)
        self.assertEqual(model.expected('timesCase', 2, 3, 4, 8), 7*4 ^ 11*-24 ^ 13*24 ^ 17*-8)

    def test_manifest_and_row_validation(self):
        rows = model.model_rows()
        self.assertEqual(len(rows), 2196)
        text = ''.join('\t'.join(map(str, (*key, value)))+'\n' for key, value in rows.items())
        self.assertEqual(model.parse_rows(text), rows)
        with self.assertRaises(AssertionError):
            model.parse_rows(text+text.splitlines()[0]+'\n')
        for e in model.entries():
            self.assertTrue(all(len(xs) == e['arity'] for xs in e['cases']))
            if e['name'].startswith('edge'):
                for lane in range(4):
                    self.assertEqual({(xs[lane], xs[4]) for xs in e['cases']},
                                     {(a, b) for a in range(21) for b in range(21)})

    def test_structural_guards_reject_mutated_proofs(self):
        # Synthetic metadata controls for inventory(), not a claim of valid Core.
        lane = dict(kind='float', primReps=['FloatRep'])
        scalar = dict(kind='long', primReps=['IntRep'])
        vector = dict(kind='vector', primReps=['VecRep 4 FloatElemRep'],
                      vector=dict(lanes=4, element='FloatElemRep'))
        tuple_rep = dict(kind='unknown', aggregate='unboxed-tuple',
                         primReps=['FloatRep']*4, components=[lane]*4)
        calls = []
        for primitive in sorted(model.PRIMITIVES):
            argument = tuple_rep if primitive == 'packFloatX4#' else vector
            result = tuple_rep if primitive == 'unpackFloatX4#' else vector
            calls.append(['app', ['prim', primitive], [['synthetic', dict(rep=argument)]], dict(rep=result)])
        bindings = [dict(name=e['name'], expr=['lam', [dict(rep=scalar)]*e['arity'],
                    calls, dict(resultRep=scalar)]) for e in model.entries()]
        bindings.append(dict(name='vectorArgument', expr=['lam', [dict(rep=vector)],
                        [], dict(resultRep=vector)]))
        fixture = dict(boundary=model.STAGES['pre'], bindings=bindings)
        self.assertEqual(model.inventory(fixture, 'pre')['primitives'], sorted(model.PRIMITIVES))

        bad = copy.deepcopy(fixture)
        pack = next(c for c in bad['bindings'][0]['expr'][2] if c[1][1] == 'packFloatX4#')
        pack[2] *= 4
        with self.assertRaisesRegex(AssertionError, 'ONE logical tuple'):
            model.inventory(bad, 'pre')
        bad = copy.deepcopy(fixture)
        pack = next(c for c in bad['bindings'][0]['expr'][2] if c[1][1] == 'packFloatX4#')
        pack[2][0][-1]['rep']['components'][0]['kind'] = 'long'
        with self.assertRaisesRegex(AssertionError, 'four Float# tuple leaves'):
            model.inventory(bad, 'pre')
        bad = copy.deepcopy(fixture)
        unpack = next(c for c in bad['bindings'][0]['expr'][2] if c[1][1] == 'unpackFloatX4#')
        unpack[-1]['rep'] = copy.deepcopy(tuple_rep)
        unpack[-1]['rep']['components'][0]['kind'] = 'long'
        with self.assertRaisesRegex(AssertionError, 'Unpack result'):
            model.inventory(bad, 'pre')
        bad = copy.deepcopy(fixture)
        bad['bindings'][-1]['expr'][1][0]['rep']['vector']['lanes'] = 8
        with self.assertRaisesRegex(AssertionError, 'inexact FloatX4'):
            model.inventory(bad, 'pre')
        bad = copy.deepcopy(fixture)
        bad['bindings'][0]['expr'][1].append(dict(rep=scalar))
        with self.assertRaisesRegex(AssertionError, 'Host entry ABI drift'):
            model.inventory(bad, 'pre')


if __name__ == '__main__':
    unittest.main()
