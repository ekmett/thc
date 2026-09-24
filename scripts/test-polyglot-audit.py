#!/usr/bin/env python3
"""Exercise the exact FCallId gate without requiring a Java or JS installation."""
import json
from copy import deepcopy
from pathlib import Path
import runpy
import unittest

ROOT = Path(__file__).resolve().parent.parent
Audit = runpy.run_path(str(ROOT / 'scripts/audit-core.py'))['Audit']
CAPABILITIES = json.loads((ROOT / 'scripts/core-capabilities.json').read_text())
ABI = json.loads((ROOT / 'src/main/resources/thc/polyglot-abi.json').read_text())


def rep(register):
    return dict(kind={'AddrRep': 'address', 'IntRep': 'long', 'DoubleRep': 'double',
                      'BoxedRep (Just Lifted)': 'object', 'State# RealWorld': 'void'}[register],
                primReps=[] if register == 'State# RealWorld' else [register], evaluated=True)


def call(symbol):
    signature = ABI['operations'][symbol]
    arguments = [rep(register) for register in signature['arguments']]
    fields = [rep(register) for register in signature['result']]
    result = dict(kind='unknown', aggregate='unboxed-tuple', components=fields,
                  primReps=[register for field in fields for register in field['primReps']], evaluated=False)
    descriptor = dict(schema=1, target=dict(kind='static', symbol=symbol, unit='main', isFunction=True),
                      convention=ABI['convention'], safety=ABI['safety'], arity=len(arguments),
                      suppliedArity=len(arguments), argumentReps=deepcopy(arguments), resultRep=deepcopy(result))
    expression = ['app', ['var', 'ffi:external', {'rep': dict(kind='closure',
                        primReps=['BoxedRep (Just Lifted)'], evaluated=True)}],
                  [['var', f'arg{i}', {'rep': value}] for i, value in enumerate(arguments)],
                  [register == 'BoxedRep (Just Lifted)' for register in signature['arguments']],
                  False, False, dict(rep=result, foreignCall=descriptor)]
    bound = {f'arg{i}': value for i, value in enumerate(arguments)}
    return expression, bound


def javascript_call(source='((x) => x + 7)', inputs=('IntRep',), output='IntRep', safety='unsafe'):
    symbol = 'thc_javascript_v1_' + source.encode('utf-8').hex()
    registers = [*inputs, 'State# RealWorld']
    arguments = [rep(register) for register in registers]
    fields = [rep('State# RealWorld')] + ([] if output == 'void' else [rep(output)])
    result = dict(kind='unknown', aggregate='unboxed-tuple', components=fields,
                  primReps=[] if output == 'void' else [output], evaluated=False)
    descriptor = dict(schema=1, intrinsic='javascript-v1', javascriptSource=source,
                      target=dict(kind='static', symbol=symbol, unit='main', isFunction=True),
                      convention='ccall', safety=safety, arity=len(arguments),
                      suppliedArity=len(arguments), argumentReps=arguments, resultRep=result)
    expression = ['app', ['var', 'ffi:javascript', {'rep': dict(kind='closure',
                        primReps=['BoxedRep (Just Lifted)'], evaluated=True)}],
                  [['var', f'arg{i}', {'rep': deepcopy(value)}] for i, value in enumerate(arguments)],
                  [False] * len(arguments), False, False, dict(rep=deepcopy(result), foreignCall=descriptor)]
    bound = {f'arg{i}': deepcopy(value) for i, value in enumerate(arguments)}
    return expression, bound


class PolyglotAuditTest(unittest.TestCase):
    def audit(self, expression, bound):
        auditor = Audit([], CAPABILITIES)
        auditor.walk(expression, bound, 'test:root', '/expr')
        return auditor

    def test_each_declared_operation_is_exactly_linked(self):
        for symbol in ABI['operations']:
            with self.subTest(symbol=symbol):
                expression, bound = call(symbol)
                auditor = self.audit(expression, bound)
                self.assertEqual([], auditor.issues)
                self.assertEqual({}, auditor.missing)
                self.assertEqual([symbol], [entry['symbol'] for entry in auditor.foreign_calls])

    def test_foreign_name_alone_is_not_a_call_contract(self):
        expression, bound = call('thc_polyglot_v1_execute_int')
        del expression[6]['foreignCall']
        auditor = self.audit(expression, bound)
        self.assertIn('ffi:external', auditor.missing)
        self.assertEqual([], auditor.foreign_calls)

    def test_changed_symbol_convention_safety_arity_or_rep_is_rejected(self):
        symbol = 'thc_polyglot_v1_execute_int'
        mutations = [
            lambda e: e[6]['foreignCall']['target'].update(symbol='thc_polyglot_v1_unknown'),
            lambda e: e[6]['foreignCall'].update(convention='ccall'),
            lambda e: e[6]['foreignCall'].update(safety='unsafe'),
            lambda e: e[6]['foreignCall'].update(suppliedArity=2),
            lambda e: e[6]['foreignCall']['argumentReps'][1].update(primReps=['WordRep']),
            lambda e: e[2][1][2]['rep'].update(primReps=['WordRep']),
            lambda e: e[6]['foreignCall']['resultRep']['components'][1].update(primReps=['WordRep']),
            lambda e: e[3].__setitem__(0, False),
            lambda e: e[6]['foreignCall']['target'].update(kind='dynamic'),
            lambda e: e[6]['foreignCall'].update(intrinsic='javascript-v1', javascriptSource='1'),
        ]
        for mutate in mutations:
            with self.subTest(mutation=mutations.index(mutate)):
                expression, bound = call(symbol)
                mutate(expression)
                auditor = self.audit(expression, bound)
                self.assertTrue(any(issue['code'] == 'foreign-call' for issue in auditor.issues))
                self.assertIn('ffi:external', auditor.missing)
                self.assertEqual([], auditor.foreign_calls)

    def test_exact_javascript_source_and_scalar_io_signatures(self):
        cases = [
            ('(() => {})', (), 'void', 'safe'),
            ('(() => 7)', (), 'IntRep', 'unsafe'),
            ('((x) => x + 7)', ('IntRep',), 'IntRep', 'unsafe'),
            ('((x) => x / 2)', ('DoubleRep',), 'DoubleRep', 'safe'),
            ('((x, y) => x + y)', ('IntRep', 'DoubleRep'), 'DoubleRep', 'unsafe'),
            ('((x) => "λ" + x)', ('IntRep',), 'IntRep', 'safe'),
        ]
        for source, inputs, output, safety in cases:
            with self.subTest(source=source, output=output, safety=safety):
                expression, bound = javascript_call(source, inputs, output, safety)
                auditor = self.audit(expression, bound)
                self.assertEqual([], auditor.issues)
                self.assertEqual({}, auditor.missing)
                self.assertEqual(1, len(auditor.foreign_calls))
                self.assertEqual(source, auditor.foreign_calls[0]['javascriptSource'])
                self.assertEqual(output, auditor.foreign_calls[0]['result'])

    def test_javascript_forgery_or_non_io_signature_is_rejected(self):
        mutations = [
            lambda e: e[6]['foreignCall'].pop('intrinsic'),
            lambda e: e[6]['foreignCall'].update(intrinsic='javascript-v2'),
            lambda e: e[6]['foreignCall'].update(javascriptSource='((x) => x + 8)'),
            lambda e: e[6]['foreignCall']['target'].update(symbol='thc_javascript_v1_28287829203d3e207829'),
            lambda e: e[6]['foreignCall']['target'].update(symbol=e[6]['foreignCall']['target']['symbol'].upper()),
            lambda e: e[6]['foreignCall'].update(convention='javascript'),
            lambda e: e[6]['foreignCall'].update(safety='interruptible'),
            lambda e: e[6]['foreignCall'].update(arity=1),
            lambda e: e[6]['foreignCall'].update(suppliedArity=1),
            lambda e: e[6]['foreignCall']['argumentReps'][0].update(primReps=['WordRep']),
            lambda e: e[2][0][2]['rep'].update(primReps=['WordRep']),
            lambda e: e[3].__setitem__(0, True),
            lambda e: e[6]['foreignCall']['resultRep'].update(primReps=['DoubleRep']),
            lambda e: e[6]['foreignCall'].update(resultRep=rep('IntRep')),
            lambda e: e[6]['foreignCall']['argumentReps'].pop(),
            lambda e: e.__setitem__(1, ['var', 'arg0']),
        ]
        for index, mutate in enumerate(mutations):
            with self.subTest(index=index):
                expression, bound = javascript_call()
                mutate(expression)
                auditor = self.audit(expression, bound)
                self.assertTrue(any(issue['code'] == 'foreign-call' for issue in auditor.issues),
                                auditor.issues)
                if index != len(mutations) - 1:
                    self.assertIn('ffi:javascript', auditor.missing)
                self.assertEqual([], auditor.foreign_calls)

    def test_javascript_source_must_be_utf8_and_void_result_is_exact_singleton_tuple(self):
        expression, bound = javascript_call('(() => {})', (), 'void')
        expression[6]['foreignCall']['javascriptSource'] = '\ud800'
        auditor = self.audit(expression, bound)
        self.assertTrue(any(issue['code'] == 'foreign-call' for issue in auditor.issues))
        expression, bound = javascript_call('(() => {})', (), 'void')
        expression[6]['rep'] = rep('IntRep')
        auditor = self.audit(expression, bound)
        self.assertTrue(any(issue['code'] == 'foreign-call' for issue in auditor.issues))
        expression, bound = javascript_call('(() => {})', (), 'void')
        expression[6]['foreignCall']['resultRep'] = rep('State# RealWorld')
        auditor = self.audit(expression, bound)
        self.assertTrue(any(issue['code'] == 'foreign-call' for issue in auditor.issues))


if __name__ == '__main__':
    unittest.main()
