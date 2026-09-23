#!/usr/bin/env python3
"""Regression tests for lexical dependency closure and capability diagnostics."""
import importlib.util
import copy
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('audit_core', ROOT / 'audit-core.py')
audit_core = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit_core)
CAP = json.loads((ROOT / 'core-capabilities.json').read_text())


def bind(key, expr, lifted=True):
    return dict(id=key, name=key, lifted=lifted, arity=0, expr=expr)


def var(key):
    return ['var', key]


def lit(number):
    return ['lit', 'int', str(number)]


def run(expr, extra=(), constructors=(), cap=None):
    module = dict(schema=1, ghc='9.14.1', bindings=[bind('root', expr), *extra], constructors=list(constructors))
    return audit_core.Audit([('fixture.json', module)], cap or CAP).run(['root'])


LONG = dict(primReps=['IntRep'], kind='long', evaluated=True)
REFERENCE = dict(primReps=['BoxedRep (Just Lifted)'], kind='data', evaluated=False)
CLOSURE = dict(REFERENCE, kind='closure', evaluated=True)
TUPLE_CAP = dict(CAP, aggregateResults=['unboxed-tuple'])


def tuple_rep(*components):
    return dict(aggregate='unboxed-tuple', kind='unknown', evaluated=True,
                components=list(components), primReps=[r for c in components for r in c['primReps']])


def tuple_fixture(proof=None):
    """A scalar host wrapper immediately destructures a tuple-returning call."""
    proof = copy.deepcopy(proof if proof is not None else tuple_rep(LONG, LONG))
    constructors = {}

    def value(rep):
        if not audit_core.Audit.is_tuple(rep):
            if rep['kind'] == 'long':
                return [*var('x'), dict(rep=copy.deepcopy(rep))]
            constructors['Box'] = dict(id='Box', kind='boxed', arity=0, fieldReps=[], strictFields=[], fieldLifted=[])
            return ['con', 'Box', 0, dict(rep=copy.deepcopy(rep))]
        children = rep['components']
        key = f'Tuple{len(children)}'
        constructors[key] = dict(id=key, kind='unboxed-tuple', arity=len(children),
                                 fieldReps=[None] * len(children), strictFields=[False] * len(children),
                                 fieldLifted=[None] * len(children))
        if not children:
            return ['con', key, 0, dict(rep=copy.deepcopy(rep))]
        return ['app', ['con', key, len(children), dict(rep=CLOSURE)], [value(c) for c in children],
                [c.get('primReps') == ['BoxedRep (Just Lifted)'] for c in children], True, True,
                dict(rep=copy.deepcopy(rep))]

    parameter = dict(id='x', lifted=False, rep=LONG)
    producer = bind('producer', ['lam', [parameter], value(proof), dict(rep=CLOSURE, resultRep=copy.deepcopy(proof))])
    producer['rep'] = CLOSURE
    fields = [dict(id=f'field{i}', lifted=False, rep=copy.deepcopy(c)) for i, c in enumerate(proof['components'])]
    call = ['app', [*var('producer'), dict(rep=CLOSURE)], [[*var('x'), dict(rep=LONG)]], [False], False, False,
            dict(rep=copy.deepcopy(proof))]
    case = ['case', call, 'result', [['data', f'Tuple{len(fields)}', [f['id'] for f in fields],
            [*lit(0), dict(rep=LONG)], dict(binders=fields)]],
            dict(rep=LONG, binder=dict(id='result', lifted=False, rep=copy.deepcopy(proof)))]
    root = bind('root', ['lam', [parameter], case, dict(rep=CLOSURE, resultRep=LONG)])
    return dict(schema=1, ghc='9.14.1', bindings=[root, producer], constructors=list(constructors.values()))


def run_tuple(module):
    return audit_core.Audit([('tuple.json', module)], TUPLE_CAP).run(['root'])


def tuple_join_fixture(zero=False):
    module = tuple_fixture()
    producer = module['bindings'][1]['expr']
    proof = copy.deepcopy(producer[3]['resultRep'])
    rhs = producer[2] if zero else ['lam', [dict(id='arg', lifted=False, rep=LONG)], producer[2],
                                  dict(rep=CLOSURE, resultRep=copy.deepcopy(proof))]
    join = dict(id='finish', name='finish', lifted=not zero, rep=proof if zero else CLOSURE,
                joinValueArity=0 if zero else 1, joinResultRep=copy.deepcopy(proof),
                info=dict(joinArity=0 if zero else 1), expr=rhs)
    call = [*var('finish'), dict(rep=copy.deepcopy(proof))] if zero else [
        'app', [*var('finish'), dict(rep=CLOSURE)], [[*var('x'), dict(rep=LONG)]],
        [False], False, False, dict(rep=copy.deepcopy(proof))]
    producer[2] = ['let', False, [join], call, dict(rep=copy.deepcopy(proof))]
    return module, join


class AuditTest(unittest.TestCase):
    def test_scalar_lexical_occurrences_cannot_replace_exact_binder_registers(self):
        for primitive, expected, declared in [('quotRemInt#', 'IntRep', 'WordRep'), ('plusInt8#', 'Int8Rep', 'Word8Rep')]:
            scalar = dict(LONG, primReps=[expected])
            if primitive == 'quotRemInt#':
                module = tuple_fixture(tuple_rep(scalar, scalar))
                root = module['bindings'][0]['expr']
                root[1][0]['rep'] = dict(LONG, primReps=[declared])
                root[2][1] = ['app', ['prim', primitive], [['var', 'x', dict(rep=scalar)]] * 2,
                              [False, False], False, False, dict(rep=tuple_rep(scalar, scalar))]
                report = run_tuple(module)
            else:
                binder = dict(id='x', lifted=False, rep=dict(LONG, primReps=[declared]))
                body = ['app', ['prim', primitive], [['var', 'x', dict(rep=scalar)]] * 2,
                        [False, False], False, False, dict(rep=scalar)]
                report = run(['lam', [binder], body, dict(rep=CLOSURE, resultRep=scalar)])
            self.assertIn('scalar-representation', {i['code'] for i in report['issues']}, primitive)

    def test_scalar_lexical_absent_unknown_and_matching_proofs_remain_compatible(self):
        unknown = dict(kind='unknown', primReps=None, evaluated=False)
        for stored, occurrence in [(None, LONG), (unknown, LONG), (LONG, None), (LONG, unknown), (LONG, LONG)]:
            binder = dict(id='x', lifted=False)
            if stored is not None:
                binder['rep'] = stored
            body = ['var', 'x'] + ([dict(rep=occurrence)] if occurrence is not None else [])
            self.assertTrue(run(['lam', [binder], body])['accepted'], (stored, occurrence))
        # Lexical shadowing replaces the name, not the representation of one value.
        word = dict(LONG, primReps=['WordRep'])
        inner = ['lam', [dict(id='x', lifted=False, rep=word)], ['var', 'x', dict(rep=word)]]
        self.assertTrue(run(['lam', [dict(id='x', lifted=False, rep=LONG)], inner])['accepted'])

    def test_tuple_arithmetic_requires_exact_logical_results_and_scalar_arguments(self):
        for name, contract in CAP['tuplePrimitives'].items():
            scalar = dict(LONG, primReps=[contract['arguments'][0]])
            proof = tuple_rep(scalar, scalar)
            module = tuple_fixture(proof)
            call = module['bindings'][0]['expr'][2][1]
            call[:] = ['app', ['prim', name],
                       [['lit', 'word' if scalar['primReps'] == ['WordRep'] else 'int', '1', dict(rep=scalar)]] * 2,
                       [False, False], False, False, dict(rep=proof)]
            self.assertTrue(run_tuple(module)['accepted'], name)
            for mutation in ('nested', 'scalar', 'unknown', 'wrong-register', 'unknown-argument', 'lifted', 'partial', 'overapplied'):
                changed = copy.deepcopy(module)
                bad = changed['bindings'][0]['expr'][2][1]
                if mutation == 'nested':
                    bad[6]['rep']['components'][0] = tuple_rep(copy.deepcopy(scalar))
                elif mutation == 'scalar':
                    bad[6]['rep'] = copy.deepcopy(scalar)
                elif mutation == 'unknown':
                    bad[6].pop('rep')
                elif mutation == 'wrong-register':
                    bad[2][0][3]['rep']['primReps'] = ['WordRep' if scalar['primReps'] == ['IntRep'] else 'IntRep']
                elif mutation == 'unknown-argument':
                    bad[2][0][3]['rep']['kind'] = 'unknown'
                elif mutation == 'lifted':
                    bad[3][0] = True
                elif mutation == 'partial':
                    bad[2].pop(); bad[3].pop()
                else:
                    bad[2].append(copy.deepcopy(bad[2][0])); bad[3].append(False)
                report = run_tuple(changed)
                self.assertFalse(report['accepted'], (name, mutation))
                self.assertIn('primitive-representation', {i['code'] for i in report['issues']}, (name, mutation))

    def test_tuple_arithmetic_first_class_values_remain_unsupported(self):
        for name in CAP['tuplePrimitives']:
            self.assertIn('primitive-arity', {i['code'] for i in run(['prim', name])['issues']})

    def test_exact_tuple_join_results_include_zero_arity_binders(self):
        for zero in (False, True):
            module, _ = tuple_join_fixture(zero)
            self.assertTrue(run_tuple(module)['accepted'])
            oldcap = dict(TUPLE_CAP, aggregateJoinResults=[])
            report = audit_core.Audit([('join', module)], oldcap).run(['root'])
            self.assertIn('aggregate-boundary', {i['code'] for i in report['issues']})

    def test_tuple_join_result_lambda_and_call_proofs_must_agree(self):
        for location in ('join', 'lambda', 'call'):
            module, join = tuple_join_fixture()
            proof = (join['joinResultRep'] if location == 'join' else join['expr'][3]['resultRep']
                     if location == 'lambda' else module['bindings'][1]['expr'][2][3][6]['rep'])
            proof['components'][0] = tuple_rep(LONG)
            self.assert_shape_rejected(module)

    def test_zero_arity_tuple_join_cannot_capture_an_aggregate(self):
        module, join = tuple_join_fixture(True)
        producer = module['bindings'][1]['expr']
        region = producer[2]
        original = join['expr']
        proof = join['joinResultRep']
        join['expr'] = [*var('held'), dict(rep=proof)]
        producer[2] = ['case', original, 'held', [['default', None, [], region, dict(binders=[])]],
                       dict(rep=proof, binder=dict(id='held', lifted=False, rep=proof))]
        report = run_tuple(module)
        self.assertIn('unboxed-tuple join capture', [i['detail'] for i in report['issues']])

    def test_recursive_join_identity_shadows_outer_tuple_for_capture_checks(self):
        module, join = tuple_join_fixture()
        producer = module['bindings'][1]['expr']
        region = producer[2]; region[1] = True
        original = join['expr'][2]
        proof = join['joinResultRep']
        call = copy.deepcopy(region[3])
        join['expr'][2] = ['case', [*var('arg'), dict(rep=LONG)], 'condition', [
            ['lit', ['int', '0'], [], original, dict(binders=[])],
            ['default', None, [], call, dict(binders=[])]],
            dict(rep=proof, binder=dict(id='condition', lifted=False, rep=LONG))]
        producer[2] = ['case', copy.deepcopy(original), 'finish', [['default', None, [], region, dict(binders=[])]],
                       dict(rep=proof, binder=dict(id='finish', lifted=False, rep=proof))]
        self.assertTrue(run_tuple(module)['accepted'])

    def assert_shape_rejected(self, module):
        report = run_tuple(module)
        self.assertFalse(report['accepted'])
        self.assertIn('aggregate-shape', {issue['code'] for issue in report['issues']})

    def test_tuple_function_result_must_match_body_logical_shape(self):
        module = tuple_fixture()
        self.assertTrue(run_tuple(module)['accepted'])
        result = module['bindings'][1]['expr'][3]['resultRep']
        result['components'][0] = tuple_rep(LONG)
        self.assert_shape_rejected(module)

    def test_tuple_constructor_operands_must_match_components(self):
        module = tuple_fixture()
        result = module['bindings'][1]['expr'][2][6]['rep']
        result['components'][0] = tuple_rep(LONG)
        self.assert_shape_rejected(module)

    def test_tuple_case_binder_must_match_scrutinee(self):
        module = tuple_fixture()
        case = module['bindings'][0]['expr'][2]
        case[4]['binder']['rep']['components'][0] = tuple_rep(LONG)
        self.assert_shape_rejected(module)

    def test_tuple_alternative_binders_must_match_components_even_when_unused(self):
        module = tuple_fixture()
        field = module['bindings'][0]['expr'][2][3][0][4]['binders'][0]
        field['rep'] = tuple_rep(LONG)
        self.assert_shape_rejected(module)

    def test_tuple_variable_occurrence_must_match_lexical_binder(self):
        module = tuple_fixture()
        case = module['bindings'][0]['expr'][2]
        wrong = tuple_rep(tuple_rep(LONG), LONG)
        case[3][0][3] = ['case', [*var('result'), dict(rep=wrong)], 'again',
                        [['default', None, [], [*lit(0), dict(rep=LONG)], dict(binders=[])]],
                        dict(rep=LONG, binder=dict(id='again', lifted=False, rep=wrong))]
        self.assert_shape_rejected(module)

    def test_empty_tuple_positions_are_not_reconstructed_from_zero_width(self):
        empty = tuple_rep()
        proof = tuple_rep(empty, tuple_rep(empty))
        module = tuple_fixture(proof)
        self.assertTrue(run_tuple(module)['accepted'])
        module['bindings'][1]['expr'][3]['resultRep'] = tuple_rep(tuple_rep(empty), empty)
        self.assert_shape_rejected(module)

    def test_boxed_leaf_refinement_keeps_primitive_name_exact(self):
        for rep in (REFERENCE, dict(REFERENCE, primReps=['BoxedRep (Just Unlifted)'], evaluated=True)):
            for kind in ('data', 'closure', 'object'):
                module = tuple_fixture(tuple_rep(rep))
                operand = module['bindings'][1]['expr'][2][2][0]
                operand[3]['rep']['kind'] = kind
                self.assertTrue(run_tuple(module)['accepted'])
        module = tuple_fixture(tuple_rep(LONG))
        module['bindings'][1]['expr'][2][2][0][2]['rep']['primReps'] = ['WordRep']
        self.assert_shape_rejected(module)

    def test_boxed_singleton_is_not_an_unboxed_singleton(self):
        module = tuple_fixture(tuple_rep(REFERENCE))
        module['bindings'][0]['expr'][2][4]['binder']['rep'] = dict(REFERENCE, evaluated=True)
        self.assert_shape_rejected(module)

    def test_unknown_logical_layout_or_leaf_is_rejected(self):
        for change in ('layout', 'leaf'):
            module = tuple_fixture()
            result = module['bindings'][1]['expr'][3]['resultRep']
            if change == 'layout':
                result['components'] = None
            else:
                result['components'][0]['kind'] = 'unknown'
            self.assertIn('aggregate-representation', {i['code'] for i in run_tuple(module)['issues']})

    def test_malformed_host_lambda_metadata_is_reported_without_crashing(self):
        for metadata in (42, [], None):
            with self.subTest(metadata=metadata):
                module = tuple_fixture()
                module['bindings'][0]['expr'][3] = metadata
                report = run_tuple(module)
                self.assertFalse(report['accepted'])
                self.assertIn('expression-metadata', {i['code'] for i in report['issues']})

    def test_scalar_shadow_of_tuple_binder_is_not_an_aggregate_argument(self):
        module = tuple_fixture()
        case = module['bindings'][0]['expr'][2]
        shadow = dict(id='result', lifted=False, rep=LONG)
        rhs = ['app', ['prim', '+#'], [[*var('result'), dict(rep=LONG)], [*lit(1), dict(rep=LONG)]],
               [False, False], False, False, dict(rep=LONG)]
        local = bind('scalar', ['lam', [shadow], rhs, dict(rep=CLOSURE, resultRep=LONG)])
        case[3][0][3] = ['let', False, [local], [*lit(0), dict(rep=LONG)], dict(rep=LONG)]
        self.assertTrue(run_tuple(module)['accepted'])

    @unittest.skipUnless((ROOT.parent / 'build/tuple-return/oracle.tsv').exists(), 'prepare native tuple return fixture first')
    def test_genuine_native_tuple_exports_are_accepted(self):
        folder = ROOT.parent / 'build/tuple-return'
        entries = sorted({row.split('\t')[0] for row in (folder / 'oracle.tsv').read_text().splitlines()})
        for stage in ('pre-core', 'post-core'):
            module = json.loads((folder / stage / 'TupleReturnAudit.json').read_text())
            report = audit_core.Audit([(stage, module)], TUPLE_CAP).run(entries)
            self.assertTrue(report['accepted'], report['issues'])

    def test_lambda_shadows_same_spelled_global(self):
        report = run(['lam', [dict(id='shadow', lifted=True)], var('shadow')], [bind('shadow', var('unreachable'))])
        self.assertTrue(report['accepted'])
        self.assertEqual([b['id'] for b in report['reachableBindings']], ['root'])

    def test_nonrecursive_rhs_has_outer_scope_but_body_has_local(self):
        expr = ['let', False, [bind('x', var('x'))], var('x')]
        report = run(expr, [bind('x', var('dependency'))])
        self.assertEqual([b['id'] for b in report['reachableBindings']], ['root', 'x'])
        self.assertEqual(report['missingGlobals'][0]['reachableVia'], ['root', 'x', 'dependency'])
        self.assertEqual(len(report['dependencies']), 2)

    def test_recursive_group_closes_all_rhs_and_body_names(self):
        expr = ['let', True, [bind('x', var('y')), bind('y', var('x'))], var('y')]
        report = run(expr, [bind('x', var('unreachable')), bind('y', var('also-unreachable'))])
        self.assertTrue(report['accepted'])
        self.assertEqual(report['dependencies'], [])

    def test_case_binder_does_not_scope_over_scrutinee_or_other_alternatives(self):
        expr = ['case', var('caseBinder'), 'caseBinder', [
            ['default', None, ['field'], var('caseBinder')],
            ['default', None, [], var('field')]]]
        report = run(expr, [bind('caseBinder', lit(1))])
        self.assertEqual([b['id'] for b in report['reachableBindings']], ['root', 'caseBinder'])
        self.assertEqual([b['id'] for b in report['missingGlobals']], ['field'])
        self.assertIn('/alternatives/1/body', report['missingGlobals'][0]['references'][0]['path'])

    def test_cycles_are_visited_once_but_every_missing_reference_is_reported(self):
        expr = ['app', var('a'), [var('missing')], [True]]
        a = ['app', var('root'), [var('missing'), var('other')], [True, True]]
        report = run(expr, [bind('a', a)])
        self.assertEqual(len(report['reachableBindings']), 2)
        self.assertEqual([item['id'] for item in report['missingGlobals']], ['missing', 'other'])
        self.assertEqual(len(report['missingGlobals'][0]['references']), 2)

    def test_reports_all_reachable_capabilities_and_rejects_partial_primitive(self):
        expr = ['let', False, [bind('partial', ['prim', '+#']), bind('bad', ['lit', 'unsupported-literal', '2.5'])],
                ['app', ['prim', 'unsupported#'], [lit(1)], [False]]]
        report = run(expr)
        self.assertEqual({i['code'] for i in report['issues']}, {'primitive-arity', 'unsupported-literal', 'unsupported-primitive'})
        self.assertEqual([p['name'] for p in report['primitives']], ['+#', 'unsupported#'])

    def test_floating_scalars_do_not_expand_aggregate_or_literal_alternative_support(self):
        for kind, register in [('float', 'FloatRep'), ('double', 'DoubleRep')]:
            scalar = dict(kind=kind, primReps=[register], evaluated=True)
            audit = audit_core.Audit([], CAP)
            audit.representation(scalar, None, '/scalar')
            self.assertEqual([], audit.issues)
            audit.representation(tuple_rep(scalar), None, '/tuple')
            self.assertIn('aggregate-representation', [i['code'] for i in audit.issues])
            report = run(['case', ['lit', kind, '0.0'], 'x',
                          [['lit', [kind, '-0.0'], [], lit(1)], ['default', None, [], lit(0)]]])
            self.assertIn('alternative-kind', [i['code'] for i in report['issues']])

    def test_strictness_checked_for_construction_not_pattern_match(self):
        con = dict(id='Strict', name='Strict', arity=1, kind='boxed', fieldReps=[['BoxedRep (Just Lifted)']],
                   strictFields=[True], fieldLifted=[True])
        cap = dict(CAP, strictLiftedFields=False)
        report = run(['case', lit(0), 'b', [['data', 'Strict', ['x'], lit(0)]]], constructors=[con], cap=cap)
        self.assertTrue(report['accepted'])
        report = run(['con', 'Strict', 1], constructors=[con], cap=cap)
        self.assertEqual([i['code'] for i in report['issues']], ['strict-lifted-field'])
        self.assertTrue(run(['con', 'Strict', 1], constructors=[con], cap=dict(cap, strictLiftedFields=True))['accepted'])

    def test_representation_and_constructor_identity_are_not_inferred(self):
        con = dict(id='Pair#', name='Pair#', arity=1, kind='unboxed-tuple', fieldReps=[None],
                   strictFields=[False], fieldLifted=[None])
        report = run(['con', 'Pair#', 1], constructors=[con])
        self.assertEqual({i['code'] for i in report['issues']}, {'constructor-kind', 'constructor-field-representation'})
        self.assertEqual(run(['con', 'missing', 0])['issues'][0]['code'], 'missing-constructor')

    def test_optional_representation_and_pattern_metadata_preserves_scope(self):
        long = dict(primReps=['IntRep'], kind='long', evaluated=True)
        case_binder = dict(id='value', lifted=False, rep=long)
        expr = ['case', [*lit(7), dict(rep=long)], 'value', [
            ['default', None, [], ['var', 'value', dict(rep=long)], dict(binders=[])]],
            dict(rep=long, binder=case_binder)]
        self.assertTrue(run(expr)['accepted'])
        expr[4]['binder'] = dict(case_binder, id='wrong')
        self.assertIn('case-binder-metadata', {i['code'] for i in run(expr)['issues']})

    def test_inconsistent_representation_and_join_prefix_are_rejected(self):
        invalid = dict(primReps=['AddrRep'], kind='long', evaluated=True)
        self.assertIn('representation-proof', {i['code'] for i in run([*lit(1), dict(rep=invalid)])['issues']})
        join = bind('j', ['lam', [dict(id='x', lifted=False)], var('x')])
        join.update(joinValueArity=2, joinResultRep=dict(primReps=['IntRep'], kind='long', evaluated=False),
                    info=dict(joinArity=3))
        report = run(['let', False, [join], ['app', var('j'), [lit(1)], [False]]])
        self.assertIn('join-metadata', {i['code'] for i in report['issues']})

    def test_duplicate_definitions_fail_instead_of_silently_overwriting(self):
        module = dict(schema=1, ghc='9.14.1', bindings=[bind('root', lit(0))], constructors=[])
        report = audit_core.Audit([('a.json', module), ('b.json', module)], CAP).run(['root'])
        self.assertEqual(report['issues'][0]['code'], 'duplicate-binding')

    def test_narrow_unsigned_literals_enforce_ranges_in_values_and_alternatives(self):
        for width in (8, 16, 32):
            maximum = (1 << width) - 1
            for text in ('0', str(maximum), '-1', str(maximum + 1), '+1', '01', '-0', '1.0', ' 1', ''):
                valid = text in ('0', str(maximum))
                literal = ['lit', f'word{width}', text]
                alternative = ['case', lit(0), 'value', [
                    ['lit', [f'word{width}', text], [], lit(1)], ['default', None, [], lit(0)]]]
                for expr in (literal, alternative):
                    with self.subTest(width=width, text=text, alternative=expr is alternative):
                        report = run(expr)
                        self.assertEqual(report['accepted'], valid)
                        if not valid:
                            self.assertEqual({i['code'] for i in report['issues']}, {'invalid-literal-value'})


if __name__ == '__main__':
    unittest.main()
