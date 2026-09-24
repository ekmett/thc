#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""The generated signatures, fixed layouts and stale-source gate agree."""
import copy
import importlib.util
import json
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch

from core_vectors import OPERATIONS, proof_error, signature_matches
from simd_family_model import cases, entries, float_operation, result

ROOT = Path(__file__).resolve().parent.parent
SPEC = importlib.util.spec_from_file_location('simd_generator', ROOT / 'scripts/generate-simd-families.py')
GEN = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GEN)


class SimdFamiliesTest(unittest.TestCase):
    def test_independent_ieee_model_signed_zeros_subnormals_and_ties(self):
        # Known bit identities include ties-to-even at the subnormal boundary.
        for width, one, two, sign, infinity, nan in (
            (32, 0x3f800000, 0x40000000, 0x80000000, 0x7f800000, 0x7fc00000),
            (64, 0x3ff0000000000000, 0x4000000000000000, 1 << 63, 0x7ff0000000000000, 0x7ff8000000000000)):
            self.assertEqual(0, float_operation('divide', 1, two, width))
            self.assertEqual(2, float_operation('divide', 3, two, width))
            self.assertEqual(sign, float_operation('divide', sign, one, width))
            self.assertEqual(infinity | sign, float_operation('divide', one | sign, 0, width))
            self.assertEqual(nan, float_operation('divide', 0, 0, width))
            self.assertEqual(nan, float_operation('divide', infinity, infinity, width))
            self.assertEqual(sign | one, float_operation('negate', one, 0, width))
            self.assertEqual(nan, float_operation('negate', infinity | 1, 0, width))

    def test_rational_division_matches_independent_host_finite_controls(self):
        for code, width, values in [('f', 32, [0x3f800001, 0x3f7fffff, 1, 3, 0x00800000, 0x7f7fffff]),
                                    ('d', 64, [0x3ff0000000000001, 0x3fefffffffffffff, 1, 3, 0x0010000000000000, 0x7fefffffffffffff])]:
            for left in values:
                for right in values:
                    a = struct.unpack('>' + code, left.to_bytes(width // 8, 'big'))[0]
                    b = struct.unpack('>' + code, right.to_bytes(width // 8, 'big'))[0]
                    result = a / b
                    try:
                        bits = int.from_bytes(struct.pack('>' + code, result), 'big')
                    except OverflowError:
                        bits = 0x7f800000
                    self.assertEqual(bits, float_operation('divide', left, right, width))

    def test_floating_composites_cover_finite_arithmetic_and_each_lane(self):
        for width, one, two, three, half, sign in (
            (32, 0x3f800000, 0x40000000, 0x40400000, 0x3f000000, 0x80000000),
            (64, 0x3ff0000000000000, 0x4000000000000000, 0x4008000000000000,
             0x3fe0000000000000, 1 << 63)):
            self.assertEqual(one, float_operation('broadcast', one, 0, width))
            self.assertEqual(three, float_operation('plus', one, two, width))
            self.assertEqual(one, float_operation('minus', two, one, width))
            self.assertEqual(one, float_operation('times', two, half, width))
            self.assertEqual(sign, float_operation('minus', sign, 0, width))
        families = [f for f in GEN.families() if f.get('composite')]
        self.assertEqual(['FloatX8', 'DoubleX4'], [f['name'] for f in families])
        self.assertEqual({'floatX8Composite', 'doubleX4Composite'},
                         {name for name, _, operation in entries() if operation == 'composite'})
        source = GEN.fixture_sources(families)['fixtures/GeneratedSimdFamilies.hs']
        for family in families:
            name = family['name'][0].lower() + family['name'][1:] + 'Composite'
            branches = source.split(name + 'Worker selector a b = case selector of\n', 1)[1].split('\n  _ -> 0#', 1)[0]
            operations = [op for op in family['operations'] if op not in ('pack', 'unpack')]
            self.assertEqual(family['lanes'] * len(operations), branches.count(' -> case unpack'))
            for operation in operations:
                self.assertIn(operation + family['name'] + '#', branches)
                self.assertEqual(family['lanes'] * (28 if operation in ('broadcast', 'negate') else 28 * 28),
                                 len(cases(family, operation)))

    def test_exact_machine_contracts_and_recursive_lanes(self):
        families = GEN.families()
        self.assertEqual(47, len(GEN.contracts(families)))
        for family in families:
            for operation in family['operations']:
                name = operation + family['name'] + '#'
                arguments, result = OPERATIONS[name]
                contract = GEN.contracts([family])[name]
                self.assertEqual(contract['reps'], [p['primReps'] for p in arguments + [result]])
                self.assertEqual(contract['tuples'], ['aggregate' in p for p in arguments + [result]])
                for proof in arguments + [result]:
                    self.assertIsNone(proof_error(proof))
                    self.assertTrue(signature_matches(proof, copy.deepcopy(proof)))
                    wrong = copy.deepcopy(proof)
                    wrong['primReps'] = ['WordRep']
                    self.assertFalse(signature_matches(proof, wrong))
        packed = OPERATIONS['packInt32X16#'][0][0]
        wrong = copy.deepcopy(packed)
        wrong['components'][7] = dict(kind='long', evaluated=True, primReps=['Word32Rep'])
        self.assertFalse(signature_matches(packed, wrong))

    def test_new_carriers_have_only_fixed_primitive_lane_fields(self):
        for family in GEN.families():
            if not family['newCarrier']:
                continue
            source = GEN.carrier(family)
            primitive = GEN.LANES[family['laneRep']][0]
            self.assertEqual(family['lanes'], source.count(f'public final {primitive} lane'))
            self.assertIn(f'.SPECIES_{family["bits"]}', source)
            for forbidden in ('Object', '[]', 'SPECIES_PREFERRED', 'VectorSpecies'):
                self.assertNotIn(forbidden, source)

    def test_word32_lanes_observe_unsigned_bits_in_both_backends(self):
        family = next(f for f in GEN.families() if f['name'] == 'Word32X8')
        self.assertEqual((1 << 32) - 1 + 17, result(family, 'broadcast', 0, -1, 0))
        self.assertEqual((1 << 32) - 2 + 17, result(family, 'plus', 0, -1, -1))
        ast = GEN.ast_code([family])
        bytecode = GEN.bytecode_nodes([family])
        for i in range(family['lanes']):
            self.assertIn(f'value.lane{i}.toLong() and 0xffff_ffffL', ast)
            self.assertIn(f'value.lane{i} & 0xffff_ffffL', bytecode)

    def test_contradictory_tables_are_rejected(self):
        original = json.loads(GEN.SPEC.read_text())
        variants = [dict(lanes=True), dict(lanes=4), dict(bits=512), dict(element='Word32ElemRep'),
                    dict(laneRep='WordRep'), dict(newCarrier=1), dict(operations=['times', 'times']),
                    dict(operations=['divide']), dict(operations=['shuffle'])]
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'families.json'
            for mutation in variants:
                value = copy.deepcopy(original)
                value['families'][0].update(mutation)
                path.write_text(json.dumps(value))
                with patch.object(GEN, 'SPEC', path), self.assertRaises(ValueError):
                    GEN.families()

    def test_generated_regions_cannot_silently_drift(self):
        families = GEN.families()
        GEN.region(ROOT / 'src/main/java/thc/runtime/BytecodeRoot.java', GEN.bytecode_nodes(families), False)
        GEN.region(ROOT / 'src/main/kotlin/thc/runtime/BytecodeProgram.kt', GEN.bytecode_emitter(families), False)
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'sample.java'
            path.write_text('    // BEGIN GENERATED SIMD FAMILIES\nwrong\n    // END GENERATED SIMD FAMILIES\n')
            before = path.read_bytes()
            with self.assertRaisesRegex(ValueError, 'Stale generated region'):
                GEN.region(path, 'right\n', False)
            self.assertEqual(before, path.read_bytes())


if __name__ == '__main__':
    unittest.main()
