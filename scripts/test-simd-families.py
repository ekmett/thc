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
from simd_family_model import cases, entries, float_operation, result, signed

ROOT = Path(__file__).resolve().parent.parent
SPEC = importlib.util.spec_from_file_location('simd_generator', ROOT / 'scripts/generate-simd-families.py')
GEN = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GEN)
AUDIT_SPEC = importlib.util.spec_from_file_location('simd_audit', ROOT / 'scripts/audit-core.py')
AUDIT = importlib.util.module_from_spec(AUDIT_SPEC)
AUDIT_SPEC.loader.exec_module(AUDIT)


class SimdFamiliesTest(unittest.TestCase):
    def test_canonical_capability_keeps_public_host_vector_arguments_excluded(self):
        capability = json.loads((ROOT / 'scripts/core-capabilities.json').read_text())
        contracts = GEN.contracts(GEN.families())
        self.assertEqual({name: proof['arity'] for name, proof in contracts.items()},
                         {name: capability['primitives'].get(name) for name in contracts})
        declared = {(shape['lanes'], shape['element']) for shape in capability['vectorRepresentations']}
        closure = dict(kind='closure', evaluated=True, primReps=['BoxedRep (Just Lifted)'])
        scalar = dict(kind='long', evaluated=True, primReps=['IntRep'])
        for family in GEN.families():
            if not family['newCarrier']:
                continue
            self.assertIn((family['lanes'], family['element']), declared)
            vector = dict(kind='vector', evaluated=True,
                          primReps=[f"VecRep {family['lanes']} {family['element']}"],
                          vector=dict(lanes=family['lanes'], element=family['element']))
            source = dict(schema=1, ghc='9.14.1', constructors=[], bindings=[
                dict(id='root', name='root', lifted=True, arity=1, rep=closure,
                     expr=['lam', [dict(id='vector', lifted=False, rep=vector)],
                           ['lit', 'int', '1', dict(rep=scalar)],
                           dict(rep=closure, resultRep=scalar)])])
            report = AUDIT.Audit([('simd-formal', source)], capability).run(['root'])
            self.assertFalse(report['accepted'], family['name'])
            detail = 'vector host argument' if 'arguments' in capability.get('vectorTransport', []) else 'vector formal argument'
            self.assertIn(('vector-boundary', detail),
                          {(issue['code'], issue['detail']) for issue in report['issues']}, family['name'])

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

    def test_infinity_products_distinguish_infinity_from_signed_zero(self):
        for width, one, sign, infinity, nan in (
            (32, 0x3f800000, 0x80000000, 0x7f800000, 0x7fc00000),
            (64, 0x3ff0000000000000, 1 << 63, 0x7ff0000000000000, 0x7ff8000000000000)):
            for left_sign in (0, sign):
                for right_sign in (0, sign):
                    expected_infinity = infinity | (left_sign ^ right_sign)
                    self.assertEqual(expected_infinity,
                                     float_operation('times', infinity | left_sign, infinity | right_sign, width))
                    self.assertEqual(expected_infinity,
                                     float_operation('times', one | left_sign, infinity | right_sign, width))
                    self.assertEqual(expected_infinity,
                                     float_operation('times', infinity | left_sign, one | right_sign, width))
                    self.assertEqual(nan, float_operation('times', infinity | left_sign, right_sign, width))
                    self.assertEqual(nan, float_operation('times', left_sign, infinity | right_sign, width))

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
        families = [f for f in GEN.families() if f.get('composite') and f['laneRep'] in ('FloatRep', 'DoubleRep')]
        self.assertEqual(['FloatX8', 'DoubleX4', 'FloatX16', 'DoubleX8'], [f['name'] for f in families])
        self.assertEqual({'floatX8Composite', 'doubleX4Composite', 'floatX16Composite', 'doubleX8Composite'},
                         {name for name, f, operation in entries() if operation == 'composite' and f['laneRep'] in ('FloatRep', 'DoubleRep')})
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
        self.assertEqual(160, len(GEN.contracts(families)))
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
            self.assertEqual(family['lanes'], source.count('@JvmField val lane'))
            self.assertIn(f'.SPECIES_{family["bits"]}', source)
            for forbidden in ('Object', '[]', 'SPECIES_PREFERRED', 'VectorSpecies'):
                self.assertNotIn(forbidden, source)

    def test_narrow_lanes_extend_with_exact_signedness_in_both_backends(self):
        for name, width, mask in [('Word16X16', 16, '0xffffL'), ('Word32X8', 32, '0xffff_ffffL')]:
            family = next(f for f in GEN.families() if f['name'] == name)
            self.assertEqual((1 << width) - 1 + 17, result(family, 'broadcast', 0, -1, 0))
            self.assertEqual((1 << width) - 2 + 17, result(family, 'plus', 0, -1, -1))
            self.assertEqual(17, result(family, 'times', 0, 1 << (width - 1), 2))
            ast, bytecode = GEN.ast_code([family]), GEN.bytecode_nodes([family])
            for i in range(family['lanes']):
                self.assertIn(f'value.lane{i}.toLong() and {mask}', ast)
                self.assertIn(f'value.lane{i} & {mask}', bytecode)
        family = next(f for f in GEN.families() if f['name'] == 'Int16X16')
        self.assertEqual(-1 + 17, result(family, 'broadcast', 0, 65535, 0))
        self.assertEqual(-32768 + 17, result(family, 'plus', 0, 32767, 1))
        self.assertEqual(-32768 + 17, result(family, 'negate', 0, -32768, 0))
        ast, bytecode = GEN.ast_code([family]), GEN.bytecode_nodes([family])
        for i in range(family['lanes']):
            self.assertIn(f'frame, slots[offset + {i}], value.lane{i}.toLong())', ast)
            self.assertIn(f'frame, value.lane{i});', bytecode)

    def test_signed_min_max_select_high_bit_lanes(self):
        for name in ('Int8X16', 'Int16X8', 'Int32X4', 'Int32X8', 'Int64X2', 'Int64X4', 'Int64X8'):
            family = next(f for f in GEN.families() if f['name'] == name)
            width = family['bits'] // family['lanes']
            low, high = -(1 << (width - 1)), (1 << (width - 1)) - 1
            for lane in (0, family['lanes'] - 1):
                a, b = low - lane * 104729, high + lane * 7919
                self.assertEqual(low + 17, result(family, 'min', lane, a, b))
                self.assertEqual(signed(high + 17), result(family, 'max', lane, a, b))

    def test_unsigned_min_max_select_high_bit_lanes(self):
        for name in ('Word8X16', 'Word16X8', 'Word32X4', 'Word32X8', 'Word64X2', 'Word64X4', 'Word64X8'):
            family = next(f for f in GEN.families() if f['name'] == name)
            width = family['bits'] // family['lanes']
            high = (1 << width) - 1
            for lane in (0, family['lanes'] - 1):
                a, b = high - lane * 104729, lane * 7919
                self.assertEqual(17, result(family, 'min', lane, a, b))
                self.assertEqual(signed(high + 17), result(family, 'max', lane, a, b))
                a, b = (1 << (width - 1)) - lane * 104729, (high >> 1) + lane * 7919
                self.assertEqual(signed((high >> 1) + 17), result(family, 'min', lane, a, b))
                self.assertEqual(signed((1 << (width - 1)) + 17), result(family, 'max', lane, a, b))

    def test_smoke_composites_bound_compilation_work_and_cover_every_selector(self):
        families = GEN.families()
        entries = GEN.smoke_entries(families)
        groups = GEN.smoke_groups(families)
        self.assertEqual(list(range(len(entries))), sorted(i for group in groups.values() for i in group))
        for group in groups.values():
            self.assertLessEqual(sum(entries[i][0]['lanes'] for i in group), 112)

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
