#!/usr/bin/env python3
"""Regression tests for lexical dependency closure and capability diagnostics."""
import importlib.util
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


class AuditTest(unittest.TestCase):
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
        expr = ['let', False, [bind('partial', ['prim', '+#']), bind('bad', ['lit', 'double', '2.5'])],
                ['app', ['prim', 'unsupported#'], [lit(1)], [False]]]
        report = run(expr)
        self.assertEqual({i['code'] for i in report['issues']}, {'primitive-arity', 'unsupported-literal', 'unsupported-primitive'})
        self.assertEqual([p['name'] for p in report['primitives']], ['+#', 'unsupported#'])

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
