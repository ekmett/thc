#!/usr/bin/env python3
"""Exact boxed levity and zero-width State contracts for the MutVar slice."""
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


def fixture(name, lifted=True, kind='data'):
    def role(role):
        if role == 'state':
            return dict(kind='void', primReps=[], evaluated=True)
        if role == 'mutvar':
            return dict(kind='object', primReps=['BoxedRep (Just Unlifted)'], evaluated=True)
        return dict(kind=kind, primReps=[f'BoxedRep (Just {"Lifted" if lifted else "Unlifted"})'], evaluated=not lifted)
    contract = CAP['managedMutVarPrimitives'][name]
    parameters = [dict(id=f'x{i}', lifted=r == 'boxed' and lifted, rep=role(r)) for i, r in enumerate(contract['arguments'])]
    result = contract['result']
    if isinstance(result, list):
        fields = [role(r) for r in result]
        result = dict(kind='unknown', aggregate='unboxed-tuple', primReps=sum([r['primReps'] for r in fields], []), components=fields, evaluated=True)
    else:
        result = role(result)
    body = ['app', ['prim', name], [['var', p['id'], dict(rep=copy.deepcopy(p['rep']))] for p in parameters],
            [p['lifted'] for p in parameters], False, False, dict(rep=copy.deepcopy(result))]
    scalar = dict(kind='long', primReps=['IntRep'], evaluated=True)
    returned = ['case', body, 'result', [['default', None, [], ['lit', 'int', '0', dict(rep=scalar)]]],
                dict(rep=scalar, binder=dict(id='result', lifted=False, rep=result))]
    closure = dict(kind='closure', primReps=['BoxedRep (Just Lifted)'], evaluated=True)
    module = dict(schema=1, ghc='9.14.1', constructors=[], bindings=[dict(id='root', name='root',
                  lifted=True, arity=len(parameters), expr=['lam', parameters, returned, dict(rep=closure, resultRep=scalar)])])
    return module, body


def check(module):
    return audit.Audit([('mutvar.json', module)], CAP).run(['root'])


class MutVarContracts(unittest.TestCase):
    def test_both_exact_boxed_levities_and_all_reference_classes(self):
        for name in CAP['managedMutVarPrimitives']:
            for lifted in (False, True):
                for kind in ('data', 'object', 'closure'):
                    report = check(fixture(name, lifted, kind)[0])
                    self.assertTrue(report['accepted'], (name, lifted, kind, report['issues']))

    def test_missing_wrong_partial_and_overapplied_proofs(self):
        for name in CAP['managedMutVarPrimitives']:
            for mutation in ('missing', 'wrong-result', 'unknown-argument', 'levity-flag', 'partial', 'overapplied'):
                module, app = fixture(name)
                if mutation == 'missing':
                    app[6].pop('rep')
                elif mutation == 'wrong-result':
                    app[6]['rep'] = dict(kind='long', primReps=['IntRep'], evaluated=True)
                elif mutation == 'unknown-argument':
                    app[2][0][2]['rep']['kind'] = 'unknown'
                elif mutation == 'levity-flag':
                    app[3][0] = not app[3][0]
                elif mutation == 'partial':
                    app[2].pop(); app[3].pop()
                else:
                    app[2].append(copy.deepcopy(app[2][0])); app[3].append(False)
                report = check(module)
                self.assertIn('primitive-representation', {i['code'] for i in report['issues']}, (name, mutation))

    def test_state_cannot_be_replaced_by_empty_tuple_or_omitted(self):
        for name in ('newMutVar#', 'readMutVar#'):
            for mutation in ('empty-tuple', 'missing-state'):
                module, app = fixture(name)
                proof = app[6]['rep']
                if mutation == 'empty-tuple':
                    proof['components'][0].update(aggregate='unboxed-tuple', components=[], kind='unknown')
                else:
                    proof['components'].pop(0)
                self.assertIn('primitive-representation', {i['code'] for i in check(module)['issues']}, (name, mutation))

    def test_payload_is_boxed_not_arbitrary_runtime_rep(self):
        for name, index in [('newMutVar#', 0), ('writeMutVar#', 1)]:
            module, app = fixture(name)
            app[2][index][2]['rep'] = dict(kind='long', primReps=['IntRep'], evaluated=True)
            app[3][index] = False
            self.assertIn('primitive-representation', {i['code'] for i in check(module)['issues']})
        module, app = fixture('readMutVar#')
        app[6]['rep']['components'][1] = dict(kind='long', primReps=['IntRep'], evaluated=True)
        app[6]['rep']['primReps'] = ['IntRep']
        self.assertIn('primitive-representation', {i['code'] for i in check(module)['issues']})

    def test_lexical_ref_cannot_be_relabelled_with_an_unlifted_occurrence(self):
        for name in ('readMutVar#', 'writeMutVar#'):
            module, _ = fixture(name)
            module['bindings'][0]['expr'][1][0]['rep']['primReps'] = ['BoxedRep (Just Lifted)']
            self.assertIn('scalar-representation', {i['code'] for i in check(module)['issues']})

    def test_first_class_and_concurrent_operations_remain_unsupported(self):
        for name in CAP['managedMutVarPrimitives']:
            module, app = fixture(name)
            app[:] = ['prim', name]
            self.assertFalse(check(module)['accepted'])
        for name in ('atomicSwapMutVar#', 'atomicModifyMutVar2#', 'casMutVar#'):
            self.assertNotIn(name, CAP['primitives'])


if __name__ == '__main__':
    unittest.main()
