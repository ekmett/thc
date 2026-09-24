#!/usr/bin/env python3
"""Independent signed64 byte and IEEE formulas, never floating raw-bit equality."""
import collections
import struct
import unittest
import doublex2_bytearray_model as model


MASK = 0xffffffffffffffff


def signed(bits):
    value = bits & MASK
    return value if value < 0x8000000000000000 else value-0x10000000000000000


def initial():
    return [(0x89abcdef+(i//4)*0x01030507) >> (8*(i%4)) & 255 for i in range(64)]


def moved(offset, bits, scale):
    data = initial()
    for byte in range(16):
        data[offset*scale+byte] = (bits[byte//8] >> (8*(byte%8))) & 255
    return data


def graph_bits(values):
    return [int.from_bytes(struct.pack('<d', value), 'little') for value in values]


class ModelTest(unittest.TestCase):
    def test_exact_domain_and_unique_signed_long_keys(self):
        rows = model.model_rows()
        self.assertEqual(len(rows), 4384)
        self.assertEqual(collections.Counter(k[0] for k in rows),
                         {family+op+'Case': count for family in ('vector', 'scalar')
                          for op, count in (('Unit', 64), ('Index', 32), ('Read', 32), ('Write', 1024),
                                            ('GraphIndex', 16), ('GraphStore', 1024))})
        self.assertTrue(all(-(1 << 63) <= x < 1 << 63 for key in rows for x in key[1:]))
        self.assertTrue(all(-(1 << 63) <= x < 1 << 63 for x in rows.values()))

    def test_all64_integer_bits_remain_observable(self):
        for bit in range(64):
            for raw in (1 << bit, MASK ^ (1 << bit)):
                for lane in range(2):
                    values = [0, 0]; values[lane] = signed(raw)
                    for family in ('vector', 'scalar'):
                        for op in ('Index', 'Read'):
                            self.assertEqual(model.expected(family+op+'Case', 0, *values, lane), signed(raw))
                    self.assertEqual(model.signed64(raw), signed(raw))
                    self.assertEqual(model.signed64(raw+(1 << 64)), signed(raw))

    def test_raw_lane_classifications(self):
        magnitude = [x & 0x7fffffffffffffff for x in model.RAW_BITS]
        self.assertEqual(len(magnitude), 16)
        self.assertEqual(magnitude.count(0), 2)
        self.assertEqual(sum(0 < x < 0x0010000000000000 for x in magnitude), 4)
        self.assertEqual(magnitude.count(0x7ff0000000000000), 2)
        quiet = [x for x in model.RAW_BITS if (x & 0x7fffffffffffffff) > 0x7ff0000000000000]
        self.assertEqual(quiet, [0x7ff8123456789abc, 0xfff8abcdef012345])
        self.assertTrue(all(x & 0x0008000000000000 for x in quiet))

    def test_each_raw_boundary_offset_and_selector(self):
        for family in ('vector', 'scalar'):
            rows = model.patterns(family)
            self.assertEqual(len(rows), 16)
            self.assertEqual({r[0] for r in rows}, set(range(4 if family == 'vector' else 7)))
            for lane in range(2):
                self.assertEqual({r[lane+1] & MASK for r in rows}, set(model.RAW_BITS))
            for op, count in (('Unit', 4), ('Index', 2), ('Read', 2), ('Write', 64)):
                entry = next(e for e in model.entries() if e['name'] == family+op+'Case')
                groups = collections.defaultdict(set)
                for *args, selector in entry['cases']:
                    groups[tuple(args)].add(selector)
                self.assertEqual(len(groups), 16)
                self.assertTrue(all(s == set(range(count)) for s in groups.values()))

    def test_alias_before_after_are_independent_full64_bit_observations(self):
        for family in ('vector', 'scalar'):
            for offset, a, b in model.patterns(family):
                for selector, wanted in enumerate((a, b, a, signed(b ^ 0x8000000000000000))):
                    self.assertEqual(model.expected(family+'UnitCase', offset, a, b, selector), wanted)

    def test_raw_loads_do_not_numeric_convert_or_canonicalize(self):
        for family in ('vector', 'scalar'):
            for offset, *values in model.patterns(family):
                for lane, wanted in enumerate(values):
                    for op in ('Index', 'Read'):
                        self.assertEqual(model.expected(family+op+'Case', offset, *values, lane), wanted)

    def test_all64_write_bytes_recover_from_full_width_xor_witness(self):
        for family in ('vector', 'scalar'):
            for offset, a, b in model.patterns(family):
                wanted = moved(offset, [a, b], 16 if family == 'vector' else 8)
                for byte in range(64):
                    actual = model.expected(family+'WriteCase', offset, a, b, byte)
                    self.assertEqual(actual, signed((a & MASK) ^ (b & MASK) ^ wanted[byte]))
                    self.assertEqual(actual ^ a ^ b, wanted[byte])

    def test_integer_ieee_encoding_independent_struct(self):
        values = range(-65536, 65537)
        for value in values:
            self.assertEqual(model.integer_double_bits(value), graph_bits([value])[0])
        for bit in range(41):
            for delta in (-1, 0, 1):
                value = (1 << bit)+delta
                if value <= 1 << 40:
                    for sign in (-1, 1):
                        self.assertEqual(model.integer_double_bits(sign*value), graph_bits([sign*value])[0])

    def test_graph_individual_boundaries_and_exact_intermediates(self):
        for family in ('vector', 'scalar'):
            rows = model.patterns(family, graph=True)
            self.assertEqual(len(rows), 16)
            self.assertEqual({r[0] for r in rows}, set(range(4 if family == 'vector' else 7)))
            for lane in range(2):
                selected = [r for r in rows if r[lane+1] in model.GRAPH_EDGE]
                self.assertEqual({r[lane+1] for r in selected}, set(model.GRAPH_EDGE))
                self.assertTrue(all(r[2-lane] == model.GRAPH_SENTINELS[1-lane] for r in selected))
            for _, a, b in rows:
                self.assertEqual(float(a)*3.0+float(b)*5.0, a*3+b*5)
                self.assertLess(abs(a*3)+abs(b*5), 1 << 44)
                self.assertLess(abs(a*3+b*5)*257+255, 1 << 63)

    def test_graph_cases_all_native_witnesses(self):
        rows = model.model_rows()
        for entry in model.graph_entries():
            self.assertEqual(len(entry['cases']), 16)
            for case in entry['cases']:
                a, b = case['lanes']; score = 3*a+5*b
                self.assertEqual(case['expectedScalar'], score)
                wanted = moved(case['offset'], graph_bits([a, b]), entry['offsetUnitBytes'])
                self.assertEqual(case['expectedBytes'], wanted)
                prefix = (case['nativeEntry'], *case['nativeArguments'])
                if entry['operation'] == 'index':
                    self.assertEqual(case['initialBytes'], wanted)
                    self.assertEqual(rows[prefix], score)
                else:
                    self.assertEqual(case['initialBytes'], initial())
                    for byte in range(64):
                        self.assertEqual(rows[(*prefix, byte)], score*257+wanted[byte])

    def test_signaling_rows_are_separate_native_only_domain(self):
        diagnostics = model.diagnostic_rows()
        self.assertEqual(len(diagnostics), 576)
        self.assertFalse(diagnostics.keys() & model.model_rows().keys())
        for bits in model.SIGNALING_BITS:
            self.assertEqual(bits & 0x7ff0000000000000, 0x7ff0000000000000)
            self.assertNotEqual(bits & 0x000fffffffffffff, 0)
            self.assertEqual(bits & 0x0008000000000000, 0)

    def test_bounds_bad_rows_and_roundtrip(self):
        for address in (-1, 49, 64, 1 << 63):
            with self.assertRaises(AssertionError): model.read_lanes(bytearray(64), address)
            with self.assertRaises(AssertionError): model.put_lanes(bytearray(64), address, [0]*2)
        for name in ('vectorIndexCase', 'scalarReadCase'):
            with self.assertRaises(AssertionError): model.expected(name, 0, 0, 0, 2)
        with self.assertRaises(AssertionError): model.expected('vectorWriteCase', 0, 0, 0, 64)
        with self.assertRaises(AssertionError): model.parse_rows('unknown\t0\n')
        rows = model.model_rows()
        text = ''.join('\t'.join(map(str, (*k, v)))+'\n' for k, v in rows.items())
        self.assertEqual(model.parse_rows(text), rows)
        with self.assertRaises(AssertionError): model.parse_rows(text+text)


if __name__ == '__main__':
    unittest.main()
