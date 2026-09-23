#!/usr/bin/env python3
"""Exact State#/unlifted reference contracts for the managed ByteArray slice."""
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


def evaluated(proof):
    return dict(proof, evaluated=True, **({'components': [evaluated(c) for c in proof['components']]}
                                         if 'components' in proof else {}))


def fixture(name):
    contract = CAP['managedByteArrayPrimitives'][name]
    parameters = [dict(id=f'x{i}', lifted=False, rep=evaluated(proof))
                  for i, proof in enumerate(contract['arguments'])]
    body = ['app', ['prim', name], [['var', p['id'], dict(rep=copy.deepcopy(p['rep']))] for p in parameters],
            [False] * len(parameters), False, False, dict(rep=evaluated(contract['result']))]
    result = evaluated(contract['result'])
    constructors = []
    returned = body
    if audit.Audit.is_tuple(result):
        fields = [dict(id=f'field{i}', lifted=False, rep=copy.deepcopy(proof)) for i, proof in enumerate(result['components'])]
        constructors = [dict(id='Tuple2', kind='unboxed-tuple', arity=2,
                             fieldReps=[p['primReps'] for p in result['components']],
                             fieldLifted=[False, False], strictFields=[False, False])]
        scalar = dict(kind='long', primReps=['IntRep'], evaluated=True)
        returned = ['case', body, 'tuple', [['data', 'Tuple2', [p['id'] for p in fields],
                    ['lit', 'int', '0', dict(rep=scalar)], dict(binders=fields)]],
                    dict(rep=scalar, binder=dict(id='tuple', lifted=False, rep=result))]
        result = scalar
    closure = dict(kind='closure', primReps=['BoxedRep (Just Lifted)'], evaluated=True)
    module = dict(schema=1, ghc='9.14.1', constructors=constructors, bindings=[dict(id='root', name='root',
                  lifted=True, arity=len(parameters), expr=['lam', parameters, returned, dict(rep=closure, resultRep=result)])])
    return module, body


def check(module):
    return audit.Audit([('bytearray.json', module)], CAP).run(['root'])


class ByteArrayContracts(unittest.TestCase):
    def test_exact_contracts(self):
        for name in CAP['managedByteArrayPrimitives']:
            report = check(fixture(name)[0])
            self.assertTrue(report['accepted'], (name, report['issues']))

    def test_missing_wrong_and_partial_signatures(self):
        for name in CAP['managedByteArrayPrimitives']:
            for mutation in ('missing', 'wrong-result', 'unknown-argument', 'lifted', 'partial', 'overapplied'):
                module, app = fixture(name)
                if mutation == 'missing':
                    app[6].pop('rep')
                elif mutation == 'wrong-result':
                    app[6]['rep'] = dict(kind='long', primReps=['WordRep'], evaluated=True)
                elif mutation == 'unknown-argument':
                    app[2][0][2]['rep']['kind'] = 'unknown'
                elif mutation == 'lifted':
                    app[3][0] = True
                elif mutation == 'partial':
                    app[2].pop(); app[3].pop()
                else:
                    app[2].append(copy.deepcopy(app[2][0])); app[3].append(False)
                report = check(module)
                self.assertFalse(report['accepted'], (name, mutation))
                self.assertIn('primitive-representation', {i['code'] for i in report['issues']}, (name, mutation))

    def test_state_is_not_an_empty_tuple_and_reference_is_not_lifted(self):
        for name in ('newByteArray#', 'unsafeFreezeByteArray#'):
            for mutation in ('empty-tuple', 'missing-state', 'lifted-reference'):
                module, app = fixture(name)
                proof = app[6]['rep']
                if mutation == 'empty-tuple':
                    proof['components'][0].update(aggregate='unboxed-tuple', components=[], kind='unknown')
                elif mutation == 'missing-state':
                    proof['components'].pop(0)
                else:
                    proof['components'][1]['primReps'] = ['BoxedRep (Just Lifted)']
                    proof['primReps'] = ['BoxedRep (Just Lifted)']
                report = check(module)
                self.assertIn('primitive-representation', {i['code'] for i in report['issues']}, (name, mutation))

    def test_lexical_reference_cannot_be_relabelled_by_an_occurrence(self):
        for name in ('writeWord8Array#', 'unsafeFreezeByteArray#', 'sizeofByteArray#', 'indexWord8Array#', 'copyByteArray#'):
            module, _ = fixture(name)
            parameter = module['bindings'][0]['expr'][1][0]
            parameter['rep']['primReps'] = ['BoxedRep (Just Lifted)']
            report = check(module)
            self.assertIn('scalar-representation', {i['code'] for i in report['issues']}, name)

    def test_copy_requires_exact_proofs_for_both_references_offsets_count_and_state(self):
        for argument in range(6):
            for mutation in ('missing', 'wrong-rep', 'tuple-state'):
                module, app = fixture('copyByteArray#')
                metadata = app[2][argument][2]
                if mutation == 'missing':
                    metadata.pop('rep')
                elif mutation == 'wrong-rep':
                    metadata['rep']['primReps'] = ['BoxedRep (Just Lifted)'] if argument in (0, 2) else ['WordRep']
                else:
                    metadata['rep'].update(kind='unknown', aggregate='unboxed-tuple', components=[], primReps=[])
                report = check(module)
                self.assertIn('primitive-representation', {i['code'] for i in report['issues']}, (argument, mutation))
        module, app = fixture('copyByteArray#')
        app[6]['rep'].update(kind='unknown', aggregate='unboxed-tuple', components=[])
        self.assertIn('primitive-representation', {i['code'] for i in check(module)['issues']})
        for argument in (0, 2):
            module, _ = fixture('copyByteArray#')
            module['bindings'][0]['expr'][1][argument]['rep']['primReps'] = ['BoxedRep (Just Lifted)']
            self.assertIn('scalar-representation', {i['code'] for i in check(module)['issues']}, argument)


if __name__ == '__main__':
    unittest.main()
