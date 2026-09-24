#!/usr/bin/env python3
"""Check lane conversion contracts without changing the canonical family table."""
import importlib.util
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parent.parent
SPEC = importlib.util.spec_from_file_location('simd_generator', ROOT / 'scripts/generate-simd-families.py')
GEN = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GEN)

# Independent expectations: durable JVM lane type, Vector API class, runtime
# accessor, narrowing suffix, Kotlin unpack, Java unpack, GHC input/output.
LANE_CASES = {
    'Int8Rep': ('byte', 'ByteVector', 'Long', '.toByte()', 'value.toLong()', 'value',
                'intToInt8# (value)', 'int8ToInt# value'),
    'Int16Rep': ('short', 'ShortVector', 'Long', '.toShort()', 'value.toLong()', 'value',
                 'intToInt16# (value)', 'int16ToInt# value'),
    'Int32Rep': ('int', 'IntVector', 'Long', '.toInt()', 'value.toLong()', 'value',
                 'intToInt32# (value)', 'int32ToInt# value'),
    'Int64Rep': ('long', 'LongVector', 'Long', '', 'value', 'value',
                 'intToInt64# (value)', 'int64ToInt# value'),
    'Word8Rep': ('byte', 'ByteVector', 'Long', '.toByte()', 'value.toLong() and 255L', 'Byte.toUnsignedLong(value)',
                 'wordToWord8# (int2Word# (value))', 'word2Int# (word8ToWord# value)'),
    'Word16Rep': ('short', 'ShortVector', 'Long', '.toShort()', 'value.toLong() and 65535L', 'Short.toUnsignedLong(value)',
                  'wordToWord16# (int2Word# (value))', 'word2Int# (word16ToWord# value)'),
    'Word32Rep': ('int', 'IntVector', 'Long', '.toInt()', 'value.toLong() and 4294967295L', 'Integer.toUnsignedLong(value)',
                  'wordToWord32# (int2Word# (value))', 'word2Int# (word32ToWord# value)'),
    'Word64Rep': ('long', 'LongVector', 'Long', '', 'value', 'value',
                  'wordToWord64# (int2Word# (value))', 'word2Int# (word64ToWord# value)'),
    'FloatRep': ('float', 'FloatVector', 'Float', '', 'value', 'value',
                 'castWord32ToFloat# (wordToWord32# (int2Word# (value)))', 'bitsFloat value'),
    'DoubleRep': ('double', 'DoubleVector', 'Double', '', 'value', 'value',
                  'castWord64ToDouble# (wordToWord64# (int2Word# (value)))', 'bitsDouble value'),
}


def family(rep, bits=256):
    width = {'byte': 8, 'short': 16, 'int': 32, 'long': 64, 'float': 32, 'double': 64}[LANE_CASES[rep][0]]
    stem = rep.removesuffix('Rep')
    operations = ['pack', 'unpack', 'broadcast', 'plus', 'minus', 'times']
    if not rep.startswith('Word'):
        operations.append('negate')
    if rep in ('FloatRep', 'DoubleRep'):
        operations.append('divide')
    return dict(name=f'{stem}X{bits // width}', element=stem + 'ElemRep', laneRep=rep,
                lanes=bits // width, bits=bits, newCarrier=True, operations=operations)


class SimdGeneratorLanesTest(unittest.TestCase):
    def test_all_ten_scalar_representations_keep_exact_carrier_width(self):
        self.assertEqual(set(LANE_CASES), set(GEN.LANES))
        for rep, (primitive, vector, access, *_) in LANE_CASES.items():
            with self.subTest(rep=rep):
                self.assertEqual(4, len(GEN.LANES[rep]))
                self.assertEqual((primitive, vector, access), (GEN.LANES[rep][0], *GEN.LANES[rep][2:]))
                for bits in (128, 256, 512):
                    f = family(rep, bits)
                    source = GEN.carrier(f)
                    self.assertEqual(f['lanes'], source.count(f'public final {primitive} lane'))
                    self.assertIn(f'{vector}.SPECIES_{bits}', source)
                    self.assertIn(f'public {f["name"]}({primitive} lane0,', source)
                    self.assertIn(f'.withLane({f["lanes"] - 1}, lane{f["lanes"] - 1})', source)
                    for forbidden in ('Object', '[]', 'SPECIES_PREFERRED', 'VectorSpecies'):
                        self.assertNotIn(forbidden, source)

    def test_ast_narrows_inputs_and_preserves_signedness_in_every_output_lane(self):
        for rep, (_, _, access, suffix, kotlin_wide, *_) in LANE_CASES.items():
            with self.subTest(rep=rep):
                f = family(rep)
                source = GEN.ast_code([f])
                for i in range(f['lanes']):
                    self.assertIn(f'frame.get{access}(slots[{i}]){suffix}', source)
                    output = kotlin_wide.replace('value', f'value.lane{i}')
                    self.assertIn(f'FrameAccess.write{access}(frame, slots[offset + {i}], {output})', source)
                self.assertIn(f'{f["name"]}.broadcast(arguments[0].executeRequired{access}(frame){suffix})', source)

    def test_bytecode_narrows_inputs_and_zero_extends_only_subword_unsigned_lanes(self):
        for rep, (primitive, _, access, _, _, java_wide, *_) in LANE_CASES.items():
            with self.subTest(rep=rep):
                f = family(rep)
                source = GEN.bytecode_nodes([f])
                cast = f'({primitive}) ' if primitive in ('byte', 'short', 'int') else ''
                arguments = ', '.join(cast + f'lane{i}' for i in range(f['lanes']))
                self.assertIn(f'new {f["name"]}({arguments})', source)
                self.assertIn(f'{f["name"]}.broadcast({cast}value)', source)
                for i in range(f['lanes']):
                    output = java_wide.replace('value', f'value.lane{i}')
                    self.assertIn(f'lane{i}.set{access}(bytecode, frame, {output});', source)
                self.assertEqual(f['lanes'] if rep in ('Word8Rep', 'Word16Rep', 'Word32Rep') else 0,
                                 source.count('.toUnsignedLong('))

    def test_haskell_conversions_and_all_observed_lane_positions(self):
        for rep, (*_, ghc_input, ghc_output) in LANE_CASES.items():
            with self.subTest(rep=rep):
                f = family(rep)
                source = GEN.fixture_sources([f])['fixtures/GeneratedSimdFamilies.hs']
                self.assertIn(f'broadcast{f["name"]}# ({ghc_input.replace("value", "a")})', source)
                for i in range(f['lanes']):
                    label = f'{i}#' if i < f['lanes'] - 1 else '_'
                    self.assertIn(f'{label} -> {ghc_output.replace("value", f"p{i}")}', source)
                self.assertNotIn(' . ', source)  # Unlifted conversion is explicit nested application.

    def test_signed_fragment_has_42_exact_tuple_and_scalar_contracts(self):
        fs = GEN.families(ROOT / 'scripts/simd-signed-families.json')
        self.assertEqual(['Int8X32', 'Int8X64', 'Int16X16', 'Int16X32', 'Int64X4', 'Int64X8'],
                         [f['name'] for f in fs])
        contracts = GEN.contracts(fs)
        self.assertEqual(42, len(contracts))
        for f in fs:
            n = f['name']; rep = f['laneRep']; count = f['lanes']
            self.assertEqual(['pack', 'unpack', 'broadcast', 'plus', 'minus', 'times', 'negate'], f['operations'])
            vector = [f'VecRep {count} {f["element"]}']
            lanes = [rep] * count
            for op in f['operations']:
                with self.subTest(family=n, operation=op):
                    args = [lanes] if op == 'pack' else [[rep]] if op == 'broadcast' else [vector] * (2 if op in ('plus', 'minus', 'times') else 1)
                    result = lanes if op == 'unpack' else vector
                    self.assertEqual(dict(arity=len(args), reps=args + [result],
                                          tuples=[op == 'pack'] * len(args) + [op == 'unpack']),
                                     contracts[op + n + '#'])

    def test_lane_proofs_retain_integer_signedness_and_floating_kind(self):
        for rep, (_, _, access, *_) in LANE_CASES.items():
            f = family(rep)
            source = GEN.proof_code([f])
            self.assertIn(f'CoreRepresentation(CoreKind.{access.upper()}, true, true, listOf("{rep}"))', source)
            self.assertIn(f'List({f["lanes"]}) {{ "{rep}" }}, List({f["lanes"]}) {{ lane{f["name"]} }}', source)

    def test_composite_fixtures_inline_all_operations_and_preserve_residual_scalar_call(self):
        fs = [family(rep) for rep in LANE_CASES]
        source = GEN.fixture_sources(fs)['fixtures/GeneratedSimdFamilies.hs']
        native = GEN.fixture_sources(fs)['fixtures/GeneratedSimdFamiliesNative.hs']
        for f in fs:
            n = f['name']
            operations = [op for op in f['operations'] if op not in ('pack', 'unpack')]
            arguments = 'lane a b ' + ' '.join(f'expected{i}' for i in range(len(operations)))
            signature = ' -> '.join(['Int#'] * (4 + len(operations)))
            with self.subTest(family=n):
                self.assertIn(f'{{-# OPAQUE check{n}Worker #-}}', source)
                self.assertIn(f'check{n}Worker :: {signature}', source)
                self.assertIn(f'check{n} {arguments} = case check{n}Worker {arguments} of value -> value +# 17#', source)
                for i, op in enumerate(operations):
                    self.assertIn(f'{{-# INLINE {op}{n}Local #-}}', source)
                    self.assertIn(f'{op}{n}Worker lane a b = {op}{n}Local lane a b', source)
                    self.assertIn(f'(uncheckedIShiftL# (({op}{n}Local lane a b +# 17#) /=# expected{i}) {i}#)', source)
                    self.assertIn(f'"{op}{n}" -> emit name {op}{n} (read lane) (read a) (read b)', native)
                # Existing native rows retain their four input fields; composite
                # roots consume those independently checked expected outputs.
                self.assertNotIn(f'"check{n}"', native)


if __name__ == '__main__':
    unittest.main()
