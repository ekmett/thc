#!/usr/bin/env python3
"""Independent byte/IEEE formulas and complete declared-domain checks."""
import collections
import struct
import unittest
import floatx4_bytearray_model as model


def initial():
    return [(0x89abcdef+(i//4)*0x01030507) >> (8*(i%4)) & 255 for i in range(64)]


def moved(offset, bits, scale):
    result = initial()
    for i in range(16):
        result[offset*scale+i] = (bits[i//4] >> (8*(i%4))) & 255
    return result


def graph_bits(values):
    return [int.from_bytes(struct.pack('<f', value), 'little') for value in values]


class ModelTest(unittest.TestCase):
    def test_exact_domain_and_unique_keys(self):
        rows = model.model_rows()
        self.assertEqual(len(rows), 6720)
        self.assertEqual(collections.Counter(k[0] for k in rows),
                         {family+op+'Case': count for family in ('vector', 'scalar')
                          for op, count in (('Unit', 128), ('Index', 64), ('Read', 64), ('Write', 1024),
                                            ('GraphIndex', 32), ('GraphStore', 2048))})

    def test_raw_lane_classifications(self):
        self.assertEqual(len(model.RAW_BITS), 16)
        self.assertEqual(sum((x & 0x7fffffff) == 0 for x in model.RAW_BITS), 2)
        self.assertEqual(sum(0 < (x & 0x7fffffff) < 0x800000 for x in model.RAW_BITS), 4)
        self.assertEqual(sum((x & 0x7fffffff) == 0x7f800000 for x in model.RAW_BITS), 2)
        quiet = [x for x in model.RAW_BITS if (x & 0x7fffffff) > 0x7f800000]
        self.assertEqual(quiet, [0x7fc12345, 0xffc54321])
        self.assertTrue(all(x & 0x400000 for x in quiet))

    def test_every_raw_lane_boundary_offset_and_selector(self):
        for family in ('vector', 'scalar'):
            rows = model.patterns(family)
            self.assertEqual(len(rows), 16)
            self.assertEqual({r[0] for r in rows}, set(range(4 if family == 'vector' else 13)))
            for lane in range(4):
                self.assertEqual({r[lane+1] for r in rows}, set(model.RAW_BITS))
            for op, count in (('Unit', 8), ('Index', 4), ('Read', 4), ('Write', 64)):
                entry = next(e for e in model.entries() if e['name'] == family+op+'Case')
                groups = collections.defaultdict(set)
                for *args, selector in entry['cases']:
                    groups[tuple(args)].add(selector)
                self.assertEqual(len(groups), 16)
                self.assertTrue(all(s == set(range(count)) for s in groups.values()))

    def test_alias_before_after_are_independent_exact_bit_observations(self):
        for family in ('vector', 'scalar'):
            for offset, *bits in model.patterns(family):
                before = list(bits)
                after = list(bits); after[1] ^= 1 << 31
                for selector, wanted in enumerate(before+after):
                    self.assertEqual(model.expected(family+'UnitCase', offset, *bits, selector), wanted)

    def test_raw_loads_do_not_float_compare_or_canonicalize(self):
        for family in ('vector', 'scalar'):
            for offset, *bits in model.patterns(family):
                for lane, wanted in enumerate(bits):
                    for op in ('Index', 'Read'):
                        self.assertEqual(model.expected(family+op+'Case', offset, *bits, lane), wanted)

    def test_all64_write_bytes_and_guards(self):
        for family in ('vector', 'scalar'):
            for offset, *bits in model.patterns(family):
                wanted = moved(offset, bits, 16 if family == 'vector' else 4)
                a, b, c, d = bits
                score = 3*a+5*b+7*c+11*d
                for byte in range(64):
                    self.assertEqual(model.expected(family+'WriteCase', offset, *bits, byte), score*257+wanted[byte])

    def test_integer_ieee_encoding_independent_struct(self):
        for n in range(-65536, 65537):
            self.assertEqual(model.integer_float_bits(n), graph_bits([n])[0])

    def test_graph_individual_boundaries_and_exact_intermediates(self):
        for family in ('vector', 'scalar'):
            rows = model.patterns(family, graph=True)
            self.assertEqual(len(rows), 32)
            self.assertEqual({r[0] for r in rows}, set(range(4 if family == 'vector' else 13)))
            for lane in range(4):
                selected = [r for r in rows if r[lane+1] in model.GRAPH_EDGE]
                self.assertEqual({r[lane+1] for r in selected}, set(model.GRAPH_EDGE))
                for r in selected:
                    self.assertTrue(all(r[k+1] == model.GRAPH_SENTINELS[k] for k in range(4) if k != lane))
            for _, *values in rows:
                total = 0.0
                for value, weight in zip(values, (3, 5, 7, 11)):
                    product = struct.unpack('<f', struct.pack('<f', value*weight))[0]
                    self.assertEqual(product, value*weight)
                    total = struct.unpack('<f', struct.pack('<f', total+product))[0]
                self.assertEqual(int(total), 3*values[0]+5*values[1]+7*values[2]+11*values[3])
                self.assertLess(sum(abs(v*w) for v, w in zip(values, (3, 5, 7, 11))), 1 << 24)

    def test_graph_cases_all_native_witnesses(self):
        rows = model.model_rows()
        for entry in model.graph_entries():
            self.assertEqual(len(entry['cases']), 32)
            for case in entry['cases']:
                a, b, c, d = case['lanes']; score = 3*a+5*b+7*c+11*d
                self.assertEqual(case['expectedScalar'], score)
                wanted = moved(case['offset'], graph_bits(case['lanes']), entry['offsetUnitBytes'])
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
        self.assertEqual(len(diagnostics), 640)
        self.assertFalse(diagnostics.keys() & model.model_rows().keys())
        for bits in model.SIGNALING_BITS:
            self.assertEqual(bits & 0x7f800000, 0x7f800000)
            self.assertNotEqual(bits & 0x7fffff, 0)
            self.assertEqual(bits & 0x400000, 0)

    def test_bounds_bad_rows_and_safe_machine_results(self):
        for address in (-1, 49, 64, 1 << 63):
            with self.assertRaises(AssertionError): model.read_lanes(bytearray(64), address)
            with self.assertRaises(AssertionError): model.put_lanes(bytearray(64), address, [0]*4)
        for name in ('vectorIndexCase', 'scalarReadCase'):
            with self.assertRaises(AssertionError): model.expected(name, 0, 0, 0, 0, 0, 4)
        with self.assertRaises(AssertionError): model.expected('vectorWriteCase', 0, 0, 0, 0, 0, 64)
        with self.assertRaises(AssertionError): model.parse_rows('unknown\t0\n')
        rows = model.model_rows()
        self.assertTrue(all(-(1 << 45) < x < 1 << 45 for x in rows.values()))
        text = ''.join('\t'.join(map(str, (*k, v)))+'\n' for k, v in rows.items())
        self.assertEqual(model.parse_rows(text), rows)
        with self.assertRaises(AssertionError): model.parse_rows(text+text)


if __name__ == '__main__':
    unittest.main()
