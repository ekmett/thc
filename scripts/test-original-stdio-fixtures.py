#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Fixture/model integrity tests; synthetic JSON is never executable guest Core."""
import copy
import errno
import importlib.util
from pathlib import Path
import unittest

import original_stdio_model as model

spec = importlib.util.spec_from_file_location('prepare_stdio', Path(__file__).with_name('prepare-original-stdio.py'))
prepare = importlib.util.module_from_spec(spec); spec.loader.exec_module(prepare)


def sample(symbol):
    declaration = prepare.descriptor(symbol)
    arguments = [['void' if not p['primReps'] else 'var', dict(rep=dict(p, evaluated=True))]
                 for p in declaration['argumentReps']]
    return ['app', ['var', 'synthetic-unit-test-only'], arguments, [False]*len(arguments), False, False,
            dict(foreignCall=declaration, rep=copy.deepcopy(declaration['resultRep']))]


class OriginalStdioFixtureTests(unittest.TestCase):
    def test_native_domain_is_defined_unique_and_covers_every_byte(self):
        rows = list(model.cases())
        self.assertEqual(len(rows), 144)
        self.assertEqual(len({(x['entry'], tuple(x['arguments'])) for x in rows}), 144)
        self.assertEqual(model.validate_rows(rows), 144)
        for entry in model.ENTRIES:
            for fd, field in ((1, 'stdoutHex'), (2, 'stderrHex')):
                self.assertIn(bytes(range(256)).hex(), [row[field] for row in rows
                    if row['entry'] == entry and row['arguments'][0] == fd])

    def test_stream_offsets_and_success_errno_sentinel_are_independent(self):
        for entry in model.ENTRIES:
            for fd in (1, 2):
                for offset, count in model.SLICES:
                    row = model.expected(entry, (fd, offset, count))
                    self.assertEqual(row['result'], -count-2 if entry.endswith('Errno') else count)
                    payload = bytes(offset+i for i in range(count)).hex()
                    self.assertEqual(row['stdoutHex'], payload if fd == 1 else '')
                    self.assertEqual(row['stderrHex'], payload if fd == 2 else '')

    def test_invalid_fd_including_zero_length_has_no_output(self):
        for entry in model.ENTRIES:
            for fd in (-2147483648, -1):
                for offset, count in model.SLICES:
                    row = model.expected(entry, (fd, offset, count))
                    self.assertEqual(row, dict(result=errno.EBADF if entry.endswith('Errno') else -1,
                                               stdoutHex='', stderrHex=''))

    def test_native_undefined_domains_fail_closed(self):
        for arguments in ((0, 0, 1), (3, 0, 1), (1, -1, 1), (1, 256, 1), (1, 0, -1),
                          (1, 0, 2**64-1), (1, 0, True), (1, 0), (1, 0, 1, 2)):
            with self.subTest(arguments=arguments), self.assertRaises(ValueError):
                model.expected('originalWrite', arguments)

    def test_missing_repeated_reordered_corrupted_native_rows_fail_closed(self):
        rows = list(model.cases())
        for modified in (rows[:-1], rows+[rows[0]], rows[::-1]):
            with self.assertRaises(ValueError): model.validate_rows(modified)
        for field, value in (('result', 999), ('stdoutHex', 'ff'), ('stderrHex', 'ff'), ('entry', 'syntheticWrite')):
            modified = copy.deepcopy(rows); modified[0][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError): model.validate_rows(modified)

    def test_exact_original_symbols_conventions_and_widths(self):
        for symbol in (prepare.SAFE_WRITE, prepare.UNSAFE_WRITE, prepare.ERRNO):
            self.assertEqual(prepare.validate_application(sample(symbol))['symbol'], symbol)
        for symbol in ('write', prepare.SAFE_WRITE.replace('20', '19'), '__hscore_set_errno'):
            with self.assertRaises(ValueError): prepare.descriptor(symbol)

    def test_forged_declared_and_actual_proofs_fail_closed(self):
        mutations = (
            lambda a: a[-1]['foreignCall'].__setitem__('schema', True),
            lambda a: a[-1]['foreignCall'].__setitem__('convention', 'ccall'),
            lambda a: a[-1]['foreignCall'].__setitem__('safety', 'interruptible'),
            lambda a: a[-1]['foreignCall']['target'].__setitem__('unit', 'main'),
            lambda a: a[-1]['foreignCall']['target'].__setitem__('isFunction', False),
            lambda a: a[-1]['foreignCall']['target'].__setitem__('isFunction', 1),
            lambda a: a[-1]['foreignCall']['argumentReps'][0].__setitem__('primReps', ['IntRep']),
            lambda a: a[2][0][-1]['rep'].__setitem__('primReps', ['IntRep']),
            lambda a: a[2][2][-1]['rep'].__setitem__('primReps', ['WordRep']),
            lambda a: a[2][-1][-1]['rep'].__setitem__('aggregate', 'unboxed-tuple'),
            lambda a: a[3].__setitem__(0, 0),
            lambda a: a[-1]['rep']['components'].pop(0),
            lambda a: a[-1]['rep']['components'][1].__setitem__('primReps', ['IntRep']),
            lambda a: a[-1]['rep']['components'][1].__setitem__('evaluated', 1),
            lambda a: a[-1]['rep'].__setitem__('evaluated', 0),
            lambda a: a[2].pop(),
        )
        for index, mutate in enumerate(mutations):
            app = sample(prepare.SAFE_WRITE); mutate(app)
            with self.subTest(index=index), self.assertRaises(ValueError): prepare.validate_application(app)

    def test_only_original_foreign_calls_can_remain_an_audit_frontier(self):
        proofs = [dict(entry='originalWrite', owner='original', head='original-ffi')]
        report = dict(roots=['original'], reachableBindings=[dict(id='original')],
                      issues=[dict(code='foreign-call', owner='original')], missingGlobals=[dict(id='original-ffi')])
        prepare.validate_audit('originalWrite', report, proofs)
        for field, value in (('issues', [dict(code='unsupported-primitive', owner='original')]),
                             ('missingGlobals', [dict(id='other-global')]),
                             ('reachableBindings', [dict(id='original'), dict(id='helper')])):
            changed = dict(report, **{field: value})
            with self.subTest(field=field), self.assertRaises(ValueError):
                prepare.validate_audit('originalWrite', changed, proofs)


if __name__ == '__main__':
    unittest.main()
