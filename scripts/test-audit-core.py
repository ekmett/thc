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
            if rep['kind'] == 'void':
                return ['void', dict(rep=copy.deepcopy(rep))]
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


def io_main_fixture(prefix=2):
    """Exact IO state worker; main retains its original state formal through a PAP."""
    state = dict(kind='void', primReps=[], evaluated=True)
    unit = dict(REFERENCE, evaluated=True)
    result = tuple_rep(state, unit)
    unit_id = 'ghc-internal:GHC.Internal.Tuple.()'
    parameters = [dict(id=f'x{i}', type='Int#', lifted=False, rep=LONG) for i in range(prefix)]
    parameters += [dict(id='state', type='State# RealWorld', lifted=False, rep=state)]
    body = ['app', ['con', 'StateUnit', 2, dict(rep=CLOSURE)],
            [['void', dict(rep=state)], ['con', unit_id, 0, dict(rep=unit)]],
            [False, True], True, True, dict(rep=result)]
    worker = dict(bind('worker', ['lam', parameters, body, dict(rep=CLOSURE, resultRep=result)]), rep=CLOSURE)
    expression = [*var('worker'), dict(rep=CLOSURE)]
    if prefix:
        expression = ['app', expression, [[*lit(i), dict(rep=LONG)] for i in range(prefix)],
                      [False] * prefix, False, False, dict(rep=CLOSURE)]
    root = dict(bind('root', expression), type='IO ()', rep=CLOSURE)
    constructors = [dict(id='StateUnit', kind='unboxed-tuple', arity=2,
                         fieldReps=[None, None], strictFields=[False, False], fieldLifted=[None, None]),
                    dict(id=unit_id, kind='boxed', arity=0, fieldReps=[], strictFields=[], fieldLifted=[])]
    return dict(schema=1, ghc='9.14.1', bindings=[root, worker], constructors=constructors)


class IoMainAuditTest(unittest.TestCase):
    def audit(self, module):
        return audit_core.Audit([('io-main.json', module)], CAP).run(['root'], io_main=True)

    def rejects_boundary(self, module):
        report = self.audit(module)
        self.assertFalse(report['accepted'])
        self.assertIn('io-main-boundary', {issue['code'] for issue in report['issues']}, report)

    def test_exact_state_formal_survives_direct_alias_and_pap_prefixes(self):
        for prefix in (0, 1, 3):
            with self.subTest(prefix=prefix):
                module = io_main_fixture(prefix)
                report = self.audit(module)
                self.assertTrue(report['accepted'], report)
                # Outer aliases must preserve the remaining binder too.
                action = copy.deepcopy(module['bindings'][0])
                action.update(id='action', name='action')
                module['bindings'].append(action)
                module['bindings'][0]['expr'] = [*var('action'), dict(rep=CLOSURE)]
                self.assertTrue(self.audit(module)['accepted'])

    def test_nested_pap_and_alias_prefixes_count_logical_zero_width_arguments(self):
        module = io_main_fixture()
        state = dict(kind='void', primReps=[], evaluated=True)
        worker = module['bindings'][1]
        worker['expr'][1][0].update(type='State# OtherWorld', rep=state)
        main = module['bindings'][0]
        first = ['app', [*var('worker'), dict(rep=CLOSURE)], [['void', dict(rep=state)]],
                 [False], False, False, dict(rep=CLOSURE)]
        module['bindings'].append(dict(bind('partial', first), rep=CLOSURE))
        main['expr'][1] = [*var('partial'), dict(rep=CLOSURE)]
        main['expr'][2] = main['expr'][2][1:]
        main['expr'][3] = [False]
        self.assertTrue(self.audit(module)['accepted'])

    def test_void_width_does_not_establish_realworld_state_identity(self):
        for spelling in (None, '(# #)', 'State# s', 'Coercion#', 'Int#'):
            with self.subTest(type=spelling):
                module = io_main_fixture()
                formal = module['bindings'][1]['expr'][1][-1]
                if spelling is None:
                    del formal['type']
                else:
                    formal['type'] = spelling
                self.rejects_boundary(module)
        module = io_main_fixture()
        module['bindings'][1]['expr'][1][-1]['rep'] = tuple_rep()
        self.rejects_boundary(module)

    def test_saturation_extra_formals_unknown_targets_and_alias_cycles_reject(self):
        for count in (0, 1, 3, 4):
            with self.subTest(supplied=count):
                module = io_main_fixture()
                expression = module['bindings'][0]['expr']
                expression[2] = [[*lit(i), dict(rep=LONG)] for i in range(count)]
                expression[3] = [False] * count
                self.rejects_boundary(module)
        module = io_main_fixture()
        module['bindings'][0]['expr'][1] = [*var('unknown'), dict(rep=CLOSURE)]
        self.rejects_boundary(module)
        module = io_main_fixture()
        module['bindings'][1]['expr'] = [*var('root'), dict(rep=CLOSURE)]
        self.rejects_boundary(module)

    def test_pap_keeps_exact_input_result_and_declared_main_checks(self):
        mutations = [lambda m: m['bindings'][0].update(type='IO Int'),
                     lambda m: m['bindings'][1]['expr'][1][-1].update(rep=LONG),
                     lambda m: m['bindings'][1]['expr'][3].update(resultRep=REFERENCE),
                     lambda m: m['bindings'][1]['expr'][3].update(resultRep=tuple_rep(REFERENCE)),
                     lambda m: m['bindings'][1]['expr'][3].update(resultRep=tuple_rep(LONG, REFERENCE)),
                     lambda m: m['bindings'][1]['expr'][3].update(resultRep=tuple_rep(dict(kind='void', primReps=[], evaluated=True), LONG))]
        for index, mutate in enumerate(mutations):
            with self.subTest(mutation=index):
                module = io_main_fixture()
                mutate(module)
                self.rejects_boundary(module)


class AuditTest(unittest.TestCase):
    def test_scalar_primitive_signatures_reject_consistent_forgery_and_hidden_binder_proofs(self):
        for primitive, expected, result in [('plusInt64#', 'Int64Rep', 'Int64Rep'),
                ('ltWord64#', 'Word64Rep', 'IntRep'), ('int64ToWord64#', 'Int64Rep', 'Word64Rep')]:
            arity = CAP['primitives'][primitive]
            for mode in ('argument', 'result', 'omitted', 'unknown'):
                wrong = 'Word64Rep' if expected != 'Word64Rep' else 'Int64Rep'
                argument_rep = dict(LONG, primReps=[wrong if mode != 'result' else expected])
                result_rep = dict(LONG, primReps=['WordRep' if mode == 'result' else result])
                argument = ['var', 'x']
                if mode != 'omitted':
                    argument += [dict(rep=dict(kind='unknown', primReps=None, evaluated=False) if mode == 'unknown' else argument_rep)]
                body = ['app', ['prim', primitive], [argument] * arity, [False] * arity, False, False, dict(rep=result_rep)]
                expression = ['lam', [dict(id='x', lifted=False, rep=argument_rep)], body, dict(resultRep=result_rep)]
                report = run(expression)
                self.assertIn('primitive-representation', {i['code'] for i in report['issues']}, (primitive, mode))
                self.assertNotIn('scalar-representation', {i['code'] for i in report['issues']})

    def test_scalar_signature_checks_keep_unknown_absent_and_intrinsic_literal_carriers(self):
        for proof in (None, dict(kind='unknown', primReps=None, evaluated=False), dict(LONG, primReps=['Int64Rep'])):
            binder = dict(id='x', lifted=False)
            argument = ['var', 'x']
            if proof is not None:
                binder['rep'] = proof
                argument += [dict(rep=proof)]
            body = ['app', ['prim', 'plusInt64#'], [argument, argument], [False, False]]
            if proof is not None:
                body += [False, False, dict(rep=proof)]
            self.assertTrue(run(['lam', [binder], body])['accepted'])
            self.assertTrue(run(['lam', [binder], ['app', ['prim', 'plusInt64#'], [body, argument], [False, False]]])['accepted'])
        # Literal kind supplies a carrier, not a fabricated exact GHC register proof.
        self.assertTrue(run(['app', ['prim', 'plusInt64#'], [lit(1), lit(2)], [False, False]])['accepted'])
        word = dict(LONG, primReps=['Word64Rep'])
        binding = dict(bind('x', ['lit', 'word64', '1', dict(rep=word)], False), rep=word)
        report = run(['app', ['prim', 'plusInt64#'], [var('x'), var('x')], [False, False]], [binding])
        self.assertIn('primitive-representation', {i['code'] for i in report['issues']})

    def test_zero_width_state_components_preserve_logical_shape(self):
        void = dict(kind='void', primReps=[], evaluated=True)
        proof = tuple_rep(void, tuple_rep(), LONG)
        module = tuple_fixture(proof)
        self.assertTrue(run_tuple(module)['accepted'])
        forged = copy.deepcopy(module)
        forged['bindings'][1]['expr'][3]['resultRep']['components'][0] = tuple_rep()
        self.assertFalse(run_tuple(forged)['accepted'], 'State# is not the empty unboxed tuple')
        byte_array = dict(kind='object', primReps=['BoxedRep (Just Unlifted)'], evaluated=True)
        self.assertTrue(run_tuple(tuple_fixture(tuple_rep(void, byte_array)))['accepted'])

    def test_word64_literals_are_canonical_unsigned_values(self):
        for value in ('0', '1', '9223372036854775808', '18446744073709551615'):
            self.assertTrue(run(['lit', 'word64', value])['accepted'], value)
        for value in ('-1', '18446744073709551616', '', '+1', '01', '-0', ' 1', '1.0'):
            self.assertIn('invalid-literal-value', {i['code'] for i in run(['lit', 'word64', value])['issues']}, value)

    def test_int32_literals_and_alternatives_require_canonical_signed_range(self):
        for text in ('-2147483648', '-1', '0', '1', '2147483647', '-2147483649', '2147483648',
                     '', '+1', '01', '-0', ' 1', '1.0', '18446744073709551616'):
            valid = text in ('-2147483648', '-1', '0', '1', '2147483647')
            literal = ['lit', 'int32', text]
            alternative = ['case', lit(0), 'x', [['lit', ['int32', text], [], lit(1)], ['default', None, [], lit(2)]]]
            for expression in (literal, alternative):
                report = run(expression)
                self.assertEqual(valid, report['accepted'], text)
                if not valid:
                    self.assertIn('invalid-literal-value', {i['code'] for i in report['issues']})

    def test_narrow_32bit_literals_cannot_change_exact_rep_or_hide_it_with_unknown_kind(self):
        for kind, expected in (('int32', 'Int32Rep'), ('word32', 'Word32Rep')):
            for rep in ('Int32Rep', 'Word32Rep', 'IntRep', 'WordRep', 'Int64Rep', 'Word64Rep'):
                for carrier in ('long', 'unknown'):
                    proof = dict(kind=carrier, primReps=[rep], evaluated=True)
                    report = run(['lit', kind, '1', dict(rep=proof)])
                    self.assertEqual(carrier == 'long' and rep == expected, report['accepted'], (kind, proof))
                    if not report['accepted']:
                        self.assertIn('scalar-representation', {i['code'] for i in report['issues']})

    def test_narrow_32bit_literals_refine_unconstrained_but_not_malformed_metadata(self):
        for kind in ('int32', 'word32'):
            for evaluated in (False, True):
                for registers in ({}, {'primReps': None}):
                    proof = dict(kind='unknown', evaluated=evaluated, **registers)
                    self.assertTrue(run(['lit', kind, '1', dict(rep=proof)])['accepted'])
                    for operation, expected, result in (('int32ToInt#', 'int32', 'IntRep'),
                                                        ('word32ToWord#', 'word32', 'WordRep')):
                        expression = ['app', ['prim', operation], [['lit', kind, '1', dict(rep=proof)]],
                                      [False], False, False, dict(rep=dict(kind='long', primReps=[result], evaluated=True))]
                        report = run(expression)
                        self.assertEqual(kind == expected, report['accepted'])
                        if kind != expected:
                            self.assertIn('primitive-representation', {issue['code'] for issue in report['issues']})
            for proof in ([], 'unknown', dict(kind='unknown', primReps=None),
                          dict(kind='unknown', primReps=None, evaluated='false'),
                          dict(kind='unknown', primReps=[], evaluated=False),
                          dict(kind='unknown', primReps=[], evaluated=False, aggregate='unboxed-tuple', components=[])):
                self.assertFalse(run(['lit', kind, '1', dict(rep=proof)])['accepted'], proof)

    def test_int16_literals_and_alternatives_require_canonical_signed_range(self):
        for text in ('-32768', '-1', '0', '1', '32767', '-32769', '32768',
                     '', '+1', '01', '-0', ' 1', '1.0', '18446744073709551616'):
            valid = text in ('-32768', '-1', '0', '1', '32767')
            literal = ['lit', 'int16', text]
            alternative = ['case', lit(0), 'x', [['lit', ['int16', text], [], lit(1)], ['default', None, [], lit(2)]]]
            for expression in (literal, alternative):
                report = run(expression)
                self.assertEqual(valid, report['accepted'], text)
                if not valid:
                    self.assertIn('invalid-literal-value', {i['code'] for i in report['issues']})

    def test_word8_literals_and_alternatives_require_canonical_unsigned_range(self):
        for text in ('0', '1', '127', '128', '255', '-1', '-128', '256',
                     '', '+1', '01', '-0', ' 1', '1.0', '18446744073709551616'):
            valid = text in ('0', '1', '127', '128', '255')
            literal = ['lit', 'word8', text]
            alternative = ['case', lit(0), 'x', [['lit', ['word8', text], [], lit(1)], ['default', None, [], lit(2)]]]
            for expression in (literal, alternative):
                report = run(expression)
                self.assertEqual(valid, report['accepted'], text)
                if not valid: self.assertIn('invalid-literal-value', {i['code'] for i in report['issues']})

    def test_word8_literals_retain_unsigned_scalar_identity(self):
        for rep in ('Int8Rep', 'Word8Rep', 'Int16Rep', 'Word16Rep', 'IntRep', 'WordRep'):
            for carrier in ('long', 'unknown'):
                proof = dict(kind=carrier, primReps=[rep], evaluated=True)
                self.assertEqual(carrier == 'long' and rep == 'Word8Rep',
                                 run(['lit', 'word8', '255', dict(rep=proof)])['accepted'], proof)
        for proof in (None, dict(kind='unknown', evaluated=False), dict(kind='unknown', primReps=None, evaluated=True)):
            literal = ['lit', 'word8', '255'] + ([] if proof is None else [dict(rep=proof)])
            self.assertTrue(run(literal)['accepted'])
            for operation, accepted, result in (('word8ToWord#', True, 'WordRep'),
                                                ('int8ToInt#', False, 'IntRep'),
                                                ('word16ToWord#', False, 'WordRep')):
                expression = ['app', ['prim', operation], [literal], [False], False, False,
                              dict(rep=dict(kind='long', primReps=[result], evaluated=True))]
                self.assertEqual(accepted, run(expression)['accepted'])
        lane = dict(kind='long', primReps=['Word8Rep'], evaluated=True)
        for proof in (dict(kind='unknown',primReps=[],evaluated=False),
                      dict(kind='unknown',primReps=None,evaluated=False,aggregate='unboxed-tuple',components=[]),
                      dict(kind='long',primReps=['Word8Rep'],evaluated=True,aggregate='unboxed-tuple',components=[lane])):
            self.assertFalse(run(['lit','word8','1',dict(rep=proof)])['accepted'],proof)

    def test_int8_literals_and_alternatives_require_canonical_signed_range(self):
        for text in ('-128', '-1', '0', '1', '127', '-129', '128', '255',
                     '', '+1', '01', '-0', ' 1', '1.0', '18446744073709551616'):
            valid = text in ('-128', '-1', '0', '1', '127')
            literal = ['lit', 'int8', text]
            alternative = ['case', lit(0), 'x', [['lit', ['int8', text], [], lit(1)], ['default', None, [], lit(2)]]]
            for expression in (literal, alternative):
                report = run(expression)
                self.assertEqual(valid, report['accepted'], text)
                if not valid: self.assertIn('invalid-literal-value', {i['code'] for i in report['issues']})

    def test_int8_literals_refine_only_unconstrained_metadata(self):
        for rep in ('Int8Rep', 'Word8Rep', 'Int16Rep', 'Int32Rep', 'IntRep', 'WordRep'):
            for carrier in ('long', 'unknown'):
                proof = dict(kind=carrier, primReps=[rep], evaluated=True)
                self.assertEqual(carrier == 'long' and rep == 'Int8Rep',
                                 run(['lit', 'int8', '1', dict(rep=proof)])['accepted'], proof)
        for evaluated in (False, True):
            for registers in ({}, {'primReps': None}):
                proof = dict(kind='unknown', evaluated=evaluated, **registers)
                self.assertTrue(run(['lit', 'int8', '1', dict(rep=proof)])['accepted'])
                for operation, accepted in (('int8ToInt#', True), ('int16ToInt#', False), ('word8ToWord#', False)):
                    expression = ['app', ['prim', operation], [['lit', 'int8', '1', dict(rep=proof)]],
                                  [False], False, False, dict(rep=dict(kind='long', primReps=['WordRep' if operation == 'word8ToWord#' else 'IntRep'], evaluated=True))]
                    self.assertEqual(accepted, run(expression)['accepted'])
        for proof in ([], 'unknown', dict(kind='unknown', primReps=None),
                      dict(kind='unknown', primReps=None, evaluated='false'),
                      dict(kind='unknown', primReps=[], evaluated=False)):
            self.assertFalse(run(['lit', 'int8', '1', dict(rep=proof)])['accepted'], proof)

    def test_narrow_16bit_literals_cannot_change_exact_rep_or_hide_it_with_unknown_kind(self):
        for kind, expected in (('int16', 'Int16Rep'), ('word16', 'Word16Rep')):
            for rep in ('Int8Rep', 'Word8Rep', 'Int16Rep', 'Word16Rep', 'Int32Rep', 'Word32Rep', 'IntRep', 'WordRep', 'Int64Rep', 'Word64Rep'):
                for carrier in ('long', 'unknown'):
                    proof = dict(kind=carrier, primReps=[rep], evaluated=True)
                    report = run(['lit', kind, '1', dict(rep=proof)])
                    self.assertEqual(carrier == 'long' and rep == expected, report['accepted'], (kind, proof))
                    if not report['accepted']:
                        self.assertIn('scalar-representation', {i['code'] for i in report['issues']})

    def test_narrow_16bit_literals_refine_unconstrained_but_not_malformed_metadata(self):
        for kind in ('int16', 'word16'):
            for evaluated in (False, True):
                for registers in ({}, {'primReps': None}):
                    proof = dict(kind='unknown', evaluated=evaluated, **registers)
                    self.assertTrue(run(['lit', kind, '1', dict(rep=proof)])['accepted'])
                    for operation, expected, result in (('int16ToInt#', 'int16', 'IntRep'),
                                                        ('word16ToWord#', 'word16', 'WordRep')):
                        expression = ['app', ['prim', operation], [['lit', kind, '1', dict(rep=proof)]],
                                      [False], False, False, dict(rep=dict(kind='long', primReps=[result], evaluated=True))]
                        report = run(expression)
                        self.assertEqual(kind == expected, report['accepted'])
                        if kind != expected:
                            self.assertIn('primitive-representation', {issue['code'] for issue in report['issues']})
            for proof in ([], 'unknown', dict(kind='unknown', primReps=None),
                          dict(kind='unknown', primReps=None, evaluated='false'),
                          dict(kind='unknown', primReps=[], evaluated=False),
                          dict(kind='unknown', primReps=[], evaluated=False, aggregate='unboxed-tuple', components=[])):
                self.assertFalse(run(['lit', kind, '1', dict(rep=proof)])['accepted'], proof)

    def test_floating_literal_carriers_cannot_be_overridden_by_metadata(self):
        carriers = [(['lit', 'float', '1.0'], dict(kind='float', primReps=['FloatRep'], evaluated=True)),
                    (['lit', 'double', '1.0'], dict(kind='double', primReps=['DoubleRep'], evaluated=True)),
                    (lit(1), LONG),
                    (['lit', 'string-bytes', '41'], dict(kind='address', primReps=['AddrRep'], evaluated=True)),
                    (['void'], dict(kind='void', primReps=[], evaluated=True))]
        for literal, actual in carriers:
            self.assertTrue(run(literal)['accepted'], literal)
            self.assertTrue(run([*literal, dict(rep=actual)])['accepted'], literal)
            for _, declared in carriers:
                if actual['kind'] == declared['kind'] or not {'float', 'double'} & {actual['kind'], declared['kind']}:
                    continue
                case = ['case', [*lit(0), dict(rep=LONG)], 's', [['default', None, [], literal]],
                        dict(rep=declared, binder=dict(id='s', lifted=False, rep=LONG))]
                for expr in [[*literal, dict(rep=declared)], case]:
                    report = run(expr)
                    self.assertIn('scalar-representation', {i['code'] for i in report['issues']}, expr)

    def test_floating_case_validation_is_order_independent_without_inventing_unknown_proofs(self):
        for kind, register in [('float', 'FloatRep'), ('double', 'DoubleRep')]:
            proof = dict(kind=kind, primReps=[register], evaluated=True)
            floating = ['lit', kind, '1.0']
            others = [['lit', 'double' if kind == 'float' else 'float', '1.0'], lit(1),
                      ['lit', 'string-bytes', '41'], ['void']]
            for other in others:
                for first, second in [(floating, other), (other, floating)]:
                    for declared in [None, proof]:
                        case = ['case', lit(0), 's', [['default', None, [], first],
                                ['lit', ['int', '0'], [], second]]]
                        if declared is not None:
                            case.append(dict(rep=declared, binder=dict(id='s', lifted=False, rep=LONG)))
                        report = run(case)
                        self.assertIn('scalar-representation', {i['code'] for i in report['issues']}, case)
            unknown_arm = ['var', 'legacy']
            case = ['case', lit(0), 's', [['default', None, [], floating], ['lit', ['int', '0'], [], unknown_arm]]]
            self.assertIsNone(audit_core.Audit.expression_rep(case))
            self.assertTrue(run(['lam', [dict(id='legacy', lifted=False)], case])['accepted'])

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

    def test_boxed_lexical_and_case_proofs_cannot_change_exact_levity(self):
        lifted = dict(REFERENCE, kind='object')
        unlifted = dict(lifted, primReps=['BoxedRep (Just Unlifted)'])
        for stored, occurrence in [(lifted, unlifted), (unlifted, lifted)]:
            for stored_kind in ('object', 'data', 'closure'):
                binder = dict(id='x', lifted=True, rep=dict(stored, kind=stored_kind))
                variable = ['var', 'x', dict(rep=occurrence)]
                case = ['case', ['var', 'x', dict(rep=stored)], 'b',
                        [['default', None, [], ['var', 'b', dict(rep=occurrence)]]],
                        dict(rep=occurrence, binder=dict(id='b', rep=occurrence))]
                for body in (variable, case):
                    report = run(['lam', [binder], body])
                    self.assertIn('scalar-representation', {i['code'] for i in report['issues']})

    def test_unknown_boxed_levity_and_class_refinements_remain_compatible(self):
        legacy = dict(kind='unknown', primReps=None, evaluated=False)
        unknown = dict(REFERENCE, kind='object', primReps=['BoxedRep Nothing'])
        for levity in ('Lifted', 'Unlifted'):
            exact = dict(REFERENCE, primReps=[f'BoxedRep (Just {levity})'])
            for stored, occurrence in [(None, exact), (exact, None), (legacy, exact), (exact, legacy),
                    (unknown, exact), (exact, unknown), (exact, dict(exact, kind='object', evaluated=True))]:
                binder = dict(id='x', lifted=True)
                if stored is not None:
                    binder['rep'] = stored
                body = ['var', 'x'] + ([dict(rep=occurrence)] if occurrence is not None else [])
                self.assertTrue(run(['lam', [binder], body])['accepted'], (stored, occurrence))

    def test_tuple_arithmetic_requires_exact_logical_results_and_scalar_arguments(self):
        for name, contract in CAP['tuplePrimitives'].items():
            scalar = dict(LONG, primReps=[contract['arguments'][0]])
            proof = tuple_rep(*(dict(LONG, primReps=[rep]) for rep in contract["result"]))
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

    def test_word_carry_result_fields_cannot_be_swapped_or_relabelled(self):
        word = dict(LONG, primReps=['WordRep'])
        for name in ('addWordC#', 'subWordC#'):
            proof = tuple_rep(word, LONG)
            module = tuple_fixture(proof)
            call = module['bindings'][0]['expr'][2][1]
            call[:] = ['app', ['prim', name], [['lit', 'word', '1', dict(rep=word)]] * 2,
                       [False, False], False, False, dict(rep=proof)]
            self.assertTrue(run_tuple(module)['accepted'])
            for fields in [('IntRep', 'WordRep'), ('WordRep', 'WordRep'), ('IntRep', 'IntRep'),
                           ('WordRep', 'Int64Rep'), ('Word64Rep', 'IntRep')]:
                changed = copy.deepcopy(module)
                bad = changed['bindings'][0]['expr'][2][1]
                bad[6]['rep'] = tuple_rep(*(dict(LONG, primReps=[rep]) for rep in fields))
                report = run_tuple(changed)
                self.assertFalse(report['accepted'], (name, fields))
                self.assertIn('primitive-representation', {i['code'] for i in report['issues']})
            for flag in (tuple_rep(LONG), dict(kind='void', evaluated=True, primReps=[]), dict(LONG, kind='unknown')):
                changed = copy.deepcopy(module)
                changed['bindings'][0]['expr'][2][1][6]['rep'] = tuple_rep(word, flag)
                self.assertFalse(run_tuple(changed)['accepted'], (name, flag))

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

    def test_floating_tuple_leaves_preserve_exact_proofs_and_reject_literal_alternatives(self):
        for kind, register in [('float', 'FloatRep'), ('double', 'DoubleRep')]:
            scalar = dict(kind=kind, primReps=[register], evaluated=True)
            audit = audit_core.Audit([], CAP)
            audit.representation(scalar, None, '/scalar')
            self.assertEqual([], audit.issues)
            audit.representation(tuple_rep(scalar), None, '/tuple')
            self.assertEqual([], audit.issues)
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



class EmptyTupleInputTests(unittest.TestCase):
    capability = dict(CAP, aggregateInputs=['empty-unboxed-tuple'])
    empty = tuple_rep()
    state = dict(kind='void', primReps=[], evaluated=True)

    @classmethod
    def fixture(cls, formal=None, actual=None):
        formal = copy.deepcopy(formal if formal is not None else [cls.empty, LONG])
        actual = copy.deepcopy(actual if actual is not None else formal)
        constructors = {}

        def value(rep):
            if audit_core.Audit.is_tuple(rep):
                children = rep.get('components')
                fields = children if isinstance(children, list) else []
                key = 'Tuple' + str(len(fields))
                constructors[key] = dict(id=key, kind='unboxed-tuple', arity=len(fields),
                    fieldReps=[None] * len(fields), strictFields=[False] * len(fields), fieldLifted=[None] * len(fields))
                if not fields:
                    return ['con', key, 0, dict(rep=rep)]
                return ['app', ['con', key, len(fields)], [value(p) for p in fields],
                        [False] * len(fields), True, True, dict(rep=rep)]
            if isinstance(rep, dict) and rep.get('kind') == 'void':
                return ['void', dict(rep=rep)]
            if isinstance(rep, dict) and rep.get('kind') in ('data', 'object', 'closure'):
                constructors['Unit'] = dict(id='Unit', kind='boxed', arity=0, fieldReps=[], strictFields=[], fieldLifted=[])
                return ['con', 'Unit', 0, dict(rep=rep)]
            return [*lit(0)] + ([dict(rep=rep)] if rep is not None else [])

        parameters = [dict(id=f'p{i}', lifted=isinstance(p, dict) and p.get('primReps') == ['BoxedRep (Just Lifted)'],
                           **({'rep': p} if p is not None else {})) for i, p in enumerate(formal)]
        worker = dict(bind('worker', ['lam', parameters, [*lit(7), dict(rep=LONG)], dict(rep=CLOSURE, resultRep=LONG)]),
                      rep=CLOSURE, arity=len(formal))
        call = ['app', [*var('worker'), dict(rep=CLOSURE)], [value(p) for p in actual],
                [isinstance(p, dict) and p.get('primReps') == ['BoxedRep (Just Lifted)'] for p in actual],
                False, False, dict(rep=LONG)]
        return dict(schema=1, ghc='9.14.1', bindings=[bind('root', call), worker], constructors=list(constructors.values()))

    def audit(self, module, entry='root', cap=None):
        return audit_core.Audit([('empty-input.json', module)], self.capability if cap is None else cap).run([entry])

    def test_exact_empty_positions_and_scalar_zero_width_controls(self):
        for formal in [[self.empty, LONG], [LONG, self.empty, LONG], [LONG, self.empty],
                       [self.empty, self.empty, LONG], [self.state, LONG], [REFERENCE, LONG]]:
            self.assertTrue(self.audit(self.fixture(formal))['accepted'], formal)
        self.assertFalse(self.audit(self.fixture(), cap=dict(self.capability, aggregateInputs=[]))['accepted'])

    def test_equal_width_other_logical_shapes_cannot_satisfy_empty_formal(self):
        others = [self.state, REFERENCE, tuple_rep(self.empty), tuple_rep(self.state), tuple_rep(LONG),
                  dict(self.empty, components=None), dict(self.empty, aggregate='unboxed-sum', alternatives=[]),
                  None, dict(kind='unknown', primReps=None, evaluated=False)]
        for actual in others:
            report = self.audit(self.fixture([self.empty], [actual]))
            self.assertFalse(report['accepted'], actual)
            self.assertIn('aggregate-shape', {i['code'] for i in report['issues']}, actual)
        for formal in [self.state, REFERENCE, LONG, None]:
            self.assertFalse(self.audit(self.fixture([formal], [self.empty]))['accepted'], formal)
        self.assertFalse(self.audit(self.fixture([self.empty, self.state, LONG], [self.state, self.empty, LONG]))['accepted'])

    def test_only_exact_empty_formals_are_enabled(self):
        for formal in [tuple_rep(self.empty), tuple_rep(self.state), tuple_rep(LONG),
                       dict(self.empty, components=None), dict(self.empty, kind='void'),
                       dict(self.empty, primReps=['IntRep'])]:
            self.assertFalse(self.audit(self.fixture([formal]))['accepted'], formal)

    def test_empty_input_is_unlifted_and_unavailable_at_host_boundary(self):
        module = self.fixture()
        self.assertFalse(self.audit(module, 'worker')['accepted'])
        module['bindings'][0]['expr'][3][0] = True
        self.assertIn('application-levity', {i['code'] for i in self.audit(module)['issues']})
        module = self.fixture()
        module['bindings'][1]['expr'][1][0]['lifted'] = True
        self.assertIn('application-levity', {i['code'] for i in self.audit(module)['issues']})

    def test_empty_actual_raw_lifted_flag_is_not_erased_by_entry_strictness(self):
        module = self.fixture([self.empty])
        module['bindings'][1]['expr'][3]['entryStrict'] = [True]
        self.assertTrue(self.audit(module)['accepted'])
        module['bindings'][0]['expr'][3][0] = True
        report = self.audit(module)
        self.assertFalse(report['accepted'])
        self.assertIn('application-levity', {i['code'] for i in report['issues']})

    def test_recursive_alias_chain_preserves_known_positional_input_proofs(self):
        module = self.fixture([self.empty])
        call = module['bindings'][0]['expr']
        worker = self.fixture([self.state])['bindings'][1]
        alias = dict(bind('alias', [*var('worker'), dict(rep=CLOSURE)]), rep=CLOSURE)
        second = dict(bind('second', [*var('alias'), dict(rep=CLOSURE)]), rep=CLOSURE)
        call[1] = [*var('second'), dict(rep=CLOSURE)]
        # Reversed dependency order requires a fixed point, not a single scan.
        module['bindings'] = [bind('root', ['let', True, [second, alias, worker], call, dict(rep=LONG)])]
        report = self.audit(module)
        self.assertFalse(report['accepted'])
        self.assertIn('aggregate-shape', {i['code'] for i in report['issues']})
        call[2][0] = ['void', dict(rep=self.state)]
        self.assertTrue(self.audit(module)['accepted'])

    def test_known_empty_input_rejects_legacy_scalar_actual_without_metadata(self):
        module = self.fixture([self.empty])
        self.assertTrue(self.audit(module)['accepted'])
        module['bindings'][0]['expr'][2][0] = lit(1)
        report = self.audit(module)
        self.assertFalse(report['accepted'])
        self.assertIn('aggregate-shape', {i['code'] for i in report['issues']})

    def test_exact_empty_join_formals_are_separately_gated_and_ordinary_empty_lets_stay_rejected(self):
        module = self.fixture([self.empty])
        worker = module['bindings'].pop()
        worker.update(joinValueArity=1, joinResultRep=LONG, info=dict(joinArity=1))
        call = module['bindings'][0]['expr']
        module['bindings'][0]['expr'] = ['let', False, [worker], call, dict(rep=LONG)]
        self.assertTrue(self.audit(module)['accepted'])
        disabled = dict(CAP, aggregateJoinInputs=[])
        self.assertFalse(audit_core.Audit([('join', module)], disabled).run(['root'])['accepted'])
        module = self.fixture()
        value = module['bindings'][0]['expr'][2][0]
        local = dict(bind('e', value, False), rep=self.empty)
        module['bindings'][0]['expr'] = ['let', False, [local], [*lit(1), dict(rep=LONG)], dict(rep=LONG)]
        self.assertIn('unboxed-tuple let binding', [i['detail'] for i in self.audit(module)['issues']])

    def test_known_partial_application_keeps_logical_positions(self):
        module = self.fixture([LONG, self.empty, LONG])
        call = module['bindings'][0]['expr']
        partial = ['app', call[1], call[2][:1], call[3][:1], False, False, dict(rep=CLOSURE)]
        suffix = ['app', partial, call[2][1:], call[3][1:], False, False, dict(rep=LONG)]
        module['bindings'][0]['expr'] = suffix
        self.assertTrue(self.audit(module)['accepted'])
        suffix[2][0] = ['void', dict(rep=self.state)]
        self.assertIn('aggregate-shape', {i['code'] for i in self.audit(module)['issues']})
        local = dict(bind('pap', partial), rep=CLOSURE)
        suffix[1] = [*var('pap'), dict(rep=CLOSURE)]
        module['bindings'][0]['expr'] = ['let', False, [local], suffix, dict(rep=LONG)]
        self.assertIn('aggregate-shape', {i['code'] for i in self.audit(module)['issues']})

    def test_global_and_local_alias_signatures_use_definition_scope(self):
        for local_alias in [False, True]:
            module = self.fixture([self.empty])
            call = module['bindings'][0]['expr']
            call[1] = [*var('alias'), dict(rep=CLOSURE)]
            alias = dict(bind('alias', [*var('worker'), dict(rep=CLOSURE)]), rep=CLOSURE)
            shadow = self.fixture([self.state])['bindings'][1]
            shadowed = ['let', False, [shadow], call, dict(rep=LONG)]
            if local_alias:
                module['bindings'][0]['expr'] = ['let', False, [alias], shadowed, dict(rep=LONG)]
            else:
                module['bindings'].append(alias)
                module['bindings'][0]['expr'] = shadowed
            self.assertTrue(self.audit(module)['accepted'], local_alias)
            call[2][0] = ['void', dict(rep=self.state)]
            self.assertIn('aggregate-shape', {i['code'] for i in self.audit(module)['issues']}, local_alias)

    def test_empty_formal_cannot_be_captured_by_a_nested_function(self):
        module = self.fixture([self.empty])
        worker = module['bindings'][1]
        inner = ['lam', [dict(id='x', lifted=False, rep=LONG)],
                 ['var', 'p0', dict(rep=self.empty)], dict(rep=CLOSURE, resultRep=self.empty)]
        worker['expr'][2] = inner
        worker['expr'][3]['resultRep'] = CLOSURE
        module['bindings'][0]['expr'][6]['rep'] = CLOSURE
        self.assertIn('unboxed-tuple capture', [i['detail'] for i in self.audit(module)['issues']])

    def test_genuine_native_empty_input_exports_are_accepted(self):
        paths = [ROOT.parent / f'build/empty-tuple-input/{stage}-core/EmptyTupleInputAudit.json' for stage in ['pre', 'post']]
        if not all(path.exists() for path in paths):
            self.skipTest('Run prepare-empty-tuple-input-audit.py for genuine native exports')
        entries = ['scalarControl', 'beforeCase', 'betweenCase', 'afterCase', 'usedCase',
                   'papEmptyCase', 'papTwoEmptyCase', 'papMixedCase', 'overCase', 'lazyCase',
                   'pairCase', 'selfCase', 'mutualCase', 'selfDepth', 'mutualDepth',
                   'effectCase', 'effectPapCase', 'betweenInputs']
        for path in paths:
            module = json.loads(path.read_text())
            report = audit_core.Audit([(str(path), module)], self.capability).run(entries)
            self.assertTrue(report['accepted'], report['issues'])

    def test_global_scalar_cannot_be_relabelled_as_an_empty_actual(self):
        module = self.fixture([self.empty])
        global_value = dict(bind('scalar', ['void', dict(rep=self.state)], False), rep=self.state)
        module['bindings'].append(global_value)
        module['bindings'][0]['expr'][2][0] = ['var', 'scalar', dict(rep=self.empty)]
        self.assertIn('aggregate-shape', {i['code'] for i in self.audit(module)['issues']})

    def test_state_primitive_contracts_do_not_accept_empty_tuple(self):
        module = self.fixture()
        empty = module['bindings'][0]['expr'][2][0]
        reference = dict(kind='object', primReps=['BoxedRep (Just Unlifted)'], evaluated=True)
        for state in [['void', dict(rep=self.state)], empty]:
            module['bindings'][0]['expr'] = ['app', ['prim', 'newByteArray#'], [[*lit(1), dict(rep=LONG)], state],
                    [False, False], False, False, dict(rep=tuple_rep(self.state, reference))]
            report = self.audit(module)
            # Tuple host result remains unsupported in either case; only the
            # malformed empty-as-State operand violates the primitive contract.
            primitive_errors = [i for i in report['issues'] if i['code'] == 'primitive-representation']
            self.assertEqual(bool(primitive_errors), state is empty)


if __name__ == '__main__':
    unittest.main()
