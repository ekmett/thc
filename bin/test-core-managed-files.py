#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Synthetic versioned file ABI proofs and auditor dispatch; no POSIX emulation."""
import copy
import importlib.util
import json
from pathlib import Path
import unittest

from core_managed_files import validate, validate_head

ROOT = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location('audit_core', ROOT / 'audit-core.py')
audit_core = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(audit_core)
CAP = json.loads((ROOT / 'core-capabilities.json').read_text())

# Keep these contracts independent from the implementation's table.
SIGNATURES = {
    'open': ['AddrRep', 'IntRep', None], 'read': ['IntRep', 'AddrRep', 'IntRep', None],
    'write': ['IntRep', 'AddrRep', 'IntRep', None], 'close': ['IntRep', None],
    'error_kind': [None], 'error_message': [None], 'seek': ['IntRep', 'IntRep', 'IntRep', None],
    'size': ['IntRep', None], 'set_size': ['IntRep', 'IntRep', None],
    'is_terminal': ['IntRep', None], 'device_type': ['IntRep', None],
}


def scalar(primitive=None, evaluated=True):
    return dict(kind='address' if primitive == 'AddrRep' else 'closure' if primitive == 'BoxedRep (Just Lifted)'
                else 'long' if primitive else 'void', primReps=[] if primitive is None else [primitive], evaluated=evaluated)


def result(name, evaluated=True):
    primitive = 'AddrRep' if name == 'error_message' else 'IntRep'
    return dict(kind='unknown', primReps=[primitive], evaluated=evaluated, aggregate='unboxed-tuple',
                components=[scalar(), scalar(primitive)])


def fixture(name='open'):
    primitives = SIGNATURES[name]
    descriptor = dict(schema=1, target=dict(kind='static', symbol='thc_io_v1_' + name, unit='main', isFunction=True),
                      convention='prim', safety='safe', arity=len(primitives), suppliedArity=len(primitives),
                      argumentReps=[scalar(p, False) for p in primitives], resultRep=result(name, False))
    return [dict(foreignCall=descriptor, rep=result(name)), [scalar(p) for p in primitives],
            [False] * len(primitives), result(name)]


def call(name='open'):
    metadata, arguments, flags, _ = fixture(name)
    return ['app', ['var', 'foreign-' + name, dict(rep=scalar('BoxedRep (Just Lifted)'))],
            [['var', 'p' + str(i), dict(rep=proof)] for i, proof in enumerate(arguments)], flags, False, False, metadata]


def module(name):
    expr = call(name)
    fields = [dict(id='state', lifted=False, rep=scalar()), dict(id='payload', lifted=False, rep=result(name)['components'][1])]
    body = ['case', expr, 'pair', [['data', 'T2', ['state', 'payload'], ['var', 'payload', dict(rep=fields[1]['rep'])], dict(binders=fields)]],
            dict(rep=fields[1]['rep'], binder=dict(id='pair', lifted=False, rep=result(name)))]
    formals = [dict(id='p' + str(i), lifted=False, rep=scalar(rep)) for i, rep in enumerate(SIGNATURES[name])]
    closure = scalar('BoxedRep (Just Lifted)')
    return dict(schema=1, ghc='9.14.1', constructors=[dict(id='T2', kind='unboxed-tuple', arity=2, tag=1)],
                bindings=[dict(id='root', name='root', arity=len(formals), lifted=True, rep=closure,
                               expr=['lam', formals, body, dict(rep=closure, resultRep=fields[1]['rep'])])])


class ManagedFileProofTest(unittest.TestCase):
    def reject(self, sample):
        with self.assertRaisesRegex(ValueError, '^Invalid managed file call: '):
            validate(*sample)

    def test_all_eleven_contracts_and_importing_units(self):
        self.assertEqual(11, len(SIGNATURES))
        for name in SIGNATURES:
            for unit in (None, 'main', 'library-1.0', 'ghc-internal'):
                sample = fixture(name); sample[0]['foreignCall']['target']['unit'] = unit
                self.assertEqual('thc_io_v1_' + name, validate(*sample))
                for argument in sample[1]: argument['evaluated'] = False
                sample[3]['evaluated'] = False
                self.assertEqual('thc_io_v1_' + name, validate(*sample))

    def test_reserved_prefix_fails_closed_without_posix_name_interception(self):
        for symbol in ('thc_io_v1_', 'thc_io_v1_unknown', 'thc_io_v1_open64'):
            sample = fixture(); sample[0]['foreignCall']['target']['symbol'] = symbol; self.reject(sample)
        for symbol in ('open', 'read', 'write', 'close', '__hsbase_open', 'thc_io_v2_open'):
            sample = fixture(); sample[0]['foreignCall']['target']['symbol'] = symbol
            self.assertIsNone(validate(*sample))
        for metadata in (None, {}, dict(foreignCall=None)):
            self.assertIsNone(validate(metadata, [], [], None))

    def test_exact_descriptor_and_target(self):
        for name in SIGNATURES:
            for field in ('schema', 'arity', 'suppliedArity'):
                for value in (None, True, False, 1.0, 2.0, '1', -1, 1 << 32):
                    sample = fixture(name); sample[0]['foreignCall'][field] = value; self.reject(sample)
                sample = fixture(name); sample[0]['foreignCall'].pop(field); self.reject(sample)
            for field, values in {'kind': [None, 'dynamic', False], 'unit': ['', 7, False],
                                  'isFunction': [None, False, 1, 'true']}.items():
                for value in values:
                    sample = fixture(name); sample[0]['foreignCall']['target'][field] = value; self.reject(sample)
            for field, values in {'convention': [None, 'ccall', 'capi', 'javascript'],
                                  'safety': [None, 'unsafe', 'interruptible']}.items():
                for value in values:
                    sample = fixture(name); sample[0]['foreignCall'][field] = value; self.reject(sample)
            sample = fixture(name); sample[0]['foreignCall']['target'].pop('unit'); self.reject(sample)
            sample = fixture(name); sample[0]['foreignCall']['target']['extra'] = None; self.reject(sample)
            sample = fixture(name); sample[0]['foreignCall']['extra'] = None; self.reject(sample)

    def test_argument_flags_and_raw_proofs(self):
        changes = [('kind', 'unknown'), ('primReps', ['WordRep']), ('primReps', ['Int32Rep']),
                   ('evaluated', 1), ('vector', None), ('aggregate', 'unboxed-tuple'), ('components', []), ('tagSlot', 0)]
        for name, primitives in SIGNATURES.items():
            for i in range(len(primitives)):
                for value in (True, 0, None, 'false'):
                    sample = fixture(name); sample[2][i] = value; self.reject(sample)
                for declared in (False, True):
                    for field, value in changes:
                        sample = fixture(name)
                        args = sample[0]['foreignCall']['argumentReps'] if declared else sample[1]
                        args[i][field] = value; self.reject(sample)
                sample = fixture(name); sample[0]['foreignCall']['argumentReps'][i]['evaluated'] = True; self.reject(sample)
            for site in (1, 2):
                sample = fixture(name); sample[site].pop(); self.reject(sample)
                sample = fixture(name); sample[site].append(sample[site][0]); self.reject(sample)
            sample = fixture(name); sample[0]['foreignCall']['argumentReps'].pop(); self.reject(sample)

    def test_all_three_results_retain_state_and_typed_payload(self):
        for name in ('open', 'error_message'):
            for site in ('declared', 'metadata', 'actual'):
                def proof(sample):
                    return sample[0]['foreignCall']['resultRep'] if site == 'declared' else sample[0]['rep'] if site == 'metadata' else sample[3]
                for field, value in [('kind', 'void'), ('primReps', []), ('aggregate', 'unboxed-sum'),
                                     ('evaluated', 1), ('components', []), ('vector', None)]:
                    sample = fixture(name); proof(sample)[field] = copy.deepcopy(value); self.reject(sample)
                for i in range(2):
                    for field, value in [('evaluated', False), ('primReps', ['WordRep']), ('components', []), ('kind', 'object')]:
                        sample = fixture(name); proof(sample)['components'][i][field] = copy.deepcopy(value); self.reject(sample)
        sample = fixture(); sample[0]['foreignCall']['resultRep']['evaluated'] = True; self.reject(sample)

    def test_head_requires_unbound_declared_foreign_identifier(self):
        head = call()[1]
        validate_head(head, False)
        for value in (None, [], ['var', None], ['prim', 'foreign-open'], ['var', 'foreign-open']):
            with self.assertRaises(ValueError): validate_head(value, False)
        with self.assertRaises(ValueError): validate_head(head, True)
        for identifier in (None, '', 3):
            value = copy.deepcopy(head); value[1] = identifier
            with self.assertRaises(ValueError): validate_head(value, False)

    def test_auditor_accepts_only_enabled_exact_symbols(self):
        cap = copy.deepcopy(CAP)
        for name in SIGNATURES:
            report = audit_core.Audit([('synthetic-file.json', module(name))], cap).run(['root'])
            self.assertTrue(report['accepted'], report)
            self.assertEqual(['thc_io_v1_' + name], [item['symbol'] for item in report['foreignCalls']])
            audit = audit_core.Audit([], dict(cap, managedForeignCalls=[]))
            self.assertTrue(audit.polyglot_call(call(name), {}, 'root', 'call'))
            self.assertEqual(['foreign-call'], [issue['code'] for issue in audit.issues])
            self.assertEqual([], audit.foreign_calls)
        for bound in ({'foreign-open': scalar('BoxedRep (Just Lifted)')},):
            audit = audit_core.Audit([], cap)
            self.assertTrue(audit.polyglot_call(call(), bound, 'root', 'call'))
            self.assertEqual(['foreign-call'], [issue['code'] for issue in audit.issues])
        bad = call(); bad[6]['foreignCall']['target']['symbol'] = 'thc_io_v1_unknown'
        audit = audit_core.Audit([], cap)
        self.assertTrue(audit.polyglot_call(bad, {}, 'root', 'call'))
        self.assertEqual(['foreign-call'], [issue['code'] for issue in audit.issues])


if __name__ == '__main__':
    unittest.main()
