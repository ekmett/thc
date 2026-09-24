#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Synthetic exact metadata contracts, not execution/concurrency tests for MVars."""
import copy
import importlib.util
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('audit_core', ROOT / 'audit-core.py')
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)
CAP = json.loads((ROOT / 'core-capabilities.json').read_text())
CONTRACTS = {
    'newMVar#': (['state'], ['state', 'mvar']),
    'takeMVar#': (['mvar', 'state'], ['state', 'boxed']),
    'readMVar#': (['mvar', 'state'], ['state', 'boxed']),
    'putMVar#': (['mvar', 'boxed', 'state'], 'state'),
    'tryTakeMVar#': (['mvar', 'state'], ['state', 'flag', 'boxed']),
    'tryReadMVar#': (['mvar', 'state'], ['state', 'flag', 'boxed']),
    'tryPutMVar#': (['mvar', 'boxed', 'state'], ['state', 'flag']),
    'isEmptyMVar#': (['mvar', 'state'], ['state', 'flag']),
}


def leaf(kind, *registers, evaluated=True):
    return dict(kind=kind, primReps=list(registers), evaluated=evaluated)


def tuple_proof(*components):
    return dict(kind='unknown', aggregate='unboxed-tuple', evaluated=True,
                components=list(components), primReps=[r for c in components for r in c['primReps']])


def role_proof(role, lifted=True, kind='data'):
    if role == 'state':
        return leaf('void')
    if role == 'mvar':
        return leaf('object', 'BoxedRep (Just Unlifted)')
    if role == 'flag':
        return leaf('long', 'IntRep')
    return leaf(kind, f'BoxedRep (Just {"Lifted" if lifted else "Unlifted"})', evaluated=not lifted)


def fixture(name, lifted=True, kind='data'):
    arguments, result = CONTRACTS[name]
    parameters = [dict(id=f'x{i}', lifted=r == 'boxed' and lifted, rep=role_proof(r, lifted, kind))
                  for i, r in enumerate(arguments)]
    proof = tuple_proof(*(role_proof(r, lifted, kind) for r in result)) if isinstance(result, list) else role_proof(result)
    app = ['app', ['prim', name], [['var', p['id'], dict(rep=copy.deepcopy(p['rep']))] for p in parameters],
           [p['lifted'] for p in parameters], False, False, dict(rep=copy.deepcopy(proof))]
    scalar = leaf('long', 'IntRep')
    body = ['case', app, 'result', [['default', None, [], ['lit', 'int', '0', dict(rep=scalar)]]],
            dict(rep=scalar, binder=dict(id='result', lifted=False, rep=proof))]
    closure = leaf('closure', 'BoxedRep (Just Lifted)')
    module = dict(schema=1, ghc='9.14.1', constructors=[], bindings=[dict(
        id='root', name='root', lifted=True, arity=len(parameters),
        expr=['lam', parameters, body, dict(rep=closure, resultRep=scalar)])])
    return module, app


def report(module):
    return audit.Audit([('synthetic-managed-mvar.json', module)], CAP).run(['root'])


def invalid_roles(role):
    vector = leaf('vector', 'VecRep 2 Int64ElemRep')
    vector['vector'] = dict(lanes=2, element='Int64ElemRep')
    candidates = [None, {}, leaf('unknown'), leaf('unknown', 'BoxedRep (Just Unlifted)'),
                  leaf('address', 'AddrRep'), leaf('float', 'FloatRep'), leaf('double', 'DoubleRep'),
                  vector, tuple_proof(), tuple_proof(role_proof(role)),
                  leaf('object', 'BoxedRep Nothing'), leaf('long', 'WordRep'),
                  leaf('long', 'Int8Rep'), leaf('long', 'Int16Rep'), leaf('long', 'Int32Rep'), leaf('long', 'Int64Rep')]
    if role != 'state':
        candidates.append(leaf('void'))
    if role != 'flag':
        candidates.append(leaf('long', 'IntRep'))
    if role == 'state':
        candidates += [leaf('void', 'VoidRep'), leaf('object', 'BoxedRep (Just Unlifted)')]
    if role == 'mvar':
        candidates += [leaf('object', 'BoxedRep (Just Lifted)'), leaf('data', 'BoxedRep (Just Unlifted)'),
                       leaf('closure', 'BoxedRep (Just Unlifted)')]
    if role == 'boxed':
        candidates += [leaf('data', 'BoxedRep (Just Lifted)', 'BoxedRep (Just Lifted)')]
    if role == 'flag':
        candidates.append(leaf('object', 'BoxedRep (Just Lifted)'))
    return candidates


class ManagedMVarContracts(unittest.TestCase):
    def reject_primitive(self, module):
        result = report(module)
        self.assertFalse(result['accepted'])
        self.assertIn('primitive-representation', {item['code'] for item in result['issues']}, result['issues'])

    def test_exact_eight_capabilities_and_logical_arities(self):
        self.assertEqual({name: dict(arguments=args, result=result) for name, (args, result) in CONTRACTS.items()},
                         CAP['managedMVarPrimitives'])
        for name, (arguments, _) in CONTRACTS.items():
            self.assertEqual(len(arguments), CAP['primitives'][name])

    def test_both_boxed_levities_and_all_reference_kinds(self):
        for name in CONTRACTS:
            for lifted in (False, True):
                for kind in ('data', 'closure', 'object'):
                    with self.subTest(name=name, lifted=lifted, kind=kind):
                        result = report(fixture(name, lifted, kind)[0])
                        self.assertTrue(result['accepted'], result['issues'])

    def test_each_argument_requires_its_exact_role(self):
        for name, (arguments, _) in CONTRACTS.items():
            for index, role in enumerate(arguments):
                for bad in invalid_roles(role):
                    with self.subTest(name=name, index=index, bad=bad):
                        module, app = fixture(name)
                        app[2][index][2]['rep'] = copy.deepcopy(bad)
                        self.reject_primitive(module)

    def test_each_result_field_requires_its_exact_role(self):
        for name, (_, result) in CONTRACTS.items():
            roles = result if isinstance(result, list) else [result]
            for index, role in enumerate(roles):
                for bad in invalid_roles(role):
                    with self.subTest(name=name, index=index, bad=bad):
                        module, app = fixture(name)
                        if isinstance(result, list):
                            proof = app[6]['rep']
                            proof['components'][index] = copy.deepcopy(bad)
                            proof['primReps'] = [r for c in proof['components'] if isinstance(c, dict)
                                                for r in c.get('primReps', [])]
                        else:
                            app[6]['rep'] = copy.deepcopy(bad)
                        self.reject_primitive(module)

    def test_arguments_require_boolean_flags_matching_exact_levity(self):
        for name in CONTRACTS:
            for lifted in (False, True):
                for index in range(len(CONTRACTS[name][0])):
                    for bad in (None, 0, 1, 'false', []):
                        with self.subTest(name=name, lifted=lifted, index=index, bad=bad):
                            module, app = fixture(name, lifted)
                            app[3][index] = bad
                            self.reject_primitive(module)
                    module, app = fixture(name, lifted)
                    app[3][index] = not app[3][index]
                    self.reject_primitive(module)
            for bad in (None, [], [False] * 5):
                module, app = fixture(name)
                app[3] = bad
                self.reject_primitive(module)

    def test_missing_unknown_or_malformed_metadata_is_rejected(self):
        for name in CONTRACTS:
            for mutation in ('result-missing', 'result-scalar', 'argument-missing', 'result-evaluated', 'argument-evaluated'):
                with self.subTest(name=name, mutation=mutation):
                    module, app = fixture(name)
                    if mutation == 'result-missing':
                        app[6].pop('rep')
                    elif mutation == 'result-scalar':
                        app[6]['rep'] = leaf('long', 'IntRep')
                    elif mutation == 'argument-missing':
                        app[2][0][2].pop('rep')
                    else:
                        proof = app[6]['rep'] if mutation == 'result-evaluated' else app[2][0][2]['rep']
                        proof['evaluated'] = 1
                    self.assertFalse(report(module)['accepted'])

    def test_exact_saturation_and_no_first_class_primitive(self):
        for name in CONTRACTS:
            for mutation in ('partial', 'overapplied', 'first-class'):
                with self.subTest(name=name, mutation=mutation):
                    module, app = fixture(name)
                    if mutation == 'partial':
                        app[2].pop(); app[3].pop()
                    elif mutation == 'overapplied':
                        app[2].append(copy.deepcopy(app[2][0])); app[3].append(app[3][0])
                    else:
                        app[:] = ['prim', name]
                    result = report(module)
                    self.assertFalse(result['accepted'])
                    self.assertIn('primitive-arity', {item['code'] for item in result['issues']})

    def test_tuple_flattening_retains_zero_width_state_and_order(self):
        for name, (_, result) in CONTRACTS.items():
            if not isinstance(result, list):
                continue
            for mutation in ('omit-state', 'extra-state', 'empty-tuple-state', 'reverse', 'flat-void',
                             'flat-empty', 'flat-extra', 'flat-missing', 'wrong-kind', 'wrong-aggregate', 'missing-components'):
                with self.subTest(name=name, mutation=mutation):
                    module, app = fixture(name)
                    proof = app[6]['rep']
                    if mutation == 'omit-state':
                        proof['components'].pop(0)
                    elif mutation == 'extra-state':
                        proof['components'].insert(0, role_proof('state'))
                    elif mutation == 'empty-tuple-state':
                        proof['components'][0] = tuple_proof()
                    elif mutation == 'reverse':
                        proof['components'].reverse()
                        proof['primReps'] = [r for c in proof['components'] for r in c['primReps']]
                    elif mutation == 'flat-void':
                        proof['primReps'].insert(0, 'VoidRep')
                    elif mutation == 'flat-empty':
                        proof['primReps'] = []
                    elif mutation == 'flat-extra':
                        proof['primReps'].append('IntRep')
                    elif mutation == 'flat-missing':
                        proof.pop('primReps')
                    elif mutation == 'wrong-kind':
                        proof['kind'] = 'object'
                    elif mutation == 'wrong-aggregate':
                        proof['aggregate'] = 'boxed'
                    else:
                        proof.pop('components')
                    self.reject_primitive(module)

    def test_put_result_is_state_not_singleton_tuple(self):
        module, app = fixture('putMVar#')
        app[6]['rep'] = tuple_proof(role_proof('state'))
        self.reject_primitive(module)

    def test_try_payload_flat_register_order_is_exact(self):
        for name in ('tryTakeMVar#', 'tryReadMVar#'):
            module, app = fixture(name)
            app[6]['rep']['primReps'].reverse()
            self.reject_primitive(module)

    def test_boxed_payload_does_not_require_evaluation_evidence(self):
        # Static representation validation must not promise evaluation of a
        # failed try's unspecified payload or force a put's lifted closure.
        for name in ('tryTakeMVar#', 'tryReadMVar#'):
            for lifted in (False, True):
                module, app = fixture(name, lifted, 'closure')
                app[6]['rep']['components'][-1]['evaluated'] = False
                self.assertTrue(report(module)['accepted'])
        for name in ('putMVar#', 'tryPutMVar#'):
            module, app = fixture(name, True, 'closure')
            self.assertIs(app[2][1][2]['rep']['evaluated'], False)
            self.assertTrue(report(module)['accepted'])

    def test_lexical_boxed_levity_cannot_be_relabelled_at_occurrence(self):
        for name in CONTRACTS:
            for index, role in enumerate(CONTRACTS[name][0]):
                if role not in ('mvar', 'boxed'):
                    continue
                module, _ = fixture(name)
                proof = module['bindings'][0]['expr'][1][index]['rep']
                proof['primReps'] = ['BoxedRep (Just Lifted)' if role == 'mvar' else 'BoxedRep (Just Unlifted)']
                result = report(module)
                self.assertFalse(result['accepted'])
                self.assertIn('scalar-representation', {item['code'] for item in result['issues']})

    def test_known_local_or_global_scalars_cannot_be_relabelled_as_mvar_roles(self):
        for name, (arguments, _) in CONTRACTS.items():
            for index in range(len(arguments)):
                for global_binding in (False, True):
                    with self.subTest(name=name, index=index, global_binding=global_binding):
                        module, app = fixture(name)
                        scalar = leaf('long', 'IntRep')
                        if global_binding:
                            app[2][index][1] = 'scalarGlobal'
                            module['bindings'].append(dict(id='scalarGlobal', name='scalarGlobal', lifted=False,
                                arity=0, rep=scalar, expr=['lit', 'int', '0', dict(rep=scalar)]))
                        else:
                            module['bindings'][0]['expr'][1][index]['rep'] = scalar
                        self.reject_primitive(module)

    def test_unknown_boxed_levity_refinement_cannot_change_physical_role(self):
        module, _ = fixture('newMVar#')
        module['bindings'][0]['expr'][1][0]['rep'] = leaf('object', 'BoxedRep Nothing')
        self.reject_primitive(module)
        module, _ = fixture('readMVar#')
        module['bindings'][0]['expr'][1][0]['rep'] = leaf('object', 'BoxedRep Nothing')
        self.assertTrue(report(module)['accepted'])
        module, _ = fixture('readMVar#')
        module['bindings'][0]['expr'][1][0]['rep'] = leaf('unknown', 'IntRep')
        self.reject_primitive(module)

    def test_no_guest_fork_async_delivery_or_full_handle_claim(self):
        for name in ('fork#', 'forkOn#', 'killThread#', 'catchRetry#', 'atomically#'):
            self.assertNotIn(name, CAP['primitives'])


if __name__ == '__main__':
    unittest.main()
