#!/usr/bin/env python3
"""Exercise the exact FCallId gate without requiring a Java or JS installation."""
import json
from pathlib import Path
import runpy
import unittest

ROOT = Path(__file__).resolve().parent.parent
Audit = runpy.run_path(str(ROOT / 'scripts/audit-core.py'))['Audit']
CAPABILITIES = json.loads((ROOT / 'scripts/core-capabilities.json').read_text())
ABI = json.loads((ROOT / 'src/main/resources/thc/polyglot-abi.json').read_text())


def rep(register):
    return dict(kind={'AddrRep': 'address', 'IntRep': 'long',
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
                      suppliedArity=len(arguments), argumentReps=arguments, resultRep=result)
    expression = ['app', ['var', 'ffi:external', {'rep': dict(kind='closure',
                        primReps=['BoxedRep (Just Lifted)'], evaluated=True)}],
                  [['var', f'arg{i}', {'rep': value}] for i, value in enumerate(arguments)],
                  [register == 'BoxedRep (Just Lifted)' for register in signature['arguments']],
                  False, False, dict(rep=result, foreignCall=descriptor)]
    bound = {f'arg{i}': value for i, value in enumerate(arguments)}
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
        ]
        for mutate in mutations:
            with self.subTest(mutation=mutations.index(mutate)):
                expression, bound = call(symbol)
                mutate(expression)
                auditor = self.audit(expression, bound)
                self.assertTrue(any(issue['code'] == 'foreign-call' for issue in auditor.issues))
                self.assertIn('ffi:external', auditor.missing)
                self.assertEqual([], auditor.foreign_calls)


if __name__ == '__main__':
    unittest.main()
