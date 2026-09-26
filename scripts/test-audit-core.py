#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Regression tests for lexical dependency closure and capability diagnostics."""
import importlib.util
import copy
import hashlib
import json
from pathlib import Path
import unittest
import core_original_foreign

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


class PackageScalarOperandTest(unittest.TestCase):
    """Call-proof controls only; no invented component or bitcode is executed."""
    def call(self, primitive):
        scalar = lambda rep, evaluated=True: dict(kind=core_original_foreign.scalar_kind(rep),
            primReps=[] if rep is None else [rep], evaluated=evaluated)
        output = dict(kind='unknown', primReps=[primitive], aggregate='unboxed-tuple', evaluated=True,
                      components=[scalar(None), scalar(primitive)])
        abi = dict(symbol='scalar_leaf', entry='unused', arguments=[primitive], result=primitive)
        descriptor = dict(schema=1, target=dict(kind='static', symbol=abi['symbol'], unit='first', isFunction=True),
                          convention='ccall', safety='unsafe', arity=2, suppliedArity=2,
                          argumentReps=[scalar(primitive, False), scalar(None, False)],
                          resultRep=dict(output, evaluated=False))
        expression = ['app', ['var', 'foreign-head', dict(rep=CLOSURE)],
                      [['var', 'argument', dict(rep=scalar(primitive))], ['void', dict(rep=scalar(None))]],
                      [False, False], None, None, dict(rep=output, foreignCall=descriptor)]
        return abi, expression, scalar(primitive)

    def inspect(self, abi, expression, stored):
        audit = audit_core.Audit([], CAP)
        audit.package_scalar_links['first'] = dict(unit='first', abi=[abi])
        self.assertTrue(audit.polyglot_call(expression, dict(argument=stored), 'root', 'root'))
        return audit.issues

    def test_package_scalar_occurrences_cannot_hide_stored_or_intrinsic_carriers(self):
        for primitive in ('Int32Rep', 'Int64Rep', 'FloatRep', 'DoubleRep'):
            abi, expression, stored = self.call(primitive)
            self.assertEqual([], self.inspect(abi, expression, stored), primitive)
            altered = dict(stored, primReps=['Word64Rep'])
            self.assertIn('stored operand', str(self.inspect(abi, expression, altered)), primitive)
            altered = copy.deepcopy(expression)
            altered[2][0] = ['lit', 'word32', '1', dict(rep=stored)]
            self.assertIn('lowered operand', str(self.inspect(abi, altered, stored)), primitive)
            altered = copy.deepcopy(expression)
            altered[2][1] = ['lit', 'int', '1', expression[2][1][1]]
            self.assertIn('lowered operand', str(self.inspect(abi, altered, stored)), primitive)

    def test_package_scalar_needs_raw_state_and_result_proof(self):
        abi, expression, stored = self.call('Int32Rep')
        altered = copy.deepcopy(expression)
        altered[2][1] = ['void']
        self.assertIn('exact scalar/State ABI', str(self.inspect(abi, altered, stored)))
        altered = copy.deepcopy(expression)
        del altered[6]['rep']
        self.assertIn('exact scalar/State ABI', str(self.inspect(abi, altered, stored)))


class ArchiveReachabilityTest(unittest.TestCase):
    def report(self, expression, initializers=(), finalizers=(), files=()):
        root = dict(schema=1, ghc='9.14.1', bindings=[bind('root', expression)], constructors=[])
        label = lambda name, init: dict(isInitializer=init, unit='pkg', module='M', name=name)
        archive = dict(schema=2, ghc='9.14.1', unit='pkg', module='M',
                       foreign=dict(schema=1, execution='not-linked',
                                    stubs=dict(header='', source='int stub(void) { return 1; }',
                                               initializers=[label(name, True) for name in initializers],
                                               finalizers=[label(name, False) for name in finalizers]), files=list(files)),
                       bindings=[bind('pkg:M.cold', lit(9))], constructors=[])
        return audit_core.Audit([('root.json', root), ('archive.json', archive)], CAP).run(['root'])

    def test_unused_archive_is_validated_but_not_executed(self):
        report = self.report(lit(7))
        self.assertTrue(report['accepted'], report['issues'])
        self.assertEqual(['root'], [item['id'] for item in report['reachableBindings']])

    def test_reachable_archive_and_global_registration_still_reject(self):
        for report in (self.report(var('pkg:M.cold')),
                       self.report(lit(7), initializers=('start',)),
                       self.report(lit(7), finalizers=('stop',)),
                       self.report(lit(7), files=(dict(language='RawObject', source='opaque', extension='.o'),)),
                       self.report(lit(7), files=(dict(language='C', source='void init(void) __attribute__((constructor));', extension='.c'),))):
            self.assertFalse(report['accepted'])
            self.assertIn('module-format', {issue['code'] for issue in report['issues']})
            self.assertIn('archive-only', str(report['issues']))


    def test_unknown_managed_import_evidence_does_not_bypass_archive_validation(self):
        # Deliberately malformed provenance: no synthetic verified producer.
        for schema in (1, 2):
            for proof in (None, {}, {'status': 'verified', 'profile': 'invented'}):
                module = dict(schema=schema, ghc='9.14.1', unit='pkg', module='M',
                              bindings=[bind('root', lit(7))], constructors=[], staticForeignImportStubs=proof)
                if schema == 2:
                    module['foreign'] = dict(schema=1, execution='not-linked', stubs=dict(
                        header='', source='int unknown(void) { return 7; }', initializers=[], finalizers=[]), files=[])
                report = audit_core.Audit([('unknown.json', module)], CAP).run(['root'])
                self.assertFalse(report['accepted'], report)
                self.assertIn('module-format', {issue['code'] for issue in report['issues']})


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


class ConstructorMetadataAuditTest(unittest.TestCase):
    def test_alpha_renamed_display_type_keeps_exact_layout_and_future_metadata(self):
        constructor = dict(id='pkg:Module.C', kind='boxed', arity=1, tag=2,
                           fieldReps=[['IntRep']], fieldTypes=['Int#'],
                           strictFields=[True], fieldLifted=[False],
                           type='forall k. C k')
        def audit(second):
            modules = [('first.json', dict(schema=1, ghc='9.14.1', bindings=[bind('root', lit(0))],
                                          constructors=[constructor])),
                       ('second.json', dict(schema=1, ghc='9.14.1', bindings=[bind('other', lit(1))],
                                           constructors=[second]))]
            return audit_core.Audit(modules, CAP).run(['root'])
        self.assertTrue(audit(dict(constructor, type='forall k1. C k1'))['accepted'])
        for field, changed in [('kind', 'unboxed-tuple'), ('arity', 2), ('tag', 3),
                               ('fieldReps', [['WordRep']]), ('fieldTypes', ['Word#']),
                               ('strictFields', [False]), ('fieldLifted', [True]),
                               ('futureLayout', 'different')]:
            with self.subTest(field=field):
                report = audit(dict(constructor, **{field: changed}))
                self.assertIn('inconsistent-constructor', {issue['code'] for issue in report['issues']})


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
    def test_bignat_intrinsic_representation_api_retains_exact_evaluated_proof(self):
        # This is an assertion on the retained Python auditor's own API. The
        # Haskell/Kotlin BigNat fixture owns corpus and CLI admission controls.
        exact = dict(kind='object', primReps=['BoxedRep (Just Unlifted)'], evaluated=True)
        for value in ('0', '1', str((1 << 255) + (1 << 128) + 3)):
            for proof in (exact, None, dict(kind='unknown', primReps=None, evaluated=False)):
                with self.subTest(value=value, proof=proof):
                    literal = ['lit', 'bignat', value] + ([] if proof is None else [dict(rep=proof)])
                    self.assertEqual(exact, audit_core.Audit.expression_rep(literal))

    def test_word_floating_requires_exact_unsigned_input_result_and_arity(self):
        for primitive, kind, register in [('word2Float#', 'float', 'FloatRep'),
                                          ('word2Double#', 'double', 'DoubleRep')]:
            word = dict(LONG, primReps=['WordRep'])
            result = dict(kind=kind, primReps=[register], evaluated=True)
            body = ['app', ['prim', primitive], [['var', 'x', dict(rep=word)]],
                    [False], False, True, dict(rep=result)]
            expression = ['lam', [dict(id='x', lifted=False, rep=word)], body,
                          dict(rep=CLOSURE, resultRep=result)]
            self.assertTrue(run(expression)['accepted'], primitive)
            for variant in ('signed', 'word64', 'result', 'arity', 'hidden'):
                bad = copy.deepcopy(expression)
                if variant in ('signed', 'word64', 'hidden'):
                    rep = dict(LONG, primReps=['Word64Rep' if variant == 'word64' else 'IntRep'])
                    bad[1][0]['rep'] = rep
                    if variant == 'hidden':
                        bad[2][2][0].pop()
                    else:
                        bad[2][2][0][2]['rep'] = rep
                elif variant == 'result':
                    other = dict(kind='double' if kind == 'float' else 'float',
                                 primReps=['DoubleRep' if kind == 'float' else 'FloatRep'], evaluated=True)
                    bad[2][6]['rep'] = other
                    bad[3]['resultRep'] = other
                else:
                    bad[2][2] = []; bad[2][3] = []
                report = run(bad)
                self.assertFalse(report['accepted'], (primitive, variant))
                self.assertTrue(any(issue['code'] in ('primitive-representation', 'primitive-arity')
                                    for issue in report['issues']), report['issues'])

    def test_native_address_casts_retain_exact_scalar_registers(self):
        address = dict(kind='address', primReps=['AddrRep'], evaluated=True)
        for primitive, argument, result in [('addr2Int#', address, LONG), ('int2Addr#', LONG, address)]:
            body = ['app', ['prim', primitive], [['var', 'x', dict(rep=argument)]],
                    [False], False, False, dict(rep=result)]
            expression = ['lam', [dict(id='x', lifted=False, rep=argument)], body,
                          dict(rep=CLOSURE, resultRep=result)]
            self.assertTrue(run(expression)['accepted'], primitive)
            for mode in ('argument', 'result', 'hidden'):
                bad = copy.deepcopy(expression)
                if mode == 'result':
                    bad[2][6]['rep'] = argument
                    bad[3]['resultRep'] = argument
                else:
                    bad[1][0]['rep'] = result
                    if mode == 'hidden': bad[2][2][0].pop()
                    else: bad[2][2][0][2]['rep'] = result
                self.assertFalse(run(bad)['accepted'], (primitive, mode))

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
            arguments = [dict(LONG, kind={'FloatRep': 'float', 'DoubleRep': 'double'}.get(rep, 'long'),
                              primReps=[rep]) for rep in contract['arguments']]
            scalar = arguments[0]
            proof = tuple_rep(*(dict(LONG, primReps=[rep]) for rep in contract["result"]))
            module = tuple_fixture(proof)
            call = module['bindings'][0]['expr'][2][1]
            call[:] = ['app', ['prim', name],
                       [['lit', {'FloatRep': 'float', 'DoubleRep': 'double', 'WordRep': 'word'}.get(rep['primReps'][0], 'int'),
                         '1', dict(rep=rep)] for rep in arguments],
                       [False] * len(contract['arguments']), False, False, dict(rep=proof)]
            self.assertTrue(run_tuple(module)['accepted'], name)
            for mutation in ('nested', 'scalar', 'unknown', 'wrong-register', 'unknown-argument', 'lifted', 'partial', 'overapplied', 'missing-result', 'extra-result'):
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
                elif mutation == 'missing-result':
                    bad[6]['rep']['components'].pop(); bad[6]['rep']['primReps'].pop()
                elif mutation == 'extra-result':
                    bad[6]['rep']['components'].append(copy.deepcopy(scalar))
                    bad[6]['rep']['primReps'].extend(scalar['primReps'])
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

    def test_zero_arity_tuple_join_reads_lexical_aggregate_slots_only_when_supported(self):
        module, join = tuple_join_fixture(True)
        producer = module['bindings'][1]['expr']
        region = producer[2]
        original = join['expr']
        proof = join['joinResultRep']
        join['expr'] = [*var('held'), dict(rep=proof)]
        producer[2] = ['case', original, 'held', [['default', None, [], region, dict(binders=[])]],
                       dict(rep=proof, binder=dict(id='held', lifted=False, rep=proof))]
        report = run_tuple(module)
        self.assertTrue(report['accepted'], report['issues'])
        disabled = dict(TUPLE_CAP, aggregateJoinCaptures=[])
        report = audit_core.Audit([('join', module)], disabled).run(['root'])
        self.assertIn('unboxed-tuple join capture', [i['detail'] for i in report['issues']])

    def test_inner_join_calls_outer_tuple_result_join_without_capturing_tuple(self):
        module, outer = tuple_join_fixture(True)
        producer = module['bindings'][1]['expr']
        region = producer[2]
        proof = copy.deepcopy(outer['joinResultRep'])
        inner = dict(id='inner', name='inner', lifted=False, rep=proof,
                     joinValueArity=0, joinResultRep=copy.deepcopy(proof),
                     info=dict(joinArity=0), expr=[*var('finish'), dict(rep=copy.deepcopy(proof))])
        region[3] = ['let', False, [inner], [*var('inner'), dict(rep=copy.deepcopy(proof))],
                     dict(rep=copy.deepcopy(proof))]
        report = run_tuple(module)
        self.assertTrue(report['accepted'], report['issues'])
        # A real tuple binder in the same lexical position needs the capture capability.
        inner['expr'] = [*var('held'), dict(rep=copy.deepcopy(proof))]
        original = outer['expr']
        producer[2] = ['case', original, 'held',
                       [['default', None, [], region, dict(binders=[])]],
                       dict(rep=proof, binder=dict(id='held', lifted=False, rep=proof))]
        disabled = dict(TUPLE_CAP, aggregateJoinCaptures=[])
        report = audit_core.Audit([('join', module)], disabled).run(['root'])
        self.assertIn('unboxed-tuple join capture', [i['detail'] for i in report['issues']])

    def test_join_lambda_calls_outer_tuple_result_join_without_heap_capture(self):
        module, outer = tuple_join_fixture(True)
        producer = module['bindings'][1]['expr']
        region = producer[2]
        proof = copy.deepcopy(outer['joinResultRep'])
        parameter = dict(id='join-arg', lifted=False, rep=LONG)
        inner = dict(id='inner', name='inner', lifted=True, rep=CLOSURE,
                     joinValueArity=1, joinResultRep=copy.deepcopy(proof),
                     info=dict(joinArity=1),
                     expr=['lam', [parameter], [*var('finish'), dict(rep=copy.deepcopy(proof))],
                           dict(rep=CLOSURE, resultRep=copy.deepcopy(proof))])
        call = ['app', [*var('inner'), dict(rep=CLOSURE)], [[*var('x'), dict(rep=LONG)]],
                [False], False, False, dict(rep=copy.deepcopy(proof))]
        region[3] = ['let', False, [inner], call, dict(rep=copy.deepcopy(proof))]
        report = run_tuple(module)
        self.assertTrue(report['accepted'], report['issues'])
    def test_join_prefix_does_not_exempt_a_residual_closure_capture(self):
        module, join = tuple_join_fixture(True)
        producer = module['bindings'][1]['expr']
        region = producer[2]
        original = join['expr']
        proof = join['joinResultRep']
        closure = ['lam', [dict(id='closure-arg', lifted=False, rep=LONG)],
                   [*var('held'), dict(rep=proof)], dict(rep=CLOSURE, resultRep=proof)]
        call = ['app', closure, [['lit', 'int', '1', dict(rep=LONG)]],
                [False], False, False, dict(rep=proof)]
        join['expr'] = ['lam', [dict(id='join-arg', lifted=False, rep=LONG)], call,
                        dict(rep=CLOSURE, resultRep=proof)]
        join['joinValueArity'] = 1
        producer[2] = ['case', original, 'held',
                       [['default', None, [], region, dict(binders=[])]],
                       dict(rep=proof, binder=dict(id='held', lifted=False, rep=proof))]
        report = run_tuple(module)
        self.assertIn('unboxed-tuple capture', [i['detail'] for i in report['issues']])

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

    def test_arithmetic_raises_require_empty_tuple_and_link_original_implicit_payloads(self):
        empty = tuple_rep()
        constructor = dict(id='Empty', kind='unboxed-tuple', arity=0,
                           fieldReps=[], strictFields=[], fieldLifted=[])
        for name, payload in audit_core.ARITHMETIC_EXCEPTIONS.items():
            good = ['app', ['prim', name], [['con', 'Empty', 0, dict(rep=empty)]],
                    [False], False, False, dict(rep=REFERENCE)]
            with self.subTest(name=name):
                missing = run(good, constructors=[constructor])
                self.assertEqual([payload], [item['id'] for item in missing['missingGlobals']])
                supplied = run(good, [bind(payload, var('coldPayloadDependency'))], [constructor])
                self.assertEqual(['coldPayloadDependency'], [item['id'] for item in supplied['missingGlobals']])
                self.assertIn(payload, [item['id'] for item in supplied['reachableBindings']])
                self.assertNotIn('aggregate-boundary', {i['code'] for i in supplied['issues']})
                for argument, flags in [(['void', dict(rep=dict(kind='void', primReps=[], evaluated=True))], [False]),
                                        (good[2][0], [True]),
                                        ([*lit(0), dict(rep=LONG)], [False])]:
                    bad = copy.deepcopy(good)
                    bad[2], bad[3] = [argument], flags
                    self.assertIn('primitive-representation', {i['code'] for i in run(bad, constructors=[constructor])['issues']})

    def test_synchronous_exception_primops_require_exact_boxed_state_tuple_contract(self):
        state = dict(kind='void', primReps=[], evaluated=True)
        result = tuple_rep(state, REFERENCE)
        def application(name):
            roles = (REFERENCE, state) if name == 'raiseIO#' else (CLOSURE, CLOSURE, state)
            args = [[*var('operand'), dict(rep=copy.deepcopy(rep))] for rep in roles]
            return ['app', ['prim', name], args, [rep is not state for rep in roles], False, False,
                    dict(rep=copy.deepcopy(result))]
        for name in ('raiseIO#', 'catch#'):
            good = application(name)
            self.assertNotIn('primitive-representation', {issue['code'] for issue in run(good)['issues']})
            for field in ('action', 'state', 'flags', 'result'):
                bad = copy.deepcopy(good)
                if field == 'action':
                    bad[2][0][-1]['rep'] = LONG
                elif field == 'state':
                    bad[2][-1][-1]['rep'] = dict(state, aggregate='unboxed-tuple', components=[])
                elif field == 'flags':
                    bad[3][-1] = True
                else:
                    bad[-1]['rep']['components'][1] = LONG
                with self.subTest(name=name, field=field):
                    self.assertIn('primitive-representation', {issue['code'] for issue in run(bad)['issues']})

    def test_public_thread_primops_require_exact_thread_id_and_lazy_payload(self):
        state = dict(kind='void', primReps=[], evaluated=True)
        thread = dict(kind='object', primReps=['BoxedRep (Just Unlifted)'], evaluated=True)
        action = dict(kind='closure', primReps=['BoxedRep (Just Lifted)'], evaluated=True)
        payload = dict(kind='data', primReps=['BoxedRep (Just Lifted)'], evaluated=False)
        roles = {'state': state, 'threadId': thread, 'action': action, 'payload': payload, 'int': LONG,
                 'byteArray': thread, 'threadArray': thread}
        contracts = CAP['managedThreadPrimitives']
        self.assertEqual({name: len(spec['arguments']) for name, spec in contracts.items()},
                         {name: CAP['primitives'][name] for name in contracts})
        for name, spec in contracts.items():
            parameters = [dict(id=f'operand{i}', lifted=role in ('action', 'payload'),
                               rep=copy.deepcopy(roles[role]))
                          for i, role in enumerate(spec['arguments'])]
            result = (tuple_rep(*(roles[role] for role in spec['result']))
                      if isinstance(spec['result'], list) else copy.deepcopy(roles[spec['result']]))
            app = ['app', ['prim', name],
                   [[*var(parameter['id']), dict(rep=copy.deepcopy(parameter['rep']))]
                    for parameter in parameters],
                   [parameter['lifted'] for parameter in parameters], False, False,
                   dict(rep=copy.deepcopy(result))]
            body = ['case', app, 'result', [['default', None, [], lit(0)]],
                    dict(rep=LONG, binder=dict(id='result', lifted=False, rep=copy.deepcopy(result)))]
            module = dict(schema=1, ghc='9.14.1', constructors=[], bindings=[dict(
                id='root', name='root', lifted=True, arity=len(parameters),
                expr=['lam', parameters, body, dict(rep=CLOSURE, resultRep=LONG)])])
            def issues():
                return {issue['code'] for issue in audit_core.Audit([('thread.json', module)], CAP).run(['root'])['issues']}
            with self.subTest(name=name):
                self.assertEqual(set(), issues())
                wrong = copy.deepcopy(app[2][0][-1]['rep'])
                app[2][0][-1]['rep'] = state if spec['arguments'][0] != 'state' else thread
                self.assertIn('primitive-representation', issues())
                app[2][0][-1]['rep'] = wrong
                app[3][0] = not app[3][0]
                self.assertIn('primitive-representation', issues())
                app[3][0] = not app[3][0]
                saved = copy.deepcopy(app[6]['rep'])
                app[6]['rep'] = state if isinstance(spec['result'], list) else thread
                self.assertIn('primitive-representation', issues())
                app[6]['rep'] = saved
                if name == 'killThread#':
                    parameters[0]['rep'] = state
                    self.assertIn('primitive-representation', issues())

    def test_masking_state_primops_require_exact_continuation_state_and_int_result(self):
        state = dict(kind='void', primReps=[], evaluated=True)
        for name, args, result in (
                ('getMaskingState#', [state], tuple_rep(state, LONG)),
                ('unmaskAsyncExceptions#', [CLOSURE, state], tuple_rep(state, REFERENCE)),
                ('maskAsyncExceptions#', [CLOSURE, state], tuple_rep(state, REFERENCE)),
                ('maskUninterruptible#', [CLOSURE, state], tuple_rep(state, REFERENCE))):
            good = ['app', ['prim', name], [[*var('operand'), dict(rep=copy.deepcopy(rep))] for rep in args],
                    [rep is not state for rep in args], False, False, dict(rep=copy.deepcopy(result))]
            self.assertNotIn('primitive-representation', {issue['code'] for issue in run(good)['issues']})
            for field in ('state', 'flags', 'result'):
                bad = copy.deepcopy(good)
                if field == 'state':
                    bad[2][-1][-1]['rep'] = LONG
                elif field == 'flags':
                    bad[3][-1] = True
                else:
                    bad[-1]['rep']['components'][1] = REFERENCE if name == 'getMaskingState#' else LONG
                with self.subTest(name=name, field=field):
                    self.assertIn('primitive-representation', {issue['code'] for issue in run(bad)['issues']})

    def test_no_duplicate_requires_exact_state_carrier(self):
        state = dict(kind='void', primReps=[], evaluated=True)
        good = ['app', ['prim', 'noDuplicate#'], [[*var('token'), dict(rep=copy.deepcopy(state))]],
                [False], False, False, dict(rep=copy.deepcopy(state))]
        self.assertNotIn('primitive-representation', {issue['code'] for issue in run(good)['issues']})
        for field in ('argument', 'flags', 'result'):
            bad = copy.deepcopy(good)
            if field == 'argument':
                bad[2][0][-1]['rep'] = LONG
            elif field == 'flags':
                bad[3][0] = True
            else:
                bad[-1]['rep'] = LONG
            with self.subTest(field=field):
                self.assertIn('primitive-representation', {issue['code'] for issue in run(bad)['issues']})

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


class TouchAuditTest(unittest.TestCase):
    state = dict(kind='void', primReps=[], evaluated=True)

    def test_production_admits_the_proven_touch_protocol(self):
        self.assertEqual(2, CAP['primitives'].get('touch#'))
        for lifted in (False, True):
            report = audit_core.Audit([('touch-production.json', self.fixture(lifted=lifted))], CAP).run(['root'])
            self.assertTrue(report['accepted'], report)

    def fixture(self, kind='object', lifted=True):
        kept = dict(kind=kind, primReps=['BoxedRep (Just Lifted)' if lifted else 'BoxedRep (Just Unlifted)'], evaluated=False)
        args = [[*var('kept'), dict(rep=copy.deepcopy(kept))], [*var('s'), dict(rep=copy.deepcopy(self.state))]]
        call = ['app', ['prim', 'touch#'], args, [lifted, False], False, False, dict(rep=copy.deepcopy(self.state))]
        params = [dict(id='kept', lifted=lifted, rep=kept), dict(id='s', lifted=False, rep=copy.deepcopy(self.state))]
        root = dict(bind('root', ['lam', params, call, dict(rep=CLOSURE, resultRep=copy.deepcopy(self.state))]), rep=CLOSURE, arity=2)
        return dict(schema=1, ghc='9.14.1', bindings=[root], constructors=[])

    @staticmethod
    def call(module):
        return module['bindings'][0]['expr'][2]

    def audit(self, module):
        # Runtime/proof controls alone do not enable production admission; the
        # existing pinned-pointer native fixture independently owns that gate.
        cap = dict(CAP, primitives=dict(CAP['primitives'], **{'touch#': 2}))
        return audit_core.Audit([('touch-model.json', module)], cap).run(['root'])

    def test_exact_lifted_and_unlifted_reference_state_contracts(self):
        for kind in ('object', 'data', 'closure'):
            for lifted in (False, True):
                module = self.fixture(kind, lifted)
                self.assertTrue(self.audit(module)['accepted'], (kind, lifted, self.audit(module)))
                self.call(module)[2][0][2]['rep']['evaluated'] = True
                self.assertTrue(self.audit(module)['accepted'])

    def test_malformed_raw_proofs_and_flags_fail_closed(self):
        for where in ('kept', 'state', 'result'):
            for key, value in (('kind', 'long'), ('primReps', ['IntRep']), ('evaluated', 1),
                               ('extra', None), ('vector', None), ('aggregate', 'unboxed-tuple'), ('components', [])):
                module = self.fixture(); call = self.call(module)
                proof = call[6]['rep'] if where == 'result' else call[2][0 if where == 'kept' else 1][2]['rep']
                proof[key] = value
                with self.subTest(where=where, key=key):
                    self.assertFalse(self.audit(module)['accepted'])
            module = self.fixture(); call = self.call(module)
            proof = call[6]['rep'] if where == 'result' else call[2][0 if where == 'kept' else 1][2]['rep']
            proof.pop('evaluated')
            self.assertFalse(self.audit(module)['accepted'])
        for flags in ([], [True], [False, False], [True, True], [1, False], [True, False, False]):
            module = self.fixture(); self.call(module)[3] = flags
            self.assertFalse(self.audit(module)['accepted'], flags)
        for count in (0, 1, 3):
            module = self.fixture(); call = self.call(module)
            call[2] = (call[2] * 2)[:count]; call[3] = [True, False, False][:count]
            self.assertFalse(self.audit(module)['accepted'], count)

    def test_unknown_levity_tuple_state_and_stored_scalar_cannot_be_disguised(self):
        for index, proof in ((0, dict(REFERENCE, primReps=['BoxedRep Nothing'])),
                             (1, tuple_rep(self.state))):
            module = self.fixture(); self.call(module)[2][index][2]['rep'] = proof
            self.assertFalse(self.audit(module)['accepted'])
        module = self.fixture(); self.call(module)[6]['rep'] = tuple_rep(self.state)
        self.assertFalse(self.audit(module)['accepted'])
        for stored in (LONG, dict(kind='object', primReps=['BoxedRep (Just Unlifted)'], evaluated=True)):
            module = self.fixture(); module['bindings'][0]['expr'][1][0]['rep'] = copy.deepcopy(stored)
            self.assertFalse(self.audit(module)['accepted'])
        module = self.fixture()
        module['bindings'][0]['expr'][1].pop(0)
        module['bindings'].append(dict(bind('kept', [*lit(1), dict(rep=LONG)], lifted=False), rep=LONG))
        self.assertFalse(self.audit(module)['accepted'])
        module = self.fixture(); self.call(module)[2][0] = [*lit(1), dict(rep=REFERENCE)]
        self.assertFalse(self.audit(module)['accepted'])


class OriginalStackCloneAuditTest(unittest.TestCase):
    """Unchanged genuine worker; only its scalar-result audit consumer is synthetic."""
    symbol = 'stg_cloneMyStackzh'
    resource = ROOT.parent / 'src/test/resources/core/original-stack-clone.json'

    def fixture(self, moved=False):
        module = json.loads(self.resource.read_text())
        worker = module['bindings'][0]
        lam = worker['expr']
        state = copy.deepcopy(lam[1][0]['rep'])
        boxed = dict(kind='data', primReps=['BoxedRep (Just Lifted)'], evaluated=True)
        result = lam[3]['resultRep']
        call = lam[2] if moved else ['app', ['var', worker['id'], dict(rep=CLOSURE)],
            [['var', lam[1][0]['id'], dict(rep=state)]], [False], False, False, dict(rep=result)]
        body = ['case', call, 'pair', [['data', 'ghc-internal:GHC.Internal.Types.(#,#)', ['s', 'snapshot'],
            ['var', 'snapshot', dict(rep=boxed)], dict(binders=[dict(id='s', lifted=False, rep=state),
                dict(id='snapshot', lifted=True, rep=boxed)])]],
            dict(rep=boxed, binder=dict(id='pair', lifted=False, rep=dict(result, evaluated=True)))]
        consumer = dict(bind('synthetic-consumer', ['lam', copy.deepcopy(lam[1]), body,
                                                dict(rep=CLOSURE, resultRep=boxed)]), rep=CLOSURE, arity=1)
        module['bindings'] = [consumer] if moved else [worker, consumer]
        return module

    @staticmethod
    def call(module):
        return module['bindings'][0]['expr'][2][1]

    def audit(self, module, cap=None):
        return audit_core.Audit([('genuine-clone-with-synthetic-consumer.json', module)],
                               CAP if cap is None else cap).run(['synthetic-consumer'])

    def reject(self, module):
        report = self.audit(module)
        self.assertFalse(report['accepted'], report)
        self.assertEqual([], report['foreignCalls'], report)
        return report

    def test_authentic_worker_and_moved_body_admit_only_the_exact_foreign_seam(self):
        self.assertEqual(1, CAP['managedForeignCalls'].count(self.symbol))
        original = json.loads(self.resource.read_text())['bindings'][0]
        for moved in (False, True):
            module = self.fixture(moved)
            if not moved: self.assertEqual(original, module['bindings'][0])
            report = self.audit(module)
            self.assertTrue(report['accepted'], report)
            self.assertEqual([self.symbol], [call['symbol'] for call in report['foreignCalls']])
            self.assertEqual([], report['missingGlobals'])
        module = self.fixture()
        self.call(module)[1][1] = 'unrelated:InlinedCaller.foreign'
        self.assertTrue(self.audit(module)['accepted'])
        module = self.fixture(); call = self.call(module)
        call[2][0][2]['rep']['evaluated'] = False
        call[6]['rep']['evaluated'] = True
        self.assertTrue(self.audit(module)['accepted'])
        module = self.fixture(); call = self.call(module)
        call[2][0] = ['void', dict(rep=copy.deepcopy(call[2][0][2]['rep']))]
        self.assertTrue(self.audit(module)['accepted'])

    def test_capability_is_explicit_and_remote_capture_and_aliases_stay_closed(self):
        module = self.fixture()
        report = self.audit(module, dict(CAP, managedForeignCalls=[]))
        self.assertFalse(report['accepted']); self.assertEqual([], report['foreignCalls'])
        self.assertTrue(any('capability disabled' in str(issue['detail']) for issue in report['issues']))
        for symbol in ('cloneMyStack#', 'stg_cloneMyStackzh2', 'prefixstg_cloneMyStackzh',
                       'stg_sendCloneStackMessagezh', 'stg_decodeStackzh', 'getStackFieldszh2',
                       'getStackClosurezh2', 'advanceStackFrameLocationzh2', 'getWordzh2'):
            self.assertNotIn(symbol, core_original_foreign.OPERATIONS)
            self.assertNotIn(symbol, CAP['managedForeignCalls'])
            module = self.fixture(); self.call(module)[6]['foreignCall']['target']['symbol'] = symbol
            self.reject(module)

    def test_descriptor_integer_target_convention_and_safety_mutations_reject(self):
        mutations = [(key, value) for key in ('schema', 'arity', 'suppliedArity')
                     for value in (None, True, False, 1.0, '1', 0, 2, 1 << 32)]
        mutations += [('convention', x) for x in (None, 'ccall', 'capi', 'javascript')]
        mutations += [('safety', x) for x in (None, 'unsafe', 'interruptible')]
        mutations += [('extra', None)]
        for key, value in mutations:
            module = self.fixture(); self.call(module)[6]['foreignCall'][key] = value; self.reject(module)
        for key, values in {'kind': (None, 'dynamic'), 'unit': (None, '', 'main', 1),
                            'isFunction': (None, False, 1), 'extra': (None,)}.items():
            for value in values:
                module = self.fixture(); self.call(module)[6]['foreignCall']['target'][key] = value; self.reject(module)

    def test_raw_state_flags_and_all_result_proofs_reject_forgery(self):
        for declared in (False, True):
            for key, value in (('kind', 'unknown'), ('primReps', ['IntRep']), ('evaluated', 1),
                               ('aggregate', 'unboxed-tuple'), ('components', []), ('vector', None)):
                module = self.fixture(); call = self.call(module)
                proof = call[6]['foreignCall']['argumentReps'][0] if declared else call[2][0][2]['rep']
                proof[key] = value; self.reject(module)
        for flags in ([], [True], [0], [None], [False, False]):
            module = self.fixture(); self.call(module)[3] = flags; self.reject(module)
        for declared in (False, True):
            for extra in (False, True):
                module = self.fixture(); call = self.call(module)
                arguments = call[6]['foreignCall']['argumentReps'] if declared else call[2]
                if extra: arguments.append(copy.deepcopy(arguments[0]))
                else: arguments.clear()
                self.reject(module)
        for site in ('declared', 'actual'):
            for key, value in (('kind', 'object'), ('primReps', ['BoxedRep (Just Lifted)']), ('evaluated', 1),
                               ('aggregate', 'unboxed-sum'), ('components', []), ('extra', None)):
                module = self.fixture(); meta = self.call(module)[6]
                proof = meta['foreignCall']['resultRep'] if site == 'declared' else meta['rep']
                proof[key] = value; self.reject(module)
            for index in (0, 1):
                for key, value in (('kind', 'unknown'), ('evaluated', False), ('primReps', ['IntRep']), ('vector', None)):
                    module = self.fixture(); meta = self.call(module)[6]
                    proof = meta['foreignCall']['resultRep'] if site == 'declared' else meta['rep']
                    proof['components'][index][key] = value; self.reject(module)
        module = self.fixture(); self.call(module)[6]['foreignCall']['argumentReps'][0]['evaluated'] = True; self.reject(module)
        module = self.fixture(); self.call(module)[6]['foreignCall']['resultRep']['evaluated'] = True; self.reject(module)

    def test_unbound_head_and_stored_state_cannot_be_relabelled(self):
        for head in ([], ['var', None], ['var', ''], ['var', 3], ['prim', self.symbol], ['var', 'unproved']):
            module = self.fixture(); self.call(module)[1] = head; self.reject(module)
        for key, value in (('kind', 'long'), ('primReps', ['BoxedRep (Just Unlifted)']),
                           ('evaluated', False), ('evaluated', 1), ('extra', None)):
            module = self.fixture(); self.call(module)[1][2]['rep'][key] = value; self.reject(module)
        for scope in ('formal', 'global'):
            module = self.fixture(); worker = module['bindings'][0]
            self.call(module)[1][1] = worker['expr'][1][0]['id'] if scope == 'formal' else worker['id']
            self.reject(module)
        bad_states = [LONG, REFERENCE, dict(kind='unknown', primReps=['IntRep'], evaluated=True),
            dict(kind='unknown', primReps=['BoxedRep Nothing'], evaluated=True), tuple_rep(),
            dict(kind='void', primReps=[], evaluated=True, vector={})]
        for bad in bad_states:
            for scope in ('formal', 'global'):
                module = self.fixture()
                if scope == 'formal': module['bindings'][0]['expr'][1][0]['rep'] = copy.deepcopy(bad)
                else:
                    module['bindings'].append(dict(bind('stored', lit(7), False), rep=copy.deepcopy(bad)))
                    self.call(module)[2][0][1] = 'stored'
                report = self.reject(module)
                self.assertTrue(any('stored State' in str(issue['detail']) for issue in report['issues']))
        module = self.fixture(); state = self.call(module)[2][0][2]
        self.call(module)[2][0] = ['lit', 'int', '7', state]; self.reject(module)


class LibdwUnavailableAuditTest(unittest.TestCase):
    """Original declaration certificates; synthetic consumers, no closure claim."""
    resource = ROOT.parent / 'src/test/resources/core/original-libdw-descriptors.json'

    def fixture(self, declaration):
        declaration = copy.deepcopy(declaration)
        parameters = [dict(id=f'arg-{i}', lifted=False, rep=dict(rep, evaluated=True))
                      for i, rep in enumerate(declaration['argumentReps'])]
        call = ['app', ['var', 'synthetic-fcall-id', dict(rep=CLOSURE)],
                [['var', p['id'], dict(rep=p['rep'])] for p in parameters],
                [False] * len(parameters), False, False,
                dict(rep=declaration['resultRep'], foreignCall=declaration)]
        body = ['case', call, 'tuple-result', [['default', None, [], [*lit(0), dict(rep=LONG)]]],
                dict(rep=LONG, binder=dict(id='tuple-result', lifted=False, rep=dict(declaration['resultRep'], evaluated=True)))]
        wrapper = dict(bind('consumer', ['lam', parameters, body, dict(rep=CLOSURE, resultRep=LONG)]),
                       rep=CLOSURE, arity=len(parameters))
        return dict(schema=1, ghc='9.14.1', bindings=[wrapper], constructors=[])

    def audit(self, module, cap=CAP):
        return audit_core.Audit([('synthetic-libdw-consumer.json', module)], cap).run(['consumer'])

    def test_original_declarations_admitted_only_with_explicit_unavailable_backend(self):
        declarations = json.loads(self.resource.read_text())
        self.assertEqual(set(core_original_foreign.LIBDW_UNAVAILABLE),
                         {d['target']['symbol'] for d in declarations})
        for declaration in declarations:
            module = self.fixture(declaration)
            result = self.audit(module)
            self.assertTrue(result['accepted'], result)
            self.assertEqual([], result['missingGlobals'])
            self.assertEqual([declaration['target']['symbol']], [c['symbol'] for c in result['foreignCalls']])
            result = self.audit(module, dict(CAP, managedForeignCalls=[]))
            self.assertFalse(result['accepted'])
            self.assertEqual([], result['foreignCalls'])

    def test_descriptor_and_stored_operand_spoofs_rejected(self):
        for declaration in json.loads(self.resource.read_text()):
            target = declaration['target']
            for incorrect in [dict(declaration, schema=1.0), dict(declaration, safety='safe'),
                              dict(declaration, arity=0), dict(declaration, convention='capi'),
                              dict(declaration, target=dict(target, unit='main')),
                              dict(declaration, target=dict(target, isFunction=False)),
                              dict(declaration, resultRep=LONG)]:
                result = self.audit(self.fixture(incorrect))
                self.assertFalse(result['accepted'], result)
                self.assertEqual([], result['foreignCalls'])
            module = self.fixture(declaration)
            module['bindings'][0]['expr'][1][0]['rep'] = LONG
            result = self.audit(module)
            self.assertFalse(result['accepted'], result)
            self.assertEqual([], result['foreignCalls'])


class OriginalSignalDeclarationTest(unittest.TestCase):
    def test_exact_call_requires_capability_and_implicit_original_dispatcher(self):
        resource = ROOT.parent / 'src/test/resources/core/original-signal-install-descriptor.json'
        declaration = json.loads(resource.read_text())
        fixture = LibdwUnavailableAuditTest()
        module = fixture.fixture(declaration)
        enabled = dict(CAP, managedForeignCalls=[*CAP['managedForeignCalls'], 'stg_sig_install'])
        missing = fixture.audit(module, enabled)
        self.assertFalse(missing['accepted'])
        dispatcher = 'ghc-internal:GHC.Internal.Conc.Signal.runHandlersPtr'
        self.assertTrue(any(item['id'] == dispatcher for item in missing['missingGlobals']), missing)
        # A synthetic dispatcher is only a linkage control, not original delivery evidence.
        module['bindings'].append(bind(dispatcher, lit(0)))
        self.assertTrue(fixture.audit(module, enabled)['accepted'])
        disabled = dict(CAP, managedForeignCalls=[s for s in CAP['managedForeignCalls'] if s != 'stg_sig_install'])
        self.assertFalse(fixture.audit(module, disabled)['accepted'])
        for index in range(4):
            wrong = copy.deepcopy(module)
            wrong['bindings'][0]['expr'][1][index]['rep'] = LONG
            self.assertFalse(fixture.audit(wrong, enabled)['accepted'])


class NativeMallocDeclarationTest(unittest.TestCase):
    """Original descriptors for the bounded, owned native allocation protocol."""
    def test_two_exact_declarations_and_capability_gate(self):
        resource = ROOT.parent / 'src/test/resources/core/original-malloc-descriptors.json'
        declarations = json.loads(resource.read_text())
        self.assertEqual(['malloc', 'free'], [d['target']['symbol'] for d in declarations])
        fixture = LibdwUnavailableAuditTest()
        disabled = dict(CAP, managedForeignCalls=[s for s in CAP['managedForeignCalls'] if s not in ('malloc', 'free')])
        for declaration in declarations:
            module = fixture.fixture(declaration)
            self.assertTrue(fixture.audit(module)['accepted'])
            self.assertFalse(fixture.audit(module, disabled)['accepted'])
            for key, value in [('safety', 'safe'), ('arity', 3), ('convention', 'capi'), ('schema', 1.0)]:
                wrong = copy.deepcopy(declaration); wrong[key] = value
                self.assertFalse(fixture.audit(fixture.fixture(wrong))['accepted'])
            wrong = copy.deepcopy(declaration); wrong['target']['unit'] = 'other'
            self.assertFalse(fixture.audit(fixture.fixture(wrong))['accepted'])
            # Occurrence annotations cannot hide a differently represented argument.
            for index in range(len(declaration['argumentReps'])):
                wrong = fixture.fixture(declaration)
                wrong['bindings'][0]['expr'][1][index]['rep'] = CLOSURE
                result = fixture.audit(wrong)
                self.assertFalse(result['accepted'], result)
                self.assertEqual([], result['foreignCalls'])
        for symbol in ('calloc', 'realloc', 'prefixmalloc', 'free2'):
            self.assertNotIn(symbol, core_original_foreign.OPERATIONS)


class OriginalMemmoveDeclarationTest(unittest.TestCase):
    def test_exact_original_descriptor_and_checked_capability(self):
        resource = ROOT.parent / 'src/test/resources/core/original-memmove-descriptor.json'
        declaration = json.loads(resource.read_text())
        self.assertEqual('memmove', declaration['target']['symbol'])
        fixture = LibdwUnavailableAuditTest()
        module = fixture.fixture(declaration)
        self.assertTrue(fixture.audit(module)['accepted'])
        disabled = dict(CAP, managedForeignCalls=[s for s in CAP['managedForeignCalls'] if s != 'memmove'])
        self.assertFalse(fixture.audit(module, disabled)['accepted'])
        for key, value in [('safety', 'safe'), ('arity', 3), ('schema', 1.0),
                           ('argumentReps', declaration['argumentReps'][:3]), ('resultRep', LONG)]:
            wrong = copy.deepcopy(declaration); wrong[key] = value
            self.assertFalse(fixture.audit(fixture.fixture(wrong))['accepted'])
        wrong = copy.deepcopy(declaration); wrong['target']['unit'] = 'other'
        self.assertFalse(fixture.audit(fixture.fixture(wrong))['accepted'])
        for index in range(4):
            wrong = fixture.fixture(declaration)
            wrong['bindings'][0]['expr'][1][index]['rep'] = LONG
            self.assertFalse(fixture.audit(wrong)['accepted'])


class OriginalMemcpyDeclarationTest(unittest.TestCase):
    def test_exact_original_descriptor_and_checked_capability(self):
        resource = ROOT.parent / 'src/test/resources/core/original-memcpy-descriptor.json'
        declaration = json.loads(resource.read_text())
        self.assertEqual('memcpy', declaration['target']['symbol'])
        fixture = LibdwUnavailableAuditTest()
        result = fixture.audit(fixture.fixture(declaration))
        self.assertTrue(result['accepted'], result)
        self.assertEqual(['memcpy'], [call['symbol'] for call in result['foreignCalls']])
        disabled = dict(CAP, managedForeignCalls=[s for s in CAP['managedForeignCalls'] if s != 'memcpy'])
        self.assertFalse(fixture.audit(fixture.fixture(declaration), disabled)['accepted'])
        for key, value in [('safety', 'safe'), ('convention', 'capi'), ('arity', 3),
                           ('suppliedArity', 3), ('schema', 1.0), ('resultRep', LONG),
                           ('argumentReps', declaration['argumentReps'][:3])]:
            wrong = copy.deepcopy(declaration); wrong[key] = value
            result = fixture.audit(fixture.fixture(wrong))
            self.assertFalse(result['accepted'], result)
            self.assertEqual([], result['foreignCalls'])
        for key, value in [('unit', None), ('unit', 'other'), ('kind', 'dynamic'), ('isFunction', False)]:
            wrong = copy.deepcopy(declaration); wrong['target'][key] = value
            self.assertFalse(fixture.audit(fixture.fixture(wrong))['accepted'])
        for symbol in ('memcpy2', '__memcpy_chk', 'prefixmemcpy'):
            self.assertNotIn(symbol, core_original_foreign.OPERATIONS)

    def test_stored_intrinsic_head_and_raw_proof_spoofs_reject(self):
        declaration = json.loads((ROOT.parent / 'src/test/resources/core/original-memcpy-descriptor.json').read_text())
        fixture = LibdwUnavailableAuditTest()

        def reject(change):
            module = fixture.fixture(declaration)
            call = module['bindings'][0]['expr'][2][1]
            change(module, call)
            result = fixture.audit(module)
            self.assertFalse(result['accepted'], result)
            self.assertEqual([], result['foreignCalls'])

        for index in range(4):
            reject(lambda module, call: module['bindings'][0]['expr'][1][index].__setitem__('rep', LONG))
            reject(lambda module, call: call[2][index].__setitem__(2, {}))
        for head in [['var', 'arg-0', dict(rep=CLOSURE)], ['prim', 'memcpy', dict(rep=CLOSURE)],
                     ['var', 'unproved-foreign-variable']]:
            reject(lambda module, call: call.__setitem__(1, head))
        reject(lambda module, call: call.__setitem__(3, [True, False, False, False]))
        reject(lambda module, call: call[2].__setitem__(0,
            ['lit', 'int', '0', dict(rep=dict(declaration['argumentReps'][0], evaluated=True))]))
        reject(lambda module, call: call[6].__setitem__('rep', LONG))

        def shadow_with_join(module, call):
            output = declaration['resultRep']
            parameters = [dict(id=f'join-{i}', lifted=False, rep=dict(rep, evaluated=True))
                          for i, rep in enumerate(declaration['argumentReps'])]
            pair = ['app', ['con', 'Tuple2', 2, dict(rep=CLOSURE)],
                    [['var', parameters[i]['id'], dict(rep=parameters[i]['rep'])] for i in (3, 0)],
                    [False, False], False, False, dict(rep=dict(output, evaluated=True))]
            join = dict(id='synthetic-fcall-id', name='shadowedForeign', lifted=True, rep=CLOSURE,
                        expr=['lam', parameters, pair, dict(rep=CLOSURE, resultRep=output)], joinValueArity=4,
                        joinResultRep=output, info=dict(joinArity=4))
            wrapper = module['bindings'][0]['expr']
            wrapper[2][1] = ['let', False, [join], call, dict(rep=output)]
            module['constructors'] = [dict(id='Tuple2', name='(#,#)', kind='unboxed-tuple', arity=2,
                                           fieldReps=[None, None], strictFields=[False, False], fieldLifted=[False, False])]

        shadowed = fixture.fixture(declaration)
        shadow_with_join(shadowed, shadowed['bindings'][0]['expr'][2][1])
        ordinary = copy.deepcopy(shadowed)
        del ordinary['bindings'][0]['expr'][2][1][3][6]['foreignCall']
        self.assertTrue(fixture.audit(ordinary)['accepted'])
        result = fixture.audit(shadowed)
        self.assertFalse(result['accepted'], result)
        self.assertEqual([], result['foreignCalls'])
        self.assertTrue(any('unresolved declared foreign variable required' in issue['detail']
                            for issue in result['issues']), result)


class OriginalStringRtsDeclarationTest(unittest.TestCase):
    def test_retained_posix_descriptors_and_capability(self):
        resource = ROOT.parent / 'src/test/resources/core/original-string-rts-descriptors.json'
        declarations = json.loads(resource.read_text())
        self.assertEqual({'strlen', 'rts_isThreaded'}, set(declarations))
        fixture = LibdwUnavailableAuditTest()
        for symbol, declaration in declarations.items():
            self.assertEqual(symbol, declaration['target']['symbol'])
            module = fixture.fixture(declaration)
            result = fixture.audit(module)
            self.assertTrue(result['accepted'], result)
            self.assertEqual([symbol], [call['symbol'] for call in result['foreignCalls']])
            disabled = dict(CAP, managedForeignCalls=[s for s in CAP['managedForeignCalls'] if s != symbol])
            self.assertFalse(fixture.audit(module, disabled)['accepted'])
            for key, value in (('safety', 'safe'), ('arity', 7), ('schema', 1.0),
                               ('argumentReps', []), ('resultRep', LONG)):
                wrong = copy.deepcopy(declaration); wrong[key] = value
                self.assertFalse(fixture.audit(fixture.fixture(wrong))['accepted'])
            wrong = copy.deepcopy(declaration); wrong['target']['unit'] = 'base'
            self.assertFalse(fixture.audit(fixture.fixture(wrong))['accepted'])
            for index in range(len(declaration['argumentReps'])):
                wrong = fixture.fixture(declaration)
                wrong['bindings'][0]['expr'][1][index]['rep'] = LONG
                self.assertFalse(fixture.audit(wrong)['accepted'])


class OriginalStackInfoAuditTest(unittest.TestCase):
    """Genuine unchanged FCall applications in explicitly synthetic scalar consumers.

    This is a raw-proof audit, not execution of GHC's complete Decode closure.
    Production capabilities admit only the independently implemented protocols;
    validator controls explicitly inject capabilities for the other declarations.
    """
    info_symbols = ('getStackInfoTableAddrzh', 'getInfoTableAddrszh', 'lookupIPE')
    symbols = (*info_symbols, 'getUnderflowFrameNextChunkzh', 'getWordzh', 'isArgGenBigRetFunTypezh',
        'getLargeBitmapzh', 'getBCOLargeBitmapzh', 'getRetFunLargeBitmapzh', 'getSmallBitmapzh',
        'getRetFunSmallBitmapzh', 'getStackClosurezh', 'getStackFieldszh', 'advanceStackFrameLocationzh')
    resource = ROOT.parent / 'src/test/resources/core/original-stack-info-calls.json'
    reviewed_resource = ROOT.parent / 'compiler/test-fixtures/OriginalStackProof.json'

    @classmethod
    def setUpClass(cls):
        cls.info = json.loads(cls.resource.read_text())
        cls.reviewed = json.loads(cls.reviewed_resource.read_text())

    def fixture(self, symbol):
        if symbol in self.info_symbols:
            call = next(r['application'] for r in self.info['calls'] if r['application'][6]['foreignCall']['target']['symbol'] == symbol)
        else:
            call = next(r['expression'] for r in self.reviewed['calls']
                if r['expression'][6]['foreignCall']['target']['symbol'] == symbol and all(a[0] == 'var' for a in r['expression'][2]))
        call = copy.deepcopy(call)
        parameters = [dict(id=a[1], lifted=False, rep=copy.deepcopy(a[2]['rep'])) for a in call[2]]
        case = ['case', call, 'synthetic-result', [['default', None, [], [*lit(0), dict(rep=LONG)]]],
                dict(rep=LONG, binder=dict(id='synthetic-result',
                    lifted=call[6]['rep']['primReps'] == ['BoxedRep (Just Lifted)'],
                    rep=dict(copy.deepcopy(call[6]['rep']), evaluated=True)))]
        wrapper = dict(bind('synthetic-consumer', ['lam', parameters, case, dict(rep=CLOSURE, resultRep=LONG)]),
                       rep=CLOSURE, arity=len(parameters))
        module = dict(schema=1, ghc='9.14.1', bindings=[wrapper], constructors=[])
        if symbol in self.info_symbols:
            module.update(sourceFiles=self.info['sourceFiles'], sourceSpans=self.info['sourceSpans'])
        # Remaining excerpts deliberately carry no synthetic source-table data.
        # They exercise the structural auditor, never an executable loader.
        return module

    @staticmethod
    def call(module):
        return module['bindings'][0]['expr'][2][1]

    def audit(self, module, enabled=True):
        capabilities = [s for s in CAP['managedForeignCalls'] if s not in self.symbols]
        if enabled: capabilities.extend(self.symbols)
        return audit_core.Audit([('genuine-stack-info-app-with-synthetic-consumer.json', module)],
            dict(CAP, managedForeignCalls=capabilities)).run(['synthetic-consumer'])

    def reject(self, module, detail=None):
        report = self.audit(module)
        self.assertFalse(report['accepted'], report)
        self.assertEqual([], report['foreignCalls'], report)
        if detail is not None:
            self.assertTrue(any(detail in str(issue['detail']) for issue in report['issues']), report)

    def test_exact_original_applications_accept_with_explicit_capability_only(self):
        resource = json.loads(self.resource.read_text())
        self.assertTrue(set(self.info_symbols) <= set(CAP['managedForeignCalls']))
        self.assertEqual('902339d332fb4ce2b3c87dcac1ee6495d41ad886', resource['ghcRevision'])
        self.assertEqual('62e3400c5b889d3971cb4047709c408fd270255f', resource['exporterRevision'])
        self.assertEqual(set(self.info_symbols), {r['application'][6]['foreignCall']['target']['symbol'] for r in resource['calls']})
        for record in resource['calls']:
            symbol = record['application'][6]['foreignCall']['target']['symbol']
            module = self.fixture(symbol)
            self.assertEqual(record['application'], self.call(module))
            report = self.audit(module)
            self.assertTrue(report['accepted'], report)
            self.assertEqual([symbol], [call['symbol'] for call in report['foreignCalls']])
            self.assertEqual([], report['missingGlobals'])
            report = self.audit(module, enabled=False)
            self.assertFalse(report['accepted'], report)
            self.assertEqual([], report['foreignCalls'])
            self.assertTrue(any('capability disabled' in str(i['detail']) for i in report['issues']))
            # Foreign identity is the descriptor, not a particular owner/head ID.
            self.call(module)[1][1] = 'unrelated:InlinedCaller.foreign'
            self.assertTrue(self.audit(module)['accepted'])

    def test_all_reviewed_original_declarations_and_getter_wrappers_are_exact(self):
        self.assertEqual('902339d332fb4ce2b3c87dcac1ee6495d41ad886', self.reviewed['ghcSourceRevision'])
        self.assertEqual('62e3400c5b889d3971cb4047709c408fd270255f', self.reviewed['exporterRevision'])
        decode = next(m for m in self.reviewed['originals'] if m['module'] == 'GHC.Internal.Stack.Decode')
        self.assertEqual('0ea6a82ea41bdf14b28aec5cb36a586ed86eb6f87f373ea21095d2b1b018089f', decode['sourceSha256'])
        self.assertEqual(28, len(self.reviewed['calls']))
        seen = set()
        for record in self.reviewed['calls']:
            call = record['expression']; symbol = call[6]['foreignCall']['target']['symbol']; seen.add(symbol)
            with self.subTest(symbol=symbol, owner=record['owner'], path=record['path']):
                self.assertEqual(symbol, core_original_foreign.validate(call[6],
                    [core_original_foreign.raw_rep(a) for a in call[2]], call[3], call[6]['rep']))
                core_original_foreign.validate_head(call[1], False)
        self.assertEqual(set(self.symbols) | {'stg_cloneMyStackzh'}, seen)
        self.assertEqual(set(self.symbols), core_original_foreign.STACK_INFO)
        for symbol in self.symbols:
            with self.subTest(symbol=symbol):
                module = self.fixture(symbol)
                self.assertIn(self.call(module), [r['expression'] for r in self.reviewed['calls']])
                report = self.audit(module)
                self.assertTrue(report['accepted'], report)
                self.assertEqual([symbol], [call['symbol'] for call in report['foreignCalls']])
                self.assertEqual([], report['missingGlobals'])
                disabled = self.audit(module, enabled=False)
                self.assertFalse(disabled['accepted']); self.assertEqual([], disabled['foreignCalls'])
                self.assertTrue(any('capability disabled' in str(i['detail']) for i in disabled['issues']))

    def test_production_admits_exact_getters_for_closed_managed_snapshot_domain(self):
        hot = set(self.symbols)
        self.assertTrue(any('context-owned immutable managed snapshots' in text for text in CAP['limitations']))
        self.assertEqual(hot, set(self.symbols) & set(CAP['managedForeignCalls']))
        for symbol in self.symbols:
            with self.subTest(symbol=symbol):
                report = audit_core.Audit([('original-getter-production-capability.json', self.fixture(symbol))],
                    CAP).run(['synthetic-consumer'])
                self.assertEqual(symbol in hot, report['accepted'], report)
                self.assertEqual([symbol] if symbol in hot else [],
                    [call['symbol'] for call in report['foreignCalls']])
                if symbol not in hot:
                    self.assertTrue(any('capability disabled' in str(i['detail']) for i in report['issues']))

    def test_projected_source_records_exactly_cover_original_application_notes(self):
        resource = json.loads(self.resource.read_text())
        # Canonical hashes of records copied directly from the two pinned source
        # exports, not coordinates inferred from span IDs or freshly read files.
        for key, expected in (
            ('sourceFiles', 'e695a8a85c68c6fe276608c9a9564156ec639387edbacb9ef823a3c342859a14'),
            ('sourceSpans', '011dc0f96e5b8e1ab8510ad20313fe73e11c19622aefafb7e1b57a78d8a1b551')):
            encoded = json.dumps(resource[key], sort_keys=True, separators=(',', ':')).encode()
            self.assertEqual(expected, hashlib.sha256(encoded).hexdigest())
        referenced = set()
        def collect(value):
            if isinstance(value, dict):
                if isinstance(value.get('source'), str): referenced.add(value['source'])
                referenced.update(value.get('sourceNotes', []))
                for child in value.values(): collect(child)
            elif isinstance(value, list):
                for child in value: collect(child)
        for record in resource['calls']: collect(record['application'])
        spans = resource['sourceSpans']; files = resource['sourceFiles']
        self.assertEqual(15, len(spans)); self.assertEqual(2, len(files))
        self.assertEqual(len(spans), len({s['id'] for s in spans}))
        self.assertEqual(referenced, {s['id'] for s in spans})
        self.assertEqual({s['file'] for s in spans}, {f['id'] for f in files})
        for symbol in self.info_symbols:
            wrapper = self.fixture(symbol)
            self.assertEqual(files, wrapper['sourceFiles'])
            self.assertEqual(spans, wrapper['sourceSpans'])

    def test_exact_descriptor_schema_target_saturation_convention_and_safety(self):
        for symbol in self.symbols:
            mutations = [(key, value) for key in ('schema', 'arity', 'suppliedArity')
                for value in (None, True, False, 1.0, '1', 0, 4, 1 << 32)]
            mutations += [('convention', x) for x in (None, 'capi', 'javascript')]
            mutations += [('safety', x) for x in (None, 'unsafe', 'interruptible')]
            mutations += [('extra', None)]
            for key, value in mutations:
                with self.subTest(symbol=symbol, key=key, value=value):
                    module = self.fixture(symbol); self.call(module)[6]['foreignCall'][key] = value
                    self.reject(module)
            module = self.fixture(symbol); descriptor = self.call(module)[6]['foreignCall']
            descriptor['convention'] = 'prim' if descriptor['convention'] == 'ccall' else 'ccall'
            self.reject(module)
            for key, values in {'kind': (None, 'dynamic'), 'unit': (None, '', 'main', 1),
                                'isFunction': (None, False, 1), 'extra': (None,)}.items():
                for value in values:
                    module = self.fixture(symbol); self.call(module)[6]['foreignCall']['target'][key] = value
                    self.reject(module)

    def test_all_actual_and_declared_argument_proofs_flags_and_lengths_are_exact(self):
        mutations = [('kind', 'unknown'), ('primReps', None), ('primReps', ['BoxedRep Nothing']),
            ('primReps', ['IntRep']), ('primReps', ['Word8Rep']), ('evaluated', 1),
            ('aggregate', 'unboxed-tuple'), ('components', []), ('vector', None), ('extra', None)]
        for symbol in self.symbols:
            count = len(self.call(self.fixture(symbol))[2])
            for index in range(count):
                for declared in (False, True):
                    for key, value in mutations:
                        module = self.fixture(symbol); call = self.call(module)
                        proof = call[6]['foreignCall']['argumentReps'][index] if declared else call[2][index][2]['rep']
                        proof[key] = value; self.reject(module)
                for flag in (True, 0, None):
                    module = self.fixture(symbol); self.call(module)[3][index] = flag; self.reject(module)
                module = self.fixture(symbol)
                self.call(module)[6]['foreignCall']['argumentReps'][index]['evaluated'] = True
                self.reject(module)
                module = self.fixture(symbol); self.call(module)[2][index][2]['rep']['evaluated'] = False
                self.assertTrue(self.audit(module)['accepted'])
            for site in ('actual', 'declared', 'flags'):
                for extra in (False, True):
                    module = self.fixture(symbol); call = self.call(module)
                    values = call[2] if site == 'actual' else call[3] if site == 'flags' else call[6]['foreignCall']['argumentReps']
                    if extra: values.append(copy.deepcopy(values[0]))
                    else: values.pop()
                    self.reject(module)

    def test_exact_scalar_and_tuple_result_shapes_cannot_be_interchanged(self):
        originals = [self.call(self.fixture(symbol))[6]['rep'] for symbol in self.symbols]
        for symbol, original in zip(self.symbols, originals):
            for declared in (False, True):
                for other in originals:
                    if dict(other, evaluated=False) == dict(original, evaluated=False): continue
                    module = self.fixture(symbol); meta = self.call(module)[6]
                    if declared: meta['foreignCall']['resultRep'] = copy.deepcopy(other)
                    else: meta['rep'] = copy.deepcopy(other)
                    self.reject(module)
                for key, value in (('kind', 'void'), ('primReps', ['FloatRep']), ('evaluated', 1),
                                   ('aggregate', 'unboxed-sum'), ('components', []), ('extra', None)):
                    module = self.fixture(symbol); meta = self.call(module)[6]
                    proof = meta['foreignCall']['resultRep'] if declared else meta['rep']
                    proof[key] = value; self.reject(module)
                for index in range(len(original.get('components', []))):
                    for key, value in (('kind', 'unknown'), ('evaluated', False), ('primReps', ['Int8Rep']), ('vector', None)):
                        module = self.fixture(symbol); meta = self.call(module)[6]
                        proof = meta['foreignCall']['resultRep'] if declared else meta['rep']
                        proof['components'][index][key] = value; self.reject(module)
            module = self.fixture(symbol); self.call(module)[6]['foreignCall']['resultRep']['evaluated'] = True
            self.reject(module)
            module = self.fixture(symbol); self.call(module)[6]['rep']['evaluated'] = True
            self.assertTrue(self.audit(module)['accepted'])
            # The caller-supplied result proof is independently checked too.
            call = self.call(self.fixture(symbol))
            with self.assertRaises(ValueError):
                core_original_foreign.validate(call[6], [a[2]['rep'] for a in call[2]], call[3], None)

    def test_scalar_results_preserve_signedness_width_and_exact_any_carrier(self):
        for symbol in ('getStackFieldszh', 'getWordzh', 'isArgGenBigRetFunTypezh',
                       'getStackClosurezh', 'getUnderflowFrameNextChunkzh'):
            original = self.call(self.fixture(symbol))[6]['rep']
            for declared in (False, True):
                for rep in ('IntRep', 'WordRep', 'Int32Rep', 'Word32Rep', 'Word64Rep',
                            'BoxedRep (Just Lifted)', 'BoxedRep (Just Unlifted)', 'BoxedRep Nothing'):
                    if original['primReps'] == [rep]: continue
                    module = self.fixture(symbol); meta = self.call(module)[6]
                    proof = meta['foreignCall']['resultRep'] if declared else meta['rep']
                    proof['primReps'] = [rep]; self.reject(module)
                if symbol == 'getStackClosurezh':
                    for kind in ('data', 'closure', 'unknown'):
                        module = self.fixture(symbol); meta = self.call(module)[6]
                        proof = meta['foreignCall']['resultRep'] if declared else meta['rep']
                        proof['kind'] = kind; self.reject(module)

    def test_head_must_remain_an_exact_unbound_foreign_variable(self):
        for symbol in self.symbols:
            for head in ([], ['var', None], ['var', ''], ['var', 3], ['prim', symbol], ['var', 'unproved']):
                module = self.fixture(symbol); self.call(module)[1] = head; self.reject(module)
            for key, value in (('kind', 'long'), ('primReps', ['BoxedRep (Just Unlifted)']),
                               ('evaluated', False), ('evaluated', 1), ('extra', None)):
                module = self.fixture(symbol); self.call(module)[1][2]['rep'][key] = value; self.reject(module)
            for scope in ('formal', 'global'):
                module = self.fixture(symbol); wrapper = module['bindings'][0]
                self.call(module)[1][1] = wrapper['expr'][1][0]['id'] if scope == 'formal' else wrapper['id']
                self.reject(module)

    def test_stored_carrier_levity_scalar_and_zero_width_proofs_cannot_be_relabelled(self):
        bad_proofs = [LONG, REFERENCE, CLOSURE, dict(kind='void', primReps=[], evaluated=True),
            dict(kind='address', primReps=['AddrRep'], evaluated=True),
            dict(kind='unknown', primReps=['IntRep'], evaluated=True),
            dict(kind='unknown', primReps=['BoxedRep Nothing'], evaluated=True), tuple_rep(),
            dict(kind='unknown', primReps=None, evaluated=False, vector={})]
        for symbol in self.symbols:
            count = len(self.call(self.fixture(symbol))[2])
            for index in range(count):
                expected = self.call(self.fixture(symbol))[2][index][2]['rep']
                for bad in bad_proofs:
                    if bad.get('kind') == expected['kind'] and bad.get('primReps') == expected['primReps']: continue
                    for scope in ('formal', 'global'):
                        module = self.fixture(symbol); argument = self.call(module)[2][index]
                        if scope == 'formal': module['bindings'][0]['expr'][1][index]['rep'] = copy.deepcopy(bad)
                        else:
                            module['bindings'].append(dict(bind('stored', lit(7), False), rep=copy.deepcopy(bad)))
                            argument[1] = 'stored'
                        self.reject(module, 'stored operand')
                for reps in (None, expected['primReps']):
                    module = self.fixture(symbol)
                    module['bindings'][0]['expr'][1][index]['rep'] = dict(kind='unknown', primReps=reps, evaluated=False)
                    self.assertTrue(self.audit(module)['accepted'])
                # A local proof wins over a same-ID global, in both directions.
                module = self.fixture(symbol); argument = self.call(module)[2][index]
                module['bindings'].append(dict(bind(argument[1], lit(7), False), rep=LONG))
                self.assertTrue(self.audit(module)['accepted'])
                module['bindings'][-1]['rep'] = copy.deepcopy(expected)
                module['bindings'][0]['expr'][1][index]['rep'] = copy.deepcopy(REFERENCE)
                self.reject(module, 'stored operand')

    def test_intrinsic_and_composite_producers_cannot_forge_operand_carriers(self):
        for symbol in self.symbols:
            for index, original in enumerate(self.call(self.fixture(symbol))[2]):
                metadata = copy.deepcopy(original[2])
                wrong_literal = 'int8' if metadata['rep']['kind'] == 'long' else 'int'
                bad = [['lit', wrong_literal, '1', metadata],
                       ['lam', [], [*lit(0), dict(rep=LONG)], dict(metadata, resultRep=LONG)],
                       ['con', 'synthetic-box', 0, metadata]]
                for operand in bad:
                    for composite in ('direct', 'let', 'case'):
                        module = self.fixture(symbol)
                        wrapped = copy.deepcopy(operand)
                        if composite == 'let': wrapped = ['let', False, [], wrapped, metadata]
                        if composite == 'case':
                            wrapped = ['case', [*lit(0), dict(rep=LONG)], 'unused',
                                [['default', None, [], wrapped]], dict(metadata, binder=dict(id='unused', lifted=False, rep=LONG))]
                        self.call(module)[2][index] = wrapped
                        self.reject(module, 'lowered operand')
                kind = metadata['rep']['kind']
                if kind in ('address', 'void', 'long'):
                    module = self.fixture(symbol)
                    self.call(module)[2][index] = (['void', metadata] if kind == 'void' else
                        ['lit', 'null-addr' if kind == 'address' else 'word', '0', metadata])
                    self.assertTrue(self.audit(module)['accepted'])

    def test_aliases_and_remote_capture_stay_unsupported(self):
        for symbol in ('stg_decodeStackzh', 'stg_sendCloneStackMessagezh',
                       *(s + '2' for s in self.symbols), *('prefix' + s for s in self.symbols)):
            self.assertNotIn(symbol, core_original_foreign.OPERATIONS)
            self.assertNotIn(symbol, CAP['managedForeignCalls'])
            module = self.fixture('lookupIPE'); self.call(module)[6]['foreignCall']['target']['symbol'] = symbol
            self.reject(module)


class OriginalGmpAuditTest(unittest.TestCase):
    """Synthetic structural controls; genuine native/pre/post proof is Haskell/Kotlin.

    Recognition is tested with an explicit local capability override. This class
    never mutates the production capability inventory or claims native execution.
    The signatures below were checked against both genuine original-gmp stages.
    """
    array = 'BoxedRep (Just Unlifted)'
    signatures = {
        '__gmpn_add': ((array, array, 'IntRep', array, 'IntRep', None), 'WordRep'),
        '__gmpn_add_1': ((array, array, 'IntRep', 'WordRep', None), 'WordRep'),
        '__gmpn_cmp': ((array, array, 'IntRep', None), 'IntRep'),
        '__gmpn_divrem_1': ((array, 'IntRep', array, 'IntRep', 'WordRep', None), 'WordRep'),
        '__gmpn_mod_1': ((array, 'IntRep', 'WordRep', None), 'WordRep'),
        '__gmpn_mul': ((array, array, 'IntRep', array, 'IntRep', None), 'WordRep'),
        '__gmpn_mul_1': ((array, array, 'IntRep', 'WordRep', None), 'WordRep'),
        '__gmpn_sub': ((array, array, 'IntRep', array, 'IntRep', None), 'WordRep'),
        '__gmpn_tdiv_qr': ((array, array, 'IntRep', array, 'IntRep', array, 'IntRep', None), None),
        'integer_gmp_mpn_tdiv_q': ((array, array, 'IntRep', array, 'IntRep', None), None),
        'integer_gmp_mpn_tdiv_r': ((array, array, 'IntRep', array, 'IntRep', None), None),
    }

    def fixture(self, symbol):
        def scalar(primitive, evaluated):
            return dict(kind='void' if primitive is None else 'object' if primitive == self.array else 'long',
                        primReps=[] if primitive is None else [primitive], evaluated=evaluated)
        arguments, output = self.signatures[symbol]
        parameters = [dict(id=f'a{i}', lifted=False, rep=scalar(p, True)) for i, p in enumerate(arguments)]
        result = tuple_rep(*(scalar(p, True) for p in ((None,) if output is None else (None, output))))
        result['evaluated'] = False
        descriptor = dict(schema=1, target=dict(kind='static', symbol=symbol, unit='ghc-internal', isFunction=True),
                          convention='ccall', safety='unsafe', arity=len(arguments), suppliedArity=len(arguments),
                          argumentReps=[scalar(p, False) for p in arguments], resultRep=copy.deepcopy(result))
        call = ['app', ['var', 'original-foreign', dict(rep=CLOSURE)],
                [['var', p['id'], dict(rep=copy.deepcopy(p['rep']))] for p in parameters],
                [False] * len(arguments), False, False, dict(rep=result, foreignCall=descriptor)]
        case = ['case', call, 'result', [['default', None, [], [*lit(0), dict(rep=LONG)]]],
                dict(rep=LONG, binder=dict(id='result', lifted=False, rep=dict(result, evaluated=True)))]
        root = dict(bind('synthetic-consumer', ['lam', parameters, case, dict(rep=CLOSURE, resultRep=LONG)]),
                    rep=CLOSURE, arity=len(parameters))
        return copy.deepcopy(dict(schema=1, ghc='9.14.1', bindings=[root], constructors=[]))

    @staticmethod
    def call(module):
        return module['bindings'][0]['expr'][2][1]

    def audit(self, module, enabled=True):
        capabilities = [s for s in CAP['managedForeignCalls'] if s not in self.signatures]
        if enabled: capabilities.extend(self.signatures)
        return audit_core.Audit([('synthetic-original-gmp-control.json', module)],
            dict(CAP, managedForeignCalls=capabilities)).run(['synthetic-consumer'])

    def reject(self, module, detail=None):
        report = self.audit(module)
        self.assertFalse(report['accepted'], report)
        self.assertEqual([], report['foreignCalls'], report)
        if detail is not None:
            self.assertTrue(any(detail in str(i['detail']) for i in report['issues']), report)

    def test_exact_eleven_shapes_require_explicit_capability(self):
        self.assertEqual(set(self.signatures), core_original_foreign.GMP_SYMBOLS)
        for symbol in self.signatures:
            module = self.fixture(symbol)
            report = self.audit(module)
            self.assertTrue(report['accepted'], report)
            self.assertEqual([symbol], [c['symbol'] for c in report['foreignCalls']])
            self.assertEqual([], report['missingGlobals'])
            disabled = self.audit(module, enabled=False)
            self.assertFalse(disabled['accepted'])
            self.assertEqual([], disabled['foreignCalls'])
            self.assertTrue(any('capability disabled' in i['detail'] for i in disabled['issues']))
            self.call(module)[1][1] = 'another-package:InlinedCaller.foreign'
            self.assertTrue(self.audit(module)['accepted'])

    def test_descriptor_unit_calling_convention_safety_and_integer_fields_are_exact(self):
        for symbol in self.signatures:
            for key in ('schema', 'arity', 'suppliedArity'):
                for value in (None, True, False, 1.0, '1', -1, 0, 1 << 32):
                    module = self.fixture(symbol); self.call(module)[6]['foreignCall'][key] = value
                    self.reject(module)
            for key, values in {'convention': ('capi', 'prim', 'javascript', None),
                                'safety': ('safe', 'interruptible', None), 'extra': (None,)}.items():
                for value in values:
                    module = self.fixture(symbol); self.call(module)[6]['foreignCall'][key] = value
                    self.reject(module)
            for key, values in {'kind': ('dynamic', None), 'unit': ('main', 'ghc-bignum', '', None),
                                'isFunction': (False, 1, None), 'extra': (None,)}.items():
                for value in values:
                    module = self.fixture(symbol); self.call(module)[6]['foreignCall']['target'][key] = value
                    self.reject(module)

    def test_operand_widths_carriers_flags_and_declared_evaluation_are_exact(self):
        bad_proofs = [dict(LONG, kind='unknown'), REFERENCE, CLOSURE,
            dict(kind='address', primReps=['AddrRep'], evaluated=True),
            dict(kind='object', primReps=['BoxedRep Nothing'], evaluated=True), tuple_rep(),
            dict(kind='long', primReps=['Word64Rep'], evaluated=True)]
        for symbol in self.signatures:
            for index in range(len(self.signatures[symbol][0])):
                for bad in bad_proofs:
                    for declared in (False, True):
                        module = self.fixture(symbol); call = self.call(module)
                        if declared: call[6]['foreignCall']['argumentReps'][index] = copy.deepcopy(bad)
                        else: call[2][index][2]['rep'] = copy.deepcopy(bad)
                        self.reject(module)
                for key, value in (('evaluated', 1), ('extra', None), ('vector', {}), ('aggregate', 'unboxed-tuple')):
                    module = self.fixture(symbol); self.call(module)[2][index][2]['rep'][key] = value
                    self.reject(module)
                module = self.fixture(symbol)
                self.call(module)[6]['foreignCall']['argumentReps'][index]['evaluated'] = True
                self.reject(module)
                for value in (True, 0, 1, None):
                    module = self.fixture(symbol); self.call(module)[3][index] = value; self.reject(module)
            for field in ('arguments', 'declared', 'flags'):
                module = self.fixture(symbol); call = self.call(module)
                (call[2] if field == 'arguments' else call[3] if field == 'flags'
                 else call[6]['foreignCall']['argumentReps']).pop()
                self.reject(module)

    def test_all_three_result_proofs_preserve_state_and_singleton_tuple(self):
        for symbol, (_, output) in self.signatures.items():
            for site in ('declared', 'actual'):
                for key, value in (('kind', 'void'), ('aggregate', 'unboxed-sum'), ('evaluated', 0),
                                   ('primReps', ['AddrRep']), ('components', []), ('extra', None)):
                    module = self.fixture(symbol); meta = self.call(module)[6]
                    (meta['foreignCall']['resultRep'] if site == 'declared' else meta['rep'])[key] = value
                    self.reject(module)
                for index in range(1 if output is None else 2):
                    for key, value in (('kind', 'unknown'), ('evaluated', False), ('primReps', ['Int32Rep']), ('extra', None)):
                        module = self.fixture(symbol); meta = self.call(module)[6]
                        (meta['foreignCall']['resultRep'] if site == 'declared' else meta['rep'])['components'][index][key] = value
                        self.reject(module)
            module = self.fixture(symbol); call = self.call(module)
            with self.assertRaises(ValueError):
                core_original_foreign.validate(call[6], [a[2]['rep'] for a in call[2]], call[3], None)
            call[6]['foreignCall']['resultRep']['evaluated'] = True; self.reject(module)
            if output is None:
                module = self.fixture(symbol); meta = self.call(module)[6]
                meta['rep'] = copy.deepcopy(meta['rep']['components'][0]); self.reject(module)

    def test_foreign_head_must_be_exact_and_unbound(self):
        for symbol in self.signatures:
            for head in ([], ['var', None], ['var', ''], ['var', 3], ['prim', symbol], ['var', 'unproved']):
                module = self.fixture(symbol); self.call(module)[1] = head; self.reject(module)
            for key, value in (('kind', 'object'), ('primReps', [self.array]), ('evaluated', False),
                               ('evaluated', 1), ('extra', None)):
                module = self.fixture(symbol); self.call(module)[1][2]['rep'][key] = value; self.reject(module)
            for identifier in ('a0', 'synthetic-consumer'):
                module = self.fixture(symbol); self.call(module)[1][1] = identifier; self.reject(module)

    def test_stored_array_scalar_and_state_cannot_be_relabelled(self):
        bad_proofs = [LONG, REFERENCE, CLOSURE, dict(kind='void', primReps=[], evaluated=True),
            dict(kind='address', primReps=['AddrRep'], evaluated=True),
            dict(kind='unknown', primReps=['IntRep'], evaluated=True),
            dict(kind='unknown', primReps=['BoxedRep Nothing'], evaluated=True), tuple_rep(),
            dict(kind='unknown', primReps=None, evaluated=False, vector={})]
        for symbol, (arguments, _) in self.signatures.items():
            for index, primitive in enumerate(arguments):
                expected = self.call(self.fixture(symbol))[2][index][2]['rep']
                for bad in bad_proofs:
                    if bad.get('kind') in (expected['kind'], 'unknown') and bad.get('primReps') == expected['primReps']: continue
                    for scope in ('formal', 'global'):
                        module = self.fixture(symbol)
                        if scope == 'formal': module['bindings'][0]['expr'][1][index]['rep'] = copy.deepcopy(bad)
                        else:
                            module['bindings'].append(dict(bind('stored', lit(7), False), rep=copy.deepcopy(bad)))
                            self.call(module)[2][index][1] = 'stored'
                        self.reject(module, 'stored operand')
                for reps in (None, [] if primitive is None else [primitive]):
                    module = self.fixture(symbol)
                    module['bindings'][0]['expr'][1][index]['rep'] = dict(kind='unknown', primReps=reps, evaluated=False)
                    self.assertTrue(self.audit(module)['accepted'])

    def test_intrinsic_and_composite_values_cannot_forge_foreign_carriers(self):
        for symbol, (arguments, _) in self.signatures.items():
            for index, primitive in enumerate(arguments):
                metadata = self.call(self.fixture(symbol))[2][index][2]
                wrong = [['lit', 'int8', '1', metadata], ['con', 'synthetic-box', 0, metadata],
                    ['lam', [], [*lit(0), dict(rep=LONG)], dict(metadata, resultRep=LONG)]]
                for operand in wrong:
                    for composite in ('direct', 'let', 'case'):
                        module = self.fixture(symbol); wrapped = copy.deepcopy(operand)
                        if composite == 'let': wrapped = ['let', False, [], wrapped, metadata]
                        if composite == 'case': wrapped = ['case', [*lit(0), dict(rep=LONG)], 'unused',
                            [['default', None, [], wrapped]], dict(metadata, binder=dict(id='unused', lifted=False, rep=LONG))]
                        self.call(module)[2][index] = wrapped; self.reject(module, 'lowered operand')
                if primitive != self.array:
                    module = self.fixture(symbol)
                    self.call(module)[2][index] = ['void', metadata] if primitive is None else [
                        'lit', 'word' if primitive == 'WordRep' else 'int', '0', metadata]
                    self.assertTrue(self.audit(module)['accepted'])

    def test_alias_symbols_and_unimplemented_gmp_calls_stay_unknown(self):
        for symbol in ('mpn_add', '__gmpn_add2', 'prefix__gmpn_add', '__gmpn_sub_1',
                       '__gmpn_gcd', 'integer_gmp_mpn_tdiv_qr'):
            self.assertNotIn(symbol, core_original_foreign.OPERATIONS)
            module = self.fixture('__gmpn_add'); self.call(module)[6]['foreignCall']['target']['symbol'] = symbol
            self.reject(module)


class OriginalDupAuditTest(unittest.TestCase):
    """Original scalar/State controls; genuine declarations live in Haskell fixtures."""
    termios = {
        '__hscore_get_saved_termios': (('Int32Rep', None), 'AddrRep'),
        '__hscore_set_saved_termios': (('Int32Rep', 'AddrRep', None), None),
        '__hscore_lflag': (('AddrRep', None), 'Word32Rep'),
        '__hscore_poke_lflag': (('AddrRep', 'Word32Rep', None), None),
        '__hscore_ptr_c_cc': (('AddrRep', None), 'AddrRep'),
        '__hscore_sizeof_termios': ((None,), 'IntRep'),
        '__hscore_sizeof_sigset_t': ((None,), 'IntRep'),
        **{f'__hscore_{name}': ((None,), 'Int32Rep')
           for name in ('echo', 'icanon', 'vmin', 'vtime', 'tcsanow', 'sigttou', 'sig_block', 'sig_setmask')},
    }
    sigset = {
        'ghczuwrapperZC13ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCsigemptyset': (('AddrRep', None), 'Int32Rep'),
        'ghczuwrapperZC12ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCsigaddset': (('AddrRep', 'Int32Rep', None), 'Int32Rep'),
        'ghczuwrapperZC11ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCsigprocmask': (('Int32Rep', 'AddrRep', 'AddrRep', None), 'Int32Rep'),
    }
    symbols = (core_original_foreign.TCSETATTR_SYMBOL, core_original_foreign.TCGETATTR_SYMBOL, 'dup', 'dup2', '__hscore_fstat', '__hscore_open', 'lockFile', 'unlockFile', *termios, *sigset)
    def fixture(self, symbol):
        arguments = (('Int32Rep', 'Int32Rep', 'AddrRep', None) if symbol == core_original_foreign.TCSETATTR_SYMBOL else
                     ('Word64Rep', 'Word64Rep', 'Word64Rep', 'Int32Rep', None) if symbol == 'lockFile' else
                     ('Word64Rep', None) if symbol == 'unlockFile' else
                     ('Int32Rep', 'AddrRep', None) if symbol in ('__hscore_fstat', core_original_foreign.TCGETATTR_SYMBOL) else
                     ('AddrRep', 'Int32Rep', 'Word32Rep', None) if symbol == '__hscore_open' else
                     ('Int32Rep', None) if symbol == 'dup' else ('Int32Rep', 'Int32Rep', None))
        arguments, output = (self.termios | self.sigset).get(symbol, (arguments, 'Int32Rep'))
        scalar = lambda rep, evaluated: dict(kind='void' if rep is None else 'address' if rep == 'AddrRep' else 'long',
            primReps=[] if rep is None else [rep], evaluated=evaluated)
        parameters = [dict(id=f'a{i}', lifted=False, rep=scalar(p, True)) for i, p in enumerate(arguments)]
        result = tuple_rep(*(scalar(rep, True) for rep in ((None,) if output is None else (None, output))))
        result['evaluated'] = False
        descriptor = dict(schema=1, target=dict(kind='static', symbol=symbol, unit='ghc-internal', isFunction=True),
            convention='capi' if symbol in self.sigset or symbol in (core_original_foreign.TCGETATTR_SYMBOL, core_original_foreign.TCSETATTR_SYMBOL) else 'ccall', safety='unsafe', arity=len(arguments), suppliedArity=len(arguments),
            argumentReps=[scalar(p, False) for p in arguments], resultRep=copy.deepcopy(result))
        call = ['app', ['var', 'original-foreign', dict(rep=CLOSURE)],
            [['var', p['id'], dict(rep=copy.deepcopy(p['rep']))] for p in parameters],
            [False] * len(arguments), False, False, dict(rep=result, foreignCall=descriptor)]
        case = ['case', call, 'pair', [['default', None, [], [*lit(0), dict(rep=LONG)]]],
            dict(rep=LONG, binder=dict(id='pair', lifted=False, rep=dict(result, evaluated=True)))]
        return dict(schema=1, ghc='9.14.1', constructors=[], bindings=[dict(bind('root',
            ['lam', parameters, case, dict(rep=CLOSURE, resultRep=LONG)]), rep=CLOSURE, arity=len(parameters))])

    def call(self, module):
        return module['bindings'][0]['expr'][2][1]

    def audit(self, module, cap=CAP):
        return audit_core.Audit([('dup-control.json', module)], cap).run(['root'])

    def test_exact_original_duplication_requires_production_capability(self):
        for symbol in self.symbols:
            self.assertEqual(1, CAP['managedForeignCalls'].count(symbol))
            report = self.audit(self.fixture(symbol)); self.assertTrue(report['accepted'], report)
            self.assertEqual([symbol], [c['symbol'] for c in report['foreignCalls']])
            self.assertFalse(self.audit(self.fixture(symbol), dict(CAP, managedForeignCalls=[]))['accepted'])
        for alias in ('dup3', '_dup', 'prefixdup', '__hscore_dup', 'prefixunlockFile', 'prefixlockFile', 'prefix__hscore_fstat', 'fstat', 'open', '__hscore_open64', 'prefix__hscore_open'):
            self.assertNotIn(alias, core_original_foreign.OPERATIONS)
            module = self.fixture('__hscore_fstat')
            self.call(module)[6]['foreignCall']['target']['symbol'] = alias
            self.assertFalse(self.audit(module)['accepted'])

    def test_original_open_three_exact_safety_contracts(self):
        for safety in ('unsafe', 'safe', 'interruptible'):
            module = self.fixture('__hscore_open')
            self.call(module)[6]['foreignCall']['safety'] = safety
            self.assertTrue(self.audit(module)['accepted'])
            self.assertFalse(self.audit(module, dict(CAP, managedForeignCalls=[]))['accepted'])

    def test_descriptor_flags_head_and_raw_representation_forgery_reject(self):
        for symbol in self.symbols:
            mutations = [(key, value) for key in ('schema', 'arity', 'suppliedArity')
                for value in (None, True, 2.0, '2', 0, 1 << 32)] + [('convention', 'ccall' if symbol in self.sigset or symbol in (core_original_foreign.TCGETATTR_SYMBOL, core_original_foreign.TCSETATTR_SYMBOL) else 'capi'), ('safety', 'unknown'), ('extra', None)] + ([] if symbol == '__hscore_open' else [('safety', 'safe'), ('safety', 'interruptible')])
            for key, value in mutations:
                module = self.fixture(symbol); self.call(module)[6]['foreignCall'][key] = value
                self.assertFalse(self.audit(module)['accepted'], (symbol, key, value))
            for key, value in (('unit', 'main'), ('kind', 'dynamic'), ('isFunction', False), ('extra', None)):
                module = self.fixture(symbol); self.call(module)[6]['foreignCall']['target'][key] = value
                self.assertFalse(self.audit(module)['accepted'])
            for head in ([], ['var', None], ['prim', symbol], ['var', 'a0', dict(rep=CLOSURE)], ['var', 'root', dict(rep=CLOSURE)]):
                module = self.fixture(symbol); self.call(module)[1] = head
                self.assertFalse(self.audit(module)['accepted'])
            for i in range(len(self.call(self.fixture(symbol))[2])):
                for flag in (True, 0, None):
                    module = self.fixture(symbol); self.call(module)[3][i] = flag
                    self.assertFalse(self.audit(module)['accepted'])
                for stored in (False, True):
                    for rep in ('IntRep', 'WordRep', 'Word32Rep', 'AddrRep'):
                        module = self.fixture(symbol)
                        proof = module['bindings'][0]['expr'][1][i]['rep'] if stored else self.call(module)[2][i][2]['rep']
                        if proof['primReps'] == [rep]: continue
                        proof['primReps'] = [rep]
                        report = self.audit(module)
                        self.assertFalse(report['accepted'])
                        self.assertEqual([], report['foreignCalls'])
                module = self.fixture(symbol); self.call(module)[2][i][2]['rep']['aggregate'] = 'unboxed-tuple'
                self.assertFalse(self.audit(module)['accepted'])
            for declared in (False, True):
                module = self.fixture(symbol); meta = self.call(module)[6]
                result = meta['foreignCall']['resultRep'] if declared else meta['rep']
                result['components'][0]['primReps'] = ['IntRep']
                self.assertFalse(self.audit(module)['accepted'])

    def test_termios_state_only_result_and_excluded_terminal_calls(self):
        self.assertEqual(set(self.termios), core_original_foreign.TERMIOS_SYMBOLS)
        self.assertEqual(set(self.sigset), set(core_original_foreign.SIGSET_OPERATIONS) | {
            "ghczuwrapperZC11ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCsigprocmask"})
        for symbol, (_, output) in (self.termios | self.sigset).items():
            if output is not None:
                # A mutually consistent descriptor/call-site forgery still
                # cannot change the original declaration's result width.
                for wrong in ('IntRep', 'Int32Rep', 'Word32Rep'):
                    if wrong == output: continue
                    module = self.fixture(symbol); meta = self.call(module)[6]
                    for result in (meta['rep'], meta['foreignCall']['resultRep']):
                        result['primReps'] = [wrong]
                        result['components'][1]['kind'] = 'long'
                        result['components'][1]['primReps'] = [wrong]
                    self.assertFalse(self.audit(module)['accepted'], (symbol, wrong))
            for declared in (False, True):
                for mutation in ('bare', 'empty', 'extra', 'sum'):
                    module = self.fixture(symbol); meta = self.call(module)[6]
                    owner, key = (meta['foreignCall'], 'resultRep') if declared else (meta, 'rep')
                    result = owner[key]
                    if mutation == 'bare': owner[key] = result['components'][0]
                    if mutation == 'empty': result['components'] = []
                    if mutation == 'extra': result['components'].append(copy.deepcopy(result['components'][0]))
                    if mutation == 'sum': result['aggregate'] = 'unboxed-sum'
                    self.assertFalse(self.audit(module)['accepted'])
        for symbol in ('prefix__hscore_lflag', 'tcgetattr', 'tcsetattr', 'sigprocmask', 'sigemptyset', 'sigaddset',
                       'prefix__hscore_sigttou', 'prefix__hscore_sizeof_sigset_t', 'prefix__hscore_get_saved_termios', 'prefix__hscore_set_saved_termios',
                       *('prefix' + name for name in self.sigset)):
            self.assertNotIn(symbol, core_original_foreign.OPERATIONS)
            module = self.fixture('__hscore_lflag')
            self.call(module)[6]['foreignCall']['target']['symbol'] = symbol
            self.assertFalse(self.audit(module)['accepted'])


class OriginalRtsDiagnosticTest(unittest.TestCase):
    def test_exact_diagnostic_descriptors_and_malformed_contracts(self):
        state = dict(kind='void', primReps=[], evaluated=True)
        address = dict(kind='address', primReps=['AddrRep'], evaluated=True)
        thread = dict(kind='object', primReps=['BoxedRep (Just Unlifted)'], evaluated=True)
        for symbol, reps in (('reportStackOverflow', [thread, state]),
                             ('reportHeapOverflow', [state]), ('errorBelch2', [address, address, state])):
            result = tuple_rep(state)
            descriptor = dict(schema=1, target=dict(kind='static', symbol=symbol, unit='ghc-internal', isFunction=True),
                convention='ccall', safety='unsafe', arity=len(reps), suppliedArity=len(reps),
                argumentReps=[dict(rep, evaluated=False) for rep in reps], resultRep=dict(result, evaluated=False))
            formals = [dict(id=f'p{i}', lifted=False, rep=rep) for i, rep in enumerate(reps)]
            call = ['app', ['var', 'foreign', dict(rep=CLOSURE)],
                [['var', f'p{i}', dict(rep=rep)] for i, rep in enumerate(reps)], [False]*len(reps),
                False, False, dict(rep=result, foreignCall=descriptor)]
            body = ['case', call, 'done', [['default', None, [], [*lit(0), dict(rep=LONG)]]],
                dict(rep=LONG, binder=dict(id='done', lifted=False, rep=result))]
            module = dict(schema=1, ghc='9.14.1', constructors=[], bindings=[
                dict(bind('root', ['lam', formals, body, dict(rep=CLOSURE, resultRep=LONG)]), rep=CLOSURE, arity=len(reps))])
            audit = lambda value: audit_core.Audit([('diagnostics.json', value)], CAP).run(['root'])
            report = audit(module)
            self.assertTrue(report['accepted'], report)
            self.assertEqual([symbol], [item['symbol'] for item in report['foreignCalls']])
            for key, value in (('safety', 'safe'), ('arity', 0), ('resultRep', state)):
                malformed = copy.deepcopy(module)
                malformed['bindings'][0]['expr'][2][1][6]['foreignCall'][key] = value
                self.assertFalse(audit(malformed)['accepted'])


class OriginalShutdownAuditTest(unittest.TestCase):
    def test_safe_shutdown_requires_exact_cint_and_state_contract(self):
        state = dict(kind='void', primReps=[], evaluated=True)
        cint = dict(kind='long', primReps=['Int32Rep'], evaluated=True)
        reps = [cint, cint, state]
        result = tuple_rep(state)
        for symbol in ('shutdownHaskellAndExit', 'shutdownHaskellAndSignal'):
            descriptor = dict(schema=1, target=dict(kind='static', symbol=symbol, unit='ghc-internal', isFunction=True),
                convention='ccall', safety='safe', arity=3, suppliedArity=3,
                argumentReps=[dict(rep, evaluated=False) for rep in reps], resultRep=dict(result, evaluated=False))
            formals = [dict(id=f'p{i}', lifted=False, rep=rep) for i, rep in enumerate(reps)]
            call = ['app', ['var', 'foreign', dict(rep=CLOSURE)],
                [['var', f'p{i}', dict(rep=rep)] for i, rep in enumerate(reps)], [False]*3,
                False, False, dict(rep=result, foreignCall=descriptor)]
            body = ['case', call, 'done', [['default', None, [], [*lit(0), dict(rep=LONG)]]],
                dict(rep=LONG, binder=dict(id='done', lifted=False, rep=result))]
            module = dict(schema=1, ghc='9.14.1', constructors=[], bindings=[
                dict(bind('root', ['lam', formals, body, dict(rep=CLOSURE, resultRep=LONG)]), rep=CLOSURE, arity=3)])
            audit = lambda value: audit_core.Audit([('shutdown.json', value)], CAP).run(['root'])
            report = audit(module)
            self.assertTrue(report['accepted'], report)
            self.assertEqual([symbol], [item['symbol'] for item in report['foreignCalls']])
            for key, value in (('safety', 'unsafe'), ('arity', 0), ('resultRep', state)):
                malformed = copy.deepcopy(module)
                malformed['bindings'][0]['expr'][2][1][6]['foreignCall'][key] = value
                self.assertFalse(audit(malformed)['accepted'])


class OriginalBoundThreadSupportTest(unittest.TestCase):
    """Negative bound capability; never current-thread state or forkOS support."""

    @staticmethod
    def fixture():
        state = dict(kind='void', primReps=[], evaluated=True)
        result = tuple_rep(state, LONG); result['evaluated'] = False
        descriptor = dict(schema=1, target=dict(kind='static', symbol='rtsSupportsBoundThreads',
            unit='ghc-internal', isFunction=True), convention='ccall', safety='unsafe',
            arity=1, suppliedArity=1, argumentReps=[dict(state, evaluated=False)], resultRep=copy.deepcopy(result))
        call = ['app', ['var', 'structural-fcall', dict(rep=CLOSURE)],
                [['var', 'state', dict(rep=state)]], [False], False, False, dict(rep=result, foreignCall=descriptor)]
        case = ['case', call, 'done', [['default', None, [], [*lit(0), dict(rep=LONG)]]],
                dict(rep=LONG, binder=dict(id='done', lifted=False, rep=dict(result, evaluated=True)))]
        binding = dict(bind('root', ['lam', [dict(id='state', lifted=False, rep=state)], case,
                        dict(rep=CLOSURE, resultRep=LONG)]), rep=CLOSURE, arity=1)
        return dict(schema=1, ghc='9.14.1', bindings=[binding], constructors=[])

    @staticmethod
    def audit(module, capabilities=CAP):
        return audit_core.Audit([('bound-thread-query.json', module)], capabilities).run(['root'])

    def test_exact_negative_capability_query_and_malformed_proofs(self):
        self.assertEqual(1, CAP['managedForeignCalls'].count('rtsSupportsBoundThreads'))
        report = self.audit(self.fixture())
        self.assertTrue(report['accepted'], report)
        self.assertEqual(['rtsSupportsBoundThreads'], [call['symbol'] for call in report['foreignCalls']])
        self.assertFalse(self.audit(self.fixture(), dict(CAP, managedForeignCalls=[]))['accepted'])
        for mutation in ('schema', 'unit', 'dynamic', 'data', 'convention', 'safety', 'arity', 'saturation',
                         'state', 'stored-state', 'flags', 'result-width', 'missing-state', 'defined-head', 'intrinsic-state'):
            module = self.fixture()
            call = module['bindings'][0]['expr'][2][1]
            descriptor = call[6]['foreignCall']
            if mutation == 'schema': descriptor['schema'] = True
            if mutation == 'unit': descriptor['target']['unit'] = 'base'
            if mutation == 'dynamic': descriptor['target']['kind'] = 'dynamic'
            if mutation == 'data': descriptor['target']['isFunction'] = False
            if mutation == 'convention': descriptor['convention'] = 'capi'
            if mutation == 'safety': descriptor['safety'] = 'safe'
            if mutation == 'arity': descriptor['arity'] = 0
            if mutation == 'saturation': descriptor['suppliedArity'] = 0
            if mutation == 'state': call[2][0][2]['rep'] = LONG
            if mutation == 'stored-state': module['bindings'][0]['expr'][1][0]['rep'] = LONG
            if mutation == 'flags': call[3] = [True]
            if mutation == 'result-width': descriptor['resultRep']['components'][1]['primReps'] = ['Int32Rep']
            if mutation == 'missing-state': descriptor['resultRep']['components'].pop(0)
            if mutation == 'defined-head': module['bindings'].append(dict(bind('structural-fcall', lit(7)), rep=LONG))
            if mutation == 'intrinsic-state': call[2][0] = [*lit(7), dict(rep=dict(kind='void', primReps=[], evaluated=True))]
            self.assertFalse(self.audit(module)['accepted'], mutation)
        for symbol in ('forkOS', 'isCurrentThreadBound', 'prefix_rtsSupportsBoundThreads'):
            module = self.fixture()
            module['bindings'][0]['expr'][2][1][6]['foreignCall']['target']['symbol'] = symbol
            self.assertFalse(self.audit(module)['accepted'], symbol)


class OriginalMainThreadRegistrationTest(unittest.TestCase):
    """The catalog admits only TopHandler's Weak# key call, not signal setup."""

    @staticmethod
    def fixture():
        weak = dict(kind='object', primReps=['BoxedRep (Just Unlifted)'], evaluated=True)
        state = dict(kind='void', primReps=[], evaluated=True)
        arguments = [dict(id='weak', lifted=False, rep=weak), dict(id='state', lifted=False, rep=state)]
        result = tuple_rep(state); result['evaluated'] = False
        descriptor = dict(schema=1, target=dict(kind='static', symbol='rts_setMainThread',
            unit='ghc-internal', isFunction=True), convention='ccall', safety='unsafe',
            arity=2, suppliedArity=2, argumentReps=[dict(weak, evaluated=False), dict(state, evaluated=False)],
            resultRep=copy.deepcopy(result))
        call = ['app', ['var', 'original-fcall', dict(rep=CLOSURE)],
                [['var', p['id'], dict(rep=p['rep'])] for p in arguments], [False, False],
                False, False, dict(rep=result, foreignCall=descriptor)]
        case = ['case', call, 'done', [['default', None, [], [*lit(0), dict(rep=LONG)]]],
                dict(rep=LONG, binder=dict(id='done', lifted=False, rep=dict(result, evaluated=True)))]
        binding = dict(bind('root', ['lam', arguments, case, dict(rep=CLOSURE, resultRep=LONG)]),
                       rep=CLOSURE, arity=2)
        return dict(schema=1, ghc='9.14.1', bindings=[binding], constructors=[])

    @staticmethod
    def audit(module, capabilities=CAP):
        return audit_core.Audit([('main-thread-call.json', module)], capabilities).run(['root'])

    def test_exact_weak_key_call_and_capability_boundary(self):
        module = self.fixture()
        self.assertEqual(1, CAP['managedForeignCalls'].count('rts_setMainThread'))
        result = self.audit(module)
        self.assertTrue(result['accepted'], result)
        self.assertEqual(['rts_setMainThread'], [call['symbol'] for call in result['foreignCalls']])
        disabled = self.audit(module, dict(CAP, managedForeignCalls=[]))
        self.assertFalse(disabled['accepted'])
        self.assertEqual([], disabled['foreignCalls'])
        for wrong in ('stg_sig_install', 'prefix_rts_setMainThread'):
            altered = self.fixture()
            altered['bindings'][0]['expr'][2][1][6]['foreignCall']['target']['symbol'] = wrong
            self.assertFalse(self.audit(altered)['accepted'])
        for mutation in ('wrong-key', 'wrong-state', 'wrong-unit', 'wrong-safety'):
            altered = self.fixture()
            call = altered['bindings'][0]['expr'][2][1]
            if mutation == 'wrong-key': call[2][0][2]['rep']['primReps'] = ['BoxedRep (Just Lifted)']
            if mutation == 'wrong-state': call[2][1][2]['rep']['primReps'] = ['IntRep']
            if mutation == 'wrong-unit': call[6]['foreignCall']['target']['unit'] = 'base'
            if mutation == 'wrong-safety': call[6]['foreignCall']['safety'] = 'safe'
            self.assertFalse(self.audit(altered)['accepted'], mutation)


class ExplicitWeakContractTest(unittest.TestCase):
    """Structural rejection controls; native semantics come from WeakAudit.hs."""
    def fixture(self, name):
        state = dict(kind='void', primReps=[], evaluated=True)
        weak = dict(kind='object', primReps=['BoxedRep (Just Unlifted)'], evaluated=True)
        roles = dict(state=state, weak=weak, boxed=REFERENCE, action=CLOSURE, flag=LONG,
                     address=dict(kind='address', primReps=['AddrRep'], evaluated=True))
        contract = CAP['managedWeakPrimitives'][name]
        parameters = [dict(id=f'a{i}', lifted=roles[role]['primReps'] == ['BoxedRep (Just Lifted)'],
                           rep=copy.deepcopy(roles[role])) for i, role in enumerate(contract['arguments'])]
        result = tuple_rep(*(copy.deepcopy(roles[role]) for role in contract['result']))
        call = ['app', ['prim', name], [[*var(p['id']), dict(rep=copy.deepcopy(p['rep']))] for p in parameters],
                [p['lifted'] for p in parameters], False, False, dict(rep=result)]
        body = ['case', call, 'result', [['default', None, [], [*lit(0), dict(rep=LONG)]]],
                dict(rep=LONG, binder=dict(id='result', lifted=False, rep=copy.deepcopy(result)))]
        root = dict(bind('root', ['lam', parameters, body, dict(rep=CLOSURE, resultRep=LONG)]),
                    arity=len(parameters), rep=CLOSURE)
        return dict(schema=1, ghc='9.14.1', bindings=[root], constructors=[])

    def call(self, module):
        return module['bindings'][0]['expr'][2][1]

    def test_five_exact_contracts_admit_only_the_typed_c_finalizer(self):
        self.assertEqual({'mkWeak#', 'mkWeakNoFinalizer#', 'deRefWeak#', 'finalizeWeak#', 'addCFinalizerToWeak#'},
                         set(CAP['managedWeakPrimitives']))
        for name in CAP['managedWeakPrimitives']:
            module = self.fixture(name)
            report = run_tuple(module)
            self.assertTrue(report['accepted'], report['issues'])
            self.call(module)[1][1] = 'notAWeakPrimitive#'
            self.assertFalse(run_tuple(module)['accepted'])
        self.assertEqual(6, CAP['primitives']['addCFinalizerToWeak#'])

    def test_flags_arity_state_logical_tuple_and_lexical_proofs_cannot_be_forged(self):
        for name in CAP['managedWeakPrimitives']:
            for mutation in range(5):
                module = self.fixture(name)
                call = self.call(module)
                if mutation == 0:
                    call[3][0] = not call[3][0]
                elif mutation == 1:
                    call[2].pop(); call[3].pop()
                elif mutation == 2:
                    call[2][-1][2]['rep'] = copy.deepcopy(LONG)
                elif mutation == 3:
                    call[6]['rep']['components'].pop(0)  # Identical physical reps, missing logical State.
                else:
                    module['bindings'][0]['expr'][1][0]['rep'] = dict(kind='long', primReps=['WordRep'])
                report = run_tuple(module)
                self.assertFalse(report['accepted'], (name, mutation))
                self.assertIn('primitive-representation', {issue['code'] for issue in report['issues']})

    def test_only_source_certified_function_labels_are_admitted(self):
        for symbol in ('libdwPoolRelease', 'backtraceFree', 'free', 'enabled_capabilities', 'notACallback'):
            module = self.fixture('addCFinalizerToWeak#')
            self.call(module)[2][0] = ['lit', 'function-addr', symbol,
                dict(rep=dict(kind='address', primReps=['AddrRep'], evaluated=True))]
            report = run_tuple(module)
            self.assertEqual(symbol in ('libdwPoolRelease', 'backtraceFree', 'free'), report['accepted'], (symbol, report))
        module = self.fixture('addCFinalizerToWeak#')
        self.call(module)[2][0] = ['lit', 'data-addr', 'enabled_capabilities',
            dict(rep=dict(kind='address', primReps=['AddrRep'], evaluated=True))]
        self.assertFalse(run_tuple(module)['accepted'])
        for mutation in ('missing', 'kind', 'rep', 'aggregate'):
            module = self.fixture('addCFinalizerToWeak#')
            label = ['lit', 'function-addr', 'libdwPoolRelease',
                dict(rep=dict(kind='address', primReps=['AddrRep'], evaluated=True))]
            self.call(module)[2][0] = label
            if mutation == 'missing': label.pop()
            elif mutation == 'kind': label[3]['rep']['kind'] = 'long'
            elif mutation == 'rep': label[3]['rep']['primReps'] = ['IntRep']
            else: label[3]['rep']['aggregate'] = 'unboxed-tuple'
            report = run_tuple(module)
            self.assertFalse(report['accepted'], (mutation, report))
            self.assertIn('scalar-representation', {issue['code'] for issue in report['issues']})


class RTSDataLabelTest(unittest.TestCase):
    def test_only_enabled_capabilities_with_evaluated_address_proof_is_admitted(self):
        label = ['lit', 'data-addr', 'enabled_capabilities',
                 dict(rep=dict(kind='address', primReps=['AddrRep'], evaluated=True))]
        self.assertTrue(run(label)['accepted'])
        self.assertFalse(run(label, cap=dict(CAP, dataLabels=[]))['accepted'])
        for symbol in ('other', 'enabled_capabilities_extra', 'n_capabilities'):
            wrong = copy.deepcopy(label); wrong[2] = symbol
            self.assertFalse(run(wrong)['accepted'])
        for mutation in ('missing', 'kind', 'width', 'evaluated', 'aggregate'):
            wrong = copy.deepcopy(label)
            if mutation == 'missing': wrong.pop()
            elif mutation == 'kind': wrong[3]['rep']['kind'] = 'long'
            elif mutation == 'width': wrong[3]['rep']['primReps'] = ['WordRep']
            elif mutation == 'evaluated': wrong[3]['rep']['evaluated'] = False
            else: wrong[3]['rep']['aggregate'] = 'unboxed-tuple'
            report = run(wrong)
            self.assertFalse(report['accepted'], (mutation, report))
            self.assertIn('scalar-representation', {issue['code'] for issue in report['issues']})


class DescriptorWaitAuditTest(unittest.TestCase):
    """The RTS bad-FD CAF is an implicit original dependency of both waits."""

    @staticmethod
    def fixture(name, include_payload=True):
        state = dict(kind='void', primReps=[], evaluated=True)
        payload_id = 'ghc-internal:GHC.Internal.Event.Thread.blockedOnBadFD'
        params = [dict(id='fd', lifted=False, rep=LONG), dict(id='s', lifted=False, rep=state)]
        call = ['app', ['prim', name],
                [['var', 'fd', dict(rep=LONG)], ['var', 's', dict(rep=state)]],
                [False, False], False, False, dict(rep=state)]
        root = dict(bind('root', ['lam', params, call, dict(rep=CLOSURE, resultRep=state)]),
                    rep=CLOSURE, arity=2)
        payload = dict(bind(payload_id, ['var', payload_id, dict(rep=REFERENCE)]), rep=REFERENCE)
        return dict(schema=1, ghc='9.14.1', bindings=[root] + ([payload] if include_payload else []),
                    constructors=[])

    def test_original_payload_and_exact_state_contract(self):
        for name in ('waitRead#', 'waitWrite#'):
            report = audit_core.Audit([('wait.json', self.fixture(name))], CAP).run(['root'])
            self.assertTrue(report['accepted'], report['issues'])
            self.assertIn('ghc-internal:GHC.Internal.Event.Thread.blockedOnBadFD',
                          [binding['id'] for binding in report['reachableBindings']])
            missing = audit_core.Audit([('wait.json', self.fixture(name, False))], CAP).run(['root'])
            self.assertFalse(missing['accepted'])
            self.assertIn('ghc-internal:GHC.Internal.Event.Thread.blockedOnBadFD',
                          [binding['id'] for binding in missing['missingGlobals']])
            for mutation in ('descriptor', 'state', 'result', 'flags'):
                module = self.fixture(name)
                call = module['bindings'][0]['expr'][2]
                if mutation == 'descriptor': call[2][0][2]['rep'] = dict(LONG, primReps=['WordRep'])
                if mutation == 'state': call[2][1][2]['rep'] = LONG
                if mutation == 'result': call[6]['rep'] = LONG
                if mutation == 'flags': call[3] = [False, True]
                bad = audit_core.Audit([('wait.json', module)], CAP).run(['root'])
                self.assertFalse(bad['accepted'], (name, mutation, bad))


class STMContractTest(unittest.TestCase):
    """Proof mutations only; native values come from the Haskell STM producer."""
    @staticmethod
    def fixture(name, include_payload=True):
        state = dict(kind='void', primReps=[], evaluated=True)
        roles = dict(state=state, action=CLOSURE, boxed=REFERENCE,
                     tvar=dict(kind='object', primReps=['BoxedRep (Just Unlifted)'], evaluated=True))
        contract = CAP['managedSTMPrimitives'][name]
        proofs = [copy.deepcopy(roles[role]) for role in contract['arguments']]
        params = [dict(id='p' + str(i), lifted=proof['primReps'] == ['BoxedRep (Just Lifted)'], rep=proof)
                  for i, proof in enumerate(proofs)]
        result = contract['result']
        if isinstance(result, list):
            parts = [copy.deepcopy(roles[role]) for role in result]
            result = dict(kind='unknown', aggregate='unboxed-tuple', evaluated=True,
                          components=parts, primReps=[rep for part in parts for rep in part['primReps']])
        else:
            result = copy.deepcopy(roles[result])
        call = ['app', ['prim', name],
                [['var', param['id'], dict(rep=proof)] for param, proof in zip(params, proofs)],
                [param['lifted'] for param in params], False, False, dict(rep=result)]
        body = ['case', call, 'result', [['default', None, [], [*lit(0), dict(rep=LONG)]]],
                dict(rep=LONG, binder=dict(id='result', lifted=False, rep=dict(result, evaluated=True)))]
        root = dict(bind('root', ['lam', params, body, dict(rep=CLOSURE, resultRep=LONG)]),
                    rep=CLOSURE, arity=len(params))
        payload_id = 'ghc-internal:GHC.Internal.Control.Exception.Base.nestedAtomically'
        payload = dict(bind(payload_id, ['var', payload_id, dict(rep=REFERENCE)]), rep=REFERENCE)
        return dict(schema=1, ghc='9.14.1', bindings=[root] +
                    ([payload] if name == 'atomically#' and include_payload else []), constructors=[])

    def test_all_eight_exact_contracts_reject_shape_state_and_liftedness_mutations(self):
        self.assertEqual({'atomically#', 'retry#', 'catchRetry#', 'catchSTM#',
                          'newTVar#', 'readTVar#', 'readTVarIO#', 'writeTVar#'},
                         set(CAP['managedSTMPrimitives']))
        for name in CAP['managedSTMPrimitives']:
            original = self.fixture(name)
            report = audit_core.Audit([('stm.json', original)], CAP).run(['root'])
            self.assertTrue(report['accepted'], (name, report['issues']))
            for mutation in ('state', 'result', 'flags', 'arity'):
                changed = copy.deepcopy(original)
                call = changed['bindings'][0]['expr'][2][1]
                if mutation == 'state': call[2][-1][2]['rep'] = LONG
                if mutation == 'result': call[6]['rep'] = LONG
                if mutation == 'flags': call[3] = [not flag for flag in call[3]]
                if mutation == 'arity': call[2].pop()
                bad = audit_core.Audit([('stm.json', changed)], CAP).run(['root'])
                self.assertFalse(bad['accepted'], (name, mutation, bad))

    def test_nested_exception_is_a_real_implicit_dependency_and_same_tvar_is_not_invented(self):
        report = audit_core.Audit([('stm.json', self.fixture('atomically#', False))], CAP).run(['root'])
        self.assertFalse(report['accepted'])
        self.assertIn('ghc-internal:GHC.Internal.Control.Exception.Base.nestedAtomically',
                      [binding['id'] for binding in report['missingGlobals']])
        self.assertNotIn('sameTVar#', CAP['primitives'])


if __name__ == '__main__':
    unittest.main()
