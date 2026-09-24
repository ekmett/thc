#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Independent bit formulas and declared-domain coverage; no guest execution."""
import collections
import copy
import gzip
import hashlib
import importlib.util
import json
from pathlib import Path
import unittest
import word32x4_bytearray_model as model


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def unsigned(value):
    return value & 0xffffffff


def score(lanes):
    a, b, c, d = map(unsigned, lanes)
    return 3*a+5*b+7*c+11*d


def bytes_after(offset, lanes, vector_units):
    data = [(0x89abcdef+(i//4)*0x01030507) >> (8*(i%4)) & 255 for i in range(64)]
    start = offset*(16 if vector_units else 4)
    for j in range(16):
        data[start+j] = (lanes[j//4] >> (8*(j%4))) & 255
    return data


class ModelTest(unittest.TestCase):
    def test_genuine_signed_mutations_match_current_auditor(self):
        prepare = load('word32x4_memory_prepare', Path(__file__).with_name('prepare-word32x4-bytearray-audit.py'))
        auditor = load('word32x4_memory_current_audit', prepare.ROOT/'scripts/audit-core.py')
        capabilities = json.loads((prepare.ROOT/'scripts/core-capabilities.json').read_text())
        retained = prepare.ROOT/'bench/experiments/word32x4-bytearray/evidence-x86_64'
        provenance = json.loads(gzip.decompress((retained/'native/provenance.json.gz').read_bytes()))
        hashes = {item['path']: item['sha256'] for item in provenance['artifacts']}
        fixture_hash = next(item['sha256'] for item in provenance['sources']
                            if item['path'] == 'compiler/test-fixtures/SimdWord32X4ByteArray.hs')
        self.assertEqual(hashlib.sha256(prepare.FIXTURE.read_bytes()).hexdigest(), fixture_hash)
        modules = []
        for stage in ('pre', 'post'):
            path = retained/f'{stage}-core.json.gz'
            data = gzip.decompress(path.read_bytes())
            self.assertEqual(hashlib.sha256(data).hexdigest(),
                             hashes[f'build/simd-word32x4-bytearray/{stage}-core/SimdWord32X4ByteArray.json'])
            modules.append((path, stage, json.loads(data)))
        # AArch64 freshly exports pre-Core only; the pinned x86 capture above
        # always covers both stages without claiming a new native run here.
        # Retained stages keep this model check useful in a clean checkout too.
        prepared = sorted(prepare.OUT.glob('*-core/SimdWord32X4ByteArray.json'))
        modules.extend((path, path.parent.name.removesuffix('-core'), json.loads(path.read_text()))
                       for path in prepared)
        expected_counts = {family+operation: 3 if operation == 'Index' else 1
                           for family in ('vector', 'scalar') for operation in ('Index', 'Read', 'Write')}
        for path, stage, module in modules:
            with self.subTest(path=str(path)):
                self.assertEqual(module['boundary'], prepare.STAGES[stage])
                original = copy.deepcopy(module)
                for name in expected_counts:
                    report = auditor.Audit([(str(path), module)], capabilities).run([name+'Case'])
                    self.assertTrue(report['accepted'])
                    self.assertFalse(report['issues'])
                    self.assertFalse(report['missingGlobals'])
                controls = prepare.signed_controls(module, path, auditor, capabilities)
                self.assertEqual(module, original)
                self.assertEqual(set(controls), set(expected_counts))
                for name, count in expected_counts.items():
                    report = controls[name]['report']
                    self.assertFalse(report['accepted'])
                    self.assertFalse(report['missingGlobals'])
                    self.assertEqual(len(report['issues']), count)

    def test_narrow_constructed_half_domains(self):
        low, high = set(), set()
        for i in range(65536):
            upper = (i*40503+97) & 65535
            value = upper*65536+i
            low.add(value & 65535); high.add(value >> 16)
            self.assertEqual(model.narrow(value), unsigned(value))
            self.assertEqual(model.narrow(value+(1 << 32)), unsigned(value))
        self.assertEqual(len(low), 65536)
        self.assertEqual(len(high), 65536)

    def test_exact_unique_domain(self):
        rows = model.model_rows()
        self.assertEqual(len(rows), 9666)
        self.assertEqual(collections.Counter(key[0] for key in rows),
                         {'vectorUnitCase': 72, 'scalarUnitCase': 234,
                          **{family+kind+'Case': (2304 if kind in ('Write', 'Store') else 36)
                             for family in ('vector', 'scalar') for kind in ('Index', 'Read', 'Write', 'Store')}})

    def test_independent_lane_boundary_and_offsets(self):
        for family in ('vector', 'scalar'):
            patterns = model.patterns(family)
            self.assertEqual(len(patterns), 36)
            self.assertEqual({row[0] for row in patterns}, set(range(4 if family == 'vector' else 13)))
            for lane in range(4):
                selected = [row for row in patterns if row[lane+1] in model.EDGE]
                self.assertEqual({row[lane+1] for row in selected}, set(model.EDGE))
                for row in selected:
                    for other in range(4):
                        if other != lane:
                            self.assertEqual(row[other+1], model.SENTINELS[other])

    def test_all_write_bytes_and_unchanged_regions(self):
        for entry in model.entries():
            if not entry['name'].endswith(('WriteCase', 'StoreCase')):
                continue
            groups = collections.defaultdict(set)
            for offset, a, b, c, d, selector in entry['cases']:
                groups[(offset, a, b, c, d)].add(selector)
                wanted = bytes_after(offset, [a, b, c, d], entry['name'].startswith('vector'))
                answer = model.expected(entry['name'], offset, a, b, c, d, selector)
                self.assertEqual(answer-score([a, b, c, d])*257, wanted[selector])
            self.assertEqual(len(groups), 36)
            self.assertTrue(all(selectors == set(range(64)) for selectors in groups.values()))

    def test_index_and_read_unsigned_checksums(self):
        for entry in model.entries():
            if entry['name'].endswith(('IndexCase', 'ReadCase')):
                for args in entry['cases']:
                    self.assertEqual(model.expected(entry['name'], *args), score(args[1:]))

    def test_high_bit_lanes_zero_extend_not_sign_extend(self):
        for lane, weight in enumerate((3, 5, 7, 11)):
            for value in (0x80000000, 0x80000001, 0xfffffffe, 0xffffffff):
                lanes = [0, 0, 0, 0]
                lanes[lane] = value
                for family in ('vector', 'scalar'):
                    for kind in ('Index', 'Read'):
                        actual = model.expected(family+kind+'Case', 0, *lanes)
                        self.assertEqual(actual, value*weight)
                        self.assertEqual(actual-(value-(1 << 32))*weight, (1 << 32)*weight)

    def test_alias_snapshots_separate_bit_formula(self):
        for family in ('vector', 'scalar'):
            for seed in model.SEEDS:
                before = [unsigned(seed), unsigned(seed+17), unsigned(3*seed-29), unsigned(seed ^ 0x55aa55aa)]
                after = list(before)
                if family == 'vector':
                    after[1] = unsigned(seed ^ 0x80000000)
                else:
                    after[1] = unsigned((after[1] & 0x00ffffff) | (((seed+101) & 255) << 24))
                for offset in range(4 if family == 'vector' else 13):
                    self.assertEqual(model.expected(family+'UnitCase', offset, seed), score(before)+15*score(after))

    def test_graph_cases_match_exact_native_keys(self):
        rows = model.model_rows()
        for entry in model.graph_entries():
            self.assertEqual(len(entry['cases']), 36)
            for case in entry['cases']:
                self.assertEqual(case['expectedScalar'], score(case['lanes']))
                self.assertEqual(case['expectedBytes'], bytes_after(case['offset'], case['lanes'], entry['offsetUnitBytes'] == 16))
                prefix = (case['nativeEntry'], *case['nativeArguments'])
                if entry['operation'] == 'index':
                    self.assertEqual(case['initialBytes'], case['expectedBytes'])
                    self.assertEqual(rows[prefix], case['expectedScalar'])
                else:
                    self.assertEqual(case['initialBytes'], list(model.initial_bytes()))
                    for byte in range(64):
                        self.assertEqual(rows[(*prefix, byte)], case['expectedScalar']*257+case['expectedBytes'][byte])

    def test_value_copy_not_storage_view(self):
        original = model.initial_bytes()
        changed = model.put_lanes(original, 4, [-1, 0, 1, -(1 << 31)])
        self.assertEqual(original, model.initial_bytes())
        snapshot = model.read_lanes(changed, 4)
        changed[4:20] = b'\0'*16
        self.assertEqual(snapshot, [0xffffffff, 0, 1, 1 << 31])

    def test_bounds_and_bad_rows_rejected(self):
        for address in (-1, 49, 64, 1 << 63):
            with self.assertRaises(AssertionError): model.read_lanes(bytearray(64), address)
            with self.assertRaises(AssertionError): model.put_lanes(bytearray(64), address, [1, 2, 3, 4])
        with self.assertRaises(AssertionError): model.parse_rows('vectorUnitCase\t0\t1\t2\n'*2)
        with self.assertRaises(AssertionError): model.parse_rows('missing\t0\t1\n')
        with self.assertRaises(AssertionError): model.parse_rows('vectorUnitCase\t0\t1\n')
        with self.assertRaises(AssertionError): model.expected('vectorWriteCase', 0, 1, 2, 3, 4, 64)

    def test_safe_checksum_bound_and_native_roundtrip(self):
        rows = model.model_rows()
        self.assertTrue(all(0 <= answer < 1 << 45 for answer in rows.values()))
        text = ''.join('\t'.join(map(str, (*key, answer)))+'\n' for key, answer in rows.items())
        self.assertEqual(model.parse_rows(text), rows)


if __name__ == '__main__':
    unittest.main()
