#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Synthetic closed-ABI proof tests; no Haskell replacement or FFI execution.

The ghc-internal unit is grounded in the actual installed Fingerprint.hi, not
relabeled main-unit Core: see compiler/test-fixtures/md5-foreign-unit-evidence.md.
"""
import copy
import unittest

from core_md5_foreign import validate


def scalar(primitive=None, evaluated=False):
    return dict(kind='address' if primitive == 'AddrRep' else 'long' if primitive else 'void',
                primReps=[] if primitive is None else [primitive], evaluated=evaluated)


def result():
    return dict(kind='unknown', primReps=[], evaluated=False, aggregate='unboxed-tuple',
                components=[scalar(evaluated=True)])


def fixture(operation='INIT'):
    symbol, primitives = {'INIT': ('__hsbase_MD5Init', ['AddrRep', None]),
                          'UPDATE': ('__hsbase_MD5Update', ['AddrRep', 'AddrRep', 'Int32Rep', None]),
                          'FINAL': ('__hsbase_MD5Final', ['AddrRep', 'AddrRep', None])}[operation]
    descriptor = dict(schema=1, target=dict(kind='static', symbol=symbol, unit='ghc-internal', isFunction=True),
                      convention='ccall', safety='unsafe', arity=len(primitives), suppliedArity=len(primitives),
                      argumentReps=[scalar(p) for p in primitives], resultRep=result())
    return [dict(foreignCall=descriptor, rep=result()), [scalar(p, True) for p in primitives],
            [False]*len(primitives), result()]


class Md5ForeignProofTest(unittest.TestCase):
    def reject(self, sample):
        with self.assertRaisesRegex(ValueError, '^Invalid MD5 foreign call: '):
            validate(*sample)

    def test_three_exact_contracts_and_evaluation_facts(self):
        for operation in ('INIT', 'UPDATE', 'FINAL'):
            sample = fixture(operation)
            self.assertEqual(validate(*sample), operation)
            for argument in sample[1]: argument['evaluated'] = False
            sample[0]['rep']['evaluated'] = True
            sample[3]['evaluated'] = True
            self.assertEqual(validate(*sample), operation)

    def test_missing_other_and_dynamic_targets_do_not_dispatch(self):
        for metadata in (None, {}, {'foreignCall': None}, {'foreignCall': {'target': {'kind': 'dynamic'}}},
                         {'foreignCall': {'target': {'symbol': 'MD5Init'}}},
                         {'foreignCall': {'target': {'symbol': '__hsbase_MD5Other'}}}):
            self.assertIsNone(validate(metadata, [], [], None))

    def test_descriptor_key_and_exact_integer_contracts(self):
        for field in ('schema', 'arity', 'suppliedArity'):
            for value in (None, True, False, 1.0, 2.0, 4.0, '2', -1, 1 << 32):
                sample = fixture(); sample[0]['foreignCall'][field] = value; self.reject(sample)
            sample = fixture(); sample[0]['foreignCall'].pop(field); self.reject(sample)
        sample = fixture(); sample[0]['foreignCall']['extra'] = True; self.reject(sample)

    def test_known_symbols_reject_wrong_unit_target_safety_convention(self):
        for field, values in {'kind': ['dynamic', None, 0], 'unit': ['main', None, 'ghc-internal-9.1401.0'],
                              'isFunction': [False, 1, 'true', None]}.items():
            for value in values:
                sample = fixture(); sample[0]['foreignCall']['target'][field] = value; self.reject(sample)
        for field, values in {'safety': ['safe', 'interruptible', None],
                              'convention': ['capi', 'stdcall', 'prim', 'javascript', None]}.items():
            for value in values:
                sample = fixture(); sample[0]['foreignCall'][field] = value; self.reject(sample)
        sample = fixture(); sample[0]['foreignCall']['target']['extra'] = 0; self.reject(sample)

    def test_every_declared_and_actual_scalar_slot_is_exact(self):
        changes = [('primReps', ['IntRep']), ('primReps', ['Word32Rep']), ('primReps', None),
                   ('kind', 'unknown'), ('evaluated', 1), ('aggregate', 'unboxed-tuple'),
                   ('components', []), ('vector', None), ('alternatives', []), ('tagSlot', 0)]
        for operation in ('INIT', 'UPDATE', 'FINAL'):
            for site in ('declared', 'actual'):
                for i in range(len(fixture(operation)[1])):
                    for field, value in changes:
                        sample = fixture(operation)
                        args = sample[0]['foreignCall']['argumentReps'] if site == 'declared' else sample[1]
                        args[i][field] = value; self.reject(sample)
                    sample = fixture(operation)
                    args = sample[0]['foreignCall']['argumentReps'] if site == 'declared' else sample[1]
                    args[i].pop('evaluated'); self.reject(sample)
        sample = fixture(); sample[0]['foreignCall']['argumentReps'][0]['evaluated'] = True; self.reject(sample)

    def test_every_result_site_preserves_singleton_state_tuple(self):
        changes = [('kind', 'void'), ('primReps', ['IntRep']), ('evaluated', 0),
                   ('aggregate', 'unboxed-sum'), ('components', []), ('components', [scalar(), scalar()]),
                   ('components', [scalar('Int32Rep', True)]), ('components', [scalar()]),
                   ('vector', None), ('alternatives', []), ('tagSlot', 0)]
        for site in ('declared', 'metadata', 'actual'):
            for field, value in changes:
                sample = fixture(); proof = sample[0]['foreignCall']['resultRep'] if site == 'declared' else sample[0]['rep'] if site == 'metadata' else sample[3]
                proof[field] = copy.deepcopy(value); self.reject(sample)
            sample = fixture(); proof = sample[0]['foreignCall']['resultRep'] if site == 'declared' else sample[0]['rep'] if site == 'metadata' else sample[3]
            proof.pop('aggregate'); self.reject(sample)
        sample = fixture(); sample[0]['foreignCall']['resultRep']['evaluated'] = True; self.reject(sample)

    def test_argument_counts_and_unlifted_flags_are_exact(self):
        for operation in ('INIT', 'UPDATE', 'FINAL'):
            for i in range(len(fixture(operation)[1])):
                for value in (True, 0, None, 'false'):
                    sample = fixture(operation); sample[2][i] = value; self.reject(sample)
            for site in (1, 2):
                sample = fixture(operation); sample[site].pop(); self.reject(sample)
                sample = fixture(operation); sample[site].append(sample[site][0]); self.reject(sample)
            sample = fixture(operation); sample[0]['foreignCall']['argumentReps'].pop(); self.reject(sample)

    def test_declared_actual_and_symbol_mismatch(self):
        sample = fixture('UPDATE'); sample[0]['foreignCall']['target']['symbol'] = '__hsbase_MD5Final'; self.reject(sample)
        sample = fixture('UPDATE'); sample[1][2] = scalar('AddrRep', True); self.reject(sample)
        sample = fixture(); sample[0].pop('rep'); self.reject(sample)


if __name__ == '__main__':
    unittest.main()
