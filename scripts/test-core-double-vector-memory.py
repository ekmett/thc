#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Exact DoubleX2 local memory proofs, including rejection by other registered families."""
import copy
import importlib.util
import json
from pathlib import Path
import unittest

from core_vector_memory import ARRAY, INDEX, STATE, DOUBLE_OPERATIONS as OPERATIONS, DOUBLE_READS as READS, DOUBLE_INDICES as INDICES, DOUBLE_WRITES as WRITES, read_case, validate_direct
from core_vectors import VECTOR_DOUBLE_REP, VECTOR32_REP, VECTOR_REP, TUPLE_DOUBLE_REP, LANE_DOUBLE_REP, proof_error

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('audit_core', ROOT / 'audit-core.py')
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)
CAP = json.loads((ROOT / 'core-capabilities.json').read_text())
CLOSURE = dict(kind='closure', primReps=['BoxedRep (Just Lifted)'], evaluated=True)


def var(identity, proof):
    return ['var', identity, dict(rep=copy.deepcopy(proof))]


def binder(identity, proof):
    return dict(id=identity, lifted=False, coercion=False, rep=copy.deepcopy(proof))


def call(name, args, proof):
    return ['app', ['prim', name, dict(rep=copy.deepcopy(CLOSURE))], args,
            [False] * len(args), False, False, dict(rep=copy.deepcopy(proof))]


def result(evaluated=False):
    return dict(kind='unknown', primReps=['VecRep 2 DoubleElemRep'], evaluated=evaluated,
                aggregate='unboxed-tuple', vector=copy.deepcopy(VECTOR_DOUBLE_REP['vector']),
                components=[copy.deepcopy(STATE), copy.deepcopy(VECTOR_DOUBLE_REP)])


def fixture(name='readDoubleX2Array#'):
    params = [binder('array', ARRAY), binder('offset', INDEX), binder('state', STATE)]
    constructors = [dict(id='Tuple2', kind='unboxed-tuple', arity=2)]
    lane_ids = ['lane0', 'lane1']
    def consume(vector):
        unpack = call('unpackDoubleX2#', [vector], TUPLE_DOUBLE_REP)
        return ['case', unpack, 'lanes', [['data', 'Tuple2', lane_ids,
            call('double2Int#', [var('lane0', LANE_DOUBLE_REP)], INDEX),
            dict(binders=[binder(i, LANE_DOUBLE_REP) for i in lane_ids])]],
            dict(rep=copy.deepcopy(INDEX), binder=binder('lanes', TUPLE_DOUBLE_REP))]
    args = [var('array', ARRAY), var('offset', INDEX)]
    if name in READS:
        app = call(name, args + [var('state', STATE)], result())
        body = ['case', app, 'whole', [['data', 'Tuple2', ['nextState', 'vector'],
            consume(var('vector', VECTOR_DOUBLE_REP)),
            dict(binders=[binder('nextState', STATE), binder('vector', VECTOR_DOUBLE_REP)])]],
            dict(rep=copy.deepcopy(INDEX), binder=binder('whole', result(True)))]
    elif name in INDICES:
        app = call(name, args, VECTOR_DOUBLE_REP)
        body = consume(app)
    else:
        vector = call('broadcastDoubleX2#', [['lit', 'double', '1.25', dict(rep=copy.deepcopy(LANE_DOUBLE_REP))]], VECTOR_DOUBLE_REP)
        app = call(name, args + [vector, var('state', STATE)], STATE)
        body = ['case', app, 'nextState', [['default', None, [], ['lit', 'int', '7', dict(rep=copy.deepcopy(INDEX))], dict(binders=[])]],
                dict(rep=copy.deepcopy(INDEX), binder=binder('nextState', STATE))]
    module = dict(schema=1, ghc='9.14.1', constructors=constructors, bindings=[dict(
        id='root', name='root', arity=3, lifted=True, rep=copy.deepcopy(CLOSURE),
        expr=['lam', params, body, dict(rep=copy.deepcopy(CLOSURE), resultRep=copy.deepcopy(INDEX))])])
    return module, app, body


def check(module):
    return audit.Audit([('vector-memory', module)], CAP).run(['root'])


class DoubleVectorMemoryProofTest(unittest.TestCase):
    def test_same_width_two_lane_integer_proof_is_not_double_memory(self):
        self.assertIsNone(proof_error(VECTOR_REP))
        for name in OPERATIONS:
            sites = ('producer', 'whole', 'producer-component', 'whole-component', 'pattern') if name in READS else ('direct',)
            for site in sites:
                module, app, body = fixture(name)
                if site == 'direct':
                    proof = app[2][2][6]['rep'] if name in WRITES else app[6]['rep']
                else:
                    producer, whole = app[6]['rep'], body[4]['binder']['rep']
                    proof = {'producer': producer, 'whole': whole,
                             'producer-component': producer['components'][1],
                             'whole-component': whole['components'][1],
                             'pattern': body[3][0][4]['binders'][1]['rep']}[site]
                proof['primReps'] = copy.deepcopy(VECTOR_REP['primReps'])
                proof['vector'] = copy.deepcopy(VECTOR_REP['vector'])
                self.assertFalse(check(module)['accepted'], (name, site))

    def test_all_six_contracts_and_no_generic_field_expansion(self):
        self.assertEqual(len(OPERATIONS), 6)
        for name in sorted(OPERATIONS):
            module, app, _ = fixture(name)
            report = check(module)
            self.assertTrue(report['accepted'], (name, report['issues']))
            self.assertEqual(CAP['primitives'][name], len(app[2]))
        self.assertNotIn('VecRep 2 DoubleElemRep', CAP['fieldRepresentations'])

    def test_double_proofs_cannot_be_used_by_other_memory_families(self):
        for name in OPERATIONS:
            for family in ('Int32', 'Word32', 'Float'):
                module, app, _ = fixture(name)
                app[1][1] = name.replace('DoubleX2', family+'X4').replace('DoubleArray', family+'Array')
                self.assertIn(app[1][1], CAP['primitives'])
                self.assertNotEqual(app[1][1], name)
                self.assertFalse(check(module)['accepted'], (name, family))

    def test_family_registry_is_closed_and_each_proof_is_exact(self):
        from core_vector_memory import VECTOR_PROOFS, vector_proof
        self.assertEqual(len(VECTOR_PROOFS), 24)
        for name in OPERATIONS:
            self.assertEqual(vector_proof(name), VECTOR_DOUBLE_REP)
        for name in ('indexDoubleX4Array#', 'readDoubleX8Array#', 'writeDoubleOffAddrAsDoubleX2#'):
            self.assertNotIn(name, CAP['primitives'])
            with self.assertRaises(ValueError):
                vector_proof(name)

    def test_pinned_outer_vector_annotation_is_not_generic_vector_proof(self):
        for name in READS:
            module, app, body = fixture(name)
            self.assertEqual(app[6]['rep']['evaluated'], False)
            self.assertTrue(body[4]['binder']['rep']['evaluated'])
            self.assertIsNone(proof_error(app[6]['rep']))
            self.assertEqual('unboxed-tuple', app[6]['rep']['aggregate'])
            self.assertEqual('unknown', app[6]['rep']['kind'])
            self.assertIsNotNone(read_case(body, {c['id']: c for c in module['constructors']}))

    def test_read_binders_are_values_not_coercions(self):
        for name in READS:
            for site in range(3):
                for value in (True, None, 0, 'false'):
                    module, _, body = fixture(name)
                    record = body[4]['binder'] if site == 0 else body[3][0][4]['binders'][site - 1]
                    record['coercion'] = value
                    self.assertFalse(check(module)['accepted'], (name, site, value))

    def test_read_aggregate_mutations_on_both_exact_sites(self):
        mutations = [
            lambda p: p.pop('vector'),
            lambda p: p.update(vector=dict(lanes=4, element='Int32ElemRep')),
            lambda p: p.update(vector=dict(lanes=4, element='DoubleElemRep')),
            lambda p: p.update(vector=dict(lanes=True, element='DoubleElemRep')),
            lambda p: p.update(kind='vector'),
            lambda p: p.update(kind='object'),
            lambda p: p.update(aggregate='unboxed-sum'),
            lambda p: p.update(primReps=['DoubleRep'] * 2),
            lambda p: p.update(evaluated=0),
            lambda p: p['components'].reverse(),
            lambda p: p['components'].pop(),
            lambda p: p['components'][0].update(kind='unknown', aggregate='unboxed-tuple', components=[]),
            lambda p: p['components'][0].update(primReps=['IntRep']),
            lambda p: p['components'][1].update(kind='unknown'),
            lambda p: p['components'][1].update(vector=dict(lanes=8, element='Int16ElemRep')),
        ]
        for name in READS:
            for site in ('producer', 'binder'):
                for i, mutate in enumerate(mutations):
                    with self.subTest(name=name, site=site, mutation=i):
                        module, app, body = fixture(name)
                        mutate(app[6]['rep'] if site == 'producer' else body[4]['binder']['rep'])
                        self.assertFalse(check(module)['accepted'])

        for name in READS:
            for site in ('producer', 'binder', 'producer-component', 'binder-component', 'pattern'):
                for count in (2.0, 2.5, True, '2', None):
                    module, app, body = fixture(name)
                    producer, whole = app[6]['rep'], body[4]['binder']['rep']
                    proof = {'producer': producer, 'binder': whole,
                             'producer-component': producer['components'][1],
                             'binder-component': whole['components'][1],
                             'pattern': body[3][0][4]['binders'][1]['rep']}[site]
                    proof['vector']['lanes'] = count
                    self.assertFalse(check(module)['accepted'], (name, site, count))
            for arity in (2.0, 2.5, True, '2', None):
                module, _, _ = fixture(name)
                module['constructors'][0]['arity'] = arity
                self.assertFalse(check(module)['accepted'], (name, arity))

    def test_all_operations_require_exact_flags_arity_and_arguments(self):
        for name in INDICES | WRITES:
            for count in (2.0, 2.5, True, '2', None):
                module, app, _ = fixture(name)
                proof = app[2][2][6]['rep'] if name in WRITES else app[6]['rep']
                proof['vector']['lanes'] = count
                self.assertFalse(check(module)['accepted'], (name, count))
        for name in OPERATIONS:
            for flag in (True, None, 0, 'false'):
                module, app, _ = fixture(name)
                app[3][0] = flag
                self.assertFalse(check(module)['accepted'], (name, flag))
            for delta in (-1, 1):
                module, app, _ = fixture(name)
                if delta < 0:
                    app[2].pop(); app[3].pop()
                else:
                    app[2].append(copy.deepcopy(app[2][0])); app[3].append(False)
                self.assertFalse(check(module)['accepted'], (name, delta))
            module, app, _ = fixture(name)
            app[2][0][2]['rep']['primReps'] = ['BoxedRep (Just Lifted)']
            self.assertFalse(check(module)['accepted'])
            module, app, _ = fixture(name)
            app[2][1][2]['rep']['primReps'] = ['WordRep']
            self.assertFalse(check(module)['accepted'])

    def test_lexical_array_identity_cannot_be_relabelled(self):
        for name in OPERATIONS:
            module, _, _ = fixture(name)
            module['bindings'][0]['expr'][1][0]['rep']['primReps'] = ['BoxedRep (Just Lifted)']
            self.assertFalse(check(module)['accepted'])

    def test_read_constructor_and_pattern_identity(self):
        mutations = [
            lambda m, b: m['constructors'].pop(0),
            lambda m, b: m['constructors'][0].update(kind='boxed'),
            lambda m, b: m['constructors'][0].update(arity=4),
            lambda m, b: b[3][0].__setitem__(0, 'default'),
            lambda m, b: b[3].append(copy.deepcopy(b[3][0])),
            lambda m, b: b[3][0][2].__setitem__(1, 'nextState'),
            lambda m, b: b[3][0][2].__setitem__(1, 'whole'),
            lambda m, b: b[3][0][4]['binders'].reverse(),
            lambda m, b: b[3][0][4]['binders'][0].update(lifted=True),
            lambda m, b: b[3][0][4]['binders'][1].update(rep=copy.deepcopy(VECTOR32_REP)),
            lambda m, b: b[4]['binder'].update(id='wrong'),
            lambda m, b: b[4]['binder'].update(lifted=True),
            lambda m, b: b[4]['binder']['rep'].update(evaluated=False),
            lambda m, b: b[3][0][4]['binders'][0].update(joinValueArity=0),
        ]
        for i, mutate in enumerate(mutations):
            with self.subTest(mutation=i):
                module, _, body = fixture()
                mutate(module, body)
                self.assertFalse(check(module)['accepted'])

    def test_whole_tuple_cannot_escape_even_without_occurrence_metadata(self):
        for value in (['var', 'whole'], var('whole', result(True)),
                      ['lam', [], ['var', 'whole'], dict(rep=CLOSURE, resultRep=INDEX)]):
            module, _, body = fixture()
            body[3][0][3] = value
            self.assertFalse(check(module)['accepted'])

    def test_shared_proof_object_is_only_exempt_at_checked_sites(self):
        module, app, body = fixture()
        shared = body[4]['binder']['rep']
        app[6]['rep'] = shared
        self.assertTrue(check(module)['accepted'])
        module['bindings'][0]['expr'][3]['resultRep'] = shared
        self.assertFalse(check(module)['accepted'])

    def test_nested_proof_is_not_swallowed_by_local_exemption(self):
        for site in ('producer', 'binder', 'pattern'):
            module, app, body = fixture()
            metadata = app[6]['rep'] if site == 'producer' else body[4]['binder']['rep'] if site == 'binder' else body[3][0][4]
            metadata['nested'] = dict(resultRep=result(True))
            self.assertFalse(check(module)['accepted'], site)

    def test_read_is_not_a_first_class_or_transportable_tuple_producer(self):
        module, app, _ = fixture()
        module['bindings'][0]['expr'][2] = app
        module['bindings'][0]['expr'][3]['resultRep'] = result(True)
        self.assertFalse(check(module)['accepted'])
        with self.assertRaises(ValueError):
            validate_direct('readDoubleX2Array#', app[2], app[3], result())

    def test_vector_function_join_constructor_and_capture_boundaries_stay_closed(self):
        for mode in ('result', 'argument', 'capture', 'join', 'constructor'):
            module, _, body = fixture()
            vector = var('vector', VECTOR_DOUBLE_REP)
            if mode == 'result':
                body[3][0][3] = vector
                body[4]['rep'] = copy.deepcopy(VECTOR_DOUBLE_REP)
                module['bindings'][0]['expr'][3]['resultRep'] = copy.deepcopy(VECTOR_DOUBLE_REP)
            elif mode == 'argument':
                body[3][0][3] = ['app', ['var', 'unknownFunction'], [vector], [False], False, False, dict(rep=INDEX)]
            elif mode == 'capture':
                body[3][0][3] = ['lam', [], vector, dict(rep=CLOSURE, resultRep=VECTOR_DOUBLE_REP)]
            elif mode == 'join':
                body[3][0][3] = ['let', False, [dict(id='join', lifted=True, rep=CLOSURE,
                    joinValueArity=0, joinResultRep=VECTOR_DOUBLE_REP, info=dict(joinArity=0), expr=vector)],
                    ['var', 'join', dict(rep=VECTOR_DOUBLE_REP)], dict(rep=VECTOR_DOUBLE_REP)]
            else:
                module['constructors'].append(dict(id='Box', kind='boxed', arity=1,
                    fieldReps=[VECTOR_DOUBLE_REP['primReps']], fieldLifted=[False], strictFields=[False]))
                body[3][0][3] = ['app', ['con', 'Box', 1], [vector], [False], False, True,
                    dict(rep=dict(kind='data', primReps=['BoxedRep (Just Lifted)'], evaluated=True))]
            self.assertFalse(check(module)['accepted'], mode)

    def test_outer_result_mismatch_is_still_checked(self):
        for wrong in (dict(kind='double', primReps=['DoubleRep'], evaluated=True), STATE, ARRAY):
            module, _, body = fixture()
            body[4]['rep'] = copy.deepcopy(wrong)
            self.assertFalse(check(module)['accepted'])

    def test_non_read_cases_do_not_acquire_an_exemption(self):
        for name in INDICES | WRITES:
            module, app, _ = fixture(name)
            app[6]['rep'] = result(True)
            self.assertFalse(check(module)['accepted'])


if __name__ == '__main__':
    unittest.main()
