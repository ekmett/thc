#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Regression tests for lexical dependency closure and capability diagnostics."""
import importlib.util
import copy
import hashlib
import io
import json
import re
import tempfile
from pathlib import Path
import unittest
import core_original_foreign
import core_package_manifest
from collections import deque
from contextlib import closing
import gc
import os
import sqlite3
import stat
import subprocess
import sys
from tempfile import TemporaryDirectory
from unittest.mock import Mock, patch
import weakref

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('audit_core', ROOT / 'audit-core.py')
audit_core = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit_core)
CAP = json.loads((ROOT / 'core-capabilities.json').read_text())
model_spec = importlib.util.spec_from_file_location('audit_cbd_models', ROOT / 'test-core-package-manifest.py')
model_support = importlib.util.module_from_spec(model_spec)
model_spec.loader.exec_module(model_support)
write_core = model_support.write_core


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


class DeepCoreTest(unittest.TestCase):
    def test_deep_storage_round_trip_keeps_checksum(self):
        value = var('leaf')
        for _ in range(1400):
            value = ['lam', [], value]
        data, digest = audit_core._pack(value)
        restored = audit_core._unpack(data, digest)
        self.assertTrue(core_package_manifest._same_json_value(value, restored))
        with self.assertRaises(audit_core.AuditStoreError):
            audit_core._unpack(data + b' ', digest)

    def test_deep_walk_keeps_scope_and_dependency_order(self):
        expression = var('leaf')
        for index in range(1200):
            expression = ['case', var('scrutinee' + str(index)), 'bound' + str(index),
                          [['default', None, [], expression]]]
        auditor = audit_core.Audit([], CAP)
        auditor.walk(expression, {}, 'root', '/expr')
        self.assertEqual(['scrutinee' + str(index) for index in reversed(range(1200))] + ['leaf'],
                         [edge['dependency'] for edge in auditor.edges])
        self.assertEqual(set(edge['dependency'] for edge in auditor.edges), auditor.free_variables(expression))
        scoped = ['let', False, [bind('local', var('local'))],
                  ['lam', [dict(id='argument')], ['case', var('argument'), 'case',
                    [['default', None, ['pattern'], ['app', var('local'),
                      [var('case'), var('pattern'), var('outside')]]]]]]]
        self.assertEqual({'local', 'outside'}, auditor.free_variables(scoped))
        scoped[1] = True
        self.assertEqual({'outside'}, auditor.free_variables(scoped))


class CommandLineEncodingTest(unittest.TestCase):
    def test_inspection_stdin_preserves_utf8_in_both_audit_modes(self):
        entry = 'root\u201d'
        with tempfile.TemporaryDirectory() as directory:
            source = write_core(Path(directory) / 'input.cbd', dict(schema=1, ghc='9.14.1',
                bindings=[bind(entry, [*lit(42), dict(rep=LONG)])], constructors=[]))
            for mode in ([], ['--eager']):
                with self.subTest(mode=mode):
                    report = Path(directory) / 'audit.json'
                    result = subprocess.run([sys.executable, str(ROOT / 'audit-core.py'),
                        *mode, '--entry', entry, '--output', str(report), '-'],
                        input=source, capture_output=True, timeout=30)
                    self.assertEqual(0, result.returncode, result.stderr.decode('utf-8'))
                    result = json.loads(report.read_bytes())
                    self.assertTrue(result['accepted'])
                    self.assertEqual([entry], result['roots'])

    def test_utf8_core_and_module_paths_do_not_use_windows_ansi_encoding(self):
        # U+201D contains a UTF-8 byte undefined in cp1252. This is the real
        # failure reached when hydrating unchanged GHC sources on Windows.
        read_text = Path.read_text
        def ansi_default(path, encoding=None, errors=None, **kwargs):
            return read_text(path, encoding=encoding or 'cp1252', errors=errors, **kwargs)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            entry = 'root\u201d'
            source = root / 'core\u201d.cbd'
            write_core(source, dict(schema=1, ghc='9.14.1',
                bindings=[bind(entry, [*lit(42), dict(rep=LONG)])], constructors=[]))
            manifest = root / 'modules.txt'
            manifest.write_bytes((source.name + '\n').encode('utf-8'))
            report = root / 'audit.json'
            with patch.object(Path, 'read_text', ansi_default), patch('sys.argv',
                    ['audit-core.py', '--module-list', str(manifest), '--entry', entry, '--output', str(report)]), \
                    patch('sys.stderr', io.StringIO()):
                self.assertEqual(0, audit_core.main())
            self.assertEqual([entry], json.loads(report.read_bytes())['roots'])


class ProvidedModuleTest(unittest.TestCase):
    def test_exact_complete_provider_is_required_by_interface_fragment(self):
        fragment = dict(schema=1, ghc='9.14.1', unit='dependency-closure', module='THC.InterfaceClosure',
                        boundary='actual-interface-unfoldings', providedModules=['pkg:Library'],
                        bindings=[bind('root', lit(42))], constructors=[])
        provider = dict(schema=1, ghc='9.14.1', unit='pkg', module='Library',
                        boundary='optimized-Core-after-Tidy-before-CorePrep', bindings=[], constructors=[])
        def check(*modules):
            supplied = [(str(index), module) for index, module in enumerate(modules)]
            expected = audit_core.Audit(supplied, CAP).run(['root'])
            with tempfile.TemporaryDirectory() as directory:
                with AuditStore(Path(directory) / 'provided.sqlite', {}) as store:
                    actual = audit_core.Audit(iter(supplied), CAP, store=store).run(['root'])
                    output = io.StringIO()
                    audit_core.write_report(actual, output)
                    self.assertEqual(expected, json.loads(output.getvalue()))
            return expected
        self.assertTrue(check(fragment, provider)['accepted'])
        for invalid in (None, dict(provider, unit='other'), dict(provider, module='Other'),
                        dict(provider, boundary='actual-interface-unfoldings')):
            modules = [fragment] + ([] if invalid is None else [invalid])
            result = check(*modules)
            self.assertFalse(result['accepted'])
            self.assertTrue(any(issue['detail'] == 'Interface closure lacks its exact complete provided module'
                                for issue in result['issues']))
        self.assertFalse(check(dict(fragment, unit='forged'), provider)['accepted'])




class AggregateHeapFieldTest(unittest.TestCase):
    empty = dict(kind='unknown', evaluated=True, aggregate='unboxed-tuple', components=[], primReps=[])
    sum_rep = dict(kind='unknown', evaluated=True, aggregate='unboxed-sum',
                   alternatives=[empty, REFERENCE], primReps=['WordRep', 'BoxedRep (Just Lifted)'],
                   tagSlot=0, alternativeSlots=[[], [1]])

    def check(self, proof, mutation=None, saturated=True, cap=None):
        info = dict(id='Box', name='Box', kind='boxed', arity=1, fieldTypes=[proof],
                    fieldReps=[proof['primReps']], fieldLifted=[False], strictFields=[True])
        argument = ['var', 'payload', dict(rep=copy.deepcopy(proof))]
        expression = ['app', ['con', 'Box', 1], [argument], [False], False, False, dict(rep=dict(REFERENCE, evaluated=True))]
        if mutation:
            mutation(info, expression)
        audit = audit_core.Audit([('heap.json', dict(schema=1, ghc='9.14.1', bindings=[], constructors=[info]))], cap or CAP)
        audit.walk(expression if saturated else expression[1], {'payload': proof}, 'root', '/expr')
        return audit.issues

    def test_direct_saturated_sum_empty_and_nested_tuple_fields(self):
        nested = dict(self.empty, components=[LONG, dict(self.empty, components=[REFERENCE], primReps=REFERENCE['primReps'])],
                      primReps=LONG['primReps'] + REFERENCE['primReps'])
        address = dict(kind='address', evaluated=True, primReps=['AddrRep'])
        address_tuple = dict(self.empty, components=[address], primReps=['AddrRep'])
        for proof in (self.empty, self.sum_rep, nested, address_tuple):
            self.assertEqual([], self.check(proof))
            self.assertTrue(self.check(proof, cap=dict(CAP, aggregateHeapFields=[])))
            self.assertTrue(self.check(proof, saturated=False))

    def test_constructor_shape_arity_and_levity_stay_checked(self):
        mutations = [lambda info, expr: info.update(fieldReps=[['WordRep']]),
                     lambda info, expr: info.update(fieldLifted=[True]),
                     lambda info, expr: expr[3].__setitem__(0, True),
                     lambda info, expr: expr[2][0][2].update(rep=LONG),
                     lambda info, expr: info.update(fieldTypes=[dict(self.sum_rep, alternativeSlots=[[], [0]])]),
                     lambda info, expr: info.update(arity=2)]
        for mutation in mutations:
            self.assertTrue(self.check(copy.deepcopy(self.sum_rep), mutation))


class ReportReachabilityTest(unittest.TestCase):
    def test_first_discovery_forest_preserves_roots_cycles_and_diagnostic_paths(self):
        bindings = [
            bind('root', ['app', var('left'), [var('right')], [True]]),
            bind('second', var('shared')),
            bind('retained', var('retainedMissing')),
            bind('left', var('shared')),
            bind('right', var('root')),
            bind('shared', ['let', False, [bind('bad', ['lit', 'unknown-kind', 'x'])], var('missing')]),
        ]
        module = dict(schema=1, ghc='9.14.1', bindings=bindings, constructors=[])
        auditor = audit_core.Audit([('first.json', module)], CAP)
        auditor.retained_exports = ['retained']
        report = auditor.run(['root', 'second', 'root', 'absent'])
        self.assertEqual(2, report['schema'])
        self.assertFalse(report['accepted'])
        self.assertEqual(['root', 'second', 'root'], report['roots'])
        self.assertEqual(['retained'], report['retainedExports'])
        self.assertEqual(dict(suppliedBindings=6, reachableBindings=6, missingGlobals=2, issues=2, unresolvedNativeSymbols=0), report['summary'])
        self.assertEqual([dict(id=key, source='first.json', predecessor=parent) for key, parent in (
            ('root', None), ('second', None), ('retained', None),
            ('left', 'root'), ('right', 'root'), ('shared', 'second'))], report['reachableBindings'])
        self.assertEqual([
            dict(code='entry-resolution', owner=None, path='absent', detail=dict(candidates=[])),
            dict(code='unsupported-literal', owner='shared', path='/expr/bindings/0/rhs',
                 detail='unknown-kind', reachableVia=['second', 'shared']),
        ], report['issues'])
        self.assertEqual([
            dict(id='missing', reachableVia=['second', 'shared', 'missing'],
                 references=[dict(owner='shared', path='/expr/body')]),
            dict(id='retainedMissing', reachableVia=['retained', 'retainedMissing'],
                 references=[dict(owner='retained', path='/expr')]),
        ], report['missingGlobals'])
        self.assertEqual(7, len(report['dependencies']))

    def test_long_chains_have_linear_reports_and_complete_tail_diagnostics(self):
        sizes = []
        for count in (5000, 10000):
            with self.subTest(count=count):
                keys = [f'node{index:05d}' for index in range(count)]
                # Keep dependency depth separate from root type inference: each
                # ordinary local RHS reaches the next global in the chain.
                bindings = [bind(key, ['let', False, [bind('edge', var(keys[index + 1]))], lit(0)])
                            for index, key in enumerate(keys[:-1])]
                bindings.append(bind(keys[-1], ['let', False,
                    [bind('bad', ['lit', 'unknown-kind', 'x'])], var('missing')]))
                module = dict(schema=1, ghc='9.14.1', bindings=bindings, constructors=[])
                report = audit_core.Audit([('chain.json', module)], CAP).run([keys[0]])
                self.assertFalse(report['accepted'])
                self.assertEqual(dict(suppliedBindings=count, reachableBindings=count,
                                      missingGlobals=1, issues=1, unresolvedNativeSymbols=0), report['summary'])
                self.assertEqual(keys, report['issues'][0]['reachableVia'])
                self.assertEqual(keys + ['missing'], report['missingGlobals'][0]['reachableVia'])
                self.assertEqual(count, len(report['dependencies']))
                self.assertEqual(keys[-2], report['reachableBindings'][-1]['predecessor'])
                sizes.append(len(json.dumps(report)))
        self.assertEqual(2, len(sizes))
        self.assertLess(sizes[1], sizes[0] * 2.1)


class ReportStreamTest(unittest.TestCase):
    def test_report_stream_matches_previous_format_without_one_large_write(self):
        class Sink(io.StringIO):
            largest = 0
            def write(self, value):
                self.largest = max(self.largest, len(value))
                return super().write(value)
        for expression in (lit(42), var('missing-λ\n')):
            with self.subTest(expression=expression):
                report = run(expression)
                sink = Sink()
                audit_core.write_report(report, sink)
                expected = json.dumps(report, indent=2) + '\n'
                self.assertEqual(expected, sink.getvalue())
                self.assertLess(sink.largest, len(expected))


class CompactSignatureTest(unittest.TestCase):
    def test_compact_image_and_heap_address_signatures(self):
        roles = {
            'state': dict(kind='void', primReps=[], evaluated=True),
            'word': dict(kind='long', primReps=['WordRep'], evaluated=True),
            'address': dict(kind='address', primReps=['AddrRep'], evaluated=True),
            'region': dict(kind='object', primReps=['BoxedRep (Just Unlifted)'], evaluated=True),
            'lifted': REFERENCE, 'boxed': REFERENCE,
        }
        self.assertEqual(6, len(CAP['managedCompactImagePrimitives']))
        for name, signature in CAP['managedCompactImagePrimitives'].items():
            arguments = [['var', str(i), dict(rep=roles[role])] for i, role in enumerate(signature['arguments'])]
            fields = [roles[role] for role in signature['result']]
            output = dict(kind='unknown', primReps=[r for rep in fields for r in rep['primReps']],
                          evaluated=True, aggregate='unboxed-tuple', components=fields)
            expression = ['app', ['prim', name], arguments,
                          [r == 'lifted' for r in signature['arguments']], False, False, dict(rep=output)]
            bound = {str(i): roles[role] for i, role in enumerate(signature['arguments'])}
            audit = audit_core.Audit([], CAP)
            audit.walk(expression, bound, 'root', 'root', tuple_result=output)
            self.assertEqual([], audit.issues, (name, audit.issues))
            bad = copy.deepcopy(expression)
            bad[2][0][2]['rep'] = dict(kind='double', primReps=['DoubleRep'], evaluated=True)
            audit = audit_core.Audit([], CAP)
            audit.walk(bad, bound, 'root', 'root', tuple_result=output)
            self.assertIn('invalid compact signature', str(audit.issues), name)

    def test_seven_compact_signatures_and_wrong_scalar_carriers(self):
        roles = {
            'state': dict(kind='void', primReps=[], evaluated=True),
            'word': dict(kind='long', primReps=['WordRep'], evaluated=True),
            'int': dict(kind='long', primReps=['IntRep'], evaluated=True),
            'region': dict(kind='object', primReps=['BoxedRep (Just Unlifted)'], evaluated=True),
            'lifted': REFERENCE,
        }
        self.assertEqual(7, len(CAP['managedCompactPrimitives']))
        for name, signature in CAP['managedCompactPrimitives'].items():
            arguments = [['var', str(i), dict(rep=roles[role])] for i, role in enumerate(signature['arguments'])]
            output = roles[signature['result']]
            if signature['result'] != 'state':
                output = dict(kind='unknown', primReps=output['primReps'], evaluated=True,
                              aggregate='unboxed-tuple', components=[roles['state'], output])
            expression = ['app', ['prim', name], arguments,
                          [r == 'lifted' for r in signature['arguments']], False, False, dict(rep=output)]
            bound = {str(i): roles[role] for i, role in enumerate(signature['arguments'])}
            audit = audit_core.Audit([], CAP)
            audit.walk(expression, bound, 'root', 'root', tuple_result=output)
            self.assertEqual([], audit.issues, (name, audit.issues))
            bad = copy.deepcopy(expression)
            bad[2][0][2]['rep'] = dict(kind='double', primReps=['DoubleRep'], evaluated=True)
            audit = audit_core.Audit([], CAP)
            audit.walk(bad, bound, 'root', 'root', tuple_result=output)
            self.assertIn('invalid compact signature', str(audit.issues), name)


class CpuAffinityQueryTest(unittest.TestCase):
    def calls(self):
        descriptors = json.loads((ROOT.parent / 'src/test/resources/core/cpu-affinity-descriptors.json').read_text())
        for descriptor in descriptors.values():
            state = dict(descriptor['argumentReps'][0], evaluated=True)
            yield ['app', ['var', 'query', dict(rep=CLOSURE)], [['var', 's', dict(rep=state)]],
                   [False], False, False,
                   dict(rep=dict(descriptor['resultRep'], evaluated=True), foreignCall=descriptor)], state

    def inspect(self, call, state, shadow=False, package=False):
        audit = audit_core.Audit([], CAP)
        if package:
            audit.package_scalar_links['main'] = dict(unit='main', abi=[])
        audit.polyglot_call(call, dict(s=state, **({'query': CLOSURE} if shadow else {})), 'root', 'root')
        return audit

    def test_real_declarations_and_native_fallback_precedence(self):
        for call, state in self.calls():
            for package in (False, True):
                audit = self.inspect(call, state, package=package)
                self.assertEqual([], audit.issues)
                self.assertEqual(1, len(audit.foreign_calls))

    def test_abi_state_and_shadowing_fail_closed(self):
        for call, state in self.calls():
            for key, value in (('safety', 'safe'), ('convention', 'prim'), ('arity', 2),
                               ('schema', True), ('suppliedArity', True), ('resultRep', LONG)):
                changed = copy.deepcopy(call)
                changed[6]['foreignCall'][key] = value
                self.assertTrue(self.inspect(changed, state).issues, key)
            self.assertTrue(self.inspect(call, LONG).issues)
            self.assertTrue(self.inspect(call, state, shadow=True).issues)


class RuntimeServicesQueryTest(unittest.TestCase):
    def calls(self):
        descriptors = json.loads((ROOT.parent / 'src/test/resources/core/runtime-services-descriptors.json').read_text())
        metadata = json.loads((ROOT.parent / 'src/test/resources/core/foreign-exception-descriptor.json').read_text())
        descriptors[metadata['target']['symbol']] = metadata
        self.assertEqual(set(CAP['runtimeServiceCalls']), set(descriptors))
        for symbol, primitives in CAP['runtimeServiceCalls'].items():
            declaration = descriptors[symbol]
            proofs = declaration['argumentReps']
            self.assertEqual(len(primitives), len(proofs))
            result = declaration['resultRep']
            bound = {f'a{i}': dict(proof, evaluated=True) for i, proof in enumerate(proofs)}
            expression = ['app', ['var', 'service', dict(rep=CLOSURE)],
                          [['var', key, dict(rep=proof)] for key, proof in bound.items()],
                          [False] * len(proofs), False, False,
                          dict(rep=dict(result, evaluated=True), foreignCall=declaration)]
            yield expression, bound

    def inspect(self, expression, bound, package=False):
        audit = audit_core.Audit([], CAP)
        if package:
            audit.package_scalar_links['main'] = dict(unit='main', abi=[])
        audit.polyglot_call(expression, bound, 'root', 'root')
        return audit

    def test_exact_reserved_calls_precede_native_compatibility_package(self):
        for expression, bound in self.calls():
            for package in (False, True):
                audit = self.inspect(expression, bound, package)
                expected = (['foreign-exception-bridge'] if
                    expression[6]['foreignCall']['target']['symbol'] == 'thc_exception_v1_text' else [])
                self.assertEqual(expected, [issue['code'] for issue in audit.issues])
                self.assertEqual(1, len(audit.foreign_calls))

    def test_foreign_abi_and_shadowed_names_reject(self):
        for expression, bound in self.calls():
            for key, value in (('schema', True), ('arity', True), ('suppliedArity', 0),
                               ('convention', 'javascript'),
                               ('safety', 'unsafe' if expression[6]['foreignCall']['safety'] == 'safe' else 'safe'),
                               ('resultRep', LONG)):
                changed = copy.deepcopy(expression)
                changed[6]['foreignCall'][key] = value
                self.assertTrue(self.inspect(changed, bound).issues, key)
            self.assertTrue(self.inspect(expression, dict(bound, service=CLOSURE)).issues)
            changed = copy.deepcopy(expression)
            changed[3][0] = True
            self.assertTrue(self.inspect(changed, bound).issues)

    def test_wrong_width_and_erased_stored_operand_reject(self):
        for expression, bound in self.calls():
            for index, key in enumerate(bound):
                bad = copy.deepcopy(expression)
                bad[2][index][2]['rep'] = LONG
                self.assertTrue(self.inspect(bad, bound).issues, key)
                self.assertTrue(self.inspect(expression, dict(bound, **{key: LONG})).issues, key)


class CoreOwnedPackageDispatchTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        calls = cls().calls()
        changed = [dict(call, target=dict(call['target'], **alteration)) for call in calls
                   for alteration in (dict(unit='ordinary-provider'), dict(symbol='ordinary_missing'))]
        cls.ownership = core_original_foreign.ForeignOwnership(calls + changed)

    def calls(self):
        return json.loads((ROOT.parent / 'src/main/resources/thc/core-native-overrides.json').read_text())['calls']

    def expression(self, call):
        bound = {f'a{index}': dict(proof, evaluated=True) for index, proof in enumerate(call['argumentReps'])}
        expression = ['app', ['var', 'foreign-head', dict(rep=CLOSURE)],
            [['var', key, dict(rep=proof)] for key, proof in bound.items()],
            [False] * len(bound), False, False,
            dict(rep=dict(call['resultRep'], evaluated=True), foreignCall=copy.deepcopy(call))]
        return expression, bound

    def inspect(self, expression, bound, cap=None):
        auditor = audit_core.Audit([], cap or CAP)
        unit = expression[6]['foreignCall']['target']['unit']
        auditor.package_scalar_links[unit] = dict(unit=unit, abi=[])
        auditor.foreign_ownership = self.ownership
        auditor.polyglot_call(expression, bound, 'root', 'root')
        return auditor

    def test_exact_shared_profile_calls_keep_their_live_owned_validator_before_native_adapters(self):
        for call in self.calls():
            expression, bound = self.expression(call)
            observed = self.inspect(expression, bound)
            with self.subTest(symbol=call['target']['symbol']):
                self.assertEqual([], observed.issues)
                self.assertEqual([call['target']['symbol']], [item['symbol'] for item in observed.foreign_calls])

    def test_profile_routing_retains_descriptor_head_operand_result_and_capability_rejections(self):
        for call in self.calls():
            original, bound = self.expression(call)
            mutations = [('schema', lambda e: e[6]['foreignCall'].update(schema=True)),
                ('arity', lambda e: e[6]['foreignCall'].update(arity=True)),
                ('owner', lambda e: e[6]['foreignCall']['target'].update(unit='ordinary-provider')),
                ('symbol', lambda e: e[6]['foreignCall']['target'].update(symbol='ordinary_missing')),
                ('convention', lambda e: e[6]['foreignCall'].update(convention='capi')),
                ('safety', lambda e: e[6]['foreignCall'].update(safety='interruptible')),
                ('declared carrier', lambda e: e[6]['foreignCall']['argumentReps'][-1].update(primReps=['IntRep'])),
                ('actual carrier', lambda e: e[2][-1][2].update(rep=LONG)),
                ('result', lambda e: e[6].update(rep=LONG)),
                ('flags', lambda e: e[3].__setitem__(-1, True)),
                ('head', lambda e: e[1][2].update(rep=LONG))]
            for name, mutate in mutations:
                changed = copy.deepcopy(original)
                mutate(changed)
                with self.subTest(symbol=call['target']['symbol'], mutation=name):
                    self.assertTrue(self.inspect(changed, bound).issues)
            with self.subTest(symbol=call['target']['symbol'], mutation='shadowed head'):
                self.assertTrue(self.inspect(original, dict(bound, **{'foreign-head': CLOSURE})).issues)
            with self.subTest(symbol=call['target']['symbol'], mutation='stored State carrier'):
                self.assertTrue(self.inspect(original, dict(bound, **{f'a{len(bound)-1}': LONG})).issues)
            # Callback release has its own pre-package protocol, not this flag.
            if call['target']['symbol'] in core_original_foreign.OPERATIONS:
                disabled = dict(CAP, managedForeignCalls=[])
                with self.subTest(symbol=call['target']['symbol'], mutation='disabled capability'):
                    self.assertTrue(self.inspect(original, bound, disabled).issues)


class JavaScriptPackageTest(unittest.TestCase):
    def test_mixed_native_unit_keeps_javascript_proof_and_c_adapter_checks(self):
        state = dict(kind='void', primReps=[], evaluated=True)
        result = dict(kind='unknown', primReps=['IntRep'], evaluated=True,
                      aggregate='unboxed-tuple', components=[state, LONG])
        source = '() => 7'
        call = dict(schema=1, intrinsic='javascript-v1', javascriptSource=source,
                    target=dict(kind='static', symbol='thc_javascript_v1_' + source.encode().hex(), unit='main', isFunction=True),
                    convention='ccall', safety='unsafe', arity=1, suppliedArity=1,
                    argumentReps=[dict(state, evaluated=False)], resultRep=dict(result, evaluated=False))
        ordinary = {key: value for key, value in call.items() if key not in ('intrinsic', 'javascriptSource')}
        ownership = core_original_foreign.ForeignOwnership([call])
        for descriptor, accepted in ((call, True), (dict(call, javascriptSource='() => 8'), False),
                                     (dict(call, arity=2), False), (ordinary, False)):
            audit = audit_core.Audit([], CAP)
            audit.package_scalar_links['main'] = dict(unit='main', abi=[])
            expression = ['app', ['var', 'foreign', dict(rep=CLOSURE)],
                          [['var', 'state', dict(rep=state)]], [False], False, False,
                          dict(rep=result, foreignCall=descriptor)]
            audit.foreign_ownership = ownership
            audit.polyglot_call(expression, {'state': state}, 'root', 'root')
            self.assertEqual(['foreign-exception-bridge'] if accepted else ['foreign-call'],
                             [issue['code'] for issue in audit.issues])
            self.assertEqual(1 if accepted else 0, len(audit.foreign_calls))


class PackageScalarOperandTest(unittest.TestCase):
    """Call-proof controls only. Valid calls still require a genuine runtime bridge;
    no invented dictionary, component or bitcode is executed."""
    @classmethod
    def setUpClass(cls):
        _, expression, _ = cls().call('WordRep')
        cls.ownership = core_original_foreign.ForeignOwnership([expression[6]['foreignCall']])

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
        audit.package_scalar_links['first'] = dict(unit='first', abi=abi if isinstance(abi, list) else [abi])
        audit.foreign_ownership = self.ownership
        self.assertTrue(audit.polyglot_call(expression, dict(argument=stored), 'root', 'root'))
        return audit.issues

    def test_package_scalar_occurrences_cannot_hide_stored_or_intrinsic_carriers(self):
        for primitive in ('Int32Rep', 'Int64Rep', 'FloatRep', 'DoubleRep'):
            abi, expression, stored = self.call(primitive)
            self.assertEqual(['foreign-exception-bridge'],
                [issue['code'] for issue in self.inspect(abi, expression, stored)], primitive)
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

    def test_package_capi_byte_storage_address_and_void_shapes(self):
        for rep in ('ByteArray#', 'MutableByteArray#', 'AddrRep'):
            abi, expression, _ = self.call('Word64Rep')
            stored = dict(kind='address' if rep == 'AddrRep' else 'object',
                          primReps=['AddrRep'] if rep == 'AddrRep' else ['BoxedRep (Just Unlifted)'], evaluated=True)
            abi.update(arguments=[rep], result='void', convention='capi')
            state = dict(kind='void', primReps=[], evaluated=True)
            output = dict(kind='unknown', primReps=[], aggregate='unboxed-tuple', evaluated=True, components=[state])
            expression[2][0][2]['rep'] = stored
            expression[6]['rep'] = output
            expression[6]['foreignCall'].update(convention='capi',
                argumentReps=[dict(stored, evaluated=False), dict(state, evaluated=False)],
                resultRep=dict(output, evaluated=False))
            self.assertEqual(['foreign-exception-bridge'],
                [issue['code'] for issue in self.inspect(abi, expression, stored)], rep)
            self.assertIn('stored operand', str(self.inspect(abi, expression,
                dict(stored, primReps=['BoxedRep (Just Lifted)']))), rep)
            altered = copy.deepcopy(expression)
            altered[6]['rep']['components'].append(dict(kind='long', primReps=['Word64Rep'], evaluated=True))
            self.assertIn('exact scalar/State ABI', str(self.inspect(abi, altered, stored)), rep)

    def test_unlinked_typed_array_calls_keep_native_link_obligations(self):
        for rep in ('ByteArray#', 'MutableByteArray#'):
            _, expression, _ = self.call('Word64Rep')
            stored = dict(kind='object', primReps=['BoxedRep (Just Unlifted)'], evaluated=True)
            expression[2][0][2]['rep'] = stored
            call = expression[6]['foreignCall']
            call.update(schema=2, argumentTypes=[rep, None])
            call['argumentReps'][0] = dict(stored, evaluated=False)
            audit = audit_core.Audit([], CAP)
            self.assertTrue(audit.polyglot_call(expression, dict(argument=stored), 'root', 'root'))
            self.assertEqual([], audit.issues)
            self.assertTrue(audit.foreign_calls[0]['nativeLinkRequired'])
            self.assertEqual([rep], audit.foreign_calls[0]['arguments'])
            for alteration in ('missing-type', 'wrong-type', 'wrong-shape', 'wrong-flag', 'wrong-state'):
                bad = copy.deepcopy(expression)
                descriptor = bad[6]['foreignCall']
                if alteration == 'missing-type': descriptor['argumentTypes'].pop()
                elif alteration == 'wrong-type': descriptor['argumentTypes'][0] = 'Any'
                elif alteration == 'wrong-shape': descriptor['argumentReps'][0]['primReps'] = ['AddrRep']
                elif alteration == 'wrong-flag': bad[3][0] = True
                else: bad[2][-1] = ['lit', 'int', '0', bad[2][-1][1]]
                audit = audit_core.Audit([], CAP)
                audit.polyglot_call(bad, dict(argument=stored), 'root', 'root')
                self.assertTrue(audit.issues, alteration)

    def test_same_symbol_pointer_variants_select_by_exact_call_shape(self):
        original, expression, _ = self.call('Word64Rep')
        variants = [dict(original, entry='adapter_' + str(index), arguments=[rep])
                    for index, rep in enumerate(('AddrRep', 'ByteArray#'))]
        for rep in ('AddrRep', 'ByteArray#'):
            stored = dict(kind='address' if rep == 'AddrRep' else 'object',
                          primReps=['AddrRep'] if rep == 'AddrRep' else ['BoxedRep (Just Unlifted)'], evaluated=True)
            expression[2][0][2]['rep'] = stored
            expression[6]['foreignCall']['argumentReps'][0] = dict(stored, evaluated=False)
            self.assertEqual(['foreign-exception-bridge'],
                [issue['code'] for issue in self.inspect(variants, expression, stored)], rep)
        ambiguous = [variants[1], dict(variants[1], entry='mutable_adapter', arguments=['MutableByteArray#'])]
        self.assertIn('unique exact scalar/State ABI', str(self.inspect(ambiguous, expression, stored)))

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
                components=list(components), primReps=None if any(c['primReps'] is None for c in components) else
                    [r for c in components for r in c['primReps']])


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
    root = dict(bind('root', expression), type='IO ()', arity=1, rep=CLOSURE)
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

    def test_known_invalid_shapes_and_missing_targets_reject(self):
        for count in (0, 1, 3, 4):
            with self.subTest(supplied=count):
                module = io_main_fixture()
                expression = module['bindings'][0]['expr']
                expression[2] = [[*lit(i), dict(rep=LONG)] for i in range(count)]
                expression[3] = [False] * count
                self.rejects_boundary(module)
        module = io_main_fixture()
        module['bindings'][0]['expr'][1] = [*var('unknown'), dict(rep=CLOSURE)]
        self.assertFalse(self.audit(module)['accepted'])

    def test_lifted_answers_do_not_depend_on_display_type(self):
        for spelling in ('IO Int', 'IO (Int -> Int)', None):
            module = io_main_fixture()
            module['bindings'][0]['type'] = spelling
            self.assertTrue(self.audit(module)['accepted'])

    def test_pap_keeps_exact_input_and_result_checks(self):
        mutations = [lambda m: m['bindings'][1]['expr'][1][-1].update(rep=LONG),
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
    def test_bignat_diagnostic_spellings_remain_strict_before_cbd_numeric_encoding(self):
        # Numeric CBD records cannot retain malformed source spelling: the
        # real encoder rejects it or canonicalizes an accepted integer model.
        # Keep these exact API negatives independently of the CBD CLI controls.
        for value in ('', '-1', '+1', '00', '01', ' 1', '1 ', '1.0', '0x10', '١'):
            report = run(['lit', 'bignat', value])
            self.assertFalse(report['accepted'], value)
            self.assertIn('invalid-literal-value', {issue['code'] for issue in report['issues']}, value)

    def test_bignat_intrinsic_representation_api_retains_exact_evaluated_proof(self):
        # This is an assertion on the retained Python auditor's own API. The
        # Fixture-free Java BigNat tests own CBD CLI admission controls.
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

    def test_floating_bits_literals_require_unsigned_width_and_floating_capability(self):
        for kind, width, carrier in [('float-bits', 32, 'float'), ('double-bits', 64, 'double')]:
            for value in ('0', '1', str(1 << (width - 1)), str((1 << width) - 1)):
                report = run(['lit', kind, value])
                self.assertTrue(report['accepted'], (kind, value))
                self.assertEqual(kind, report['literals'][0]['kind'])
                self.assertEqual([value], report['literals'][0]['examples'])
            for value in ('-1', str(1 << width), '', '+1', '01', '-0', ' 1', '1.0', 1, None):
                report = run(['lit', kind, value])
                self.assertIn('invalid-literal-value', {i['code'] for i in report['issues']}, (kind, value))
            cap = dict(CAP, literalKinds=[literal for literal in CAP['literalKinds'] if literal != carrier])
            report = run(['lit', kind, '0'], cap=cap)
            self.assertIn('unsupported-literal', {i['code'] for i in report['issues']}, kind)

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
                    (['lit', 'float-bits', '2143289344'], dict(kind='float', primReps=['FloatRep'], evaluated=True)),
                    (['lit', 'double-bits', '9221120237041090560'], dict(kind='double', primReps=['DoubleRep'], evaluated=True)),
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

    def test_empty_tuple_case_evaluates_bottom_without_requiring_a_fabricated_alternative(self):
        module = tuple_fixture(tuple_rep(LONG))
        case = module['bindings'][0]['expr'][2]
        self.assertEqual('case', case[0])
        case[3] = []
        self.assertTrue(run_tuple(module)['accepted'])
        case[3] = [['default', None, [], lit(0)], ['default', None, [], lit(0)]]
        self.assertIn('unboxed-tuple requires at most one alternative',
                      [issue['detail'] for issue in run_tuple(module)['issues']])

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
        disabled = dict(TUPLE_CAP, aggregateCaptures=[])
        report = audit_core.Audit([('tuple.json', module)], disabled).run(['root'])
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
                vector = dict(kind='vector', evaluated=True, primReps=['VecRep 8 Int16ElemRep'],
                              vector=dict(lanes=8, element='Int16ElemRep'))
                summed = dict(kind='unknown', evaluated=True, aggregate='unboxed-sum', alternatives=[LONG, LONG],
                              primReps=['WordRep', 'WordRep'], tagSlot=0, alternativeSlots=[[1], [1]])
                for result in (vector, summed):
                    bottom = copy.deepcopy(good)
                    bottom[-1]['rep'] = result
                    function = ['lam', [dict(id='input', lifted=False, rep=LONG)], bottom,
                                dict(rep=CLOSURE, resultRep=result)]
                    report = run(function, [bind(payload, var(payload))], [constructor])
                    self.assertTrue(report['accepted'], report['issues'])
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

    def test_exception_scalar_results_preserve_exact_tuple_and_payload_proofs(self):
        state = dict(kind='void', primReps=[], evaluated=True)
        scalar_results = [LONG, dict(kind='long', primReps=['WordRep'], evaluated=True),
                          dict(kind='address', primReps=['AddrRep'], evaluated=True)]
        for name in ('raiseIO#', 'catch#', 'maskAsyncExceptions#', 'maskUninterruptible#', 'unmaskAsyncExceptions#'):
            arguments = ([REFERENCE, state] if name == 'raiseIO#' else
                         [CLOSURE, CLOSURE, state] if name == 'catch#' else [CLOSURE, state])
            for scalar in scalar_results:
                good = ['app', ['prim', name],
                        [[*var('operand'), dict(rep=copy.deepcopy(rep))] for rep in arguments],
                        [rep is not state for rep in arguments], False, False,
                        dict(rep=tuple_rep(state, scalar))]
                with self.subTest(name=name, result=scalar):
                    self.assertNotIn('primitive-representation', {i['code'] for i in run(good)['issues']})
                    mutations = [
                        tuple_rep(scalar, state),
                        tuple_rep(state, scalar, scalar),
                        tuple_rep(state, dict(kind='unknown', primReps=None, evaluated=True)),
                        dict(tuple_rep(state, scalar), primReps=['DoubleRep']),
                        tuple_rep(state, dict(scalar, kind='float')),
                    ]
                    for result in mutations:
                        bad = copy.deepcopy(good)
                        bad[-1]['rep'] = result
                        self.assertIn('primitive-representation', {i['code'] for i in run(bad)['issues']})
                    bad = copy.deepcopy(good)
                    bad[2][0][-1]['rep'] = scalar
                    self.assertIn('primitive-representation', {i['code'] for i in run(bad)['issues']})

    def test_exception_result_layouts_and_payload_levities_are_independent(self):
        state = dict(kind='void', primReps=[], evaluated=True)
        unlifted = dict(kind='object', primReps=['BoxedRep (Just Unlifted)'], evaluated=True)
        floating = dict(kind='float', primReps=['FloatRep'], evaluated=True)
        results = [REFERENCE, unlifted, dict(kind='object', primReps=['BoxedRep Nothing'], evaluated=False), state, tuple_rep(), floating,
                   dict(kind='double', primReps=['DoubleRep'], evaluated=True),
                   tuple_rep(LONG, tuple_rep(floating, tuple_rep()))]
        results.extend(dict(kind='long', primReps=[rep], evaluated=True) for rep in
                       ('Int8Rep', 'Word8Rep', 'Int16Rep', 'Word16Rep', 'Int32Rep', 'Word32Rep', 'Int64Rep', 'Word64Rep'))
        for name in ('raiseIO#', 'catch#', 'maskAsyncExceptions#', 'maskUninterruptible#', 'unmaskAsyncExceptions#'):
            for payload in (REFERENCE, unlifted):
                arguments = ([payload, state] if name == 'raiseIO#' else
                             [CLOSURE, CLOSURE, state] if name == 'catch#' else [CLOSURE, state])
                flags = [rep.get('primReps') == ['BoxedRep (Just Lifted)'] for rep in arguments]
                for result in results:
                    good = ['app', ['prim', name],
                            [[*var('operand'), dict(rep=copy.deepcopy(rep))] for rep in arguments],
                            flags, False, False, dict(rep=tuple_rep(state, result))]
                    with self.subTest(name=name, payload=payload, result=result):
                        self.assertNotIn('primitive-representation', {i['code'] for i in run(good)['issues']})
                        for malformed in (dict(kind='unknown', primReps=[], evaluated=True),
                                          dict(kind='unknown', primReps=None, evaluated=True)):
                            bad = copy.deepcopy(good)
                            bad[-1]['rep'] = tuple_rep(state, malformed)
                            self.assertIn('primitive-representation', {i['code'] for i in run(bad)['issues']})
                        bad = copy.deepcopy(good)
                        bad[3] = [not flags[0], *flags[1:]]
                        self.assertIn('primitive-representation', {i['code'] for i in run(bad)['issues']})

    def test_public_thread_primops_require_exact_thread_id_and_lazy_payload(self):
        state = dict(kind='void', primReps=[], evaluated=True)
        thread = dict(kind='object', primReps=['BoxedRep (Just Unlifted)'], evaluated=True)
        action = dict(kind='closure', primReps=['BoxedRep (Just Lifted)'], evaluated=True)
        payload = dict(kind='data', primReps=['BoxedRep (Just Lifted)'], evaluated=False)
        roles = {'state': state, 'threadId': thread, 'action': action, 'payload': payload, 'int': LONG,
                 'int64': dict(kind='long', primReps=['Int64Rep'], evaluated=True),
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
        for kind, carrier, register, zero, negative_zero in [
                ('float', 'float', 'FloatRep', '0.0', '-0.0'),
                ('double', 'double', 'DoubleRep', '0.0', '-0.0'),
                ('float-bits', 'float', 'FloatRep', '0', '2147483648'),
                ('double-bits', 'double', 'DoubleRep', '0', '9223372036854775808')]:
            scalar = dict(kind=carrier, primReps=[register], evaluated=True)
            audit = audit_core.Audit([], CAP)
            audit.representation(scalar, None, '/scalar')
            self.assertEqual([], audit.issues)
            audit.representation(tuple_rep(scalar), None, '/tuple')
            self.assertEqual([], audit.issues)
            report = run(['case', ['lit', kind, zero], 'x',
                          [['lit', [kind, negative_zero], [], lit(1)], ['default', None, [], lit(0)]]])
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

    def test_empty_input_is_unlifted_and_available_at_host_boundary(self):
        module = self.fixture()
        self.assertTrue(self.audit(module, 'worker')['accepted'])
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

    def test_exact_empty_join_formals_and_unlifted_lets_are_separately_gated(self):
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
        self.assertTrue(self.audit(module)['accepted'])
        disabled = dict(CAP, aggregateLetBindings=[])
        self.assertIn('unboxed-tuple let binding', [i['detail'] for i in audit_core.Audit([('let', module)], disabled).run(['root'])['issues']])

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

    def test_empty_formal_capture_is_logical_but_requires_capture_capability(self):
        module = self.fixture([self.empty])
        worker = module['bindings'][1]
        inner = ['lam', [dict(id='x', lifted=False, rep=LONG)],
                 ['var', 'p0', dict(rep=self.empty)], dict(rep=CLOSURE, resultRep=self.empty)]
        worker['expr'][2] = inner
        worker['expr'][3]['resultRep'] = CLOSURE
        module['bindings'][0]['expr'][6]['rep'] = CLOSURE
        self.assertTrue(self.audit(module)['accepted'])
        self.assertIn('unboxed-tuple capture', [i['detail'] for i in
            self.audit(module, cap=dict(self.capability, aggregateCaptures=[]))['issues']])


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
            # Host tuple transport does not make an empty tuple a State token.
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

    def test_owner_native_component_does_not_claim_prim_stack_calls(self):
        audit = audit_core.Audit([('original-stack.json', self.fixture())], CAP)
        audit.package_scalar_links['ghc-internal'] = dict(unit='ghc-internal', abi=[])
        report = audit.run(['synthetic-consumer'])
        self.assertTrue(report['accepted'], report['issues'])

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


class OriginalForeignAuditFixture(unittest.TestCase):
    """Original declaration certificates; synthetic consumers, no closure claim."""

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
        return audit_core.Audit([('synthetic-foreign-consumer.json', module)], cap).run(['consumer'])



class OriginalSignalDeclarationTest(unittest.TestCase):
    def test_exact_call_requires_capability_and_implicit_original_dispatcher(self):
        for name in ('original-signal-install-descriptor.json', 'original-unix-signal-install-descriptor.json'):
            with self.subTest(resource=name):
                self.check_original(name)

    def check_original(self, name):
        resource = ROOT.parent / 'src/test/resources/core' / name
        declaration = json.loads(resource.read_text())
        fixture = OriginalForeignAuditFixture()
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
        for unit in (None, 'unix', 'unix-2.8.7.0-inplace', 'other', 1):
            wrong = copy.deepcopy(declaration)
            wrong['target']['unit'] = unit
            malformed = fixture.fixture(wrong)
            malformed['bindings'].append(bind(dispatcher, lit(0)))
            self.assertFalse(fixture.audit(malformed, enabled)['accepted'])


class NativeMallocDeclarationTest(unittest.TestCase):
    """Original descriptors for the bounded, owned native allocation protocol."""
    def test_two_exact_declarations_and_capability_gate(self):
        resource = ROOT.parent / 'src/test/resources/core/original-malloc-descriptors.json'
        declarations = json.loads(resource.read_text())
        self.assertEqual(['malloc', 'free'], [d['target']['symbol'] for d in declarations])
        fixture = OriginalForeignAuditFixture()
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
        for symbol in ('calloc', 'prefixmalloc', 'free2'):
            self.assertNotIn(symbol, core_original_foreign.OPERATIONS)

    def test_realloc_exact_abi_and_capability_gate(self):
        # Synthetic ABI consumer; EnvironmentFullCore independently audits the
        # genuine original declaration in Foreign.Marshal.Alloc.$wreallocBytes.
        declarations = json.loads((ROOT.parent / 'src/test/resources/core/original-malloc-descriptors.json').read_text())
        declaration = copy.deepcopy(declarations[0])
        declaration['target']['symbol'] = 'realloc'
        declaration['argumentReps'].insert(0, copy.deepcopy(declarations[1]['argumentReps'][0]))
        declaration['arity'] = declaration['suppliedArity'] = 3
        fixture = OriginalForeignAuditFixture()
        self.assertTrue(fixture.audit(fixture.fixture(declaration))['accepted'])
        disabled = dict(CAP, managedForeignCalls=[s for s in CAP['managedForeignCalls'] if s != 'realloc'])
        self.assertFalse(fixture.audit(fixture.fixture(declaration), disabled)['accepted'])
        for key, value in [('safety', 'safe'), ('arity', 2), ('convention', 'capi'), ('schema', 1.0)]:
            wrong = copy.deepcopy(declaration); wrong[key] = value
            self.assertFalse(fixture.audit(fixture.fixture(wrong))['accepted'])


class OriginalUnlinkDeclarationTest(unittest.TestCase):
    def test_original_path_and_cint_result_contract(self):
        primitive = lambda kind, rep, evaluated: dict(kind=kind, primReps=[] if rep is None else [rep], evaluated=evaluated)
        state = primitive('void', None, True)
        address = primitive('address', 'AddrRep', False)
        result = dict(kind='unknown', primReps=['Int32Rep'], evaluated=False,
                      aggregate='unboxed-tuple', components=[state, primitive('long', 'Int32Rep', True)])
        descriptor = dict(schema=1, target=dict(kind='static', symbol='unlink', unit='ghc-internal', isFunction=True),
                          convention='ccall', safety='unsafe', arity=2, suppliedArity=2,
                          argumentReps=[address, primitive('void', None, False)], resultRep=result)
        fixture = OriginalForeignAuditFixture()
        self.assertTrue(fixture.audit(fixture.fixture(descriptor))['accepted'])
        disabled = dict(CAP, managedForeignCalls=[s for s in CAP['managedForeignCalls'] if s != 'unlink'])
        self.assertFalse(fixture.audit(fixture.fixture(descriptor), disabled)['accepted'])
        for key, value in [('convention', 'capi'), ('safety', 'safe'), ('arity', 1), ('resultRep', LONG)]:
            wrong = copy.deepcopy(descriptor); wrong[key] = value
            self.assertFalse(fixture.audit(fixture.fixture(wrong))['accepted'])


class OriginalMemmoveDeclarationTest(unittest.TestCase):
    def test_exact_original_descriptor_and_checked_capability(self):
        resource = ROOT.parent / 'src/test/resources/core/original-memmove-descriptor.json'
        declaration = json.loads(resource.read_text())
        self.assertEqual('memmove', declaration['target']['symbol'])
        fixture = OriginalForeignAuditFixture()
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


class OriginalMemorySearchDeclarationTest(unittest.TestCase):
    def test_exact_cint_csize_and_address_result_contracts(self):
        fixture = OriginalForeignAuditFixture()
        def scalar(rep, evaluated=False):
            kind = 'void' if rep is None else 'address' if rep == 'AddrRep' else 'long'
            return dict(kind=kind, primReps=[] if rep is None else [rep], evaluated=evaluated)
        for symbol, second, result in [('memcmp', 'AddrRep', 'Int32Rep'), ('memchr', 'Int32Rep', 'AddrRep'), ('memset', 'Int32Rep', 'AddrRep')]:
            output = dict(kind='unknown', primReps=[result], evaluated=False, aggregate='unboxed-tuple',
                          components=[scalar(None, True), scalar(result, True)])
            declaration = dict(schema=1, target=dict(kind='static', symbol=symbol, unit='bytestring-0.12.2.0-inplace', isFunction=True),
                convention='ccall', safety='unsafe', arity=4, suppliedArity=4,
                argumentReps=list(map(scalar, ['AddrRep', second, 'Word64Rep', None])), resultRep=output)
            for unit in ('bytestring-0.12.2.0-inplace', 'bytestring-0.12.2.0-119b',
                         'bytestring-0.12.2.0-5637', 'bytestring-0.12.2.0-3f3f', 'bytestring-0.12.2.0'):
                declaration['target']['unit'] = unit
                self.assertTrue(fixture.audit(fixture.fixture(declaration))['accepted'], (symbol, unit))
                disabled = dict(CAP, managedForeignCalls=[s for s in CAP['managedForeignCalls'] if s != symbol])
                self.assertFalse(fixture.audit(fixture.fixture(declaration), disabled)['accepted'], (symbol, unit))
            for key, value in [('safety', 'safe'), ('convention', 'capi'), ('arity', 3), ('schema', True)]:
                bad = copy.deepcopy(declaration); bad[key] = value
                self.assertFalse(fixture.audit(fixture.fixture(bad))['accepted'])
            for unit in ['foreign', 'ghc-internal', 'bytestring-0.12.1.0-119b',
                         'bytestring-0.12.2.0-119b-extra', 'bytestring-0.12.2.0-',
                         'bytestring-0.12.2.0-119b extra', 'bytestring-0.12.2.0-119b:forged',
                         'bytestring-0.12.2.0-119b\n', 'other-bytestring-0.12.2.0-119b',
                         None, ['bytestring-0.12.2.0-119b']]:
                bad = copy.deepcopy(declaration); bad['target']['unit'] = unit
                self.assertEqual(unit == 'ghc-internal' and symbol == 'memcmp', fixture.audit(fixture.fixture(bad))['accepted'])
            bad = copy.deepcopy(declaration); bad['argumentReps'][2] = scalar('WordRep')
            self.assertFalse(fixture.audit(fixture.fixture(bad))['accepted'])


    def test_memset_state_occurrence_cannot_hide_stored_or_lowered_long(self):
        fixture = OriginalForeignAuditFixture()
        def scalar(rep, evaluated=False):
            return dict(kind='void' if rep is None else 'address' if rep == 'AddrRep' else 'long',
                        primReps=[] if rep is None else [rep], evaluated=evaluated)
        # Same original CInt/CSize/State# declaration as the GHC native fixture.
        declaration = dict(schema=1, target=dict(kind='static', symbol='memset',
            unit='bytestring-0.12.2.0-inplace', isFunction=True), convention='ccall', safety='unsafe',
            arity=4, suppliedArity=4,
            argumentReps=list(map(scalar, ['AddrRep', 'Int32Rep', 'Word64Rep', None])),
            resultRep=dict(kind='unknown', primReps=['AddrRep'], evaluated=False, aggregate='unboxed-tuple',
                           components=[scalar(None, True), scalar('AddrRep', True)]))
        self.assertTrue(fixture.audit(fixture.fixture(declaration))['accepted'])
        for source in ('stored', 'lowered'):
            with self.subTest(source=source):
                module = fixture.fixture(declaration)
                wrapper = module['bindings'][0]['expr']
                call = wrapper[2][1]
                if source == 'stored':
                    wrapper[1][3]['rep'] = copy.deepcopy(LONG)
                else:
                    call[2][3] = ['lit', 'int', '1', dict(rep=scalar(None, True))]
                # The use site still claims void; the producer is really Long.
                self.assertEqual(scalar(None, True), call[2][3][-1]['rep'])
                report = fixture.audit(module)
                self.assertFalse(report['accepted'], report)
                self.assertEqual([], report['foreignCalls'])
                self.assertTrue(any(issue['code'] == 'foreign-call' and
                    source + ' operand 3' in issue['detail'] for issue in report['issues']), report)




class OriginalForeignOperandAuditTest(unittest.TestCase):
    """Producer proofs must agree with every admitted original occurrence."""

    @staticmethod
    def declarations():
        targets = [('bytestring-0.12.2.0-inplace', symbol) for symbol in (
            'memcmp', 'memchr', 'memset', 'bytestring_is_valid_utf8')]
        targets += [('ghc-internal', symbol) for symbol in (
            'close', 'isatty', 'epoll_ctl', 'hs_free_stable_ptr', '__hscore_set_errno', 'getpid')]
        targets += [('unix-2.8.8.0-inplace', 'geteuid')]
        targets += [('process-1.6.26.1-inplace', symbol) for symbol in core_original_foreign.PROCESS_OPERATIONS]
        targets += [('ghc-internal', symbol) for symbol in core_original_foreign.WINDOWS_ENCODING_OPERATIONS]
        targets += [('ghc-internal', 'GetLastError'), ('Win32-2.14.2.1-inplace', 'GetLastError')]
        for unit, symbol in targets:
            target = dict(kind='static', symbol=symbol, unit=unit, isFunction=True)
            convention, safety, arguments, output = core_original_foreign.operation(target)
            def scalar(rep, evaluated=False):
                return dict(kind=core_original_foreign.scalar_kind(rep),
                            primReps=[] if rep is None else [rep], evaluated=evaluated)
            result = dict(kind='unknown', primReps=[rep for rep in output if rep is not None],
                          evaluated=False, aggregate='unboxed-tuple',
                          components=[scalar(rep, True) for rep in output])
            for mode in safety if isinstance(safety, tuple) else (safety,):
                yield dict(schema=1, target=target, convention=convention, safety=mode,
                    arity=len(arguments), suppliedArity=len(arguments),
                    argumentReps=list(map(scalar, arguments)), resultRep=result)

    def test_catalog_arguments_have_checked_scalar_or_unlifted_carriers(self):
        # Adding a new carrier (especially a lifted closure or aggregate) needs
        # an explicit review of original_stack_operand, not another symbol list.
        supported = {None, 'AddrRep', 'FloatRep', 'DoubleRep', 'BoxedRep (Just Unlifted)',
                     'IntRep', 'Int8Rep', 'Int16Rep', 'Int32Rep', 'Int64Rep',
                     'WordRep', 'Word8Rep', 'Word16Rep', 'Word32Rep', 'Word64Rep'}
        for declaration in (*core_original_foreign.OPERATIONS.values(),
                            *core_original_foreign.LIBRARY_OPERATIONS.values()):
            self.assertLessEqual(set(declaration[2]), supported)
        # Unit-specific operands must be selected before checking producers.
        target = dict(symbol='memcpy', unit='array-0.5.8.0-inplace')
        self.assertEqual(('BoxedRep (Just Unlifted)', 'BoxedRep (Just Unlifted)', 'Word64Rep', None),
                         core_original_foreign.operation(target)[2])

    def test_windows_encoding_declarations_keep_exact_owners_and_safety(self):
        fixture = OriginalForeignAuditFixture()
        for declaration in self.declarations():
            symbol = declaration['target']['symbol']
            if symbol not in core_original_foreign.WINDOWS_ENCODING_OPERATIONS and symbol != 'GetLastError':
                continue
            rejected_safety = 'interruptible' if symbol == 'WideCharToMultiByte' else 'safe'
            for changes in (dict(target=dict(declaration['target'], unit='main')),
                            dict(safety=rejected_safety), dict(convention='stdcall'), dict(arity=99)):
                with self.subTest(symbol=symbol, changes=changes):
                    report = fixture.audit(fixture.fixture(dict(declaration, **changes)))
                    self.assertFalse(report['accepted'])
                    self.assertEqual([], report['foreignCalls'])

    def test_genuine_shaped_occurrences_and_refinable_stored_proofs_remain_valid(self):
        fixture = OriginalForeignAuditFixture()
        for declaration in self.declarations():
            with self.subTest(symbol=declaration['target']['symbol'], safety=declaration['safety']):
                module = fixture.fixture(declaration)
                self.assertTrue(fixture.audit(module)['accepted'])
                for formal in module['bindings'][0]['expr'][1]:
                    formal['rep'] = dict(kind='unknown', primReps=None, evaluated=False)
                self.assertTrue(fixture.audit(module)['accepted'])

    def test_state_occurrences_cannot_hide_stored_or_lowered_values(self):
        fixture = OriginalForeignAuditFixture()
        for declaration in self.declarations():
            index = len(declaration['argumentReps']) - 1
            for source in ('formal', 'global', 'literal', 'let', 'case'):
                with self.subTest(symbol=declaration['target']['symbol'], safety=declaration['safety'], source=source):
                    module = fixture.fixture(declaration)
                    wrapper = module['bindings'][0]['expr']
                    call = wrapper[2][1]
                    metadata = copy.deepcopy(call[2][index][-1])
                    if source == 'formal':
                        wrapper[1][index]['rep'] = copy.deepcopy(LONG)
                    elif source == 'global':
                        module['bindings'].append(dict(bind('stored-state', lit(1), False), rep=copy.deepcopy(LONG)))
                        call[2][index][1] = 'stored-state'
                    else:
                        operand = ['lit', 'int', '1', metadata]
                        if source == 'let':
                            operand = ['let', False, [], operand, metadata]
                        elif source == 'case':
                            operand = ['case', [*lit(0), dict(rep=LONG)], 'unused',
                                [['default', None, [], operand]],
                                dict(metadata, binder=dict(id='unused', lifted=False, rep=LONG))]
                        call[2][index] = operand
                    self.assertEqual([], core_original_foreign.raw_rep(call[2][index])['primReps'])
                    report = fixture.audit(module)
                    self.assertFalse(report['accepted'], report)
                    self.assertEqual([], report['foreignCalls'])
                    diagnostic = ('stored' if source in ('formal', 'global') else 'lowered') + f' operand {index}'
                    self.assertTrue(any(issue['code'] == 'foreign-call' and diagnostic in issue['detail']
                                        for issue in report['issues']), report)


class OriginalByteStringUtf8DeclarationTest(unittest.TestCase):
    def test_safe_and_unsafe_pointer_contracts_stay_closed(self):
        fixture = OriginalForeignAuditFixture()
        def scalar(rep, evaluated=False):
            return dict(kind='void' if rep is None else 'address' if rep == 'AddrRep' else 'long',
                        primReps=[] if rep is None else [rep], evaluated=evaluated)
        output = dict(kind='unknown', primReps=['Int32Rep'], evaluated=False, aggregate='unboxed-tuple',
                      components=[scalar(None, True), scalar('Int32Rep', True)])
        for safety in ('safe', 'unsafe'):
            declaration = dict(schema=1, target=dict(kind='static', symbol='bytestring_is_valid_utf8',
                unit='bytestring-0.12.2.0-inplace', isFunction=True), convention='ccall', safety=safety,
                arity=3, suppliedArity=3, argumentReps=list(map(scalar, ['AddrRep', 'Word64Rep', None])), resultRep=output)
            self.assertTrue(fixture.audit(fixture.fixture(declaration))['accepted'])
            disabled = dict(CAP, managedForeignCalls=[s for s in CAP['managedForeignCalls'] if s != 'bytestring_is_valid_utf8'])
            self.assertFalse(fixture.audit(fixture.fixture(declaration), disabled)['accepted'])
            for key, value in [('safety', 'interruptible'), ('convention', 'capi'), ('arity', 4), ('schema', True)]:
                bad = copy.deepcopy(declaration); bad[key] = value
                self.assertFalse(fixture.audit(fixture.fixture(bad))['accepted'], (safety, key))
            for unit in ['ghc-internal', 'text-2.1.3-inplace', 'bytestring-0.12.1.0-inplace', None]:
                bad = copy.deepcopy(declaration); bad['target']['unit'] = unit
                self.assertFalse(fixture.audit(fixture.fixture(bad))['accepted'], (safety, unit))
            for index, rep in [(0, 'BoxedRep (Just Unlifted)'), (1, 'WordRep')]:
                bad = copy.deepcopy(declaration); bad['argumentReps'][index] = scalar(rep)
                self.assertFalse(fixture.audit(fixture.fixture(bad))['accepted'], (safety, index))


class OriginalGcStatsDeclarationTest(unittest.TestCase):
    """Synthetic ABI negatives; JVM GC boundaries execute in CompilerHeapHintTest."""
    def test_closed_original_gc_stats_and_clock_abis(self):
        fixture = OriginalForeignAuditFixture()
        def scalar(rep, evaluated=False):
            return dict(kind='void' if rep is None else 'address' if rep == 'AddrRep' else 'long',
                        primReps=[] if rep is None else [rep], evaluated=evaluated)
        for symbol, arguments, output, safety in (
                ('getRTSStatsEnabled', (None,), 'IntRep', 'safe'),
                ('getRTSStats', ('AddrRep', None), None, 'safe'),
                ('performGC', (None,), None, 'safe'),
                ('performMajorGC', (None,), None, 'safe'),
                ('performBlockingMajorGC', (None,), None, 'safe'),
                ('getMonotonicNSec', (None,), 'Word64Rep', 'unsafe'),
                ('getNumberOfProcessors', (None,), 'Word32Rep', 'unsafe'),
                ('setNumCapabilities', ('Word32Rep', None), None, 'safe'),
                ('__hscore_sizeof_siginfo_t', (None,), 'Word64Rep', 'safe'),
                ('__hscore_f_setfd', (None,), 'Int32Rep', 'unsafe'),
                ('__hscore_fd_cloexec', (None,), 'Int64Rep', 'unsafe'),
                ('getOrSetSystemEventThreadIOManagerThreadStore', ('AddrRep', None), 'AddrRep', 'unsafe'),
                ('getOrSetSystemTimerThreadEventManagerStore', ('AddrRep', None), 'AddrRep', 'unsafe'),
                ('getOrSetSystemTimerThreadIOManagerThreadStore', ('AddrRep', None), 'AddrRep', 'unsafe')):
            declaration = dict(schema=1, target=dict(kind='static', symbol=symbol,
                unit='ghc-internal', isFunction=True), convention='ccall', safety=safety,
                arity=len(arguments), suppliedArity=len(arguments),
                argumentReps=[scalar(rep) for rep in arguments],
                resultRep=dict(kind='unknown', primReps=[] if output is None else [output],
                    evaluated=False, aggregate='unboxed-tuple', components=[scalar(None, True)] +
                    ([] if output is None else [scalar(output, True)])))
            module = fixture.fixture(declaration)
            report = fixture.audit(module)
            self.assertTrue(report['accepted'], report)
            self.assertEqual([symbol], [call['symbol'] for call in report['foreignCalls']])
            disabled = dict(CAP, managedForeignCalls=[s for s in CAP['managedForeignCalls'] if s != symbol])
            self.assertFalse(fixture.audit(module, disabled)['accepted'])
            for key, value in (('safety', 'unsafe' if safety == 'safe' else 'safe'),
                               ('convention', 'capi'), ('arity', 0), ('suppliedArity', 0),
                               ('schema', True), ('resultRep', LONG)):
                wrong = copy.deepcopy(declaration); wrong[key] = value
                self.assertFalse(fixture.audit(fixture.fixture(wrong))['accepted'], (symbol, key))
            for key, value in (('unit', 'main'), ('isFunction', False), ('symbol', symbol + '_alias')):
                wrong = copy.deepcopy(declaration); wrong['target'][key] = value
                self.assertFalse(fixture.audit(fixture.fixture(wrong))['accepted'], (symbol, key))
            for index in range(len(arguments)):
                wrong = fixture.fixture(declaration)
                wrong['bindings'][0]['expr'][1][index]['rep'] = LONG
                self.assertFalse(fixture.audit(wrong)['accepted'], (symbol, index))


class CompilerHeapHintDeclarationTest(unittest.TestCase):
    """Synthetic closed-ABI controls; actual GHC binding preparation is separate."""
    def test_only_the_exact_original_compiler_heap_hint_is_admitted(self):
        fixture = OriginalForeignAuditFixture()
        def scalar(rep, evaluated=False):
            return dict(kind='void' if rep is None else 'long',
                        primReps=[] if rep is None else [rep], evaluated=evaluated)
        declaration = dict(schema=1, target=dict(kind='static', symbol='setHeapSize',
            unit='ghc-9.14.1-inplace', isFunction=True), convention='ccall', safety='unsafe',
            arity=2, suppliedArity=2, argumentReps=[scalar('IntRep'), scalar(None)],
            resultRep=dict(kind='unknown', primReps=[], evaluated=False,
                           aggregate='unboxed-tuple', components=[scalar(None, True)]))
        report = fixture.audit(fixture.fixture(declaration))
        self.assertTrue(report['accepted'], report)
        self.assertEqual(['setHeapSize'], [call['symbol'] for call in report['foreignCalls']])
        disabled = dict(CAP, managedForeignCalls=[s for s in CAP['managedForeignCalls'] if s != 'setHeapSize'])
        self.assertFalse(fixture.audit(fixture.fixture(declaration), disabled)['accepted'])
        for key, value in [('unit', 'ghc-internal'), ('unit', 'main'), ('unit', 'ghc-9.14.1-other'),
                           ('symbol', 'setHeapSize_alias'), ('symbol', 'enableTimingStats'), ('isFunction', False)]:
            wrong = copy.deepcopy(declaration); wrong['target'][key] = value
            self.assertFalse(fixture.audit(fixture.fixture(wrong))['accepted'], (key, value))
        for key, value in [('safety', 'safe'), ('safety', 'interruptible'), ('convention', 'capi'),
                           ('arity', 1), ('suppliedArity', 1), ('schema', True), ('resultRep', LONG),
                           ('argumentReps', [scalar('WordRep'), scalar(None)])]:
            wrong = copy.deepcopy(declaration); wrong[key] = value
            self.assertFalse(fixture.audit(fixture.fixture(wrong))['accepted'], (key, value))


class OriginalLibraryMemoryDeclarationTest(unittest.TestCase):
    def test_bytestring_strlen_keeps_csize_abi_for_installed_units(self):
        fixture = OriginalForeignAuditFixture()
        declaration = json.loads((ROOT.parent / 'src/test/resources/core/original-bytestring-strlen-descriptor.json').read_text())
        signed = json.loads((ROOT.parent / 'src/test/resources/core/original-string-rts-descriptors.json').read_text())['strlen']
        for unit in ('bytestring-0.12.2.0-inplace', 'bytestring-0.12.2.0', 'bytestring-0.12.2.0-319833abde312f'):
            with self.subTest(unit=unit):
                installed = copy.deepcopy(declaration)
                installed['target']['unit'] = unit
                report = fixture.audit(fixture.fixture(installed))
                self.assertTrue(report['accepted'], report)
                self.assertEqual(['strlen'], [call['symbol'] for call in report['foreignCalls']])
                self.assertEqual(unit, installed['target']['unit'])
                disabled = dict(CAP, managedForeignCalls=[s for s in CAP['managedForeignCalls'] if s != 'strlen'])
                self.assertFalse(fixture.audit(fixture.fixture(installed), disabled)['accepted'])
                wrong = copy.deepcopy(installed)
                wrong['resultRep'] = signed['resultRep']
                self.assertFalse(fixture.audit(fixture.fixture(wrong))['accepted'])
        for unit in (None, ['bytestring-0.12.2.0'], 'ghc-internal', 'foreign',
                     'bytestring-0.12.1.0', 'bytestring-0.12.2.0-', 'bytestring-0.12.2.0-hash-extra',
                     'bytestring-0.12.2.0 hash', 'bytestring-0.12.2.0:hash', 'bytestring-0.12.2.0\n'):
            wrong = copy.deepcopy(declaration)
            wrong['target']['unit'] = unit
            self.assertFalse(fixture.audit(fixture.fixture(wrong))['accepted'], unit)

    def test_original_library_carriers_and_abis_remain_distinct(self):
        fixture = OriginalForeignAuditFixture()
        for resource in ('original-array-memcpy-descriptor.json', 'original-bytestring-strlen-descriptor.json'):
            with self.subTest(resource=resource):
                declaration = json.loads((ROOT.parent / 'src/test/resources/core' / resource).read_text())
                result = fixture.audit(fixture.fixture(declaration))
                self.assertTrue(result['accepted'], result)
                self.assertEqual([declaration['target']['symbol']], [call['symbol'] for call in result['foreignCalls']])
                for unit in ('other', 'ghc-internal'):
                    wrong = copy.deepcopy(declaration)
                    wrong['target']['unit'] = unit
                    self.assertFalse(fixture.audit(fixture.fixture(wrong))['accepted'])


class OriginalMemcpyDeclarationTest(unittest.TestCase):
    def test_original_ram_pointer_abi_and_installed_unit_authority(self):
        declaration = json.loads((ROOT.parent / 'src/test/resources/core/original-ram-memcpy-descriptor.json').read_text())
        arrays = json.loads((ROOT.parent / 'src/test/resources/core/original-array-memcpy-descriptor.json').read_text())
        fixture = OriginalForeignAuditFixture()
        for unit in (declaration['target']['unit'], 'ram-0.22.1', 'ram-0.22.1-inplace', 'ram-0.22.1-aB123'):
            installed = copy.deepcopy(declaration)
            installed['target']['unit'] = unit
            report = fixture.audit(fixture.fixture(installed))
            self.assertTrue(report['accepted'], report)
            self.assertEqual(['memcpy'], [call['symbol'] for call in report['foreignCalls']])
            disabled = dict(CAP, managedForeignCalls=[s for s in CAP['managedForeignCalls'] if s != 'memcpy'])
            self.assertFalse(fixture.audit(fixture.fixture(installed), disabled)['accepted'])
        for unit in (None, ['ram-0.22.1'], 'other-0.22.1', 'ram-0.22.0', 'ram-0.22.1-',
                     'ram-0.22.1-a-b', 'ram-0.22.1 hash', 'ram-0.22.1:hash', 'ram-0.22.1\n'):
            wrong = copy.deepcopy(declaration)
            wrong['target']['unit'] = unit
            self.assertFalse(fixture.audit(fixture.fixture(wrong))['accepted'], unit)
        for key, value in [('safety', 'safe'), ('convention', 'capi'), ('arity', 3), ('suppliedArity', 3),
                           ('resultRep', LONG), ('argumentReps', arrays['argumentReps'])]:
            wrong = copy.deepcopy(declaration)
            wrong[key] = value
            self.assertFalse(fixture.audit(fixture.fixture(wrong))['accepted'], key)
        for symbol in ('memmove', 'memcmp'):
            wrong = copy.deepcopy(declaration)
            wrong['target']['symbol'] = symbol
            self.assertFalse(fixture.audit(fixture.fixture(wrong))['accepted'], symbol)

    def test_exact_original_descriptor_and_checked_capability(self):
        resource = ROOT.parent / 'src/test/resources/core/original-memcpy-descriptor.json'
        declaration = json.loads(resource.read_text())
        self.assertEqual('memcpy', declaration['target']['symbol'])
        fixture = OriginalForeignAuditFixture()
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
        fixture = OriginalForeignAuditFixture()

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
    def test_original_compiler_host_way_family_has_exact_ownership_and_abi(self):
        declarations = json.loads((ROOT.parent / 'src/test/resources/core/original-ghc-host-ways-descriptors.json').read_text())
        self.assertEqual({'rts_isDynamic', 'rts_isProfiled', 'rts_isThreaded', 'rts_isDebugged', 'rts_isTracing'}, set(declarations))
        fixture = OriginalForeignAuditFixture()
        for symbol, declaration in declarations.items():
            with self.subTest(symbol=symbol):
                self.assertEqual('ghc-9.14.1-inplace', declaration['target']['unit'])
                result = fixture.audit(fixture.fixture(declaration))
                self.assertTrue(result['accepted'], result)
                self.assertEqual([symbol], [call['symbol'] for call in result['foreignCalls']])
                disabled = dict(CAP, managedForeignCalls=[s for s in CAP['managedForeignCalls'] if s != symbol])
                self.assertFalse(fixture.audit(fixture.fixture(declaration), disabled)['accepted'])
                for key, value in [('safety', 'safe'), ('arity', 2), ('argumentReps', []), ('resultRep', LONG),
                                   ('target', dict(declaration['target'], unit='other-compiler')),
                                   ('target', dict(declaration['target'], isFunction=False))]:
                    wrong = copy.deepcopy(declaration); wrong[key] = value
                    self.assertFalse(fixture.audit(fixture.fixture(wrong))['accepted'])

    def test_retained_posix_descriptors_and_capability(self):
        resource = ROOT.parent / 'src/test/resources/core/original-string-rts-descriptors.json'
        declarations = json.loads(resource.read_text())
        self.assertEqual({'strlen', 'rts_isThreaded'}, set(declarations))
        fixture = OriginalForeignAuditFixture()
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






class OriginalUnixLibcDeclarationTest(unittest.TestCase):
    def test_original_unix_units_keep_exact_abis_and_capabilities(self):
        fixture = OriginalForeignAuditFixture()
        declarations = json.loads((ROOT.parent / 'src/test/resources/core/original-unix-libc-descriptors.json').read_text())
        self.assertEqual({'close', 'dup', 'isatty', 'getenv'}, set(declarations))
        for symbol, declaration in declarations.items():
            with self.subTest(symbol=symbol):
                self.assertEqual('unix-2.8.8.0-inplace', declaration['target']['unit'])
                self.assertTrue(fixture.audit(fixture.fixture(declaration))['accepted'])
                disabled = dict(CAP, managedForeignCalls=[s for s in CAP['managedForeignCalls'] if s != symbol])
                self.assertFalse(fixture.audit(fixture.fixture(declaration), disabled)['accepted'])
                installed = copy.deepcopy(declaration); installed['target']['unit'] = 'unix-2.8.8.0-460b'
                self.assertTrue(fixture.audit(fixture.fixture(installed))['accepted'])
                for unit in ('unix', 'unix-2.8.8.1-inplace', 'unix-2.8.8.0-forged', 'unix-2.8.8.0',
                             'unix-2.8.8.0-', 'unix-2.8.8.0-460b-extra', 'other'):
                    wrong = copy.deepcopy(declaration); wrong['target']['unit'] = unit
                    self.assertFalse(fixture.audit(fixture.fixture(wrong))['accepted'])
                for key, value in (('safety', 'safe'), ('arity', 1), ('resultRep', LONG)):
                    wrong = copy.deepcopy(declaration); wrong[key] = value
                    self.assertFalse(fixture.audit(fixture.fixture(wrong))['accepted'])
                wrong = copy.deepcopy(declaration)
                wrong['argumentReps'][0]['primReps'] = ['WordRep']
                self.assertFalse(fixture.audit(fixture.fixture(wrong))['accepted'])
                wrong = copy.deepcopy(declaration)
                wrong['resultRep']['components'].reverse()
                self.assertFalse(fixture.audit(fixture.fixture(wrong))['accepted'])
        wrong = copy.deepcopy(declarations['getenv'])
        wrong['target']['symbol'] = 'putenv'
        self.assertFalse(fixture.audit(fixture.fixture(wrong))['accepted'])


class OriginalThreadIdentityDeclarationTest(unittest.TestCase):
    symbols = ('rts_getThreadId', 'eq_thread', 'cmp_thread')

    def test_context_owned_identity_abi_and_capability_controls(self):
        fixture = OriginalForeignAuditFixture()
        enabled = CAP
        for symbol in self.symbols:
            self.assertEqual(1, CAP['managedForeignCalls'].count(symbol))
        scalar = lambda rep, evaluated=True: dict(kind=core_original_foreign.scalar_kind(rep),
            primReps=[] if rep is None else [rep], evaluated=evaluated)
        for symbol, count, output in (('rts_getThreadId', 1, 'Word64Rep'), ('eq_thread', 2, 'Word8Rep'), ('cmp_thread', 2, 'Int32Rep')):
            arguments = ['BoxedRep (Just Unlifted)'] * count + [None]
            declaration = dict(schema=1, target=dict(kind='static', symbol=symbol, unit='ghc-internal', isFunction=True),
                convention='ccall', safety='unsafe', arity=len(arguments), suppliedArity=len(arguments),
                argumentReps=[scalar(rep, False) for rep in arguments], resultRep=dict(kind='unknown', primReps=[output],
                    evaluated=False, aggregate='unboxed-tuple', components=[scalar(None), scalar(output)]))
            self.assertTrue(fixture.audit(fixture.fixture(declaration), enabled)['accepted'])
            disabled = dict(enabled, managedForeignCalls=[name for name in enabled['managedForeignCalls'] if name != symbol])
            self.assertFalse(fixture.audit(fixture.fixture(declaration), disabled)['accepted'])
            for key, value in (('safety', 'safe'), ('convention', 'capi'), ('arity', 0), ('suppliedArity', 1), ('schema', 1.0)):
                wrong = copy.deepcopy(declaration); wrong[key] = value
                self.assertFalse(fixture.audit(fixture.fixture(wrong), enabled)['accepted'])
            for unit in ('main', 'ghc-prim', 'ghc-internal-9.1401.0-inplace'):
                wrong = copy.deepcopy(declaration); wrong['target']['unit'] = unit
                self.assertFalse(fixture.audit(fixture.fixture(wrong), enabled)['accepted'])
            for carrier in ('AddrRep', 'BoxedRep (Just Lifted)', 'BoxedRep Nothing'):
                wrong = copy.deepcopy(declaration); wrong['argumentReps'][0] = scalar(carrier, False)
                self.assertFalse(fixture.audit(fixture.fixture(wrong), enabled)['accepted'])
        for symbol in ('enableAllocationLimit', 'disableAllocationLimit', 'prefixeq_thread', 'cmp_thread2'):
            self.assertNotIn(symbol, core_original_foreign.OPERATIONS)

    def test_genuine_original_call_inventory(self):
        source = os.environ.get('THC_TEST_THREAD_ID_CBD')
        if not source:
            self.skipTest('requires genuine original-unit stock import proof')
        data = Path(source).read_bytes()
        self.assertEqual(os.environ.get('THC_TEST_THREAD_ID_CBD_SHA256'), hashlib.sha256(data).hexdigest())
        module = core_package_manifest.inspect_cbd(data)
        self.assertEqual('ghc-internal', module['unit'])
        self.assertEqual('GHC.Internal.Conc.Sync', module['module'])
        proof = module['staticForeignImports']
        self.assertEqual('verified', proof['status'])
        fixture = OriginalForeignAuditFixture()
        for symbol in self.symbols:
            declaration = next(call for call in proof['expectedCalls'] if call['target'].get('symbol') == symbol)
            self.assertTrue(fixture.audit(fixture.fixture(declaration))['accepted'])


class OriginalEnvironmentDeclarationTest(unittest.TestCase):
    def test_environment_abi_and_capability_controls(self):
        fixture = OriginalForeignAuditFixture()
        scalar = lambda rep, evaluated=True: dict(kind=core_original_foreign.scalar_kind(rep),
            primReps=[] if rep is None else [rep], evaluated=evaluated)
        for symbol, arguments, output in (
                ('getenv', ('AddrRep', None), 'AddrRep'),
                ('putenv', ('AddrRep', None), 'Int32Rep'),
                ('__hsbase_unsetenv', ('AddrRep', None), 'Int32Rep'),
                ('__hscore_environ', (None,), 'AddrRep')):
            declaration = dict(schema=1, target=dict(kind='static', symbol=symbol,
                unit='ghc-internal', isFunction=True), convention='ccall', safety='unsafe',
                arity=len(arguments), suppliedArity=len(arguments),
                argumentReps=[scalar(rep, False) for rep in arguments],
                resultRep=dict(kind='unknown', primReps=[output], evaluated=False,
                    aggregate='unboxed-tuple', components=[scalar(None), scalar(output)]))
            module = fixture.fixture(declaration)
            self.assertTrue(fixture.audit(module)['accepted'])
            disabled = dict(CAP, managedForeignCalls=[s for s in CAP['managedForeignCalls'] if s != symbol])
            self.assertFalse(fixture.audit(module, disabled)['accepted'])
            for key, value in (('safety', 'safe'), ('arity', 0), ('resultRep', LONG)):
                wrong = copy.deepcopy(declaration); wrong[key] = value
                self.assertFalse(fixture.audit(fixture.fixture(wrong))['accepted'])
            wrong = copy.deepcopy(declaration); wrong['target']['unit'] = 'other'
            self.assertFalse(fixture.audit(fixture.fixture(wrong))['accepted'])


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
    reviewed_resource = ROOT.parent / 't/fixtures/compiler/OriginalStackProof.json'

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
    """Synthetic structural controls; genuine native/pre/post proof is Haskell/Java.

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
        'integer_gmp_mpn_rshift': ((array, array, 'IntRep', 'WordRep', None), 'WordRep'),
        'integer_gmp_mpn_rshift_2c': ((array, array, 'IntRep', 'WordRep', None), 'WordRep'),
        'integer_gmp_mpn_get_d': ((array, 'IntRep', 'IntRep', None), 'DoubleRep'),
        '__int_encodeDouble': (('IntRep', 'IntRep', None), 'DoubleRep'),
        'integer_gmp_gcd_word': (('WordRep', 'WordRep', None), 'WordRep'),
        'integer_gmp_mpn_gcd_1': ((array, 'IntRep', 'WordRep', None), 'WordRep'),
        'integer_gmp_mpn_gcd': ((array, array, 'IntRep', array, 'IntRep', None), 'IntRep'),
        'integer_gmp_mpn_lshift': ((array, array, 'IntRep', 'WordRep', None), 'WordRep'),
        'integer_gmp_mpn_and_n': ((array, array, array, 'IntRep', None), None),
        'integer_gmp_mpn_andn_n': ((array, array, array, 'IntRep', None), None),
        'integer_gmp_mpn_ior_n': ((array, array, array, 'IntRep', None), None),
        'integer_gmp_mpn_xor_n': ((array, array, array, 'IntRep', None), None),
        '__gmpn_popcount': ((array, 'IntRep', None), 'WordRep'),
    }

    def fixture(self, symbol):
        def scalar(primitive, evaluated):
            return dict(kind='void' if primitive is None else 'object' if primitive == self.array else 'double' if primitive == 'DoubleRep' else 'long',
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

    def test_exact_twenty_four_shapes_require_explicit_capability(self):
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
    event_descriptors = {
        'eventfd': (('Int32Rep', 'Int32Rep', None), 'Int32Rep'),
        'eventfd_write': (('Int32Rep', 'Word64Rep', None), 'Int32Rep'),
        'pipe': (('AddrRep', None), 'Int32Rep'),
    }
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
    process_identities = {'getpid': ((None,), 'Int32Rep'), 'geteuid': ((None,), 'Word32Rep')}
    errno = {'__hscore_set_errno': (('Int32Rep', None), None)}
    open_flags = {f'__hscore_o_{name}': ((None,), 'Int32Rep') for name in ('excl', 'binary', 'trunc')}
    symbols = (core_original_foreign.TCSETATTR_SYMBOL, core_original_foreign.TCGETATTR_SYMBOL, 'dup', 'dup2', '__hscore_fstat', '__hscore_open', 'lockFile', 'unlockFile', *termios, *event_descriptors, *open_flags, *errno, *process_identities)
    def fixture(self, symbol):
        arguments = (('Int32Rep', 'Int32Rep', 'AddrRep', None) if symbol == core_original_foreign.TCSETATTR_SYMBOL else
                     ('Word64Rep', 'Word64Rep', 'Word64Rep', 'Int32Rep', None) if symbol == 'lockFile' else
                     ('Word64Rep', None) if symbol == 'unlockFile' else
                     ('Int32Rep', 'AddrRep', None) if symbol in ('__hscore_fstat', core_original_foreign.TCGETATTR_SYMBOL) else
                     ('AddrRep', 'Int32Rep', 'Word32Rep', None) if symbol == '__hscore_open' else
                     ('Int32Rep', None) if symbol == 'dup' else ('Int32Rep', 'Int32Rep', None))
        arguments, output = (self.termios | self.event_descriptors | self.open_flags | self.errno | self.process_identities).get(symbol, (arguments, 'Int32Rep'))
        scalar = lambda rep, evaluated: dict(kind='void' if rep is None else 'address' if rep == 'AddrRep' else 'long',
            primReps=[] if rep is None else [rep], evaluated=evaluated)
        parameters = [dict(id=f'a{i}', lifted=False, rep=scalar(p, True)) for i, p in enumerate(arguments)]
        result = tuple_rep(*(scalar(rep, True) for rep in ((None,) if output is None else (None, output))))
        result['evaluated'] = False
        descriptor = dict(schema=1, target=dict(kind='static', symbol=symbol,
            unit='unix-2.8.8.0-inplace' if symbol == 'geteuid' else 'ghc-internal', isFunction=True),
            convention='capi' if symbol in (core_original_foreign.TCGETATTR_SYMBOL, core_original_foreign.TCSETATTR_SYMBOL) else 'ccall', safety='unsafe', arity=len(arguments), suppliedArity=len(arguments),
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

    def test_interruptible_open_uses_owned_transport_before_package_adapter(self):
        module = self.fixture('__hscore_open')
        call = self.call(module)
        call[6]['foreignCall']['safety'] = 'interruptible'
        ownership = core_original_foreign.ForeignOwnership([call[6]['foreignCall']])
        archive = dict(unclassifiedReason=None, unresolvedSymbols=[], unsupportedImports=[
            dict(symbol='__hscore_open', convention='ccall', safety='interruptible')])
        self.assertFalse(core_package_manifest.native_archive_blocks(dict(unit='ghc-internal'),
            module['bindings'][0], archive, ownership))
        for malformed in (False, True):
            candidate = copy.deepcopy(module)
            if malformed:
                self.call(candidate)[6]['foreignCall']['argumentReps'][0]['primReps'] = ['IntRep']
            auditor = audit_core.Audit([('open-control.json', candidate)], CAP)
            auditor.package_scalar_links['ghc-internal'] = dict(unit='ghc-internal', abi=[])
            auditor.foreign_ownership = ownership
            report = auditor.run(['root'])
            with self.subTest(malformed=malformed):
                self.assertEqual(not malformed, report['accepted'], report['issues'])
                if malformed:
                    self.assertIn('foreign-call', {issue['code'] for issue in report['issues']})

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

    def test_unix_pipe_and_dup2_preserve_original_owner(self):
        for symbol in ('pipe', 'dup2'):
            for unit in ('unix-2.8.8.0-inplace', 'unix-2.8.8.0-0123abcdef'):
                module = self.fixture(symbol)
                self.call(module)[6]['foreignCall']['target']['unit'] = unit
                self.assertTrue(self.audit(module)['accepted'], (symbol, unit))
            for unit in ('main', 'unix-2.8.7.0-inplace', 'unix-2.8.8.0-not-a-hash'):
                module = self.fixture(symbol)
                self.call(module)[6]['foreignCall']['target']['unit'] = unit
                self.assertFalse(self.audit(module)['accepted'], (symbol, unit))

    def test_process_identity_keeps_exact_owner_and_signedness(self):
        for symbol, expected in (('getpid', 'Int32Rep'), ('geteuid', 'Word32Rep')):
            for unit in ('unix-2.8.8.0-inplace', 'unix-2.8.8.0-460b', 'unix-2.8.8.0-deadbeef'):
                module = self.fixture(symbol)
                self.call(module)[6]['foreignCall']['target']['unit'] = unit
                self.assertTrue(self.audit(module)['accepted'], (symbol, unit))
            for unit in ('main', 'unix-2.8.7.0-inplace', 'ghc-internal-9.1401.0-inplace',
                         'unix-2.8.8.0-', 'unix-2.8.8.0-ABCD', 'unix-2.8.8.0-xyz',
                         'unix-2.8.8.0-inplace\n', 'unix-2.8.8.0-460b:forged',
                         *(['ghc-internal'] if symbol == 'geteuid' else [])):
                module = self.fixture(symbol)
                self.call(module)[6]['foreignCall']['target']['unit'] = unit
                self.assertFalse(self.audit(module)['accepted'], (symbol, unit))
            for wrong in ('IntRep', 'WordRep', 'Int32Rep', 'Word32Rep'):
                if wrong == expected: continue
                module = self.fixture(symbol); meta = self.call(module)[6]
                for result in (meta['rep'], meta['foreignCall']['resultRep']):
                    result['primReps'] = [wrong]
                    result['components'][1]['primReps'] = [wrong]
                self.assertFalse(self.audit(module)['accepted'], (symbol, wrong))

    def test_errno_setter_keeps_owner_and_singleton_state_result(self):

        symbol = '__hscore_set_errno'
        for unit in ('main', 'unix-2.8.8.0-inplace', 'ghc-internal-9.1401.0-inplace'):
            module = self.fixture(symbol)
            self.call(module)[6]['foreignCall']['target']['unit'] = unit
            self.assertFalse(self.audit(module)['accepted'])
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

    def test_original_open_flags_keep_ghc_owner_and_cint_result(self):

        for symbol in self.open_flags:
            for unit in ('main', 'unix-2.8.8.0-inplace', 'ghc-internal-9.1401.0-inplace'):
                module = self.fixture(symbol)
                self.call(module)[6]['foreignCall']['target']['unit'] = unit
                self.assertFalse(self.audit(module)['accepted'], (symbol, unit))
            for wrong in ('IntRep', 'Word32Rep', 'WordRep'):
                module = self.fixture(symbol); meta = self.call(module)[6]
                for result in (meta['rep'], meta['foreignCall']['resultRep']):
                    result['primReps'] = [wrong]
                    result['components'][1]['primReps'] = [wrong]
                self.assertFalse(self.audit(module)['accepted'], (symbol, wrong))
            module = self.fixture(symbol)
            # The declared State proof cannot hide an integer-producing operand.
            self.call(module)[2][0] = [*lit(9), self.call(module)[2][0][2]]
            self.assertFalse(self.audit(module)['accepted'], symbol)

    def test_original_open_three_exact_safety_contracts(self):
        for mode in ('Word16Rep', 'Word32Rep', 'Word8Rep', 'Int16Rep', 'Int32Rep', 'Word64Rep'):
            for safety in ('unsafe', 'safe', 'interruptible'):
                with self.subTest(mode=mode, safety=safety):
                    module = self.fixture('__hscore_open')
                    call = self.call(module)
                    call[6]['foreignCall']['safety'] = safety
                    for proof in (call[6]['foreignCall']['argumentReps'][2], call[2][2][2]['rep'],
                                  module['bindings'][0]['expr'][1][2]['rep']):
                        proof['primReps'] = [mode]
                    self.assertEqual(mode in ('Word16Rep', 'Word32Rep'), self.audit(module)['accepted'])
                    self.assertFalse(self.audit(module, dict(CAP, managedForeignCalls=[]))['accepted'])

    def test_descriptor_flags_head_and_raw_representation_forgery_reject(self):
        for symbol in self.symbols:
            mutations = [(key, value) for key in ('schema', 'arity', 'suppliedArity')
                for value in (None, True, 2.0, '2', 0, 1 << 32)] + [('convention', 'ccall' if symbol in (core_original_foreign.TCGETATTR_SYMBOL, core_original_foreign.TCSETATTR_SYMBOL) else 'capi'), ('safety', 'unknown'), ('extra', None)] + ([] if symbol == '__hscore_open' else [('safety', 'safe'), ('safety', 'interruptible')])
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
        for symbol, (_, output) in (self.termios).items():
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
                       'prefix__hscore_sigttou', 'prefix__hscore_sizeof_sigset_t', 'prefix__hscore_get_saved_termios', 'prefix__hscore_set_saved_termios'):
            self.assertNotIn(symbol, core_original_foreign.OPERATIONS)
            module = self.fixture('__hscore_lflag')
            self.call(module)[6]['foreignCall']['target']['symbol'] = symbol
            self.assertFalse(self.audit(module)['accepted'])


class OriginalPathStatDeclarationTest(unittest.TestCase):
    """Synthetic rejection controls; Haskell fixtures retain the original FCallIds."""
    unix_symbol = 'ghczuwrapperZC2ZCunixzm2zi8zi8zi0zminplaceZCSystemziPosixziFilesziPosixStringZClstat'
    symbols = ('__hscore_stat', '__hscore_lstat', unix_symbol)

    def declaration(self, symbol):
        state = dict(kind='void', primReps=[], evaluated=True)
        address = dict(kind='address', primReps=['AddrRep'], evaluated=True)
        result = dict(kind='long', primReps=['Int32Rep'], evaluated=True)
        return dict(schema=1, target=dict(kind='static', symbol=symbol, isFunction=True,
            unit='unix-2.8.8.0-inplace' if symbol == self.unix_symbol else 'ghc-internal'),
            convention='capi' if symbol == self.unix_symbol else 'ccall', safety='unsafe', arity=3, suppliedArity=3,
            argumentReps=[dict(rep, evaluated=False) for rep in (address, address, state)],
            resultRep=dict(tuple_rep(state, result), evaluated=False))

    def test_exact_original_path_stat_declarations_require_capability(self):
        fixture = OriginalForeignAuditFixture()
        for symbol in self.symbols:
            module = fixture.fixture(self.declaration(symbol))
            report = fixture.audit(module)
            self.assertTrue(report['accepted'], report)
            self.assertEqual([symbol], [call['symbol'] for call in report['foreignCalls']])
            self.assertFalse(fixture.audit(module, dict(CAP, managedForeignCalls=[]))['accepted'])

    def test_path_stat_owner_abi_and_actual_stored_operands_reject_spoofs(self):
        fixture = OriginalForeignAuditFixture()
        for symbol in self.symbols:
            declaration = self.declaration(symbol)
            for key, value in (('convention', 'ccall' if symbol == self.unix_symbol else 'capi'),
                               ('safety', 'safe'), ('arity', 2), ('suppliedArity', 2), ('resultRep', LONG)):
                wrong = copy.deepcopy(declaration); wrong[key] = value
                self.assertFalse(fixture.audit(fixture.fixture(wrong))['accepted'], (symbol, key))
            for unit in ('main', 'unix-2.8.7.0-inplace', 'unix-2.8.8.0-abcd',
                         'ghc-internal' if symbol == self.unix_symbol else 'unix-2.8.8.0-inplace'):
                wrong = copy.deepcopy(declaration); wrong['target']['unit'] = unit
                self.assertFalse(fixture.audit(fixture.fixture(wrong))['accepted'], (symbol, unit))
            for index in range(3):
                module = fixture.fixture(declaration)
                module['bindings'][0]['expr'][1][index]['rep'] = LONG
                self.assertFalse(fixture.audit(module)['accepted'], (symbol, index, 'stored'))
                module = fixture.fixture(declaration)
                call = module['bindings'][0]['expr'][2][1]
                call[2][index] = [*lit(9), call[2][index][2]]
                self.assertFalse(fixture.audit(module)['accepted'], (symbol, index, 'producer'))

    def test_installed_lstat_wrapper_keeps_original_symbol_and_exact_owner(self):
        fixture = OriginalForeignAuditFixture()
        for suffix in ('inplace', '460b', 'deadbeef'):
            declaration = self.declaration(self.unix_symbol)
            symbol = self.unix_symbol.replace('zminplaceZC', 'zm' + suffix + 'ZC')
            declaration['target'].update(unit='unix-2.8.8.0-' + suffix, symbol=symbol)
            module = fixture.fixture(declaration)
            report = fixture.audit(module)
            self.assertTrue(report['accepted'], report)
            self.assertEqual([symbol], [call['symbol'] for call in report['foreignCalls']])
            self.assertFalse(fixture.audit(module, dict(CAP, managedForeignCalls=[]))['accepted'])
            for owner in ('unix-2.8.8.0-' + ('460b' if suffix == 'inplace' else 'inplace'),
                          'unix-2.8.7.0-' + suffix, 'unix-2.8.8.0-' + suffix + ':forged',
                          'unix-2.8.8.0-' + suffix + '\n', 'unix-2.8.8.0-ABCD', 'unix-2.8.8.0-'):
                wrong = copy.deepcopy(declaration); wrong['target']['unit'] = owner
                self.assertFalse(fixture.audit(fixture.fixture(wrong))['accepted'], owner)
            for wrong_symbol in (symbol.replace('ZC2ZC', 'ZC3ZC'), symbol.replace('ZClstat', 'ZCstat'),
                                 symbol.replace('FilesziPosixString', 'FilesziUnknown'),
                                 symbol.replace('unixzm2zi8zi8zi0', 'unixzm2zi8zi7zi0')):
                wrong = copy.deepcopy(declaration); wrong['target']['symbol'] = wrong_symbol
                self.assertFalse(fixture.audit(fixture.fixture(wrong))['accepted'], wrong_symbol)
            for key, value in (('convention', 'ccall'), ('safety', 'safe'), ('arity', 2)):
                wrong = copy.deepcopy(declaration); wrong[key] = value
                self.assertFalse(fixture.audit(fixture.fixture(wrong))['accepted'], key)


class OriginalPathModeDeclarationTest(unittest.TestCase):
    """Synthetic rejection controls; Haskell fixtures retain the original FCallIds."""
    unix_symbol = 'mkdir'
    symbols = (unix_symbol, 'chmod')

    def declaration(self, symbol):
        state = dict(kind='void', primReps=[], evaluated=True)
        address = dict(kind='address', primReps=['AddrRep'], evaluated=True)
        result = dict(kind='long', primReps=['Int32Rep'], evaluated=True)
        mode = dict(kind='long', primReps=['Word32Rep'], evaluated=True)
        return dict(schema=1, target=dict(kind='static', symbol=symbol, isFunction=True,
            unit='unix-2.8.8.0-inplace' if symbol == self.unix_symbol else 'ghc-internal'),
            convention='ccall', safety='unsafe', arity=3, suppliedArity=3,
            argumentReps=[dict(rep, evaluated=False) for rep in (address, mode, state)],
            resultRep=dict(tuple_rep(state, result), evaluated=False))

    def test_exact_original_path_mode_declarations_require_capability(self):
        fixture = OriginalForeignAuditFixture()
        for symbol in self.symbols:
            module = fixture.fixture(self.declaration(symbol))
            report = fixture.audit(module)
            self.assertTrue(report['accepted'], report)
            self.assertEqual([symbol], [call['symbol'] for call in report['foreignCalls']])
            self.assertFalse(fixture.audit(module, dict(CAP, managedForeignCalls=[]))['accepted'])
        for unit in ('unix-2.8.8.0-460b', 'unix-2.8.8.0-deadbeef'):
            declaration = self.declaration('mkdir'); declaration['target']['unit'] = unit
            self.assertTrue(fixture.audit(fixture.fixture(declaration))['accepted'], unit)

    def test_path_mode_owner_abi_and_actual_stored_operands_reject_spoofs(self):
        fixture = OriginalForeignAuditFixture()
        for symbol in self.symbols:
            declaration = self.declaration(symbol)
            for key, value in (('convention', 'capi'),
                               ('safety', 'safe'), ('arity', 2), ('suppliedArity', 2), ('resultRep', LONG)):
                wrong = copy.deepcopy(declaration); wrong[key] = value
                self.assertFalse(fixture.audit(fixture.fixture(wrong))['accepted'], (symbol, key))
            for unit in ('main', 'unix-2.8.7.0-inplace', 'unix-2.8.8.0-ABCD',
                         'unix-2.8.8.0-', 'unix-2.8.8.0-inplace\n', 'unix-2.8.8.0-460b:forged',
                         'ghc-internal' if symbol == self.unix_symbol else 'unix-2.8.8.0-inplace'):
                wrong = copy.deepcopy(declaration); wrong['target']['unit'] = unit
                self.assertFalse(fixture.audit(fixture.fixture(wrong))['accepted'], (symbol, unit))
            for index in range(3):
                module = fixture.fixture(declaration)
                module['bindings'][0]['expr'][1][index]['rep'] = LONG
                self.assertFalse(fixture.audit(module)['accepted'], (symbol, index, 'stored'))
                module = fixture.fixture(declaration)
                call = module['bindings'][0]['expr'][2][1]
                producer = ['lit', 'string-bytes', '41'] if index == 1 else lit(9)
                call[2][index] = [*producer, call[2][index][2]]
                self.assertFalse(fixture.audit(module)['accepted'], (symbol, index, 'producer'))



class OriginalFstatAtDeclarationTest(unittest.TestCase):
    """Exact original wrapper controls, separate from genuine native Id fixtures."""

    symbol = 'ghczuwrapperZC1ZCdirectoryzm1zi3zi10zi0zminplaceZCSystemziDirectoryziInternalziPosixZCfstatat'

    def declaration(self, suffix='inplace'):
        state = dict(kind='void', primReps=[], evaluated=True)
        address = dict(kind='address', primReps=['AddrRep'], evaluated=True)
        integer = dict(kind='long', primReps=['Int32Rep'], evaluated=True)
        return dict(schema=1, target=dict(kind='static',
            symbol=self.symbol.replace('zminplaceZC', 'zm' + suffix + 'ZC'),
            isFunction=True, unit='directory-1.3.10.0-' + suffix), convention='capi', safety='safe',
            arity=5, suppliedArity=5,
            argumentReps=[dict(rep, evaluated=False) for rep in (integer, address, address, integer, state)],
            resultRep=dict(tuple_rep(state, integer), evaluated=False))

    def test_exact_fstatat_owner_wrapper_and_safe_cint_abi(self):
        fixture = OriginalForeignAuditFixture()
        for suffix in ('inplace', '02fc'):
            declaration = self.declaration(suffix)
            report = fixture.audit(fixture.fixture(declaration))
            self.assertTrue(report['accepted'], report)
            self.assertFalse(fixture.audit(fixture.fixture(declaration), dict(CAP, managedForeignCalls=[]))['accepted'])
            for unit in ('ghc-internal', 'main', 'unix-2.8.8.0-inplace', 'directory-1.3.9.0-' + suffix,
                         'directory-1.3.10.0-', 'directory-1.3.10.0-02FC', 'directory-1.3.10.0-02fc:forged',
                         'directory-1.3.10.0-' + ('02fc' if suffix == 'inplace' else 'inplace')):
                wrong = copy.deepcopy(declaration); wrong['target']['unit'] = unit
                self.assertFalse(fixture.audit(fixture.fixture(wrong))['accepted'], unit)
            for old, new in (('ZC1ZC', 'ZC2ZC'), ('InternalziPosix', 'Posix'), ('ZCfstatat', 'ZCfstat')):
                wrong = copy.deepcopy(declaration)
                wrong['target']['symbol'] = wrong['target']['symbol'].replace(old, new)
                self.assertFalse(fixture.audit(fixture.fixture(wrong))['accepted'])
            for key, value in (('convention', 'ccall'), ('safety', 'unsafe'), ('safety', 'interruptible'),
                               ('arity', 4), ('suppliedArity', 4), ('resultRep', LONG)):
                wrong = copy.deepcopy(declaration); wrong[key] = value
                self.assertFalse(fixture.audit(fixture.fixture(wrong))['accepted'])

    def test_fstatat_declared_stored_and_lowered_operands_are_checked(self):
        fixture = OriginalForeignAuditFixture()
        declaration = self.declaration()
        for index in (0, 3):
            for rep in ('IntRep', 'Word32Rep', 'Word64Rep'):
                wrong = copy.deepcopy(declaration); wrong['argumentReps'][index]['primReps'] = [rep]
                self.assertFalse(fixture.audit(fixture.fixture(wrong))['accepted'])
        for index in range(5):
            module = fixture.fixture(declaration)
            module['bindings'][0]['expr'][1][index]['rep'] = LONG
            self.assertFalse(fixture.audit(module)['accepted'], (index, 'stored'))
            module = fixture.fixture(declaration)
            call = module['bindings'][0]['expr'][2][1]
            producer = ['lit', 'string-bytes', '41'] if index in (0, 3) else lit(9)
            call[2][index] = [*producer, call[2][index][2]]
            self.assertFalse(fixture.audit(module)['accepted'], (index, 'producer'))


class OriginalUnlinkAtDeclarationTest(unittest.TestCase):
    """Synthetic rejection controls; native fixtures retain the actual directory Id."""

    def declaration(self):
        state = dict(kind='void', primReps=[], evaluated=True)
        address = dict(kind='address', primReps=['AddrRep'], evaluated=True)
        integer = dict(kind='long', primReps=['Int32Rep'], evaluated=True)
        return dict(schema=1, target=dict(kind='static', symbol='unlinkat', isFunction=True,
            unit='directory-1.3.10.0-inplace'), convention='ccall', safety='safe', arity=4, suppliedArity=4,
            argumentReps=[dict(rep, evaluated=False) for rep in (integer, address, integer, state)],
            resultRep=dict(tuple_rep(state, integer), evaluated=False))

    def test_exact_directory_owner_safe_call_and_cint_signature(self):
        fixture = OriginalForeignAuditFixture()
        for unit in ('directory-1.3.10.0-inplace', 'directory-1.3.10.0-02fc'):
            declaration = self.declaration(); declaration['target']['unit'] = unit
            report = fixture.audit(fixture.fixture(declaration))
            self.assertTrue(report['accepted'], report)
        declaration = self.declaration()
        for unit in ('ghc-internal', 'main', 'unix-2.8.8.0-inplace', 'directory-1.3.9.0-inplace',
                     'directory-1.3.10.0-', 'directory-1.3.10.0-ABCD', 'directory-1.3.10.0-02fc:forged',
                     'directory-1.3.10.0-inplace\n'):
            wrong = copy.deepcopy(declaration); wrong['target']['unit'] = unit
            self.assertFalse(fixture.audit(fixture.fixture(wrong))['accepted'], unit)
        for key, value in (('convention', 'capi'), ('safety', 'unsafe'), ('safety', 'interruptible'),
                           ('arity', 3), ('suppliedArity', 3), ('resultRep', LONG)):
            wrong = copy.deepcopy(declaration); wrong[key] = value
            self.assertFalse(fixture.audit(fixture.fixture(wrong))['accepted'], key)
        self.assertFalse(fixture.audit(fixture.fixture(declaration), dict(CAP, managedForeignCalls=[]))['accepted'])

    def test_unlinkat_declared_stored_and_lowered_operands_are_checked(self):
        fixture = OriginalForeignAuditFixture()
        declaration = self.declaration()
        for index in (0, 2):
            for rep in ('IntRep', 'Word32Rep', 'Word64Rep'):
                wrong = copy.deepcopy(declaration); wrong['argumentReps'][index]['primReps'] = [rep]
                self.assertFalse(fixture.audit(fixture.fixture(wrong))['accepted'])
        for index in range(4):
            module = fixture.fixture(declaration)
            module['bindings'][0]['expr'][1][index]['rep'] = LONG
            self.assertFalse(fixture.audit(module)['accepted'], (index, 'stored'))
            module = fixture.fixture(declaration)
            call = module['bindings'][0]['expr'][2][1]
            producer = ['lit', 'string-bytes', '41'] if index in (0, 2) else lit(9)
            call[2][index] = [*producer, call[2][index][2]]
            self.assertFalse(fixture.audit(module)['accepted'], (index, 'producer'))


class OriginalPathAccessDeclarationTest(unittest.TestCase):
    """Synthetic rejection controls; native fixtures preserve the original Id."""

    def declaration(self):
        state = dict(kind='void', primReps=[], evaluated=True)
        address = dict(kind='address', primReps=['AddrRep'], evaluated=True)
        integer = dict(kind='long', primReps=['Int32Rep'], evaluated=True)
        return dict(schema=1, target=dict(kind='static', symbol='access', isFunction=True, unit='ghc-internal'),
            convention='ccall', safety='unsafe', arity=3, suppliedArity=3,
            argumentReps=[dict(rep, evaluated=False) for rep in (address, integer, state)],
            resultRep=dict(tuple_rep(state, integer), evaluated=False))

    def test_exact_access_requires_capability_and_ghc_owner(self):
        fixture = OriginalForeignAuditFixture()
        module = fixture.fixture(self.declaration())
        report = fixture.audit(module)
        self.assertTrue(report['accepted'], report)
        self.assertEqual(['access'], [call['symbol'] for call in report['foreignCalls']])
        self.assertFalse(fixture.audit(module, dict(CAP, managedForeignCalls=[]))['accepted'])
        for unit in ('main', 'unix-2.8.8.0-inplace', 'unix-2.8.8.0-460b', 'ghc-internal-forged'):
            declaration = self.declaration(); declaration['target']['unit'] = unit
            self.assertFalse(fixture.audit(fixture.fixture(declaration))['accepted'], unit)

    def test_access_exact_signed_cint_and_state_contract(self):
        fixture = OriginalForeignAuditFixture()
        declaration = self.declaration()
        for key, value in (('convention', 'capi'), ('safety', 'safe'), ('arity', 2),
                           ('suppliedArity', 2), ('resultRep', LONG)):
            wrong = copy.deepcopy(declaration); wrong[key] = value
            self.assertFalse(fixture.audit(fixture.fixture(wrong))['accepted'], key)
        for rep in ('IntRep', 'Word32Rep', 'Word64Rep'):
            wrong = copy.deepcopy(declaration); wrong['argumentReps'][1]['primReps'] = [rep]
            self.assertFalse(fixture.audit(fixture.fixture(wrong))['accepted'], rep)
        for index in range(3):
            module = fixture.fixture(declaration)
            module['bindings'][0]['expr'][1][index]['rep'] = LONG
            self.assertFalse(fixture.audit(module)['accepted'], (index, 'stored'))
            module = fixture.fixture(declaration)
            call = module['bindings'][0]['expr'][2][1]
            producer = ['lit', 'string-bytes', '41'] if index == 1 else lit(9)
            call[2][index] = [*producer, call[2][index][2]]
            self.assertFalse(fixture.audit(module)['accepted'], (index, 'producer'))


class OriginalPathnameDeclarationTest(unittest.TestCase):
    """Synthetic negatives; genuine unchanged Id positives are Haskell fixtures."""

    def test_unix_native_family_uses_shared_declaration_contract(self):
        fixture = OriginalForeignAuditFixture()
        declarations = {**core_original_foreign.UNIX_NATIVE_OPERATIONS,
                        **core_original_foreign.UNIX_ENVIRONMENT_OPERATIONS}
        for symbol, (convention, safety, arguments, result) in declarations.items():
            rep = lambda value, evaluated: dict(kind='void' if value is None else 'address' if value == 'AddrRep' else 'long',
                primReps=[] if value is None else [value], evaluated=evaluated)
            declaration = dict(schema=1, target=dict(kind='static', symbol=symbol, unit='unix-2.8.8.0-inplace', isFunction=True),
                convention=convention, safety=safety, arity=len(arguments), suppliedArity=len(arguments),
                argumentReps=[rep(value, False) for value in arguments],
                resultRep=dict(tuple_rep(*(rep(value, True) for value in result)), evaluated=False))
            self.assertTrue(fixture.audit(fixture.fixture(declaration))['accepted'], symbol)
        # One representative owner, width and wrapper-alias check exercises the
        # shared admission boundary; libc behavior is tested by libc itself.
        symbol = 'ghczuwrapperZC0ZCunixzm2zi8zi8zi0zminplaceZCSystemziPosixziFilesziPosixStringZCtruncate'
        signature = declarations[symbol]
        declaration = dict(schema=1, target=dict(kind='static', symbol=symbol.replace('ziPosixString', 'ziByteString'),
            unit='unix-2.8.8.0-inplace', isFunction=True), convention='capi', safety='unsafe', arity=3, suppliedArity=3,
            argumentReps=[rep(value, False) for value in signature[2]],
            resultRep=dict(tuple_rep(*(rep(value, True) for value in signature[3])), evaluated=False))
        self.assertTrue(fixture.audit(fixture.fixture(declaration))['accepted'])
        wrong = copy.deepcopy(declaration); wrong['target']['unit'] = 'unix-2.8.8.0-460b'
        self.assertFalse(fixture.audit(fixture.fixture(wrong))['accepted'])
        wrong = copy.deepcopy(declaration); wrong['argumentReps'][1] = rep('Int32Rep', False)
        self.assertFalse(fixture.audit(fixture.fixture(wrong))['accepted'])

    def declaration(self, symbol):
        address = dict(kind='address', primReps=['AddrRep'], evaluated=False)
        state = dict(kind='void', primReps=[], evaluated=False)
        capacity = dict(kind='long', primReps=['Word64Rep'], evaluated=False)
        args = {'symlink': [address, address, state], 'rename': [address, address, state], 'readlink': [address, address, capacity, state],
                'rmdir': [address, state], 'chdir': [address, state], 'getcwd': [address, capacity, state]}[symbol]
        output = dict(address, evaluated=True) if symbol == 'getcwd' else dict(kind='long', primReps=['Int32Rep'], evaluated=True)
        return dict(schema=1, target=dict(kind='static', symbol=symbol, isFunction=True,
            unit='unix-2.8.8.0-inplace'), convention='ccall', safety='unsafe',
            arity=len(args), suppliedArity=len(args), argumentReps=args,
            resultRep=dict(tuple_rep(dict(state, evaluated=True), output), evaluated=False))

    def test_exact_original_pathname_signatures_and_result(self):
        fixture = OriginalForeignAuditFixture()
        for symbol in ('symlink', 'rename', 'readlink', 'chdir', 'getcwd', 'rmdir'):
            declaration = self.declaration(symbol)
            report = fixture.audit(fixture.fixture(declaration))
            self.assertTrue(report['accepted'], report)
            for unit in ('unix-2.8.8.0-460b', 'unix-2.8.8.0-deadbeef'):
                installed = copy.deepcopy(declaration); installed['target']['unit'] = unit
                self.assertTrue(fixture.audit(fixture.fixture(installed))['accepted'], unit)
            if symbol == 'readlink':
                installed = copy.deepcopy(declaration); installed['target']['unit'] = 'ghc-internal'
                self.assertTrue(fixture.audit(fixture.fixture(installed))['accepted'])
            for unit in ('main', 'unix-2.8.7.0-inplace', 'unix-2.8.8.0-ABCD',
                         'unix-2.8.8.0-', 'unix-2.8.8.0-inplace\n', 'unix-2.8.8.0-460b:forged'):
                wrong = copy.deepcopy(declaration); wrong['target']['unit'] = unit
                self.assertFalse(fixture.audit(fixture.fixture(wrong))['accepted'])
            if symbol != 'readlink':
                wrong = copy.deepcopy(declaration); wrong['target']['unit'] = 'ghc-internal'
                self.assertFalse(fixture.audit(fixture.fixture(wrong))['accepted'])
            for key, value in (('convention', 'capi'), ('safety', 'safe'), ('arity', declaration['arity'] + 1), ('suppliedArity', declaration['arity'] + 1)):
                wrong = copy.deepcopy(declaration); wrong[key] = value
                self.assertFalse(fixture.audit(fixture.fixture(wrong))['accepted'])
            wrong = copy.deepcopy(declaration)
            wrong['resultRep']['components'][1]['primReps'] = ['Int64Rep']
            wrong['resultRep']['primReps'] = ['Int64Rep']
            self.assertFalse(fixture.audit(fixture.fixture(wrong))['accepted'])
            self.assertFalse(fixture.audit(fixture.fixture(declaration), dict(CAP, managedForeignCalls=[]))['accepted'])

    def test_stored_and_lowered_pathname_operands_cannot_be_relabelled(self):
        fixture = OriginalForeignAuditFixture()
        for symbol in ('symlink', 'rename', 'readlink', 'chdir', 'getcwd', 'rmdir'):
            declaration = self.declaration(symbol)
            for index in range(declaration['arity']):
                module = fixture.fixture(declaration)
                module['bindings'][0]['expr'][1][index]['rep'] = LONG
                self.assertFalse(fixture.audit(module)['accepted'], (symbol, index, 'stored'))
                module = fixture.fixture(declaration)
                call = module['bindings'][0]['expr'][2][1]
                producer = ['lit', 'string-bytes', '41'] if (symbol == 'readlink' and index == 2 or symbol == 'getcwd' and index == 1) else lit(9)
                call[2][index] = [*producer, call[2][index][2]]
                self.assertFalse(fixture.audit(module)['accepted'], (symbol, index, 'lowered'))


class OriginalDirectoryStreamTest(unittest.TestCase):
    @staticmethod
    def declarations():
        def rep(value, evaluated):
            return dict(kind='void' if value is None else 'address' if value == 'AddrRep' else 'long',
                        primReps=[] if value is None else [value], evaluated=evaluated)
        for symbol, (convention, safety, arguments, result) in core_original_foreign.DIRECTORY_STREAM_OPERATIONS.items():
            yield dict(schema=1, target=dict(kind='static', symbol=symbol, unit='unix-2.8.8.0-inplace', isFunction=True),
                convention=convention, safety=safety, arity=len(arguments), suppliedArity=len(arguments),
                argumentReps=[rep(value, False) for value in arguments],
                resultRep=dict(tuple_rep(*(rep(value, True) for value in result)), evaluated=False))

    def test_installed_wrapper_owner_abi_and_safety_stay_exact(self):
        fixture = OriginalForeignAuditFixture()
        for declaration in self.declarations():
            self.assertTrue(fixture.audit(fixture.fixture(declaration))['accepted'], declaration)
            installed = copy.deepcopy(declaration)
            installed['target']['unit'] = 'unix-2.8.8.0-460b'
            installed['target']['symbol'] = installed['target']['symbol'].replace('zminplaceZC', 'zm460bZC')
            self.assertTrue(fixture.audit(fixture.fixture(installed))['accepted'], installed)
            for key, value in (('safety', 'safe'), ('suppliedArity', declaration['arity'] - 1)):
                wrong = copy.deepcopy(declaration); wrong[key] = value
                self.assertFalse(fixture.audit(fixture.fixture(wrong))['accepted'])
            for unit in ('ghc-internal', 'unix-2.8.9.0-inplace', 'unix-2.8.8.0-', 'unix-2.8.8.0-inplace\n'):
                wrong = copy.deepcopy(declaration); wrong['target']['unit'] = unit
                self.assertFalse(fixture.audit(fixture.fixture(wrong))['accepted'])
            if declaration['convention'] == 'capi':
                wrong = copy.deepcopy(installed); wrong['target']['unit'] = 'unix-2.8.8.0-abcd'
                self.assertFalse(fixture.audit(fixture.fixture(wrong))['accepted'])
            self.assertFalse(fixture.audit(fixture.fixture(declaration), dict(CAP, managedForeignCalls=[]))['accepted'])

    def test_output_and_state_operand_authority_cannot_be_relabelled(self):
        fixture = OriginalForeignAuditFixture()
        for declaration in self.declarations():
            for index in range(declaration['arity']):
                module = fixture.fixture(declaration)
                module['bindings'][0]['expr'][1][index]['rep'] = LONG
                self.assertFalse(fixture.audit(module)['accepted'])
            wrong = copy.deepcopy(declaration)
            wrong['resultRep']['components'][0] = LONG
            self.assertFalse(fixture.audit(fixture.fixture(wrong))['accepted'])


class OriginalRtsDiagnosticTest(unittest.TestCase):
    def test_exact_diagnostic_descriptors_and_malformed_contracts(self):
        state = dict(kind='void', primReps=[], evaluated=True)
        address = dict(kind='address', primReps=['AddrRep'], evaluated=True)
        thread = dict(kind='object', primReps=['BoxedRep (Just Unlifted)'], evaluated=True)
        for symbol, reps in (('reportStackOverflow', [thread, state]),
                             ('reportHeapOverflow', [state]), ('errorBelch2', [address, address, state]),
                             ('debugBelch2', [address, address, state])):
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
            self.assertEqual(1, CAP['managedForeignCalls'].count(symbol))
            self.assertEqual([symbol], [item['symbol'] for item in report['foreignCalls']])
            for key, value in (('safety', 'safe'), ('arity', 0), ('suppliedArity', len(reps) + 1),
                               ('convention', 'capi'), ('schema', True), ('resultRep', state)):
                malformed = copy.deepcopy(module)
                malformed['bindings'][0]['expr'][2][1][6]['foreignCall'][key] = value
                self.assertFalse(audit(malformed)['accepted'])
            for unit in ('main', 'ghc-internal-9.1401.0-inplace'):
                malformed = copy.deepcopy(module)
                malformed['bindings'][0]['expr'][2][1][6]['foreignCall']['target']['unit'] = unit
                self.assertFalse(audit(malformed)['accepted'])
            malformed = copy.deepcopy(module)
            malformed['bindings'][0]['expr'][1][-1]['rep'] = LONG
            self.assertFalse(audit(malformed)['accepted'])
            malformed = copy.deepcopy(module)
            malformed['bindings'][0]['expr'][2][1][2][-1] = [*lit(0), dict(rep=state)]
            self.assertFalse(audit(malformed)['accepted'])
            malformed = copy.deepcopy(module)
            malformed['bindings'][0]['expr'][2][1][1][1] = 'p0'
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
            # The unit's ordinary native component must not capture an RTS
            # override merely because both use ccall. An empty test ABI makes
            # accidental package selection fail deterministically.
            mixed = audit_core.Audit([('shutdown.json', module)], CAP)
            mixed.package_scalar_links['ghc-internal'] = dict(unit='ghc-internal', abi=[])
            mixed.foreign_ownership = core_original_foreign.ForeignOwnership(core_package_manifest.native_archive_calls(module))
            self.assertTrue(mixed.run(['root'])['accepted'])
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

    def test_original_capture_requires_exact_nominal_admission_in_both_auditors(self):
        def ty(module, name, *arguments, namespace='type'):
            return dict(kind='tycon', name=dict(unit='ghc-internal', module=module,
                occurrence=name, namespace=namespace), arguments=list(arguments))
        nominal = dict(kind='function', multiplicity=ty('GHC.Internal.Types', 'Many', namespace='data'),
            argument=ty('GHC.Internal.Prim', 'Weak#', ty('GHC.Internal.Types', 'Lifted', namespace='data'),
                        ty('GHC.Internal.Conc.Sync', 'ThreadId')),
            result=ty('GHC.Internal.Types', 'IO', ty('GHC.Internal.Tuple', 'Unit')))
        module = self.fixture(); module.update(schema=2, unit='ghc-internal', module='Captured')
        call = module['bindings'][0]['expr'][2][1][6]['foreignCall']
        emitted = dict(symbol='rts_setMainThread', unit='ghc-internal', convention='ccall', safety='unsafe',
            arguments=['BoxedRep (Just Unlifted)', 'void'], result=['void'])
        declaration = dict(binder=dict(unit='ghc-internal', module='Captured', occurrence='setter', namespace='value'),
            header=None, symbol='rts_setMainThread', unit='ghc-internal', isFunction=True, convention='ccall', safety='unsafe',
            declaredType=nominal, normalizedType=nominal, normalizationRole='representational', emitted=emitted)
        module['staticForeignImports'] = dict(schema=1, scope='retained-static-import-products', execution='not-linked',
            profile='ghc-9.14.1-thc-only-static-c-imports-v1', unit='ghc-internal', module='Captured', status='verified',
            wordBits=64, expectedForeign=dict(schema=1, execution='not-linked', files=[],
                stubs=dict(header='', source='test-only retained C product', initializers=[], finalizers=[])),
            imports=[declaration], expectedCalls=[call])
        alias = copy.deepcopy(declaration); alias['binder']['occurrence'] = 'setterAlias'
        module['staticForeignImports']['imports'].append(alias)
        module['foreign'] = copy.deepcopy(module['staticForeignImports']['expectedForeign'])
        module['packageNativeArchive'] = dict(schema=1, profile='thc-package-native-archive-v1', execution='not-linked',
            unit='ghc-internal', module='Captured', unsupportedImports=[emitted, emitted], unclassifiedReason=None,
            unresolvedSymbols=[], artifact=None)
        for indexed in (False, True):
            for mutation in (None, 'weak-payload', 'missing-call', 'missing-proof'):
                value = copy.deepcopy(module)
                if mutation == 'weak-payload':
                    for field in ('declaredType', 'normalizedType'):
                        value['staticForeignImports']['imports'][0][field]['argument']['arguments'][1] = ty('GHC.Internal.Types', 'Int')
                elif mutation == 'missing-call': value['staticForeignImports']['expectedCalls'] = []
                elif mutation == 'missing-proof':
                    value.pop('staticForeignImports'); value.pop('packageNativeArchive')
                with self.subTest(indexed=indexed, mutation=mutation), TemporaryDirectory() as temporary:
                    if indexed:
                        with audit_core.AuditStore(Path(temporary) / 'audit.sqlite', {}) as store:
                            instance = audit_core.Audit([('captured.cbd', value)], CAP, store=store)
                            if mutation is None: self.assertEqual(1, len(instance.boxed_foreign_calls[('ghc-internal', 'rts_setMainThread')]))
                            report = instance.run(['root'])
                            self.assertEqual(mutation is None, report['accepted'], list(report['issues']))
                    else:
                        instance = audit_core.Audit([('captured.cbd', value)], CAP)
                        if mutation is None: self.assertEqual(1, len(instance.boxed_foreign_calls[('ghc-internal', 'rts_setMainThread')]))
                        report = instance.run(['root'])
                        self.assertEqual(mutation is None, report['accepted'], report)

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
    def test_package_callback_selection_requires_one_proved_finalizer_entry(self):
        # Exercise label selection after inventory validation, independently of
        # the manifest tests for nominal callback types and component ownership.
        for mode in ('proved', 'unproved', 'ordinary-call', 'other-symbol', 'ambiguous'):
            with self.subTest(mode=mode):
                audit = audit_core.Audit([], CAP)
                link = dict(abi=[dict(symbol='package_cleanup', entry='owned_entry')],
                            finalizers=['owned_entry'])
                audit.package_scalar_links['owner'] = link
                audit.package_scalar_proofs['owner'] = {'owned_entry'}
                if mode == 'unproved':
                    audit.package_scalar_proofs['owner'].clear()
                elif mode == 'ordinary-call':
                    link['finalizers'].clear()
                elif mode == 'other-symbol':
                    link['abi'][0]['symbol'] = 'different_cleanup'
                elif mode == 'ambiguous':
                    audit.package_scalar_links['other'] = copy.deepcopy(link)
                    audit.package_scalar_proofs['other'] = {'owned_entry'}
                audit.literal('function-addr', 'package_cleanup', 'root', 'expr')
                self.assertEqual([] if mode in ('proved', 'other-symbol') else ['unsupported-literal'],
                                 [issue['code'] for issue in audit.issues])
                self.assertEqual(['package_cleanup'] if mode == 'other-symbol' else [],
                                 [item['symbol'] for item in audit.unresolved_native_symbols])

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

    def test_native_function_labels_record_unresolved_demand_frontier(self):
        for symbol in ('free', 'enabled_capabilities', 'notACallback'):
            module = self.fixture('addCFinalizerToWeak#')
            self.call(module)[2][0] = ['lit', 'function-addr', symbol,
                dict(rep=dict(kind='address', primReps=['AddrRep'], evaluated=True))]
            report = run_tuple(module)
            self.assertTrue(report['accepted'], (symbol, report))
            self.assertEqual([] if symbol == 'free' else [symbol],
                [item['symbol'] for item in report['unresolvedNativeSymbols']])
        module = self.fixture('addCFinalizerToWeak#')
        self.call(module)[2][0] = ['lit', 'data-addr', 'enabled_capabilities',
            dict(rep=dict(kind='address', primReps=['AddrRep'], evaluated=True))]
        self.assertFalse(run_tuple(module)['accepted'])
        for mutation in ('missing', 'kind', 'rep', 'aggregate'):
            module = self.fixture('addCFinalizerToWeak#')
            label = ['lit', 'function-addr', 'free',
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
    def test_native_function_frontier_rejects_bad_symbols_and_ambiguous_authority(self):
        label = ['lit', 'function-addr', 'missing_optional_native_function',
                 dict(rep=dict(kind='address', primReps=['AddrRep'], evaluated=True))]
        def audit(expr, ambiguous=False):
            module = dict(schema=1, ghc='9.14.1', bindings=[bind('root', expr)], constructors=[])
            auditor = audit_core.Audit([('fixture.json', module)], CAP)
            if ambiguous:
                for unit in ('first-unit', 'second-unit'):
                    auditor.package_scalar_links[unit] = dict(abi=[dict(symbol=label[2], entry='address')], dataSymbols=['address'])
            return auditor.run(['root'])
        report = audit(label)
        self.assertTrue(report['accepted'])
        self.assertEqual([label[2]], [item['symbol'] for item in report['unresolvedNativeSymbols']])
        self.assertFalse(audit(label, True)['accepted'])
        for value in ('', None, 3, 'bad\0symbol'):
            wrong = copy.deepcopy(label); wrong[2] = value
            self.assertFalse(audit(wrong)['accepted'], value)

    def test_native_data_requires_link_and_address_proof(self):
        label = ["lit", "data-addr", "hs_bytestring_lower_hex_table",
                 dict(rep=dict(kind="address", primReps=["AddrRep"], evaluated=True))]
        def linked(expr, ambiguous=False):
            module = dict(schema=1, ghc='9.14.1', bindings=[bind('root', expr)], constructors=[])
            audit = audit_core.Audit([('fixture.json', module)], CAP)
            link = dict(abi=[dict(symbol=label[2], entry='address')], dataSymbols=['address'])
            audit.package_scalar_links['declaring-unit'] = link
            if ambiguous: audit.package_scalar_links['another-unit'] = link
            return audit.run(['root'])
        self.assertFalse(run(label)["accepted"])
        self.assertTrue(linked(label)["accepted"])
        self.assertFalse(linked(label, ambiguous=True)["accepted"])
        for symbol in ("hs_bytestring_lower_hex_table_extra", "hs_bytestring_digit_pairs_table"):
            wrong = copy.deepcopy(label); wrong[2] = symbol
            self.assertFalse(linked(wrong)["accepted"])
        for rep in (dict(kind="address", primReps=["AddrRep"], evaluated=False),
                    dict(kind="long", primReps=["WordRep"], evaluated=True),
                    dict(kind="address", primReps=["AddrRep"], evaluated=True, aggregate="unboxed-tuple")):
            wrong = copy.deepcopy(label); wrong[3]["rep"] = rep
            self.assertFalse(linked(wrong)["accepted"])

    def test_rtsflags_label_still_requires_exact_evaluated_address_proof(self):
        label = ['lit', 'data-addr', 'RtsFlags',
                 dict(rep=dict(kind='address', primReps=['AddrRep'], evaluated=True))]
        self.assertTrue(run(label)['accepted'])
        self.assertFalse(run(label, cap=dict(CAP, dataLabels=[]))['accepted'])
        for symbol in ('RtsFlags_extra', 'rtsFlags'):
            wrong = copy.deepcopy(label); wrong[2] = symbol
            self.assertFalse(run(wrong)['accepted'])
        for rep in (dict(kind='address', primReps=['AddrRep'], evaluated=False),
                    dict(kind='long', primReps=['WordRep'], evaluated=True)):
            wrong = copy.deepcopy(label); wrong[3]['rep'] = rep
            self.assertFalse(run(wrong)['accepted'])

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

    def test_erased_stm_newtype_action_refines_only_the_same_lifted_object_carrier(self):
        for name in ('atomically#', 'catchRetry#', 'catchSTM#'):
            for index, role in enumerate(CAP['managedSTMPrimitives'][name]['arguments']):
                if role != 'action':
                    continue
                original = self.fixture(name)
                binder = original['bindings'][0]['expr'][1][index]
                binder['rep'] = dict(kind='object', primReps=['BoxedRep (Just Lifted)'], evaluated=False)
                report = audit_core.Audit([('newtype-stm.json', original)], CAP).run(['root'])
                self.assertTrue(report['accepted'], (name, index, report['issues']))
                for wrong in (LONG, dict(kind='float', primReps=['FloatRep'], evaluated=True),
                              dict(kind='address', primReps=['AddrRep'], evaluated=True),
                              dict(kind='void', primReps=[], evaluated=True),
                              dict(kind='object', primReps=['BoxedRep (Just Unlifted)'], evaluated=True),
                              dict(kind='closure', primReps=['BoxedRep (Just Unlifted)'], evaluated=True),
                              dict(kind='data', primReps=['BoxedRep (Just Lifted)'], evaluated=True),
                              dict(kind='unknown', aggregate='unboxed-tuple', components=[REFERENCE],
                                   primReps=['BoxedRep (Just Lifted)'], evaluated=True)):
                    for site in ('binding', 'occurrence'):
                        changed = copy.deepcopy(original)
                        target = (changed['bindings'][0]['expr'][1][index] if site == 'binding' else
                                  changed['bindings'][0]['expr'][2][1][2][index][2])
                        target['rep'] = wrong
                        bad = audit_core.Audit([('bad-newtype-stm.json', changed)], CAP).run(['root'])
                        self.assertFalse(bad['accepted'], (name, index, site, wrong))
                # A stored object does not grant a missing function occurrence.
                changed = copy.deepcopy(original)
                changed['bindings'][0]['expr'][2][1][2][index][2]['rep'] = copy.deepcopy(binder['rep'])
                self.assertFalse(audit_core.Audit([('bad-action.json', changed)], CAP).run(['root'])['accepted'])


class OriginalTextForeignAuditTests(unittest.TestCase):
    def fixture(self, symbol):
        arguments = ('BoxedRep (Just Unlifted)', 'BoxedRep (Just Unlifted)' if symbol == '_hs_text_reverse' else 'Word64Rep', 'Word64Rep',
                     'Word8Rep' if symbol == '_hs_text_memchr' else 'Word64Rep', None)
        def scalar(rep, evaluated=True):
            return dict(kind='void' if rep is None else 'object' if rep.startswith('BoxedRep') else 'long',
                        primReps=[] if rep is None else [rep], evaluated=evaluated)
        parameters = [dict(id=f'a{i}', lifted=False, rep=scalar(rep)) for i, rep in enumerate(arguments)]
        result = dict(tuple_rep(*([scalar(None)] if symbol == '_hs_text_reverse' else [scalar(None), scalar('Int64Rep')])), evaluated=False)
        descriptor = dict(schema=1, target=dict(kind='static', unit='text-2.1.3-inplace', symbol=symbol,
                          isFunction=True), convention='ccall', safety='unsafe', arity=5, suppliedArity=5,
                          argumentReps=[scalar(rep, False) for rep in arguments], resultRep=copy.deepcopy(result))
        call = ['app', ['var', 'original-text', dict(rep=CLOSURE)],
                [['var', p['id'], dict(rep=copy.deepcopy(p['rep']))] for p in parameters],
                [False] * 5, False, False, dict(rep=result, foreignCall=descriptor)]
        body = ['case', call, 'result', [['default', None, [], [*lit(0), dict(rep=LONG)]]],
                dict(rep=LONG, binder=dict(id='result', lifted=False, rep=dict(result, evaluated=True)))]
        root = dict(bind('root', ['lam', parameters, body, dict(rep=CLOSURE, resultRep=LONG)]),
                    rep=CLOSURE, arity=5)
        return dict(schema=1, ghc='9.14.1', bindings=[root], constructors=[])

    def test_exact_text_calls_and_capability_boundary(self):
        for symbol in ('_hs_text_memchr', '_hs_text_measure_off', '_hs_text_reverse'):
            module = self.fixture(symbol)
            report = audit_core.Audit([('text-control.json', module)], CAP).run(['root'])
            self.assertTrue(report['accepted'], report['issues'])
            disabled = dict(CAP, managedForeignCalls=[name for name in CAP['managedForeignCalls'] if name != symbol])
            self.assertFalse(audit_core.Audit([('text-control.json', module)], disabled).run(['root'])['accepted'])

    def test_text_identity_state_array_result_and_binding_mutations_reject(self):
        for symbol in ('_hs_text_memchr', '_hs_text_measure_off', '_hs_text_reverse'):
            for mutation in ('unit', 'safety', 'arity', 'array', 'state', 'result', 'stored-array', 'flags'):
                module = self.fixture(symbol)
                call = module['bindings'][0]['expr'][2][1]
                descriptor = call[6]['foreignCall']
                if mutation == 'unit': descriptor['target']['unit'] = 'other-text'
                elif mutation == 'safety': descriptor['safety'] = 'safe'
                elif mutation == 'arity': descriptor['suppliedArity'] = 4
                elif mutation == 'array': descriptor['argumentReps'][0]['primReps'] = ['AddrRep']
                elif mutation == 'state': call[2][-1][2]['rep'] = LONG
                elif mutation == 'result': descriptor['resultRep']['primReps'] = ['Word64Rep']
                elif mutation == 'stored-array': module['bindings'][0]['expr'][1][0]['rep'] = LONG
                elif mutation == 'flags': call[3][0] = True
                report = audit_core.Audit([('text-control.json', module)], CAP).run(['root'])
                self.assertFalse(report['accepted'], (symbol, mutation))

    def test_installed_text_suffix_preserves_the_exact_release(self):
        accepted = ('text-2.1.3-inplace', 'text-2.1.3-e182', 'text-2.1.3-119b')
        rejected = (None, 'text-2.1.3', 'text-2.1.3-', 'text-2.1.2-e182', 'text-2.1.4-e182',
                    'text-2.1.3-e182-extra', 'text-2.1.3-e182\n', 'text-2.1.3-e182 ',
                    'other-text-2.1.3-e182', 'text-2.1.3-foreign', 'text-2.1.3-e182:forged')
        for symbol in ('_hs_text_memchr', '_hs_text_measure_off', '_hs_text_reverse'):
            for unit in accepted + rejected:
                module = self.fixture(symbol)
                call = module['bindings'][0]['expr'][2][1]
                call[6]['foreignCall']['target']['unit'] = unit
                report = audit_core.Audit([('text-control.json', module)], CAP).run(['root'])
                self.assertEqual(unit in accepted, report['accepted'], (symbol, unit, report['issues']))


class OriginalWaitStatusAuditTests(unittest.TestCase):
    def fixture(self, symbol):
        def scalar(rep, evaluated=True):
            return dict(kind='void' if rep is None else 'long', primReps=[] if rep is None else [rep],
                        evaluated=evaluated)
        parameters = [dict(id='status', lifted=False, rep=scalar('Int32Rep')),
                      dict(id='state', lifted=False, rep=scalar(None))]
        result = dict(tuple_rep(scalar(None), scalar('Int32Rep')), evaluated=False)
        descriptor = dict(schema=1, target=dict(kind='static', unit='unix-2.8.8.0-inplace', symbol=symbol,
                          isFunction=True), convention='capi', safety='unsafe', arity=2, suppliedArity=2,
                          argumentReps=[scalar('Int32Rep', False), scalar(None, False)], resultRep=copy.deepcopy(result))
        call = ['app', ['var', 'original-wait-status', dict(rep=CLOSURE)],
                [['var', p['id'], dict(rep=copy.deepcopy(p['rep']))] for p in parameters],
                [False, False], False, False, dict(rep=result, foreignCall=descriptor)]
        body = ['case', call, 'result', [['default', None, [], [*lit(0), dict(rep=LONG)]]],
                dict(rep=LONG, binder=dict(id='result', lifted=False, rep=dict(result, evaluated=True)))]
        root = dict(bind('root', ['lam', parameters, body, dict(rep=CLOSURE, resultRep=LONG)]), rep=CLOSURE, arity=2)
        return dict(schema=1, ghc='9.14.1', bindings=[root], constructors=[])

    def test_seven_original_macros_and_capability_boundary(self):
        for symbol in audit_core.core_original_foreign.WAIT_STATUS_OPERATIONS:
            module = self.fixture(symbol)
            report = audit_core.Audit([('wait-status.json', module)], CAP).run(['root'])
            self.assertTrue(report['accepted'], report['issues'])
            disabled = dict(CAP, managedForeignCalls=[name for name in CAP['managedForeignCalls'] if name != symbol])
            self.assertFalse(audit_core.Audit([('wait-status.json', module)], disabled).run(['root'])['accepted'])

    def test_installed_wait_status_symbols_preserve_exact_owner_and_policy(self):
        for canonical in audit_core.core_original_foreign.WAIT_STATUS_OPERATIONS:
            for suffix in ('inplace', '460b', 'deadbeef'):
                unit = 'unix-2.8.8.0-' + suffix
                symbol = canonical.replace('zminplaceZC', 'zm' + suffix + 'ZC')
                module = self.fixture(symbol)
                target = module['bindings'][0]['expr'][2][1][6]['foreignCall']['target']
                target['unit'] = unit
                report = audit_core.Audit([('wait-status.json', module)], CAP).run(['root'])
                self.assertTrue(report['accepted'], report['issues'])
                self.assertEqual(symbol, report['foreignCalls'][0]['symbol'])
                disabled = dict(CAP, managedForeignCalls=[name for name in CAP['managedForeignCalls'] if name != canonical])
                self.assertFalse(audit_core.Audit([('wait-status.json', module)], disabled).run(['root'])['accepted'])
                target['unit'] = 'unix-2.8.8.0-inplace' if suffix != 'inplace' else 'unix-2.8.8.0-460b'
                self.assertFalse(audit_core.Audit([('wait-status.json', module)], CAP).run(['root'])['accepted'])
            for bad in (canonical.replace('zminplaceZC', 'zmABCDZC'), canonical.replace('zminplaceZC', 'zmnothexZC'),
                        canonical.replace('2zi8zi8zi0', '2zi8zi7zi0'), canonical.replace('ProcessziInternals', 'ProcessziByteString'),
                        re.sub(r'ghczuwrapperZC[0-6]ZC', 'ghczuwrapperZC7ZC', canonical), canonical + 'Extra'):
                self.assertFalse(audit_core.Audit([('wait-status.json', self.fixture(bad))], CAP).run(['root'])['accepted'])

    def test_wait_status_unit_width_state_and_stored_proofs_are_exact(self):
        for symbol in audit_core.core_original_foreign.WAIT_STATUS_OPERATIONS:
            for mutation in ('unit', 'safety', 'convention', 'arity', 'width', 'state', 'result', 'stored-status', 'stored-state', 'flags'):
                module = self.fixture(symbol)
                call = module['bindings'][0]['expr'][2][1]
                descriptor = call[6]['foreignCall']
                if mutation == 'unit': descriptor['target']['unit'] = 'ghc-internal'
                elif mutation == 'safety': descriptor['safety'] = 'safe'
                elif mutation == 'convention': descriptor['convention'] = 'ccall'
                elif mutation == 'arity': descriptor['suppliedArity'] = 1
                elif mutation == 'width': descriptor['argumentReps'][0]['primReps'] = ['IntRep']
                elif mutation == 'state': call[2][-1][2]['rep'] = LONG
                elif mutation == 'result': descriptor['resultRep']['primReps'] = ['Word32Rep']
                elif mutation == 'stored-status': module['bindings'][0]['expr'][1][0]['rep'] = LONG
                elif mutation == 'stored-state': module['bindings'][0]['expr'][1][1]['rep'] = LONG
                elif mutation == 'flags': call[3][0] = True
                report = audit_core.Audit([('wait-status.json', module)], CAP).run(['root'])
                self.assertFalse(report['accepted'], (symbol, mutation))


AuditStore = audit_core.AuditStore


class AuditStoreTest(unittest.TestCase):
    def setUp(self):
        self.temporary = TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.path = Path(self.temporary.name) / 'catalogue.sqlite'

    def store(self, **options):
        store = AuditStore(self.path, {'manifestSha256': 'a' * 64, 'tools': ['original']}, **options)
        self.addCleanup(store.close)
        return store

    def binding(self, key, **fields):
        return dict(id=key, name='shared', lifted=True, expr=['var', key], **fields)

    def test_ingestion_does_not_copy_unused_module_payload(self):
        module = dict(schema=1, ghc='9.14.1', sourceCore='diagnostic source text',
                      bindings=[self.binding('root')], constructors=[])
        store = self.store()
        with patch.object(store, 'put_record', wraps=store.put_record) as put:
            auditor = audit_core.Audit([('original.json', module)], CAP, store=store)
        self.assertNotIn('modules', [call.args[0] for call in put.call_args_list])
        self.assertEqual('original.json', store.sources['root'])
        self.assertEqual(module['bindings'][0], dict(auditor.bindings['root']))
        expected = audit_core.Audit([('original.json', module)], CAP).run(['root'])
        actual = io.StringIO()
        audit_core.write_report(auditor.run(['root']), actual)
        self.assertEqual(json.dumps(expected, indent=2) + '\n', actual.getvalue())

    def test_exact_binding_roundtrip_and_header_lookup_never_decode_expression(self):
        store = self.store()
        original = self.binding('unit:Module.f', future={'nested': [None, False, 2**100]}, rep={'kind': 'object'})
        self.assertIsNone(store.put_binding('original.zip!core/0.json', original))
        binding = store.bindings[original['id']]
        self.assertEqual(list(original), list(binding))
        self.assertEqual('shared', binding.get('name'))
        self.assertEqual(original['rep'], binding.get('rep'))
        self.assertEqual(0, store.expression_loads)
        self.assertEqual(original, dict(binding))
        self.assertEqual(1, store.expression_loads)
        self.assertEqual(original, dict(store.bindings[original['id']]))
        self.assertEqual(1, store.expression_loads)
        self.assertEqual({original['id']: 'original.zip!core/0.json'}, dict(store.sources))
        self.assertNotIn(None, store.bindings)
        self.assertNotIn('absent', store.bindings)
        self.assertEqual(None, store.bindings.get('absent'))
        with self.assertRaises(KeyError): store.sources['absent']

    def test_input_objects_are_not_retained_and_mutation_does_not_change_rows(self):
        class Binding(dict): pass
        store = self.store()
        original = Binding(self.binding('one'))
        reference = weakref.ref(original)
        store.put_binding('source', original)
        original['expr'][1] = 'changed after staging'
        del original
        gc.collect()
        self.assertIsNone(reference())
        self.assertEqual(['var', 'one'], store.bindings['one']['expr'])

    def test_duplicates_keep_first_binding_source_name_and_insertion_order(self):
        store = self.store()
        first = self.binding('z')
        store.put_binding('', first)
        store.put_binding('second', self.binding('a'))
        replacement = dict(first, name='replacement', expr=['var', 'changed'])
        self.assertEqual('', store.put_binding('duplicate', replacement))
        self.assertEqual(first, dict(store.bindings['z']))
        self.assertEqual(['z', 'a'], list(store.bindings))
        self.assertEqual(['z', 'a'], list(store.named('shared')))
        self.assertEqual([], list(store.named('replacement')))
        self.assertEqual(2, len(store.bindings))

    def test_missing_expression_is_distinct_from_explicit_null(self):
        store = self.store()
        for key, binding in [('absent', {'id': 'absent'}), ('null', {'id': 'null', 'expr': None})]:
            store.put_binding('source', binding)
            self.assertEqual(binding, dict(store.bindings[key]))
        with self.assertRaises(KeyError): store.bindings['absent']['expr']
        self.assertIsNone(store.bindings['null']['expr'])
        self.assertEqual(1, store.expression_loads)

    def test_all_json_string_keys_and_unknown_values_roundtrip(self):
        store = self.store()
        keys = ['\x00', '\ud800', '\udfff', '\uffff', '\U00010000', 'λ', 'plain']
        for key in keys:
            store.put_binding(key, dict(id=key, name=key, expr=['lit', key], diagnostic={'text': key}))
            store.put_record('constructors', key, {'id': key})
            store.append_event('uses', key, group=key, owner=key)
        store.seal(validation_complete=True)
        self.assertEqual(keys, list(store.bindings))
        self.assertEqual(keys, list(store.records('constructors')))
        self.assertEqual(sorted(keys), list(store.event_groups('uses')))
        for key in keys:
            self.assertEqual(key, store.sources[key])
            self.assertEqual([key], list(store.named(key)))
            self.assertEqual(['lit', key], store.bindings[key]['expr'])
            self.assertEqual([key], list(store.events('uses', group=key, owner=key)))

    def test_record_namespaces_update_without_changing_original_order(self):
        store = self.store()
        store.put_record('constructors', 'z', {'type': 'alpha', 'future': [1]})
        store.put_record('constructors', 'a', {'type': 'other'})
        store.put_record('native', 'z', {'proof': 'retained'})
        store.put_record('constructors', 'z', {'type': 'beta', 'future': [1]})
        records = store.records('constructors')
        self.assertEqual(['z', 'a'], list(records))
        self.assertEqual(2, len(records))
        self.assertEqual({'type': 'beta', 'future': [1]}, records['z'])
        self.assertEqual({'proof': 'retained'}, store.records('native')['z'])
        with self.assertRaises(KeyError): records['absent']

    def test_catalogue_lookup_matches_dict_for_malformed_key_types(self):
        store = self.store()
        maps = [store.bindings, store.sources, store.records('constructors'),
                audit_core._InputRecords(store, 'package-links'),
                audit_core._InputRecords(store, 'linked', compound=True)]
        for mapping in maps:
            for key in [None, False, 1, 1.5, ('unit', 7)]:
                with self.subTest(mapping=type(mapping).__name__, key=key):
                    self.assertIsNone(mapping.get(key))
                    self.assertNotIn(key, mapping)
            for key in [[], {}, ('unit', [])]:
                with self.subTest(mapping=type(mapping).__name__, key=key):
                    with self.assertRaises(TypeError) as expected: {}.get(key)
                    with self.assertRaises(TypeError) as actual: mapping.get(key)
                    self.assertEqual(str(expected.exception), str(actual.exception))

    def test_cache_lru_entry_and_byte_bounds_and_oversized_bypass(self):
        store = self.store(cache_entries=2, cache_bytes=24)
        for key in ['a', 'b', 'c']:
            store.put_binding('source', self.binding(key))  # 11 encoded bytes each.
        store.put_binding('source', dict(id='large', expr='x' * 40))
        for key in ['a', 'b', 'a', 'c']:
            store.bindings[key]['expr']
        self.assertEqual(3, store.expression_loads)
        self.assertEqual(2, store.cached_expression_count)
        self.assertEqual(22, store.cached_expression_bytes)
        store.bindings['b']['expr']  # b, not recently-used a, was evicted.
        self.assertEqual(4, store.expression_loads)
        for _ in range(2): store.bindings['large']['expr']
        self.assertEqual(6, store.expression_loads)
        self.assertEqual(22, store.cached_expression_bytes)
        store.close()
        self.assertEqual(0, store.cached_expression_count)
        self.assertEqual(0, store.cached_expression_bytes)

    def test_cache_byte_limit_can_evict_before_entry_limit(self):
        store = self.store(cache_entries=20, cache_bytes=15)
        for key in ['a', 'b']:
            store.put_binding('source', self.binding(key))
            store.bindings[key]['expr']
        self.assertEqual(1, store.cached_expression_count)
        self.assertLessEqual(store.cached_expression_bytes, 15)

    def test_disabled_cache_never_retains_an_expression(self):
        for option in [{'cache_entries': 0}, {'cache_bytes': 0}]:
            with self.subTest(option=option):
                path = Path(self.temporary.name) / ('disabled-' + next(iter(option)))
                with AuditStore(path, {}, **option) as store:
                    store.put_binding('source', self.binding('a'))
                    for _ in range(2): store.bindings['a']['expr']
                    self.assertEqual(2, store.expression_loads)
                    self.assertEqual(0, store.cached_expression_count)
                    store.seal(validation_complete=True)

    def test_incomplete_context_and_closed_store_are_not_ready(self):
        store = self.store()
        with self.assertRaisesRegex(RuntimeError, 'not sealed'):
            with store: store.put_binding('source', self.binding('a'))
        self.assertFalse(store.complete)
        for action in [lambda: len(store.bindings), lambda: store.provenance(), lambda: store.pop_pending()]:
            with self.assertRaisesRegex(RuntimeError, 'closed'): action()
        store.close()  # Idempotent, and the partial private DB remains evidence.
        self.assertTrue(self.path.is_file())
        with closing(sqlite3.connect(self.path)) as connection:
            self.assertEqual(b'"ingesting"', connection.execute("SELECT payload FROM metadata WHERE key='phase'").fetchone()[0])

    def test_seal_requires_exact_completion_and_freezes_input_not_acceptance(self):
        store = self.store()
        for incomplete in [False, None, 1, 'true']:
            with self.assertRaisesRegex(ValueError, 'incomplete'): store.seal(validation_complete=incomplete)
        for action in [lambda: list(store.events('issue')), lambda: store.discover('a'),
                       lambda: store.pop_pending(), lambda: list(store.reachable())]:
            with self.assertRaisesRegex(RuntimeError, 'not sealed'): action()
        store.append_event('issue', {'code': 'deliberate-rejection'})
        store.seal(validation_complete=True)
        self.assertTrue(store.complete)
        self.assertEqual(1, store.event_count('issue'))
        self.assertEqual([{'code': 'deliberate-rejection'}], list(store.events('issue')))
        for action in [lambda: store.put_binding('source', self.binding('a')),
                       lambda: store.put_record('constructor', 'a', {}),
                       lambda: store.seal(validation_complete=True)]:
            with self.assertRaisesRegex(RuntimeError, 'already sealed'): action()
        store.append_event('issue', {'code': 'later-rejection'})
        self.assertEqual(2, store.event_count('issue'))

    def test_body_exception_preserves_failure_and_rolls_back_pending_rows(self):
        store = self.store(batch_rows=1)
        class Interrupted(Exception): pass
        with self.assertRaises(Interrupted):
            with store:
                store.put_binding('source', self.binding('committed'))
                store._batch_rows = 100
                store.put_binding('source', self.binding('pending'))
                raise Interrupted('original interruption')
        self.assertFalse(store.complete)
        with closing(sqlite3.connect(self.path)) as connection:
            self.assertEqual([(b'committed',)], connection.execute('SELECT key FROM bindings').fetchall())

    def test_exclusive_creation_never_overwrites_or_reopens(self):
        store = self.store()
        before = self.path.read_bytes()
        with self.assertRaises(FileExistsError): AuditStore(self.path, {'different': True})
        self.assertEqual(before, self.path.read_bytes())
        if os.name != 'nt':  # Windows symlink privileges/ACLs are not POSIX mode bits.
            link = self.path.with_name('link.sqlite')
            link.symlink_to(self.path)
            with self.assertRaises(FileExistsError): AuditStore(link, {})
            self.assertEqual(0o600, stat.S_IMODE(self.path.stat().st_mode))
        self.assertEqual({'manifestSha256': 'a' * 64, 'tools': ['original']}, store.provenance())

    def test_invalid_bounds_fail_before_creating_a_file(self):
        for key in ['cache_bytes', 'cache_entries', 'page_cache_kib', 'batch_rows']:
            for value in [-1, True, 1.2]:
                with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                    AuditStore(self.path, {}, **{key: value})
                self.assertFalse(self.path.exists())
        for key in ['page_cache_kib', 'batch_rows']:
            with self.assertRaises(ValueError): AuditStore(self.path, {}, **{key: 0})

    def test_events_keep_exact_order_groups_owners_and_nested_payload(self):
        store = self.store()
        rows = [('use', {'row': 0}, 'z', 'owner1'), ('issue', {'row': 1}, None, None),
                ('use', {'row': 2, 'nested': [False, None]}, 'a', 'owner2'),
                ('use', {'row': 3}, 'z', 'owner2'), ('use', {'row': 4}, None, None)]
        for index, (kind, payload, group, owner) in enumerate(rows):
            self.assertEqual(index + 1, store.append_event(kind, payload, group=group, owner=owner))
        store.seal(validation_complete=True)
        self.assertEqual([row[1] for row in rows if row[0] == 'use'], list(store.events('use')))
        self.assertEqual([rows[0][1], rows[3][1]], list(store.events('use', group='z')))
        self.assertEqual([rows[3][1]], list(store.events('use', group='z', owner='owner2')))
        self.assertEqual([rows[4][1]], list(store.events('use', group=None, owner=None)))
        self.assertEqual([None, 'a', 'z'], list(store.event_groups('use')))
        self.assertEqual(2, store.event_count('use', group='z'))
        self.assertEqual(2, store.event_count('use', owner='owner2'))
        self.assertEqual(0, store.event_count('absent'))

    def test_large_group_decodes_only_requested_rows_and_can_be_repeated(self):
        store = self.store()
        for index in range(12000): store.append_event('missing', {'index': index}, group='missing-id')
        store.seal(validation_complete=True)
        with patch.object(audit_core, '_unpack', wraps=audit_core._unpack) as unpack:
            rows = store.events('missing', group='missing-id')
            self.assertEqual(0, unpack.call_count)
            self.assertEqual({'index': 0}, next(rows))
            self.assertEqual(1, unpack.call_count)
            rows.close()
        for _ in range(2):
            self.assertEqual(sum(range(12000)), sum(row['index'] for row in store.events('missing', group='missing-id')))
        self.assertEqual(12000, store.event_count('missing'))
        self.assertEqual(0, store.cached_expression_count)

    def test_fifo_multiroot_cycles_and_first_predecessor_match_reference(self):
        graph = {'r2': ['a', 'r1'], 'r1': ['a', 'b'], 'a': ['a', 'c'], 'b': ['c'], 'c': ['r2']}
        roots = ['r2', 'r1', 'r2']
        expected, predecessors, queue = [], {}, deque()
        for key in roots:
            if key not in predecessors: predecessors[key] = None; queue.append(key)
        while queue:
            key = queue.popleft()
            expected.append((key, predecessors[key]))
            for child in graph[key]:
                if child not in predecessors: predecessors[child] = key; queue.append(child)
        store = self.store(batch_rows=2)
        store.seal(validation_complete=True)
        for key in roots: store.discover(key)
        self.assertEqual(2, store.pending_count())
        self.assertEqual([], list(store.reachable()))
        while (key := store.pop_pending()) is not None:
            for child in graph[key]: store.discover(child, key)
        self.assertEqual(expected, list(store.reachable()))
        self.assertEqual(len(expected), store.reachable_count())
        self.assertEqual(0, store.pending_count())
        self.assertEqual(['r2', 'a', 'c'], store.reachable_via('c'))
        self.assertEqual(['r1'], store.reachable_via('r1'))
        self.assertFalse(store.discover('r1', 'c'))
        self.assertIsNone(store.predecessor('r1'))
        self.assertFalse(store.is_discovered('absent'))
        with self.assertRaises(KeyError): store.reachable_via('absent')
        with self.assertRaisesRegex(ValueError, 'discovered'): store.discover('new', 'absent')
        self.assertFalse(store.is_discovered('new'))

    def test_long_chain_keeps_only_disk_forest_not_all_witnesses(self):
        store = self.store()
        store.seal(validation_complete=True)
        for index in range(4000): store.discover(str(index), str(index - 1) if index else None)
        self.assertEqual([str(index) for index in range(4000)], store.reachable_via('3999'))
        self.assertEqual(0, store.reachable_count())
        self.assertEqual(4000, store.pending_count())
        self.assertEqual('0', store.pop_pending())
        self.assertEqual([('0', None)], list(store.reachable()))

    def test_corrupt_payloads_fail_closed_at_every_decoding_seam(self):
        store = self.store(cache_entries=0)
        store.put_binding('source', self.binding('a'))
        store.put_record('constructors', 'a', {})
        store.append_event('issue', {})
        store.seal(validation_complete=True)
        tests = [('bindings', 'header', lambda: store.bindings['a']),
                 ('bindings', 'expression', lambda: store._expression(b'a')),
                 ('records', 'payload', lambda: store.records('constructors')['a']),
                 ('events', 'payload', lambda: list(store.events('issue'))),
                 ('metadata', 'payload', store.provenance)]
        for table, column, read in tests:
            with self.subTest(table=table, column=column):
                store._connection.execute('SAVEPOINT corrupt')
                store._connection.execute(f'UPDATE {table} SET {column}=?', (b'{}corrupt',))
                with self.assertRaisesRegex(audit_core.AuditStoreError, 'checksum'): read()
                store._connection.execute('ROLLBACK TO corrupt')
                store._connection.execute('RELEASE corrupt')

    def test_commit_failure_never_sets_completion_and_close_releases_connection(self):
        store = self.store()
        connection = store._connection
        wrapped = Mock(wraps=connection)
        wrapped.commit.side_effect = sqlite3.OperationalError('disk full')
        store._connection = wrapped
        with self.assertRaisesRegex(sqlite3.OperationalError, 'disk full'): store.seal(validation_complete=True)
        self.assertFalse(store.complete)
        with self.assertRaisesRegex(sqlite3.OperationalError, 'disk full'): store.close()
        self.assertTrue(store._closed)
        with self.assertRaises(sqlite3.ProgrammingError): connection.execute('SELECT 1')

    def test_failed_seal_does_not_leave_a_ready_marker_for_later_close(self):
        store = self.store()
        connection = store._connection
        wrapped = Mock(wraps=connection)
        wrapped.commit.side_effect = sqlite3.OperationalError('disk full')
        store._connection = wrapped
        with self.assertRaises(sqlite3.OperationalError): store.seal(validation_complete=True)
        store._connection = connection
        store.close()
        with closing(sqlite3.connect(self.path)) as saved:
            self.assertEqual(b'"ingesting"', saved.execute("SELECT payload FROM metadata WHERE key='phase'").fetchone()[0])

    def test_sqlite_record_limit_failure_does_not_truncate_or_complete(self):
        store = self.store()
        store._connection.setlimit(sqlite3.SQLITE_LIMIT_LENGTH, 512)
        with self.assertRaises(sqlite3.DataError):
            store.put_binding('source', dict(id='oversized', expr='x' * 1024))
        self.assertNotIn('oversized', store.bindings)
        self.assertFalse(store.complete)

    def test_schema_initialization_failure_rolls_back_and_closes(self):
        real_connect = sqlite3.connect
        opened = []
        def failing_connect(path, **options):
            connection = real_connect(path, **options)
            opened.append(connection)
            wrapped = Mock(wraps=connection)
            wrapped.executescript.side_effect = lambda script: connection.executescript(script + '\nINVALID SQL;')
            return wrapped
        with patch.object(audit_core.sqlite3, 'connect', side_effect=failing_connect), self.assertRaises(sqlite3.OperationalError):
            AuditStore(self.path, {})
        with self.assertRaises(sqlite3.ProgrammingError): opened[0].execute('SELECT 1')
        with closing(real_connect(self.path)) as saved:
            self.assertEqual([], saved.execute('SELECT name FROM sqlite_master').fetchall())

    def test_validated_stream_exhaustion_is_required_before_store_seal(self):
        root = Path(self.temporary.name)
        records = []
        for name in ['First', 'Last']:
            module = dict(schema=1, ghc='9.14.1', unit='unit', module=name,
                boundary=core_package_manifest.BOUNDARY, bindings=[self.binding('unit:' + name + '.f')], constructors=[])
            data = write_core(root / (name + '.cbd'), module)
            records.append(dict(name=name, boundary=module['boundary'], path=name + '.cbd',
                                sha256=hashlib.sha256(data).hexdigest()))
        manifest = root / 'packages.json'
        manifest.write_text(json.dumps(dict(format='thc-core-packages', schema=1, ghc='9.14.1',
            units=[dict(id='unit', depends=[], modules=records)])))
        for mode in ['complete', 'early-close', 'late-corrupt']:
            with self.subTest(mode=mode):
                store = AuditStore(root / (mode + '.sqlite'), {'manifestSha256': hashlib.sha256(manifest.read_bytes()).hexdigest()})
                self.addCleanup(store.close)
                if mode == 'late-corrupt': (root / 'Last.cbd').write_bytes(b'corrupt')
                def ingest():
                    with store:
                        with core_package_manifest.open_modules(manifest, audit_archives=True) as modules:
                            for source, module in modules:
                                for binding in module['bindings']: store.put_binding(source, binding)
                                del module
                                if mode == 'early-close': break
                        store.seal(validation_complete=modules.complete)
                if mode == 'complete':
                    ingest()
                    self.assertTrue(store.complete)
                else:
                    with self.assertRaises(ValueError): ingest()
                    self.assertFalse(store.complete)

    def test_lazy_catalogue_matches_entire_existing_auditor_report(self):
        # Exercise existing semantics unchanged. These structural controls test
        # storage equivalence, not a claim of genuine GHC/runtime acceptance.
        root = Path(__file__).resolve().parent
        cap = json.loads((root / 'core-capabilities.json').read_text())
        def binding(key, expression):
            return dict(id=key, name=key, lifted=True, arity=0, expr=expression)
        bindings = [
            binding('root', ['app', ['var', 'left'], [['var', 'right']], [True]]),
            binding('second', ['var', 'shared']),
            binding('retained', ['var', 'retainedMissing']),
            binding('left', ['var', 'shared']),
            binding('right', ['var', 'root']),
            binding('shared', ['let', False, [binding('bad', ['lit', 'unknown-kind', 'x'])], ['var', 'missing']]),
        ]
        modules = [('original.json', dict(schema=1, ghc='9.14.1', bindings=bindings, constructors=[])),
                   ('duplicate.json', dict(schema=1, ghc='9.14.1', bindings=[bindings[0]], constructors=[]))]
        entries = ['root', 'second', 'root', 'absent']
        eager = audit_core.Audit(modules, cap)
        eager.retained_exports = ['retained']
        expected = eager.run(entries)
        for cache_entries in [0, 1, 32]:
            with self.subTest(cache_entries=cache_entries):
                with AuditStore(Path(self.temporary.name) / ('parity-' + str(cache_entries)), {}, cache_entries=cache_entries) as store:
                    lazy = audit_core.Audit(iter(modules), cap, store=store)
                    lazy.retained_exports = ['retained']
                    report = lazy.run(entries)
                    for _ in range(2):
                        output = io.StringIO()
                        audit_core.write_report(report, output)
                        self.assertEqual(json.dumps(expected, indent=2) + '\n', output.getvalue())
                    self.assertLessEqual(store.cached_expression_count, cache_entries)


class BoundedAuditIntegrationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)


    def bridge_manifest(self, selected='runtime-b'):
        # Structural exporter-proof controls. Genuine native-produced closure
        # equivalence is checked separately from these small schema fixtures.
        boundary = core_package_manifest.BOUNDARY
        units = []
        for unit in ('runtime-a', 'runtime-b'):
            prefix = unit + ':THC.Internal.Exception.'
            bridge = dict(schema=1, unit=unit, module='THC.Internal.Exception',
                box=prefix + 'boxForeign', project=prefix + 'projectForeign',
                payloadType=prefix + 'ForeignException',
                exceptionType='ghc-internal:GHC.Internal.Exception.Type.SomeException')
            module = dict(schema=1, ghc='9.14.1', unit=unit, module=bridge['module'], boundary=boundary,
                foreignExceptionBridge=bridge, constructors=[],
                bindings=[bind(bridge['box'], [*lit(1), dict(rep=LONG)]), bind(bridge['project'], [*lit(2), dict(rep=LONG)])])
            source = self.root / (unit + '.cbd')
            write_core(source, module)
            units.append(dict(id=unit, depends=[], modules=[dict(name=module['module'],
                boundary=boundary, path=source.name, sha256=hashlib.sha256(source.read_bytes()).hexdigest())]))
        declaration = json.loads((ROOT.parent / 'src/test/resources/core/foreign-exception-descriptor.json').read_text())
        parameters = [dict(id='argument' + str(i), lifted=False, rep=dict(rep, evaluated=True))
                      for i, rep in enumerate(declaration['argumentReps'])]
        call = ['app', ['var', 'service', dict(rep=CLOSURE)],
                [['var', p['id'], dict(rep=p['rep'])] for p in parameters],
                [False] * len(parameters), False, False,
                dict(rep=dict(declaration['resultRep'], evaluated=True), foreignCall=declaration)]
        helper = bind('query', ['lam', parameters, call,
            dict(rep=CLOSURE, resultRep=declaration['resultRep'])])
        module = dict(schema=1, ghc='9.14.1', unit='app', module='Main', boundary=boundary,
            constructors=[], bindings=[bind('app:Main.entry', ['let', False, [helper], [*lit(0), dict(rep=LONG)], dict(rep=LONG)])])
        source = self.root / 'app.cbd'
        write_core(source, module)
        units.append(dict(id='app', depends=['runtime-a', 'runtime-b'], modules=[dict(name='Main',
            boundary=boundary, path=source.name, sha256=hashlib.sha256(source.read_bytes()).hexdigest())]))
        manifest = dict(format=core_package_manifest.FORMAT, schema=1, ghc='9.14.1', units=units)
        if selected is not None:
            manifest['foreignExceptionBridgeUnit'] = selected
        path = self.root / 'packages.json'
        path.write_text(json.dumps(manifest))
        return path

    def assert_bridge_report(self, report, selected):
        if selected in ('runtime-a', 'runtime-b'):
            self.assertTrue(report['accepted'], report['issues'])
            self.assertEqual(['app:Main.entry', selected + ':THC.Internal.Exception.boxForeign',
                              selected + ':THC.Internal.Exception.projectForeign'],
                             [b['id'] for b in report['reachableBindings']])
        else:
            self.assertFalse(report['accepted'])
            self.assertEqual(['foreign-exception-bridge'], [i['code'] for i in report['issues']])
            self.assertEqual(['app:Main.entry'], [b['id'] for b in report['reachableBindings']])

    def test_bridge_selection_and_ambiguity_match_in_both_cli_modes(self):
        for index, selected in enumerate(('runtime-a', 'runtime-b', None, 'absent-runtime')):
            with self.subTest(selected=selected):
                manifest = self.bridge_manifest(selected)
                reports = []
                for mode in ('eager', 'indexed'):
                    output = self.root / ('bridge-' + str(index) + '-' + mode + '.json')
                    options = ['--eager'] if mode == 'eager' else [
                        '--store', str(self.root / ('bridge-' + str(index) + '.sqlite'))]
                    result = subprocess.run([sys.executable, str(ROOT / 'audit-core.py'),
                        '--package-manifest', str(manifest), '--entry', 'app:Main.entry',
                        '--output', str(output), *options], capture_output=True, text=True)
                    self.assertEqual(0 if selected in ('runtime-a', 'runtime-b') else 1,
                                     result.returncode, result.stderr)
                    reports.append(output.read_bytes())
                    self.assert_bridge_report(json.loads(reports[-1]), selected)
                self.assertEqual(*reports)


    def test_indexed_bridge_registration_keeps_exact_identity_and_helpers(self):
        for index, mutate in enumerate((
                lambda module: module['foreignExceptionBridge'].update(schema=True),
                lambda module: module['foreignExceptionBridge'].update(unit='runtime-a'),
                lambda module: module['foreignExceptionBridge'].update(exceptionType='shadow:SomeException'),
                lambda module: module['bindings'].pop())):
            manifest = self.bridge_manifest('runtime-b')
            modules = list(core_package_manifest.load_for_audit(manifest))
            selected = next(module for _, module in modules if module['unit'] == 'runtime-b')
            mutate(selected)
            expected = audit_core.Audit(modules, CAP, 'runtime-b').run(['app:Main.entry'])
            self.assertFalse(expected['accepted'])
            self.assertEqual(['foreign-exception-bridge', 'foreign-exception-bridge'],
                             [issue['code'] for issue in expected['issues']])
            self.assertIn('Invalid genuine bridge', expected['issues'][0]['detail'])
            self.assertEqual(['app:Main.entry'], [item['id'] for item in expected['reachableBindings']])
            with AuditStore(self.root / ('invalid-bridge-' + str(index) + '.sqlite'), {}) as store:
                actual = audit_core.Audit(iter(modules), CAP, 'runtime-b', store=store).run(['app:Main.entry'])
                output = io.StringIO()
                audit_core.write_report(actual, output)
                self.assertEqual(json.dumps(expected, indent=2) + '\n', output.getvalue())

    def test_bridge_selection_uses_the_validated_manifest_snapshot(self):
        for mode in ('eager', 'indexed'):
            with self.subTest(mode=mode):
                manifest = self.bridge_manifest('runtime-b')
                original = manifest.read_bytes()
                observed = core_package_manifest._ModuleStream._manifest_read
                reads = []
                def replace_after_read(stream, identity):
                    observed(stream, identity)
                    reads.append(identity)
                    changed = dict(identity['manifest'], foreignExceptionBridgeUnit='runtime-a')
                    manifest.write_text(json.dumps(changed))
                def check(store=None):
                    with patch.object(core_package_manifest._ModuleStream, '_manifest_read', replace_after_read):
                        auditor = audit_core._audit_inputs(manifest, [], CAP, store)
                    self.assertEqual('runtime-b', auditor.exception_bridge_unit)
                    output = io.StringIO()
                    audit_core.write_report(auditor.run(['app:Main.entry']), output)
                    self.assert_bridge_report(json.loads(output.getvalue()), 'runtime-b')
                    self.assertEqual(1, len(reads))
                    self.assertEqual(hashlib.sha256(original).hexdigest(), reads[0]['sha256'])
                    self.assertEqual('runtime-a', json.loads(manifest.read_bytes())['foreignExceptionBridgeUnit'])
                    if store is not None:
                        self.assertEqual(reads[0], store.records('input-provenance')['package-manifest'])
                if mode == 'eager':
                    check()
                else:
                    with AuditStore(self.root / 'snapshot.sqlite', {}) as store:
                        check(store)

    def test_many_missing_uses_stream_one_row_at_a_time_with_complete_evidence(self):
        count = 12000
        expression = ['let', False, [bind('local' + str(i), var('missing')) for i in range(count)], lit(0)]
        module = dict(schema=1, ghc='9.14.1', bindings=[bind('root', expression)], constructors=[])
        expected = audit_core.Audit([('fan-in.json', module)], CAP).run(['root'])
        class Sink(io.StringIO):
            largest = 0
            def write(self, value):
                self.largest = max(self.largest, len(value))
                return super().write(value)
        with AuditStore(self.root / 'fan-in.sqlite', {}, cache_bytes=0) as store:
            report = audit_core.Audit(iter([('fan-in.json', module)]), CAP, store=store).run(['root'])
            self.assertIsInstance(report['missingGlobals'], audit_core._StreamArray)
            output = Sink()
            audit_core.write_report(report, output)
            self.assertEqual(json.dumps(expected, indent=2) + '\n', output.getvalue())
            self.assertLess(output.largest, 512)
            self.assertEqual(count, store.event_count('missing'))
            self.assertEqual(0, store.cached_expression_count)

    def test_module_and_binding_objects_are_released_before_next_module(self):
        class Module(dict): pass
        class Binding(dict): pass
        references = []
        def modules():
            for index in range(4):
                gc.collect()
                self.assertTrue(all(item() is None for item in references), 'old module/binding survived')
                binding = Binding(bind('id' + str(index), lit(index)))
                module = Module(schema=1, ghc='9.14.1', bindings=[binding], constructors=[])
                references.extend([weakref.ref(module), weakref.ref(binding)])
                yield str(index), module
                del module, binding
        with AuditStore(self.root / 'retention.sqlite', {}) as store:
            auditor = audit_core.Audit(modules(), CAP, store=store)
            gc.collect()
            self.assertTrue(all(item() is None for item in references))
            self.assertEqual(4, len(auditor.bindings))

    def test_report_emission_failure_preserves_previous_output_and_partial(self):
        output = self.root / 'report.json'
        output.write_text('previous verified output')
        def broken(report, stream):
            stream.write('{"schema":2,')
            raise OSError('deliberate disk failure')
        with patch.object(audit_core, 'write_report', broken), self.assertRaises(OSError):
            audit_core._emit_report({}, output)
        self.assertEqual('previous verified output', output.read_text())
        self.assertEqual(1, len(list(self.root.glob('.report.json.*.partial'))))

    def test_automatic_catalogue_cleanup_preserves_explicit_and_failed_evidence(self):
        source, output = self.root / 'input.cbd', self.root / 'report.json'
        write_core(source, dict(schema=1, ghc='9.14.1',
            bindings=[bind('root', [*lit(0), dict(rep=LONG)])], constructors=[]))
        command = [sys.executable, str(ROOT / 'audit-core.py'), str(source)]
        options = ['--entry', 'root', '--output', str(output)]
        accepted = subprocess.run([*command, *options], capture_output=True, text=True)
        self.assertEqual(0, accepted.returncode, accepted.stderr)
        report = output.read_bytes()
        self.assertTrue(json.loads(report)['accepted'])
        self.assertEqual([], list(self.root.glob('thc-core-audit-*')))

        explicit = self.root / 'retained.sqlite'
        retained = subprocess.run([*command, *options, '--store', str(explicit)], capture_output=True, text=True)
        self.assertEqual(0, retained.returncode, retained.stderr)
        self.assertTrue(explicit.is_file())
        self.assertEqual(report, output.read_bytes())

        rejected = subprocess.run([*command, '--entry', 'absent', '--output', str(output)],
            capture_output=True, text=True)
        self.assertEqual(1, rejected.returncode, rejected.stderr)
        self.assertFalse(json.loads(output.read_bytes())['accepted'])
        self.assertEqual(1, len(list(self.root.glob('thc-core-audit-*/catalogue.sqlite'))))

        previous = output.read_bytes()
        broken = self.root / 'broken.cbd'
        broken.write_bytes(b'incomplete')
        failed = subprocess.run([*command, str(broken), *options], capture_output=True, text=True)
        self.assertEqual(2, failed.returncode, failed.stderr)
        self.assertEqual(previous, output.read_bytes())
        self.assertEqual(2, len(list(self.root.glob('thc-core-audit-*/catalogue.sqlite'))))

    def test_late_cli_parse_failure_never_replaces_report_or_seals_catalogue(self):
        first, last = self.root / 'first.cbd', self.root / 'last.cbd'
        write_core(first, dict(schema=1, ghc='9.14.1', bindings=[bind('root', [*lit(0), dict(rep=LONG)])], constructors=[]))
        last.write_text('{"incomplete":')
        output, store = self.root / 'report.json', self.root / 'failed.sqlite'
        output.write_text('previous verified output')
        result = subprocess.run([sys.executable, str(ROOT / 'audit-core.py'), str(first), str(last),
            '--entry', 'root', '--store', str(store), '--output', str(output)], capture_output=True, text=True)
        self.assertEqual(2, result.returncode, result.stderr)
        self.assertEqual('', result.stdout)
        self.assertEqual('previous verified output', output.read_text())
        with closing(sqlite3.connect(store)) as connection:
            self.assertEqual(b'"ingesting"', connection.execute("SELECT payload FROM metadata WHERE key='phase'").fetchone()[0])

    def test_loose_cli_rejects_json_in_both_modes(self):
        module = dict(schema=1, ghc='9.14.1', bindings=[bind('root', lit(0))], constructors=[], future=0)
        source = self.root / 'loose.json'
        source.write_text(json.dumps(module)[:-1] + ', "future":1}')
        for mode in ['eager', 'indexed']:
            output = self.root / (mode + '.json')
            options = ['--eager'] if mode == 'eager' else ['--store', str(self.root / 'loose.sqlite')]
            result = subprocess.run([sys.executable, str(ROOT / 'audit-core.py'), str(source), '--entry', 'root',
                '--output', str(output), *options], capture_output=True, text=True)
            self.assertEqual(2, result.returncode, result.stderr)
            self.assertIn('Core input must be CBD', result.stderr)
            self.assertFalse(output.exists())


class RetainedAuditStoreTest(unittest.TestCase):
    @unittest.skipUnless(os.environ.get('THC_AUDIT_GENUINE_MANIFEST'), 'retained genuine equivalence not requested')
    def test_exact_retained_fourway_pre_post_cli_reports(self):
        """Reuse original native-produced inputs, not a new capture or oracle."""
        manifest_path = Path(os.environ['THC_AUDIT_GENUINE_MANIFEST'])
        source_root = Path(os.environ['THC_AUDIT_GENUINE_ROOT'])
        output = Path(os.environ['THC_AUDIT_GENUINE_OUTPUT'])
        self.assertFalse(output.exists(), 'preserve previous equivalence evidence')
        manifest_bytes = manifest_path.read_bytes()
        manifest = core_package_manifest.strict_json(manifest_bytes.decode('utf-8'))
        self.assertIs(manifest.get('strictAccepted'), True, 'requires freshly accepted genuine four-way Core')
        checked = 0
        for group in ['inputHashes', 'artifactHashes']:
            for path, expected in manifest[group].items():
                candidate = (source_root / path).resolve()
                self.assertTrue(candidate.is_relative_to(source_root.resolve()))
                with candidate.open('rb') as stream:
                    self.assertEqual(expected, hashlib.file_digest(stream, 'sha256').hexdigest(), path)
                checked += 1
        output.mkdir(parents=True)
        receipt = dict(manifest=dict(path=str(manifest_path), sha256=hashlib.sha256(manifest_bytes).hexdigest()),
            checkedOriginalHashes=checked, auditorSha256=hashlib.sha256((ROOT / 'audit-core.py').read_bytes()).hexdigest(),
            nativeRows=manifest['nativeRows'], phases={})
        for phase in ['pre', 'post']:
            command_path = manifest_path.parent / 'commands' / (phase + '-audit.command.json')
            command = json.loads(command_path.read_text())['argv']
            entries = [command[index + 1] for index, value in enumerate(command) if value == '--entry']
            modules = [source_root / path for path in command[-2:]]
            self.assertTrue(all(path.name in ['FourWayAggregateFields.cbd', 'THC.InterfaceClosure.cbd'] for path in modules))
            common = [sys.executable, str(ROOT / 'audit-core.py'), *map(str, modules)]
            for entry in entries: common.extend(['--entry', entry])
            reports = []
            for mode in ['eager', 'indexed']:
                report_path = output / (phase + '-' + mode + '.json')
                options = ['--eager'] if mode == 'eager' else ['--store', str(output / (phase + '.sqlite'))]
                result = subprocess.run([*common, *options, '--output', str(report_path)], text=True, capture_output=True)
                (output / (phase + '-' + mode + '.stderr')).write_text(result.stderr)
                self.assertEqual(0, result.returncode, result.stderr)
                reports.append(report_path.read_bytes())
            self.assertEqual(*reports)
            report = json.loads(reports[0])
            self.assertTrue(report['accepted'])
            self.assertEqual(manifest['auditSummaries'][phase], report['summary'])
            receipt['phases'][phase] = dict(completeReportsEqual=True, accepted=True,
                summary=report['summary'], bytes=len(reports[0]), sha256=hashlib.sha256(reports[0]).hexdigest())
        (output / 'equivalence.json').write_text(json.dumps(receipt, indent=2) + '\n')
        print(json.dumps(receipt))


if __name__ == '__main__':
    unittest.main()
